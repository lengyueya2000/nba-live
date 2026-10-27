package com.nbalive.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强调色底上的前景配色必须真的达到 WCAG AA（review/03-ui.md P1-2 / P1-4）。
 *
 * ## 要解决什么
 *
 * hero 卡（直播中的大卡）整张卡压着红橙渐变，上面全是白字。
 * 渐变本身用 [whiteTextSafe] 压暗过，白字是达标的 —— 但卡上**不是纯白字**：
 *
 * | 位置 | 原取值 | 实测对比度 |
 * |---|---|---|
 * | 联赛名 | 92% 白 | 4.03~4.07:1 ❌ |
 * | "VS" | 70% 白 | 2.85~3.05:1 ❌ |
 * | "立即观看"胶囊底 | 22% 白 | 底被提亮成 #C4845A~#E8586D，压纯白字只有 3.09~3.47:1 ❌ |
 *
 * 典型症状是"压暗函数把底色调到达标线，紧接着的半透明白字又把对比度还回去"。
 *
 * 联赛筛选胶囊的场次计数同病：选中态底是 [accentBrush]（已压暗、纯白达标），
 * 但计数用了 80% 白，实测只有 3.35:1，而它是 10.sp 的小字 —— 最需要看清的一处。
 *
 * ## 修法
 *
 * 把"强调底上该用什么前景色"收进一个可单测的策略对象 [AccentContrast]：
 *  - 强调底上的正文一律**不透明纯白**（半透明白字在压暗底上必然掉出 AA）；
 *  - hero 卡的药丸底改用 **20% 黑**（与 HeroPill 同一配方，实测 6.4:1），
 *    而不是 22% 白 —— 后者是把底**提亮**，方向正好相反。
 *
 * ⚠️ 这些断言只保证"策略常量是对的"；真正会不会有人绕过策略、
 * 又写回 `Color.White.copy(alpha = 0.7f)`，由
 * `MatchListContrastDisciplineTest` 扫源码守住。
 */
class AccentContrastTest {

    @Test
    fun `hero 卡的白色正文在渐变每一档都达到 AA 4点5比1`() {
        for (stop in AccentContrast.HeroGradientStops) {
            val ratio = AccentContrast.contrast(AccentContrast.OnAccent, stop)
            assertTrue(
                "hero 卡正文在渐变档 $stop 上只有 ${"%.2f".format(ratio)}:1，达不到 AA 4.5:1",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `hero 卡药丸底合成后 白字要达到 AA 4点5比1`() {
        for (stop in AccentContrast.HeroGradientStops) {
            // 药丸底是半透明的，必须先叠到渐变上再算对比度
            val composited = AccentContrast.composite(AccentContrast.HeroPillBackground, stop)
            val ratio = AccentContrast.contrast(Color.White, composited)
            assertTrue(
                "hero 卡药丸底叠到渐变档 $stop 后，白字只有 ${"%.2f".format(ratio)}:1，达不到 AA 4.5:1",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `hero 卡药丸底不能把渐变底色提亮`() {
        // 22% 白是**提亮**底色（方向错了）；20% 黑必须是压暗。
        for (stop in AccentContrast.HeroGradientStops) {
            val composited = AccentContrast.composite(AccentContrast.HeroPillBackground, stop)
            assertTrue(
                "药丸底把渐变底色提亮了：$stop -> $composited",
                AccentContrast.luminance(composited) <= AccentContrast.luminance(stop) + 1e-6,
            )
        }
    }

    @Test
    fun `联赛筛选胶囊选中态的场次计数 白字要达到 AA 4点5比1`() {
        // 强调渐变由主题主色生成，深浅主题的主色不同，都要测
        for (primary in listOf(SportDark.Red, SportLight.Red, SportDark.Nba, SportLight.Nba)) {
            for (stop in AccentContrast.accentStops(primary)) {
                val ratio = AccentContrast.contrast(AccentContrast.OnAccent, stop)
                assertTrue(
                    "筛选胶囊计数在主色 $primary 的档 $stop 上只有 ${"%.2f".format(ratio)}:1，达不到 AA 4.5:1",
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `composite 把半透明前景正确叠到背景上`() {
        // 20% 黑叠到白底 = 80% 白
        val overWhite = AccentContrast.composite(Color(0x33000000), Color.White)
        assertEquals(0.8f, overWhite.red, 0.01f)
        assertEquals(0.8f, overWhite.green, 0.01f)
        assertEquals(0.8f, overWhite.blue, 0.01f)

        // 不透明前景原样返回
        val opaque = AccentContrast.composite(Color.White, Color.Black)
        assertEquals(1f, opaque.red, 0.01f)
        assertEquals(1f, opaque.green, 0.01f)
        assertEquals(1f, opaque.blue, 0.01f)
    }

    @Test
    fun `contrast 与 WCAG 定义一致`() {
        // 黑字白底 = 21:1，白字黑底同样是 21:1
        assertEquals(21.0, AccentContrast.contrast(Color.Black, Color.White), 0.01)
        assertEquals(21.0, AccentContrast.contrast(Color.White, Color.Black), 0.01)
    }
}
