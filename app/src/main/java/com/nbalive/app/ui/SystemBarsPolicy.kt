package com.nbalive.app.ui

/**
 * 全屏 / 小窗的系统栏与屏幕方向判定（review/01-player.md P2-7 / P2-8）。
 *
 * ## 为什么抽成纯判据
 *
 * 这两条都是"`DisposableEffect` 的 key 写错 / 少写"造成的，
 * 而 Compose 的副作用时序在 JVM 单测里跑不到 ——
 * 把**判定**抽出来之后，"小窗期间该不该复位方向"这类问题可以被钉住，
 * Composable 里只剩执行。
 */
enum class OrientationAction {
    /** 锁定横屏 */
    Landscape,

    /**
     * 保持横屏但**不复位**。
     *
     * 小窗期间用这个：`DisposableEffect` 在 `enabled` 变 false 时会走 onDispose，
     * 那里若复位成 `UNSPECIFIED`，小窗窗口会跟着设备方向乱转
     * （用户正看着的画面突然竖过来）。
     */
    KeepLandscape,

    /** 复位成 UNSPECIFIED（跟随系统） */
    Reset,
}

object SystemBarsPolicy {

    /**
     * 该对屏幕方向做什么。
     *
     * @param fullscreen 是否全屏
     * @param inPip 是否在画中画
     * @param onPlayer 是否还在播放页
     */
    fun orientationFor(fullscreen: Boolean, inPip: Boolean, onPlayer: Boolean): OrientationAction = when {
        // 小窗：视频是横向的，必须保持横屏。不能走 Reset ——
        // 那正是"进小窗方向被复位"的根因（P2-7）。
        inPip -> OrientationAction.KeepLandscape
        fullscreen -> OrientationAction.Landscape
        // 其余情形（含播放页的非全屏竖屏布局、离开播放页）都跟随系统
        else -> OrientationAction.Reset
    }

    /**
     * 该不该隐藏系统栏。
     *
     * 小窗期间**不隐藏**：窗口由系统绘制，我们去 hide 既无效，
     * 退出小窗时还会因为"隐藏/显示"来回切换而闪烁。
     */
    fun shouldHideSystemBars(fullscreen: Boolean, inPip: Boolean): Boolean =
        fullscreen && !inPip

    /**
     * 浮层该不该自动收起。
     *
     * ⚠️ 面板打开期间必须**暂停**计时（P2-8）：
     * 用户在面板里挑线路超过 10 秒，计时器到点会把控制栏收掉，
     * 而面板关闭时没人回补 —— 全屏底部控制栏就再也不出现了。
     */
    fun shouldAutoHideControls(
        fullscreen: Boolean,
        locked: Boolean,
        inPip: Boolean,
        pickerOpen: Boolean,
        livePanelOpen: Boolean,
    ): Boolean = fullscreen && !locked && !inPip && !pickerOpen && !livePanelOpen

    /**
     * 面板关闭时要不要回补控制栏。
     *
     * 这是 P2-8 的**第二半**：只暂停计时还不够 ——
     * 若面板开着期间计时器已经触发过（或用户手动收起了栏），
     * 关闭面板时必须有一步显式恢复，否则栏不会自己回来。
     *
     * @param fullscreen 只有全屏才有"自动收起的栏"这回事
     */
    fun shouldRestoreControlsOnPanelClose(
        pickerOpen: Boolean,
        livePanelOpen: Boolean,
        fullscreen: Boolean,
    ): Boolean = fullscreen && !pickerOpen && !livePanelOpen
}