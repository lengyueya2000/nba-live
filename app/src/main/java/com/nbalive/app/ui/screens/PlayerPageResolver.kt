package com.nbalive.app.ui.screens

import com.nbalive.app.data.remote.Base64Decode

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

    /**
     * 拉壳页的超时（P13，`review/opt/player.md` 第 13 条）。
     *
     * 原来是 `WebSurface` 里的裸字面量 `2_000L`。这条请求是**额外**的一次 OkHttp 拉取
     * （WebView 同时在加载同一个壳页），失败/超时只是回退到原播放页 ——
     * 所以超时越短越划算：弱网下 2 秒会把"进网页线路"整体拖慢 2 秒，
     * 而收益（跳过 parser-blocking 的 `t.js`）依赖解析成功。
     *
     * **900ms**：壳页是 KB 级、通常几百毫秒内返回，给弱网留约一倍余量，
     * 同时把最坏等待压到 1 秒以内。
     */
    const val SHELL_FETCH_TIMEOUT_MS: Long = 900L

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
     * Base64 解码——**委托给 [Base64Decode.decode]**（进程内唯一实现）。
     *
     * 原来这里是本文件自己的一份私有实现；接入 88看球时 `K88Parser` 也要解 base64，
     * 于是变成两份（review/optimization-review.md D15 给 JSON 取值器立的同一条纪律：
     * 同一份规则写两遍，改一处漏一处）。
     *
     * ⚠️ 保留这个 `internal` 入口而不是让调用方直接改：`PlayerPageResolverTest`
     * 有一条"与 `java.util.Base64` 逐样本比对"的用例钉着它的行为，
     * 名字与签名不变，那条判据继续有效。
     */
    internal fun decodeBase64(input: String): String? = Base64Decode.decode(input)
}