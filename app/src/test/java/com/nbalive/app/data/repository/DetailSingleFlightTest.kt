package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页请求的 single-flight 去重（review/02-data.md P2-8）。
 *
 * ## 要解决什么
 *
 * 详情页有 5 分钟 TTL 缓存，但缓存只挡**已完成**的请求，挡不住**正在飞**的。
 * 状态校正（4 并发）与 `loadSignals` 同时请求同一个 detailId 时，
 * 两边都会 miss 并各打一次源站 —— 用户"刷新列表还没结束就点进播放页"就是这个场景。
 *
 * 源站是免费盗播站、有严格限流（403/429 已经在代码里专门处理），
 * 这种成倍请求正是最该避免的。
 */
class DetailSingleFlightTest {

    private val repo = MatchRepository()

    private fun detailHtml() = """
        <div class="match-iframe"><iframe src="https://play.example.com/play/kbs/?id=1"></iframe></div>
        <p class="match-info-time">2026-10-23 07:00</p>
    """.trimIndent()

    private fun matchOf(id: String, detailId: String) = Match(
        id = id, league = League.NBA, leagueRawName = "NBA",
        homeTeam = "湖人", awayTeam = "凯尔特人", homeLogo = null, awayLogo = null,
        timeLabel = "07:00", startAtMillis = null, status = MatchStatus.LIVE,
        signalCount = 0, sources = emptyList(), jrsDetailId = detailId,
    )

    @Test
    fun `并发请求同一详情页只打一次源站`() = runBlocking {
        var fetches = 0
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                fetches++
                // 故意慢一点，保证并发窗口真实存在
                delay(100)
                FetchResult.Ok(detailHtml())
            } else {
                FetchResult.Ok("")
            }
        }

        // 5 个并发请求同一个 detailId（模拟状态校正 4 并发 + 播放页 1 个）
        val results = (1..5).map {
            async(Dispatchers.IO) { repo.loadSignals(matchOf("m$it", "777"), fetch) }
        }.awaitAll()

        assertEquals(
            "并发请求同一详情页必须合并成一次（实际打了 $fetches 次源站）",
            1,
            fetches,
        )
        assertTrue("每个调用方都要拿到结果", results.all { it.signals.size == 0 || it.correction == null })
    }

    @Test
    fun `并发失败不会把 detailId 永久卡住`() = runBlocking {
        // 回归风险：in-flight map 若不在 finally 里移除，
        // 一次失败会让这个场次的详情页永远等待一个已结束的 Deferred。
        var calls = 0
        val failing: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                calls++
                FetchResult.Unreachable("boom")
            } else {
                FetchResult.Ok("")
            }
        }
        repo.loadSignals(matchOf("m1", "888"), failing)
        val firstCalls = calls
        assertTrue("第一次应当尝试过", firstCalls >= 1)

        // 第二次必须能重新发起（不能被 in-flight 卡死）
        var secondCalls = 0
        repo.loadSignals(matchOf("m1", "888")) { url, _ ->
            if (url.contains("/live/")) {
                secondCalls++
                FetchResult.Ok(detailHtml())
            } else {
                FetchResult.Ok("")
            }
        }
        assertTrue(
            "上一次失败不能把 detailId 永久卡在 in-flight map 里（第二次请求数 $secondCalls）",
            secondCalls >= 1,
        )
    }

    @Test
    fun `不同 detailId 仍然各自请求`() = runBlocking {
        // 去重不能去过头：不同场次的详情页是不同内容
        val seen = mutableSetOf<String>()
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                synchronized(seen) { seen += url }
                FetchResult.Ok(detailHtml())
            } else {
                FetchResult.Ok("")
            }
        }
        repo.loadSignals(matchOf("a", "101"), fetch)
        repo.loadSignals(matchOf("b", "102"), fetch)
        assertEquals("不同场次必须各自请求", 2, seen.size)
    }
}