package com.nbalive.app.ui

/**
 * 网页兜底加载期间要不要显示"正在连接源站播放页…"（2026-10-30 感知优化）。
 *
 * ## 为什么
 *
 * 过滤掉主播线路后，剩余线路全部走 WebView。实测页面加载 **0.1~27 秒**不等，
 * 这期间界面是一块**纯黑**（源站页面还没画出播放器），没有任何文字说明 ——
 * 用户看到的是"点了比赛，黑屏，像卡死"。加载本身未必变快，但至少要让他知道
 * App 在工作、慢在源站。
 *
 * ## 规则
 *
 * 只在"网页兜底 + 还没有流 + 没报错 + 不在小窗 + 加载未超时"时显示：
 *  - 有流了：已经有直连画面，提示多余；
 *  - 有错误：错误卡/提示更准确，不能与错误叠加；
 *  - 小窗：窗口只有视频区，没有空间画文字（也不该挡住画面）；
 *  - 超过 [MAX_HINT_MS]：源站页面就是打不开，继续显示会像"永远在连接"，
 *    交给 15 秒的嗅探看门狗与错误提示。
 */
object PlayerLoadingHintPolicy {

    /** 提示最多显示多久：20 秒（大于正常加载，小于"永久等"）。 */
    const val MAX_HINT_MS: Long = 20_000L

    fun shouldShow(
        hasWebFallback: Boolean,
        hasStream: Boolean,
        sinceLoadMs: Long,
        hasErrorMessage: Boolean,
        inPip: Boolean,
    ): Boolean = hasWebFallback &&
        !hasStream &&
        !hasErrorMessage &&
        !inPip &&
        sinceLoadMs in 0 until MAX_HINT_MS
}