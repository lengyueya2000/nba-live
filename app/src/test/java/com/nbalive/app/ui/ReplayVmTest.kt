package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.data.model.ReplayLine
import com.nbalive.app.data.remote.StreamUrlResolver
import com.nbalive.app.ui.NavTab
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录像状态与播放接线判据（2026-11-25 新增）。 */
class ReplayVmTest {

    private fun replay() = ReplayItem(
        matchId = "2451",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "老鹰",
        awayTeam = "活塞",
        homeScore = 107,
        awayScore = 97,
        timeLabel = "11-23 04:30",
        startAtMillis = 1L,
        lines = listOf(
            ReplayLine("replay-2451-c0", "标清回放", "https://www.rtmpcdn.com/vodplay/a.m3u8"),
            ReplayLine("replay-2451-c1", "中文高清回放", "https://www.rtmpcdn.com/vodplay/b.m3u8"),
        ),
    )

    @Test
    fun `加载录像填入状态并清掉首载`() = vmTest {
        var calls = 0
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            reloadReplays = { calls++; listOf(replay()) },
        )
        vm.loadReplays(force = true)
        settle()
        assertEquals(1, calls)
        assertFalse(vm.replayState.value.loading)
        assertEquals(listOf("2451"), vm.replayState.value.items.map { it.matchId })
    }

    @Test
    fun `点录像把清晰度线路塞进播放状态并直连起播`() = vmTest {
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            reloadReplays = { listOf(replay()) },
            resolveStream = { source ->
                StreamUrlResolver.Resolved(m3u8Url = source.pageUrl, referer = null)
            },
        )
        vm.playReplay(replay())
        settle()
        val st = vm.playerState.value
        assertEquals(2, st.signals.size)
        assertEquals(listOf("标清回放", "中文高清回放"), st.signals.map { it.label })
        assertTrue("录像线路是裸 m3u8，必须判为可直连", st.signals.all { it.pageUrl.endsWith(".m3u8") })
        assertEquals(Provider.XXZB5, st.signals.first().provider)
        assertEquals("https://www.rtmpcdn.com/vodplay/a.m3u8", st.streamUrl)
        assertNotNull(st.match)
        assertEquals("老鹰", st.match!!.homeTeam)
    }

    @Test
    fun `没有线路的录像点进去不会起播`() = vmTest {
        val vm = MainViewModel(loadMatches = { Result.success(emptyList()) })
        val empty = replay().copy(lines = emptyList())
        vm.playReplay(empty)
        settle()
        assertEquals(null, vm.playerState.value.streamUrl)
    }

    @Test
    fun `切换录像日期必须用新日期重新拉而不是沿用旧日期`() = vmTest {
        val dates = mutableListOf<java.time.LocalDate>()
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            reloadReplays = { d -> dates += d; listOf(replay()) },
        )
        val picked = java.time.LocalDate.of(2026, 11, 11)
        vm.selectReplayDate(picked)
        settle()
        assertEquals(listOf(picked), dates)
        assertEquals(picked, vm.replayState.value.selectedDate)
    }

    @Test
    fun `播放录像必须标记为VOD供播放器显示进度条`() = vmTest {
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            reloadReplays = { listOf(replay()) },
            resolveStream = { source ->
                StreamUrlResolver.Resolved(m3u8Url = source.pageUrl, referer = null)
            },
        )
        vm.playReplay(replay())
        settle()
        assertTrue("录像线路必须标 isVod，进度条判据依赖它；直播恒 false", vm.playerState.value.isVod)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `首次切到录像标签必须自动加载 别被初始 loading 标志挡住`() = vmTest {
        var calls = 0
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            reloadReplays = { calls++; listOf(replay()) },
        )
        // 初态 loading=true 表示"还没拉过"，不是"正在拉"——不能据此拒绝首次加载。
        vm.ensureReplaysLoaded(NavTab.REPLAY)
        settle()
        assertEquals("进入录像标签应真的发起一次加载", 1, calls)
        assertFalse(vm.replayState.value.loading)

        vm.ensureReplaysLoaded(NavTab.REPLAY)
        settle()
        assertEquals("重复触发不该再打源站", 1, calls)

        vm.ensureReplaysLoaded(NavTab.MATCHES)
        settle()
        assertEquals("其它标签不该触发录像加载", 1, calls)
    }

    private suspend fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun vmTest(block: suspend TestScope.() -> Unit) {
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
