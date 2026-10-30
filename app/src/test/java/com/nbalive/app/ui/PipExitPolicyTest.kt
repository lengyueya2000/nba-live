package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小窗里点系统的「全屏」按钮，回来后必须是全屏（review 现场反馈 2026-10-30）。
 *
 * 复现路径（雷电模拟器实测）：竖屏播放页 → 点「画中画」→ 小窗里点「全屏」
 * → 回到 720×1280 的竖屏播放页（信号列表还在），而不是横屏全屏。
 *
 * 根因：`fullscreen` 是进小窗前那一刻的状态，竖屏页进小窗时它是 false，
 * 退出小窗没有任何一步把它改回 true —— 用户按的明明是「全屏」。
 */
class PipExitPolicyTest {

    @Test
    fun `在播放页退出小窗要回到全屏`() {
        assertTrue(
            "小窗里点「全屏」必须回到全屏播放，而不是竖屏页面",
            PipExitPolicy.shouldExpandToFullscreen(onPlayer = true, finishing = false),
        )
    }

    @Test
    fun `不在播放页时不要擅自全屏`() {
        // 防御：小窗只从播放页进，但判定不该依赖"调用方一定在播放页"
        assertFalse(PipExitPolicy.shouldExpandToFullscreen(onPlayer = false, finishing = false))
    }

    @Test
    fun `正在收窗（点 X）时不要请求全屏`() {
        // 点 X 是小窗的「关闭」不是「全屏」；这时再请求横屏只会闪一下
        assertFalse(PipExitPolicy.shouldExpandToFullscreen(onPlayer = true, finishing = true))
    }
}