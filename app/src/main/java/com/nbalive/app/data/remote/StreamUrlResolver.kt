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
                // ⚠️ 脱敏：播放页地址里带 playId，query 里可能还有签名
                Log.w(TAG, "fetch failed: ${redactUrl(current)}")
                return null
            }
            debugLog(TAG, "fetched len=${html.length}: $current")

            val candidates = extractM3u8Candidates(html)
            if (candidates.isNotEmpty()) {
                debugLog(TAG, "m3u8 candidates: $candidates")
                return Resolved(
                    m3u8Url = candidates.first(),
                    referer = refererFor(pageUrl),
                    m3u8Candidates = candidates,
                )
            }

            // 中间跳：/play/sm.html?id=439 会再套一层 /play/439.html
            val next = nextHop(current, html)
            if (next == null) {
                Log.w(TAG, "no m3u8 and no next hop in: ${redactUrl(current)}")
                return null
            }
            debugLog(TAG, "hop -> $next")
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
        // 但**尾部**的 &id2= 是源站播放页自己的第二个参数，不属于 payload，必须剥掉。
        //
        // ⚠️ 历史 bug：早先写成 `([^"'\s<]+)(?=&id2=|["'\s<]|$)` ——
        // 字符类没排除 &，贪婪量词会把 "&id2=" 一起吞进捕获组，
        // 而后面那个 (?=&id2=) 前瞻因此永远命中不了（& 已被消费完），是死分支。
        // 后果是还原出的地址尾部多一个 "&id2="，拉不到 #EXTM3U，
        // /play/sm.html 这条唯一能静态还原的链路全废。
        //
        // 修法：先在完整匹配里吃到终止符，再用 removeSuffix 剥掉尾部 &id2=。
        // 这样既保住 payload 内部的裸 &（auth_key 需要），又不会带上 id2。
        Regex("""msss\.html\?id=([^"'\s<]+)""").find(html)?.let { m ->
            val raw = stripId2Tail(m.groupValues[1])
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
        // ⚠️ `.m3u8` 后面必须紧跟 query/hash 或分隔符/结尾：
        // 早先用 `\.m3u8[^\s"'<>]*` 会从 "a.m3u8path=1" 里抠出
        // "http://cdn/a.m3u8path=1" 这类垃圾候选，调用方逐个探测时白费请求。
        //
        // ⚠️ 还要排除"m3u8 只是 query 参数值"的情况：
        // 播放页里常见 `msss.html?id=//cdn/live/a.m3u8&id2=`。
        // 若 host 部分允许 `?`/`&`，匹配会从 `//demo.songzenet.com` 起算，
        // 把**整个播放页 URL**（含 msss.html?id=...）当成 m3u8 候选 ——
        // 实测确实产出这种垃圾：调用方为它白跑一次请求，
        // 反混淆还会把它改成另一个垃圾域名。
        // 判据：host+path 段内不允许 `?`/`&`，真 m3u8 的 query 在 `.m3u8` 之后。
        Regex("""(https?:)?//[^\s"'<>?&]*?\.m3u8(?:[?#][^\s"'<>]*)?(?=["'<>\s]|$)""")
            .findAll(html)
            .forEach { m ->
                // 兜底命中的 query 段同样可能带着播放页的 &id2= 尾巴
                var u = stripId2Tail(m.value)
                if (u.startsWith("//")) u = "http:$u"
                restoreObfuscatedHost(u)?.let { out.add(it) }
                out.add(u)
            }

        return out.toList()
    }

    /**
     * 剥掉 payload 尾部的 `&id2=`。
     *
     * 源站播放页的形态是 `msss.html?id=<payload>&id2=`，
     * 无论主链路还是兜底正则，捕获到的都可能是"payload + &id2="，
     * 而 `id2` 是**播放页**的第二个参数，不属于 m3u8 地址本身。
     */
    private fun stripId2Tail(value: String): String =
        value.removeSuffix("&id2=").removeSuffix("&amp;id2=")

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
