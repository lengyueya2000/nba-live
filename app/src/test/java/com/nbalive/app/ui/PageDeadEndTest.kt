package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.repository.MatchRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源站播放器报错后的**自动换线**（2026-10-31「在这个页面点刷新没有反应」）。
 *
 * 现场：网页兜底播放时源站 xgplayer 画一块「网络错误 / 请试试 刷新」，
 * 点「刷新」点不动（它只是把同一个失效地址重赋给 `<video>`）。
 * App 探测到页面错误态后必须自己换线，而不是等源站自愈。
 *
 * 判据见 [WebPageDeadEndPolicy]；这里守**行为**：真的换了线、只换一次、别打扰在播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageDeadEndTest {

    private val PAGE_A = "http://play.example.com/play/mgxl/?id=9&id2="
    private val PAGE_B = "http://play.example.com/play/mgxl/?id=10&id2="

    @Test
    fun `页面报错后换到下一条线路`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        assertEquals("首条线路先走网页", PAGE_A, vm.playerState.value.webFallbackUrl)

        vm.onPageDeadEnd()
        settle()

        assertEquals("页面报错了就该换线", 1, vm.playerState.value.selectedIndex)
        assertEquals(PAGE_B, vm.playerState.value.webFallbackUrl)
        assertNull("换线后不能还挂着旧地址", vm.playerState.value.streamUrl)
    }

    @Test
    fun `探测是轮询的 重复上报不能连跳两条线`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B, "http://play.example.com/play/mgxl/?id=11&id2=")
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        // 每秒轮询，同一块错误面板会被探测到很多次
        repeat(5) { vm.onPageDeadEnd() }
        settle()

        assertEquals("重复上报只该换一条线，不能一路跳到第三条", 1, vm.playerState.value.selectedIndex)
    }

    @Test
    fun `已经在直连播时页面报错不能把画面踢掉`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        // 页面嗅探到流 → 升级成 ExoPlayer 直连
        vm.onStreamSniffed("http://cdn.example.com/a.m3u8?expire=9999999999", PAGE_A)
        settle()
        assertEquals("应已升级为直连", "http://cdn.example.com/a.m3u8?expire=9999999999", vm.playerState.value.streamUrl)

        // 页面组件可能滞后于升级才把错误态报上来
        vm.onPageDeadEnd()
        settle()

        assertEquals("正在播的画面不许被踢掉", 0, vm.playerState.value.selectedIndex)
        assertEquals(
            "正在播的画面不许被踢掉",
            "http://cdn.example.com/a.m3u8?expire=9999999999",
            vm.playerState.value.streamUrl,
        )
    }

    @Test
    fun `只有一条线时给明确提示而不是无限换线`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        vm.onPageDeadEnd()
        settle()

        assertTrue(
            "没有别的线路可换时必须给可执行的提示（当前=${vm.playerState.value.errorMessage}）",
            vm.playerState.value.errorMessage!!.contains("所有线路都没有信号"),
        )
    }

    // ---------------------------------------------------------------
    // v1.2.26 组合回归：P18 的恢复条上报与 onPageDeadEnd 的去重守卫
    // ---------------------------------------------------------------

    /**
     * 线上现场（修复前双双失效的正是这两条出口）：
     * 恢复条 1.5 秒出现 → `onDeadEndSuspectOnly` 置 playbackSuspect；
     * 9.5 秒探测循环到点 → `onPageDeadEnd` —— 原来它的守卫读 playbackSuspect，
     * 于是被 1.5 秒那次上报吞掉，自动换线与「换线路」按钮双双失灵。
     */
    @Test
    fun `恢复条已上报疑似后 自动换线与换线路按钮仍必须生效`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        // 1.5 秒：恢复条出现，只置疑似、不换线（P18）
        vm.onDeadEndSuspectOnly()
        settle()
        assertEquals("P18：恢复条出现不许换线", 0, vm.playerState.value.selectedIndex)

        // 9.5 秒：探测循环到点，自动换线**必须**发生（修复前被守卫吞掉）
        vm.onPageDeadEnd()
        settle()
        assertEquals("恢复条上报过疑似 ≠ 死胡同已处置 —— 9.5s 自动换线不许被吞", 1, vm.playerState.value.selectedIndex)
        assertEquals(PAGE_B, vm.playerState.value.webFallbackUrl)
    }

    @Test
    fun `恢复条上报后 用户点换线路按钮也必须换线`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B, "http://play.example.com/play/mgxl/?id=11&id2=")
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        // 恢复条出现（1.5s）→ 用户点了「换线路」（PlayerScreen 浮层按钮走同一个出口）
        vm.onDeadEndSuspectOnly()
        vm.onPageDeadEnd()
        settle()

        assertEquals("用户点「换线路」必须换下一条", 1, vm.playerState.value.selectedIndex)
    }

    @Test
    fun `同一条线路上死胡同重复上报仍只换一条线`() = vmTest {
        val watching = match("watching")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B, "http://play.example.com/play/mgxl/?id=11&id2=")
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        // 探测每秒轮询：同一条死线可能连发多次 onPageDeadEnd
        vm.onPageDeadEnd()
        repeat(5) { vm.onPageDeadEnd() }
        settle()

        assertEquals("死胡同账随线路只记一次，不许连跳", 1, vm.playerState.value.selectedIndex)
    }

    // ---------------------------------------------------------------

    private fun vmWithSignals(watching: Match, vararg pages: String): MainViewModel {
        val signals = pages.mapIndexed { i, page -> source("watching-s$i", page) }
        return MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = signals.toList()) },
        )
    }

    private fun match(id: String) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "勇士",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-23 07:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )

    private fun source(id: String, pageUrl: String) = StreamSource(
        id = id,
        label = "信号${id.takeLast(1)}",
        provider = Provider.JRKAN,
        pageUrl = pageUrl,
    )

    private suspend fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    private fun TestScope.waitForLoadSignals(vm: MainViewModel) {
        var spins = 0
        while (vm.detailState.value.loadingSignals) {
            check(spins < 10_000) { "loadSignals 迟迟未落地" }
            spins++
            runCurrent()
            Thread.yield()
        }
    }

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
