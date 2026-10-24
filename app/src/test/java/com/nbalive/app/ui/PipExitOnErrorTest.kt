package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「小窗是否该跟着退出」的判定。
 *
 * 实测回归（v1.2.8，Android 14 / API 34）：播放页显示「网络错误」，
 * 画面完全是死的，按 Home 仍然弹出一个画中画窗，里面还是那句
 * 「网络错误 / 请试试 刷新」—— 一个坏掉的小窗挂在桌面上，
 * 用户既看不了球，也没有任何办法操作它。
 *
 * 为什么 [PipState.shouldAutoEnterPip] 的 `hasStream` 没拦住它：
 * `hasStream` 只区分"拿到过地址"和"没拿到地址"。
 * 网页兜底（webFallbackUrl）在 ExoPlayer 直连失败后**仍然带着地址**，
 * 于是 hasStream=true、判定照样放行 —— 但 WebView 里报的是
 * "网络错误"，播出来的东西用户根本看不了。
 *
 * 也就是说：**"有地址"不等于"在播"**。地址拿到的瞬间只是"该试试"，
 * 真正确认能播要看播放器自己有没有报错。
 *
 * 因此这里加一层"小窗是否还值得留着"的判定，把小窗的存亡
 * 绑到播放器真实状态上，而不是绑到"有没有一个 URL"上。
 */
class PipExitOnErrorTest {

    @Test
    fun `有流且没报错时小窗该留着`() {
        assertTrue(
            "正常播放中，小窗是用户主动要的功能，必须能继续用",
            PipState.shouldKeepPip(hasStream = true, hasPlaybackError = false),
        )
    }

    @Test
    fun `播放器报错后小窗该退出`() {
        assertFalse(
            "ExoPlayer/WebView 报错后画面是死的，小窗里只剩一句错误提示，必须退出",
            PipState.shouldKeepPip(hasStream = true, hasPlaybackError = true),
        )
    }

    @Test
    fun `没有流时小窗也没有存在意义`() {
        assertFalse(
            PipState.shouldKeepPip(hasStream = false, hasPlaybackError = false),
        )
        assertFalse(
            PipState.shouldKeepPip(hasStream = false, hasPlaybackError = true),
        )
    }
}
