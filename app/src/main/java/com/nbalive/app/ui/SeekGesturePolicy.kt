package com.nbalive.app.ui

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 录像快进/快退手势的纯判据（2026-11-25 用户需求：右滑快进、左滑快退，按距离连续 seek）。
 *
 * 约定：横向滑动**一整个屏宽** = 走完整段录像时长。右滑（px>0）快进，左滑（px<0）快退。
 * 纯函数，JVM 单测可跑；手势层只负责把像素位移喂进来。
 */
object SeekGesturePolicy {

    /**
     * 由一次水平位移算出应 seek 的毫秒增量（带符号）。
     *
     * @param px 本次 pointer 的水平位移（右正左负，单位像素）
     * @param widthPx 视频区宽度（像素）；0 时无法换算，返回 0
     * @param durationMs 录像总时长；<=0（未就绪/直播）时返回 0，绝不 seek
     * @return 有符号毫秒增量，绝对值不超过 [durationMs]
     */
    fun deltaMsFromDrag(px: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f || durationMs <= 0L || px == 0f) return 0L
        val fraction = (px / widthPx).coerceIn(-1f, 1f)
        return (fraction * durationMs).roundToLong()
    }

    /**
     * 把增量叠加到当前位置并夹到 [0, durationMs]。
     * 时长未知（<=0）时返回 0（表示"不可 seek"），调用方据此不调用 seekTo。
     */
    fun applySeek(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (currentMs + deltaMs).coerceIn(0L, durationMs)
    }

    /** 供 OSD 显示：这次 seek 增量是否值得提示（至少 1 秒，避免每帧刷字）。 */
    fun shouldShowOsd(absDeltaMs: Long): Boolean = abs(absDeltaMs) >= 1_000L

    /**
     * 是否启用快进/快退手势：仅**录像** + 全屏未锁（dragEnabled）+ 非画中画（layerEnabled）。
     * 直播一律 false —— 直播没有进度概念，快进/快退是录像专属。
     */
    fun canSeek(isVod: Boolean, dragEnabled: Boolean, layerEnabled: Boolean): Boolean =
        isVod && dragEnabled && layerEnabled

    /** 手势主轴：水平（|dx|>|dy|）交给快进快退，其余（含相等）保持原有的垂直亮度/音量。 */
    enum class Axis { HORIZONTAL, VERTICAL }

    fun gestureAxis(dx: Float, dy: Float): Axis =
        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
}
