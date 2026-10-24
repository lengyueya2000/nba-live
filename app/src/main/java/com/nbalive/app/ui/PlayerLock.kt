package com.nbalive.app.ui

/**
 * 播放页「锁定」状态的纯逻辑。
 *
 * 锁定 = 防误触。看球时手放在屏幕上，很容易碰到返回键、切线路、换源。
 * 锁定后：
 *  - 所有浮层隐藏（时钟、画质、画中画/全屏、底部控制栏）
 *  - 手势层停用（滑动不再改亮度/音量，单击不再切换浮层）
 *  - 画面中央显示一个提示，说明现在是锁定态
 *  - 视频继续播，屏幕继续常亮（锁屏就熄屏还看什么）
 *
 * ⚠️ 用户明确要求：**只有点解锁键才能解锁**。
 * 所以锁定后画面不可点、返回键不退出播放页 —— 解锁入口唯一，就是右侧中间的锁按钮。
 */
object PlayerLock {

    /** 浮层（顶部信息、右上角按钮、底部控制栏）是否显示 */
    fun showOverlays(locked: Boolean): Boolean = !locked

    /** 手势层（亮度/音量滑动、单击画面）是否启用 */
    fun gesturesEnabled(locked: Boolean): Boolean = !locked

    /** 底部控制栏是否显示 */
    fun showBottomBar(locked: Boolean): Boolean = !locked

    /**
     * 锁按钮是否显示。
     *
     * ⚠️ 锁定时**必须**仍然显示 —— 它是唯一的解锁入口。
     */
    fun showLockButton(locked: Boolean): Boolean = true

    /**
     * 锁按钮在**未锁定**时是否跟着浮层一起隐藏。
     *
     * 全屏直连下浮层默认是收起的（单击画面才显示），
     * 锁按钮孤零零挂在画面右侧中间，既挡画面又破坏了"单击清空画面"的预期。
     * 锁定时反过来 —— 它必须常显，否则用户没法解锁。
     */
    fun hideLockButtonWhenOverlaysHidden(locked: Boolean): Boolean = !locked

    /**
     * 返回键在当前状态下该不该退出播放页。
     *
     * ⚠️ 锁定时返回键**不退出**：用户明确要求「只有点解锁键才会解锁」，
     * 返回键被吃掉而不是退出播放页。
     *
     * 这条判定以前是个没人调用的死函数，
     * 真正的判断写在 NbaLiveApp 的 BackHandler 里 ——
     * 规则有两份实现、其中一份还是死代码，
     * 测试测的是没人用的那份，真实行为（用户被困在锁定态）反而零保护。
     * 现在统一走这里，NbaLiveApp 调用它、测试也测它。
     */
    fun backExitsPlayer(locked: Boolean): Boolean = !locked

    /** 画面中央的锁定提示是否显示 */
    fun showLockHint(locked: Boolean): Boolean = locked

    /**
     * 锁定是否对当前这场比赛生效。
     *
     * ⚠️ 锁必须绑定到**比赛 id**：
     * 「同时在播」面板切到另一场时 route 一直是 PLAYER，
     * 裸布尔锁会跟着漏到新的一场（用户看着新比赛却是锁着的、浮层全没了）。
     *
     * @param lockedMatchId 被锁住的比赛 id（null = 没锁）
     * @param currentMatchId 当前在看的比赛 id
     */
    fun isLocked(lockedMatchId: String?, currentMatchId: String?): Boolean =
        lockedMatchId != null && lockedMatchId == currentMatchId
}
