package com.nbalive.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页线路退到后台时的**媒体暂停脚本**（review/01-player.md P2-3）。
 *
 * ## 要解决什么
 *
 * 网页兜底播放时，声音由源站 H5 播放器（`<video>`/`<audio>`）发出。
 * 退到后台（锁屏、切 App，且**没进小窗**）时当前只调了
 * `webView.onPause()` + `pauseTimers()` —— 这两者只暂停页面渲染与定时器，
 * **不会**停掉正在播放的媒体元素，用户锁屏后声音还在外放。
 *
 * 直连路径没这个问题：ExoPlayer 由 `ON_STOP` 显式 `pause()`。
 *
 * ## 为什么要把脚本抽出来
 *
 * 它是一段注入到第三方页面的 JS 字符串，写错了不会抛异常、只会静默无效 ——
 * 恰恰是最难在真机上发现的那类问题。抽成常量后可以被单测断言
 * "确实覆盖了 video/audio、确实是 pause()、确实遍历了所有元素"。
 */
class WebMediaPauseScriptTest {

    @Test
    fun `脚本必须暂停所有 video 与 audio 元素`() {
        val js = WebMediaPauseScript.PAUSE_ALL
        assertTrue("必须处理 <video>：$js", js.contains("video"))
        assertTrue("必须处理 <audio>：$js", js.contains("audio"))
        assertTrue("必须真的调用 pause()：$js", js.contains("pause()"))
    }

    @Test
    fun `脚本必须遍历全部媒体元素 而不是只取第一个`() {
        val js = WebMediaPauseScript.PAUSE_ALL
        // querySelector 只返回第一个；直播页可能有多个媒体元素（广告、主播放器）
        assertTrue(
            "必须用 querySelectorAll 并遍历，否则只停掉第一个媒体元素：$js",
            js.contains("querySelectorAll"),
        )
        assertTrue(
            "必须逐个 pause，而不是只对集合调用一次：$js",
            Regex("""forEach|for\s*\(""").containsMatchIn(js),
        )
    }

    @Test
    fun `脚本必须是防御式的 页面没有媒体时不能抛错`() {
        val js = WebMediaPauseScript.PAUSE_ALL
        // 注入到第三方页面：元素可能不存在，也可能 getElementsByTagName 返回空
        assertTrue(
            "必须做存在性判断，避免在无媒体页面上抛异常（异常会被静默吞掉、但会污染控制台）：$js",
            js.contains("if (") || js.contains("if("),
        )
    }

    @Test
    fun `恢复脚本只恢复被我们暂停过的媒体`() {
        val js = WebMediaPauseScript.RESUME_PAUSED
        assertTrue("恢复必须调 play()：$js", js.contains("play()"))
        // 关键：不能把用户自己暂停的视频也播起来
        assertTrue(
            "必须只恢复**我们暂停过**的元素（用标记位），否则用户手动暂停的视频会被强制播放：$js",
            js.contains("__nbaPausedByUs") || js.contains("dataset"),
        )
    }
}