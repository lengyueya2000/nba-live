package com.nbalive.app.ui

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

    // ---------------- 1. 根组合项的函数体行数 ----------------

    @Test
    fun `根组合项函数体不得超过 180 行`() {
        val lines = bodyLineCount(source("NbaLiveApp.kt"))
        assertTrue(
            "fun NbaLiveApp( 的函数体有 $lines 行（上限 180）：review/03-ui.md P2-18 拆掉的" +
                "路由分发/副作用/外壳如果被「顺手加回」根节点，这里会先变红。\n" +
                "请把新逻辑放进 RouteHost（路由）、AppEffects.kt（副作用）、" +
                "AppChrome.kt（外壳）或 SearchOverlay.kt。",
            lines <= 180,
        )
    }

    @Test
    fun `函数体行数判据自检 参数表里的 lambda 与注释不能骗过它`() {
        // ⚠️ 这正是本仓库踩过的坑：Compose 形参默认值里全是 lambda（`= {}`），
        // 直接找第一个 `{` 会把那个空 lambda 当函数体，于是行数永远很小 ——
        // 一条永远不会红的守卫。
        val compliant = """
            @Composable
            fun NbaLiveApp(
                viewModel: MainViewModel,
                /** 注释里的花括号 { } 不算代码 */
                onDarkModeChange: (Boolean) -> Unit = {},
                onAspectChange: (AspectMode) -> Unit = {},
            ) {
                println(1)
            }
            fun NbaLiveAppHelper() { println(2) }
        """.trimIndent()
        assertEquals(
            "参数表里的 lambda 被当成了函数体（正确应为 3 行：`{` / `println(1)` / `}`）",
            3,
            bodyLineCount(compliant),
        )

        // 违规样本：200 行函数体必须判超限
        val oversized = buildString {
            append("@Composable\nfun NbaLiveApp(\n    viewModel: MainViewModel = {},\n) {\n")
            repeat(200) { append("    println(1)\n") }
            append("}\n")
        }
        assertTrue(
            "200 行的函数体没有被判超限（判据退化成摆设）",
            bodyLineCount(oversized) > 180,
        )

        // 反向锚点：**另一个**函数的长函数体不算（判据必须锚在 NbaLiveApp 上）
        val otherLong = buildString {
            append("fun NbaLiveApp() { println(1) }\n")
            append("fun somethingElse() {\n")
            repeat(300) { append("    println(2)\n") }
            append("}\n")
        }
        assertEquals(
            "把别的函数的长度算到了 NbaLiveApp 头上（它的体只有 1 行）",
            1,
            bodyLineCount(otherLong),
        )
    }

    // ---------------- 2. 根节点的状态收集数（P2-15） ----------------

    @Test
    fun `根节点里的状态收集不得超过 2 处`() {
        val collects = rootCollects(source("NbaLiveApp.kt"))
        assertTrue(
            "fun NbaLiveApp( 里 collectAsStateWithLifecycle( 出现了 $collects 处（上限 2）：" +
                "review/03-ui.md P2-15 —— 根节点每多收集一条 flow，它的每次变化都会" +
                "重新走一遍 Scaffold/顶栏/底栏。\n" +
                "请在 RouteHost（列表/详情/播放状态）或 AppTopBar/SearchOverlay 内部收集。",
            collects <= 2,
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
            "只扫到 ${files.size} 个生产 kt 文件（应 ≥30，cwd=${System.getProperty("user.dir")}）—— " +
                "扫描范围走偏时上面几条会静默全绿",
            files.size >= 30,
        )
        for (name in SPLIT_FILES) {
            val lines = GuardSourceScan.uniqueFile(files, name).readText().lines().size
            assertTrue(
                "$name 只有 $lines 行（应 >20）—— 把逻辑「搬」进一个空壳文件也能让" +
                    "「根组合项变短」变绿，所以这条要有下限",
                lines > 20,
            )
        }
    }

    // ---------------------------------------------------------------
    // 判据（被测样本与生产源码走**同一个**函数）
    // ---------------------------------------------------------------

    /**
     * `fun NbaLiveApp(` 函数体的行数。
     *
     * 用共享的 [GuardSourceScan.functionBody]：它先配对跳过**参数表**再找函数体的 `{`，
     * 所以形参默认值里的 lambda、KDoc 里的花括号都不会算错 —— 本仓库在别处
     * 踩过"直接找第一个 `{` 拿到空 lambda"的坑（守卫永远绿）。
     * 找不到声明/括号不配对照样 `error()`，不会静默返回 0。
     */
    private fun bodyLineCount(rawSource: String): Int =
        GuardSourceScan.functionBody(rawSource, "fun NbaLiveApp(").lines().size

    /** 根节点里的 `collectAsStateWithLifecycle(` 调用点数量（已剔注释与字符串）。 */
    private fun rootCollects(rawSource: String): Int {
        val body = GuardSourceScan.functionBody(rawSource, "fun NbaLiveApp(")
        return Regex("""collectAsStateWithLifecycle\s*\(""").findAll(body).count()
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

    private fun productionFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到生产源码目录（cwd=$cwd，候选=${candidates.map { it.path }}）—— " +
                    "本文件所有守卫会静默全绿，所以直接失败",
            )
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
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