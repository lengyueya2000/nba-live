package com.nbalive.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航宿主 / 偏好写入的职责边界守卫（review/03-ui.md P2-4 / P2-9 / P2-16 / P2-17 / P2-31 / P2-35）。
 *
 * ## 为什么是源码守卫
 *
 * 这 6 条都没有"点一下就能看见"的行为 bug，它们是**同一条规则写了两遍**、
 * **写好了却没人用**、**注释与实现相反**这几种形态：
 * 普通单测永远是绿的，只有扫源码看调用点/注释才能发现。
 *
 * ## 这个仓库踩过的坑（本文件一律按这三条写）
 *
 *  1. 断言前**剔除注释**：注释里常写着"为什么这么改"，含同样的字面量；
 *  2. 匹配**调用**而不是字符串出现：`import` 行会让守卫永远绿；
 *  3. 路径解析失败 **error() 硬失败**，绝不 assumeTrue 静默跳过（那等于守卫不存在）。
 */
class NavHostGuardTest {

    // ---------------- P2-4：onNewIntent 是只调 setIntent 的死代码 ----------------

    @Test
    fun `onNewIntent 不能是只调 setIntent 的死代码`() {
        val code = stripComments(mainActivity().readText())
        assertTrue("没读到 MainActivity 的正文，守卫会静默全绿", code.contains("class MainActivity"))

        // 已删除 = 达标；将来真加深链时，这里要求它**真的读** intent
        if (!code.contains("override fun onNewIntent")) return

        assertTrue(
            "MainActivity 声明了 onNewIntent，却没有任何读取 intent 的代码" +
                "（Manifest 的 intent-filter 只有 MAIN/LAUNCHER，没有 VIEW/BROWSABLE 深链）：" +
                "只调 setIntent(intent) 是纯死代码，而注释把它写成了深链准备，" +
                "后来者会以为深链已经支持（review/03-ui.md P2-4）。" +
                "要么删掉这个空实现，要么真的消费 intent。",
            INTENT_READ.containsMatchIn(code),
        )
    }

    // ---------------- P2-9：三个 remember 的其中两个已经冗余 ----------------

    @Test
    fun `列表派生值必须直接读 state 不能再包一层 remember`() {
        val code = stripComments(nbaLiveApp().readText())
        assertTrue(
            "visibleMatches 应直接读 listState.visibleMatches：MainViewModel 里它已经是" +
                " data class 的 val（构造时求值一次），再 remember 不产生任何收益，" +
                "只多持一份引用，还让读者以为这里必须 remember（review/03-ui.md P2-9）",
            code.contains("val visibleMatches = listState.visibleMatches"),
        )
        assertTrue(
            "counts 同上：应直接读 listState.counts（P2-9）",
            code.contains("val leagueCounts = listState.counts"),
        )
        assertFalse(
            "NbaLiveApp 又把 visibleMatches/counts 包回了 remember：\n" +
                "那是 getter 时代的旧问题（已在 MainViewModel 源头修掉），P2-9 明确要求删掉这层",
            REMEMBERED_DERIVED.containsMatchIn(code),
        )
    }

    @Test
    fun `派生值必须在 MainViewModel 里算一次而不是 getter`() {
        // P2-9 的**前提**：删掉这层 remember 之所以安全，是因为源头已经是 val。
        // 若哪天有人把它改回 getter，上面的直接读取就会变成每帧分配 ——
        // 这条守卫把那个前提钉住，避免"修法在源头被回退、守卫还全绿"。
        val code = stripComments(mainViewModel().readText())
        assertTrue(
            "MainViewModel.visibleMatches 必须是 data class 构造时求值的 val（P2-9）",
            Regex("""val visibleMatches: List<Match>\s*=""").containsMatchIn(code),
        )
        assertTrue(
            "MainViewModel.counts 必须是 data class 构造时求值的 val（P2-9）",
            Regex("""val counts: Map<League, Int>\s*=""").containsMatchIn(code),
        )
        assertFalse(
            "visibleMatches/counts 又被写成 getter 了：界面每次重组读它都会新建 List/Map（P2-9）",
            Regex("""val (visibleMatches|counts)[^\n]*get\(\)""").containsMatchIn(code),
        )
    }

    // ---------------- P2-16：null 该怎么解释只能有一份实现 ----------------

    @Test
    fun `null 怎么解释必须两处都走同一个纯函数`() {
        for (file in listOf(mainActivity(), nbaLiveApp())) {
            val code = stripComments(file.readText())
            assertTrue(
                "${file.name} 没有调用 resolveDarkMode(...)：null→深色是**一条业务规则**，" +
                    "不允许在 MainActivity 与 NbaLiveApp 各写一份（review/03-ui.md P2-16）。" +
                    "将来规则一变，漏改一处就会出现「主题是浅色、状态栏图标按深色画」的错配。",
                code.contains("resolveDarkMode("),
            )
            assertFalse(
                "${file.name} 里还留着自己写的 `darkPref ?: true` 兜底：" +
                    "同一业务规则两份实现（review/03-ui.md P2-16），必须改为调用 resolveDarkMode。",
                DARK_PREF_FALLBACK.containsMatchIn(code),
            )
        }
    }

    @Test
    fun `AppPrefs 的注释不得再宣称 null 等于跟随系统`() {
        // 这条故意**不剔注释** —— 它查的就是注释本身（P2-16：注释与行为相反）。
        val raw = appPrefs().readText()
        assertFalse(
            "AppPrefs 又宣称「null = 跟随系统」了：实现（resolveDarkMode）把 null 解释成" +
                "**深色**，注释与行为相反会让后来者照注释改代码（review/03-ui.md P2-16）。",
            LYING_COMMENT.containsMatchIn(raw),
        )
        assertTrue(
            "AppPrefs 必须写明 null 的真实语义（未手动设置过 → 深色）并指向唯一判定处 resolveDarkMode",
            raw.contains("未手动设置过") && raw.contains("resolveDarkMode"),
        )
    }

    // ---------------- P2-17：UI 层不能拿 LocalContext 写 SharedPreferences ----------------

    @Test
    fun `Composable 不得把 Context 传给偏好写入`() {
        val code = stripComments(nbaLiveApp().readText())
        for (call in listOf("AppPrefs.setDarkMode(", "PlayerPrefs.setAspect(")) {
            assertFalse(
                "Composable 里直接调 $call 写 SharedPreferences：把 Android Context 这层依赖" +
                    "漏进了 UI（review/03-ui.md P2-17），也无法在纯 Compose 单测里验证。\n" +
                    "落盘应由平台层（MainActivity）负责，UI 只回调。",
                code.contains(call),
            )
        }
    }

    @Test
    fun `偏好落盘必须由平台层完成且回调真的接到界面上`() {
        val activityCode = stripComments(mainActivity().readText())
        assertTrue(
            "偏好落盘必须由平台层完成（否则开关点了不落盘）：" +
                "MainActivity 里应出现 AppPrefs.setDarkMode( 与 PlayerPrefs.setAspect(",
            activityCode.contains("AppPrefs.setDarkMode(") && activityCode.contains("PlayerPrefs.setAspect("),
        )
        assertTrue(
            "MainActivity 必须把两个落盘回调传给 NbaLiveApp（onDarkModeChange= / onAspectChange=）",
            activityCode.contains("onDarkModeChange =") && activityCode.contains("onAspectChange ="),
        )

        val uiCode = stripComments(nbaLiveApp().readText())
        assertTrue(
            "ProfileScreen 的深色开关必须接到参数上，不能传常量或空实现（P2-17）",
            Regex("""onDarkModeChange\s*=\s*onDarkModeChange\b""").containsMatchIn(uiCode),
        )
        assertEquals(
            "画面模式有**两个**调用点（我的页 + 播放页），必须都接到参数上（P2-17）",
            2,
            Regex("""onAspectChange\s*=\s*onAspectChange\b""").findAll(uiCode).count(),
        )
    }

    // ---------------- P2-31：AppPrefs 的 loaded 短路与 PlayerPrefs 约定相反 ----------------

    @Test
    fun `AppPrefs 不得再用 loaded 短路 init`() {
        val code = stripComments(appPrefs().readText())
        assertFalse(
            "AppPrefs.init 又用 `loaded` 短路了（review/03-ui.md P2-31）：与 PlayerPrefs 的" +
                "明确约定相反 —— object 单例在任何早于 MainActivity.onCreate 的读取里会拿到" +
                "初值 null（消费方解释成深色），与用户设置不符。" +
                "SharedPreferences 本身已在内存里，每次读开销可忽略。",
            Regex("""\bloaded\b""").containsMatchIn(code),
        )
        assertTrue(
            "init 必须真的从 SharedPreferences 还原偏好（走 darkModeFromStorage）",
            code.contains("darkModeFromStorage("),
        )
    }

    // ---------------- P2-35：无「更多」时的占位与左侧不等宽 ----------------

    @Test
    fun `无更多时的占位必须用 SubPageBarSideSlot`() {
        val code = stripComments(nbaLiveApp().readText())
        val body = functionBody(code, "fun SubPageTopBar(")

        assertTrue(
            "无「更多」时的占位必须用 SubPageBarSideSlot，不能写裸 dp：" +
                "左侧返回键的**布局**尺寸被 minimumInteractiveComponentSize() 撑到 48dp，" +
                "右侧占位只有 40dp 时，两侧 weight 分到不等宽的剩余空间，" +
                "标题中心相对屏幕中心左偏 4dp（review/03-ui.md P2-35）",
            Regex("""Spacer\(\s*Modifier\.size\(\s*SubPageBarSideSlot\s*\)\s*\)""").containsMatchIn(body),
        )
        assertFalse(
            "SubPageTopBar 里又出现了裸 dp 的 Spacer 占位：与左侧 48dp 不等宽，标题会左偏（P2-35）",
            Regex("""Spacer\(\s*Modifier\.size\(\s*\d+(\.\d+)?\.dp\s*\)\s*\)""").containsMatchIn(body),
        )
    }

    @Test
    fun `顶栏侧位常量必须是 48dp`() {
        val code = stripComments(nbaLiveApp().readText())
        val slot = Regex("""SubPageBarSideSlot\s*=\s*(\d+)\.dp""").find(code)
            ?: error("找不到 SubPageBarSideSlot 常量定义 —— 守卫会静默全绿（P2-35）")
        assertEquals(
            "顶栏侧位必须等于 minimumInteractiveComponentSize 的最小触摸目标 48dp：" +
                "左侧按钮的布局尺寸就是被它撑到 48dp 的，占位写 40dp 标题就会左偏 4dp（P2-35）",
            48,
            slot.groupValues[1].toInt(),
        )
    }

    // ---------------- 扫描自检 ----------------

    @Test
    fun `扫描必须真的扫到目标源码`() {
        val root = productionRoot()
        assertTrue("扫描不到生产源码（cwd=${System.getProperty("user.dir")}），守卫会静默全绿", root.walkTopDown().count { it.isFile && it.extension == "kt" } >= 10)
        assertTrue("扫描不到 MainActivity.kt", source("MainActivity.kt").isFile)
        assertTrue("扫描不到 NbaLiveApp.kt", source("NbaLiveApp.kt").isFile)
        assertTrue("扫描不到 AppPrefs.kt", source("AppPrefs.kt").isFile)
    }

    // ---------------- 工具 ----------------

    private fun productionRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd，候选=${candidates.map { it.path }}）—— 守卫会静默全绿")
    }

    private fun source(fileName: String): File {
        val root = productionRoot()
        return root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
            ?: error("在 ${root.path} 下找不到 $fileName —— 守卫不能静默跳过")
    }

    private fun mainActivity() = source("MainActivity.kt")
    private fun nbaLiveApp() = source("NbaLiveApp.kt")
    private fun appPrefs() = source("AppPrefs.kt")
    private fun mainViewModel() = source("MainViewModel.kt")

    /**
     * 提取以 [marker] 起始的函数花括号体。
     *
     * ⚠️ marker 必须带左括号（`fun SubPageTopBar(`），否则会命中同前缀函数
     * （这个仓库踩过：`fun LiveDot` 命中了 `fun LiveDotPhase(`）。
     */
    private fun functionBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        require(start >= 0) { "源码里找不到 $marker —— 守卫会静默全绿" }
        var i = start + marker.length - 1
        require(i < code.length && code[i] == '(') { "marker 必须以左括号结尾：$marker" }
        var paren = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                // 参数表之外出现 `=` 说明是表达式体函数，没有花括号体
                '=' -> if (paren == 0) return ""
                '{' -> if (paren == 0) return balanced(code, i)
            }
            i++
        }
        error("$marker 找不到函数体 —— 守卫会静默全绿")
    }

    private fun balanced(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        error("花括号不配对 —— 守卫会静默全绿")
    }

    /** 去掉行注释与块注释（注释里写同样的字面量会让守卫永远全绿）。 */
    private fun stripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")

    private companion object {
        /** 真的读了 activity 的 intent（不是只 setIntent）。 */
        val INTENT_READ = Regex("""getIntent\(\)|intent\.[A-Za-z]|intent\?\.\w""")

        /** 「把派生值重新包一层 remember」。 */
        val REMEMBERED_DERIVED =
            Regex("""remember\(\s*listState\.(matches|selectedLeague)[^)]*\)\s*\{\s*listState\.(visibleMatches|counts)\s*\}""")

        /** 两处各写一遍的 null 兜底。 */
        val DARK_PREF_FALLBACK = Regex("""darkPref\s*\?:\s*(true|false)""")

        /** AppPrefs 注释里那句与实现相反的谎话。 */
        val LYING_COMMENT = Regex("""null\s*=\s*跟随系统""")
    }
}