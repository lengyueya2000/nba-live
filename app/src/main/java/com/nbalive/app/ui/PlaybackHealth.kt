package com.nbalive.app.ui

/**
 * 播放健康度的分级判定（纯逻辑，P0-1）。
 *
 * ## 为什么要分级
 *
 * 原来的链路是「25 秒没嗅探到含 `.m3u8` 的请求 ⇒ 判死 ⇒ `finishAndRemoveTask()`」：
 * 判据是**推断**（既不问播放器也不看画面），处置却**不可逆**（把整个任务从
 * 最近任务里移除）。两者叠在一起，误判的代价是用户正在看的小窗连同任务一起消失，
 * 而且再也找不回来。
 *
 * 误判并非理论风险，至少三条正常在播的路径会被它误杀：
 *  - 源站用 MSE/`blob:` 播放，或 m3u8 地址不带 `.m3u8` 字面量 —— 永远嗅探不到；
 *  - 弱网首帧 > 25 秒 —— 页面还没发起视频请求；
 *  - 源站播放页把主框架导航到广告页 —— 导航成功，所以既无 `onPlayerError`
 *    也无 `onReceivedError`，App 反而一直以为"还在播"。
 *
 * 所以这里把两件事彻底分开：
 *  - **[Level.SUSPECT] 疑似异常**：来自嗅探超时这类**推断**。只用于
 *    显示提示 + 停掉「按 Home 自动进小窗」，**绝不**关闭任何东西；
 *  - **[Level.DEAD] 确认失败**：来自播放器**自己报的错**
 *    （ExoPlayer `onPlayerError`、WebView 主框架真实失败码）。
 *    才允许收掉小窗，且处置降级为可逆（保留最近任务）。
 *
 * 命名沿用 `PipState.shouldKeepPip` 里已经写过的那条原则：
 * **"有地址"不等于"在播"**，真正确认要看播放器有没有报错。
 */
object PlaybackHealth {

    enum class Level {
        /** 一切正常，什么都不做 */
        OK,

        /** 疑似异常（推断而来）：提示 + 停自动进小窗，不收窗、不关任务 */
        SUSPECT,

        /** 确认失败（播放器报错而来）：允许收掉小窗 */
        DEAD,
    }

    /**
     * 收掉坏小窗的处置方式。
     *
     * ⚠️ 原来的 `finishAndRemoveTask()` 会把任务从**最近任务**里一并移除。
     * 单 Activity 的应用里，这等于用户再也找不到这个 App 的入口，
     * 而触发它的只是一次**推断**。降级为 [KEEP_RECENTS]：
     * `finish()` 只关窗口、任务留在最近任务里，用户点开还能看到完整错误页
     * 和重试入口 —— 这才是原注释里承诺的行为（旧代码做不到，Activity 已被移除）。
     */
    enum class PipDisposition { NONE, KEEP_RECENTS }

    /**
     * `android.webkit.WebViewClient.ERROR_*` 的本地副本。
     *
     * 抽成常量有两个原因：单测跑在 JVM 上拿不到 Android stub 的语义，
     * 而且把"哪些码算真失败"这件事显式写在这里，比散在 `if` 里更好复核。
     */
    const val WEB_ERROR_UNKNOWN = -1
    const val WEB_ERROR_HOST_LOOKUP = -2
    const val WEB_ERROR_CONNECT = -4
    const val WEB_ERROR_TIMEOUT = -8
    const val WEB_ERROR_FAILED_SSL_HANDSHAKE = -11
    const val WEB_ERROR_BAD_URL = -12
    const val WEB_ERROR_FILE_NOT_FOUND = -14

    /**
     * `ERR_ABORTED`：**导航被新导航取代**，不是加载失败。
     *
     * 触发场景（review/01-P1-1）：源站播放页里有一句 `location.href = ...` 跳转，
     * 或者用户在计时窗口内连点两次「重连」（`WebSurface` 的 update 里
     * `loadUrl` 打断上一次导航）。旧代码不分码一律判死，于是**一次正常的重定向
     * 就能把播放页判成彻底坏了**。
     */
    const val WEB_ERROR_ABORTED = -3

    /** 只有这些失败码说明"页面真的没打开"。 */
    private val FATAL_WEB_ERRORS = setOf(
        WEB_ERROR_UNKNOWN,
        WEB_ERROR_HOST_LOOKUP,
        WEB_ERROR_CONNECT,
        WEB_ERROR_TIMEOUT,
        WEB_ERROR_FAILED_SSL_HANDSHAKE,
        WEB_ERROR_BAD_URL,
        WEB_ERROR_FILE_NOT_FOUND,
    )

    /**
     * WebView 的这次报错能不能算「确认失败」。
     *
     * 三个条件都要满足，缺一不算：
     *  1. **主框架**：子资源（图片、广告 iframe）失败很常见，画面不受影响；
     *  2. **不是重定向**：重定向意味着导航还在正常推进，最终页面可能马上就出来；
     *  3. **失败码在致命集合里**：把 `ERR_ABORTED` 这类"被取代"排除掉。
     *
     * @param errorCode `WebResourceError.errorCode`（可能拿不到，null 不判死）
     * @param isForMainFrame 是否主框架
     * @param isRedirect 该请求是否为重定向链上的一环
     * @param pageFinished 首次加载是否已完成（页面还没加载完时的错误不判死：
     *   源站首页常有跳转，把它算成失败会误杀正常线路）
     */
    fun isConfirmedWebFailure(
        errorCode: Int?,
        isForMainFrame: Boolean,
        isRedirect: Boolean,
        pageFinished: Boolean,
    ): Boolean =
        isForMainFrame &&
            !isRedirect &&
            pageFinished &&
            errorCode != null &&
            errorCode in FATAL_WEB_ERRORS

    /**
     * 嗅探时这条请求算不算「媒体在动」。
     *
     * ## 为什么不能只看 `.m3u8`
     *
     * 旧判据是 `u.contains(".m3u8")`，于是以下形态**正常在播也永远嗅不到**
     * （review/01-P0-1 明确列出的误杀场景）：
     *  - 源站改用 DASH（`.mpd`）或纯分片路径（`/hls/`、`/dash/`、签名写进 path）；
     *  - 直播走 `.ts` / `.m4s` / `.flv` 分片而清单地址不带扩展名；
     *  - 走 MSE + `blob:`，清单地址本身不可见但分片请求照样会经过这里。
     *
     * 所以把判据放宽成「任何在拉媒体数据」：看到分片就认定播放器在动，
     * 不再报疑似。它**不**解决嗅探升级（那需要真实清单地址），只解决误判。
     *
     * ## 为什么必须只看 path、不看 query
     *
     * 两个方向的错都能发生：
     *  - 源站播放页自己的地址常常就在 `/live/` 目录下
     *    （`http://www.jrszbt.com/live/555.html`）。若按整串 URL 匹配目录标记，
     *    **页面文档会被当成媒体请求**，「疑似无信号」从此永不触发 ——
     *    比赛打完了也不提示，等于把误杀改成漏判，修出新的静默失败。
     *  - 广告/统计跳转常把真实地址塞在 query 里
     *    （`/ad/redirect?to=...x.m3u8`）。把它当媒体同样是假信号。
     *
     * 因此先剥掉 query/fragment，只在 **path** 上匹配扩展名与目录标记，
     * 并且排除文档/静态资源后缀。
     */
    fun isMediaRequest(url: String?): Boolean {
        val path = url.pathPortion() ?: return false
        if (DOCUMENT_SUFFIXES.any { path.endsWith(it) }) return false
        return MEDIA_EXTENSIONS.any { path.contains(it) } ||
            MEDIA_PATH_MARKERS.any { path.contains(it) }
    }

    /**
     * 清单/流地址：命中这些才可能升级成 ExoPlayer 直连。
     *
     * ⚠️ 同样只看 path。旧写法 `u.contains(".m3u8") && !u.contains(".js")`
     * 会被 `player/config.js?src=a.m3u8` 这类脚本 URL 喂假信号
     * （脚本里常带拼接出来的清单字符串），拿它去直连必然失败。
     */
    fun isPlaylistRequest(url: String?): Boolean {
        val path = url.pathPortion() ?: return false
        if (DOCUMENT_SUFFIXES.any { path.endsWith(it) }) return false
        return PLAYLIST_EXTENSIONS.any { path.contains(it) }
    }

    /**
     * 取 URL 的 path 部分（剥掉 query 与 fragment），小写便于匹配。
     * 空/畸形返回 null 让调用方判否。
     */
    private fun String?.pathPortion(): String? {
        if (isNullOrBlank()) return null
        val cut = listOf(indexOf('?'), indexOf('#')).filter { it >= 0 }.minOrNull()
        val base = if (cut == null) this else substring(0, cut)
        return if (base.isBlank()) null else base.lowercase()
    }

    private val PLAYLIST_EXTENSIONS = listOf(".m3u8", ".mpd")
    private val MEDIA_EXTENSIONS = PLAYLIST_EXTENSIONS + listOf(".ts", ".m4s", ".flv", ".mp4")
    private val MEDIA_PATH_MARKERS = listOf("/hls/", "/dash/", "/ts/", "/segment")
    /**
     * 文档与静态资源后缀：这些一律**不是**媒体。
     *
     * `.html` 必须在列 —— 源站播放页地址形如 `/live/555.html`，
     * 少了它就会把页面文档当成分片。
     */
    private val DOCUMENT_SUFFIXES = listOf(
        ".html", ".htm", ".js", ".css", ".png", ".jpg", ".jpeg", ".gif",
        ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".json", ".xml",
    )

    /**
     * 把两路信号合成一个健康等级。

     *
     * **确认失败优先于疑似**：一旦播放器自己报了错，就按 DEAD 处理；
     * 只有推断（嗅探超时）时最多到 SUSPECT。
     *
     * @param sniffTimedOut 嗅探计时到点且没看到流（推断）
     * @param confirmedFailure 播放器/WebView 主动报错（事实）
     */
    fun levelOf(sniffTimedOut: Boolean, confirmedFailure: Boolean): Level = when {
        confirmedFailure -> Level.DEAD
        sniffTimedOut -> Level.SUSPECT
        else -> Level.OK
    }

    /**
     * 该等级下小窗怎么处置。
     *
     * ⚠️ SUSPECT 返回 [PipDisposition.NONE] 是这次修复的核心：
     * 推断出来的问题不配拿走不可逆的处置。
     */
    fun pipDisposition(level: Level): PipDisposition = when (level) {
        Level.DEAD -> PipDisposition.KEEP_RECENTS
        Level.OK, Level.SUSPECT -> PipDisposition.NONE
    }

    /**
     * 「按 Home 是否还在看视频」里要不要算上这个等级。
     *
     * SUSPECT 和 DEAD 都**不该**自动进小窗 —— 区别只在于处置：
     * 疑似时不弹小窗，但已经在播的小窗不主动收掉。
     */
    fun blocksAutoEnterPip(level: Level): Boolean = level != Level.OK

    /** 是否该在界面上显示"疑似无信号"的提示。 */
    fun showsSuspectHint(level: Level): Boolean = level == Level.SUSPECT
}
