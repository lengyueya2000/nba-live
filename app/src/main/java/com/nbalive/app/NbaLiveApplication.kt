package com.nbalive.app

import android.app.Application
import android.webkit.WebView

/**
 * 应用入口。
 *
 * 注意：类名刻意不与 UI 层的 [com.nbalive.app.ui.NbaLiveApp] 重名，
 * 否则 `NbaLiveApp()` 在重载解析时会命中这个无参构造函数，
 * 导致界面完全不渲染（静默空白页）。
 */
class NbaLiveApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 仅 debug 包开启 WebView 远程调试。
        //
        // 为什么需要：网页兜底播放时源站的比分条/控制条被挤出视口，
        // 这类问题**必须**看 WebView 里的真实 DOM 才能定位是谁把高度撑走的
        // （uiautomator dump 读不到 WebView 内部，只能看到宿主 App 的控件）。
        // 开启后可以：
        //   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
        //   curl http://127.0.0.1:9222/json/list
        //
        // ⚠️ 只在 BuildConfig.DEBUG 下开：release 包开启等于把用户
        // 浏览的网页内容暴露给任何能连 adb 的人。
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
