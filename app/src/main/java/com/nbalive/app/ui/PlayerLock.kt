package com.nbalive.app.ui

/**
 * 播放页「锁定」状态的纯逻辑。
 *
 * 锁定 = 防误触。看球时手放在屏幕上，很容易碰到返回键、切线路、换源。
 * 锁定后：
 *  - 所有浮层隐藏（时钟、画质、画中画/全屏、底部控制栏、锁按钮、提示）
 *  - 手势停用（滑动不改亮度/音量）
 *  - 视频继续播，屏幕继续常亮（锁屏就熄屏还看什么）
 *
 * 解锁方式：**单击画面**唤出锁按钮（3 秒后自动收起），点它解锁。
 * 只单击画面本身不会解锁 —— 防误触的意义就在这：
 * 手掌蹭一下屏幕不会把锁解开，必须看到按钮再精准点它。
 *
 * ⚠️ 返回键在锁定时**不退出播放页**（用户明确要求）。
 */
object PlayerLock {

    /**
     * 浮层（顶部信息、右上角按钮、底部控制栏）是否显示。
     *
     * 锁定时浮层全隐藏，但单击画面后 [showUnlockUi] 为 true 时
     * 锁按钮会单独出现（见 [showLockButton]）。
     */
    fun showOverlays(locked: Boolean): Boolean = !locked

    /** 手势层（亮度/音量滑动）是否启用 */
    fun gesturesEnabled(locked: Boolean): Boolean = !locked

    /** 底部控制栏是否显示 */
    fun showBottomBar(locked: Boolean): Boolean = !locked

    /**
     * 锁按钮是否显示。
     *
     * @param locked 当前是否锁定
     * @param unlockUiVisible 锁定态下"单击画面唤出的解锁 UI"是否处于显示期
     *
     * 未锁定 → 跟随浮层（全屏默认收起，单击才出现）；
     * 锁定 → **只在 unlockUiVisible 时显示**，其余时间画面完全干净。
     */
    fun showLockButton(locked: Boolean, unlockUiVisible: Boolean): Boolean =
        if (locked) unlockUiVisible else true

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

    /**
     * 顶部「已锁定 · 点右侧锁按钮解锁」提示是否显示。
     *
     * 只在单击画面唤出解锁 UI 时跟着出现 —— 锁定时常驻的话，
     * 画面顶部永远挂一条黑条，与"锁定是为了画面干净"自相矛盾。
     */
    fun showLockHint(locked: Boolean, unlockUiVisible: Boolean): Boolean =
        locked && unlockUiVisible

    /**
     * 锁定态下，单击画面是否应该唤出解锁 UI。
     *
     * 未锁定时单击由调用方处理（切换浮层显隐），不归这里管。
     */
    fun tapShowsUnlockUi(locked: Boolean): Boolean = locked

    /** 解锁 UI 自动收起的延时（毫秒）。太短来不及点，太长又破坏画面干净 */
    const val UNLOCK_UI_TIMEOUT_MS: Long = 3_000

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
