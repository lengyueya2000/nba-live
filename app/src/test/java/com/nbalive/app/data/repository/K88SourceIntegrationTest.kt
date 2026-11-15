package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.remote.FetchResult
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 88看球作为**第三源**的端到端判据（2026-11-15）。
 *
 * ## 与 K88ParserTest 的分工
 *
 * [com.nbalive.app.data.remote.K88ParserTest] 测的是"一个源的正文怎么变成 Match/线路"；
 * 这里测的是**接进来之后**三件事是否真的成立：
 *  1. 三源都开时，88 与 JRS/JRKAN 的**同一路线路只留一条**（用户点名的需求）；
 *  2. 88 独有的裸 m3u8 线路**留下来**并被判为可直连（这是本源的全部增量价值）；
 *  3. 关掉 K88 时**一个请求都不发**（与既有两源同一条"不解析"纪律）。
 *
 * 这三条都是**跨模块**的行为，任何单模块的测试都覆盖不到 ——
 * 而恰恰是"看起来接好了"最容易骗人的地方：解析器全绿、开关全绿，
 * 播放页里却列出三条名字不同、点开是同一个上游的线路。
 */
class K88SourceIntegrationTest {

    private val repo = MatchRepository()

    // ---------------- 判据 1：跨源线路去重 ----------------

    @Test
    fun `三源都开时 88 与 JRS 的同一路云直播线路只留一条`() = runBlocking {
        // 实测数据（2026-11-15，鹈鹕 vs 掘金）：
        //  JRS 信号3  = play.sportsteam368.com/play/kbs/?id=10022600231
        //  88 国内线路 Q ⑤ = play.sportsteam368.com/play/kbs/?id=10022600231  ← 整串相同
        //  JRKAN 中文高清 Q ⑤ = play.sportsteam**356**.com/play/kbs/?id=10022600231 ← 只换 host
        // 三条是同一路流（356/368 是同后端镜像，已验证）。
        val merged = repo.mergeForTest(
            jrs = listOf(matchWith(
                id = "jrs-364357",
                provider = Provider.JRS,
                urls = listOf(
                    "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=",
                    "http://play.sportsteam368.com/play/mgxl/?id=00588403&id2=",
                ),
            )),
            jrkan = listOf(matchWith(
                id = "jrkan-3931673",
                provider = Provider.JRKAN,
                urls = listOf(
                    "http://play.sportsteam356.com/play/kbs/?id=10022600231&id2=",
                    "http://play.sportsteam356.com/play/pao/?id=39316732&id2=",
                ),
            )),
            k88 = listOf(matchWith(
                id = "k88-131401",
                provider = Provider.K88,
                urls = listOf(
                    "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=",
                    "http://play.sportsteam368.com/play/mgxl/?id=00588403&id2=",
                    "http://play.sportsteam368.com/play/pao/?id=39316732&id2=",
                    "http://play.sportsteam368.com/play/oo/?id=3931673o&id2=",
                    "http://play.sportsteam368.com/play/vxt/?id=10022600231&id2=",
                    // 本源独有：裸 m3u8 代理壳
                    "https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8",
                ),
            )),
        )

        val single = merged.single()
        val kept = single.sources.map { it.pageUrl }
        // 期望：kbs/mgxl/pao/oo/vxt 各一条 + m3u8 壳一条 = 6
        assertEquals("去重后的线路：$kept", 6, kept.size)
        assertTrue(
            "同 host 的 kbs 必须只剩一条（旧实现按整串 URL 去重时这里是 2~3 条）",
            kept.count { it.contains("/play/kbs/") } == 1,
        )
        assertTrue(
            "88 独有的 m3u8 壳不许被误当重复删掉 —— 那是本源唯一的增量价值",
            kept.any { it.contains("88player.top") },
        )
    }

    @Test
    fun `去重保留的是先出现的那条 也就是既有源的线路`() = runBlocking {
        // 保留谁的线路**不是**随便定的：先出现的来自既有源（JRS/JRKAN），
        // 它们的地址被两个源的既有解析链路验证过；88 的地址形态更杂。
        // 若反过来保留 88 那条，同一路线路的"来源"就变了，
        // 用户关掉 JRS 时它会被 allowedSignals 一起滤掉（开关等于误伤）。
        val merged = repo.mergeForTest(
            jrs = listOf(matchWith(
                id = "jrs-1", provider = Provider.JRS,
                urls = listOf("http://play.sportsteam368.com/play/kbs/?id=1&id2="),
            )),
            jrkan = emptyList(),
            k88 = listOf(matchWith(
                id = "k88-1", provider = Provider.K88,
                urls = listOf("http://play.sportsteam368.com/play/kbs/?id=1&id2="),
            )),
        )
        val survivors = merged.single().sources.filter {
            it.pageUrl.contains("/play/kbs/?id=1")
        }
        assertEquals(1, survivors.size)
        assertEquals(
            "同一路线路必须保留既有源那条（否则关掉 JRS 会连带消失）",
            Provider.JRS,
            survivors.single().provider,
        )
    }

    // ---------------- 判据 3：关掉就不解析 ----------------

    @Test
    fun `关掉 K88 后完全不请求它的域名`() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = repo.loadMatchesWithOutcome(enabled = setOf(Provider.JRS, Provider.JRKAN)) { url, _ ->
            synchronized(seen) { seen += url }
            when {
                url.contains("jrszbt") -> FetchResult.Ok(jrsListHtml())
                // ⚠️ 每个源拿**自己那份正文**：JRKAN 落到 else 拿到 88 的 HTML
                // 会解析归零，于是"其余源照常返回"这条断言测的是夹具而非行为。
                url.contains("oss-accelerate") -> FetchResult.Ok(jrkanJs())
                else -> FetchResult.Ok(k88ListHtml())
            }
        }
        assertTrue(
            "用户说的是「不解析」，不是「解析完再过滤」：$seen",
            seen.none { it.contains("88kanqiu") || it.contains("88zhibo") },
        )
        assertTrue("其余源照常返回", outcome.matches.isNotEmpty())
        assertTrue(
            "关掉的源不许被报成失败源（那是用户自己关的）",
            outcome.failedProviders.none { it == Provider.K88 },
        )
    }

    @Test
    fun `三源都开时 88 真的被请求且场次并卡`() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            synchronized(seen) { seen += url }
            when {
                url.contains("jrszbt") -> FetchResult.Ok(jrsListHtml())
                url.contains("/match/") -> FetchResult.Ok(k88ListHtml())
                // ⚠️ 88 必须**先于** else 分支单独路由：写成 `else -> jrkanJs()`
                // 会把 88 的列表正文喂成 JRKAN 的 JS（或反之），夹具自己制造解析失败。
                url.contains("oss-accelerate") -> FetchResult.Ok(jrkanJs())
                else -> FetchResult.Unreachable("unexpected $url")
            }
        }
        assertTrue(
            "默认全开时 88 必须真的被解析（实际请求：$seen）—— " +
                "否则第三源只是 SourceEndpoints 里的一行摆设",
            seen.any { it.contains("88kanqiu") },
        )
        // 三个源给的是同一对阵（湖人 vs 凯尔特人 / 同一天），必须并成一张卡
        assertEquals(
            "同对阵跨三源必须并成一张卡（实际 ${outcome.matches.map { it.id }}）",
            1,
            outcome.matches.size,
        )
        val card = outcome.matches.single()
        // ⚠️ 断"并卡"而不是断"三个 provider 都在 sources 里"：
        // JRS 的**列表行**按设计 sources 恒空（线路在详情页才展开，signalCount=-1），
        // 上一版这么写就是夹具骗人 —— 它红的原因是断言本身不成立，不是接线坏了。
        // 这里用三个可观测的锚点覆盖"三源都真的进了同一张卡"：
        assertTrue("JRKAN 的播放页线路必须在卡里：${card.sources}", card.sources.any { it.provider == Provider.JRKAN })
        assertTrue("88 的线路接口入口必须在卡里：${card.sources}", card.sources.any { it.provider == Provider.K88 })
        assertNotNull("JRS 的详情页 id 必须回填（关掉 JRS 后靠它展开线路）", card.jrsDetailId)
        assertTrue("三源都成功时不该报失败源：${outcome.failedProviders}", outcome.failedProviders.isEmpty())
    }

    // ---------------- 判据 2：线路展开（详情页） ----------------

    @Test
    fun `详情页展开 88 的线路接口拿到裸 m3u8 并判为可直连`() = runBlocking {
        val match = matchWith(
            id = "k88-131401", provider = Provider.K88,
            urls = listOf("https://www.88kanqiu.cx/live/131401/source"),
        )
        val detail = repo.loadSignals(match) { url, _ ->
            when {
                url.endsWith("/source") -> FetchResult.Ok(sourcePayload())
                else -> FetchResult.Unreachable("unexpected $url")
            }
        }
        val labels = detail.signals.map { it.label }
        assertTrue("必须展开出线路（实际 $labels）", detail.signals.isNotEmpty())
        assertTrue(
            "88 独有壳线路要能直连（实际 ${detail.signals.map { it.pageUrl }}）",
            detail.signals.any { it.isDirectHls && it.pageUrl.contains("88player.top") },
        )
        // 与 JRS/JRKAN 同一路的线路由 LineEquivalence 收敛：本源内部也去重
        assertEquals(
            "同一 host+player+id 的线路在列表内不许重复（实际 $labels）",
            detail.signals.size,
            detail.signals.map { com.nbalive.app.data.remote.LineEquivalence.key(it.pageUrl) }.distinct().size,
        )
    }

    @Test
    fun `88 线路接口主域名不可达时改用镜像`() = runBlocking {
        val tried = mutableListOf<String>()
        val match = matchWith(
            id = "k88-131401", provider = Provider.K88,
            urls = listOf("https://www.88kanqiu.cx/live/131401/source"),
        )
        val detail = repo.loadSignals(match) { url, _ ->
            tried += url
            if (url.contains("88kanqiu.cx")) FetchResult.Unreachable("dns 失败")
            else FetchResult.Ok(sourcePayload())
        }
        assertTrue(
            "主域名不可达时必须按候选改试镜像（实际请求：$tried）",
            tried.any { it.contains("88kanqiu.tw") || it.contains("88zhibo.me") },
        )
        assertTrue("镜像上的线路接口照样要展开出线路", detail.signals.isNotEmpty())
    }

    @Test
    fun `线路接口被限流时不试镜像并进入冷却`() = runBlocking {
        val tried = mutableListOf<String>()
        val match = matchWith(
            id = "k88-131401", provider = Provider.K88,
            urls = listOf("https://www.88kanqiu.cx/live/131401/source"),
        )
        repo.loadSignals(match) { url, _ ->
            tried += url
            FetchResult.Rejected(403)
        }
        assertEquals(
            "403 只允许打一次，不能换域名继续（镜像共享限流窗口）—— 实际 $tried",
            1,
            tried.size,
        )
    }

    // ---------------- 夹具 ----------------

    private fun matchWith(id: String, provider: Provider, urls: List<String>) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "凯尔特人",
        homeLogo = null,
        awayLogo = null,
        // 三个源用**同一个 timeLabel**：合并键含日期，写歪一个字符就会拆成两张卡，
        // 那正是本文件要暴露的缺陷，绝不能由夹具自己制造。
        timeLabel = "10-23 07:30",
        startAtMillis = dayMillis(),
        status = MatchStatus.LIVE,
        signalCount = urls.size,
        sources = urls.mapIndexed { i, u ->
            StreamSource(id = "$id-s$i", label = "线路${i + 1}", provider = provider, pageUrl = u)
        },
    )

    private fun dayMillis(): Long =
        java.time.LocalDate.of(2026, 10, 23)
            .atTime(7, 30)
            .atZone(com.nbalive.app.data.model.MatchStatusClock.SOURCE_ZONE)
            .toInstant().toEpochMilli()

    private fun jrsListHtml() = """
        <div class="list"><p class="list-title">2026-10-23</p>
        <a href="/live/555.html" class="list-con">
          <div class="list-team-con"><p class="list-team-name">湖人</p></div>
          <div class="list-team-con"><p class="list-team-name">凯尔特人</p></div>
          <p class="list-info-league">NBA</p><p class="list-info-time">07:30</p>
        </a></div>
    """.trimIndent()

    private fun jrkanJs() = """
        document.write('<ul class="item play active" data-lid="900,2,900">');
        document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
        document.write('  <li class="lab_time">10-23 07:30</li>');
        document.write('  <li class="lab_team_home"><strong class="name">湖人</strong></li>');
        document.write('  <li class="lab_team_away"><strong class="name">凯尔特人</strong></li>');
        // ⚠️ 必须带 getPlayUrl：JRKAN 的列表源就是靠它产出**一条播放页线路**的，
        // 缺了它这份夹具的 sources 恒空，"三源都接到同一张卡"就测不到 JRKAN 那一半。
        document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line1", "824761") + '"></a></li>');
        document.write('</ul>');
    """.trimIndent()

    private fun k88ListHtml() = """
        <ul class="list-group">
          <li class="list-group-item live-list-group-time">2026-10-23</li>
          <li class="list-group-item group-game-item">
            <div class="row">
              <div class="col-md-1 category-game-time">07:30</div>
              <div class="col-md-3"><div><span class="game-type">NBA</span></div></div>
              <div class="col-md-4"><span class="team-name" title="湖人">湖人</span></div>
              <div class="col-md-1">VS</div>
              <div class="col-md-3"><span class="team-name" title="凯尔特人">凯尔特人</span></div>
              <div><a href='/live/131401/play'>直播中</a></div>
            </div>
          </li>
        </ul>
    """.trimIndent()

    /** 真实形态：`{"data":"<乱码头6 + base64(JSON) + 乱码尾2>"}`（实测 131401 那场）。 */
    private fun sourcePayload(): String {
        val inner = """
            {"shareStatus":0,"links":[
              {"url":"http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=","name":"国内线路 Q ⑤","shareLock":0},
              {"url":"http://play.sportsteam368.com/play/mgxl/?id=00588403&id2=","name":"国内线路 M⑥","shareLock":0},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8","name":"腾讯超清1","shareLock":0},
              {"url":"https://taifood-blog.asia/embed/nba/2026-10-23/lal-bos","name":"HD | Home","shareLock":0}
            ]}
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8))
        return """{"data":"lfLsjR${b64}8H"}"""
    }
}
