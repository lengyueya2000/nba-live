package com.nbalive.app.ui.screens

/**
 * 从源站播放页壳里算出真正的玩家页地址（2026-10-30「解析播放太慢」）。
 *
 * ## 页面链路（实测抓到的结构）
 *
 * 播放页壳（约 2 KB）：
 * ```
 * window.playerConfig = { playerUrlBase64: "aHR0cHM6Ly9kZW1vLnNvbmd6ZW5ldC5jb20vcGFwcy5odG1sP2lkPQ==" };
 * var encodedStr = "uZenXFlv6HsKu7BL5RNqTJvx6qhdQCTbay42...";
 * ```
 * 壳页再加载 `pjs.js`，由它拼出 `base64 解码结果 + encodedStr` 并导航到
 * `https://demo.songzenet.com/paps.html?id=<encodedStr>`；
 * m3u8 是**那个页面**里的播放器发的（我们靠嗅探拿到）。
 *
 * 壳页还要额外拉跨站 jquery（`ss0.bdstatic.com`）与 `t.js`（parser-blocking），
 * 实测这一步耗时 0.1~27 秒。直接加载算出来的 paps 地址可以跳过这一层 ——
 * 但**必须保留回退**：解析/加载任何一步不成立，就退回原播放页，不能让用户因此播不出来。
 */
object PlayerPageResolver {

    private val PLAYER_URL_BASE64 = Regex("""playerUrlBase64["']?\s*[:=]\s*["']([^"']+)["']""")
    private val ENCODED_STR = Regex("""encodedStr\s*=\s*["']([^"']+)["']""")

    /**
     * @param pageHtml 播放页壳的 HTML（由 OkHttp 拉取，不执行脚本）
     * @param baseUrl 壳页地址（用于补全协议相对的玩家地址）
     * @return 可直接加载的玩家页 URL；解析不出来返回 null
     */
    fun resolvePlayerUrl(pageHtml: String, baseUrl: String): String? {
        if (pageHtml.isBlank()) return null
        val base64 = PLAYER_URL_BASE64.find(pageHtml)?.groupValues?.get(1) ?: return null
        val encoded = ENCODED_STR.find(pageHtml)?.groupValues?.get(1) ?: return null
        if (encoded.isBlank()) return null

        val prefix = decodeBase64(base64)?.trimEnd() ?: return null
        if (prefix.isBlank() || !prefix.contains("://")) return null
        val absolute = if (prefix.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https")
            "$scheme:$prefix"
        } else {
            prefix
        }
        return absolute + encodeComponent(encoded)
    }

    /** `encodedStr` 放在 query 里：`+` `/` `=` 必须转义，否则服务端解不开。 */
    private fun encodeComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * 手写 Base64 解码。
     *
     * ⚠️ 不用 `java.util.Base64`（API 26+，本项目 minSdk 24），也不用
     * `android.util.Base64`（JVM 单测里没有 Android 运行时，无法覆盖）。
     */
    internal fun decodeBase64(input: String): String? {
        val cleaned = input.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.length % 4 != 0) return null
        val bytes = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
            if (c == '=') break
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes.write((buffer shr bits) and 0xFF)
            }
        }
        return runCatching { bytes.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
    }
}