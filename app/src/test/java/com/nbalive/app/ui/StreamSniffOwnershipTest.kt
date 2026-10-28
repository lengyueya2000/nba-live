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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 嗅探升级的**页面归属**校验（review/01-player.md P2-4）。
 *
 * ## 要解决什么
 *
 * 网页兜底播放时，App 靠 `shouldInterceptRequest` 嗅探 `.m3u8` 并升级成直连。
 * 切网页线路时，**旧页面在途的请求**可能在新线路已经开始加载之后才回来 ——
 * 于是把旧线路的流当成新线路升级：界面显示线路 B，
 * 实际播的是已经判失败的线路 A，用户看到"换线了但画面还是原来那个/直接黑屏"。
 *
 * 修法两层：
 *  1. `PlayerScreen` 给 `WebSurface` 加 `key(webFallbackUrl)`，切换线路即重建，
 *     旧页面的回调随 WebView 一起作废；
 *  2. `MainViewModel.onStreamSniffed` 再校验一次 Referer 是否属于当前播放页。
 *
 * 这里测第 2 层（纯 ViewModel，可单测）；第 1 层是 Compose 结构，见
 * `DeadCodeWiringGuardTest` 的源码断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSniffOwnershipTest {

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

    private fun matchWith(sources: List<StreamSource>) = Match(
        id = "m1", league = League.NBA, leagueRawName = "NBA",
        homeTeam = "湖人", awayTeam = "勇士", homeLogo = null, awayLogo = null,
        timeLabel = "10-23 07:00", startAtMillis = null,
        status = MatchStatus.LIVE, signalCount = sources.size,
        sources = sources, jrsDetailId = null,
    )

    private fun source(id: String, pageUrl: String) = StreamSource(
        id = id, label = "线路1", provider = Provider.JRKAN, pageUrl = pageUrl,
    )

    /** 让 ViewModel 进入"网页兜底播放中"状态。 */
    private suspend fun TestScope.enterWebFallback(
        vm: MainViewModel,
        pageUrl: String,
    ) {
        // 线路不带 /play/sm.html -> 不可静态解析 -> 走网页兜底
        vm.playMatch(matchWith(listOf(source("s0", pageUrl))))
        advanceTimeBy(1)
        runCurrent()
        // playMatch 内部会 resolveStream（可能 IO）——等它落地
        var spins = 0
        while (vm.playerState.value.webFallbackUrl == null && spins < 10_000) {
            spins++
            runCurrent()
            Thread.yield()
        }
        // ⚠️ 只读审计 review/audit-guards.md A13：这个循环到上界后**静默退出**。
        // 夹具坏掉（根本没进网页兜底）时 `webFallbackUrl` 本来就是 null，
        // 负向用例的 `assertNull(streamUrl)` 会以"通过"掩盖夹具故障 —— 假安全感。
        // 所以这里硬失败（check，不是 assume），并顺带钉住兜底的**是这一条**线路。
        checkNotNull(vm.playerState.value.webFallbackUrl) {
            "夹具没把 ViewModel 带进网页兜底（spins=$spins，playerState=${vm.playerState.value}）—— " +
                "后续断言会以「通过」掩盖夹具故障（审计 A13）"
        }
        assertEquals(
            "网页兜底必须停在**这条**线路的页面上，否则后续断言测的是别的页面",
            pageUrl,
            vm.playerState.value.webFallbackUrl,
        )
    }

    @Test
    fun `当前播放页自己嗅探到的流会被升级`() = vmTest {
        val page = "http://play.sportsteam368.com/play/mgxl/?id=1&id2="
        val vm = MainViewModel(
            repository = MatchRepository(),
            loadMatches = { Result.success(emptyList()) },
            loadSignals = { MatchRepository.Detail(signals = listOf(source("s0", page))) },
        )
        enterWebFallback(vm, page)

        vm.onStreamSniffed("http://cdn.example.com/live/a.m3u8", "http://play.sportsteam368.com/")
        advanceTimeBy(1)
        runCurrent()

        assertEquals(
            "同源嗅探必须能升级成直连",
            "http://cdn.example.com/live/a.m3u8",
            vm.playerState.value.streamUrl,
        )
    }

    @Test
    fun `别的播放页的嗅探结果不会被升级`() = vmTest {
        val page = "http://play.sportsteam368.com/play/mgxl/?id=1&id2="
        val vm = MainViewModel(
            repository = MatchRepository(),
            loadMatches = { Result.success(emptyList()) },
            loadSignals = { MatchRepository.Detail(signals = listOf(source("s0", page))) },
        )
        enterWebFallback(vm, page)

        // 旧页面（另一个播放主机）在途的请求晚到了
        val oldPageReferer = "http://play.sportsteam356.com/"
        // ⚠️ 前置条件（审计 A13）：负向用例必须先证明"夹具真的在兜底、而且旧页面确实不同源"。
        // 否则夹具失效时 streamUrl 本来就是 null，这条 assertNull 会以"通过"掩盖故障。
        assertNull(
            "进兜底时不该已经有流（否则下面断言的是别的东西）",
            vm.playerState.value.streamUrl,
        )
        assertFalse(
            "夹具里的「旧页面」必须与当前播放页不同源，否则这条负向用例其实测的是正向",
            page.startsWith(oldPageReferer) || oldPageReferer.startsWith(page),
        )
        vm.onStreamSniffed("http://cdn.example.com/live/old.m3u8", oldPageReferer)
        advanceTimeBy(1)
        runCurrent()

        assertNull(
            "不属于当前播放页的流不能被升级 —— 否则界面显示线路 B、实际播的是已判失败的线路 A",
            vm.playerState.value.streamUrl,
        )
    }

    @Test
    fun `拿不到 referer 时仍然允许升级`() = vmTest {
        // 有些源站请求不带 Referer；缺信息不该丢掉真流
        val page = "http://play.sportsteam368.com/play/mgxl/?id=1&id2="
        val vm = MainViewModel(
            repository = MatchRepository(),
            loadMatches = { Result.success(emptyList()) },
            loadSignals = { MatchRepository.Detail(signals = listOf(source("s0", page))) },
        )
        enterWebFallback(vm, page)

        vm.onStreamSniffed("http://cdn.example.com/live/a.m3u8", null)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(
            "缺 Referer 时不能丢掉真流",
            "http://cdn.example.com/live/a.m3u8",
            vm.playerState.value.streamUrl,
        )
    }
}