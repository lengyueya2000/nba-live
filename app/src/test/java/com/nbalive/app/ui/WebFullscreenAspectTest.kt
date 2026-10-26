package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网页兜底全屏时，WebView 该占多大区域。
 *
 * ## 要解决什么
 *
 * 实测复现（20:9 屏幕 2400x1080，网页兜底全屏）：
 * 底部源站比分条消失，画面铺满整个屏幕、上下被裁。
 *
 * 同一场比赛在 16:9 屏幕（1280x720）上比分条**可见**。
 *
 * ## 根因
 *
 * 全屏容器是 `fillMaxSize()`，在 20:9 屏上就是 20:9。
 * 而视频源是 16:9：
 *  - 直连路径：`PlayerView.resizeMode = FIT` 会保持 16:9 加黑边，
 *    画面完整，比分条可见
 *  - 网页路径：WebView 里的 xgplayer 把 16:9 画面**放大铺满** 20:9 容器，
 *    上下各裁掉一截 —— 比分条正好在底部，被裁没了
 *
 * 屏幕越细长（19.5:9 / 20:9 的真机比 16:9 的模拟器宽得多），裁掉越多。
 * 这就是"模拟器能看到、真机看不到"的原因。
 *
 * ## 修法
 *
 * 网页全屏时给 WebView 一个 **16:9 的区域**（居中、上下留黑边），
 * 而不是整个屏幕。画面不再被放大裁切，比分条留在画面里。
 *
 * 非全屏时容器本来就是 16:9（`aspectRatio(16f / 9f)`），不需要改。
 */
class WebFullscreenAspectTest {

    /** 20:9 真机全屏：宽度 2400，高度 1080 */
    private val phone20x9W = 2400
    private val phone20x9H = 1080

    @Test
    fun `20比9屏幕全屏时 WebView 高度按 16比9 算`() {
        // 宽 2400 -> 16:9 的高度应该是 2400 * 9 / 16 = 1350
        // 但屏幕只有 1080 高，所以受限的是高度：按高度反算宽度
        val h = PlayerControls.webViewHeightForAspect(
            containerWidth = phone20x9W,
            containerHeight = phone20x9H,
            aspectRatio = 16f / 9f,
        )
        assertEquals(
            "20:9 容器里，16:9 的 WebView 高度应等于容器高度（高度是限制项）",
            phone20x9H,
            h,
        )
    }

    @Test
    fun `16比9屏幕全屏时 WebView 占满高度`() {
        val h = PlayerControls.webViewHeightForAspect(
            containerWidth = 1280,
            containerHeight = 720,
            aspectRatio = 16f / 9f,
        )
        assertEquals(
            "16:9 容器和视频比例一致，应该铺满高度、不裁切",
            720,
            h,
        )
    }

    @Test
    fun `更细长的21比9屏幕也不裁切`() {
        // 21:9 手机：宽度 2520，高度 1080
        val h = PlayerControls.webViewHeightForAspect(
            containerWidth = 2520,
            containerHeight = 1080,
            aspectRatio = 16f / 9f,
        )
        assertEquals(
            "再细长的屏也只是上下留更多黑边，不裁画面",
            1080,
            h,
        )
    }

    @Test
    fun `容器比16比9更方时按宽度算高度`() {
        // 4:3 容器（比如平板）：宽度 1024，高度 768
        // 16:9 视频放进 4:3 容器，高度是限制项 -> 宽度受限
        val h = PlayerControls.webViewHeightForAspect(
            containerWidth = 1024,
            containerHeight = 768,
            aspectRatio = 16f / 9f,
        )
        assertEquals(
            "4:3 容器里，16:9 视频高度 = 宽 * 9 / 16 = 576",
            576,
            h,
        )
    }

    @Test
    fun `非全屏时不改动容器`() {
        // 非全屏的容器本来就是 aspectRatio(16:9)，不该再套一层
        assertEquals(
            -1,
            PlayerControls.webViewHeightForAspect(
                containerWidth = 720,
                containerHeight = 405,
                aspectRatio = 16f / 9f,
                fullscreen = false,
            ),
        )
    }
}
