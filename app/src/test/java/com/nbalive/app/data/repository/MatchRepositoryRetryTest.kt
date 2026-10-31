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
        // ⚠️ 只读审计 review/audit-guards.md A11：原来的上界是**用同一份候选列表算出来的**
        //     bound = JRS_LIST.size.let { 3 + it - 1 } + JRKAN_LIST_JS.size.let { 3 + it - 1 }
        // 注释里担心的恰恰是"每个镜像都重试 3 次"被镜像数量放大 ——
        // 但断言跟着候选数量一起放大，所以永远追得上：镜像加 10 个，上界也加 10。
        // `attempts >= 1` 也近乎恒真（夹具一跑就 ≥1）。
        // 现在改成**与候选列表长度无关的硬上界**，并按 host 分组钉住"每个 host 的重试 ≤3 次"。
        // ⚠️ 必须用**线程安全**容器：两个源在 MatchRepository.loadMatchesWithOutcome 里
// 是 `async` 并发跑的（Dispatchers.IO），普通 MutableList 会丢更新 ——
// 2026-10-28 实测：并发下真实 8 次只记到 ≤7 次，于是"上界"断言看起来永远成立。
        val attemptedUrls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        repo.loadMatchesWithOutcome { url, _ ->
            attemptedUrls.add(url)
            FetchResult.Unreachable("timeout")
        }

        // 非空性锚点：夹具必须真的打到了两个源，否则下面两条上界断言都测不到东西
        val hosts = attemptedUrls.map { hostOf(it) }.distinct()
        assertTrue(
            "夹具必须真的尝试过两个源的 host（实际 ${attemptedUrls.size} 次：$attemptedUrls）—— " +
                "否则重试上限守卫会静默全绿",
            hosts.size >= 2,
        )
        assertTrue(
            "至少要尝试过 3 次（一个候选被重试满）才是有效夹具，实际 ${attemptedUrls.size} 次",
            attemptedUrls.size >= 3,
        )

        val perHost = attemptedUrls.groupingBy { hostOf(it) }.eachCount()
        val overRetried = perHost.filterValues { it > MAX_ATTEMPTS_PER_HOST }
        assertTrue(
            "同一 host 的尝试次数超过 $MAX_ATTEMPTS_PER_HOST 次（$overRetried）：\n" +
                "镜像域名存在的意义是「这个域名死了」——在死域名上反复重试纯属浪费几秒超时；\n" +
                "镜像数量增加更不能把「重试有上限」偷偷放大。",
            overRetried.isEmpty(),
        )
        assertTrue(
            "失败重试总次数超过硬上界 $MAX_TOTAL_ATTEMPTS（实际 ${attemptedUrls.size} 次：$attemptedUrls）。\n" +
                "这个上界**与候选列表长度无关**：一旦每个镜像都重试 3 次，它必须变红（审计 A11）。",
            attemptedUrls.size <= MAX_TOTAL_ATTEMPTS,
        )

        // 对照（审计 A11）：旧判据把上界写成「候选列表派生」—— 候选一变上界跟着变，没有约束力。
        // 用同一份公式演示它挡不住"每个镜像都重试 3 次"：
        val legacyDerivedBound = { jrs: Int, jrkan: Int -> (3 + jrs - 1) + (3 + jrkan - 1) }
        assertTrue(
            "旧的派生上界会随镜像数量自动放大（JRS 10 个镜像时上界=${legacyDerivedBound(10, 1)} > " +
                "$MAX_TOTAL_ATTEMPTS）—— 所以它守不住「重试有上限」；本条改用与候选数量无关的常量。",
            legacyDerivedBound(10, 1) > MAX_TOTAL_ATTEMPTS,
        )
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
        repo.loadMatchesWithOutcome(fetch = fetch)
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

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()

    private companion object {
        /**
         * 单个候选 host 的尝试次数上限（含首次）。
         *
         * 与 `SourceEndpoints.*.size` **无关**：镜像再多，每个 host 也只能试这么多次 ——
         * 审计 A11 记录的原始缺陷就是上界跟着候选列表一起放大。
         */
        const val MAX_ATTEMPTS_PER_HOST = 3

        /**
         * 全部失败时的总尝试次数硬上界（JRS 3 候选：3+1+1 = 5；JRKAN 1 候选：3 —— 共 8）。
         *
         * ⚠️ 这是**写死的常量**，不是从候选列表算出来的：候选数变化时应当由人重新审阅这个数字，
         * 而不是让断言跟着自动放宽（否则"重试有上限"永远追得上）。
         */
        const val MAX_TOTAL_ATTEMPTS = 8
    }
}
