package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.JrkanParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * [SourceEndpoints] 的**接线**验证（review/02-data.md P1-9 第二半）。
 *
 * ## 为什么单独一个文件
 *
 * 上一轮把域名候选列表建起来了，`SourceEndpointsTest` 也全绿 ——
 * 但那组测试只证明「列表本身声明得对」（非空、合法 URL、顺序对），
 * **完全没有证明有人真的用它**。
 *
 * 事实是当时 `PLAY_HOSTS` 在 `app/src/main/java` 里**零消费点**：
 * 只有它自己的声明和测试引用了它，而 `JrkanParser.PLAY_HOST` 依旧是单值常量，
 * `MatchRepository.fetchJrsDetail` 也依旧把 `jrszbt.com` 写死。
 * 这正是审查反复点名的「测试写了但没测住 / 假安全感」：
 * 候选列表躺在那里，源站一换域名，播放页与详情页照旧只敲一个死域名。
 *
 * 所以这里测两件事：
 * 1. **行为**：主域名失效时，详情页与 JRKAN 播放页真的会走下一个候选；
 * 2. **接线**：候选常量在生产代码里必须有消费点（源码扫描，同
 *    [com.nbalive.app.data.remote.LoggingDisciplineTest] 的思路）。
 */
class SourceEndpointsWiringTest {

    private val repo = MatchRepository()

    // ---------- 行为：JRS 详情页的域名候选 ----------

    @Test
    fun `详情页主域名不可达时改用镜像域名`() = runBlocking {
        val tried = mutableListOf<String>()
        val m = jrsMatch(detailId = "555")

        val detail = repo.loadSignals(m) { url, _ ->
            tried += url
            if (url.contains("jrszbt.com")) {
                FetchResult.Unreachable("dns 失败")
            } else if (url.contains("/live/")) {
                FetchResult.Ok(jrsDetailHtml())
            } else {
                FetchResult.Unreachable("unexpected")
            }
        }

        assertTrue(
            "主域名不可达时必须按候选顺序改试镜像域名（实际请求：$tried）",
            tried.any { it.contains("jrszb444.com") || it.contains("jrszb222.com") },
        )
        assertEquals(
            "详情页拿到正文就该有 JRS 信号，不能因为主域名死了就整场没信号",
            1,
            detail.signals.size,
        )
    }

    @Test
    fun `详情页被限流时不试镜像域名`() = runBlocking {
        // 403/429 是限流信号：镜像与主域名同属一个站点家族，大概率共享限流窗口，
        // 继续打只是把窗口拖更长 —— 与列表抓取同一条纪律。
        val tried = mutableListOf<String>()
        repo.loadSignals(jrsMatch(detailId = "555")) { url, _ ->
            tried += url
            FetchResult.Rejected(403)
        }
        assertEquals("403 只允许打一次，不能换域名继续", 1, tried.size)
    }

    @Test
    fun `详情页缓存按场次失效 镜像域名下缓存过的也要清掉`() = runBlocking {
        // 回归：旧实现把缓存 key 写成拼死的 URL（jrszbt.com），
        // 而"刷新线路"也按同一个写死 URL 去 remove —— 一旦正文是从镜像域名拿到的，
        // 缓存 key 就与 remove 的 key 对不上，用户点「刷新线路」会命中旧缓存，
        // 源站后补的线路永远看不到，且**不报错**。
        var detailFetches = 0
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                detailFetches++
                if (url.contains("jrszbt.com")) FetchResult.Unreachable("dns 失败")
                else FetchResult.Ok(jrsDetailHtml())
            } else {
                FetchResult.Unreachable("unexpected")
            }
        }
        val m = jrsMatch(detailId = "555")

        repo.loadSignals(m, fetch)
        val afterFirst = detailFetches
        assertTrue("首次应当真的请求了详情页", afterFirst >= 1)

        repo.loadSignals(m, fetch)
        assertEquals("缓存期内不该重复请求", afterFirst, detailFetches)

        repo.invalidateDetailCache("555")
        repo.loadSignals(m, fetch)
        assertTrue(
            "用户显式刷新必须真的回源（哪怕上次是从镜像域名拿到的）",
            detailFetches > afterFirst,
        )
    }

    // ---------- 行为：JRKAN 播放页的播放主机候选 ----------

    @Test
    fun `播放页主域名不可达时改用候选播放主机`() = runBlocking {
        val tried = mutableListOf<String>()
        val m = jrkanMatch(primaryPlayHost)

        val detail = repo.loadSignals(m) { url, _ ->
            tried += url
            if (url.contains("play.sportsteam356.com")) {
                FetchResult.Unreachable("主机已下线")
            } else {
                FetchResult.Ok(channelsHtml())
            }
        }

        assertTrue(
            "主播放主机不可达时必须改试候选主机（实际请求：$tried）",
            tried.any { it.contains("play.sportsteam368.com") },
        )
        assertEquals("候选主机上的播放页应当被展开成线路", 1, detail.signals.size)
        assertTrue(
            "线路地址必须指向真正可用的主机，否则播放器还是去敲死域名（实际：${detail.signals.map { it.pageUrl }}）",
            detail.signals.first().pageUrl.contains("play.sportsteam368.com"),
        )
    }

    @Test
    fun `播放页候选尝试次数有上限`() = runBlocking {
        var playFetches = 0
        repo.loadSignals(jrkanMatch(primaryPlayHost)) { url, _ ->
            if (url.contains("/play/")) playFetches++
            FetchResult.Unreachable("全挂")
        }
        assertTrue(
            "候选主机数量必须给请求次数设上界，不能每个候选都跑满重试（实际 $playFetches 次）",
            playFetches <= SourceEndpoints.PLAY_HOSTS.size,
        )
    }

    @Test
    fun `播放页取到正文但线路全被主播过滤时不退回入口页`() = runBlocking {
        // 这一场只剩主播线路（用户要求过滤掉）。
        // 退回列表页给的入口页 = 用 WebView 打开 steam<id>.html，源站 JS 会自动
        // 选中它自己的第一条（就是主播线路）—— 等于把刚过滤掉的内容又播回来。
        val detail = repo.loadSignals(jrkanMatch(primaryPlayHost)) { _, _ ->
            FetchResult.Ok(sensitiveOnlyChannelsHtml())
        }

        assertEquals(
            "主播线路必须一条都不剩；退回入口页会绕过过滤（实际：${detail.signals.map { it.pageUrl }}）",
            0,
            detail.signals.size,
        )
    }

    @Test
    fun `播放页一次都没取到正文时仍退回入口页`() = runBlocking {
        // 取不到正文 = 我们不知道里面有什么，此时入口页还有 WebView 兜底价值
        val detail = repo.loadSignals(jrkanMatch(primaryPlayHost)) { _, _ ->
            FetchResult.Unreachable("全挂")
        }

        assertEquals(1, detail.signals.size)
        assertTrue(
            "兜底线路必须是列表页给的那个入口页，实际：${detail.signals.map { it.pageUrl }}",
            detail.signals.single().pageUrl == "$primaryPlayHost/play/steam824761.html",
        )
    }

    // ---------- 接线守卫：候选常量必须有生产消费点 ----------

    @Test
    fun `候选常量在生产代码里真的有消费点`() {
        val root = sourceRoot()
        // ⚠️ 硬失败而不是跳过（审计 A10，这正是上一轮 PLAY_HOSTS 零消费点漏网的那条守卫）：
        // 源码根解析不到时它会"零违规"地静默全绿，而它的全部价值就是"证明有人真的在用"。
        assertTrue(
            "找不到 app/src/main/java（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时候选常量的接线守卫会静默全绿，所以直接失败而不是跳过",
            root != null,
        )
        val productionFiles = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SourceEndpoints.kt" }
            .toList()
        assertTrue(
            "生产源码只扫到 ${productionFiles.size} 个文件 —— 范围走偏时这条守卫会静默全绿，" +
                "所以直接失败而不是跳过",
            productionFiles.size >= 20,
        )

        // ⚠️ 检查的是**访问入口**而不是 PLAY_HOSTS 这个字段名本身：
        // 候选列表按设计由本对象自己的访问函数消费
        // （playPageCandidates / jrsDetailUrls 内部读 PLAY_HOSTS、JRS_LIST），
        // 所以"有没有人用"要看**访问函数有没有人调**。
        // 上一轮的缺陷正是：PLAY_HOSTS 与 jrsDetailUrls 连访问函数都没有，
        // 生产代码零调用 —— 候选列表再合法也只是摆设。
        val entryPoints = mapOf(
            "SourceEndpoints.JRS_LIST" to "列表页域名候选",
            "SourceEndpoints.JRKAN_LIST_JS" to "JRKAN 列表 js 候选",
            "SourceEndpoints.playPageCandidates" to "播放主机候选（消费 PLAY_HOSTS）",
            "SourceEndpoints.jrsDetailUrls" to "详情页域名候选（消费 JRS_LIST）",
        )
        val missing = entryPoints.filter { (symbol, _) ->
            productionFiles.none { f -> f.readText().contains(symbol) }
        }
        assertTrue(
            "以下候选入口在生产代码（SourceEndpoints.kt 之外）零调用 —— " +
                "源站换域名时它们不会生效，只是给人「已经有兜底」的错觉：\n" +
                missing.entries.joinToString("\n") { "  ${it.key}（${it.value}）" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `候选访问函数真的覆盖了候选列表 而不是空壳`() {
        // 防"访问函数存在但返回空列表"这种更隐蔽的假安全感：
        // 上一轮守卫只看"有没有人调"，调用点存在但候选为空照样全绿。
        assertTrue("播放主机候选至少要有两个镜像才谈得上'换域名'", SourceEndpoints.PLAY_HOSTS.size >= 2)
        assertTrue(
            "详情页候选必须覆盖列表的全部域名",
            SourceEndpoints.jrsDetailUrls("555").size == SourceEndpoints.JRS_LIST.size,
        )
        assertTrue(
            "播放页候选必须真的带上候选主机",
            SourceEndpoints.playPageCandidates("${SourceEndpoints.PLAY_HOSTS.first()}/play/steam1.html")
                .any { it.contains(SourceEndpoints.PLAY_HOSTS.last()) },
        )
    }

    @Test
    fun `解析器拼出的播放页地址落在候选列表首位`() {
        // 防两处各写一份主机字符串而漂移：解析器拼播放页时用的 host
        // 必须就是候选列表的首位，否则"换域名"从列表页这一步就断了。
        val js = """
            document.write('<ul class="item play active" data-lid="3931527,2,3931527">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">10-22 07:00</li>');
            document.write('  <li class="lab_team_home"><strong class="name">凯尔特人</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">老鹰</strong></li>');
            document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line1", "824761") + '"></a></li>');
            document.write('</ul>');
        """.trimIndent()

        val source = JrkanParser().parseList(js).first().sources.first()
        assertTrue(
            "解析器拼的播放页地址必须用候选首位主机，实际：${source.pageUrl}",
            source.pageUrl.startsWith(SourceEndpoints.PLAY_HOSTS.first()),
        )
    }

    // ---------- 夹具 ----------

    private val primaryPlayHost = SourceEndpoints.PLAY_HOSTS.first()

    private fun jrsMatch(detailId: String) = Match(
        id = "jrs-$detailId",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "凯尔特人",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "07:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = detailId,
    )

    private fun jrkanMatch(playHost: String) = Match(
        id = "jrkan-1",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "凯尔特人",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-21 19:35",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 1,
        sources = listOf(
            StreamSource(
                id = "jrkan-1-s0",
                label = "信号1",
                provider = Provider.JRKAN,
                pageUrl = "$playHost/play/steam824761.html",
            ),
        ),
    )

    private fun jrsDetailHtml() = """
        <div class="match-info">
          <a class="toLive" data-url="https://jrszbt.com/play/steam999.html">信号1</a>
        </div>
    """.trimIndent()

    private fun channelsHtml() =
        """<a class="item" data-play="/play/sm.html?id=439&id2="><strong>线路1</strong></a>"""

    /** 播放页里只有主播线路（用户要求过滤掉的那一类）。 */
    private fun sensitiveOnlyChannelsHtml() =
        """<a class="item" data-play="/play/sm.html?id=439&id2="><strong>主播解说①</strong></a>"""

    /** 定位 `app/src/main/java/com/nbalive/app`；单测工作目录是模块目录。 */
    private fun sourceRoot(): File? {
        val candidates = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }
}