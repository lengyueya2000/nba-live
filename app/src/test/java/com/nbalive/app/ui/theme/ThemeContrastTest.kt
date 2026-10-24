package com.nbalive.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题颜色的可读性。
 *
 * ⚠️ 这类"看起来差不多"的取值，回归时没有任何编译期/运行期信号 ——
 * 只能靠断言把对比度钉住。
 */
class ThemeContrastTest {

    /** WCAG 相对亮度 */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** 对比度，顺序无关 */
    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `浅色主题的警告色在卡片上要达到 AA 4点5比1`() {
        val warn = SportLight.Warn
        // 浅色主题的卡片底色
        val card = Color(0xFFFFFFFF)
        val card3 = Color(0xFFF4F6FB)
        assertTrue(
            "警告色在白底上只有 ${"%.2f".format(contrast(warn, card))}:1，达不到 AA 4.5:1",
            contrast(warn, card) >= 4.5,
        )
        assertTrue(
            "警告色在 Card3 上只有 ${"%.2f".format(contrast(warn, card3))}:1，达不到 AA 4.5:1",
            contrast(warn, card3) >= 4.5,
        )
    }

    @Test
    fun `深色主题的警告色在卡片上要达到 AA 4点5比1`() {
        val warn = SportDark.Warn
        val card = Color(0xFF161A22)
        assertTrue(
            "警告色在深色卡片上只有 ${"%.2f".format(contrast(warn, card))}:1，达不到 AA 4.5:1",
            contrast(warn, card) >= 4.5,
        )
    }

    @Test
    fun `whiteTextSafe 压暗后白字要达到 4点5比1`() {
        val samples = listOf(
            Color(0xFFFF2E4D), // 直播红
            Color(0xFFFF6B2C), // 直播橙
            Color(0xFFFF8A3D), // 亮橙
            Color(0xFF2563EB), // NBA 蓝
            Color(0xFFE11D48), // CBA 红
            Color(0xFF60A5FA), // 浅蓝
            Color(0xFFFB923C), // 浅橙
            Color(0xFF0E9F5C), // 绿
            Color(0xFFC9820A), // 琥珀
        )
        for (c in samples) {
            val safe = c.whiteTextSafe()
            assertTrue(
                "$c 压暗后白字对比度只有 ${"%.2f".format(contrast(safe, Color.White))}:1",
                contrast(safe, Color.White) >= 4.5,
            )
        }
    }

    @Test
    fun `whiteTextSafe 不会把颜色压得比 scale 更亮`() {
        // 二分查找的上界必须是 scale：压暗函数不该让颜色变亮
        val base = Color(0xFF123456)
        val scaled = base.whiteTextSafe(0.5f)
        assertTrue(
            "scale=0.5 的结果不该比原色亮",
            luminance(scaled) <= luminance(base) + 1e-6,
        )
        assertTrue(
            "scale=0.5 的结果不该比 scale=1 的结果亮",
            luminance(scaled) <= luminance(base.whiteTextSafe()) + 1e-6,
        )
    }

    @Test
    fun `whiteTextSafe 对已经够暗的颜色不做压暗`() {
        val dark = Color(0xFF102030)
        assertEquals(dark, dark.whiteTextSafe())
    }
}
