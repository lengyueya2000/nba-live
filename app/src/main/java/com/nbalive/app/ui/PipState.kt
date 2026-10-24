package com.nbalive.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 画中画状态桥接。
 *
 * Activity 的 `onPictureInPictureModeChanged` 与 Compose 界面分属两套生命周期，
 * 用一个进程内的 StateFlow 传递，Compose 侧 collect 后只渲染视频区。
 *
 * ⚠️ 全部用 StateFlow，不要混用 @Volatile：
 * 两套并发原语混在一起时，"写 @Volatile 变量"和"读 StateFlow"之间没有可见性保证，
 * 而且 Compose 侧 collect 不到 @Volatile 的变化（不会触发重组）。
 * 统一成 StateFlow 后，写入即触发重组，也没有内存可见性问题。
 */
object PipState {

    /** 当前是否处于画中画窗口 */
    val inPip = MutableStateFlow(false)

    /**
     * 当前是否停留在播放页。
     * 用于「按 Home 键自动进入画中画」：只有真的在看视频时才小窗。
     *
     * ⚠️ 改了类型：以前是 @Volatile Boolean，
     * Activity 侧（onUserLeaveHint）读它、Compose 侧写它，
     * 现在统一成 StateFlow，两边都读同一个数据源。
     */
    private val _playerActive = MutableStateFlow(false)
    val playerActive = _playerActive.asStateFlow()

    /**
     * 当前视频的宽高（像素）。
     *
     * 画中画窗口用真实视频比例才不会给非 16:9 的源加黑边；
     * 拿不到时（还没起播）用 16:9 兜底。
     *
     * ⚠️ 宽高是一对，必须**原子**地更新：
     * 分两次写两个变量时，Activity 侧可能读到"新的宽 + 旧的高"，
     * 算出错误比例给画中画窗口。用一个 StateFlow 装数据类就不会错位。
     */
    private val _videoSize = MutableStateFlow(VideoSize(0, 0))
    val videoSize = _videoSize.asStateFlow()

    /** 视频尺寸数据类（宽、高，单位像素） */
    data class VideoSize(val width: Int, val height: Int)

    // ---- 写入入口：统一在这里做，避免外部直接改内部 StateFlow ----

    /** 设置"是否停留在播放页" */
    fun setPlayerActive(active: Boolean) {
        _playerActive.value = active
    }

    /** 设置视频宽高（一对，原子更新） */
    fun setVideoSize(width: Int, height: Int) {
        _videoSize.value = VideoSize(width, height)
    }
}
