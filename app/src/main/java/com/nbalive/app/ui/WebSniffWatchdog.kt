package com.nbalive.app.ui

/**
 * 网页线路「嗅探超时」看门狗（review/01-player.md 的 25 秒疑似判据）。
 *
 * ## 它守的是什么
 *
 * 网页线路不走 ExoPlayer，App 只能等源站页面里的 JS 自己去拉流，再用
 * `shouldInterceptRequest` 把 m3u8 嗅探出来（见 `PlayerScreen.WebSurface`）。
 * 如果页面一直没有任何媒体请求，就要报「疑似无信号」：
 * 让界面给用户一句提示、并允许自动换下一条线（`PlaybackAutoAdvance`）。
 *
 * ## ⚠️ 为什么判据必须抽出来（这是线上"永久黑屏"的第二种形态）
 *
 * 早先这段逻辑写在 `WebSurface` 的 `LaunchedEffect` 里，并且**第一句**就是：
 *
 * ```
 * if (pageFinishedAt == 0L) return@LaunchedEffect   // 页面还没加载完不起跑
 * ```
 *
 * 于是判据被 `onPageFinished` **门控**了：源站播放页要是被广告 / 坏 iframe 卡住
 * 永远不 finish，看门狗永远不触发 —— 既不提示、也不换线，用户看到的是**永久黑屏
 * 且一条日志都没有**（实测样本：纯黑 95 秒，logcat 无任何相关输出）。
 * 「等页面加载完再计时」的初衷是对的（导航 2~3 跳要花时间），
 * 但"永远加载不完"必须有**独立的兜底时限**，否则这条判据在那种页面下等于不存在。
 *
 * 现在两条路径并列（顺序即优先级）：
 *
 * | 情况 | 判据 |
 * |---|---|
 * | 已看到媒体请求 | 不报（在播，别打扰） |
 * | 页面**永不** finish | 从 **加载开始** 起算超过 [HARD_DEADLINE_MS] 才报 |
 * | 页面已 finish | 从 **finish 时刻** 起算超过 [AFTER_FINISH_MS] 才报（语义与旧实现一致） |
 *
 * 判据是纯函数，可直接单测（见 `WebSniffWatchdogTest`）；`WebSurface` 只负责
 * 轮询采样与执行，不再自己写任何阈值。
 */
object WebSniffWatchdog {

    /**
     * 页面加载**完成后**留给源站播放器的时间。
     *
     * 25 秒与旧实现一致（原为 `WebSurface` 里的字面量 `delay(25_000)`）——
     * 这里改成常量不是改行为，而是把"多久算疑似"从 Compose 代码里挪到唯一判据里。
     */
    const val AFTER_FINISH_MS: Long = 25_000L

    /**
     * 从**加载开始**算的硬上限：页面永不 `onPageFinished` 时的兜底。
     *
     * 45 秒 = 25 秒播放器预算 + 20 秒导航预算（源站播放页常有多跳重定向，
     * 而 `pageFinishedAt` 与 `loadUrl` 之间还夹着这段导航时间）。
     * 取值要点：必须**大于**正常路径的"导航 + 25 秒"，否则正常线路会被提前判疑似，
     * 界面连着闪两次提示；又不能太大，否则真卡住的页面黑屏时间肉眼可见地长。
     */
    const val HARD_DEADLINE_MS: Long = 45_000L

    /** 调用方的采样间隔（`WebSurface` 每 [POLL_MS] 读一次状态）。 */
    const val POLL_MS: Long = 1_000L

    /**
     * 单次采样判定 —— **阈值判据的唯一来源**。
     *
     * @param loadStartedAt 本次 `loadUrl` 的起点（单调时钟，见 `WebSurface` 用
     *   `SystemClock.elapsedRealtime()`：用户改系统时间不影响计时）
     * @param pageFinishedAt 首次 `onPageFinished` 的时刻；`0L` = 还没 finish
     * @param nowMs 当前单调时刻
     * @param sawMediaRequest 是否已观察到任何媒体请求
     * @return true = 该报「疑似无信号」了
     */
    fun shouldReport(
        loadStartedAt: Long,
        pageFinishedAt: Long,
        nowMs: Long,
        sawMediaRequest: Boolean,
    ): Boolean = when {
        // 已经在拉流：地址不带 .m3u8 字面量（MSE / blob:）也说明播放器在工作，别打扰
        sawMediaRequest -> false
        // 永不 finish：兜底时限从加载开始算，否则这条判据在这类页面上永远不成立
        pageFinishedAt == 0L -> nowMs - loadStartedAt >= HARD_DEADLINE_MS
        // 已 finish：语义与旧实现一致（finish 之后 25 秒）
        else -> nowMs - pageFinishedAt >= AFTER_FINISH_MS
    }
}