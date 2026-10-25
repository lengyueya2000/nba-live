package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页兜底全屏时底部控制栏的显隐。
 *
 * 实测回归（v1.2.9 之后，用户截图反馈）：
 * 直连失败退回网页播放（WebView 承载源站播放页）后，全屏单击画面，
 * **底部没有任何按钮** —— 用户没法换源、换线路、重连，也找不到退出入口，
 * 只能干看着一个自己操作不了的播放器。
 *
 * 根因在 PlayerScreen：`webFullscreen = fullscreen && !inPip && streamUrl == null`
 * 被塞进底部栏的显示条件里，把网页线路整条排除了。
 *
 * 当初的理由有两条，现在都不成立：
 *  1) "源站 H5 播放器自带底部控制条，我们的栏盖上去就没法用了" ——
 *     实测：源站控制条在 WebView 内部，我们的 Compose 浮层画在它之上，
 *     两条控制条各在各的层，源站那条仍然可以点。
 *     真正被挡的只是视觉位置重叠，而我们的按钮用的是**半透明渐变底**，
 *     不是不透明的实色块。
 *  2) "网页路径没法接管点击把浮层收起来" ——
 *     v1.2.8 起两条路径都支持「单击显隐 + 10 秒无操作自动收起」，
 *     这条前提已经不存在了。
 *
 * 而排除它的代价是实的：用户在网页线路里**完全失去操作能力**。
 * 两害相权，按钮必须画出来。
 */
class WebFallbackControlsTest {

    @Test
    fun `网页兜底全屏时底部控制栏也要显示`() {
        assertTrue(
            "退回网页播放后用户还要换源/换线路/重连，按钮不能消失",
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = null,          // 网页兜底：没有直连地址
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `直连全屏时底部控制栏正常显示`() {
        assertTrue(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8?sign=x",
                webFallbackUrl = null,
            ),
        )
    }

    @Test
    fun `非全屏不显示底部控制栏`() {
        // 底部栏是全屏专属：非全屏时信号列表就在页面上，不需要浮层按钮
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = false,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `画中画窗口内不显示底部控制栏`() {
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = true,
                controlsVisible = true,
                locked = false,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `锁定时不显示底部控制栏`() {
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = true,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `浮层收起时不显示底部控制栏`() {
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = false,
                locked = false,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }
}
