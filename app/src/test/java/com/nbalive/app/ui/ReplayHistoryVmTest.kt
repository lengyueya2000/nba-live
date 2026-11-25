package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.data.model.ReplayLine
import com.nbalive.app.data.remote.StreamUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录像历史的 VM 接线判据（2026-11-25 用户需求）。 */
class ReplayHistoryVmTest {

    private fun replay(id: String = "2451") = ReplayItem(
        matchId = id, league = League.NBA, leagueRawName = "NBA",
        homeTeam = "老鹰", awayTeam = "活塞", homeScore = 107, awayScore = 97,
        timeLabel = "11-23 04:30", startAtMillis = 1L,
        lines = listOf(ReplayLine("l", "标清回放", "https://x/$id.m3u8")),
    )

    @Test
    fun `播放录像后自动记入历史并带上位置`() = vmTest {
        val prefs = InMemoryPrefsStore()
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            prefs = prefs,
            reloadReplays = { listOf(replay()) },
            resolveStream = { s -> StreamUrlResolver.Resolved(m3u8Url = s.pageUrl, referer = null) },
        )
        vm.playReplay(replay())
        settle()
        // 起播时位置为 0，记为一条；随后上报位置
        vm.recordReplayPosition(positionMs = 42_000L, durationMs = 1_000_000L)
        settle()
        val history = vm.replayHistory.value
        assertEquals(1, history.size)
        assertEquals("2451", history.first().matchId)
        assertEquals(42_000L, history.first().lastPositionMs)
    }

    @Test
    fun `清空历史后状态与存储都为空`() = vmTest {
        val prefs = InMemoryPrefsStore()
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            prefs = prefs,
            reloadReplays = { listOf(replay()) },
            resolveStream = { s -> StreamUrlResolver.Resolved(m3u8Url = s.pageUrl, referer = null) },
        )
        vm.playReplay(replay())
        settle()
        assertEquals(1, vm.replayHistory.value.size)
        vm.clearReplayHistory()
        settle()
        assertTrue(vm.replayHistory.value.isEmpty())
        assertTrue(prefs.replayHistory.value.isEmpty())
    }

    @Test
    fun `从历史续播把上次位置传给播放器`() = vmTest {
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            reloadReplays = { listOf(replay()) },
            resolveStream = { s -> StreamUrlResolver.Resolved(m3u8Url = s.pageUrl, referer = null) },
        )
        val historyItem = ReplayHistoryPolicy.fromReplay(replay(), positionMs = 42_000L, watchedAt = 1L)
        vm.playReplayFromHistory(historyItem)
        settle()
        assertEquals(42_000L, vm.playerState.value.initialSeekMs)
        assertTrue("历史续播仍须是 VOD", vm.playerState.value.isVod)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun vmTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) {
        try {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                block()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
