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
        ) : Decision

        /** 已是最新（或**无法判断**，见类说明）。 */
        data object UpToDate : Decision
    }

    /**
     * 比较两个版本号，返回 remote 是否比 local **新**。
     *
     * 规则：
     *  - 去掉 `v`/`V` 前缀与首尾空白；
     *  - **数字段**逐段按整数比较，段数不同时缺的补 0（`1.2` == `1.2.0`）；
     *  - `-`/`+` 之后的部分是**后缀**，不参与数字段比较，
     *    且带后缀的版本**低于**同数字的正式版（review/optimization-review.md D17）：
     *    `1.2.24-hotfix` < `1.2.24`、`1.2.24-rc.1` < `1.2.24`；
     *  - 两边都带后缀时，后缀里的数字段仍可比较（`1.2.24-rc.2` > `1.2.24-rc.1`）。
     *
     * ## 为什么后缀必须"降级"（旧实现的两种误判）
     *
     * 旧实现只用 `Regex("""\d+""")` 把整串的数字段全捞出来：
     *  - `1.2.24-hotfix.1` → `[1,2,24,1]` 被判成比 `1.2.24` **新** →
     *    提示用户升级到一个"同名版本"，点了没变化；
     *  - `1.2.24-hotfix` → `[1,2,24]` 被判成**相等** → 真有新版却不提示。
     * 走 `releases/latest`（GitHub 已排除 prerelease）时 `-beta` 不构成真实风险，
     * 真正的缝是"正式 tag 带后缀"，两种都误判，而判据是纯函数，改起来最便宜。
     *
     * @return true = remote 更新（应该提示用户）；任何一段解析失败都返回 false（宁可不提示）
     */
    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val r = split(remote) ?: return false
        val l = split(local) ?: return false

        // ① 先比数字段（语义与旧实现一致：段数不同补 0）
        val size = maxOf(r.core.size, l.core.size)
        for (i in 0 until size) {
            val a = r.core.getOrElse(i) { 0 }
            val b = l.core.getOrElse(i) { 0 }
            if (a != b) return a > b
        }

        // ② 数字段相同：无后缀的正式版比带后缀的高
        return when {
            r.suffix.isEmpty() && l.suffix.isEmpty() -> false
            r.suffix.isEmpty() -> true // remote 是正式版、local 是预发布/修订版
            l.suffix.isEmpty() -> false // remote 带后缀、local 是正式版
            // ③ 两边都带后缀：按后缀里的数字段比（rc.2 > rc.1），相同则不算更新
            else -> compareSegments(suffixDigits(r.suffix), suffixDigits(l.suffix)) > 0
        }
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
     * ⚠️ 原先还有一个 `notes` 形参：release 说明被解析出来、长期持有，
     * 却**没有任何界面消费**（review/optimization-review.md U14）。
     * 零消费的形参会让人误以为"说明已经展示出来了"，已删除；
     * 将来真要显示说明时再按需要加回来（并同时接上界面）。
     *
     * @param remoteTag GitHub release 的 tag（如 `v1.2.24`）；null = 没拿到（网络失败/限流）
     * @param localVersion 当前安装的版本（`BuildConfig.VERSION_NAME`）
     * @param downloadUrl release 页地址
     */
    fun decide(
        remoteTag: String?,
        localVersion: String?,
        downloadUrl: String,
    ): Decision =
        if (isRemoteNewer(remoteTag, localVersion)) {
            Decision.Available(normalize(remoteTag), downloadUrl)
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
     *
     * ⚠️ 这条**只服务 [normalize]**：它刻意保留后缀里的数字
     * （`1.2.24-beta.1` → `1.2.24.1`），既有用例钉着这个行为。
     * 比较语义走 [split]，那里后缀不参与数字段比较。
     */
    private fun parse(version: String?): List<Int>? {
        val text = version?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = SEGMENT.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        return parts.ifEmpty { null }
    }

    /**
     * 版本号的"数字核心 + 后缀"拆分结果。
     *
     * @param core 参与数字段比较的段（后缀之前的数字）
     * @param suffix `-`/`+` 之后的原文（**不**参与数字段比较）
     */
    private data class Parsed(val core: List<Int>, val suffix: String)

    /**
     * 拆出"数字核心 + 后缀"（review/optimization-review.md D17）。
     *
     * - `1.2.24` → core=`[1,2,24]`，suffix=`""`
     * - `1.2.24-rc.1` → core=`[1,2,24]`，suffix=`"rc.1"`
     * - `release-1.2.24` → 第一个 `-` 之前没有数字，**不**当成后缀
     *   （退化为旧行为：整串取数字段，suffix 为空）—— 免得把这类 tag 判成"解析失败"
     *   而彻底静默不提示。
     */
    private fun split(version: String?): Parsed? {
        val text = version?.trim()?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cut = text.indexOfFirst { it == '-' || it == '+' }
        val head = if (cut >= 0) text.substring(0, cut) else text
        val headParts = parseInts(head)
        if (headParts.isEmpty()) {
            val all = parseInts(text)
            return if (all.isEmpty()) null else Parsed(all, "")
        }
        return Parsed(headParts, if (cut >= 0) text.substring(cut + 1) else "")
    }

    private fun parseInts(text: String): List<Int> =
        SEGMENT.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()

    /** 后缀里的数字段（`rc.1` → `[1]`）；没有数字时空列表。 */
    private fun suffixDigits(suffix: String): List<Int> = parseInts(suffix)

    /** 逐段比大小，返回 -1 / 0 / 1（段数不同补 0）。 */
    private fun compareSegments(a: List<Int>, b: List<Int>): Int {
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private val SEGMENT = Regex("""\d+""")
}
