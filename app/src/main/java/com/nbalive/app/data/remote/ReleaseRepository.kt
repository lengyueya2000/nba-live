package com.nbalive.app.data.remote

/**
 * GitHub Releases 的读取（review/optimization-review.md U12 / D15）。
 *
 * ## 为什么要有这个文件
 *
 * 「检查更新」原来整个长在 `MainViewModel` 里：VM 直接发 HTTP
 *（`HttpClient.shared.fetch(url, null, fastFail = true)`）**并且手写一段 47 行的
 * JSON 字符串状态机** `jsonString`。这越过了本工程的 `data/` 边界约定
 * ——平台/网络/解析都归 `data/`，`ui/` 只消费（同 `MatchRepository` /
 * `EspnScoreboard` / `ScoreboardRepository` 的位置）。
 *
 * 后果不只是"分层不好看"：超时/脱敏策略、JSON 取值边界校验都在 VM 里各写一份，
 * 与数据层那份（`EspnScoreboard.JsonCursor`）漂移。这里让取值回到**同一套**
 * [EspnScoreboard.JsonCursor]：它已经带"key 前必须是 `{`/`,`"的边界校验
 *（D15 指出 `jsonString` 缺这一条，`"homeScore"` 会命中更长的键）。
 *
 * ## 注入 fetch 而不是用 `HttpClient.shared`
 *
 * `fetch` 由调用方注入（生产是 `HttpClient.shared.fetch(..., fastFail = true)`，
 * 与改前同一个选型）：JVM 单测因此能驱动"拿到 200 / 非 2xx / 垃圾正文 /
 * 字段缺失"四条分支，不需要真网络，也不需要 `HttpClient` 的 Android 依赖。
 *
 * ## 只读"界面真正要用的字段"
 *
 * 返回 [Release] 里只有 `tag` 与 `html_url`。**刻意不读 `body`**：
 * release 说明往往几十 KB，而全仓没有任何 UI 展示它（U14）——
 * 解析并长期持有一份永远不显示的文本是纯浪费。
 *
 * @param fetch HTTP 入口；`fetch(url)` 返回 [FetchResult]（非 2xx / 超时 → 非 Ok）
 */
class ReleaseRepository(
    private val fetch: suspend (String) -> FetchResult,
) {

    /** 一次 release 查询的结果（只保留判据与下载入口真正要用的字段）。 */
    data class Release(
        /** release 的 tag（如 `v1.2.24`；是否更新由 `UpdateCheckPolicy` 判） */
        val tag: String,
        /** release 页地址（`html_url`）；为空时调用方走 `fallbackReleaseUrl` 兜底 */
        val htmlUrl: String,
    )

    /**
     * 读取一次 release。
     *
     * **静默失败**（与 `UpdateCheckPolicy.decide` 的口径一致）：网络不通、
     * 非 2xx、正文不是 JSON、缺 `tag_name` —— 一律返回 null，由调用方落
     * 「已是最新」。检查更新失败不该给用户弹任何东西（GitHub 在国内经常不通）。
     */
    suspend fun latest(url: String): Release? {
        val body = when (val result = fetch(url)) {
            is FetchResult.Ok -> result.body
            else -> return null
        }
        val cursor = EspnScoreboard.JsonCursor(body)
        val tag = cursor.string("tag_name")
        // ⚠️ tag 是"有没有新版本"的唯一输入：拿不到就当查不到（宁可不提示）
        if (tag.isNullOrBlank()) return null
        return Release(tag = tag, htmlUrl = cursor.string("html_url").orEmpty())
    }
}