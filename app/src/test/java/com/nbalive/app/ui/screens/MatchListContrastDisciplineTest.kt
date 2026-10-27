package com.nbalive.app.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 列表页「强调底上的文字不许用半透明白」的**调用点**守卫
 * （review/03-ui.md P1-2 / P1-4）。
 *
 * ## 为什么需要扫源码
 *
 * [com.nbalive.app.ui.theme.AccentContrastTest] 只证明策略常量本身达标。
 * 但真正决定对比度的是**界面里有没有用那个常量** ——
 * 回归时最常见的写法是"这里淡一点好看"，直接写回 `Color.White.copy(alpha = 0.7f)`，
 * 而这类改动没有任何编译期或运行期信号。
 *
 * 同一个道理在 `LoggingDisciplineTest`（日志脱敏）里已经用过一次：
 * **函数有测试，使用纪律没有测试**，是这类缺陷最常见的形态。
 *
 * ⚠️ 扫描范围只限 `MatchListScreen.kt`：
 * 那里是"饱和色底 + 白字"的重灾区（hero 卡、筛选胶囊）。
 * 全工程扫描会误伤正常的半透明层（例如遮罩、描边），价值反而下降。
 */
class MatchListContrastDisciplineTest {

    private fun target(): File? {
        val candidates = listOf(
            File("src/main/java/com/nbalive/app/ui/screens/MatchListScreen.kt"),
            File("app/src/main/java/com/nbalive/app/ui/screens/MatchListScreen.kt"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app/ui/screens/MatchListScreen.kt"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** 取代码行（跳过注释），避免把 doc 里的反例判成违规。 */
    private fun codeLines(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            ) {
                null
            } else {
                index + 1 to trimmed
            }
        }

    @Test
    fun `列表页没有直接使用半透明白色`() {
        val file = target()
        // 找不到源码时跳过（而不是失败）：守卫不能在打包环境里误报
        assumeTrue("找不到 MatchListScreen.kt，跳过源码扫描", file != null)
        val violations = codeLines(file!!)
            .filter { (_, text) -> ALPHA_WHITE.containsMatchIn(text) }
            .map { (line, text) -> "MatchListScreen.kt:$line  $text" }
        assertTrue(
            "强调底上的半透明白字达不到 WCAG AA 4.5:1（压暗函数把底色调到达标线后，\n" +
                "半透明白字又把对比度还了回去）。\n" +
                "请改用 AccentContrast.OnAccent（不透明纯白）；\n" +
                "半透明**底色**请用 AccentContrast.HeroPillBackground（20% 黑）。\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `守卫本身有效 不会变成永远全绿的摆设`() {
        // 防止上面那条扫描退化成摆设：用内存样本确认它真的能抓到违规写法
        val bad = "color = Color.White.copy(alpha = 0.7f),"
        val good = "color = AccentContrast.OnAccent,"
        assertTrue("必须能抓到半透明白字", ALPHA_WHITE.containsMatchIn(bad))
        assertTrue("不能误报策略常量", !ALPHA_WHITE.containsMatchIn(good))
    }

    private companion object {
        /** `Color.White.copy(alpha = …)` —— 强调底上的半透明白字。 */
        val ALPHA_WHITE = Regex("""Color\.White\.copy\(alpha\s*=\s*0?\.\d+f?\)""")
    }
}
