package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已经写好但没人调」的守卫（review/00-summary「测试的假安全感」）。
 *
 * ## 为什么需要这个文件
 *
 * 审查发现最危险的形态不是"没写"，而是**写了、测了、但生产代码从不调用**：
 *  - `PlayerControls.shouldShowAspectChip` 有单测、有详细注释，
 *    但 UI 里那个「画面」胶囊仍是无条件渲染 → 网页线路点它毫无变化（01-P2-11）；
 *  - `MatchListUiState.partialError` 有测试断言文案，
 *    但没有任何界面读它 → 用户只看到"场次变少了"（02-P1-2）。
 *
 * 这类缺口单测永远是绿的，只能靠**扫源码看调用点**发现。
 *
 * ## 它自己曾经是"假安全感"，被修过两轮
 *
 * 1. **2026-10-28 审计 A1/A3**：四条断言命中注释/声明（`key(webFallbackUrl)` 命中的是
 *    解释它的注释、`FIRST_FRAME_WATCHDOG_MS` 命中的是 `const val` 声明），
 *    外加 22 处 `assumeTrue` 路径一变就整片 skipped。
 * 2. **2026-10-29 审计「第一节残余」（task-11）**：剩下的 12 处仍是
 *    `code("X.kt").contains("符号")` —— 注释已剔，但**"文件里出现过"不等于"在这一处用上了"**：
 *    把调用搬到同文件另一个函数、或搬到没人调的死函数里，判据照样绿。
 *
 * 现在每条判据都锚在**结构**上（见 [GuardSourceScan]）：
 *
 * | 判据 | 锚点 |
 * |---|---|
 * | 网页退后台注入暂停脚本 | `fun WebSurface(` 体内 + `ON_STOP`/`ON_START` 分支里 |
 * | 返回键走 BackPolicy | `BackHandler(...) { … }` 这一整段调用 |
 * | 详情页缺 match 给占位 | `Route.DETAIL -> when (…)` 这个路由分支的 `when` 块 |
 * | 系统栏方向 / 浮层计时 | `DisposableEffect(...) { … }` / `LaunchedEffect(...) { … }` 体内 |
 * | 手动画中画 | `fun PlayerScreen(` 体内 |
 * | 切场复位小窗尺寸 | `fun VideoSurface(` 体内 |
 * | 首帧看门狗 | 真实调用 `delay(FIRST_FRAME_WATCHDOG_MS)` |
 *
 * 每条判据都配一个**内存样本自检**（[判据自检 文件级残留写法必须被新判据抓到]）：
 * 同一个变异样本，旧的文件级 `contains` 仍为真（证明"改前不会红"），新判据必须为红。
 */
class DeadCodeWiringGuardTest {

    // ------------------------------------------------------------------
    // 判据（纯函数：原始源码文本 → 违规说明列表；空 = 达标）
    // ------------------------------------------------------------------

    /** 01-P2-11：`shouldShowAspectChip` 必须在**别的**生产文件里有真实调用点。 */
    private fun aspectChipViolations(raw: Map<String, String>): List<String> {
        val callers = raw.filterKeys { it != "PlayerControls.kt" }
            .filterValues { GuardSourceScan.callSites(it, "PlayerControls.shouldShowAspectChip") >= 1 }
            .keys
        return if (callers.isNotEmpty()) {
            emptyList()
        } else {
            listOf(
                "shouldShowAspectChip 零调用点：网页线路点「画面」三档文字变、画面毫无变化，" +
                    "而且选择会被持久化（01-P2-11）",
            )
        }
    }

    /** 01-P1-5：必须构造带 TTL 的 `SourceFailureBook`，且拉黑判定必须走 `isSourceFailed`（调用而非声明）。 */
    private fun ttlViolations(rawVm: String): List<String> {
        val out = mutableListOf<String>()
        if (GuardSourceScan.callSites(rawVm, "SourceFailureBook") == 0) {
            out += "没看到 SourceFailureBook(...) 构造 —— 可能又退回裸 Set，" +
                "一次误判会把健康线路永久拉黑（01-P1-5）"
        }
        if (GuardSourceScan.callSites(rawVm, "isSourceFailed") == 0) {
            out += "isSourceFailed(...) 零调用点：只有声明 → 带过期检查的判据从来没被用上"
        }
        return out
    }

    /** 01-P1-5：首帧看门狗必须是**具名常量被 delay 调用**，且不能退回裸 15s。 */
    private fun watchdogViolations(rawPlayer: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawPlayer)
        val out = mutableListOf<String>()
        if (!Regex("""\bdelay\s*\(\s*FIRST_FRAME_WATCHDOG_MS\s*\)""").containsMatchIn(code)) {
            out += "没有真实调用 `delay(FIRST_FRAME_WATCHDOG_MS)` —— 常量声明还在也只是摆设，" +
                "看门狗可被删掉而不变红"
        }
        if (Regex("""\bdelay\s*\(\s*15_?000\s*[,)]""").containsMatchIn(code)) {
            out += "退回了裸 `delay(15_000)`：15 秒对弱网首帧太紧，会误杀健康线路（01-P1-5）"
        }
        return out
    }

    /** 01-P2-4 第一层：`WebSurface` 必须包在真实调用 `key(webFallbackUrl) { … }` 里。 */
    private fun webRebuildViolations(rawPlayer: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawPlayer)
        return if (Regex("""\bkey\s*\(\s*webFallbackUrl\s*\)\s*\{""").containsMatchIn(code)) {
            emptyList()
        } else {
            listOf(
                "没看到真实调用 `key(webFallbackUrl) { … }`：换网页线路时复用同一个 WebView，" +
                    "旧页面的在途嗅探会把已判失败的线路重新升级（界面显示 B、实际播 A）",
            )
        }
    }

    /** 01-P2-4 第二层：`onStreamSniffed` 必须校验 Referer 归属（调用点而非声明）。 */
    private fun sniffOwnershipViolations(rawVm: String): List<String> =
        if (GuardSourceScan.callSites(rawVm, "belongsToPage") >= 1) {
            emptyList()
        } else {
            listOf("belongsToPage(...) 零调用点：嗅探升级不再校验页面归属（01-P2-4 第二层防线）")
        }

    /**
     * 01-P2-3：`PAUSE_ALL` / `RESUME_PAUSED` 必须在 **`WebSurface` 函数体内**，
     * 且分别挂在 `ON_STOP` / `ON_START` 分支上 —— 不是文件里随便一处 `contains`。
     */
    private fun webPauseViolations(rawPlayer: String): List<String> {
        val out = mutableListOf<String>()
        val body = GuardSourceScan.functionBody(rawPlayer, "fun WebSurface(")
        val stopBranches = GuardSourceScan.branchBlocks(body, "Lifecycle.Event.ON_STOP")
        val startBranches = GuardSourceScan.branchBlocks(body, "Lifecycle.Event.ON_START")
        if (stopBranches.isEmpty()) out += "WebSurface 体内找不到 `Lifecycle.Event.ON_STOP` 分支"
        if (startBranches.isEmpty()) out += "WebSurface 体内找不到 `Lifecycle.Event.ON_START` 分支"
        val pauseCall = Regex(
            """\bevaluateJavascript\s*\(\s*WebMediaPauseScript\s*\.\s*PAUSE_ALL\b""",
        )
        val resumeCall = Regex(
            """\bevaluateJavascript\s*\(\s*WebMediaPauseScript\s*\.\s*RESUME_PAUSED\b""",
        )
        if (stopBranches.isNotEmpty() && stopBranches.none { pauseCall.containsMatchIn(it) }) {
            out += "ON_STOP 分支里没有注入 `evaluateJavascript(WebMediaPauseScript.PAUSE_ALL, …)`：" +
                "锁屏后 HTML5 声音还会外放（01-P2-3）"
        }
        if (startBranches.isNotEmpty() && startBranches.none { resumeCall.containsMatchIn(it) }) {
            out += "ON_START 分支里没有 `evaluateJavascript(WebMediaPauseScript.RESUME_PAUSED, …)`：" +
                "只暂停不恢复，回前台没声"
        }
        return out
    }

    /**
     * 03-P2-1：返回键必须走 `BackPolicy.shouldIntercept` / `BackPolicy.action`，
     * 且非默认标签按返回要能走到 `BackAction.ResetTab` —— 判据锚在 **`BackHandler(...) { … }`
     * 这一整段调用**里，不是文件里别处出现过。
     */
    private fun backPolicyViolations(rawUi: Map<String, String>): List<String> {
        val blocks = rawUi.flatMap { (name, text) ->
            GuardSourceScan.callBlocks(text, "BackHandler").map { name to it }
        }
        if (blocks.isEmpty()) {
            return listOf("整个 ui/ 目录里找不到 `BackHandler(` 调用 —— 返回键判据无处落地")
        }
        val out = mutableListOf<String>()
        for ((name, block) in blocks) {
            if (GuardSourceScan.callSites(block, "BackPolicy.shouldIntercept") == 0) {
                out += "$name 的 BackHandler 实参没走 `BackPolicy.shouldIntercept(...)`：" +
                    "enabled 条件又漏了判据（含标签页），在「直播」/「我的」按返回会直接退出 App"
            }
            if (GuardSourceScan.callSites(block, "BackPolicy.action") == 0) {
                out += "$name 的 BackHandler 回调没走 `BackPolicy.action(...)`：返回动作与判据分成两套"
            }
        }
        if (blocks.none { (_, block) -> GuardSourceScan.references(block, "BackAction.ResetTab") >= 1 }) {
            out += "没有任何 BackHandler 回调处理 `BackAction.ResetTab`：非默认标签按返回会退出 App" +
                "（而不是回「赛事」标签）"
        }
        return out
    }

    /**
     * 03-P2-5：详情路由必须用 `MissingMatchPolicy.forDetail` 判缺数据，
     * 并在**同一个 `Route.DETAIL ->` 分支**里渲染 `MissingMatchPlaceholder`（不是别处出现过占位）。
     */
    private fun detailRouteViolations(rawUi: Map<String, String>): List<String> {
        val candidates = rawUi.filterValues {
            GuardSourceScan.callSites(it, "MissingMatchPolicy.forDetail") >= 1
        }
        if (candidates.isEmpty()) {
            return listOf(
                "整个 ui/ 目录里找不到 `MissingMatchPolicy.forDetail(...)` 调用 —— " +
                    "详情路由又回到 `match ?: return@Box` 的静默白屏形态（03-P2-5）",
            )
        }
        val out = mutableListOf<String>()
        for ((name, text) in candidates) {
            val branch = GuardSourceScan.whenBodyWithCall(
                rawText = text,
                callee = "MissingMatchPolicy.forDetail",
                branchLabel = "Route.DETAIL",
            )
            if (GuardSourceScan.callSites(branch, "MissingMatchPlaceholder") == 0) {
                out += "$name 的 `Route.DETAIL ->` 分支里没有 `MissingMatchPlaceholder(...)`：" +
                    "缺 match 时没有占位与返回入口（用户对着白屏没法操作）"
            }
        }
        return out
    }

    /** 01-P2-6 / 01-P2-5：手动画中画必须在 `PlayerScreen` 体内走守卫；切场必须复位小窗尺寸。 */
    private fun manualPipViolations(rawPlayer: String): List<String> {
        val out = mutableListOf<String>()
        val screenBody = GuardSourceScan.functionBody(rawPlayer, "fun PlayerScreen(")
        if (GuardSourceScan.callSites(screenBody, "ManualPipPolicy.decide") == 0) {
            out += "`fun PlayerScreen(` 体内没有 `ManualPipPolicy.decide(...)` 调用：" +
                "解析中/全失败时点画中画是一块黑窗（01-P2-6）"
        }
        val videoBody = GuardSourceScan.functionBody(rawPlayer, "fun VideoSurface(")
        if (GuardSourceScan.callSites(videoBody, "PipAspectReset.shouldResetOnMatchSwitch") == 0) {
            out += "`fun VideoSurface(` 体内没有 `PipAspectReset.shouldResetOnMatchSwitch()` 调用：" +
                "切场不复位小窗尺寸，新场次首帧到达前按上一场比例显示（01-P2-5）"
        }
        return out
    }

    /**
     * 01-P2-7 / 01-P2-8：方向判定必须在 `DisposableEffect(...)` 体内；
     * 浮层自动收起/回补必须在 `LaunchedEffect(...)` 体内。
     */
    private fun systemBarsViolations(rawUi: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        val disposableBlocks = rawUi.flatMap { (_, text) -> GuardSourceScan.callBlocks(text, "DisposableEffect") }
        if (disposableBlocks.none { GuardSourceScan.callSites(it, "SystemBarsPolicy.orientationFor") >= 1 }) {
            out += "没有任何 `DisposableEffect(...)` 体内调用 `SystemBarsPolicy.orientationFor(...)`：" +
                "方向判定脱离了系统栏 effect（进小窗时 onDispose 会把方向复位成 UNSPECIFIED）"
        }
        val launchedBlocks = rawUi.flatMap { (_, text) -> GuardSourceScan.callBlocks(text, "LaunchedEffect") }
        if (launchedBlocks.none { GuardSourceScan.callSites(it, "SystemBarsPolicy.shouldAutoHideControls") >= 1 }) {
            out += "没有任何 `LaunchedEffect(...)` 体内调用 `shouldAutoHideControls(...)`：" +
                "面板打开时若继续计时，面板关闭后控制栏不再出现（01-P2-8）"
        }
        if (launchedBlocks.none {
                GuardSourceScan.callSites(it, "SystemBarsPolicy.shouldRestoreControlsOnPanelClose") >= 1
            }
        ) {
            out += "没有任何 `LaunchedEffect(...)` 体内调用 `shouldRestoreControlsOnPanelClose(...)`：" +
                "面板关闭后控制栏不回补（01-P2-8 第二半）"
        }
        val inPipOk = rawUi.values.any { raw ->
            Regex("""\bFullscreenSystemEffect\s*\([^)]*inPip\s*=\s*inPip\b""")
                .containsMatchIn(GuardSourceScan.stripNonCode(raw))
        }
        if (!inPipOk) {
            out += "`FullscreenSystemEffect(...)` 没有接收**真实的** `inPip`（传常量等于没传，" +
                "onDispose 判不出小窗，方向还是会被复位）"
        }
        return out
    }

    // ------------------------------------------------------------------
    // 测试：每条判据在生产上必须为空
    // ------------------------------------------------------------------

    @Test
    fun `画面胶囊的显隐判据必须被生产代码调用`() {
        assertNoViolations("shouldShowAspectChip 的接线断了", aspectChipViolations(productionRaw()))
    }

    @Test
    fun `失败记账的 TTL 必须真的被用上`() {
        assertNoViolations("SourceFailureBook 的 TTL 没被用上", ttlViolations(code("MainViewModel.kt")))
    }

    @Test
    fun `首帧看门狗阈值不能退回 15 秒`() {
        assertNoViolations("首帧看门狗判据不合格", watchdogViolations(code("PlayerScreen.kt")))
    }

    @Test
    fun `网页播放器必须按 URL 重建 否则切线路会串流`() {
        assertNoViolations("WebView 重建判据不合格", webRebuildViolations(code("PlayerScreen.kt")))
    }

    @Test
    fun `嗅探升级必须校验页面归属`() {
        assertNoViolations("嗅探归属判据不合格", sniffOwnershipViolations(code("MainViewModel.kt")))
    }

    @Test
    fun `网页退后台必须注入媒体暂停脚本`() {
        assertNoViolations("媒体暂停脚本判据不合格", webPauseViolations(code("PlayerScreen.kt")))
    }

    @Test
    fun `所有状态胶囊调用点都要传 isPostponed`() {
        // 02-P2-10：Match.isPostponed 算好了却没人渲染，延期场次在界面上
        // 与「未开赛」无法区分。接线后必须**每个**调用点都传，
        // 漏一个就会在那一页继续显示错误的「未开赛」。
        val violations = productionRaw().flatMap { (name, raw) ->
            GuardSourceScan.stripNonCode(raw).lines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                // 只查调用点（`StatusChip(` 后跟参数），跳过定义
                if (!code.contains("StatusChip(status =")) return@mapIndexedNotNull null
                // 全量直播场次用固定 LIVE 状态，没有延期语义，允许不传
                if (code.contains("MatchStatus.LIVE")) return@mapIndexedNotNull null
                if (code.contains("isPostponed")) return@mapIndexedNotNull null
                "$name:${index + 1}  $code"
            }
        }
        assertTrue(
            "以下 StatusChip 调用点没有传 isPostponed，那一页的延期场次会显示成「未开赛」：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `返回键判据必须走 BackPolicy 且含标签页`() {
        assertNoViolations("返回键判据不合格", backPolicyViolations(uiRaw()))
    }

    @Test
    fun `详情页缺 match 不能 return 白屏`() {
        assertNoViolations("详情路由判据不合格", detailRouteViolations(uiRaw()))
    }

    @Test
    fun `手动画中画必须走守卫 且切场要复位小窗尺寸`() {
        assertNoViolations("手动画中画判据不合格", manualPipViolations(code("PlayerScreen.kt")))
    }

    @Test
    fun `方向与浮层计时都必须走 SystemBarsPolicy`() {
        assertNoViolations("SystemBarsPolicy 接线判据不合格", systemBarsViolations(uiRaw()))
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        // ⚠️ 非空性锚点（审计 A3）：cwd 一变，上面十条会"零违规"地一起变绿，而"全绿"看不出区别。
        val files = productionFiles()
        assertTrue("扫描不到生产源码（cwd=${cwd()}），本文件所有守卫会静默全绿", files.size >= 30)
        val ui = uiRaw()
        assertTrue("扫描不到 ui 源码（cwd=${cwd()}），跨文件判据会静默全绿", ui.size >= 20)
        for (name in listOf("PlayerScreen.kt", "MainViewModel.kt", "PlayerControls.kt")) {
            assertTrue("没扫到 $name —— 扫描范围错了，相关守卫都会变成摆设", files.any { it.name == name })
        }
        for (name in listOf("NbaLiveApp.kt", "BackPolicy.kt", "SystemBarsPolicy.kt")) {
            assertTrue("没扫到 ui/$name —— 跨文件判据会锚不到东西", ui.containsKey(name))
        }
    }

    // ------------------------------------------------------------------
    // 判据自检：把"文件级残留写法"喂给判据，旧判据会放过、新判据必须抓到
    // ------------------------------------------------------------------

    @Test
    fun `判据自检 文件级残留写法必须被新判据抓到`() {
        // 每个样本都是「真实违规写法」：判据调用**没有**落在它该在的那一处，
        // 但符号仍然在同一个文件里 —— 旧的 `code("X.kt").contains("符号")` 会照样绿。
        // 这里对每条升级后的判据同时断言两件事：
        //   (a) 旧的文件级 contains 确实为真（证明"改前不会红"）；
        //   (b) 新判据必须判违规（证明"改后回退即红"）。

        // ---- WebSurface / PAUSE_ALL + RESUME_PAUSED ----
        val webPauseOk = """
            @Composable
            private fun WebSurface(url: String, onStreamFound: (String) -> Unit = {}) {
                val webView: WebView? = null
                DisposableEffect(url) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> {
                                webView?.evaluateJavascript(WebMediaPauseScript.PAUSE_ALL, null)
                            }
                            Lifecycle.Event.ON_START -> {
                                webView?.evaluateJavascript(WebMediaPauseScript.RESUME_PAUSED, null)
                            }
                            else -> Unit
                        }
                    }
                    onDispose { }
                }
            }
        """.trimIndent()
        assertTrue(
            "合规的 WebSurface 样本被判违规（判据有假红）：\n" +
                webPauseViolations(webPauseOk).joinToString("\n"),
            webPauseViolations(webPauseOk).isEmpty(),
        )
        val webPauseMutant = """
            @Composable
            private fun WebSurface(url: String, onStreamFound: (String) -> Unit = {}) {
                DisposableEffect(url) {
                    onDispose { }
                }
            }

            /** 死代码：判据被搬到这里，WebSurface 里其实什么都没注入 */
            private fun deadInjectionHelper(webView: WebView?) {
                webView?.evaluateJavascript(WebMediaPauseScript.PAUSE_ALL, null)
                webView?.evaluateJavascript(WebMediaPauseScript.RESUME_PAUSED, null)
            }
        """.trimIndent()
        assertTrue(
            "旧的文件级判据会放过这个变异（说明改前确实不会红）",
            GuardSourceScan.stripNonCode(webPauseMutant).contains("WebMediaPauseScript.PAUSE_ALL") &&
                GuardSourceScan.stripNonCode(webPauseMutant).contains("WebMediaPauseScript.RESUME_PAUSED"),
        )
        assertTrue("注入被搬到死函数后，新判据必须变红", rejects { webPauseViolations(webPauseMutant) })

        // ---- BackHandler / BackPolicy ----
        val backOk = """
            fun Root() {
                BackHandler(
                    enabled = BackPolicy.shouldIntercept(
                        searchOpen = false,
                        route = Route.LIST,
                        fullscreen = false,
                        onDefaultTab = true,
                    ),
                ) {
                    when (BackPolicy.action(false, Route.LIST, false, true, true)) {
                        BackAction.CloseSearch -> Unit
                        BackAction.ResetTab -> Unit
                        BackAction.None -> Unit
                    }
                }
            }
        """.trimIndent()
        assertTrue(
            "合规的 BackHandler 样本被判违规：\n" + backPolicyViolations(mapOf("App.kt" to backOk)).joinToString("\n"),
            backPolicyViolations(mapOf("App.kt" to backOk)).isEmpty(),
        )
        val backMutant = """
            fun Root() {
                BackHandler(enabled = false) { Unit }
            }

            /** 死代码：判据被搬出 BackHandler 的实参/回调 */
            private fun deadBackHelper() {
                val a = BackPolicy.shouldIntercept(false, Route.LIST, false, true)
                val b = BackPolicy.action(false, Route.LIST, false, true, true)
                val c = BackAction.ResetTab
            }
        """.trimIndent()
        assertTrue(
            "旧的文件级判据会放过这个变异（说明改前确实不会红）",
            GuardSourceScan.stripNonCode(backMutant).contains("BackPolicy.shouldIntercept"),
        )
        assertTrue(
            "判据被搬出 BackHandler 后，新判据必须变红",
            rejects { backPolicyViolations(mapOf("App.kt" to backMutant)) },
        )

        // ---- Route.DETAIL / MissingMatchPolicy ----
        val detailOk = """
            fun Render(route: Route, detailState: DetailState) {
                when (route) {
                    Route.LIST -> Unit
                    Route.DETAIL -> when (
                        val state = MissingMatchPolicy.forDetail(detailState.match != null)
                    ) {
                        is MissingMatchState.ShowPlaceholder -> MissingMatchPlaceholder(
                            message = state.message,
                            onBack = { },
                        )
                        MissingMatchState.Render -> Unit
                    }
                }
            }
        """.trimIndent()
        assertTrue(
            "合规的 Route.DETAIL 样本被判违规：\n" +
                detailRouteViolations(mapOf("App.kt" to detailOk)).joinToString("\n"),
            detailRouteViolations(mapOf("App.kt" to detailOk)).isEmpty(),
        )
        val detailMutant = """
            fun Render(route: Route, detailState: DetailState) {
                when (route) {
                    Route.DETAIL -> when (
                        val state = MissingMatchPolicy.forDetail(detailState.match != null)
                    ) {
                        is MissingMatchState.ShowPlaceholder -> Unit
                        MissingMatchState.Render -> Unit
                    }
                    Route.LIST -> MissingMatchPlaceholder("缺数据", onBack = { })
                }
            }
        """.trimIndent()
        assertTrue(
            "旧的文件级判据会放过这个变异（两道判据都在其它分支里）",
            GuardSourceScan.stripNonCode(detailMutant).contains("MissingMatchPolicy.forDetail") &&
                GuardSourceScan.stripNonCode(detailMutant).contains("MissingMatchPlaceholder"),
        )
        assertTrue(
            "占位被搬到别的路由分支后，新判据必须变红",
            rejects { detailRouteViolations(mapOf("App.kt" to detailMutant)) },
        )

        // ---- ManualPip / VideoSurface ----
        val pipOk = """
            @Composable
            fun PlayerScreen(match: Match?, streamUrl: String?, onEnterPip: () -> Unit) {
                Box {
                    Button(onClick = {
                        when (val decision = ManualPipPolicy.decide(
                            isPlayerRoute = true,
                            hasStream = streamUrl != null,
                        )) {
                            ManualPipDecision.Allow -> onEnterPip()
                            is ManualPipDecision.Blocked -> Unit
                        }
                    }) { }
                }
            }

            @Composable
            private fun VideoSurface(streamUrl: String?) {
                LaunchedEffect(streamUrl) {
                    if (PipAspectReset.shouldResetOnMatchSwitch()) {
                        PipState.setVideoSize(0, 0)
                    }
                }
            }
        """.trimIndent()
        assertTrue(
            "合规的 PlayerScreen/VideoSurface 样本被判违规：\n" + manualPipViolations(pipOk).joinToString("\n"),
            manualPipViolations(pipOk).isEmpty(),
        )
        val pipMutant = """
            @Composable
            fun PlayerScreen(match: Match?, streamUrl: String?, onEnterPip: () -> Unit) {
                Box { }
            }

            /** 死代码：手动画中画判据被搬出 PlayerScreen */
            private fun deadPipHelper(streamUrl: String?, onEnterPip: () -> Unit) {
                when (ManualPipPolicy.decide(isPlayerRoute = true, hasStream = streamUrl != null)) {
                    ManualPipDecision.Allow -> onEnterPip()
                    is ManualPipDecision.Blocked -> Unit
                }
            }

            @Composable
            private fun VideoSurface(streamUrl: String?) {
                LaunchedEffect(streamUrl) {
                    if (PipAspectReset.shouldResetOnMatchSwitch()) {
                        PipState.setVideoSize(0, 0)
                    }
                }
            }
        """.trimIndent()
        assertTrue(
            "旧的文件级判据会放过这个变异（说明改前确实不会红）",
            GuardSourceScan.stripNonCode(pipMutant).contains("ManualPipPolicy.decide"),
        )
        assertTrue("判据被搬出 PlayerScreen 后，新判据必须变红", rejects { manualPipViolations(pipMutant) })

        // ---- SystemBarsPolicy 的 effect 归属 ----
        val systemBarsOk = """
            @Composable
            private fun FullscreenSystemEffect(enabled: Boolean, inPip: Boolean) {
                DisposableEffect(enabled, inPip) {
                    when (SystemBarsPolicy.orientationFor(fullscreen = enabled, inPip = inPip, onPlayer = true)) {
                        OrientationAction.Reset -> Unit
                        else -> Unit
                    }
                    onDispose { }
                }
            }

            @Composable
            private fun ControlsEffect(fullscreen: Boolean, pickerOpen: Boolean) {
                LaunchedEffect(fullscreen, pickerOpen) {
                    if (SystemBarsPolicy.shouldAutoHideControls(fullscreen = fullscreen, pickerOpen = pickerOpen)) {
                        delay(3_000)
                    }
                }
                LaunchedEffect(pickerOpen) {
                    if (SystemBarsPolicy.shouldRestoreControlsOnPanelClose(pickerOpen = pickerOpen)) {
                        visible = true
                    }
                }
            }

            fun callSite(inPip: Boolean) {
                FullscreenSystemEffect(enabled = true, inPip = inPip)
            }
        """.trimIndent()
        assertTrue(
            "合规的 SystemBarsPolicy effect 样本被判违规：\n" +
                systemBarsViolations(mapOf("App.kt" to systemBarsOk)).joinToString("\n"),
            systemBarsViolations(mapOf("App.kt" to systemBarsOk)).isEmpty(),
        )
        val systemBarsMutant = """
            @Composable
            private fun FullscreenSystemEffect(enabled: Boolean, inPip: Boolean) {
                DisposableEffect(enabled, inPip) {
                    onDispose { }
                }
            }

            /** 死代码：方向判据被搬出 DisposableEffect；浮层计时被搬出 LaunchedEffect */
            private fun deadSystemBarsHelper(fullscreen: Boolean, pickerOpen: Boolean) {
                val orientation = SystemBarsPolicy.orientationFor(fullscreen, inPip = false, onPlayer = true)
                val hide = SystemBarsPolicy.shouldAutoHideControls(fullscreen, locked = false, pickerOpen = pickerOpen)
                val restore = SystemBarsPolicy.shouldRestoreControlsOnPanelClose(pickerOpen = pickerOpen)
            }

            fun callSite(inPip: Boolean) {
                FullscreenSystemEffect(enabled = true, inPip = inPip)
            }
        """.trimIndent()
        assertTrue(
            "旧的文件级判据会放过这个变异（说明改前确实不会红）",
            GuardSourceScan.stripNonCode(systemBarsMutant).contains("SystemBarsPolicy.orientationFor"),
        )
        assertTrue(
            "判据被搬出 effect 后，新判据必须变红",
            rejects { systemBarsViolations(mapOf("App.kt" to systemBarsMutant)) },
        )

        // ---- 首帧看门狗：常量被"引用"但 delay 用的是裸字面量 ----
        val watchdogOk = """
            private const val FIRST_FRAME_WATCHDOG_MS: Long = 30_000

            private fun video() {
                LaunchedEffect(streamUrl) {
                    delay(FIRST_FRAME_WATCHDOG_MS)
                }
            }
        """.trimIndent()
        assertTrue("合规的看门狗样本被判违规", watchdogViolations(watchdogOk).isEmpty())
        val watchdogMutant = """
            private const val FIRST_FRAME_WATCHDOG_MS: Long = 30_000

            private fun video() {
                LaunchedEffect(streamUrl) {
                    val documented = FIRST_FRAME_WATCHDOG_MS
                    delay(30_000L)
                }
            }
        """.trimIndent()
        assertTrue(
            "旧的「行里出现常量名就算用过」判据会放过这个变异",
            GuardSourceScan.stripNonCode(watchdogMutant).lines().any {
                it.contains("FIRST_FRAME_WATCHDOG_MS") && !it.contains("const val")
            },
        )
        assertTrue("delay 用裸字面量而常量只被「引用」时，新判据必须变红", rejects { watchdogViolations(watchdogMutant) })
    }

    /**
     * 宽松 `stripComments` 的反例（审计「未验证的怀疑 #6」）。
     *
     * 旧实现只把**整行注释**置空，行尾 `// …` 与行内 `/* … */` 原样保留 ——
     * 于是"删掉真正的调用、只在同一行留一句提到它的注释"就能让
     * `strippedCode.contains("符号")` 继续为真。这个测试把两条反例钉成可执行证据，
     * 同时证明词法实现 [GuardSourceScan.stripNonCode] 已经把这两条堵住。
     */
    @Test
    fun `判据自检 宽松剔注释会被行尾与行内注释骗过 所以必须走词法实现`() {
        // 反例 1：行尾 `//` 注释（最容易被误当成"调用还在"）
        val trailingLineComment = """
            private fun play() {
                enterPip() // 判据在 ManualPipPolicy.decide(...) 里，这里只是说明
            }
        """.trimIndent()
        assertTrue(
            "宽松版应把这个**行尾注释**当成代码 —— 这正是它能骗过 contains 判据的原因",
            looseStripComments(trailingLineComment).contains("ManualPipPolicy.decide("),
        )
        assertTrue(
            "词法版必须剔掉行尾注释里的字面量",
            !GuardSourceScan.stripNonCode(trailingLineComment).contains("ManualPipPolicy.decide("),
        )

        // 反例 2：行内块注释
        val inlineBlockComment = "val enabled = false /* 原来这里是 BackPolicy.shouldIntercept(...) */\n"
        assertTrue(
            "宽松版应把这个**行内块注释**留下（它只看行首是不是注释）",
            looseStripComments(inlineBlockComment).contains("BackPolicy.shouldIntercept("),
        )
        assertTrue(
            "词法版必须剔掉行内块注释",
            !GuardSourceScan.stripNonCode(inlineBlockComment).contains("BackPolicy.shouldIntercept("),
        )

        // 反例 3：字符串字面量（旧的两套实现都保留字符串，词法版一并剔掉）
        val inString = "val note = \"进小窗走 ManualPipPolicy.decide(...)\"\n"
        assertTrue(
            "宽松版会把字符串里的字面量当成代码",
            looseStripComments(inString).contains("ManualPipPolicy.decide("),
        )
        assertTrue(
            "词法版必须剔掉字符串字面量",
            !GuardSourceScan.stripNonCode(inString).contains("ManualPipPolicy.decide("),
        )
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /** `true` = 判据判为违规（返回非空，或锚不上直接 `error()` —— 两者都会让测试红）。 */
    private fun rejects(block: () -> List<String>): Boolean =
        try {
            block().isNotEmpty()
        } catch (e: IllegalStateException) {
            true
        } catch (e: IllegalArgumentException) {
            true
        }

    private fun assertNoViolations(what: String, violations: List<String>) {
        assertTrue(
            "$what：\n" + violations.joinToString("\n") { "  · $it" },
            violations.isEmpty(),
        )
    }

    /**
     * **旧版的宽松剔注释**（只置空整行注释）。
     *
     * ⚠️ 它只作为**反例证据**存在（见 [判据自检 宽松剔注释会被行尾与行内注释骗过 所以必须走词法实现]），
     * 任何判据都不许再调用它 —— 生产/测试代码统一走 [GuardSourceScan.stripNonCode]。
     */
    private fun looseStripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")

    private fun code(name: String): String = productionRaw()[name]
        ?: error("找不到 $name，守卫会静默全绿（$name 是这条判据的唯一锚点）")

    /** 生产源码：文件名 → 原始文本。判据函数内部自己剔注释/字符串（铁律①）。 */
    private fun productionRaw(): Map<String, String> =
        productionFiles().associate { it.name to it.readText() }

    /** `ui/` 目录下的全部源码：文件名 → 原始文本（跨文件判据用，抗"把代码搬到新文件"的重构）。 */
    private fun uiRaw(): Map<String, String> =
        uiFiles().associate { it.name to it.readText() }

    private fun productionFiles(): List<File> {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=${cwd()}）")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun uiFiles(): List<File> {
        val root = listOf(
            File("src/main/java/com/nbalive/app/ui"),
            File("app/src/main/java/com/nbalive/app/ui"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app/ui"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到 ui 源码目录（cwd=${cwd()}）—— 跨文件判据会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath
}