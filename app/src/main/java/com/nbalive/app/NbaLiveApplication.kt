package com.nbalive.app

import android.app.Application

/**
 * 应用入口。
 *
 * 注意：类名刻意不与 UI 层的 [com.nbalive.app.ui.NbaLiveApp] 重名，
 * 否则 `NbaLiveApp()` 在重载解析时会命中这个无参构造函数，
 * 导致界面完全不渲染（静默空白页）。
 */
class NbaLiveApplication : Application()
