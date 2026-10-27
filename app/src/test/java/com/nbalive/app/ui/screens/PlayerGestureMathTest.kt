package com.nbalive.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页亮度/音量手势的换算。
 *
 * 这些公式错了都不会崩，只会"手感不对"：上滑变暗、滑到底也调不满、
 * 音量档位四舍五入到 0 导致静音。这类问题只能靠真机手测发现，
 * 所以把可测的部分抽成纯函数并在这里锁死。
 */
class PlayerGestureMathTest {

    private val screenHeight = 1000f // 满量程 = 700px

    @Test
    fun `上滑变大 下滑变小`() {
        val full = PlayerGestureMath.fullSwipe(screenHeight)
        assertEquals(700f, full, 0.01f)

        // 手指向上移动 = movedPx 为负 -> 值变大
        val up = PlayerGestureMath.valueFromDrag(0.5f, -350f, full, min = 0f)
        assertEquals("上滑 350px（半个量程）应到 100%", 1f, up, 0.001f)

        // 手指向下移动 = movedPx 为正 -> 值变小
        val down = PlayerGestureMath.valueFromDrag(1f, 350f, full, min = 0f)
        assertEquals("下滑 350px 应到 50%", 0.5f, down, 0.001f)
    }

    @Test
    fun `亮度有下限 音量可以到零`() {
        val full = PlayerGestureMath.fullSwipe(screenHeight)
        // 一直往下滑到底：亮度停在 2%（不能全黑），音量可以到 0
        val brightness = PlayerGestureMath.valueFromDrag(
            startValue = 0.5f,
            movedPx = 100_000f,
            fullSwipe = full,
            min = 0.02f,
        )
        assertEquals(0.02f, brightness, 0.0001f)

        val volume = PlayerGestureMath.valueFromDrag(
            startValue = 0.5f,
            movedPx = 100_000f,
            fullSwipe = full,
            min = 0f,
        )
        assertEquals(0f, volume, 0.0001f)
    }

    @Test
    fun `非法满量程不会除零`() {
        // size 还没测量出来时可能拿到 0
        assertTrue(PlayerGestureMath.fullSwipe(0f) >= 1f)
        assertEquals(1f, PlayerGestureMath.fullSwipe(0f), 0.001f)
        // 直接传 0 给 valueFromDrag 也不能炸出 NaN/Infinity
        val v = PlayerGestureMath.valueFromDrag(0.5f, -100f, fullSwipe = 0f, min = 0f)
        assertTrue("不能是 NaN", !v.isNaN())
        assertTrue("不能是 Infinity", v.isFinite())
        assertTrue("结果必须夹在 0..1 内，实际=$v", v in 0f..1f)
    }

    @Test
    fun `音量档位四舍五入且不越界`() {
        assertEquals(0, PlayerGestureMath.volumeStep(0f, 15))
        assertEquals(15, PlayerGestureMath.volumeStep(1f, 15))
        // 0.5 * 15 = 7.5 -> 8
        assertEquals(8, PlayerGestureMath.volumeStep(0.5f, 15))
        // 越界输入夹住，不能崩 AudioManager
        assertEquals(0, PlayerGestureMath.volumeStep(-3f, 15))
        assertEquals(15, PlayerGestureMath.volumeStep(9f, 15))
        // 极端设备可能返回 0 档，必须按 1 处理
        assertEquals(0, PlayerGestureMath.volumeStep(0f, 0))
        assertEquals(1, PlayerGestureMath.volumeStep(1f, 0))
    }

    @Test
    fun `百分比显示不会出现 101 或负数`() {
        assertEquals(0, PlayerGestureMath.percent(0f))
        assertEquals(50, PlayerGestureMath.percent(0.5f))
        assertEquals(100, PlayerGestureMath.percent(1f))
        assertEquals(100, PlayerGestureMath.percent(1.7f))
        assertEquals(0, PlayerGestureMath.percent(-0.4f))
    }

    // ---------- 亮度复位（P1-4） ----------

    @Test
    fun `离开播放页要复位亮度`() {
        // P1-4：以前只改窗口亮度、从不复位。本 App 是单 Activity
        // （launchMode=singleTask），从播放页返回列表时窗口不重建，
        // 用户把亮度滑到 2% 看球，返回列表后**整个 App** 都是 2%，
        // 而且列表页没有任何恢复入口。
        //
        // BRIGHTNESS_OVERRIDE_NONE = -1f = 恢复跟随系统。
        assertEquals(
            "用户改过亮度：离开时必须写回 -1（跟随系统）",
            -1f,
            requireNotNull(PlayerGestureMath.brightnessOnDispose(userChanged = true)),
            0.0001f,
        )
    }

    @Test
    fun `没改过亮度就不要动窗口`() {
        // 没改过却写 -1f 也有副作用：会把 Activity 上别处设的
        // screenBrightness 抹掉。所以只在"用户确实改过"时才复位。
        assertNull(
            "没动过亮度就不该写窗口属性",
            PlayerGestureMath.brightnessOnDispose(userChanged = false),
        )
    }

    @Test
    fun `左半屏调亮度 右半屏调音量`() {
        val width = 1080f
        assertTrue("左半屏 = 亮度", PlayerGestureMath.sideOf(0f, width))
        assertTrue("左半屏 = 亮度", PlayerGestureMath.sideOf(539f, width))
        assertFalse("右半屏 = 音量", PlayerGestureMath.sideOf(540f, width))
        assertFalse("右半屏 = 音量", PlayerGestureMath.sideOf(1079f, width))
    }
}
