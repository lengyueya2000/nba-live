package com.nbalive.app.data.repository

import com.nbalive.app.data.remote.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录像加载判据（2026-11-25 新增）。
 *
 * `MatchRepository.loadReplays` 复用窗口赛程挑篮球完赛场次，
 * 再按 matchId 并发拉 `/api/v1/replays?matchId=` 的清晰度线路：
 *  - 单场详情失败不能带走其它场次（失败隔离）；
 *  - 窗口里没有可回放场次时返回空表（不是错误）；
 *  - 线路按 sort 排，卡面保留真实比分。
 */
class ReplayLoadTest {

    private val repo = MatchRepository()

    @Test
    fun `窗口里的篮球完赛场次被填充清晰度线路`() = runBlocking {
        val items = repo.loadReplays { url, _ ->
            when {
                url.contains("/matches/window") -> FetchResult.Ok(windowBody())
                url.contains("/replays?matchId=2451") -> FetchResult.Ok(replayBody("2451"))
                url.contains("/replays?matchId=2452") -> FetchResult.Ok(replayBody("2452"))
                else -> FetchResult.NotFound(404)
            }
        }
        assertEquals(2, items.size)
        val first = items.first { it.matchId == "2451" }
        assertEquals("老鹰", first.homeTeam)
        assertEquals(107, first.homeScore)
        assertEquals(listOf("标清回放", "中文高清回放"), first.lines.map { it.label })
        assertTrue(first.lines.all { it.url.endsWith(".m3u8") })
    }

    @Test
    fun `单场录像详情失败只让那场没有线路 不影响其它场次`() = runBlocking {
        val items = repo.loadReplays { url, _ ->
            when {
                url.contains("/matches/window") -> FetchResult.Ok(windowBody())
                url.contains("/replays?matchId=2451") -> FetchResult.Unreachable("timeout")
                url.contains("/replays?matchId=2452") -> FetchResult.Ok(replayBody("2452"))
                else -> FetchResult.NotFound(404)
            }
        }
        assertEquals(2, items.size)
        assertTrue("失败的那场卡片仍在（保留比分），只是没有线路",
            items.first { it.matchId == "2451" }.lines.isEmpty())
        assertEquals(2, items.first { it.matchId == "2452" }.lines.size)
    }

    @Test
    fun `窗口没有可回放场次时返回空表`() = runBlocking {
        val items = repo.loadReplays { url, _ ->
            if (url.contains("/matches/window")) FetchResult.Ok("""{"items":[]}""")
            else FetchResult.NotFound(404)
        }
        assertTrue(items.isEmpty())
    }

    // ---------- 夹具 ----------

    private fun windowBody() = """
    {"items":[
      {"id":2451,"league":"NBA","sport":"篮球","status":"finished","replayId":8490,
       "home":{"name":"老鹰"},"away":{"name":"活塞"},
       "startsAt":"2026-11-22T20:30:00Z","homeScore":107,"awayScore":97},
      {"id":2452,"league":"NBA","sport":"篮球","status":"finished","replayId":8498,
       "home":{"name":"热火"},"away":{"name":"雷霆"},
       "startsAt":"2026-11-22T23:00:00Z","homeScore":123,"awayScore":113}
    ]}
    """.trimIndent()

    private fun replayBody(matchId: String) = """
    {"items":[
      {"id":1,"matchId":$matchId,"title":"中文高清回放","sort":1,"published":true,
       "url":"https://www.rtmpcdn.com/vodplay/$matchId/cn.m3u8"},
      {"id":2,"matchId":$matchId,"title":"标清回放","sort":0,"published":true,
       "url":"https://www.rtmpcdn.com/vodplay/$matchId/sd.m3u8"}
    ]}
    """.trimIndent()
}
