package com.nbalive.app.ui

import com.nbalive.app.data.model.MatchStatus

/**
 * 赛事状态的**文案**判定（review/02-data.md P2-10）。
 *
 * ## 为什么要把文案抽出来
 *
 * 文案原来散在 `StatusChip` 的 `when (status)` 里。延期场次的状态是
 * `UPCOMING`（源站文案"延期/推迟/待定"都归到未开赛），于是界面一律显示
 * 「未开赛」—— 用户按时间找过去发现没有直播，以为数据错了。
 *
 * 数据层早就算出了 `Match.isPostponed`（`Match.kt` 里解释了为什么不加
 * `POSTPONED` 枚举：那是跨层改动，会让所有 `when(status)` 编译不过），
 * 但**没有任何 UI 读它**。
 *
 * 抽成纯函数的好处：文案规则可以被 JVM 单测钉住（`PostponedLabelTest`），
 * 而 Compose 渲染在纯单测里跑不到。
 */
object StatusLabel {

    /**
     * 状态文案。
     *
     * @param status 赛事状态
     * @param isPostponed 源站是否标注了延期/推迟/待定
     *   （见 `Match.isPostponed`）。**只在未开赛语义下生效**：
     *   直播中/已结束的文案不能被它覆盖 —— 一场已经打完的比赛
     *   即使 timeLabel 里残留"延期"字样，也不该显示成延期。
     */
    fun of(status: MatchStatus, isPostponed: Boolean): String = when (status) {
        MatchStatus.LIVE -> "直播中"
        // 延期场次与"还没开赛"必须能区分：前者等下去也不会有直播
        MatchStatus.UPCOMING -> if (isPostponed) "延期" else "未开赛"
        MatchStatus.FINISHED -> "已结束"
    }
}