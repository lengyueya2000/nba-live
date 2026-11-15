package com.nbalive.app.data.remote

/**
 * 线路**等价键**：判断两条播放地址是不是同一路流。
 *
 * ## 为什么整串 URL 去重不够（2026-11-15 第三源接入时实测）
 *
 * 合并层旧语义是 `distinctBy { it.pageUrl }` —— 整串相等才算重复。
 * 而这三家源站给同一批云直播线路时**拼法不同**：
 *
 *  | 线路 | JRS | JRKAN | 88看球 |
 *  |---|---|---|---|
 *  | mgxl | `sportsteam368.com/play/mgxl/?id=00588403` | `sportsteam356.com/play/mgxl/?id=00588403` | `sportsteam368.com/play/mgxl/?id=00588403` |
 *  | kbs  | `…368/play/kbs/?id=10022600231` | `…356/play/kbs/?id=10022600231` | `…368/play/kbs/?id=10022600231` |
 *  | pao  | — | `…356/play/pao/?id=39316732` | `…368/play/pao/?id=39316732` |
 *
 * `356` 与 `368` 是**同一套后端的镜像**（已验证：同 path+id 的页面里
 * `playerUrlBase64` 解出同一个 `demo.songzenet.com/paps.html?id=` 入口，
 * 只有每次现发的 `encodedStr` 时效签名不同）。所以只换 host 的两条是同一路，
 * 整串比较会漏 —— 用户在换线列表里看到 3 个名字不同的"同一条线路"，
 * 故障切换也会在它们之间反复撞同一个死上游。
 *
 * ## 判据（保守优先）
 *
 * 归一成 `播放器标识|身份参数`：**同一路必须同键，不同路绝不同键**。
 * 误合并（把两条不同线路判成一条）的代价是**少一条能看的线路**，
 * 漏合并只是列表里多一行 —— 前者更糟，所以判据只在**有把握**时收敛：
 *
 *  - `kbs` 与 `vxt` 即使 id 相同也**算两条**（不同上游，实测 88 同场都给出）；
 *  - `sm.html?id=N` 与 `N.html` **算两条**（前者是中转页、后者是内层页，
 *    保留原始形态能让 `StreamUrlResolver` 的跳数逻辑正常工作）；
 *  - 认不出的形态退回**整串归一**（只统一 scheme/host 大小写、空参数、参数顺序），
 *    绝不跨 host 合并。
 *
 * `m3u8.html?url=<真实地址>` 这类代理壳按**解码后的真实清单地址**归一：
 * 壳本身不携带内容，`auth_key` 不同的两个签名快照仍会落到不同键（都要保留 ——
 * 实测 88 会给同一场的两个 `auth_key`，一个过期另一个还能用）。
 */
object LineEquivalence {

    /** 已知云直播播放器目录（`/play/<name>/`）；不同名字 = 不同上游。 */
    private val PLAYER_PATH = Regex("""/play/([A-Za-z][A-Za-z0-9_-]*)/""")

    /** `/play/steam<id>.html` 入口页。 */
    private val STEAM = Regex("""/play/steam(\d+)\.html""")

    /** 代理壳的页面名：`…/m3u8.html?url=<encode 后的真实 m3u8>`。 */
    private const val M3U8_WRAPPER_PAGE = "m3u8.html"

    /**
     * 这条地址的等价键。同一个输入恒得同一个键（不做大小写外的有损变换）。
     *
     * ⚠️ 返回值只用于**比较**，不许拿去当播放地址、展示或落库：
     * 它是归一化后的指纹，丢掉的信息（scheme、host 全貌）正是"换域名"要保留的部分。
     */
    fun key(pageUrl: String): String {
        val trimmed = pageUrl.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: return "raw:" + normalize(trimmed)
        val path = parsed.path.orEmpty()
        // ⚠️ 用 rawQuery 而不是 query：URI.getQuery() 是**已解码**的，
        // 而 `url=https%3A%2F%2F…%26token%3D…` 这类参数值解码后会带进裸 `&`，
        // 按 `&` 切分就把一个参数劈成两半（键名与值都错，且**不报错** ——
        // 表现为两条不同签名的线路被判成同一路、少一条能看的线路）。
        val query = parsed.rawQuery.orEmpty()

        // 代理壳：按解码后的真实清单地址归一
        if (path.substringAfterLast('/', "").equals(M3U8_WRAPPER_PAGE, ignoreCase = true)) {
            wrappedTarget(query)?.let { return "hls:" + normalize(it) }
        }

        // 裸清单地址：与壳内地址**同键**，否则同一份清单会以"壳 + 直链"两条进列表
        if (path.endsWith(".m3u8", ignoreCase = true)) {
            return "hls:" + normalize(trimmed)
        }

        // steam 入口页：按 playId
        STEAM.find(path)?.groupValues?.get(1)?.let { return "steam:$it" }

        // sm.html?id=N 中转页：按 id（与内层 N.html 刻意不同键，见类说明）
        if (path.endsWith("/play/sm.html")) {
            val id = queryParams(query)["id"] ?: return "raw:" + normalize(trimmed)
            return "sm:$id"
        }

        // 云直播播放器目录：播放器名 + id
        val player = PLAYER_PATH.find(path + "/")?.groupValues?.get(1)?.lowercase()
        if (player != null) {
            val id = queryParams(query)["id"].orEmpty()
            return "player:$player:$id"
        }

        // 认不出的形态：整串归一，保守不合并
        return "raw:" + normalize(trimmed)
    }

    /** 一批线路里保留谁：同键只留**第一条**（调用方已按优先级排好序）。 */
    fun distinctByLine(sources: List<com.nbalive.app.data.model.StreamSource>): List<com.nbalive.app.data.model.StreamSource> {
        val seen = HashSet<String>()
        return sources.filter { seen.add(key(it.pageUrl)) }
    }

    /**
     * 代理壳里那条真实清单地址（已解码）；壳里没有、或解不出清单时返回 null。
     *
     * ⚠️ 必须**按原样切分 rawQuery，再单独解码** `url` 的值：
     * 反过来先解码整串 query，编码过的 `&`（`%26`）会变成真分隔符，
     * 一个参数被劈成两半 —— 两条不同签名的线路于是被判成同一路，
     * 用户少看到的恰好是"唯一还能播的那条"。
     */
    private fun wrappedTarget(rawQuery: String): String? {
        val encoded = rawQuery
            .split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.removePrefix("url=")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        return decoded.takeIf {
            it.startsWith("http") && it.contains(".m3u8", ignoreCase = true)
        }
    }

    /** query → 参数表（重复键取第一个；空值参数保留键）。 */
    private fun queryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty()) null else name to pair.substringAfter('=', "").trim()
            }
            .toMap()
    }

    /**
     * 整串归一：scheme/host 小写、去掉空值参数、参数按名字排序、去掉尾部 `/`。
     *
     * ⚠️ 参数**值**不动：`auth_key=…` 的签名差异必须活下来。
     */
    private fun normalize(url: String): String {
        val u = url.trim()
        val parsed = runCatching { java.net.URI(u) }.getOrNull() ?: return u.lowercase()
        val scheme = (parsed.scheme ?: "").lowercase()
        val host = (parsed.host ?: "").lowercase()
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = parsed.path.orEmpty().trimEnd('/')
        val kept = queryParams(parsed.query.orEmpty())
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .entries
            .joinToString("&") { "${it.key}=${it.value}" }
        val q = if (kept.isEmpty()) "" else "?$kept"
        return "$scheme://$host$port$path$q"
    }
}
