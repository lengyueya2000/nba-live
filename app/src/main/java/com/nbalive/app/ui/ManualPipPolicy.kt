package com.nbalive.app.ui

/**
 * 手动点「画中画」按钮的守卫（review/01-player.md P2-6）。
 *
 * ## 要解决什么
 *
 * 播放页的「画中画」按钮 onClick 直接调 `onEnterPip()`，而 `enterPip()`
 * 只判系统版本与硬件特性，**不判当前有没有流可播**。于是：
 *  - 线路还在解析时点 → 一块转圈/黑的小窗
 *  - 所有线路都失败（界面显示"暂无信号"）时点 → 一块坏掉的小窗
 *
 * 「按 Home 自动进小窗」那条路早就有 [PipState.shouldAutoEnterPip] 守卫
 * （`PipAutoEnterTest` 覆盖），**手动按钮这条路没有** ——
 * 同一个问题两条路径、只守住一条，是典型的"改了一半"。
 *
 * ## 规则
 *
 * 手动与自动用**同一条**判据（不另写一份，避免漂移），
 * 但反馈不同：自动是静默不弹（用户本来没打算小窗），
 * 手动必须给一句提示（否则用户以为按钮坏了）。
 */
sealed interface ManualPipDecision {
    /** 允许进入画中画 */
    data object Allow : ManualPipDecision

    /** 拦住，并给用户这句提示 */
    data class Blocked(val message: String) : ManualPipDecision
}

object ManualPipPolicy {

    private const val NO_STREAM_MESSAGE = "线路还没准备好，暂时无法进入画中画"

    /**
     * 决定这次点「画中画」该怎么办。
     *
     * ⚠️ 判定必须与 [PipState.shouldAutoEnterPip] 完全一致：
     * 两条路径不一致会出现"按 Home 不进、点按钮进"这种自相矛盾的行为。
     */
    fun decide(isPlayerRoute: Boolean, hasStream: Boolean): ManualPipDecision =
        if (PipState.shouldAutoEnterPip(isPlayerRoute, hasStream)) {
            ManualPipDecision.Allow
        } else {
            ManualPipDecision.Blocked(NO_STREAM_MESSAGE)
        }
}

/**
 * 切场时复位小窗视频尺寸（review/01-player.md P2-5）。
 *
 * ## 要解决什么
 *
 * `PipState.setVideoSize` 只在 `onVideoSizeChanged` 里写。切到另一场比赛时，
 * 新场次的首帧到达之前**没人清零** —— 小窗按上一场的比例显示
 * （上一场是 4:3、这一场是 16:9 时，小窗会被拉成错误形状）。
 *
 * ## 做法
 *
 * 切场时写 `setVideoSize(0, 0)`，[ratioFor] 在拿不到真实宽高时回退 16:9。
 */
object PipAspectReset {

    /**
     * 切场时是否要把小窗视频尺寸清零（P17，`player.md` 第 17 条）。
     *
     * ## 为什么不再是恒真函数
     *
     * 原实现写死 `= true`，调用点于是成了 `if (恒真)` —— 读代码的人以为这里有分支，
     * 测试断言的其实是一个常量（"测试给假安全感"这一类）。
     *
     * 真判据是"**有没有东西需要复位**"：`PipState.videoSize` 只在
     * `onVideoSizeChanged` 里写，如果上一场压根没上报过宽高（`0, 0`），
     * 再写一次 `setVideoSize(0, 0)` 是空操作 —— 跳过它不影响任何可观察状态。
     * 于是清零只在上一次真的记过尺寸时才做。
     *
     * @param hasRecordedVideoSize 上一次是否记录过真实视频宽高
     *   （调用方用 `PipState.videoSize.value.width > 0` 现查）。
     *   ⚠️ 默认 `true` = 调用方拿不到信息时**保守地复位**，
     *   保持 01-player.md P2-5 的原始语义（新场次首帧到达前按 16:9 显示）。
     */
    fun shouldResetOnMatchSwitch(hasRecordedVideoSize: Boolean = true): Boolean = hasRecordedVideoSize

    /**
     * 由宽高算小窗比例。
     *
     * 宽高非法（0/负数，来自"还没拿到"或播放器脏数据）时回退 16:9，
     * 绝不返回 NaN 或负数 —— 系统拿到非法比例会直接抛异常。
     */
    fun ratioFor(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 16f / 9f
        val ratio = width.toFloat() / height.toFloat()
        if (ratio.isNaN() || ratio <= 0f || ratio.isInfinite()) return 16f / 9f
        return ratio
    }
}