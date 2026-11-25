package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录像快进/快退手势的纯判据（2026-11-25 用户需求：右滑快进、左滑快退，按距离连续 seek）。
 */
class SeekGesturePolicyTest {

    @Test
    fun `右滑快进左滑快退方向与增量`() {
        // 滑过整个屏宽 = 走完整段时长
        assertEquals(
            10_000L,
            SeekGesturePolicy.deltaMsFromDrag(px = 1000f, widthPx = 1000f, durationMs = 10_000L),
        )
        assertEquals(
            -10_000L,
            SeekGesturePolicy.deltaMsFromDrag(px = -1000f, widthPx = 1000f, durationMs = 10_000L),
        )
    }

    @Test
    fun `零位移或零屏宽或零时长不seek`() {
        assertEquals(0L, SeekGesturePolicy.deltaMsFromDrag(0f, 1000f, 10_000L))
        assertEquals(0L, SeekGesturePolicy.deltaMsFromDrag(100f, 0f, 10_000L))
        assertEquals(0L, SeekGesturePolicy.deltaMsFromDrag(100f, 1000f, 0L))
    }

    @Test
    fun `增量不超过时长`() {
        // 滑出屏幕外（px 大于屏宽）也不能超过总时长
        assertEquals(10_000L, SeekGesturePolicy.deltaMsFromDrag(2000f, 1000f, 10_000L))
        assertEquals(-10_000L, SeekGesturePolicy.deltaMsFromDrag(-2000f, 1000f, 10_000L))
    }

    @Test
    fun `applySeek夹到0到时长`() {
        assertEquals(5_000L, SeekGesturePolicy.applySeek(3_000L, 2_000L, 100_000L))
        assertEquals(0L, SeekGesturePolicy.applySeek(3_000L, -9_000L, 100_000L))
        assertEquals(100_000L, SeekGesturePolicy.applySeek(99_000L, 5_000L, 100_000L))
    }

    @Test
    fun `负时长按不可seek`() {
        assertEquals(0L, SeekGesturePolicy.applySeek(1_000L, 500L, -1L))
        assertTrue(SeekGesturePolicy.deltaMsFromDrag(100f, 1000f, -1L) == 0L)
    }

    @Test
    fun `从锚点加累计位移算绝对落点不依赖异步currentPosition`() {
        // 半屏 = 半段时长；anchor=100s, 右滑半屏 → 200s（总时长 200s，clamp 到上限）
        assertEquals(
            200_000L,
            SeekGesturePolicy.seekTargetFromAnchor(100_000L, 1000f, 1000f, 200_000L),
        )
        // 左滑半屏 → 回到 0
        assertEquals(
            0L,
            SeekGesturePolicy.seekTargetFromAnchor(100_000L, -1000f, 1000f, 200_000L),
        )
        // anchor 50s，右滑 1/4 屏 → +50s = 100s
        assertEquals(
            100_000L,
            SeekGesturePolicy.seekTargetFromAnchor(50_000L, 250f, 1000f, 200_000L),
        )
    }

    @Test
    fun `锚点落点时长未知返回0不可seek`() {
        assertEquals(0L, SeekGesturePolicy.seekTargetFromAnchor(100L, 500f, 1000f, 0L))
        assertEquals(0L, SeekGesturePolicy.seekTargetFromAnchor(100L, 500f, 0f, 10_000L))
    }

    @Test
    fun `seek事件按间隔节流首次必发`() {
        assertTrue(SeekGesturePolicy.shouldEmitSeek(1_000L, 0L, 120L))
        assertTrue(SeekGesturePolicy.shouldEmitSeek(1_200L, 1_000L, 120L))
        org.junit.Assert.assertFalse(SeekGesturePolicy.shouldEmitSeek(1_100L, 1_000L, 120L))
    }

    @Test
    fun `手势主轴判定水平优先给seek垂直给亮度音量`() {
        assertEquals(SeekGesturePolicy.Axis.HORIZONTAL, SeekGesturePolicy.gestureAxis(30f, 5f))
        assertEquals(SeekGesturePolicy.Axis.VERTICAL, SeekGesturePolicy.gestureAxis(5f, 30f))
        // 相等或都极小按垂直（不改变现有亮度/音量的默认归属）
        assertEquals(SeekGesturePolicy.Axis.VERTICAL, SeekGesturePolicy.gestureAxis(1f, 1f))
    }

    @Test
    fun `仅录像全屏未锁非画中画允许快进快退`() {
        assertTrue(SeekGesturePolicy.canSeek(isVod = true, dragEnabled = true, layerEnabled = true))
        // 直播、锁定/非全屏（dragEnabled=false）、画中画（layerEnabled=false）都不 seek
        org.junit.Assert.assertFalse(SeekGesturePolicy.canSeek(false, true, true))
        org.junit.Assert.assertFalse(SeekGesturePolicy.canSeek(true, false, true))
        org.junit.Assert.assertFalse(SeekGesturePolicy.canSeek(true, true, false))
    }
}
