package com.nbalive.app.ui

/**
 * 网页线路「嗅探超时」看门狗（review/01-player.md 的疑似判据）。
 *
 * 2026-10-30 收紧：finish 后 25s→**15s**、硬上限 45s→**30s**。
 * 起因是用户反馈「网页线路解析播放太慢」——正常页面在 finish 后 2~3 秒就出流，
 * 旧阈值让每条无信号线路多等 10~15 秒（详见 [AFTER_FINISH_MS] 的取值依据）。
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
 * | 页面已 finish | 从 **finish 时刻** 起算超过 [AFTER_FINISH_MS] 才报（语义不变，阈值 15s） |
 *
 * 判据是纯函数，可直接单测（见 `WebSniffWatchdogTest`）；`WebSurface` 只负责
 * 轮询采样与执行，不再自己写任何阈值。
 */
object WebSniffWatchdog {

    /**
     * 页面加载**完成后**留给源站播放器的时间。
     *
     * **15 秒**（2026-10-30 由 25 秒收紧；原为 `WebSurface` 里的字面量 `delay(25_000)`）。
     *
     * 取值依据：
     *  - 正常网页线路在页面 finish 后 **2~3 秒**内就会发出 m3u8 请求（线上实测），
     *    25 秒里绝大部分时间只是纯等待 —— 用户反馈「解析播放太久」，这段是主要来源；
     *  - 15 秒对**弱网 / MSE（blob:）播放页**仍有 5 倍以上余量：这类页面要先让 JS 建好
     *    MSE 管线才发请求，比直连 m3u8 慢，但实测仍在 10 秒内；
     *  - 阈值只决定「多久算疑似无信号」（出一句提示 + 允许自动换线），
     *    **不影响已经在播的页面**：`sawMediaRequest` 一旦为 true 就永不报（见 [shouldReport]）。
     */
    const val AFTER_FINISH_MS: Long = 15_000L

    /**
     * 从**加载开始**算的硬上限：页面永不 `onPageFinished` 时的兜底。
     *
     * **30 秒** = 15 秒播放器预算（[AFTER_FINISH_MS]）+ 15 秒导航预算
     * （源站播放页常有多跳重定向，`pageFinishedAt` 与 `loadUrl` 之间还夹着这段导航时间）。
     *
     * 2026-10-30 由 45 秒收紧：否则「永不 finish」的页面（广告 / 坏 iframe）比正常路径
     * 多黑屏 30 秒 —— 而这类页面恰恰是用户最容易放弃的那一类。
     *
     * 取值要点：必须**大于**正常路径的"导航 + [AFTER_FINISH_MS]"，否则正常线路会被提前
     * 判疑似、界面连着闪两次提示；又不能太大，否则真卡住的页面黑屏时间肉眼可见地长。
     */
    const val HARD_DEADLINE_MS: Long = 30_000L

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
        // 已 finish：从 finish 时刻起算 15 秒（2026-10-30 由 25 秒收紧）
        else -> nowMs - pageFinishedAt >= AFTER_FINISH_MS
    }
}

/**
 * 播放链路的单调时钟（P14）。
 *
 * ## 为什么要有它
 *
 * `PlayerScreen` 原来有 9 处直接调 `android.os.SystemClock.elapsedRealtime()` /
 * `System.nanoTime()`。这些时刻正是"计时 / 复位"类缺陷（P4 跨导航不清零、
 * P5 基准不重置、P6 效应互相抵消）最难被覆盖的原因 —— 只能上真机试。
 * 抽成 `fun interface` 之后，纯逻辑（见 [WebSniffState]）可以在 JVM 单测里
 * 注入一个假时钟，把"导航之后基准是否复位"钉成可失败的判据。
 *
 * ## 为什么默认实现必须是单调时钟
 *
 * `SystemClock.elapsedRealtime()` 不受用户改系统时间 / 跨时区影响。
 * 换成墙钟（`currentTimeMillis()`）时，用户调一次时间就可能让计时永远不到点
 * 或立刻到点 —— 看门狗要么永不触发、要么刚进页面就判「疑似无信号」。
 */
internal fun interface PlayerClock {

    /** 当前单调时刻（毫秒）。 */
    fun nowMs(): Long

    companion object {
        /** 默认实现：单调时钟（`android.os.SystemClock.elapsedRealtime()`）。 */
        val System: PlayerClock = PlayerClock { android.os.SystemClock.elapsedRealtime() }
    }
}

/**
 * 一次 WebView 导航内的嗅探状态（P4 / P5 / P10）。
 *
 * ## 为什么必须有它
 *
 *  - **P4**：`sawMediaRequest` 原来是一个只 `set(true)` 的 `AtomicBoolean`（全仓无 `set(false)`）。
 *    用户点「重新加载」或退回原播放页之后，这条 `WebSurface` 实例的嗅探看门狗
 *    **永久失效** —— 页面再卡住也不会报「疑似无信号」。
 *  - **P5**：`loadStartedAt` 原来只按 `url` remember，导航之后仍按**旧基准**算
 *    [WebSniffWatchdog.HARD_DEADLINE_MS]；退回原页后刚加载的页面只等几秒就被判疑似。
 *  - **P10**：这层状态原来只能靠真机试（3 条 `while(true)` + 6 个 effect 写在 composable 里）。
 *    抽出来之后，"导航代次变化 → 状态复位"可以在 JVM 单测里钉住。
 *
 * ## 线程约定
 *
 * [markMediaRequest] 由 WebView 的**后台线程**（`shouldInterceptRequest`）调用，
 * 其余方法都在主线程 —— 所以用原子类型而不是 Compose 快照状态
 * （写 `mutableStateOf` 会跨线程触碰快照系统）。
 *
 * ## 为什么 `pageFinishedAt` / `pageErrorAt` 不在这里
 *
 * 它们是 `LaunchedEffect` 的 **key**（页面 finish / 首次探到错误时要立刻重跑那轮采样），
 * 而 key 必须是 Compose 快照状态才会触发重启。这里持有的是看门狗真正要读的那两个量
 * （媒体请求时刻、加载起点）与导航代次；两个时间戳由 `WebSurface` 在
 * [onNavigationStarted] 的同一个入口里一起归零（见 `WebSurface.beginNavigation`）。
 */
internal class WebSniffState(private val clock: PlayerClock) {

    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val mediaRequestSeen = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastMediaRequestAt = java.util.concurrent.atomic.AtomicLong(0L)
    private var loadStartedAt = clock.nowMs()

    /** 导航代次：每次重新导航 +1（P10 的判据输入）。 */
    val navigationGeneration: Int get() = generation.get()

    /** 本次导航的加载起点（单调时刻）；随 [onNavigationStarted] 重置。 */
    fun loadStartedAtMs(): Long = loadStartedAt

    /** 本次导航内是否观察到过任何媒体请求。 */
    fun sawMediaRequest(): Boolean = mediaRequestSeen.get()

    /**
     * 最近一次媒体请求的单调时刻；`0L` = 本次导航内还没见过。
     *
     * 消费点：`WebPageDeadEndPolicy` 的第一道否决（见 [PlaybackHealth.hasStreamNow]）。
     */
    fun lastMediaRequestAtMs(): Long = lastMediaRequestAt.get()

    /**
     * 每次导航（首次加载 / 回到原始壳页 / 退回原播放页）都必须调用：
     * **P4 + P5 的唯一复位入口**。
     *
     * 不复位的后果不是"旧数据不好看"，而是看门狗与死胡同判据在**新页面上**读到
     * 上一个页面的事实，于是要么永久沉默（P4）、要么按旧基准提前判疑似（P5）。
     */
    fun onNavigationStarted() {
        generation.incrementAndGet()
        mediaRequestSeen.set(false)
        lastMediaRequestAt.set(0L)
        loadStartedAt = clock.nowMs()
    }

    /** 观察到一条媒体请求（WebView 后台线程调用）。 */
    fun markMediaRequest() {
        mediaRequestSeen.set(true)
        lastMediaRequestAt.set(clock.nowMs())
    }
}