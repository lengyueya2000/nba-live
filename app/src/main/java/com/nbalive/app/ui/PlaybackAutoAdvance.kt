package com.nbalive.app.ui

/**
 * 网页线路长时间拿不到媒体时的**自动换线**判据（线上「点比赛就黑屏」的修复）。
 *
 * ## 要解决什么（2026-10-29 实机复现）
 *
 * 播放页自动选中源站列表里的第一条线路；对多数场次那是 **JRS** 线路，
 * 而 JRS 只能走 WebView —— 源站那套播放器是
 * `document.write` 注入**跨站**脚本（`cloud.yumixiu768.com/player/t.js`），
 * chromium 明确警告这种写法"**可能被浏览器拦截**"：
 *
 * ```
 * [INFO:CONSOLE:37] "A parser-blocking, cross site script,
 *   http://cloud.yumixiu768.com/player/t.js?_t=…, is invoked via document.write.
 *   The network request for this script MAY be blocked by the browser…"
 * ```
 *
 * 脚本一被拦，源站播放器就永远起不来 → 嗅探器 25 秒看不到任何媒体请求。
 * **旧行为只是置一个「疑似」标记、不换线**（见 `onPlaybackSuspect` 里
 * "不改线路、不重试"的原注释），于是用户对着黑屏干等。
 * 雷电模拟器实测（截图逐像素统计）：视频区 **0% 非黑像素**、无声音、控件正常 ——
 * 与用户报的现象完全一致。
 *
 * 而**同一场比赛的 JRKAN 线路是能静态解析的**：`steam→sm→inner→msss.html`
 * 能还原出真 m3u8（已用真实播放页验证 `#EXTM3U` 可拉取），
 * 却因为"不是第一条"而永远不会被尝试。
 *
 * ## 为什么这不是"把健康线路也换掉"
 *
 * 原注释担心的是：MSE/`blob:`、地址不带 `.m3u8`、弱网首帧超 25 秒
 * 这三种**正常在播**的情况也会嗅探不到 —— 那时换线就会把正在播的踢掉。
 * 所以判据把"已经拿到直连流（[hasDirectStream]）"作为**第一优先否决**：
 * 只要已经在直连播，无论嗅探结果如何都**不动**（[Decision.Wait]）。
 * 真正会前进的只有"**一条流都没有、而且这条线路已经被判疑似**"——
 * 那正是黑屏状态。
 *
 * ## 边界
 *
 * 每条线路只试一次（调用方用 [Decision] 的输入 `alreadyTried` 记账），
 * 全部试完返回 [Decision.Exhausted]，由上层给一句可执行的提示，
 * 而不是无限循环换线（那会把源站打出限流，见 `play` 里 MAX_FALLBACK_CANDIDATES 的同款理由）。
 */
object PlaybackAutoAdvance {

    /** 这次「疑似」上报之后该怎么办。 */
    sealed interface Decision {

        /** 换到 [nextIndex] 那条线路。 */
        data class Advance(val nextIndex: Int) : Decision

        /** 全部线路都试过了：上层应给明确提示，不要再换。 */
        data object Exhausted : Decision

        /** 条件不成立，什么都不做（等下一次上报）。 */
        data object Wait : Decision
    }

    /**
     * @param signalCount 当前比赛的线路总数
     * @param currentIndex 正在播/正在试的下标
     * @param hasDirectStream **已经拿到直连流**（`streamUrl != null`）——
     *   这是最强否决项：正在直连播就绝不换线，避免踢掉正常播放的画面
     * @param suspect 这条线路是否已被判"疑似"（嗅探超时）
     * @param alreadyTried 已经自动试过的下标（含当前这条）
     */
    fun decide(
        signalCount: Int,
        currentIndex: Int,
        hasDirectStream: Boolean,
        suspect: Boolean,
        alreadyTried: Set<Int>,
    ): Decision {
        if (signalCount <= 0) return Decision.Wait
        if (hasDirectStream) return Decision.Wait
        if (!suspect) return Decision.Wait

        val tried = alreadyTried + currentIndex
        // 环形找下一条没试过的：优先往后，绕回前面（源站第一条常常是最不稳的）
        val next = ((currentIndex + 1) until signalCount).firstOrNull { it !in tried }
            ?: (0 until currentIndex).firstOrNull { it !in tried }
        return if (next == null) Decision.Exhausted else Decision.Advance(next)
    }
}