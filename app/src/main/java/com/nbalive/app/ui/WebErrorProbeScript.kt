package com.nbalive.app.ui

/**
 * 「源站播放器自己画出了错误面板」的探测脚本。
 *
 * ## 为什么必须探测页面内部的 DOM
 *
 * 网页兜底播放时，播不出来的**事实**只存在于 WebView 内部：源站播放器
 * （xgplayer 3.0.23）出错后会在页面里画一块「网络错误 / 请试试 刷新」。
 * 宿主 App 读不到它 —— 于是这条路径既没有 ExoPlayer 的 `onPlayerError`，
 * 也不会触发 `onReceivedError`（页面本身加载成功了），
 * 更不会触发嗅探看门狗（页面**确实**发过媒体请求，只是那条请求 404）。
 *
 * 结果就是用户反馈的那一幕：界面永久停在这块错误面板上，点它的「刷新」也没有反应。
 *
 * ## 判据取什么
 *
 * xgplayer 把错误状态挂在播放器根节点的 class 上：
 *
 * ```
 * ERROR:"xgplayer-is-error"            // 状态类
 * <xg-error class="xgplayer-error">    // 面板节点，CSS 里 `.xgplayer-is-error .xgplayer-error{display:flex}`
 * ```
 *
 * 所以「根节点带 `xgplayer-is-error`」是**事实**（播放器自己设的），
 * 不是我们推断出来的。CSS 只在 `.xgplayer-is-error` 存在时才把面板显示出来，
 * 两者等价；这里两个都查，任一见效即认为页面进入错误态。
 *
 * ⚠️ 只查 class 是否存在，不查文案：错误文案来自 i18n（中英文都会变），
 * 而且源站随时可能换播放器版本。class 是结构契约，文案不是。
 *
 * ## 为什么要遍历 iframe
 *
 * 部分源站把播放器放在 iframe 里（`pjs.js` 就是往容器里塞 iframe）。
 * 同源 iframe 的 `contentDocument` 可读；跨源会抛异常 —— 直接跳过，
 * 不能让探测本身把宿主拖挂。
 */
object WebErrorProbeScript {

    /** 播放器根节点上的错误状态类（xgplayer 的 `ERROR` 常量）。 */
    const val ERROR_CLASS: String = "xgplayer-is-error"

    /** 错误面板节点（xgplayer 的 `render()` 返回值）。 */
    const val ERROR_PANEL: String = "xgplayer-error"

    /**
     * 注入执行的探测脚本：页面处于错误态返回 `"1"`，否则 `"0"`。
     *
     * ⚠️ 必须返回字符串常量而不是布尔：`evaluateJavascript` 的回调只给 JSON 文本，
     * 返回 boolean 时拿到的是 `"true"/"false"`，两种写法都得判；
     * 统一成 `"1"/"0"` 让宿主侧只有一条判断路径。
     */
    val HAS_ERROR: String = """
        (function() {
          try {
            function bad(doc) {
              if (!doc) return false;
              if (doc.querySelector('.$ERROR_CLASS')) return true;
              var panel = doc.querySelector('.$ERROR_PANEL');
              if (!panel) return false;
              // 面板存在但可能还没显示（CSS 靠状态类控制），再确认一次可见性
              var style = doc.defaultView && doc.defaultView.getComputedStyle
                ? doc.defaultView.getComputedStyle(panel)
                : null;
              return !style || style.display !== 'none';
            }
            if (bad(document)) return '1';
            var frames = document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
              try {
                if (bad(frames[i].contentDocument)) return '1';
              } catch (e) { /* 跨源 iframe：读不到，跳过 */ }
            }
            return '0';
          } catch (e) {
            return '0';
          }
        })();
    """

    /**
     * 宿主侧解读 `evaluateJavascript` 的返回值。
     *
     * 返回值是 JSON 文本（字符串常量会被再包一层引号：`"\"1\""`），
     * 所以用 `contains("1")` 而不是 `== "1"` —— 直接相等比较永远不成立，
     * 那种 bug 表现为"探测代码写了、提示永远不出现"，最难在真机上发现。
     *
     * `null`（WebView 已销毁 / 回调没来）一律判否。
     */
    fun detectsError(rawResult: String?): Boolean = rawResult?.contains("1") == true
}
