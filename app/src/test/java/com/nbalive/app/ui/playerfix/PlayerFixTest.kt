package com.nbalive.app.ui.playerfix

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.ui.AudioFocusPolicy
import com.nbalive.app.ui.PipAspectReset
import com.nbalive.app.ui.PipState
import com.nbalive.app.ui.PlaybackHealth
import com.nbalive.app.ui.PlayerClock
import com.nbalive.app.ui.SystemBarsPolicy
import com.nbalive.app.ui.WebPageDeadEndPolicy
import com.nbalive.app.ui.WebSniffState
import com.nbalive.app.ui.WebSniffWatchdog
import com.nbalive.app.ui.screens.LineSwitchPolicy
import com.nbalive.app.ui.screens.PlayerPageResolver
import com.nbalive.app.ui.screens.VideoResolution
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放链路 18 条修复的**判据**（task-7 / `review/fix/player.md`）。
 *
 * ## 这个文件守什么
 *
 * 每一条都对应一个"改动之前会红"的判据：
 *  - 纯逻辑（H6 / P2 / P3 / P4 / P5 / P6 / P10 / P12 / P13 / P14 / P15 / P16 / P17 / P18）
 *    直接断言新抽取的纯函数与纯状态类；
 *  - 纯 UI 副作用（P7 / P8 / P9 / P11 与各条"接线"）只能扫**生产源码的结构**：
 *    一律走 [GuardSourceScan]（先剔注释与字符串），并锚在函数体/调用点上，
 *    而不是"文件里出现过这个名字"。
 *
 * ## 纪律（与全仓一致，见 `GuardHygieneTest`）
 *
 * 1. 断言前先剔非代码；2. 声明不算使用（用 [GuardSourceScan.callSites]）；
 * 3. 找不到文件 / 锚不上结构 → `error()` 硬失败，绝不静默跳过；
 * 4. 末尾有非空性锚点，防止"零命中的空扫"。
 *
 * ⚠️ 本文件只**新增**，不改任何既有测试 —— 既有守卫的锚点（`fun PlayerScreen(` 里的
 * `ManualPipPolicy.decide`、`fun WebSurface(` 里的 PAUSE_ALL/RELOAD 接线、
 * `PlayerLoadingHintPolicy.shouldShow` 等）在本次修复里被刻意保留。
 */
class PlayerFixTest {

    // ==================================================================
    // H6（= player.md 第 1 条）「重新加载」拿旧签名
    // ==================================================================

    @Test
    fun `H6 重新加载的导航目标必须是原始壳页 而不是当前 paps 地址`() {
        val page = "http://play.example.com/play/mgxl/?id=9&id2="
        val paps = "https://demo.songzenet.com/paps.html?id=OLD_ENCODED"
        assertEquals(
            "捷径路径下必须回到壳页重新解析，才会拿到新的 encodedStr",
            page,
            WebPageDeadEndPolicy.targetOnReload(page, paps),
        )
        assertEquals(
            "回退路径（当前就是原页）同样回壳页，语义不变",
            page,
            WebPageDeadEndPolicy.targetOnReload(page, page),
        )
        assertNotEquals(
            "留在 paps 地址上 = reload 同一个旧签名（就是要修的 bug）",
            paps,
            WebPageDeadEndPolicy.targetOnReload(page, paps),
        )
    }

    @Test
    fun `H6 生产代码不再 reload 当前地址 且回到壳页的重解析真的接线`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val code = GuardSourceScan.stripNonCode(raw)
        assertFalse(
            "`webView?.reload()` 重发的还是同一个 encodedStr —— 这就是 H6 的现场",
            code.contains("webView?.reload()"),
        )
        assertTrue(
            "「重新加载」必须真的调 WebPageDeadEndPolicy.targetOnReload（判据唯一来源）",
            GuardSourceScan.callSites(raw, "WebPageDeadEndPolicy.targetOnReload") >= 1,
        )
        assertTrue(
            "捷径必须能重跑一次：原 `directPlayerTried` 一次性开关必须换成显式导航代次",
            code.contains("var reloadSeq by remember(url)"),
        )
        assertTrue(
            "导航标识必须带上代次：只比地址会让「地址没变但要求重新加载」落空",
            code.contains("if (view.tag != navigationTag)"),
        )
        assertTrue(
            "AndroidView 里两次导航（工厂 + update）都必须写 navigationTag",
            Regex("""tag\s*=\s*navigationTag""").findAll(code).count() >= 2,
        )
    }

    // ==================================================================
    // P2 hasStream 死参数（复核修正版）
    // ==================================================================

    @Test
    fun `P2 hasStreamNow 只在窗口内还有新媒体请求时算在出流`() {
        assertFalse(
            "本次导航内没见过任何媒体请求 → 不算在出流",
            PlaybackHealth.hasStreamNow(recentMediaRequestAtMs = 0L, nowMs = 100_000L),
        )
        assertTrue(
            "刚刚拉过分片 → 页面确实在出流",
            PlaybackHealth.hasStreamNow(recentMediaRequestAtMs = 99_500L, nowMs = 100_000L),
        )
        assertTrue(
            "正好落在窗口边界上仍算在出流（闭区间）",
            PlaybackHealth.hasStreamNow(
                recentMediaRequestAtMs = 100_000L - PlaybackHealth.MEDIA_REQUEST_STREAM_WINDOW_MS,
                nowMs = 100_000L,
            ),
        )
        assertFalse(
            "超出窗口 → 请求已经停了，不该再算在出流",
            PlaybackHealth.hasStreamNow(recentMediaRequestAtMs = 97_000L, nowMs = 100_000L),
        )
    }

    /**
     * 复核 `verification.md` 第 2 节要求的那条：**修法不能把本功能唯一的目标场景关掉**。
     *
     * 现场 = 页面确实发过一次媒体请求（那条请求 404）→ 播放器画错误面板。
     * 若 `hasStream` 接的是 `sawMediaRequest`（一旦 true 永不回 false），
     * `shouldOffer` 的 `!hasStream` 会永远为 false，出口再也不出现。
     */
    @Test
    fun `P2 目标场景 页面发过媒体请求但那条 404 时出口仍必须出现`() {
        val requestAt = 1_000L
        val errorDetectedAt = 2_500L
        // 确认期（1.5s）到点时，那条失败的请求已经过去 3 秒 > 2 秒窗口
        val firstOfferCheck = errorDetectedAt + WebPageDeadEndPolicy.CONFIRM_MS
        assertFalse(
            "那条 404 的请求不该被当成「还在出流」",
            PlaybackHealth.hasStreamNow(requestAt, firstOfferCheck),
        )
        assertTrue(
            "出口（恢复条）必须照常出现 —— 接 sawMediaRequest 就会永久关闭这条出口",
            WebPageDeadEndPolicy.shouldOffer(
                hasStream = PlaybackHealth.hasStreamNow(requestAt, firstOfferCheck),
                pageErrorDetected = true,
                sinceDetectedMs = WebPageDeadEndPolicy.CONFIRM_MS,
            ),
        )
    }

    @Test
    fun `P2 生产代码不许再把 hasStream 写成常量`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
        assertFalse(
            "`val hasStream = false` 让 policy 的第一道否决从未武装（就是要修的死参数）",
            code.contains("val hasStream = false"),
        )
        assertTrue(
            "必须接「现在是否真的在出流」的判据",
            code.contains("PlaybackHealth.hasStreamNow("),
        )
        assertTrue(
            "死胡同判据的两处消费点都要用同一个 hasStream 变量",
            Regex("""hasStream\s*=\s*hasStream""").findAll(code).count() >= 2,
        )
    }

    // ==================================================================
    // P3 音频焦点轮询缺恢复（复核修正版：只补轮询路径）
    // ==================================================================

    @Test
    fun `P3 通话结束后轮询路径必须自己恢复播放`() {
        assertTrue(
            "被焦点暂停 + 现在不是通话模式 → 必须补申请焦点并恢复",
            AudioFocusPolicy.shouldResumeForAudioMode(
                pausedByFocus = true,
                playing = false,
                mode = android.media.AudioManager.MODE_NORMAL,
            ),
        )
        assertFalse(
            "通话中不许恢复（会和电话/抖音打焦点乒乓）",
            AudioFocusPolicy.shouldResumeForAudioMode(
                pausedByFocus = true,
                playing = false,
                mode = android.media.AudioManager.MODE_IN_CALL,
            ),
        )
        assertFalse(
            "不是焦点造成的暂停（用户自己点的）→ 绝不替他继续播",
            AudioFocusPolicy.shouldResumeForAudioMode(
                pausedByFocus = false,
                playing = false,
                mode = android.media.AudioManager.MODE_NORMAL,
            ),
        )
        assertFalse(
            "已经在播就不重复 play",
            AudioFocusPolicy.shouldResumeForAudioMode(
                pausedByFocus = true,
                playing = true,
                mode = android.media.AudioManager.MODE_NORMAL,
            ),
        )
    }

    @Test
    fun `P3 轮询必须消费恢复判据 且重投不再是无条件`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
        assertTrue(
            "轮询要补对称的恢复分支（判据唯一来源 AudioFocusPolicy.shouldResumeForAudioMode）",
            code.contains("AudioFocusPolicy.shouldResumeForAudioMode("),
        )
        assertTrue(
            "重投必须是「播放中 / 被焦点暂停」——与 AudioFocusPolicy 的注释一致",
            code.contains("player.playWhenReady || pausedByFocus"),
        )
        assertTrue(
            "暂停态不重投之后，起播必须把链接回来（否则这道保险永久失效）",
            code.contains("focusPollRearm.request()"),
        )
    }

    // ==================================================================
    // P4 / P5 / P10 / P14 嗅探状态与时钟注入
    // ==================================================================

    @Test
    fun `P4 导航之后 sawMediaRequest 必须归零 否则看门狗永久失效`() {
        val clock = FakeClock(now = 0L)
        val state = WebSniffState(clock.asClock())

        clock.now = 1_000L
        state.markMediaRequest()
        assertTrue("见过媒体请求", state.sawMediaRequest())
        assertEquals("最近一次媒体请求的时刻要记得住", 1_000L, state.lastMediaRequestAtMs())

        clock.now = 9_000L
        state.onNavigationStarted()
        assertFalse(
            "「重新加载 / 退回原页」之后必须能再次报「疑似无信号」——" +
                "旧实现全仓无 set(false)，这条实例从此永久沉默",
            state.sawMediaRequest(),
        )
        assertEquals("媒体请求记账必须跟着导航清零", 0L, state.lastMediaRequestAtMs())
    }

    @Test
    fun `P5 P10 导航代次变化时加载基准必须重置`() {
        val clock = FakeClock(now = 0L)
        val state = WebSniffState(clock.asClock())
        val generationBefore = state.navigationGeneration

        clock.now = 1_000L
        state.onNavigationStarted()
        assertEquals("导航起点必须取**新的**单调时刻", 1_000L, state.loadStartedAtMs())
        assertTrue("代次必须 +1", state.navigationGeneration > generationBefore)

        // 旧基准（0）下：距"加载 30.1 秒"已经到点 → 看门狗会误报
        assertTrue(
            "旧基准下确实会报（证明这条判据抓得到「沿旧基准计时」这个缺陷）",
            WebSniffWatchdog.shouldReport(
                loadStartedAt = 0L,
                pageFinishedAt = 0L,
                nowMs = 30_100L,
                sawMediaRequest = false,
            ),
        )
        assertFalse(
            "导航之后必须按新的加载起点计时：刚加载 29.1 秒，还没到 30 秒硬上限",
            WebSniffWatchdog.shouldReport(
                loadStartedAt = state.loadStartedAtMs(),
                pageFinishedAt = 0L,
                nowMs = 30_100L,
                sawMediaRequest = state.sawMediaRequest(),
            ),
        )
    }

    @Test
    fun `P4 P5 P10 生产代码必须走 WebSniffState 且每次导航都复位`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
        assertTrue(
            "嗅探状态必须收进 WebSniffState（唯一复位入口）",
            code.contains("val sniff = remember(url) { WebSniffState(clock) }"),
        )
        assertTrue(
            "导航复位必须是一个具名入口，而不是散落的 set(false)",
            code.contains("fun beginNavigation()") && code.contains("sniff.onNavigationStarted()"),
        )
        assertTrue(
            "看门狗必须读 WebSniffState 里的加载起点与媒体请求记账",
            code.contains("loadStartedAt = sniff.loadStartedAtMs()") &&
                code.contains("sawMediaRequest = sniff.sawMediaRequest()"),
        )
        assertTrue(
            "媒体请求必须在后台线程回调里记进 WebSniffState（原子类型）",
            code.contains("sniff.markMediaRequest()"),
        )
        assertFalse(
            "旧的只 set(true) 的 AtomicBoolean 形态必须消失（P4 的现场）",
            code.contains("AtomicBoolean(false)"),
        )
    }

    @Test
    fun `P14 时钟必须可注入 且播放页不再散落 SystemClock`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
        assertFalse("System.nanoTime 不该再出现在播放页（改用注入的单调时钟）", code.contains("System.nanoTime"))
        assertEquals(
            "只剩 WebSurface 体内那一处默认实现（WebSniffWatchdogTest 的单调时钟锚点就是它）",
            1,
            Regex("""android\.os\.SystemClock\.elapsedRealtime\s*\(\s*\)""").findAll(code).count(),
        )
        assertTrue("默认实现必须展开成 PlayerClock 的 lambda（可注入）", code.contains("PlayerClock {"))
        assertTrue(
            "卡死看门狗也必须走同一个时钟（两个 nanoTime 调用点已替换）",
            Regex("""nowMs\s*=\s*clock\.nowMs\(\)""").findAll(code).count() >= 3,
        )
    }

    @Test
    fun `P14 顺带修掉 lint 的 AutoboxingStateCreation`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
        assertTrue(
            "controlsTouchSeq 必须用 mutableIntStateOf（AGP lint AutoboxingStateCreation）",
            code.contains("var controlsTouchSeq by remember { mutableIntStateOf(0) }"),
        )
        assertFalse(
            "旧写法（Integer 装箱）不许回来",
            code.contains("var controlsTouchSeq by remember { mutableStateOf(0) }"),
        )
        assertTrue(
            "同一路径上的 Long 状态也一并去装箱",
            code.contains("mutableLongStateOf(0L)"),
        )
    }

    // ==================================================================
    // P6 进全屏浮层被回补 effect 抵消
    // ==================================================================

    @Test
    fun `P6 进全屏收起浮层不能被面板回补抵消`() {
        assertFalse(
            "进全屏这一次没有「面板刚关闭」这个变化 → 不许回补，" +
                "否则就是 P6 的现场（取决于两个 effect 的启动顺序）",
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = false,
                livePanelOpen = false,
                fullscreen = true,
                panelWasOpen = false,
            ),
        )
        assertTrue(
            "面板**刚刚关闭**（上一次开着）→ 必须显式回补（P2-8 的第二半）",
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = false,
                livePanelOpen = false,
                fullscreen = true,
                panelWasOpen = true,
            ),
        )
        assertFalse(
            "面板还开着不回补",
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = true,
                livePanelOpen = false,
                fullscreen = true,
                panelWasOpen = true,
            ),
        )
        assertFalse(
            "非全屏没有「自动收起的栏」这回事",
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = false,
                livePanelOpen = false,
                fullscreen = false,
                panelWasOpen = true,
            ),
        )
    }

    @Test
    fun `P6 默认参数保持 P2-8 旧语义 且生产合并成一个显式排序的 effect`() {
        assertTrue(
            "不追踪历史的调用方（既有测试的三参调用）必须仍是旧行为",
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = false,
                livePanelOpen = false,
                fullscreen = true,
            ),
        )
        val raw = productionFile("PlayerScreen.kt").readText()
        val code = GuardSourceScan.stripNonCode(raw)
        assertFalse(
            "「进全屏收起」不许再是独立的 LaunchedEffect(fullscreen)",
            code.contains("LaunchedEffect(fullscreen) {"),
        )
        assertFalse(
            "旧的「面板关闭回补」effect 形态必须消失",
            code.contains("LaunchedEffect(picker, showLivePanel, fullscreen)"),
        )
        val merged = GuardSourceScan.callBlocks(raw, "LaunchedEffect").filter {
            it.contains("shouldRestoreControlsOnPanelClose") && it.contains("panelWasOpen")
        }
        assertEquals(
            "收起与回补必须合并在**同一个** LaunchedEffect 里显式排序（且带上 panelWasOpen）",
            1,
            merged.size,
        )
    }

    // ==================================================================
    // P7 250ms ticker 的失效面 + 派生列表 remember
    // ==================================================================

    @Test
    fun `P7 派生列表必须 remember 提示判据必须下沉到 derivedStateOf`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val code = GuardSourceScan.stripNonCode(raw)

        val otherBody = GuardSourceScan.functionBody(raw, "fun PlayerOtherLiveSection(")
        assertTrue(
            "「其他直播」的 filter + take(4) 必须 remember（键 = 数据来源），" +
                "否则播放状态每翻转一次就重新分配一个列表",
            otherBody.contains("remember(otherMatches"),
        )
        assertTrue(
            "面板里的 providers / currentProvider 必须 remember",
            code.contains("remember(signals)") && code.contains("remember(signals, selectedIndex)"),
        )
        val screenBody = GuardSourceScan.functionBody(raw, "fun PlayerScreen(")
        assertTrue(
            "250ms 的 ticker 读取必须收在 derivedStateOf 里 —— 只让那个布尔失效，" +
                "而不是每 250ms 失效一次整块 content lambda",
            screenBody.contains("derivedStateOf"),
        )
        assertTrue(
            "判据仍必须在 PlayerScreen 体内被调用（既有 PlayerLoadingHintPolicyTest 的锚点）",
            screenBody.contains("PlayerLoadingHintPolicy.shouldShow("),
        )
    }

    // ==================================================================
    // P8 巨型函数
    // ==================================================================

    @Test
    fun `P8 播放页函数体必须被拆小 且拆出来的块都还在`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val lines = GuardSourceScan.functionBody(raw, "fun PlayerScreen(").lines().size
        assertTrue(
            "`fun PlayerScreen(` 的函数体又长回 $lines 行（拆分前 915 行）：" +
                "把 P8 拆出去的 5 块搬回函数体就会变红",
            lines <= 800,
        )
        for (name in listOf(
            "PlayerMatchCard",
            "PlayerSignalStrip",
            "PlayerNoticeCards",
            "PlayerOtherLiveSection",
            "PlayerPickerPanel",
        )) {
            assertTrue(
                "$name 不见了（P8 的拆分被回退，巨型函数会长回去）",
                raw.contains("$name("),
            )
        }
        assertTrue(
            "选择面板必须是 BoxScope 扩展（面板本体靠 Modifier.align 定位）",
            raw.contains("private fun BoxScope.PlayerPickerPanel("),
        )
    }

    // ==================================================================
    // P9 完整版：换线路不重建 ExoPlayer，只换媒体项
    // ==================================================================

    @Test
    fun `P9 完整版 必须复用播放器 换线只换媒体项 且沿用播放意图`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val body = GuardSourceScan.functionBody(raw, "fun VideoSurface(")
        val code = GuardSourceScan.stripNonCode(raw)

        assertTrue(
            "Referer 必须交给每请求现查的提供者 —— 这是「不重建播放器」能成立的前提" +
                "（否则 CDN 会拿到上一条线路的 Referer → 403）",
            body.contains("RefererDataSourceFactory("),
        )
        assertTrue(
            "提供者必须读**当前**的 referer（工厂只跑一次，捕获形参会让换线后仍用旧 Referer）",
            body.contains("refererProvider = { currentReferer.value }"),
        )
        assertFalse(
            "P9 完整版已落地：`key(streamUrl, streamReferer)` 必须**消失** —— " +
                "它还留着的话，换线路就把整个 ExoPlayer（解码器/渲染器/缓冲）重建一遍，" +
                "换线延迟与黑帧都白付；这正是本条要消灭的形态。",
            code.contains("key(streamUrl, streamReferer)"),
        )
        // 反向锚点：不许"把 key 删了就完事"，媒体项的应用必须走判据，否则重组会重复 prepare。
        assertTrue(
            "换线的应用点必须走 LineSwitchPolicy.decide（纯函数判据 + 单测）",
            body.contains("LineSwitchPolicy.decide("),
        )
        assertTrue(
            "必须有「已应用到播放器上的地址」这个状态 —— 它是「重组不重复 prepare」的唯一守卫",
            Regex("""appliedUrl\s*=\s*streamUrl""").containsMatchIn(body.replace("\n", " ")) ||
                body.contains("appliedUrl"),
        )
        assertTrue(
            "工厂包装类必须真的在 createDataSource 时现算请求头",
            Regex("""override fun createDataSource\(\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `P9 完整版 换线判据 地址没变必须 Ignore 新地址才 Apply 且沿用播放意图`() {
        // 行为判据（不是结构扫描）：这两条正是"复用播放器"最容易踩的两个坑。
        assertEquals(
            "同一个地址再次重组必须 Ignore（否则每次缓冲状态翻转都会 setMediaItem+prepare → 画面不断重缓冲）",
            LineSwitchPolicy.Action.Ignore,
            LineSwitchPolicy.decide(appliedUrl = "https://a/x.m3u8", newUrl = "https://a/x.m3u8", playingIntent = true),
        )
        assertEquals(
            "还没应用过任何地址 → 必须 Apply（首帧要真的起播）",
            LineSwitchPolicy.Action.Apply(playWhenReady = true),
            LineSwitchPolicy.decide(appliedUrl = null, newUrl = "https://a/x.m3u8", playingIntent = true),
        )
        assertEquals(
            "**换线前在播** → 新线自动起播",
            LineSwitchPolicy.Action.Apply(playWhenReady = true),
            LineSwitchPolicy.decide(appliedUrl = "https://a/x.m3u8", newUrl = "https://b/y.m3u8", playingIntent = true),
        )
        assertEquals(
            "**用户按过暂停** → 只 prepare，不替他按播放（意图必须取换线前一刻的值）",
            LineSwitchPolicy.Action.Apply(playWhenReady = false),
            LineSwitchPolicy.decide(appliedUrl = "https://a/x.m3u8", newUrl = "https://b/y.m3u8", playingIntent = false),
        )
    }

    // ==================================================================
    // P11 update 里的 setMediaItem 死分支
    // ==================================================================

    @Test
    fun `P11 update 里不许再有每次重组都应用的死分支`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val body = GuardSourceScan.functionBody(raw, "fun VideoSurface(")
        assertFalse(
            "`current != streamUrl` 那个「现场比较地址」的死分支不许复活：" +
                "它一旦在某些地址形态下成立，就会在**每次重组**setMediaItem+prepare",
            body.contains("current != streamUrl"),
        )
        // 锚点：找不到 update 块就直接失败 —— 否则下面的 substring 会退化成"扫了整个函数"的空判据。
        assertTrue(
            "锚点丢了：VideoSurface 里找不到 `update = { view ->`（判据会静默变成空扫）",
            body.contains("update = { view ->"),
        )
        val updateBlock = body.substringAfter("update = { view ->").substringBefore("onRelease = {")
        assertFalse(
            "`update` 块里又出现了 setMediaItem —— 这正是 P11 删掉的失效模式：" +
                "`update` 每次重组都会跑，于是缓冲状态一翻转就重新装载媒体项（画面不断重缓冲）。" +
                "媒体项的应用必须只走 LineSwitchPolicy 的 Apply 分支。",
            updateBlock.contains("setMediaItem"),
        )
        assertFalse("`update` 块里又出现了 prepare()（同上的失效模式）", updateBlock.contains("prepare()"))
        assertEquals(
            "setMediaItem 只许剩两处，且各有明确职责：`factory` 首次装载 + Apply 分支换线一次性应用",
            2,
            GuardSourceScan.callSites(body, "setMediaItem"),
        )
        assertTrue(
            "换线应用点必须落在 Apply 分支里（Ignore 分支什么都不做）",
            body.contains("is LineSwitchPolicy.Action.Apply ->"),
        )
        assertTrue("update 仍必须同步 resizeMode（这是它唯一的职责）", body.contains("view.resizeMode = aspectMode.resizeMode"))
    }

    // ==================================================================
    // P12 死胡同探测无退避
    // ==================================================================

    @Test
    fun `P12 探测必须退避而不是无条件 1Hz`() {
        assertEquals(
            "首轮密集：错误要在确认期内被发现",
            WebPageDeadEndPolicy.PROBE_INTERVAL_MS,
            WebPageDeadEndPolicy.probeIntervalMs(consecutiveClean = 0, hasStream = false),
        )
        assertEquals(
            "还没到退避门槛时保持 1Hz",
            WebPageDeadEndPolicy.PROBE_INTERVAL_MS,
            WebPageDeadEndPolicy.probeIntervalMs(
                consecutiveClean = WebPageDeadEndPolicy.CLEAN_PROBES_BEFORE_BACKOFF - 1,
                hasStream = false,
            ),
        )
        assertEquals(
            "连续干净达到门槛 → 退避",
            WebPageDeadEndPolicy.PROBE_INTERVAL_SLOW_MS,
            WebPageDeadEndPolicy.probeIntervalMs(
                consecutiveClean = WebPageDeadEndPolicy.CLEAN_PROBES_BEFORE_BACKOFF,
                hasStream = false,
            ),
        )
        assertEquals(
            "确认在出流 → 退到 3 秒（与 1Hz 的嗅探看门狗不再叠成 2Hz）",
            WebPageDeadEndPolicy.PROBE_INTERVAL_STREAMING_MS,
            WebPageDeadEndPolicy.probeIntervalMs(consecutiveClean = 0, hasStream = true),
        )
        assertTrue(
            "退避后的间隔必须仍小于出口窗口（1.5s + 8s），否则自动换线会迟到",
            WebPageDeadEndPolicy.PROBE_INTERVAL_SLOW_MS <
                WebPageDeadEndPolicy.CONFIRM_MS + WebPageDeadEndPolicy.AUTO_ADVANCE_MS,
        )
    }

    @Test
    fun `P12 生产代码必须消费退避判据 且探到错误要把计数清零`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
        assertTrue(
            "探测循环必须用 probeIntervalMs 决定间隔（判据唯一来源）",
            code.contains("WebPageDeadEndPolicy.probeIntervalMs("),
        )
        assertTrue(
            "探到错误/页面恢复都必须把 cleanProbes 清零，否则退避会拖慢确认",
            Regex("""cleanProbes\s*=\s*0""").findAll(code).count() >= 2,
        )
    }

    // ==================================================================
    // P13 壳页多一次 OkHttp（2s 超时）
    // ==================================================================

    @Test
    fun `P13 壳页请求超时必须压到 1 秒以内且生产消费该常量`() {
        assertTrue(
            "超时必须落在 800ms~1s：弱网下 2s 会把「进网页线路」整体拖慢 2 秒",
            PlayerPageResolver.SHELL_FETCH_TIMEOUT_MS in 800L..1_000L,
        )
        val body = GuardSourceScan.functionBody(
            productionFile("PlayerScreen.kt").readText(),
            "fun WebSurface(",
        )
        assertTrue(
            "WebSurface 必须用这个具名常量（否则改一处不影响另一处）",
            body.contains("PlayerPageResolver.SHELL_FETCH_TIMEOUT_MS"),
        )
        assertFalse(
            "不许退回裸 2 秒字面量",
            body.contains("2_000L"),
        )
    }

    // ==================================================================
    // P15 VideoResolution.findTier 每次新建 Regex
    // ==================================================================

    @Test
    fun `P15 档位正则必须是顶层 private val 且行为不变`() {
        val raw = productionFile("VideoResolution.kt").readText()
        val objectStart = raw.indexOf("object VideoResolution {")
        assertTrue("VideoResolution 的 object 声明不见了", objectStart > 0)
        assertTrue(
            "TIER_REGEX 必须声明在 object **之前**（顶层 val，只编译一次）",
            Regex("""private val TIER_REGEX\s*=\s*Regex\(""").containsMatchIn(raw.substring(0, objectStart)),
        )
        // ⚠️ `findTier` 是表达式体函数（`= …`），GuardSourceScan.functionBody 明确不支持它，
        // 所以这里改成"整个文件里只许有一个 Regex(" 构造"（即顶层那个常量）。
        val code = GuardSourceScan.stripNonCode(raw)
        assertEquals(
            "整个文件里只许有顶层 TIER_REGEX 一处 Regex 构造 —— findTier 不许再每次调用都 new Regex",
            1,
            Regex("""\bRegex\s*\(""").findAll(code).count(),
        )
        assertEquals(
            "路径里带 p 的档位照旧认得出来",
            "1080P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc_1080p.m3u8?expire=1"),
        )
        assertEquals(
            "query 里的裸数字照旧认得出来",
            "720P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc.m3u8?hd=720"),
        )
        assertNull(
            "没有档位信息时仍是 null（不许显示假的画质）",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc.m3u8"),
        )
    }

    // ==================================================================
    // P16 PipState.inPip 唯一可外部直写
    // ==================================================================

    @Test
    fun `P16 inPip 必须只读 写入口是 setInPip value Boolean`() {
        val code = GuardSourceScan.stripNonCode(productionFile("PipState.kt").readText())
        assertFalse(
            "inPip 不许再是可直接写的 MutableStateFlow（同对象另外三个都私有 + 写入口）",
            code.contains("val inPip = MutableStateFlow("),
        )
        assertTrue("必须私有化内部流", code.contains("private val _inPip = MutableStateFlow(false)"))
        assertTrue("对外只读", code.contains("val inPip = _inPip.asStateFlow()"))
        assertTrue(
            "写入口签名必须是 `fun setInPip(value: Boolean)` —— fix-ui-state 按这个签名改 " +
                "MainActivity.kt:49/250/302",
            code.contains("fun setInPip(value: Boolean)"),
        )
        // 行为：写入口真的改到同一个 StateFlow，且**不需要 Context**（进程级单例，Activity 之外也能写）
        PipState.setInPip(true)
        assertTrue("setInPip(true) 必须让读取方看到 true", PipState.inPip.value)
        PipState.setInPip(false)
        assertFalse("setInPip(false) 必须复位", PipState.inPip.value)
    }

    // ==================================================================
    // P17 PipAspectReset.shouldResetOnMatchSwitch 恒 true
    // ==================================================================

    @Test
    fun `P17 切场复位不再是恒真函数`() {
        assertFalse(
            "上一场压根没记过尺寸（0,0）→ 复写 setVideoSize(0,0) 是空操作，跳过它",
            PipAspectReset.shouldResetOnMatchSwitch(hasRecordedVideoSize = false),
        )
        assertTrue(
            "上一场记过真实尺寸 → 必须清零（否则小窗沿用上一场比例，01-P2-5）",
            PipAspectReset.shouldResetOnMatchSwitch(hasRecordedVideoSize = true),
        )
        assertTrue(
            "默认（调用方拿不到信息）保守复位：既有 ManualPipGuardTest 的无参调用必须仍是 true",
            PipAspectReset.shouldResetOnMatchSwitch(),
        )
        val code = GuardSourceScan.stripNonCode(productionFile("ManualPipPolicy.kt").readText())
        assertFalse(
            "恒真实现必须消失（测试断言常量 = 假安全感）",
            code.contains("fun shouldResetOnMatchSwitch(): Boolean = true"),
        )
        assertTrue(
            "生产调用点必须真的把判据传进去（否则 branch 仍是恒真的 if）",
            GuardSourceScan.stripNonCode(productionFile("PlayerScreen.kt").readText())
                .contains("hasRecordedVideoSize = PipState.videoSize.value.width > 0"),
        )
    }

    // ==================================================================
    // P18 恢复条出现 → 自动换线之间的 8 秒窗口（复核重写版）
    // ==================================================================

    @Test
    fun `P18 恢复条出现时必须同步上报疑似 且只报一次`() {
        assertTrue(
            "恢复条首次出现 → 必须上报「疑似」（那 8 秒里 playbackSuspect 原本是 false）",
            WebPageDeadEndPolicy.shouldEscalateSuspectOnOffer(deadEndAlreadyOffered = false),
        )
        assertFalse(
            "每秒轮询会重复探到同一块面板 → 只许上报一次",
            WebPageDeadEndPolicy.shouldEscalateSuspectOnOffer(deadEndAlreadyOffered = true),
        )
        assertTrue(
            "窗口确实存在：确认期 1.5s 与自动换线 9.5s 之间有 8 秒",
            WebPageDeadEndPolicy.AUTO_ADVANCE_MS == 8_000L &&
                WebPageDeadEndPolicy.CONFIRM_MS == 1_500L,
        )
    }

    @Test
    fun `P18 上报必须走轻回调 不能直接复用会立刻换线的 onPlaybackSuspect`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val body = GuardSourceScan.functionBody(raw, "fun WebSurface(")
        assertTrue(
            "恢复条出现处必须走判据",
            body.contains("WebPageDeadEndPolicy.shouldEscalateSuspectOnOffer(pageDeadEnd)"),
        )
        assertTrue(
            "必须走**只置标记**的新回调 onDeadEndSuspect，而不是 onPlaybackSuspect",
            body.contains("onDeadEndSuspectState.value()"),
        )
        val code = GuardSourceScan.stripNonCode(raw)
        assertTrue(
            "PlayerScreen 必须暴露 onDeadEndSuspect 并把它透传给 WebSurface",
            code.contains("onDeadEndSuspect: () -> Unit = {}") &&
                code.contains("onDeadEndSuspect = onDeadEndSuspect"),
        )
        // 反向锚点：为什么必须"更轻"的回调 —— onPlaybackSuspect 置标记之后**立刻换线**。
        // 这条断言把"接错回调"的代价钉在源码事实上，而不是靠注释提醒。
        val vm = GuardSourceScan.stripNonCode(productionFile("MainViewModel.kt").readText())
        val suspectBody = GuardSourceScan.functionBody(vm, "fun onPlaybackSuspect(")
        assertTrue(
            "MainViewModel.onPlaybackSuspect 置标记之后紧跟 advanceAfterNoSignal（会**立刻换线**）——" +
                "所以恢复条的上报绝不能接到它上面（那样 1.5 秒就换线，恢复条与 8 秒选择窗口一起消失）",
            suspectBody.contains("advanceAfterNoSignal("),
        )

        // 端到端接线（P18 的另一半由 `NbaLiveApp` / `MainViewModel` 提供，不在本文件的生产范围，
        // 但**必须**被这条判据钉住：接错回调 = 直接把恢复条废掉）：
        val light = GuardSourceScan.functionBody(vm, "fun onDeadEndSuspectOnly(")
        assertTrue(
            "只置标记的入口必须真的写 playbackSuspect（否则那 8 秒里 playingNow 仍为 true）",
            light.contains("markPlaybackSuspect(true)"),
        )
        assertFalse(
            "这条入口**绝不换线**：出现 advanceAfterNoSignal 就等于把恢复条与 8 秒窗口一起废掉",
            light.contains("advanceAfterNoSignal("),
        )
        val app = GuardSourceScan.stripNonCode(productionFile("NbaLiveApp.kt").readText())
        assertTrue(
            "NbaLiveApp 必须把 PlayerScreen 的 onDeadEndSuspect 接到只置标记的入口上" +
                "（形如 `onDeadEndSuspect = viewModel::onDeadEndSuspectOnly`）",
            Regex("""onDeadEndSuspect\s*=[^,\n]*onDeadEndSuspectOnly""").containsMatchIn(app),
        )
    }

    // ==================================================================
    // P1 真机回归（雷电模拟器 2026-11-01）：恢复条按钮「点不动」
    // ==================================================================

    /**
     * 现场：源站 404 → 恢复条渲染出来，但「重新加载 / 换线路」点不动
     *（连点三次无状态变化、无日志；同一时刻 BACK 正常、CDP 里 URL 6 秒没变）。
     *
     * 根因是**声明位置**：恢复条画在 `WebSurface` 内部，而手势层
     * `PlayerMediaGestures(Modifier.fillMaxSize())` 是视频区 `Box` 的**后置兄弟** ——
     * 命中测试先落进手势层那棵子树，更早的兄弟子树拿不到这次点击。
     *
     * ⚠️ 顺带纠正一处注释误解：手势层**没有**消费点击（它不用 `detectTapGestures`，
     * 且未锁定 + 非全屏时 `PlayerLock.gestureConfig` 给 `tapAction = PASS_THROUGH`
     * （`onTap = null`）、`consumeTap = false`）—— 问题在命中范围，不在消费。
     */
    @Test
    fun `P1真机回归 恢复条必须渲染在手势层之后 否则按钮点不动`() {
        val raw = productionFile("PlayerScreen.kt").readText()
        val screenBody = GuardSourceScan.functionBody(raw, "fun PlayerScreen(")
        val gesturesAt = screenBody.indexOf("PlayerMediaGestures(")
        val barAt = screenBody.indexOf("PlayerDeadEndBar(")
        assertTrue(
            "手势层不见了 —— 这条判据的锚点失效（守卫会变成摆设）",
            gesturesAt >= 0,
        )
        assertTrue(
            "恢复条不在 `fun PlayerScreen(` 函数体里 —— 是不是又搬回 WebSurface 内部了？",
            barAt >= 0,
        )
        assertTrue(
            "恢复条必须声明在手势层**之后**（下标 $barAt 应 > $gesturesAt）：" +
                "手势层是 `Modifier.fillMaxSize()` 的兄弟，命中测试先落进它那棵子树；" +
                "画在更早兄弟子树里的按钮点不动（真机实测：连点三次无反应、URL 6 秒没变）",
            barAt > gesturesAt,
        )

        // 反向锚点：不许再画回 WebSurface 内部（那棵子树在手势层之前）
        val webBody = GuardSourceScan.functionBody(raw, "fun WebSurface(")
        assertFalse(
            "恢复条又画回 WebSurface 内部了 —— 它在手势层**之前**的兄弟子树里，按钮一定点不动",
            webBody.contains("PlayerDeadEndBar("),
        )
        // 文案的唯一来源仍在"探测到死胡同"的那一处（既有 WebPageDeadEndWiringTest 的锚点）
        assertTrue(
            "恢复条文案必须仍由 WebSurface 处的 WebPageDeadEndPolicy 产出" +
                "（浮层区只渲染、不另写一份字面量）",
            webBody.contains("WebPageDeadEndPolicy.note()") &&
                webBody.contains("WebPageDeadEndPolicy.reloadLabel()") &&
                webBody.contains("WebPageDeadEndPolicy.switchLabel()"),
        )
        // 自动换线兜底必须原样保留（9.5 秒后仍要能换下一条）
        assertTrue(
            "9.5 秒后的自动换线兜底不见了（换线路按钮只是与它共用同一个出口）",
            webBody.contains("WebPageDeadEndPolicy.shouldAutoAdvance(") &&
                webBody.contains("onPageDeadEndState.value()"),
        )
        // 三件事的真接线：显示上报 / 重载请求下传 / 换线路复用既有出口
        val code = GuardSourceScan.stripNonCode(raw)
        assertTrue(
            "显示内容必须由 WebSurface 上报（`onDeadEndBarChanged(...)` + `WebDeadEndBar(...)`）",
            code.contains("onDeadEndBarChanged") && code.contains("WebDeadEndBar("),
        )
        assertTrue(
            "「重新加载」必须走**计数式请求**（否则布尔事件会被重组合并吞掉）",
            code.contains("webDeadEndReloadRequests++") &&
                webBody.contains("LaunchedEffect(reloadRequest)") &&
                webBody.contains("WebPageDeadEndPolicy.targetOnReload("),
        )
        assertTrue(
            "「换线路」必须复用既有出口 `onPageDeadEnd()`（自动换线判据的唯一入口）",
            screenBody.contains("onPageDeadEnd()"),
        )
        assertTrue(
            "恢复条必须真的渲染出两个按钮（文案来自 WebSurface 上抛的 WebDeadEndBar）",
            code.contains("PlayerChip(label = bar.reloadLabel") &&
                code.contains("PlayerChip(label = bar.switchLabel"),
        )
    }

    // ==================================================================
    // 基础设施（与全仓守卫同一套纪律）
    // ==================================================================

    @Test
    fun `扫描必须真的扫到生产源码`() {
        val player = productionFile("PlayerScreen.kt")
        val lines = player.readText().lines().size
        assertTrue(
            "PlayerScreen.kt 只有 $lines 行 —— 扫描范围走偏时上面所有结构判据会静默全绿",
            lines > 1_000,
        )
        for (name in listOf(
            "WebPageDeadEndPolicy.kt",
            "PlaybackHealth.kt",
            "AudioFocusPolicy.kt",
            "WebSniffWatchdog.kt",
            "SystemBarsPolicy.kt",
            "ManualPipPolicy.kt",
            "PipState.kt",
            "VideoResolution.kt",
            "PlayerPageResolver.kt",
        )) {
            assertTrue(
                "没扫到 $name —— 与它相关的判据会变成摆设",
                productionFile(name).readText().lines().size > 20,
            )
        }
    }

    /** 可控单调时钟（P14 的注入点）。 */
    private class FakeClock(var now: Long) {
        fun asClock(): PlayerClock = PlayerClock { now }
    }

    private fun productionFile(name: String): File {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error(
                "找不到生产源码根（cwd=${cwd()}）—— 本文件所有结构判据会静默全绿，所以直接失败",
            )
        return GuardSourceScan.uniqueFile(
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList(),
            name,
        )
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath
}