package com.nbalive.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 强调色底上的**前景**配色，以及半透明叠加后的对比度判据。
 *
 * ## 为什么单独抽出来
 *
 * 渐变底（hero 卡的红橙、强调胶囊的主题色）已经用 [whiteTextSafe] 压暗到
 * "白字达标"，但界面上的文字**往往不是纯白**：
 *
 * | 位置 | 原取值 | 实测对比度 |
 * |---|---|---|
 * | hero 卡联赛名 | 92% 白 | 4.03~4.07:1 ❌ |
 * | hero 卡 "VS" | 70% 白 | 2.85~3.05:1 ❌ |
 * | hero 卡 "立即观看"胶囊底 | 22% 白 | 底被**提亮**成 #C4845A~#E8586D，白字只剩 3.09~3.47:1 ❌ |
 * | 联赛筛选胶囊的场次计数 | 80% 白 | 3.35:1（10.sp 小字）❌ |
 *
 * 症状是同一个：**压暗函数把底色调到达标线，紧接着的半透明白字又把对比度还了回去。**
 *
 * 半透明白字在压暗底上必然掉出 AA —— 白的 alpha 一降，实际呈现色就往底色靠。
 * 所以规矩很简单：
 *  - 强调底上的**文字**一律不透明纯白（[OnAccent]）；
 *  - 强调底上需要半透明**底色**时，用**黑色**而不是白色（[HeroPillBackground]），
 *    因为白底是提亮、黑底是压暗。
 *
 * ⚠️ 这里只定策略常量；"有没有真的用上"由
 * `MatchListContrastDisciplineTest` 扫源码守住（函数有测试，纪律没有测试）。
 */
object AccentContrast {

    /**
     * 强调底上的正文颜色：**不透明纯白**。
     *
     * 不用半透明白：alpha 一降，实际呈现色就往底色靠，对比度必然掉出 AA。
     */
    val OnAccent = Color.White

    /**
     * hero 卡上"药丸"的半透明底色：20% 黑（`Color(0x33000000)`）。
     *
     * 与 HeroPill 用的同一配方 —— 实测白字 6.4:1。
     * ⚠️ 原来是 22% **白**：那是把底**提亮**，方向正好相反。
     */
    val HeroPillBackground = Color(0x33000000)

    /**
     * hero 卡渐变的每一档（**白字安全版**）。
     *
     * **派生自** `Theme.kt` 的唯一色值来源 [LiveGradientSource]（review/audit-guards.md B11），
     * 与 `GradLiveOnWhite` 同源 —— 只是这里给裸颜色，方便单测直接算对比度。
     * 派生关系由 `AccentContrastTest` 的相等断言钉住。
     *
     * ⚠️ 千万别在这里再抄一遍字面量：那样 `AccentContrastTest` 的 3 条
     * "hero 卡 AA 4.5:1" 测的又变成一份**平行副本**，
     * 改 Theme.kt 的界面底色时对比度可能掉出 AA 而断言照样绿。
     */
    val HeroGradientStops: List<Color> = LiveGradientSource.map { it.whiteTextSafe() }

    /**
     * 强调渐变（主 CTA / 筛选胶囊选中态）的每一档。
     *
     * 与 [accentBrush] 同源，抽出来是为了让单测能直接算对比度。
     */
    fun accentStops(primary: Color): List<Color> = listOf(
        primary.whiteTextSafe(),
        primary.whiteTextSafe(0.75f),
    )

    /**
     * 把半透明前景叠到背景上，得到**实际呈现**的颜色。
     *
     * ⚠️ 半透明底色必须先合成再算对比度：
     * 直接拿"22% 白"去和底色比是错的 —— 屏幕上看到的是合成后的结果。
     */
    fun composite(foreground: Color, background: Color): Color {
        val alpha = foreground.alpha
        fun mix(fg: Float, bg: Float): Float = fg * alpha + bg * (1 - alpha)
        return Color(
            red = mix(foreground.red, background.red),
            green = mix(foreground.green, background.green),
            blue = mix(foreground.blue, background.blue),
            alpha = 1f,
        )
    }

    /** WCAG 相对亮度。 */
    fun luminance(color: Color): Double {
        fun lin(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(color.red) + 0.7152 * lin(color.green) + 0.0722 * lin(color.blue)
    }

    /** WCAG 对比度，顺序无关。 */
    fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }
}
