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
