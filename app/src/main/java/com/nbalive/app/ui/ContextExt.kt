package com.nbalive.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从任意 `Context` 找到宿主 `Activity`（review/03-ui.md P2-21）。
 *
 * ## 为什么必须递归
 *
 * `setContent` 拿到的 context **不保证**就是 Activity：它可能是
 * `ContextThemeWrapper` / `ContextWrapper` 链上的一环。
 * 直接用 `context as? Activity` 强转，遇到包装会**静默失败**——
 * 调用方通常写成 `?: return@SideEffect`，于是表现为
 * "状态栏图标不跟随主题"这种没人会当成 bug 提的现象。
 *
 * ## 为什么抽到这一份
 *
 * 原来工程里有两套写法并存：
 *  - `ui/NbaLiveApp.kt` 里这份递归扩展；
 *  - `ui/theme/Theme.kt` 里 `(view.context as? Activity)` 的直接强转。
 *
 * 当前是单 Activity + `setContent`，所以强转那条路暂时不会踩到包装；
 * 但两套写法并存意味着"哪个更可靠"没有统一答案 ——
 * 后来人会照抄离自己近的那一份，迟早抄到错的那份。
 * 现在两处共用这一份（源码守卫 `ContextExtTest` 钉住）。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}