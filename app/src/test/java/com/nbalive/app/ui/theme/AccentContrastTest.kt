package com.nbalive.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.nbalive.app.GuardSourceScan
import java.io.File
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
    fun `hero 渐变档必须派生自 Theme 的唯一色值来源 而不是平行副本`() {
        // B11（review/audit-guards.md）：红橙三档原来在生产里有三份副本
        // （Theme.kt 的 GradLive、GradLiveOnWhite、这里的 HeroGradientStops）。
        // 后果：下面 3 条 "hero 卡 AA 4.5:1" 遍历的是副本，
        // 改 Theme.kt 的界面底色时 hero 卡可能掉出 AA，而断言照样绿。
        //
        // 这里断言的是**派生关系**（不是再把字面量抄一遍）：
        // 谁改 `LiveGradientSource`，这些档必须跟着变。
        assertTrue(
            "LiveGradientSource 至少要有 2 档，否则下面的相等断言会“两边都是空”地静默全绿",
            LiveGradientSource.size >= 2,
        )
        assertTrue(
            "HeroGradientStops 不能为空 —— 为空时上面 3 条遍历它的断言会一条不跑地全绿",
            AccentContrast.HeroGradientStops.isNotEmpty(),
        )
        assertEquals(
            "HeroGradientStops 与 Theme.kt 的 LiveGradientSource 派生关系断了：" +
                "这里必须写 `LiveGradientSource.map { it.whiteTextSafe() }`，" +
                "不许再抄一份字面量（否则 hero 卡对比度守卫测的只是平行副本）",
            LiveGradientSource.map { it.whiteTextSafe() },
            AccentContrast.HeroGradientStops,
        )
        // 单靠上面那条相等断言抓不住"抄一份**同值**副本"（值相同就绿，等源真变了才红），
        // 所以再钉一次初始化表达式 —— 这是 B11 的形态本身，不是数值。
        val code = themeSource("AccentContrast.kt")
        assertTrue(
            "HeroGradientStops 又抄回了一份字面量副本（同值时相等断言抓不到它，但改\n" +
                "LiveGradientSource 时这份副本会漂移，hero 卡 AA 守卫就又测副本了）：\n" +
                "RHS 必须写成 `LiveGradientSource.map { it.whiteTextSafe() }`",
            HERO_STOPS_DERIVED.containsMatchIn(code),
        )
    }

    @Test
    fun `Theme 的两条直播渐变都从唯一来源派生 而不是各抄一份字面量`() {
        // 同一条 B11 的另一半：界面真正画的两条渐变也必须从 LiveGradientSource 派生。
        //
        // 为什么用**源码断言**而不是运行时比对：Compose 1.7.5 的 LinearGradient
        // 把 colors 收成 internal（只有 createShader/equals 是 public），
        // Brush 在单测里只能当不透明对象；比 toString 比源码扫描更脆。
        // 所以退一步断言"初始化**表达式**里出现唯一来源" —— 这也是本仓库其他守卫的形态。
        val code = GuardSourceScan.stripNonCode(themeFile().readText())
        assertTrue(
            "GradLive 不是从 LiveGradientSource 派生的 —— 又有人在 Theme.kt 里抄了一份字面量：" +
                "改唯一来源时这条渐变不会跟着变（review/audit-guards.md B11）",
            Regex("""\bval GradLive\s*=\s*Brush\.linearGradient\(\s*colors\s*=\s*LiveGradientSource\s*\)""")
                .containsMatchIn(code),
        )
        assertTrue(
            "GradLiveOnWhite（hero 卡 / 直播标签真正的底色）不是从 LiveGradientSource 派生的 —— " +
                "改唯一来源时界面底色不跟着变，上面 3 条 AA 断言实际上测的又是另一份副本",
            Regex("""\bval GradLiveOnWhite\s*=\s*safeOnWhite\(\s*LiveGradientSource\s*\)""")
                .containsMatchIn(code),
        )
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

    // ---------------------------------------------------------------

    /**
     * 找不到 Theme.kt / 其它 theme 源码就 error() 硬失败（铁律③），绝不 return 静默全绿。
     */
    private fun themeFile(name: String = "Theme.kt"): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui/theme"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui/theme"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui/theme 目录，cwd=$cwd，候选=${roots.map { it.path }}")
        return root.listFiles { f -> f.isFile && f.name == name }?.firstOrNull()
            ?: error("找不到 $name（cwd=$cwd），派生关系守卫会静默全绿")
    }

    /** 源码（已剔注释与字符串字面量，行号不变）。 */
    private fun themeSource(name: String): String = GuardSourceScan.stripNonCode(themeFile(name).readText())

    private companion object {
        /**
         * `HeroGradientStops` 的初始化**表达式**必须是 `LiveGradientSource.map { … }`。
         *
         * 值层面的相等断言抓不住"抄一份同值副本"（值相同就是绿的），
         * 只能等源真变了才红；这条直接把"抄副本"这个形态本身钉死。
         */
        val HERO_STOPS_DERIVED = Regex("""\bval\s+HeroGradientStops[^\n=]*=\s*LiveGradientSource\s*\.map""")
    }
}
