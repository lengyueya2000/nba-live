package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏 / 小窗的系统栏与屏幕方向（review/01-player.md P2-7 / P2-8）。
 *
 * ## P2-7 进小窗时方向被复位
 *
 * `FullscreenSystemEffect(enabled = fullscreen && !inPip)`：进入画中画时
 * `enabled` 从 true 变 false，`DisposableEffect` 的 **onDispose 会执行**
 * 并把 `requestedOrientation` 复位成 `UNSPECIFIED` ——
 * 于是小窗窗口跟着设备方向乱转，用户正在看的画面突然竖过来。
 *
 * 小窗期间应该**保持横屏**（视频是横向的），退出小窗回全屏时继续横屏，
 * 真正离开播放页才复位。
 *
 * ## P2-8 面板停留过久后控制栏不再出现
 *
 * 自动收起浮层的计时器 key 是
 * `(fullscreen, controlsVisible, locked, inPip, controlsTouchSeq)` ——
 * **不含 picker / showLivePanel**。用户在面板里挑线路超过 10 秒，
 * 计时器到点把 `controlsVisible = false`；选完线路面板关闭时
 * `LaunchedEffect(picker, showLivePanel)` 只在**打开**时把 controlsVisible
 * 置 true，关闭时不回补 —— 全屏底部控制栏就再也不出现了。
 */
class FullscreenOrientationTest {

    // ---------- P2-7 ----------

    @Test
    fun `小窗期间必须保持横屏 不能复位成 UNSPECIFIED`() {
        val action = SystemBarsPolicy.orientationFor(fullscreen = true, inPip = true, onPlayer = true)
        assertEquals(
            "小窗窗口也要横屏（视频是横向的），复位成 UNSPECIFIED 会让它跟着设备乱转",
            OrientationAction.KeepLandscape,
            action,
        )
    }

    @Test
    fun `全屏非小窗时锁定横屏`() {
        assertEquals(
            OrientationAction.Landscape,
            SystemBarsPolicy.orientationFor(fullscreen = true, inPip = false, onPlayer = true),
        )
    }

    @Test
    fun `离开播放页才复位方向`() {
        assertEquals(
            OrientationAction.Reset,
            SystemBarsPolicy.orientationFor(fullscreen = false, inPip = false, onPlayer = false),
        )
    }

    @Test
    fun `非全屏但还在播放页时也复位 让竖屏详情正常`() {
        // 播放页的非全屏态是竖屏布局（上下留黑边的视频区），必须允许竖屏
        assertEquals(
            OrientationAction.Reset,
            SystemBarsPolicy.orientationFor(fullscreen = false, inPip = false, onPlayer = true),
        )
    }

    @Test
    fun `小窗期间不隐藏系统栏`() {
        // 小窗里是系统在画窗口，我们去 hide 系统栏既无效又会在退出时闪烁
        assertFalse(
            SystemBarsPolicy.shouldHideSystemBars(fullscreen = true, inPip = true),
        )
    }

    @Test
    fun `全屏非小窗时隐藏系统栏`() {
        assertTrue(SystemBarsPolicy.shouldHideSystemBars(fullscreen = true, inPip = false))
        assertFalse(SystemBarsPolicy.shouldHideSystemBars(fullscreen = false, inPip = false))
    }

    // ---------- P2-8 ----------

    @Test
    fun `面板打开期间自动收起计时器必须暂停`() {
        // 用户在面板里挑线路超过 10 秒，计时器到点会把控制栏收掉；
        // 面板关闭后没人回补，全屏底部栏就再也不出现
        assertFalse(
            "面板打开时不能自动收起控制栏",
            SystemBarsPolicy.shouldAutoHideControls(
                fullscreen = true,
                locked = false,
                inPip = false,
                pickerOpen = true,
                livePanelOpen = false,
            ),
        )
        assertFalse(
            SystemBarsPolicy.shouldAutoHideControls(
                fullscreen = true, locked = false, inPip = false,
                pickerOpen = false, livePanelOpen = true,
            ),
        )
    }

    @Test
    fun `面板都关闭时才自动收起`() {
        assertTrue(
            SystemBarsPolicy.shouldAutoHideControls(
                fullscreen = true, locked = false, inPip = false,
                pickerOpen = false, livePanelOpen = false,
            ),
        )
    }

    @Test
    fun `非全屏 锁定 小窗时都不自动收起`() {
        assertFalse(
            SystemBarsPolicy.shouldAutoHideControls(false, false, false, false, false),
        )
        assertFalse(
            SystemBarsPolicy.shouldAutoHideControls(true, true, false, false, false),
        )
        assertFalse(
            SystemBarsPolicy.shouldAutoHideControls(true, false, true, false, false),
        )
    }

    @Test
    fun `面板关闭后必须回补控制栏`() {
        // 从"面板开着"变"面板关了"这一次转换，要显式把控制栏亮出来
        assertTrue(
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = false, livePanelOpen = false, fullscreen = true,
            ),
        )
        // 面板还开着就别回补（会让面板和栏同时闪）
        assertFalse(
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = true, livePanelOpen = false, fullscreen = true,
            ),
        )
        // 非全屏本来就有栏，不需要
        assertFalse(
            SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = false, livePanelOpen = false, fullscreen = false,
            ),
        )
    }
}