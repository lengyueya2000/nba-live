package com.nbalive.app.ui

/**
 * 列表顶部横幅该显示什么（review/02-data.md P1-2）。
 *
 * ## 为什么单独抽成纯判据
 *
 * 后台早就把"哪个源挂了"算清楚了（`SourceOutcome` → `LoadOutcome.failedProviders`
 * → `MatchListUiState.partialError`），但**没有任何 UI 读它**：用户只看到
 * "场次变少了"，无法区分"源挂了"和"今天真没比赛"。
 *
 * 抽成纯函数有两个好处：
 *  1. 「两条提示同时存在时显示哪一条」是有判断的规则，不该埋在 Composable 的
 *     `when` 里 —— 那样只能靠人眼审、改坏了测试也不红；
 *  2. 规则可以被 JVM 单测钉住（见 `ListNoticePolicyTest`），
 *     而 Compose 渲染在纯 JVM 单测里跑不到。
 */
sealed interface ListNotice {

    /** 整次刷新失败：可重试。 */
    data class Failed(val message: String) : ListNotice

    /**
     * 部分数据源不可用：列表里**确实少了场次**，但整体还能用。
     *
     * ⚠️ 与 [Failed] 的区别必须让用户看出来：这条不是"刷新失败"，
     * 重试也多半没用（源站限流/宕机有自己的恢复节奏），
     * 所以它**不带重试动作**、也不套「刷新失败：」前缀。
     */
    data class Partial(val message: String) : ListNotice

    /** 没有异常，不该挂任何横幅。 */
    data object None : ListNotice
}

object ListNoticePolicy {

    /**
     * 决定横幅内容。
     *
     * @param error 整次刷新的错误文案（`MatchListUiState.error`）
     * @param partialError 部分源不可用的说明（`MatchListUiState.partialError`）；
     *   数据层已保证"列表为空时不报半失败"（那时该走整屏错误态）
     */
    fun of(error: String?, partialError: String?): ListNotice = when {
        // 整次失败优先：它是可重试的那条，对用户更有用。
        // 两条叠着显示只是噪音，用户也分不清哪条该点。
        error != null -> ListNotice.Failed(error)
        partialError != null -> ListNotice.Partial(partialError)
        else -> ListNotice.None
    }
}