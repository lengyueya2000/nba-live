package com.nbalive.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 画中画状态桥接。
 *
 * Activity 的 `onPictureInPictureModeChanged` 与 Compose 界面分属两套生命周期，
 * 用一个进程内的 StateFlow 传递，Compose 侧 collect 后只渲染视频区。
 */
object PipState {

    /** 当前是否处于画中画窗口 */
    val inPip = MutableStateFlow(false)

    /**
     * 当前是否停留在播放页。
     * 用于「按 Home 键自动进入画中画」：只有真的在看视频时才小窗。
     */
    @Volatile
    var playerActive: Boolean = false
}
