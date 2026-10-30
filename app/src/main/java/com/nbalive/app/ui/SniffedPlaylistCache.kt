package com.nbalive.app.ui

/**
 * 已经**嗅探成功过**的播放页 -> 真 m3u8 的短期缓存（2026-10-30「解析播放太慢」）。
 *
 * ## 要解决什么
 *
 * 过滤掉主播线路之后，剩下的 JRKAN 线路（`/play/pao|wen|mgxl|wlive/`）全部
 * 只能走 WebView：先加载源站播放页（外部脚本 parser-blocking，实测 0.1~27 秒不等），
 * 再由页面里的播放器发出 m3u8 请求，我们嗅探到后升级成 ExoPlayer。
 *
 * 用户返回列表再点开同一场（或者从画中画回来、切回刚看过的比赛）时，
 * 这条链路会**从头再走一遍** —— 明明上一次已经拿到了可直连的 m3u8。
 *
 * 源站播放页发出的 m3u8 是**带时效签名**的：实测 `expire` 参数在几小时之后，
 * 所以短时间内完全可以复用。这里按播放页缓存（页面 URL 是稳定的，m3u8 每轮签名不同）。
 *
 * ## 规则
 *
 * - 有效期 = min(m3u8 的 `expire` 参数, 现在 + [DEFAULT_TTL_MS])，并 clamp 到 [MAX_TTL_MS]；
 * - 解析不到 `expire` 就用 [DEFAULT_TTL_MS]（这类地址也可能不过期，但短 TTL 更安全）；
 * - `expire` 已经过去 -> 立即视为过期（不缓存死地址）。
 *
 * ## 失效路径（必须存在，否则会反复播死流）
 *
 * 直连失败时 [MainViewModel.onPlaybackFailed] 会调 [invalidate]，
 * 下一次进场回到 WebView 重新嗅探，而不是拿旧地址无限重试。
 *
 * ⚠️ 用**墙钟**（`System.currentTimeMillis`）而不是单调时钟：
 * 缓存的有效期来自源站签名的绝对时间戳，两者必须同一套时间基准。
 * 时钟被改动最多让缓存提前/延后失效 —— 它只影响"快不快"，
 * 不影响正确性（真的 404 了会被 invalidate 掉）。
 */
class SniffedPlaylistCache(
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
) {

    data class Hit(val m3u8Url: String, val referer: String?)

    private data class Entry(
        val m3u8Url: String,
        val referer: String?,
        val validUntilWallMs: Long,
    )

    private val entries = mutableMapOf<String, Entry>()

    /** 记一次成功嗅探。 */
    @Synchronized
    fun put(pageUrl: String, m3u8Url: String, referer: String?, nowWallMs: Long) {
        if (pageUrl.isBlank() || m3u8Url.isBlank()) return
        val validUntil = expiryWallMs(m3u8Url, nowWallMs, defaultTtlMs)
        // 签名已经过期：不要把它写进缓存，否则下一次进场拿到的是一条死地址
        if (validUntil <= nowWallMs) {
            entries.remove(pageUrl)
            return
        }
        purgeExpired(nowWallMs)
        entries[pageUrl] = Entry(m3u8Url, referer, validUntil)
    }

    /** 查一条还在有效期内的缓存；没有/过期返回 null。 */
    @Synchronized
    fun get(pageUrl: String, nowWallMs: Long): Hit? {
        val entry = entries[pageUrl] ?: return null
        if (nowWallMs >= entry.validUntilWallMs) {
            entries.remove(pageUrl)
            return null
        }
        return Hit(entry.m3u8Url, entry.referer)
    }

    /** 这条线的直连已经失败：立刻作废，下次回到网页重新嗅探。 */
    @Synchronized
    fun invalidate(pageUrl: String) {
        entries.remove(pageUrl)
    }

    /** 有效条目数（供测试与诊断）。 */
    @Synchronized
    fun size(nowWallMs: Long): Int {
        purgeExpired(nowWallMs)
        return entries.size
    }

    private fun purgeExpired(nowWallMs: Long) {
        entries.entries.removeAll { (_, e) -> nowWallMs >= e.validUntilWallMs }
    }

    companion object {
        /**
         * 默认 10 分钟。
         *
         * 取值依据：用户"返回列表再点回来"通常在一两分钟内；
         * 10 分钟覆盖了"看一会儿、退出、再回来看同一场"这种常见路径，
         * 同时又不会让一条早已被源站下线的地址活太久。
         */
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        /** 上限 30 分钟：即使签名很长，也不把陈旧地址留成半天。 */
        const val MAX_TTL_MS = 30 * 60 * 1000L

        private val EXPIRE = Regex("""[?&]expire=(\d+)""")

        /**
         * 这条 m3u8 地址在墙钟上到什么时候还有效。
         *
         * 源站 `expire` 是 Unix 时间戳，实测为**秒**（10 位）；
         * 也兼容毫秒（13 位）。解析不到 -> `now + defaultTtl`；
         * 解析到 -> `min(expire, now + MAX_TTL_MS)`。
         */
        fun expiryWallMs(url: String, nowWallMs: Long, defaultTtlMs: Long = DEFAULT_TTL_MS): Long {
            val raw = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()
                ?: return nowWallMs + defaultTtlMs
            // 10 位 = 秒，13 位 = 毫秒。阈值取 1e11（≈1973 年毫秒 / 5138 年的秒）：
            // 任何"秒"级时间戳都远小于它，毫秒级时间戳远大于它。
            val expiry = if (raw < 100_000_000_000L) raw * 1000L else raw
            return minOf(expiry, nowWallMs + MAX_TTL_MS)
        }
    }
}