package com.nbalive.app.ui.theme

import com.nbalive.app.GuardSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 系统栏（状态栏 / 导航栏）图标配色守卫 —— review/03-ui.md **P1-3**。
 *
 * ## 为什么必须有这条判据
 *
 * 现象：浅色主题下底部系统导航键仍是**白色图标**，压在浅色 surface 上几乎看不见，
 * 用户以为返回/主页键坏了。旧实现只设了 `isAppearanceLightStatusBars`，
 * 漏掉了同一个问题的另一半 `isAppearanceLightNavigationBars`。
 *
 * 修复只有两行赋值（`Theme.kt` 的 `NbaLiveTheme` -> SideEffect 里）：
 * ```kotlin
 * controller.isAppearanceLightStatusBars = !darkTheme
 * controller.isAppearanceLightNavigationBars = !darkTheme
 * ```
 * 但 JVM 单测拿不到真实的 `WindowInsetsController`（需要 instrumentation / 真机），
 * 所以这条 P1 在修复后的很长一段时间里**零自动化信号** —— 谁把那两行删掉都不会红。
 * 这里退到**函数体级源码判据**：截 `NbaLiveTheme` 的函数体（剔注释/字符串），
 * 断言两个赋值都在、都跟随 `!darkTheme`。
 *
 * ⚠️ 反面判据也必须红：把 `!darkTheme` 改成 `false`（图标永远白色 = 旧缺陷）会变红。
 */
class SystemBarsAppearanceTest {

    private val themeText: String = productionFile("ui/theme/Theme.kt").readText()

    @Test
    fun `浅色主题必须同时把状态栏和导航栏图标切成深色`() {
        // 非空性锚点：扫到的确实是主题实现文件
        assertTrue("扫错文件了（找不到 NbaLiveTheme）：${themeText.take(80)}", themeText.contains("fun NbaLiveTheme("))

        val body = GuardSourceScan.functionBody(themeText, "fun NbaLiveTheme(")
        assertEquals("状态栏图标必须随主题切换（旧缺陷：只剩这一半也不行）", 1, countAssignment(body, "isAppearanceLightStatusBars"))
        assertEquals("导航栏图标必须随主题切换（P1-3 的原缺陷就是漏了它）", 1, countAssignment(body, "isAppearanceLightNavigationBars"))
    }

    @Test
    fun `两个系统栏赋值都必须真的跟随 darkTheme 而不是写死`() {
        val body = GuardSourceScan.functionBody(themeText, "fun NbaLiveTheme(")

        assertTrue(
            "isAppearanceLightStatusBars 必须赋成 `!darkTheme`（写死 false/true 都会让浅色主题下图标不可见）",
            Regex("""isAppearanceLightStatusBars\s*=\s*!darkTheme""").containsMatchIn(body),
        )
        assertTrue(
            "isAppearanceLightNavigationBars 必须赋成 `!darkTheme`（P1-3 的判据）",
            Regex("""isAppearanceLightNavigationBars\s*=\s*!darkTheme""").containsMatchIn(body),
        )
    }

    /** 计数形如 `名称 = …` 的赋值（已剔注释/字符串，声明行不会命中）。 */
    private fun countAssignment(code: String, name: String): Int =
        Regex("""\b""" + Regex.escape(name) + """\s*=""").findAll(code).count()

    private fun productionFile(relative: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        return listOf(
            File("src/main/java/com/nbalive/app/$relative"),
            File("app/src/main/java/com/nbalive/app/$relative"),
            File(cwd, "src/main/java/com/nbalive/app/$relative"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${File(cwd).absolutePath}）—— 守卫会静默全绿，所以直接失败")
    }
}