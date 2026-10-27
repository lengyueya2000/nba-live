package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.FetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 列表拉取的重试策略。
 *
 * 背景：源站会对频繁请求回 403/429（限流）。早先的实现把**所有**失败都当成
 * "瞬时抖动"重试 3 次，退避只有 400ms / 800ms——被限流时反而在 1.2 秒内
 * 追加 3 倍请求，让限流窗口越拖越长。
 *
 * 现在区分两类失败：
 *  - [FetchResult.Rejected]：源站明确拒绝（403/429）→ **不重试**
 *  - [FetchResult.Unreachable]：网络层失败（超时、DNS 抖动）→ **值得重试**
 */
class MatchRepositoryRetryTest {

    private val repo = MatchRepository()

    private fun jrsHtml(id: String) = """
        <div class="list"><p class="list-title">2026-10-23</p>
        <a href="/live/$id.html" class="list-con">
          <div class="list-team-con"><p class="list-team-name">湖人</p></div>
          <div class="list-team-con"><p class="list-team-name">凯尔特人</p></div>
          <p class="list-info-league">NBA</p><p class="list-info-time">直播中</p>
        </a></div>
    """.trimIndent()

    @Test
    fun `源站限流 403 时不重试`() = runBlocking {
        // ⚠️ 走**生产管线** loadMatchesWithOutcome（review 遗留问题）：
        // 这些重试纪律测试原来测的是 loadMatchesInternal ——
        // 那条路径在生产代码里零调用点（生产走 loadMatchesOutcome）。
        // 测试守着一份线上跑不到的代码，是典型的"假安全感"。
        // 现在改为直接测生产入口，纪律才真正被守住。
        var listAttempts = 0
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) {
                if (url.endsWith("/")) listAttempts++
                // 模拟源站限流：直接拒绝
                FetchResult.Rejected(403)
            } else {
                FetchResult.Ok(jrsHtml("555"))
            }
        }

        assertEquals(
            "403 属于明确拒绝，重试只会加剧限流，列表页必须只请求一次",
            1,
            listAttempts,
        )
        assertTrue(
            "被拒绝时要如实报出失败源（而不是静默当成'今天没比赛'）",
            outcome.failedProviders.contains(com.nbalive.app.data.model.Provider.JRS),
        )
    }

    @Test
    fun `429 限流同样不重试`() = runBlocking {
        var listAttempts = 0
        repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) {
                if (url.endsWith("/")) listAttempts++
                FetchResult.Rejected(429)
            } else {
                FetchResult.Unreachable(null)
            }
        }
        assertEquals("429 也不该重试", 1, listAttempts)
    }

    @Test
    fun `网络故障 超时 DNS抖动 仍然重试`() = runBlocking {
        var attempts = 0
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) {
                attempts++
                if (attempts == 1) FetchResult.Unreachable("timeout") else FetchResult.Ok(jrsHtml("555"))
            } else {
                FetchResult.Unreachable("timeout")
            }
        }

        assertTrue("网络抖动应该重试", attempts >= 2)
        assertTrue(
            "重试成功后必须仍能拿到赛事（实际: ${outcome.matches.map { it.id }}）",
            outcome.matches.any { it.id == "jrs-555" },
        )
    }

    @Test
    fun `重试次数有上限 不会无限打源站`() = runBlocking {
        var attempts = 0
        repo.loadMatchesWithOutcome { _, _ ->
            attempts++
            FetchResult.Unreachable("timeout")
        }
        // 上界按结构算，不硬编码：每源 = 首候选拉满 3 次重试 + 每个镜像域名 1 次。
        // （候选列表见 SourceEndpoints：域名候选让镜像也参与，但绝不能每个都重试 3 次，
        //  否则"重试有上限"就被镜像数量偷偷放大了。）
        val bound = SourceEndpoints.JRS_LIST.size.let { 3 + it - 1 } +
            SourceEndpoints.JRKAN_LIST_JS.size.let { 3 + it - 1 }
        assertTrue(
            "失败重试次数必须有上限（≤$bound），实际请求了 $attempts 次",
            attempts <= bound,
        )
        assertTrue("至少要尝试过一次", attempts >= 1)
    }

    @Test
    fun `同一个详情页在缓存期内只拉一次`() = runBlocking {
        // 回归：详情页在"刷新列表"和"点进播放页"里各被拉一次，
        // 同一个 URL 在一条操作链里打源站 2~3 次。
        var detailFetches = 0
        val detailHtml = """
            <div class="match-iframe"><iframe src="https://play.example.com/play/kbs/?id=1"></iframe></div>
            <p class="match-info-time">2026-10-23 07:00</p>
        """.trimIndent()

        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                detailFetches++
                FetchResult.Ok(detailHtml)
            } else {
                FetchResult.Ok(jrsHtml("555"))
            }
        }

        // 第一次：列表状态校正会拉详情页（走生产管线）
        repo.loadMatchesWithOutcome(fetch)
        val afterFirst = detailFetches

        // 第二次：进播放页 loadSignals 拉**同一个**详情页
        val m = Match(
            id = "jrs-555",
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
            jrsDetailId = "555",
        )
        repo.loadSignals(m, fetch)

        assertEquals(
            "详情页在缓存期内应只拉一次（实际拉了 $detailFetches 次）",
            afterFirst,
            detailFetches,
        )
    }

    @Test
    fun `详情页缓存有条数上限 不会无限增长`() = runBlocking {
        // MatchRepository 跟着 ViewModel 活整个进程，
        // 缓存必须有上限，否则一天下来几百个 URL 会一直堆着。
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) FetchResult.Ok(detailHtml()) else FetchResult.Ok(jrsHtml("555"))
        }
        // 拉 200 个不同场次的详情页
        repeat(200) { i ->
            repo.loadSignals(
                matchOf("jrs-$i", "jrsDetailId" to "$i"),
                fetch,
            )
        }
        val cacheSize = detailCacheSize(repo)
        assertTrue(
            "详情页缓存必须有上限，实际存了 $cacheSize 条",
            cacheSize <= 64,
        )
    }

    @Test
    fun `用户点刷新线路时绕过缓存 真的回源`() = runBlocking {
        // 回归：5 分钟 TTL 内点"刷新线路"拿到的是同一份旧 HTML，
        // 源站后补的线路永远看不到 —— 按钮成了摆设。
        // 用户显式刷新必须清掉该场缓存。
        var detailFetches = 0
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                detailFetches++
                FetchResult.Ok(detailHtml())
            } else {
                FetchResult.Ok(jrsHtml("555"))
            }
        }
        val m = matchOf("jrs-555", "jrsDetailId" to "555")

        // 第一次进场：走缓存路径
        repo.loadSignals(m, fetch)
        val afterFirst = detailFetches

        // 第二次进场（未过期）：命中缓存，不回源
        repo.loadSignals(m, fetch)
        assertEquals("缓存期内不该重复请求", afterFirst, detailFetches)

        // 用户显式点"刷新线路"：清缓存 -> 必须回源
        repo.invalidateDetailCache("555")
        repo.loadSignals(m, fetch)
        assertEquals(
            "用户显式刷新必须真的回源，否则源站后补的线路永远看不到",
            afterFirst + 1,
            detailFetches,
        )
    }

    private fun detailHtml() = """
        <div class="match-iframe"><iframe src="https://play.example.com/play/kbs/?id=1"></iframe></div>
        <p class="match-info-time">2026-10-23 07:00</p>
    """.trimIndent()

    private fun matchOf(id: String, vararg pairs: Pair<String, String>) = Match(
        id = id,
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
        jrsDetailId = pairs.first().second,
    )

    private fun detailCacheSize(repo: MatchRepository): Int {
        val f = MatchRepository::class.java.getDeclaredField("detailCache")
        f.isAccessible = true
        return (f.get(repo) as Map<*, *>).size
    }
}
