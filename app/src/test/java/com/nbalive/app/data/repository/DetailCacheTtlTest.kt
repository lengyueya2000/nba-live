package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.FetchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页缓存 TTL 的**起点**（review/02-data.md P2-7）。
 *
 * ## 要解决的回归
 *
 * `fetchJrsDetail` 用 `detailCache[detailId] = cachedAt to body` 记录缓存年龄。
 * 旧实现把 `cachedAt` 取在 `fetchDetailBody(...)` **之前**：一次 35 秒的慢请求，
 * 正文还没回来缓存就已经"老"了 35 秒 —— 5 分钟 TTL 被请求耗时悄悄吃掉，
 * 越慢的请求缓存越短，而所有断言照样绿（改了没人红）。
 *
 * 修法：拿到正文之后才取时间戳，缓存年龄从**响应到达**开始算。
 *
 * ## 为什么这里需要反射读私有缓存
 *
 * `DETAIL_CACHE_TTL_MS` 是 `private const`、仓库也没有注入时钟的口子。
 * 纯黑盒样本必须在"请求耗时"与"TTL 300 秒"之间坐等数分钟才能看出起点差异，
 * 25 分钟预算内不现实（任务也明确说别真睡满 5 分钟）。
 * 所以判据直接读缓存里记录的时间戳，和 fetch **开始时刻**比较：
 * 修复后它应晚出约一个样本时长；若赋值被挪回 fetch 之前，它就贴着开始时刻。
 * 这只在测试侧反射，**没有为了测试改任何生产可见性**。
 *
 * 另附一条行为面复核：慢请求完成后再请求同一场，TTL 内必须命中缓存、不回源。
 */
class DetailCacheTtlTest {

    private fun detailHtml() = """
        <div class="match-iframe"><iframe src="https://play.example.com/play/kbs/?id=1"></iframe></div>
        <p class="match-info-time">2026-10-23 07:00</p>
    """.trimIndent()

    private fun matchOf(detailId: String) = Match(
        id = "m1",
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

    /** 读 private 的 `detailCache` 里该场次的缓存时间戳（毫秒，nanoTime 口径）；未缓存返回 null。 */
    private fun cachedAtOf(repo: MatchRepository, detailId: String): Long? {
        val field = MatchRepository::class.java.getDeclaredField("detailCache")
        field.isAccessible = true
        val cache = field.get(repo) as Map<*, *>
        val entry = cache[detailId] as? Pair<*, *> ?: return null
        return entry.first as? Long
    }

    @Test
    fun `慢请求的缓存年龄从拿到正文之后开始算`() = runBlocking {
        val repo = MatchRepository()
        val detailId = "9601"
        // 样本取 TTL（5 分钟）的一小段：够看出"起点在 fetch 之后"，
        // 又不会把测试拖成几分钟。
        val sampleMs = 2_000L
        var fetches = 0
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                fetches++
                delay(sampleMs)
                FetchResult.Ok(detailHtml())
            } else {
                FetchResult.Ok("")
            }
        }

        val startedAt = System.nanoTime() / 1_000_000L
        repo.loadSignals(matchOf(detailId), fetch)

        val cachedAt = cachedAtOf(repo, detailId)
        assertNotNull("慢请求成功后必须写入详情缓存", cachedAt)
        val fetchAge = cachedAt!! - startedAt
        assertTrue(
            "缓存时间戳必须在**拿到正文之后**才取（review/02-data.md P2-7）：" +
                "旧实现把 cachedAt 取在 fetch 之前，缓存年龄会被请求耗时吃掉 —— " +
                "实测时间戳距 fetch 开始只有 $fetchAge ms（样本 $sampleMs ms）",
            fetchAge >= sampleMs - 100,
        )

        // 行为面复核：慢请求完成后立刻再请求同一场，TTL 内必须命中缓存、不回源
        repo.loadSignals(matchOf(detailId), fetch)
        assertEquals(
            "同一场次在 TTL 内必须命中缓存，实际回源 $fetches 次",
            1,
            fetches,
        )
    }
}