package com.nbalive.app.ui

/**
 * 网页线路退后台时的**媒体暂停脚本**（review/01-player.md P2-3）。
 *
 * ## 要解决什么
 *
 * 网页兜底播放时，声音由源站 H5 播放器（`<video>`/`<audio>`）发出。
 * 退到后台（锁屏、切 App，且**没进小窗**）时原来只调了
 * `webView.onPause()` + `pauseTimers()` —— 这两者只暂停页面渲染与定时器，
 * **不会**停掉正在播放的媒体元素，用户锁屏后声音还在外放。
 * 直连路径没这个问题：ExoPlayer 由 `ON_STOP` 显式 `pause()`。
 *
 * ## 为什么用 JS 而不是别的手段
 *
 * `WebView` 没有公开 API 能停 HTML5 媒体；`pauseTimers()` 只影响 JS 定时器。
 * 唯一可靠的方式是注入脚本调 `media.pause()`。
 *
 * ## 为什么抽成常量
 *
 * 这是一段注入第三方页面的 JS，写错了**不会抛异常、只会静默无效** ——
 * 恰是最难在真机上发现的那类问题。抽出来后单测可以断言
 * "覆盖 video/audio、真的调 pause()、遍历了全部元素、有存在性判断"。
 */
object WebMediaPauseScript {

    /** 我们在元素上打的标记，恢复时只播"被我们暂停过"的那些。 */
    private const val MARK = "__nbaPausedByUs"

    /**
     * 暂停页面上**所有**正在播放的媒体元素。
     *
     * 用 `querySelectorAll('video, audio')` 一次拿全：直播页里常有多个媒体元素
     * （广告位、备用播放器），只处理第一个会留下一路声音。
     * 只暂停 `!paused` 的，并打标记 —— 用户自己暂停的元素不该被我们"恢复"。
     */
    const val PAUSE_ALL: String = """
        (function() {
          try {
            var list = document.querySelectorAll('video, audio');
            if (!list || list.length === 0) return;
            for (var i = 0; i < list.length; i++) {
              var m = list[i];
              if (m && !m.paused) {
                m['$MARK'] = true;
                m.pause();
              }
            }
          } catch (e) { /* 第三方页面，任何异常都不该影响宿主 */ }
        })();
    """

    /**
     * 恢复**被我们暂停过**的媒体。
     *
     * ⚠️ 只播带标记的：源站播放器自己可能因为缓冲/广告暂停过，
     * 无条件 `play()` 会把用户手动暂停的视频强行播起来。
     * `play()` 返回 Promise，被浏览器自动播放策略拒绝时会 reject ——
     * 必须 catch，否则在控制台留 unhandled rejection。
     */
    const val RESUME_PAUSED: String = """
        (function() {
          try {
            var list = document.querySelectorAll('video, audio');
            if (!list || list.length === 0) return;
            for (var i = 0; i < list.length; i++) {
              var m = list[i];
              if (m && m['$MARK']) {
                m['$MARK'] = false;
                var p = m.play();
                if (p && p.catch) p.catch(function() {});
              }
            }
          } catch (e) { /* 同上 */ }
        })();
    """
}