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

    /**
     * 手势层（亮度/音量滑动）是否启用。
     *
     * ⚠️ 这是 [gestureConfig] 的单项视图，只表达"锁"这一个维度。
     * 真实判定要连全屏/画中画一起看，生产代码请调 [gestureConfig]。
     */
    fun gesturesEnabled(locked: Boolean): Boolean = !locked

    /** 手势层单击动作的分流结果。 */
    enum class TapAction {
        /** 锁定态：单击唤出解锁 UI（否则用户找不到解锁入口） */
        TOGGLE_UNLOCK_UI,

        /** 未锁定 + 全屏：单击切换浮层显隐 */
        TOGGLE_CONTROLS,

        /** 未锁定 + 非全屏：浮层常驻，我们不管单击，留给源站播放器 */
        PASS_THROUGH,
    }

    /**
     * 手势层的完整配置。
     *
     * ## 为什么要收敛成一个函数（P2-9）
     *
     * 以前 [gesturesEnabled] / [tapShowsUnlockUi] 是**死函数**，
     * 没有任何生产调用方，真实判定散在 `PlayerScreen` 的组合表达式里：
     *  `enabled = !inPip`、`tapOnly = locked`、`onTap = when { locked -> ... }`。
     * 这正是本文件 [backExitsPlayer] 注释里批评过的模式 ——
     * 规则有两份实现、其中一份是死代码，测试测的是没人用的那份，
     * 真实行为反而零保护。现在 PlayerScreen 调这一个函数，测试也测它。
     *
     * @param locked 是否锁定（防误触）
     * @param fullscreen 是否全屏
     * @param inPip 是否在画中画窗口里
     */
    fun gestureConfig(locked: Boolean, fullscreen: Boolean, inPip: Boolean): GestureConfig =
        GestureConfig(
            // 画中画窗口里没有我们的手势层（系统窗口不接收这些手势）
            layerEnabled = !inPip,
            // 滑动只在**全屏**且未锁定时接管。
            //
            // 非全屏必须让开（P2-2）：非全屏时播放页是一列可滚动内容
            // （视频下面是信号列表），视频区占屏幕上部约 1/3，
            // 用户从画面起手向上滑是想滚页面。手势层如果无条件接管垂直拖动，
            // 就会被解释成调亮度/音量并弹 OSD，页面一动不动。
            // 未接管的拖动（不 consume）会自然落到父级 verticalScroll。
            dragEnabled = !locked && fullscreen,
            // 单击动作分流
            tapAction = when {
                locked -> TapAction.TOGGLE_UNLOCK_UI
                fullscreen -> TapAction.TOGGLE_CONTROLS
                else -> TapAction.PASS_THROUGH
            },
            // 只有锁定态才消费单击（P1-6）。
            //
            // 锁定后我们的浮层全隐藏了，但手势层仍在（要能唤出解锁按钮）。
            // 不消费的话，每一次误触的单击都照旧透传给 WebView 里的源站播放器：
            // 手掌蹭一下就会点亮源站控制条、甚至双击进源站自己的全屏 ——
            // 用户以为锁住了，实际只锁住了我们这一层。
            //
            // ⚠️ 非锁定态**必须保持透传**：v1.2.5 明确要求网页线路
            // 能单击调出源站控制条 / 调亮度音量，这里不能顺手"统一消费"。
            consumeTap = locked,
        )

    /**
     * 手势层的配置结果。
     *
     * @param layerEnabled 整层是否挂载（画中画里不挂）
     * @param dragEnabled 是否接管滑动（改亮度/音量）
     * @param tapAction 单击该做什么
     * @param consumeTap 单击是否消费掉（true = 不再透传给 WebView 里的源站播放器）
     */
    data class GestureConfig(
        val layerEnabled: Boolean,
        val dragEnabled: Boolean,
        val tapAction: TapAction,
        val consumeTap: Boolean,
    )

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
