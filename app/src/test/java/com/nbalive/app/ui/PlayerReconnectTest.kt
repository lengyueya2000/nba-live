package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.TestPaths
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.repository.MatchRepository
import com.nbalive.app.ui.screens.LineSwitchPolicy
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「视频播放失败时，有时候刷新没反应」（2026-11-17 用户现场反馈）。
 *
 * ## 现场与根因
 *
 * 播放页底栏那个刷新图标走 [MainViewModel.reconnect]，它只是再调一次 `play(selectedIndex)`。
 * 而 `play()` 在**网页承载**的两条分支上，两次状态写入之间没有任何挂起点：
 *
 *  - 嗅探缓存命中：先把 `streamUrl` 写成 null，同一轮里又写回**逐字相同**的缓存地址；
 *  - 缓存未命中：先把 `webFallbackUrl` 写成 null，同一轮里又写回**同一个播放页**。
 *
 * StateFlow / Compose 快照会把「变了又变回来」合并掉，界面观察不到中间态，于是：
 *  - [LineSwitchPolicy] 看到地址没变 → Ignore（不 setMediaItem、不 prepare）；
 *  - `WebSurface` 的 `navigationTag` 没变 → 不调 loadUrl，WebView 原样待着。
 *
 * 用户点下去**什么都没发生**。
 *
 * ## 为什么是「有时候」
 *
 * 只有**静态直连线路**那条分支中间真的有挂起点（`resolveStream` 是 suspend），
 * 界面会看到 `streamUrl` 先变 null、播放器释放再重建。实测（雷电 / Android 14）：
 * ExoPlayerImpl Release → m3u8 OK → ExoPlayerImpl Init —— 那条路径重连有效。
 * 其余（JRS / JRKAN 加密线路、嗅探升级后的线路）全部空转。
 *
 * ## 修复形态
 *
 * 重连是一次**用户意图**，不是一个地址；意图必须能穿过「地址没变」这层判据。
 * 所以给它一个自增序号 [PlayerUiState.reconnectNonce]：
 *  - `VideoSurface` 把它进 effect key，本轮未消费时强制 Apply；
 *  - `WebSurface` 把它并进 navigationTag 与壳页解析 effect 的 key，同地址也会真的重新导航。
 *
 * 序号只在「这一次点击」生效，重组仍不会重复 prepare（P9 / P11 的原意不变）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerReconnectTest {

    private val WEB_PAGE = "http://play.example.com/play/mgxl/?id=9&id2="
    private val CACHED_M3U8 = "http://cdn.example.com/live/a.m3u8?expire=" +
        (System.currentTimeMillis() / 1000 + 600) + "&sign=1"

    // ---------- 判据本体 ----------

    @Test
    fun `地址没变但用户要求重投时必须 Apply 而普通重组仍 Ignore`() {
        // 这条是「刷新没反应」的核心：Ignore 的语义必须是「没人要求重来」，
        // 而不是「地址相同」。地址相同 + 用户刚点了刷新 = 必须重新装载。
        assertEquals(
            "普通重组（没有新的用户意图）地址相同必须 Ignore —— P9/P11 的原意不许丢",
            LineSwitchPolicy.Action.Ignore,
            LineSwitchPolicy.decide(
                appliedUrl = "https://a/x.m3u8",
                newUrl = "https://a/x.m3u8",
                playingIntent = true,
            ),
        )
        assertEquals(
            "用户点了重连：地址相同也必须重新装载一次",
            LineSwitchPolicy.Action.Apply(playWhenReady = true),
            LineSwitchPolicy.decide(
                appliedUrl = "https://a/x.m3u8",
                newUrl = "https://a/x.m3u8",
                playingIntent = true,
                forceReapply = true,
            ),
        )
        assertEquals(
            "重连时用户处于暂停态：仍不许替他按播放",
            LineSwitchPolicy.Action.Apply(playWhenReady = false),
            LineSwitchPolicy.decide(
                appliedUrl = "https://a/x.m3u8",
                newUrl = "https://a/x.m3u8",
                playingIntent = false,
                forceReapply = true,
            ),
        )
    }

    // ---------- VM 行为 ----------

    @Test
    fun `重连必须产生一个新的意图序号`() = vmTest {
        val watching = match("watching")
        val vm = vmWith(watching, WEB_PAGE)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        val before = vm.playerState.value.reconnectNonce
        vm.reconnect()
        settle()
        assertNotEquals(
            "reconnect 只调 play() 的话，网页线路会把同一个地址原样写回，" +
                "界面判「地址没变」就什么都不做 —— 必须留下一次可观察的意图",
            before,
            vm.playerState.value.reconnectNonce,
        )
    }

    @Test
    fun `嗅探缓存命中时重连不换地址但必须换意图序号`() = vmTest {
        // 「有时候」里最常见的一支：看一会儿、卡住、点刷新。
        // 此时签名没过期，复用同一条地址是**对的**；空转的是「没有任何东西被重新装载」。
        val watching = match("watching")
        val vm = vmWith(watching, WEB_PAGE)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        vm.onStreamSniffed(CACHED_M3U8, WEB_PAGE)
        settle()
        assertEquals(CACHED_M3U8, vm.playerState.value.streamUrl)

        val before = vm.playerState.value.reconnectNonce
        vm.reconnect()
        settle()
        assertEquals("签名没过期就该复用同一条地址（不换地址不是 bug）", CACHED_M3U8, vm.playerState.value.streamUrl)
        assertNotEquals(
            "地址没变也必须让界面知道「用户要求重连了」，否则这次点击是空转",
            before,
            vm.playerState.value.reconnectNonce,
        )
    }

    @Test
    fun `重连不许把嗅探回环的封禁清掉`() = vmTest {
        // 既有纪律（MainViewModelTest「重连不会重放嗅探回环」）与本修复必须共存：
        // 加意图序号不许顺手把 failedSniffPage 清了 —— 那等于每次点击都重放整条页面链。
        val watching = match("watching")
        val vm = vmWith(watching, WEB_PAGE)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        vm.onStreamSniffed(CACHED_M3U8, WEB_PAGE)
        vm.onPlaybackFailed()
        settle()
        assertEquals(WEB_PAGE, vm.playerState.value.failedSniffPage)

        vm.reconnect()
        settle()
        assertEquals(
            "重连产生了新意图，但不能因此解除嗅探封禁",
            WEB_PAGE,
            vm.playerState.value.failedSniffPage,
        )
    }

    @Test
    fun `线路列表为空时重连必须重新拉线路而不是静默返回`() = vmTest {
        // 现场：线路拉空（源站改版 / 限流冷却），界面显示「该赛事暂无可用线路」。
        // 旧实现 `if (state.signals.isEmpty()) return` 静默返回，连一个请求都不发 ——
        // 用户唯一的自救按钮变成摆设，只能退出播放页重进。
        var signalLoads = 0
        val watching = match("watching")
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = {
                signalLoads++
                MatchRepository.Detail(signals = emptyList())
            },
        )
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        val afterEnter = signalLoads
        assertTrue("进场至少拉过一次线路，实际 $afterEnter 次", afterEnter >= 1)

        vm.reconnect()
        settle()
        waitForLoadSignals(vm)
        settle()
        assertTrue(
            "线路为空时点重连必须重新拉一次线路（旧实现静默 return，用户永远出不去）",
            signalLoads > afterEnter,
        )
    }

    @Test
    fun `点当前正在播的那条线路也算一次重连意图`() = vmTest {
        // 同一个根因的另一个入口：线路失败后，用户在线路面板里再点一次**同一条**，
        // 语义就是「再试这条」。旧实现地址没变 → 界面 Ignore → 同样没反应。
        val watching = match("watching")
        val vm = vmWith(watching, WEB_PAGE)
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        val before = vm.playerState.value.reconnectNonce
        vm.selectSignal(0)
        settle()
        assertNotEquals(
            "重复点同一条线路必须也产生新意图，否则「再试一次」这个动作根本不存在",
            before,
            vm.playerState.value.reconnectNonce,
        )
    }

    // ---------- 接线守卫：判据改了、界面没接上 = 假安全感 ----------

    @Test
    fun `界面必须真的消费 reconnectNonce 各处接线一个都不能少`() {
        val screen = TestPaths.productionFile("PlayerScreen.kt").readText()
        val code = GuardSourceScan.stripNonCode(screen)

        // 参数声明在**签名**里，而 functionBody 只截花括号内的函数体 —— 想钉签名必须打在全文上。
        // 三处：PlayerScreen（从 VM 收）/ VideoSurface / WebSurface。
        val paramDecls = Regex("""reconnectNonce\s*:\s*Long""").findAll(code).count()
        assertTrue(
            "reconnectNonce 参数必须同时出现在 PlayerScreen / VideoSurface / WebSurface 三个签名里，" +
                "实际命中 $paramDecls 处（少一处就是链路中间断了）",
            paramDecls >= 3,
        )

        val video = GuardSourceScan.functionBody(screen, "fun VideoSurface(")
        assertTrue(
            "换线 effect 的 key 必须带 reconnectNonce：否则重连时地址没变，effect 根本不重跑",
            Regex("""LaunchedEffect\(\s*streamUrl\s*,\s*playerView\s*,\s*reconnectNonce\s*\)""")
                .containsMatchIn(video),
        )
        assertTrue(
            "换线判据必须把「本轮重连尚未消费」传给 LineSwitchPolicy.decide(forceReapply = …)",
            Regex("""forceReapply\s*=\s*reconnectNonce\s*!=\s*appliedNonce""").containsMatchIn(video),
        )
        assertTrue(
            "Apply 分支必须记下本轮已消费的序号，否则同一次重连会在后续重组里反复 prepare",
            video.contains("appliedNonce = reconnectNonce"),
        )

        // ⚠️ 下面三条守的是「二阶洞」：只补换线不补这些的话，重连之后
        // 这一轮永远不再被看门狗盯、错误也只会上报一次 —— 比原来的空转更难发现。
        // 每条都锚在**具体声明**上：早先写成「函数体里含 reconnectNonce」，
        // 变异实验实测把下面这些接线删掉判据照样全绿（那就是假安全感）。
        assertTrue(
            "reachedReady 必须按 (streamUrl, reconnectNonce) remember：重连后首帧看门狗要能重新判死",
            Regex("""reachedReady\s+by\s+remember\(\s*streamUrl\s*,\s*reconnectNonce\s*\)""").containsMatchIn(video),
        )
        assertTrue(
            "错误去重集合必须按 (streamUrl, reconnectNonce) remember：重连后的第二次失败不许被静默吞掉",
            Regex("""reportedRef\s*=\s*remember\(\s*streamUrl\s*,\s*reconnectNonce\s*\)""").containsMatchIn(video),
        )
        val watchdogKeys = Regex("""LaunchedEffect\(\s*streamUrl\s*,\s*reconnectNonce\s*\)""")
            .findAll(video).count()
        assertTrue(
            "两条看门狗（首帧 + 中途卡死）的 effect key 都必须带 reconnectNonce，实际命中 $watchdogKeys 处",
            watchdogKeys >= 2,
        )

        val web = GuardSourceScan.functionBody(screen, "fun WebSurface(")
        // ⚠️ navigationTag 的判据必须打在 **stripComments** 上，不能打在 functionBody 上：
        // functionBody 内部走 stripNonCode，会把字符串字面量的内容换成空格 ——
        // 而 reconnectNonce 恰好就写在那个模板串里，剔掉之后断言恒假（实测红）。
        // stripComments 只剔注释、保留字面量，于是"注释里提一句"仍不算数。
        val webCode = GuardSourceScan.stripComments(screen)
        assertTrue(
            "navigationTag 这个表达式本身必须含 reconnectNonce —— 网页线路重连全靠它才会真的 loadUrl。" +
                "只断言「函数体里含 reconnectNonce」是假判据：effect key 里也有它，" +
                "把 tag 里的删掉实测仍全绿（变异实验 M6）。",
            Regex("""navigationTag\s*=\s*"[^"]*reconnectNonce""").containsMatchIn(webCode),
        )
        assertTrue(
            "壳页解析 effect 的 key 必须带 reconnectNonce —— " +
                "否则重连只是用同一个 encodedStr 再导航一次（H6 那个老问题会回来）",
            Regex("""LaunchedEffect\(\s*url\s*,\s*reloadSeq\s*,\s*reconnectNonce\s*\)""").containsMatchIn(web),
        )
        assertTrue(
            "死胡同探测循环的 key 必须带 reconnectNonce：重连就是一次新导航，" +
                "pageErrorAt / cleanProbes 必须回到「重新开始」的基准",
            Regex("""LaunchedEffect\(\s*url\s*,\s*targetUrl\s*,\s*reconnectNonce\s*\)""").containsMatchIn(web),
        )

        val app = TestPaths.productionFile("NbaLiveApp.kt").readText()
        assertTrue(
            "NbaLiveApp 必须把 playerState.reconnectNonce 传给播放页（漏了这一环整条链就断掉）",
            Regex("""reconnectNonce\s*=\s*playerState\.reconnectNonce""").containsMatchIn(app),
        )
    }

    // ---------- 基础设施（与 PageDeadEndTest 同一套） ----------

    private fun vmWith(watching: Match, vararg pages: String): MainViewModel {
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
        label = "信号1",
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
