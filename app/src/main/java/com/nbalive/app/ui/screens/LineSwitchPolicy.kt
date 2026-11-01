package com.nbalive.app.ui.screens

/**
 * 换线路时"要不要把新地址应用到**已复用**的播放器上"的判据
 * （review/optimization-review.md **P9 完整版**，纯函数，有单测）。
 *
 * ## 为什么要有这条判据
 *
 * P9 的完整版是"换线路不重建 ExoPlayer"：同一个播放页内**复用**一个播放器实例，
 * 换线只换媒体项（`setMediaItem` + `prepare`）。这带来两个必须显式挡住的风险：
 *
 * 1. **重复应用**：Compose 会因为缓冲状态、`controlsVisible`、画质标签等无关变化
 *    反复重组 `VideoSurface`。若在重组里无条件 `setMediaItem + prepare`，
 *    表现为"画面不断重缓冲"——这正是当年 P11 删掉的那个死分支的失效模式
 *    （`PlayerScreen.kt` 里那个 `if (current != streamUrl)` 分支）。
 *    所以"已经应用到播放器上的地址"必须是**状态**，而不是现场比较出来的临时值。
 * 2. **起播意图**：换线前用户在**看**（`playWhenReady == true`）→ 新线要自动起播；
 *    换线前用户**按过暂停** → 不能替他按播放。这个意图只能取"换线前一刻"的值，
 *    所以它作为 [Action.Apply] 的载荷传出去，而不是在应用处再读一次
 *    （应用处读到的可能是刚被 `prepare()` 改过的值）。
 *
 * ## 与 P11 的关系
 *
 * P11 删掉的是"每次重组都 `setMediaItem`"这个**失效分支**；P9 完整版把同一件事
 * 变成了**判据**：地址没变就 `Ignore`，于是"重组不会重复 prepare"这件事
 * 第一次有了可失败的测试（见 `PlayerFixTest.P9完整版`）。
 */
internal object LineSwitchPolicy {

    /** 一次换线决策的结果。 */
    sealed interface Action {
        /** 地址没变（已经应用过）→ 什么都不做。重组走到这里必须是这个分支。 */
        data object Ignore : Action

        /**
         * 新地址 → 应用**一次**。
         *
         * @param playWhenReady 换线前一刻的播放意图：`true`（在播）→ 新线自动起播；
         *   `false`（用户按过暂停）→ 只 `prepare`，不替用户按播放。
         */
        data class Apply(val playWhenReady: Boolean) : Action
    }

    /**
     * @param appliedUrl 已经应用到播放器上的媒体地址；`null` = 还没应用过任何地址
     *   （首轮工厂跑完、view 就绪时的第一次应用）
     * @param newUrl 当前要播的线路地址
     * @param playingIntent 换线前一刻的 `player.playWhenReady`
     */
    fun decide(appliedUrl: String?, newUrl: String, playingIntent: Boolean): Action =
        if (appliedUrl == newUrl) Action.Ignore else Action.Apply(playWhenReady = playingIntent)
}