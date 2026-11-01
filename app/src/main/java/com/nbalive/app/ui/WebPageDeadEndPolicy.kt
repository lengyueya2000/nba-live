package com.nbalive.app.ui

/**
 * 「源站播放页自己进入了错误死胡同」时，App 该不该给出**自己的**出口。
 *
 * ## 现场（2026-10-31 用户反馈：「在这个页面点刷新没有反应」）
 *
 * 网页兜底播放时，源站播放器（xgplayer 3.0.23）出错会画一块错误面板：
 * 「网络错误 / 请试试 <em>刷新</em>」。用户点那个「刷新」**什么都不发生**。
 *
 * 这不是我们的点击被吞了，而是那个按钮本身是死路：
 * xgplayer 的 `errorRetry` 只调 `player.retry()`，而 `retry()` 的实现是
 * （`unpkg.byted-static.com/xgplayer/3.0.23/dist/index.min.js` 原文）
 *
 * ```
 * key:"retry",value:function(){ … var i=this.config.url, n=T.isMSE(e.media)?{url:i}:e.preProcessUrl(i);
 *   e.src=n.url, …
 * ```
 *
 * 即**把同一个 `config.url` 重新赋给 `<video>`**。直播地址带时效签名，
 * 那个地址已经失效（或源站已删档），重赋一次还是同一个死地址 → 再报一次同样的错。
 * 面板闪一下又回来，观感就是「点了没反应」。
 *
 * 源站页面里也**没有任何** `location.reload()` / 重新签名逻辑
 * （实测抓取 `paps.html` 全文：`reload` 出现 0 次、`刷新` 出现 0 次，
 * 错误文案全部来自 xgplayer 的 i18n）。所以等源站自己恢复是不可能的。
 *
 * ## 我们能做的
 *
 * 只有 App 能真正换一个**新签名**：重新加载原始播放页（壳页 → 新的 `encodedStr`
 * → 新的 m3u8），或者直接换下一条线路。所以判据是「页面自己报了错」这个**事实**，
 * 而不是「我们嗅探不到」这种推断 —— 后者在 MSE / 只发分片的健康页面上也会成立，
 * 拿它当依据会把「其实在正常播放」的画面盖上一块提示（那正是 P0-1 批评过的误杀）。
 *
 * ## 为什么不是立刻弹
 *
 * 源站播放器自己会先做一次内部重试；[CONFIRM_MS] 给这段自愈留出余地，
 * 避免「刚好在网络抖动的一瞬间」就压一块提示上去。
 */
object WebPageDeadEndPolicy {

    /** 检测到错误面板后，再观察这么久仍然没出流才判定「死胡同」。 */
    const val CONFIRM_MS: Long = 1_500L

    /**
     * 页面内部错误态的采样间隔。
     *
     * 1 秒与 [WebSniffWatchdog.POLL_MS] 一致：注入脚本要过 JSBridge 往返，
     * 更密只是白烧 CPU（这条循环在整个网页播放期间都跑着）。
     */
    const val PROBE_INTERVAL_MS: Long = 1_000L

    /**
     * 连续多少轮探测都「干净」（没看到错误面板）之后才把探测间隔放宽。
     *
     * 3 轮 = 3 秒：足够确认页面已经稳定（不是刚好在两次抖动的间隙里），
     * 又不会让"真报错"的页面等太久 —— 探测到错误立刻把计数清零。
     */
    const val CLEAN_PROBES_BEFORE_BACKOFF: Int = 3

    /**
     * 退避后的探测间隔：页面稳定时从 1Hz 降到每 5 秒一次（P12）。
     *
     * 5 秒：错误面板一旦出现，[CONFIRM_MS] + [AUTO_ADVANCE_MS] 的出口窗口是 9.5 秒，
     * 5 秒采样最多让出口晚出现 5 秒（仍在窗口内），换来的是稳定页面里
     * `evaluateJavascript` 往返从 1Hz 降到 0.2Hz。
     */
    const val PROBE_INTERVAL_SLOW_MS: Long = 5_000L

    /**
     * 已经确认**在出流**时的探测间隔（P12）。
     *
     * 3 秒：页面确实在拉媒体时，它自己的错误面板大概率只是瞬时抖动，
     * 没必要维持 2Hz（与同样 1Hz 的嗅探看门狗叠加）的 JSBridge 往返。
     */
    const val PROBE_INTERVAL_STREAMING_MS: Long = 3_000L

    /**
     * 恢复条显示之后，用户一直不动的话，再过这么久自动换下一条线。
     *
     * 8 秒：与 [SniffRecoveryPolicy.GRACE_MS] 同一个量级 —— 本仓库对"用户要不要自己动手"
     * 的一贯取舍是**给出口、但也给兜底**：干等一块点不动的面板是最差的结果。
     *
     * 计时的起点是**第一次探测到错误**（不是恢复条出现），两者只差 [CONFIRM_MS]。
     */
    const val AUTO_ADVANCE_MS: Long = 8_000L

    /**
     * 该不该在用户没动作的情况下自动换线。
     *
     * 与 [shouldOffer] 的关系：`shouldOffer` 决定"要不要给用户出口"，
     * 这里决定"出口摆了一会儿还没人点，我们替他点"。
     */
    fun shouldAutoAdvance(
        hasStream: Boolean,
        pageErrorDetected: Boolean,
        sinceDetectedMs: Long,
    ): Boolean =
        !hasStream &&
            pageErrorDetected &&
            sinceDetectedMs >= CONFIRM_MS + AUTO_ADVANCE_MS

    /**
     * 该不该显示 App 自己的恢复条。
     *
     * @param hasStream 现在是否已经有可播的流（有流就绝不打扰）
     * @param pageErrorDetected 页面里是否检测到源站播放器的错误面板
     *   （见 [WebErrorProbeScript.HAS_ERROR]，由 WebSurface 轮询注入）
     * @param sinceDetectedMs 从**第一次**检测到错误面板起经过的毫秒数
     */
    fun shouldOffer(
        hasStream: Boolean,
        pageErrorDetected: Boolean,
        sinceDetectedMs: Long,
    ): Boolean = !hasStream && pageErrorDetected && sinceDetectedMs >= CONFIRM_MS

    /**
     * 探测间隔（P12，`player.md` 第 12 条）。
     *
     * 原来是无条件 `while (true) + delay(PROBE_INTERVAL_MS)`：整个网页播放期间每秒一次
     * `evaluateJavascript`（页面内还要遍历全部同源 iframe 并取 `getComputedStyle`），
     * 与同样 1Hz 的嗅探看门狗叠加成 2Hz —— 而这段时间正是 chromium 与源站播放器 JS
     * 最吃 CPU 的时候。
     *
     * 策略：首轮密集（确认错误要快），连续 [CLEAN_PROBES_BEFORE_BACKOFF] 轮干净后退避；
     * 若已确认真的在出流则退到 [PROBE_INTERVAL_STREAMING_MS]。
     * ⚠️ 调用方在探测到错误时必须把 `consecutiveClean` 清零 —— 出口仍要在 [CONFIRM_MS] 内出现。
     *
     * @param consecutiveClean 连续「干净」的探测轮数（探测到错误就归零）
     * @param hasStream 现在是否真的在出流（判据见 [PlaybackHealth.hasStreamNow]）
     */
    fun probeIntervalMs(consecutiveClean: Int, hasStream: Boolean): Long = when {
        hasStream -> PROBE_INTERVAL_STREAMING_MS
        consecutiveClean >= CLEAN_PROBES_BEFORE_BACKOFF -> PROBE_INTERVAL_SLOW_MS
        else -> PROBE_INTERVAL_MS
    }

    /**
     * 「重新加载」按钮该把 WebView 导航到哪里（H6 / `player.md` 第 1 条）。
     *
     * ⚠️ 不能再用 `WebView.reload()`：走 [com.nbalive.app.ui.screens.PlayerPageResolver]
     * 捷径时当前页就是 `paps.html?id=<旧 encodedStr>`，reload 只会用**同一个** encodedStr
     * 重发一次请求，解出来还是那条已失效的地址 —— 用户看到同一块错误面板回来，
     * 与要修的那个"点刷新没反应"一模一样。
     * 回到**原始壳页**（`pageUrl`）才会重新算出新的 encodedStr。
     *
     * @param pageUrl 原始播放页地址（本组件 `url` 形参）
     * @param currentTarget 当前实际加载的地址（捷径时为 paps 地址）。
     *   只用于说明这条判据在两条路径上的语义，返回值与它无关 ——
     *   无论当前在哪，都必须回到壳页重来。
     */
    fun targetOnReload(pageUrl: String, currentTarget: String): String = pageUrl

    /**
     * 恢复条出现的那一刻，要不要**同步**上报一次"疑似无信号"（P18，复核修正版）。
     *
     * 现场：恢复条在 [CONFIRM_MS]（1.5 秒）出现，而上层要到 [AUTO_ADVANCE_MS] 到点
     * （9.5 秒）才收到 `onPageDeadEnd`。中间这 **8 秒**里 `playbackSuspect` 仍是 false，
     * 用户此刻按 Home 会进一块点不动的坏小窗（与 v1.2.8 那个"有地址 ≠ 在播"回归同源）。
     *
     * ⚠️ 上报必须走**只置标记、不换线**的入口。直接复用 `MainViewModel.onPlaybackSuspect`
     * 是**有害**的：它在 `markPlaybackSuspect(true)` 之后紧接着 `advanceAfterNoSignal(...)`
     * （`MainViewModel.kt:1242-1254`），1.5 秒就换线会把恢复条与用户的 8 秒选择窗口一起抹掉。
     *
     * @param deadEndAlreadyOffered 这次采样之前恢复条是否已经出现过（防止每秒重复上报）
     */
    fun shouldEscalateSuspectOnOffer(deadEndAlreadyOffered: Boolean): Boolean = !deadEndAlreadyOffered

    /**
     * 恢复条上的主按钮：重新加载原始播放页。
     *
     * 文案刻意不说「刷新」：源站那个「刷新」是坏的，我们写同一个词会让用户
     * 以为还是那个按钮。说「重新加载」并**换掉**这个入口，用户才点得对。
     */
    fun reloadLabel(): String = "重新加载"

    /** 恢复条上的次按钮：放弃这条线，换下一条。 */
    fun switchLabel(): String = "换线路"

    /** 恢复条上的说明：告诉用户这块提示是 App 给的，不是源站那块坏按钮。 */
    fun note(): String = "源站播放器报错了，换一条线路试试"

    /**
     * 自动换线时拼进提示的原因（与 [SniffRecoveryPolicy.advanceNote] 区分）。
     *
     * 两种路径的现场不一样：那边是"直连流挂了、网页也没恢复"，
     * 这边是"源站播放器自己报了错"。文案撞词会让用户以为遇到同一个问题。
     */
    fun advanceNote(): String = "源站播放器报错"
}
