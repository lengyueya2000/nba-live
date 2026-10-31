package com.nbalive.app.ui

/**
 * 「检查更新」的判据（2026-10-31 用户需求）。
 *
 * ## 为什么需要它
 *
 * 本 App 通过 GitHub Releases 分发 APK，用户装完就**再也不知道有没有新版**——
 * 没有应用商店的更新通道，也没有任何版本检查。每次发版后都要用户自己去翻 release 页。
 *
 * ## 为什么判据要抽出来
 *
 * 版本比较最容易写错的地方都在**边界**上：
 *  - `"1.2.9"` vs `"1.2.10"`：按字符串比会把 1.2.9 判成更新（"9" > "1"）；
 *  - `"1.2.21"` vs `"1.2.3"`：同上；
 *  - 版本号带前缀（`v1.2.23`）、带后缀（`1.2.23-beta`）、段数不同（`1.2` vs `1.2.0`）。
 * 这些都是纯字符串/整数运算，可以在 JVM 上钉死，不需要真机。
 *
 * ## 静默失败是**有意**的
 *
 * GitHub API 在国内经常不通。检查更新失败**不能**给用户弹任何东西 ——
 * 用户没有主动要求检查、失败也不影响他看球，弹一个"检查更新失败"只是噪音。
 * 所以 [decide] 在拿不到远端版本时一律返回 [Decision.UpToDate]，
 * 由调用方决定"用户手动点的时候才提示一句"。
 */
object UpdateCheckPolicy {

    /** 检查结果。 */
    sealed interface Decision {

        /** 有更新可用。 */
        data class Available(
            /** 远端最新版本号（已规范化，如 `1.2.24`） */
            val latest: String,
            /** 下载页地址 */
            val downloadUrl: String,
            /** release 说明（可能为空） */
            val notes: String,
        ) : Decision

        /** 已是最新（或**无法判断**，见类说明）。 */
        data object UpToDate : Decision
    }

    /**
     * 比较两个版本号，返回 remote 是否比 local **新**。
     *
     * 规则：
     *  - 去掉 `v`/`V` 前缀与首尾空白；
     *  - 只取**数字段**（`-beta`、`+build` 这类后缀丢弃）；
     *  - 段数不同时缺的补 0（`1.2` == `1.2.0`）；
     *  - 逐段按**整数**比较，不是字符串。
     *
     * @return true = remote 更新（应该提示用户）；任何一段解析失败都返回 false（宁可不提示）
     */
    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * 规范化版本号用于显示（去掉 `v` 前缀，只留数字段）。
     *
     * `"v1.2.24"` → `"1.2.24"`；`"1.2.24-beta.1"` → `"1.2.24"`；解析不出来 → 原样返回。
     */
    fun normalize(version: String?): String {
        val parsed = parse(version) ?: return version?.trim().orEmpty()
        return parsed.joinToString(".")
    }

    /**
     * 决定要不要提示更新。
     *
     * @param remoteTag GitHub release 的 tag（如 `v1.2.24`）；null = 没拿到（网络失败/限流）
     * @param localVersion 当前安装的版本（`BuildConfig.VERSION_NAME`）
     * @param downloadUrl release 页地址
     * @param notes release 说明
     */
    fun decide(
        remoteTag: String?,
        localVersion: String?,
        downloadUrl: String,
        notes: String,
    ): Decision =
        if (isRemoteNewer(remoteTag, localVersion)) {
            Decision.Available(normalize(remoteTag), downloadUrl, notes)
        } else {
            // 拿不到远端版本（remoteTag == null）也走这里：静默，不打扰
            Decision.UpToDate
        }

    /** 「我的」页那一行显示什么。 */
    fun statusText(decision: Decision): String = when (decision) {
        is Decision.Available -> "有新版本 ${decision.latest}"
        Decision.UpToDate -> "已是最新"
    }

    /**
     * 把 GitHub 的 tag 变成 release 页地址。
     *
     * 直接用 `html_url` 更稳，但纯判据里不依赖网络字段：拿不到时按仓库规则拼。
     * ⚠️ 这只是**兜底**：调用方优先用 API 返回的 `html_url`。
     */
    fun fallbackReleaseUrl(owner: String, repo: String, tag: String): String =
        "https://github.com/$owner/$repo/releases/tag/${tag.trim()}"

    // ---------------------------------------------------------------

    /**
     * 解析成整数段列表。
     *
     * ⚠️ 用 `Regex("""\d+""")` 逐段取，而不是 `split('.')`：
     * 源站/我们自己的 tag 可能写成 `v1.2.24`、`release-1.2.24`、`1.2.24-beta`，
     * 按点切分会把 `release-1` 当成第一段并 `toIntOrNull()` 失败。
     */
    private fun parse(version: String?): List<Int>? {
        val text = version?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = SEGMENT.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        return parts.ifEmpty { null }
    }

    private val SEGMENT = Regex("""\d+""")
}
