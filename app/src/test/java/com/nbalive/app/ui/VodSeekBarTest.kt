package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录像进度条的显示判据与时间格式（2026-11-25 新增）。
 *
 * 用户明确要求：**直播播放器绝不能有进度条/拖拽**（会误触跳离直播边缘），
 * 只有录像回放才画，且**只在全屏**。本测试把这条边界钉死在纯函数上，
 * 免得将来改 PlayerScreen 布局时又把直播带上了进度条。
 */
class VodSeekBarTest {

    private val vodUrl = "https://www.rtmpcdn.com/vodplay/1/a.m3u8"

    @Test
    fun `直播任何情况都不显示进度条`() {
        listOf(true, false).forEach { fullscreen ->
            listOf(true, false).forEach { inPip ->
                listOf(vodUrl, null).forEach { url ->
                    assertFalse(
                        "直播（isVod=false）永远不画进度条：fullscreen=$fullscreen inPip=$inPip url=$url",
                        PlayerControls.shouldShowSeekBar(
                            isVod = false,
                            streamUrl = url,
                            fullscreen = fullscreen,
                            inPip = inPip,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `录像全屏直连显示进度条`() {
        assertTrue(
            PlayerControls.shouldShowSeekBar(
                isVod = true,
                streamUrl = vodUrl,
                fullscreen = true,
                inPip = false,
            ),
        )
    }

    @Test
    fun `录像非全屏不显示进度条`() {
        // 用户选择「仅全屏显示」，竖屏播放器保持现状
        assertFalse(
            PlayerControls.shouldShowSeekBar(
                isVod = true,
                streamUrl = vodUrl,
                fullscreen = false,
                inPip = false,
            ),
        )
    }

    @Test
    fun `录像画中画不显示进度条`() {
        assertFalse(
            PlayerControls.shouldShowSeekBar(
                isVod = true,
                streamUrl = vodUrl,
                fullscreen = true,
                inPip = true,
            ),
        )
    }

    @Test
    fun `录像网页兜底不显示进度条`() {
        // 播放器的 seek 只接了 ExoPlayer（直连）；网页兜底由源站页面自己管
        assertFalse(
            PlayerControls.shouldShowSeekBar(
                isVod = true,
                streamUrl = null,
                fullscreen = true,
                inPip = false,
            ),
        )
    }

    @Test
    fun `播放时间格式化`() {
        assertEquals("00:00", PlayerControls.formatPlaybackTime(0L))
        assertEquals("01:01", PlayerControls.formatPlaybackTime(61_000L))
        assertEquals("59:59", PlayerControls.formatPlaybackTime(3_599_000L))
        assertEquals("1:01:01", PlayerControls.formatPlaybackTime(3_661_000L))
        assertEquals("00:00", PlayerControls.formatPlaybackTime(-1L))
    }

    @Test
    fun `进度上报节流间隔为5秒`() {
        // 播放器轮询 400ms，但写历史每 5 秒一次，避免高频落盘
        assertEquals(5_000L, PlayerControls.REPLAY_PROGRESS_REPORT_INTERVAL_MS)
    }
}
