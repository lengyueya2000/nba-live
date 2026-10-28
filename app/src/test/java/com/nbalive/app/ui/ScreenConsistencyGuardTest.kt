package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 一致性守卫：无障碍动作词、颜色字面量、时间口径、底部留白
 * （review/03-ui.md P2-26 / P2-30 / P2-32 / P2-34）。
 *
 * ## 为什么这几条要写成源码守卫而不是纯判据
 *
 * 这四条的共同形态是**"改动不会产生任何编译期/运行期信号"**：
 *  - 少写一个 `onClickLabel`：编译器不管，只有读屏用户能感觉到；
 *  - 颜色写回字面量：两处"同一配方各写一份"，改配色时只改到一半；
 *  - 显示回源站原始 `timeLabel`：把 `hhmm()` 里"JRS 直播行假 00:00 不上屏"
 *    的防护绕过去了，肉眼看不出来；
 *  - 底部再叠一层 96dp：只是滚动到底多一块空白，没人会为此提 bug。
 *
 * 所以判据落在**调用点**上（这也是本项目已经用过两次的形态：
 * `LoggingDisciplineTest` 守日志脱敏、`MatchListContrastDisciplineTest` 守对比度）。
 *
 * ⚠️ 三条铁律都用上了：断言前剔注释、匹配调用而不是字符串出现、
 * 找不到源码目录直接 error() 而不是静默跳过。
 */
class ScreenConsistencyGuardTest {

    @Test
    fun `hero 卡必须给读屏用户一个动作词`() {
        val code = stripComments(screenFile("MatchListScreen.kt").readText())
        val body = functionBody(code, "LiveHeroCard")
            ?: error("找不到 LiveHeroCard，守卫会静默全绿")
        assertTrue(
            "hero 卡整卡可点却没有 onClickLabel：TalkBack 会先读一长串队名/时间/线路数，\n" +
                "最后只说'双击激活'，用户不知道会发生什么（review/03-ui.md P2-26）。\n" +
                "按按钮类节点应写 role = Role.Button。实际函数体：\n$body",
            // 动作词只能来自 `CardSemantics.ACTION_LABEL` 这一个常量（review/audit-guards.md B6：
            // 原来这里是 `CardSemantics.actionLabel(hasInnerButton = …)`，形参被实现忽略）。
            // 也不再接受就地写字符串字面量 —— 那等于把"统一动作词"又拆成两份。
            body.contains("onClickLabel = CardSemantics.ACTION_LABEL"),
        )
        assertTrue(
            "hero 卡应声明 role = Role.Button（卡片整体是播放入口，不是普通文字）：\n$body",
            body.contains("role = Role.Button"),
        )
    }

    @Test
    fun `hero 卡背景必须用主题的白字安全渐变 GradLiveOnWhite`() {
        // B11（review/audit-guards.md）：hero 卡的 AA 4.5:1 断言（AccentContrastTest）
        // 遍历的是 HeroGradientStops，而生产真正画在屏幕上的底是 GradLiveOnWhite。
        // 两者现在都派生自 Theme.kt 的 LiveGradientSource，但**派生链**只保证数值同源；
        // 这条守的是"生产确实消费了它" —— 否则对比度守卫测的还是一份没人用的副本。
        val code = stripComments(screenFile("MatchListScreen.kt").readText())
        val body = functionBody(code, "LiveHeroCard")
            ?: error("找不到 LiveHeroCard，守卫会静默全绿")
        assertTrue(
            "hero 卡的背景没有用 GradLiveOnWhite（主题里唯一来源派生的白字安全版）：\n" +
                "一旦这里换回原始亮度渐变或自带字面量，AccentContrastTest 的 3 条\n" +
                "“hero 卡 AA 4.5:1”就又只是测一份平行副本了（review/audit-guards.md B11）。\n" +
                "实际函数体：\n$body",
            HERO_BACKGROUND.containsMatchIn(body),
        )
    }

    @Test
    fun `screens 里不得出现颜色字面量`() {
        // HeroPill 的 Color(0x33000000) 与 AccentContrast.HeroPillBackground 是同一配方，
        // 各写一份 → 改配色时必然只改到一半（review/03-ui.md P2-30）。
        val offenders = screens().filter { stripComments(it.readText()).contains("Color(0x") }
            .map { it.name }
        assertTrue(
            "以下 screen 里出现了颜色字面量（Color(0x…)）：应改用主题/AccentContrast 里的语义常量：\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `screens 不得直接渲染源站 timeLabel`() {
        // 列表页/直播页/搜索页原来各显示一种样子；timeLabel 直出还会绕过
        // hhmm() 里"JRS 直播行假 00:00 不上屏"的防护（review/03-ui.md P2-32）。
        // 正确写法：match.hhmm() / match.displayTime()。
        val offenders = screens().mapNotNull { f ->
            val code = stripComments(f.readText())
            val lines = code.lines().withIndex()
                .filter { (_, l) -> Regex("""\btimeLabel\b""").containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1}  ${l.trim()}" }
            if (lines.isEmpty()) null else lines.joinToString("\n")
        }
        assertTrue(
            "以下位置直接渲染了源站 timeLabel（同一场比赛在三个页面显示三种样子，\n" +
                "且绕过了 hhmm() 的假 00:00 防护）：请改用 hhmm()／displayTime()：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `详情页不得再叠加 96dp 底部留白`() {
        // 详情页是 Scaffold 的 content，外层 padding 已含 bottomBar 高度；
        // 再补 96dp → 滚到底是一大块空白（review/03-ui.md P2-34）。
        val code = stripComments(screenFile("MatchDetailScreen.kt").readText())
        assertFalse(
            "MatchDetailScreen 又出现了 96.dp 底部留白：Scaffold 的 innerPadding 已经\n" +
                "包含 bottomBar 高度，叠加会滚到底多出一大块空白（review/03-ui.md P2-34）",
            code.contains("96.dp"),
        )
    }

    @Test
    fun `扫描必须真的扫到 screens 源码`() {
        val files = screens()
        assertTrue("扫不到 screens 源码，上面几条会'零违规'地静默全绿", files.size >= 5)
        assertTrue(
            "没扫到 MatchListScreen.kt",
            files.any { it.name == "MatchListScreen.kt" },
        )
        assertTrue("没扫到 PlayerScreen.kt", files.any { it.name == "PlayerScreen.kt" })
    }

    // ---------------------------------------------------------------

    private fun screens(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui/screens"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui/screens"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 screens 目录，cwd=$cwd，候选=${roots.map { it.path }}")
        return root.listFiles { f -> f.isFile && f.extension == "kt" }?.toList() ?: emptyList()
    }

    private fun screenFile(name: String): File =
        // 审计「未验证的怀疑 #7」：按文件名取第一个 → 同名文件时静默守错。现在唯一命中否则 error()。
        GuardSourceScan.uniqueFile(screens(), name)

    /** 取 `private fun <name>(...) { ... }` 的花括号体（平衡括号，含嵌套 lambda）。 */
    private fun functionBody(code: String, name: String): String? {
        val marker = Regex("""(private )?fun $name\s*\(""")
        val m = marker.find(code) ?: return null
        // 注意从 `(` 本身开始扫：从括号之后开始的话，参数表里的 `()` 会让
        // 深度变成 -1，于是所有带 lambda 参数的函数都取不到函数体（这个坑真踩过）
        var i = m.range.last
        var paren = 0
        var open = -1
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '=' -> if (paren == 0) return null
                '{' -> if (paren == 0) {
                    open = i
                    break
                }
            }
            i++
        }
        if (open < 0) return null
        var depth = 0
        i = open
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
        return code.substring(open + 1)
    }

    /** 剔注释与字符串字面量：词法级实现（[GuardSourceScan]），保留行号；铁律①。 */
    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)

    private companion object {
        /**
         * hero 卡的背景调用：`.background(GradLiveOnWhite)`，
         * 允许 `brush = ` 具名实参与尾部逗号（等价写法不放行才合理 ——
         * 放行的应该是同一个来源，不是同一个字符串）。
         */
        val HERO_BACKGROUND = Regex("""\.background\(\s*(?:brush\s*=\s*)?GradLiveOnWhite\s*[,)]""")
    }
}