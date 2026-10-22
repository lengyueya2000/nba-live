package com.nbalive.app.data.remote

import android.util.Log
import java.net.URLDecoder

/**
 * 播放地址解析：把源站的播放页还原成可直连的 HLS m3u8。
 *
 * 已验证的两条链路：
 *
 * A) JRKAN / 简单 sm 播放器
 *    /play/steam{playId}.html
 *      -> iframe src="/play/sm.html?id={innerId}&id2="
 *      -> /play/{innerId}.html
 *      -> <iframe src="//demo.songzenet.com/msss.html?id={encodedM3u8}">
 *    其中 encodedM3u8 是 encodeURIComponent 后的 "//host/path?query"，
 *    且 host 的主域名被**反转**混淆（gnimixuiq -> qiuximing）。
 *    还原方式：取 host 的倒数第二段标签，反转其字符。
 *
 * B) JRS mgxl / pao 播放器
 *    /play/mgxl/?id=xxx&id2=
 *      -> window.playerConfig.playerUrlBase64 = "aHR0cHM6Ly9kZW1vLnNvbmd6ZW5ldC5jb20vcGFwcy5odG1sP2lkPQ=="
 *      -> window.encodedStr = "..."（XXTEA 加密，无法在纯 Kotlin 静态还原）
 *    该链路使用 XXTEA + 混淆 JS 加密，本实现不解析，作为降级（交给 WebView 或标记为不可用）。
 *
 * ⚠️ 反混淆是**有损**的：源站哪天不再混淆，[restoreObfuscatedHost] 就会把正常域名
 * 改成乱码（hls.example.com -> hls.elpmaxe.com）。所以本类不再"只认一个地址"，
 * 而是一次给出多个候选（反混淆版 + 原文版），由调用方用"能不能拉到清单"来裁决。
 */
class StreamUrlResolver {

    companion object {
        /** 最多跟随的中间跳数（steam -> sm -> 内层播放页）。 */
        private const val MAX_HOPS = 4
        private const val TAG = "StreamResolver"
    }

    data class Resolved(
        /** 已选定的 m3u8 地址（调用方校验过可拉出清单的那一个）。 */
        val m3u8Url: String,
        val referer: String?,
        /**
         * 同一播放页还原出的全部候选地址，按可能性从高到低。
         * 调用方应逐个校验（拉清单看有没有 #EXTM3U），第一个成功的即采用。
         */
        val m3u8Candidates: List<String> = listOf(m3u8Url),
    )

    /**
     * @param pageUrl 播放页地址
     * @return 解析成功返回候选地址；失败返回 null
     */
    suspend fun resolve(pageUrl: String, fetch: suspend (String) -> String?): Resolved? {
        var current = pageUrl

        // 最多跟随 3 跳：steam{id}.html -> sm.html?id=N -> N.html -> msss.html
        repeat(MAX_HOPS) {
            val html = fetch(current)
            if (html == null) {
                Log.w(TAG, "fetch failed: $current")
                return null
            }
            Log.d(TAG, "fetched len=${html.length}: $current")

            val candidates = extractM3u8Candidates(html)
            if (candidates.isNotEmpty()) {
                Log.d(TAG, "m3u8 candidates: $candidates")
                return Resolved(
                    m3u8Url = candidates.first(),
                    referer = refererFor(pageUrl),
                    m3u8Candidates = candidates,
                )
            }

            // 中间跳：/play/sm.html?id=439 会再套一层 /play/439.html
            val next = nextHop(current, html)
            if (next == null) {
                Log.w(TAG, "no m3u8 and no next hop in: $current")
                return null
            }
            Log.d(TAG, "hop -> $next")
            current = next
        }
        return null
    }

    /**
     * 从页面里抽取 m3u8 候选地址（含域名反混淆）。
     *
     * 顺序即优先级：反混淆版在前（源站在混淆时它是唯一正确的），
     * 原文版在后（源站哪天不混淆时它才是对的）。
     */
    private fun extractM3u8Candidates(html: String): List<String> {
        val out = LinkedHashSet<String>()

        // 主链路：songzenet msss.html?id=<encoded m3u8>
        // 注意：payload 里可能带 &（auth_key 的 query），不能按 & 截断；
        // 只在遇到 &id2=、引号、空白或标签边界时停。
        Regex("""msss\.html\?id=([^"'\s<]+?)(?=&id2=|["'\s<])""").find(html)?.let { m ->
            val raw = m.groupValues[1]
            val decoded = runCatching {
                // encodeURI 不转义 '+'，而 URLDecoder 会把 '+' 解成空格，
                // 破坏 base64 的 auth_key 签名 -> 先把裸 '+' 还原为 %2B
                URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            }.getOrNull() ?: raw
            restoreObfuscatedHost(decoded)?.let { out.add(it) }
            normalizeScheme(decoded)?.let { out.add(it) }
            normalizeScheme(raw)?.let { out.add(it) }
        }

        // 兜底：页面里直接出现 .m3u8
        Regex("""(https?:)?//[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.let { m ->
            var u = m.value
            if (u.startsWith("//")) u = "http:$u"
            restoreObfuscatedHost(u)?.let { out.add(it) }
            out.add(u)
        }

        return out.toList()
    }

    private fun normalizeScheme(raw: String): String? {
        val value = raw.trim()
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "http:$value"
            else -> null
        }
    }

    /**
     * 计算下一跳地址。两种情况都要覆盖：
     *
     *  1. 当前页就是 `sm.html?id=439`：页面用 JS 拼出 `/play/439.html`，
     *     静态 HTML 里查不到 `sm.html?id=`，必须从当前 URL 取 id。
     *  2. 当前页是 `steam{id}.html`：HTML 里有 `iframe src="/play/sm.html?id=439"`。
     */
    private fun nextHop(current: String, html: String): String? {
        val origin = runCatching {
            val u = java.net.URI(current)
            val host = u.host ?: return null
            // 保留端口：播放页挂在非常规端口时，丢掉端口下一跳必然连不上
            val port = if (u.port > 0 && !((u.scheme == "http" && u.port == 80) || (u.scheme == "https" && u.port == 443))) ":${u.port}" else ""
            "${u.scheme ?: "http"}://$host$port"
        }.getOrNull() ?: return null

        // 情况 1：当前 URL 自身就是 sm.html?id=N
        val innerId = Regex("""sm\.html\?id=([^"&\s]+)""").find(current)?.groupValues?.get(1)
            ?: Regex("""sm\.html\?id=([^"'&\s]+)""").find(html)?.groupValues?.get(1)

        if (innerId.isNullOrBlank()) return null
        val next = "$origin/play/$innerId.html"
        return if (next == current) null else next
    }

    /**
     * 还原被反转混淆的主域名。
     * 例：hdl2.gnimixuiq.com -> hdl2.qiuximing.com
     *
     * ⚠️ 本函数**假定**域名被混淆过。源站取消混淆时它会返回错误的主域名，
     * 因此调用方必须把它当作候选之一、用实际拉清单来验证，不能直接当唯一答案。
     */
    fun restoreObfuscatedHost(raw: String): String? {
        var value = raw.trim()
        if (value.startsWith("//")) value = "http:$value"
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null

        val schemeEnd = value.indexOf("://") + 3
        val hostStart = schemeEnd
        val pathStart = value.indexOf('/', hostStart).let { if (it < 0) value.length else it }
        val authority = value.substring(hostStart, pathStart)
        val rest = value.substring(pathStart)

        val hostOnly = authority.substringBefore(':')
        val port = authority.substringAfter(':', "")
        // IP 字面量没有"倒数第二段主域名"可言，直接原样返回
        if (Regex("""\d+\.\d+\.\d+\.\d+""").matches(hostOnly)) return value
        val labels = hostOnly.split('.').toMutableList()
        if (labels.size < 2) return null

        // 反转倒数第二段（主域名）
        val idx = labels.size - 2
        labels[idx] = labels[idx].reversed()

        val newHost = labels.joinToString(".")
        val newAuthority = if (port.isNotEmpty()) "$newHost:$port" else newHost
        return value.substring(0, hostStart) + newAuthority + rest
    }

    /**
     * m3u8 主站需要 Referer 才会返回清单（已验证），用播放页同源作为 Referer。
     */
    private fun refererFor(pageUrl: String): String? = runCatching {
        val u = java.net.URI(pageUrl)
        val scheme = u.scheme ?: "http"
        val host = u.host ?: return null
        "$scheme://$host/"
    }.getOrNull()
}
