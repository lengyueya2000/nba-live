package com.nbalive.app.ui

import com.nbalive.app.TestPaths
import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NbaLiveApp` 拆分后的**结构守卫**（review/03-ui.md P2-18 / P2-6 / P2-15）。
 *
 * ## 为什么是源码守卫
 *
 * "上帝组件"不是一次性事故，而是**每次加功能都会往回长**的趋势：
 * 少写一个 `RouteHost(...)`、把一段 effect 直接写回根节点，代码照样编译、
 * 界面照样能用，只有 `fun NbaLiveApp` 的行数在悄悄变多 —— 这种退化没有任何
 * 运行期信号。所以这里把"拆分成果"钉成判据：
 *
 *  1. `fun NbaLiveApp(` 的函数体 ≤180 行（用正确的函数体提取：**先跳过参数表**，
 *     否则 Compose 形参默认值里的 `= {}` 会被当成函数体）；
 *  2. 根节点里的 `collectAsStateWithLifecycle(` 调用点 ≤2（P2-15：其余状态在
 *     `RouteHost` 等下层收集，根节点不再因为高频抖动重组）；
 *  3. `NbaLiveApp.kt` 里不再出现外壳/搜索层/返回键的**实现**，且它们确实活在
 *     新文件里（只查"原文件没有"会被"两边都没有"骗过）；
 *  4. 另外两条非空性锚点（被扫文件 ≥30、新文件都 >20 行）——没有它们，
 *     工作目录一变，上面几条会"零违规"地一起变绿；
 *  5. P2-6 的顺序钉住：`AppPipEffects` 里 `PipState.setPlayerActive(` 必须排在
 *     `syncAutoEnterEnabled()` **之前**（这是"显式顺序"的全部内容）。
 *
 * ## 每条判据都有内存样本自检
 *
 * 判据抽成 `private` 纯函数，测试把**违规样本**与**反向样本（注释/字符串）**
 * 喂给它：违规样本必须命中，注释/字符串必须不命中。避免本仓库反复出现的
 * "守卫自己永远不会红"（review/audit-guards.md A3/A6/A9）。
 *
 * 剔除注释与字符串统一走共享的 [GuardSourceScan.stripNonCode]（词法级，保留行号），
 * 不自己再写一份宽松版。路径定位用 [GuardSourceScan.uniqueFile]：0 个或多个命中
 * 都 `error()` 硬失败，绝不用 `assume` 静默跳过。
 */
class AppStructureGuardTest {

    // ---------------- 1. 根组合项不得内联副作用（B12 重写：替换掉"函数体 ≤180 行"） ----------------

    /**
     * ⚠️ 本条在 2026-11-01 被**重写**（review/optimization-review.md B12）。
     *
     * ## 改前断言什么
     *
     * `fun NbaLiveApp(` 的**函数体行数 ≤180**（某轮拆分的测量残留）。
     *
     * ## 改后断言什么
     *
     * 根组合项体内**不得出现内联的系统副作用调用**（[INLINED_EFFECTS]），
     * 并且**仍然真的在装配骨架**（`AppShell(` + `RouteHost(`）。
     *
     * ## 为什么是等价或更强
     *
     * - 旧判据度量的是**形状**：一次格式化、加一段注释就能逼近边界；失败文案只说"超了 N 行"，
     *   不指出"什么行为退化了"。它真正守的"路由分发/副作用/外壳不许回长到根节点"这件事，
     *   已经由两条**搬不动**的判据覆盖：同文件 `definitionViolations`（六个实现不许回写根文件）
     *   与本文件第 2 节（根体内直接收集 ≤1、`AppUiState` 状态面 ≤6，H5）。
     * - 新判据锚在**行为**上：系统副作用一旦就地写回根体（P2-18 要消灭的形态），
     *   任何一次状态抖动都会连带整棵 `Scaffold`/顶栏/底栏重组 —— 这正是要拦的后果。
     *
     * ## 为什么仍然**可失败**
     *
     * 把 `LaunchedEffect(route, tab) { … }`、`DisposableEffect`、`BackHandler` 或
     * `rememberInfiniteTransition` 写回根体 → 第一条断言立刻红；
     * 反过来"把根体删空让判据变绿" → 第二条正向锚点红（见 `内联副作用判据自检`）。
     */
    @Test
    fun `根组合项不得内联系统副作用`() {
        val body = GuardSourceScan.functionBody(source("NbaLiveApp.kt"), "fun NbaLiveApp(")
        val inlined = INLINED_EFFECTS.filter { body.contains(it) }
        assertTrue(
            "根组合项里又出现了内联的副作用调用：${inlined.joinToString()}。\n" +
                "review/03-ui.md P2-18 拆掉的正是这些（根节点只承担「读状态 → 摆骨架」）：\n" +
                "  · 状态老化 / 比分轮询 → AppEffects.kt 的 AppListStatusAgeEffect / AppScoresRefreshEffect\n" +
                "  · 全屏与系统栏 → AppEffects.kt 的 FullscreenSystemEffect\n" +
                "  · 返回键 → AppBackHandling.kt 的 AppBackHandler\n" +
                "副作用写在根体里时，任何一次状态抖动都会连带整棵 Scaffold/顶栏/底栏重组。",
            inlined.isEmpty(),
        )
        assertTrue(
            "根组合项没有装配 AppShell( / RouteHost( —— 第一条判据可以靠「把根体删空」骗过去",
            body.contains("AppShell(") && body.contains("RouteHost("),
        )
    }

    @Test
    fun `内联副作用判据自检 违规样本必须命中而注释样本必须不命中`() {
        fun violations(raw: String): List<String> {
            val body = GuardSourceScan.functionBody(raw, "fun NbaLiveApp(")
            return INLINED_EFFECTS.filter { body.contains(it) }
        }

        val compliant = """
            @Composable
            fun NbaLiveApp(viewModel: MainViewModel) {
                AppListStatusAgeEffect(route, tab) { }
                AppShell(topBar = { }) { RouteHost() }
            }
        """.trimIndent()
        assertTrue("合规样本被误报：${violations(compliant)}", violations(compliant).isEmpty())

        val inlined = """
            @Composable
            fun NbaLiveApp(viewModel: MainViewModel) {
                LaunchedEffect(route) { while (true) { delay(1) } }
                BackHandler(enabled = true) { }
                AppShell(topBar = { }) { RouteHost() }
            }
        """.trimIndent()
        assertTrue(
            "内联 LaunchedEffect / BackHandler 没有被抓到（判据退化成摆设）",
            violations(inlined).isNotEmpty(),
        )

        val commentOnly = """
            @Composable
            fun NbaLiveApp(viewModel: MainViewModel) {
                // LaunchedEffect(route) { }  ← 注释里的调用不算
                AppShell(topBar = { }) { RouteHost() }
            }
        """.trimIndent()
        assertTrue("注释里的 LaunchedEffect 被当成真调用（没剔非代码）", violations(commentOnly).isEmpty())
    }

    /**
     * [GuardSourceScan.functionBody] 的自检：**跳过参数表**再取函数体。
     *
     * 这是所有以"函数体内必须有 X"为形态的守卫的共同前提：
     * Compose 形参默认值里全是 lambda（`onA: (Boolean) -> Unit = {}`），
     * 直接找第一个 `{` 会拿到那个空 lambda，于是"体内"永远看不到任何调用 ——
     * 一条永远不会红的守卫（旧版 180 行判据的自检就是为这件事写的，
     * 现在它随判据一起搬到共享 helper 上，覆盖范围更大）。
     */
    @Test
    fun `functionBody 自检 参数表里的默认 lambda 不能骗过它`() {
        val sample = """
            @Composable
            fun Target(
                viewModel: MainViewModel,
                /** 注释里的花括号 { } 不算代码 */
                onA: (Boolean) -> Unit = {},
            ) {
                AppShell()
                RouteHost()
            }
            fun Other() { println(2) }
        """.trimIndent()
        val body = GuardSourceScan.functionBody(sample, "fun Target(")
        assertTrue(
            "参数表里的默认 lambda 被当成了函数体（体内看不到真调用）：$body",
            body.contains("AppShell()") && body.contains("RouteHost()"),
        )
        assertTrue("函数体提取吃过了函数结尾（把别的函数也吞进来）", !body.contains("fun Other("))
        assertTrue("参数表被算进了函数体（默认 lambda 漏在体内）", !body.contains("onA"))
    }

    // ---------------- 2. 根节点的状态收集数（P2-15） ----------------

    @Test
    fun `根节点状态面必须收敛在显式对象里 且容量有上限`() {
        // ⚠️ 这条守卫在 2026-11-01 被**重写**（review/optimization-review.md H5）。
        //
        // 旧版只数 `fun NbaLiveApp(` 体内的 `collectAsStateWithLifecycle(` 个数（上限 2）。
        // P2-15 的修复把 5 条 flow 搬进了 `rememberAppUiState`，根函数体内只剩 1 处 ——
        // 于是这条守卫**不可能再红**：再来一次"搬到别的文件"就能绕过，
        // 它度量的是"某个函数里的调用点个数"，而不是它声称守住的"根作用域订阅了几条 flow"。
        // 现在改成三件**搬不动**的事：根体内的直接收集数（上限 1，只留给进程级 PipState）、
        // 根节点必须真的经 rememberAppUiState 取状态、以及 AppUiState 的**状态面上限**。
        val appSource = source("NbaLiveApp.kt")
        val direct = rootCollects(appSource)
        assertTrue(
            "fun NbaLiveApp( 体内直接收集了 $direct 条 flow（上限 1：只允许进程级 PipState.inPip）：" +
                "review/03-ui.md P2-15 —— 根节点每多订阅一条 flow，它的每次变化都会" +
                "重新走一遍 Scaffold/顶栏/底栏。\n" +
                "请把根作用域的状态收进 AppUiState/rememberAppUiState，" +
                "或下沉到 RouteHost/AppTopBar/SearchOverlay 内部收集。\n" +
                "（若你把收集搬到**别的文件**来让这条变绿，下面的状态面上限会拦住你。）",
            direct <= 1,
        )
        assertTrue(
            "根节点没有再经 rememberAppUiState(viewModel) 取状态 —— 上面那条可以靠" +
                "「把收集整段删掉」骗过去（改读全局单例照样能编译）",
            GuardSourceScan.stripNonCode(appSource).contains("rememberAppUiState("),
        )

        val uiStateSource = source("AppUiState.kt")
        val fields = appUiStateFields(uiStateSource)
        assertTrue(
            "AppUiState 的状态面已涨到 $fields 个字段（上限 6）：根作用域每多一条 flow，" +
                "它的每次变化都要在根节点的重组里重走 Scaffold/顶栏/底栏（P2-15）。" +
                "新状态请下沉到各路由 composable 内部收集，而不是往这个对象上继续挂。",
            fields in 1..6,
        )
        val collects = helperCollects(uiStateSource)
        assertTrue(
            "rememberAppUiState 收集了 $collects 条 flow，但 AppUiState 只暴露 $fields 个字段 —— " +
                "收集数不得超过字段数（多订阅而不使用 = 白订阅，正是 P2-15 要拦的形态）",
            collects <= fields,
        )
        assertTrue(
            "rememberAppUiState 一条 flow 都没收集（判据退化成摆设）：它必须至少订阅 AppUiState 暴露的状态",
            collects >= 1,
        )
    }

    @Test
    fun `状态收集判据自检 能抓到第三处收集且不误报注释与函数外收集`() {
        val compliant = """
            fun NbaLiveApp(viewModel: MainViewModel) {
                val ui = rememberAppUiState(viewModel)
                val inPip by PipState.inPip.collectAsStateWithLifecycle()
                println("文案里的 collectAsStateWithLifecycle( 不算")
            }
            fun other() { val x by vm.a.collectAsStateWithLifecycle() }
        """.trimIndent()
        assertEquals("合规样本被误报", 1, rootCollects(compliant))

        val violating = """
            fun NbaLiveApp(viewModel: MainViewModel) {
                val a by viewModel.a.collectAsStateWithLifecycle()
                val b by viewModel.b.collectAsStateWithLifecycle()
                val c by viewModel.c.collectAsStateWithLifecycle()
            }
        """.trimIndent()
        assertTrue(
            "三处收集没有被判超限（P2-15 的判据退化了）",
            rootCollects(violating) > 2,
        )

        val commented = """
            fun NbaLiveApp(viewModel: MainViewModel) {
                // val a by viewModel.a.collectAsStateWithLifecycle()
                println(1)
            }
        """.trimIndent()
        assertEquals(
            "注释里的收集被当成真收集（没剔非代码）：删掉真收集、留一句注释就能骗过计数",
            0,
            rootCollects(commented),
        )
    }

    // ---------------- 3. 实现真的搬到新文件了 ----------------

    @Test
    fun `外壳与搜索层的实现必须在新文件里 不能继续长在根文件`() {
        val violations = definitionViolations(source("NbaLiveApp.kt"))
        assertTrue(
            "以下实现又被写回 `NbaLiveApp.kt`（review/03-ui.md P2-18 的拆分被回退）：\n" +
                violations.joinToString("\n").prependIndent("  "),
            violations.isEmpty(),
        )
        // 正向断言：防"两边都没有"被当成合规（负向判据单独用会漏这种形态）
        val chrome = GuardSourceScan.stripNonCode(source("AppChrome.kt"))
        assertTrue(
            "AppChrome.kt 里找不到 SportBottomBar 的实现 —— 搬迁只做了一半",
            chrome.contains("fun SportBottomBar("),
        )
        assertTrue(
            "AppChrome.kt 里找不到 SubPageTopBar 的实现 —— 搬迁只做了一半",
            chrome.contains("fun SubPageTopBar("),
        )
        assertTrue(
            "SearchOverlay.kt 里找不到 SearchOverlay 的实现 —— 搬迁只做了一半",
            GuardSourceScan.stripNonCode(source("SearchOverlay.kt")).contains("fun SearchOverlay("),
        )
        assertTrue(
            "AppBackHandling.kt 里找不到 Compose BackHandler( 的调用 —— 返回键接线没了",
            BACK_HANDLER_CALL.containsMatchIn(GuardSourceScan.stripNonCode(source("AppBackHandling.kt"))),
        )
    }

    @Test
    fun `搬迁判据自检 能抓到留在原文件的定义且不误报调用与注释`() {
        val moved = """
            // fun SportBottomBar(selected: NavTab) { }  ← 注释里的定义不算
            @Composable
            fun NbaLiveApp(viewModel: MainViewModel) {
                SportBottomBar(selected = tab, onSelect = viewModel::selectTab)
            }
            const val note = "fun SearchOverlay( 写在文案里不算"
        """.trimIndent()
        assertTrue(
            "调用 SportBottomBar( / 注释里的定义被误报成「实现还在原文件」：\n" +
                definitionViolations(moved).joinToString("\n"),
            definitionViolations(moved).isEmpty(),
        )

        val stillThere = """
            @Composable
            fun SportBottomBar(selected: NavTab, onSelect: (NavTab) -> Unit) { }
        """.trimIndent()
        assertTrue(
            "实现留在原文件必须被抓到",
            definitionViolations(stillThere).isNotEmpty(),
        )

        val inlineBackHandler = """
            @Composable
            fun NbaLiveApp(viewModel: MainViewModel) {
                BackHandler(enabled = true) { }
            }
        """.trimIndent()
        assertTrue(
            "根文件里直接调 Compose 的 BackHandler( 必须被抓到",
            definitionViolations(inlineBackHandler).isNotEmpty(),
        )
    }

    // ---------------- 5. P2-6：画中画接线的显式顺序 ----------------

    @Test
    fun `画中画的状态写入必须排在粘性开关刷新之前`() {
        val violations = pipOrderViolations(source("AppEffects.kt"))
        assertTrue(
            "review/03-ui.md P2-6 的修法被回退了（顺序不再是显式的）：\n" +
                violations.joinToString("\n").prependIndent("  "),
            violations.isEmpty(),
        )
    }

    @Test
    fun `顺序判据自检 反序与只剩注释都必须被抓到`() {
        val compliant = """
            @Composable
            fun AppPipEffects(activity: MainActivity?, route: Route) {
                LaunchedEffect(route) {
                    PipState.setPlayerActive(PipState.shouldAutoEnterPip(true, true))
                    activity?.syncAutoEnterEnabled()
                }
                DisposableEffect(route) { onDispose { PipState.setPlayerActive(false) } }
            }
        """.trimIndent()
        assertTrue(
            "合规顺序被误报：\n" + pipOrderViolations(compliant).joinToString("\n"),
            pipOrderViolations(compliant).isEmpty(),
        )

        val reversed = """
            @Composable
            fun AppPipEffects(activity: MainActivity?, route: Route) {
                LaunchedEffect(route) {
                    activity?.syncAutoEnterEnabled()
                    PipState.setPlayerActive(PipState.shouldAutoEnterPip(true, true))
                }
            }
        """.trimIndent()
        assertTrue(
            "反序（先刷开关再写状态）必须被抓到 —— 那正是 P2-6 的隐式顺序问题",
            pipOrderViolations(reversed).isNotEmpty(),
        )

        val commentOnly = """
            @Composable
            fun AppPipEffects(activity: MainActivity?, route: Route) {
                LaunchedEffect(route) {
                    // PipState.setPlayerActive(...)  ← 注释里的调用不算
                    activity?.syncAutoEnterEnabled()
                }
            }
        """.trimIndent()
        assertTrue(
            "只在注释里写 setPlayerActive 必须被抓到（剔非代码后没有真调用）",
            pipOrderViolations(commentOnly).isNotEmpty(),
        )
    }

    // ---------------- 4. 非空性锚点 ----------------

    @Test
    fun `扫描必须真的扫到源码与新文件`() {
        val files = productionFiles()
        assertTrue(
            "只扫到 ${files.size} 个生产 kt 文件（应 ≥30，cwd=${TestPaths.workingDirPath()}）—— " +
                "扫描范围走偏时上面几条会静默全绿",
            files.size >= 30,
        )
        for (name in SPLIT_FILES) {
            val lines = GuardSourceScan.uniqueFile(files, name).readText().lines().size
            assertTrue(
                "$name 只有 $lines 行（应 >20）—— 把逻辑「搬」进一个空壳文件也能让" +
                    "「根组合项不再有内联副作用」的判据变绿（那只是把实现搬走、不是拆干净），" +
                    "所以这些文件必须有实际体量，且 §1/§3 的接线判据仍要成立",
                lines > 20,
            )
        }
    }

    // ---------------------------------------------------------------
    // 判据（被测样本与生产源码走**同一个**函数）
    // ---------------------------------------------------------------
    // ⚠️ 原来这里还有一个 `bodyLineCount(rawSource)`：随"函数体 ≤180 行"判据一起删除
    //（B12）。它测的"functionBody 跳过参数表"这件事搬到了 `functionBody 自检 …`
    // 那条用例上，覆盖的是共享 helper 本身（所有"函数体内必须有 X"守卫的共同前提）。

    /** 根节点里的 `collectAsStateWithLifecycle(` 调用点数量（已剔注释与字符串）。 */
    private fun rootCollects(rawSource: String): Int {
        val body = GuardSourceScan.functionBody(rawSource, "fun NbaLiveApp(")
        return Regex("""collectAsStateWithLifecycle\s*\(""").findAll(body).count()
    }

    /**
     * `rememberAppUiState(` 体内的收集点数量（已剔注释与字符串）。
     *
     * ⚠️ 这是 H5 重写后的**搬不动**的那一半：根函数体可以不含收集，
     * 但"这些收集必须真的喂给 AppUiState 的字段"，所以两边一起数。
     */
    private fun helperCollects(rawSource: String): Int =
        Regex("""collectAsStateWithLifecycle\s*\(""")
            .findAll(GuardSourceScan.functionBody(rawSource, "fun rememberAppUiState("))
            .count()

    /**
     * `data class AppUiState(` 的构造参数个数 = **根作用域的状态面**。
     *
     * 为什么用字段数而不是"某个文件里的调用点个数"：字段数是**结构事实**，
     * 谁想再往根作用域挂一条状态，就必须改这个声明，绕不过去。
     * 找不到声明或括号不配对一律 `error()`（守卫静默失效比误报更危险）。
     */
    private fun appUiStateFields(rawSource: String): Int {
        val code = GuardSourceScan.stripNonCode(rawSource)
        val decl = Regex("""data\s+class\s+AppUiState\s*\(""").find(code)
            ?: error("找不到 `data class AppUiState(` —— 守卫会静默失效，所以直接失败")
        var parens = 0
        var cursor = decl.range.last
        while (cursor < code.length) {
            when (code[cursor]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            cursor++
        }
        if (cursor >= code.length) error("`data class AppUiState(` 的参数表括号没配对")
        val params = code.substring(decl.range.last + 1, cursor)
        return Regex("""\bval\s+\w+""").findAll(params).count()
    }

    /** 外壳/搜索层实现是否还在根文件里（返回违规说明，空 = 达标）。 */
    private fun definitionViolations(rawAppSource: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawAppSource)
        val out = mutableListOf<String>()
        for (definition in MOVED_DEFINITIONS) {
            if (code.contains(definition)) out += "$definition 仍在 NbaLiveApp.kt 里"
        }
        if (BACK_HANDLER_CALL.containsMatchIn(code)) {
            out += "NbaLiveApp.kt 里直接调用了 Compose 的 BackHandler("
        }
        return out
    }

    /** P2-6：`AppPipEffects` 里状态写入与粘性开关刷新的顺序（返回违规说明，空 = 达标）。 */
    private fun pipOrderViolations(rawEffectsSource: String): List<String> {
        val body = GuardSourceScan.functionBody(rawEffectsSource, "fun AppPipEffects(")
        val out = mutableListOf<String>()
        val setActive = body.indexOf("PipState.setPlayerActive(")
        val sync = body.indexOf("syncAutoEnterEnabled()")
        if (setActive < 0) out += "AppPipEffects 里没有 PipState.setPlayerActive( —— 进程级播放状态不再落下"
        if (sync < 0) out += "AppPipEffects 里没有 syncAutoEnterEnabled() —— 粘性开关不再被刷新"
        if (setActive >= 0 && sync >= 0 && setActive > sync) {
            out += "syncAutoEnterEnabled() 排在 PipState.setPlayerActive( 之前 —— " +
                "粘性开关又会读到「上一次」的状态，P2-6 要消灭的隐式顺序问题回来了"
        }
        return out
    }

    // ---------------------------------------------------------------
    // 文件定位
    // ---------------------------------------------------------------

    private fun source(name: String): String =
        GuardSourceScan.uniqueFile(productionFiles(), name).readText()

    private fun productionFiles(): List<File> = TestPaths.productionFiles()

    private companion object {
        /**
         * 根组合项体内**不许内联**的副作用调用（B12：把"函数体 ≤180 行"换成行为判据）。
         *
         * 这些是 P2-18 拆到 `AppEffects.kt` / `AppBackHandling.kt` 的东西：
         * 根节点只允许**调用**那些具名效应函数（`AppListStatusAgeEffect(` 等），
         * 不允许自己 `LaunchedEffect` / `DisposableEffect` 起来。
         */
        val INLINED_EFFECTS = listOf(
            "LaunchedEffect(",
            "DisposableEffect(",
            "rememberInfiniteTransition(",
            "BackHandler(",
        )

        /** 拆分后才该存在的文件（非空性锚点：都在且都不是空壳）。 */
        val SPLIT_FILES = listOf(
            "NbaLiveApp.kt",
            "AppChrome.kt",
            "SearchOverlay.kt",
            "AppEffects.kt",
            "AppBackHandling.kt",
            "AppUiState.kt",
        )

        /** 这些实现必须活在 AppChrome.kt / SearchOverlay.kt 里，不许回长到根文件。 */
        val MOVED_DEFINITIONS = listOf(
            "fun SportBottomBar(",
            "fun SearchOverlay(",
            "fun AppShell(",
            "fun SubPageTopBar(",
            "fun BrandTopBar(",
            "fun MissingMatchPlaceholder(",
        )

        /** Compose 的返回键 API：根文件不该直接碰它（接线在 AppBackHandling.kt）。 */
        val BACK_HANDLER_CALL = Regex("""\bBackHandler\s*\(""")
    }
}