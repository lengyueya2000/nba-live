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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直连失败退回网页后的**有界恢复**（2026-10-30「解析播放太慢」）。
 *
 * ## 现场
 *
 * 嗅探到的 m3u8 返回 404 → ExoPlayer 报错 → 按设计退回网页。然后界面就停在
 * 源站播放器自己的「网络错误请试试刷新」上：同页禁止再升级，页面又发过媒体请求，
 * 25 秒嗅探看门狗不触发 —— **实测挂了 5 分钟没有任何状态变化**，用户只能手动换线。
 *
 * 判据见 [SniffRecoveryPolicy]：退回后给 8 秒宽限，期间页面重新发流就取消，
 * 否则按"无信号"的同一套判据换下一条（每条只试一次）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SniffRecoveryTest {

    private val PAGE_A = "http://play.example.com/play/mgxl/?id=9&id2="
    private val PAGE_B = "http://play.example.com/play/mgxl/?id=10&id2="

    @Test
    fun `直连失败退回网页后 宽限期内没有恢复就自动换下一条`() = vmTest {
        val watching = match("watching", "湖人", "勇士")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        assertEquals("首条线路先走网页", PAGE_A, vm.playerState.value.webFallbackUrl)

        vm.onStreamSniffed("http://cdn.example.com/a.m3u8?expire=9999999999", PAGE_A)
        assertEquals(0, vm.playerState.value.selectedIndex)

        // 该地址 404：退回网页，并开始宽限计时
        vm.onPlaybackFailed()
        settle()
        assertEquals(PAGE_A, vm.playerState.value.webFallbackUrl)
        assertEquals("宽限期内不能立刻换线", 0, vm.playerState.value.selectedIndex)

        // 宽限期内页面没有恢复 → 应该换到第二条
        advanceTimeBy(SniffRecoveryPolicy.GRACE_MS + 1)
        runCurrent()
        settle()

        assertEquals("超过宽限必须换下一条", 1, vm.playerState.value.selectedIndex)
        assertEquals(PAGE_B, vm.playerState.value.webFallbackUrl)
        assertNull("换线后不能还挂着旧地址", vm.playerState.value.streamUrl)
        // ⚠️ 不在这里断言提示文案：换线会走 play()，而 play() 会清理上一轮 errorMessage
        // （提示只在"正在切换"的瞬间存在，行为层抓不稳）。文案由
        // SniffRecoveryPolicy.advanceNote() 的纯判据 + 接线守卫钉住。
    }

    @Test
    fun `宽限期内页面重新发流则不换线`() = vmTest {
        // 页面重载后可能拿到一个新签名地址（虽然同页升级被防回环挡住），
        // 但只要它又开始发流，就不该把这条线判死。
        val watching = match("watching", "湖人", "勇士")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        vm.onStreamSniffed("http://cdn.example.com/a.m3u8?expire=9999999999", PAGE_A)
        vm.onPlaybackFailed()
        settle()

        advanceTimeBy(3_000)
        runCurrent()
        vm.onStreamSniffed("http://cdn.example.com/a2.m3u8?expire=9999999999", PAGE_A)

        advanceTimeBy(SniffRecoveryPolicy.GRACE_MS + 1)
        runCurrent()
        settle()

        assertEquals("页面又发流了，不该换线", 0, vm.playerState.value.selectedIndex)
        assertEquals(PAGE_A, vm.playerState.value.webFallbackUrl)
    }

    @Test
    fun `离开播放页会取消恢复计时`() = vmTest {
        val watching = match("watching", "湖人", "勇士")
        val vm = vmWithSignals(watching, PAGE_A, PAGE_B)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        vm.onStreamSniffed("http://cdn.example.com/a.m3u8?expire=9999999999", PAGE_A)
        vm.onPlaybackFailed()
        settle()

        vm.back()
        assertEquals(Route.LIST, vm.route.value)
        val messageAfterBack = vm.playerState.value.errorMessage

        advanceTimeBy(SniffRecoveryPolicy.GRACE_MS + 1)
        runCurrent()
        settle()

        assertEquals("已经离开播放页，计时不能再去换线", messageAfterBack, vm.playerState.value.errorMessage)
        assertNull("也不能留下网页兜底地址", vm.playerState.value.webFallbackUrl)
    }

    @Test
    fun `只有这一条线时给明确提示而不是无限换线`() = vmTest {
        val watching = match("watching", "湖人", "勇士")
        val vm = vmWithSignals(watching, PAGE_A)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        vm.onStreamSniffed("http://cdn.example.com/a.m3u8?expire=9999999999", PAGE_A)
        vm.onPlaybackFailed()
        settle()

        advanceTimeBy(SniffRecoveryPolicy.GRACE_MS + 1)
        runCurrent()
        settle()

        assertTrue(
            "没有别的线路可换时必须给可执行的提示（当前=${vm.playerState.value.errorMessage}）",
            vm.playerState.value.errorMessage!!.contains("所有线路都没有信号"),
        )
    }

    // ---------------------------------------------------------------

    private fun vmWithSignals(watching: Match, vararg pages: String): MainViewModel {
        val signals = pages.mapIndexed { i, page -> source("watching-s$i", page) }
        return MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = signals.toList()) },
        )
    }

    private fun match(id: String, home: String, away: String) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
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
            check(spins < 10_000) { "loadSignals 迟迟未落地：IO 线程被饿死，或实现已改成发真实请求" }
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