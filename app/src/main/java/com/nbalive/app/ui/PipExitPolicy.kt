package com.nbalive.app.ui

/**
 * 退出画中画时，用户按的是系统的「全屏」按钮 —— 回来该是全屏播放，不是竖屏页面。
 *
 * ## 现场（2026-10-30 反馈）
 *
 * 竖屏播放页 → 点「画中画」→ 在小窗里点系统的「全屏」→ **回到 720×1280 的竖屏播放页**
 * （信号列表还在），而不是横屏全屏。
 *
 * ## 根因
 *
 * `fullscreen`（`NbaLiveApp` 的 `rememberSaveable`）只是"进小窗前那一刻"的状态：
 *
 *  - 从全屏进小窗：它是 true，退出小窗后 Compose 重算仍为 true → 恢复全屏（碰巧对）；
 *  - 从**竖屏播放页**进小窗：它是 false，退出小窗时**没有任何一步**把它改回 true
 *    → 用户按的明明是「全屏」，回来却是竖屏页面。
 *
 * 小窗的「全屏」是系统动作，不会替我们改应用状态；所以「要不要回全屏」必须由
 * 退出小窗这个回调显式判一次，而不是指望进小窗前那个状态碰巧是 true。
 *
 * ⚠️ 判据抽成纯函数（有单测）的原因：`onPictureInPictureModeChanged` 在 JVM 单测里跑不到，
 * 真正的调用点在 `MainActivity`，实机也难覆盖全部分支。
 */
object PipExitPolicy {

    /**
     * 退出小窗时该不该把播放页切回全屏。
     *
     * @param onPlayer 小窗之前是否真的在看视频（[PipState.playerActive]）
     * @param finishing 窗口是不是正在被收掉（用户点 X 关闭小窗，不是「全屏」）
     */
    fun shouldExpandToFullscreen(onPlayer: Boolean, finishing: Boolean): Boolean =
        onPlayer && !finishing
}