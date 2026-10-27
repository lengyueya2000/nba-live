package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 网页兜底全屏时，WebView 该占多大区域。
 *
 * ## 要解决什么
 *
 * 实测复现（20:9 屏幕，网页兜底全屏）：底部源站比分条消失，
 * 画面铺满整个屏幕、上下被裁。同一场比赛在 16:9 屏幕上比分条**可见**。
 *
 * ## 根因
 *
 * 全屏容器是 `fillMaxSize()`，在 20:9 屏上就是 20:9；
 * 而视频源是 16:9：
 *  - 直连路径：`PlayerView.resizeMode = FIT` 保持 16:9 加黑边，画面完整
 *  - 网页路径：WebView 里的 xgplayer 把 16:9 画面**放大铺满** 20:9 容器，
 *    上下各裁一截 —— 比分条正好在底部，被裁没了
 *
 * 屏幕越细长，裁掉越多。这就是「模拟器能看到、真机看不到」的原因。
 *
 * ## 修法
 *
 * 网页全屏时给 WebView 一个 **16:9 的区域**（居中、四周留黑边），
 * 而不是整个屏幕。画面不再被放大裁切，比分条留在画面里。
 *
 * ⚠️ **必须同时算宽和高**：横屏 20:9 容器（1600x720）里，
 * 16:9 视频受限的是**宽度**（1280x720，左右留黑边）；
 * 竖屏 20:9 容器（720x1600）里受限的才是高度（720x405）。
 * 早先只算高度，横屏下算出 `min(720, 900) = 720` —— 和原样一模一样，
 * 等于什么都没修。
 *
 * 非全屏时容器本来就是 16:9（`aspectRatio(16f / 9f)`），不需要改。
 */
class WebFullscreenAspectTest {

    /** 横屏全屏：20:9 手机转到横屏后是 1600x720 */
    private val landscapeW = 1600
    private val landscapeH = 720

    /** 竖屏全屏：20:9 手机竖着是 720x1600 */
    private val portraitW = 720
    private val portraitH = 1600

    private fun box(
        w: Int,
        h: Int,
        aspectRatio: Float = 16f / 9f,
        fullscreen: Boolean = true,
    ): PlayerControls.BoxSize? =
        PlayerControls.webViewSizeForAspect(w, h, aspectRatio, fullscreen)

    private fun requireBox(
        w: Int,
        h: Int,
        aspectRatio: Float = 16f / 9f,
    ): PlayerControls.BoxSize {
        val b = box(w, h, aspectRatio)
        checkNotNull(b) { "容器 ${w}x$h 应该算出尺寸，却返回了 null" }
        return b
    }

    @Test
    fun `横屏20比9容器里 16比9 画面按宽度受限`() {
        // 1600x720 容器，16:9 视频：720 * 16/9 = 1280 <= 1600
        // -> 高度顶满 720，宽度 1280，左右各留 160 黑边
        val b = requireBox(landscapeW, landscapeH)
        assertEquals("横屏 20:9 下宽度是限制项", 1280, b.width)
        assertEquals("高度顶满容器", 720, b.height)
    }

    @Test
    fun `竖屏20比9容器里 16比9 画面按高度受限`() {
        // 720x1600 容器：720 / (16/9) = 405 <= 1600
        // -> 宽度顶满 720，高度 405，上下留大量黑边
        val b = requireBox(portraitW, portraitH)
        assertEquals("竖屏 20:9 下宽度顶满容器", 720, b.width)
        assertEquals("高度按 16:9 算出 405", 405, b.height)
    }

    @Test
    fun `16比9容器完全吻合不裁剪也不留黑边`() {
        val b = requireBox(1280, 720)
        assertEquals(1280, b.width)
        assertEquals(720, b.height)
    }

    @Test
    fun `更细长的21比9横屏也按宽度受限`() {
        // 21:9 手机横屏：2520x1080
        val b = requireBox(2520, 1080)
        assertEquals("21:9 更细，左右黑边更宽", 1920, b.width)
        assertEquals(1080, b.height)
    }

    @Test
    fun `4比3容器里按高度受限`() {
        // 1024x768（4:3 平板）：1024 / (16/9) = 576 <= 768
        val b = requireBox(1024, 768)
        assertEquals(1024, b.width)
        assertEquals("4:3 容器里 16:9 视频高度 = 1024 * 9 / 16 = 576", 576, b.height)
    }

    @Test
    fun `算出的宽高比恒等于 16比9`() {
        // 遍历各种畸形容器，宽高比必须严格守住，否则画面还是会被拉/裁
        val cases = listOf(
            1600 to 720, 720 to 1600, 2520 to 1080, 1024 to 768,
            1280 to 720, 1080 to 2400, 3840 to 2160, 1000 to 1000,
        )
        for ((w, h) in cases) {
            val b = requireBox(w, h)
            val actual = b.width.toFloat() / b.height.toFloat()
            assertEquals(
                "容器 ${w}x$h 算出的 ${b.width}x${b.height} 比例应为 16:9",
                16f / 9f,
                actual,
                0.01f,
            )
        }
    }

    @Test
    fun `非全屏时不改动容器`() {
        // 非全屏容器本来就是 aspectRatio(16:9)，不该再套一层
        assertNull(box(720, 405, fullscreen = false))
    }

    @Test
    fun `容器尺寸非法时不崩`() {
        assertNull(box(0, 720))
        assertNull(box(1600, 0))
    }
}
