package com.nbalive.app.ui

/**
 * WebView 里允许哪些导航（review/01「广告把播放页导航走」+ 04-P1-3/4）。
 *
 * ## 要挡的是什么
 *
 * 旧实现只挡非 http(s)（`intent://` / `market://`），等于**所有 http/https
 * 导航都放行**。源站播放页里混着的广告脚本做 `location.href = "https://广告页"`
 * 时，主框架被导航走，后果是四条静默全中：
 *  - 源站播放器随页面销毁，画面变黑或直接变成广告页；
 *  - 不触发 `onPlayerError`（那不是 ExoPlayer）；
 *  - 不触发 `onReceivedError`（导航本身是**成功**的）；
 *  - 「疑似无信号」的嗅探计时器也未必会报（广告页照样在发请求）。
 *
 * 也就是说 App 会一直认为「还在播」，而用户面对的是挂着广告、点不动的页面。
 * 这条路径在判死逻辑之外，必须有自己的显式判据。
 *
 * ## 为什么不能一刀切成「只许同源」
 *
 * 源站播放页自己就跨域跳转（实测链路）：
 * `www.jrszbt.com/live/555.html` → `play.sportsteam368.com/play/steamN.html`
 * → 内层 `play/sm.html` → `demo.songzenet.com/msss.html?id=…`。
 * 按严格同源拦，正常播放链路第一步就走不通。
 *
 * 所以策略是**注册域白名单**：只放行源站及其已知播放镜像域，其余 http(s) 拦掉。
 * 新增域要显式加进白名单 —— 把「默认允许一切」换成「默认拒绝」。
 *
 * ## 只作用于主框架
 *
 * ⚠️ 内层播放器整条链路都靠 **iframe** 承载（见上面的跳转链）。
 * 若在非主框架上启用同一套拦截，播放器会被自己的守卫挡死。
 * 所以 [shouldBlock] 对 `isMainFrame = false` 一律放行：
 * 广告 iframe 仍然可能出现，但它不会毁掉播放，
 * 而主框架被导航走是**必然毁掉播放**的那一类。
 */
object WebNavigationPolicy {

    /**
     * 允许承载播放页的**注册域**（不含子域前缀）。
     *
     * 匹配规则见 [hostAllowed]：host 等于该域、或以 `.` + 该域结尾。
     * ⚠️ 绝不能用 `contains` / `endsWith(域)` —— 那样
     * `jrszbt.com.attacker.net` 会因为字符串里含有白名单域而被放行。
     */
    private val ALLOWED_REGISTERED_DOMAINS = setOf(
        "jrszbt.com",        // JRS 源站
        "jrkan.com",         // JRKAN 源站
        "sportsteam368.com", // JRS 播放页
        "sportsteam356.com", // JRKAN 播放页
        "songzenet.com",     // JRKAN 内层 msss.html
    )

    /**
     * 这次导航该不该拦掉。
     *
     * @param url 目标地址
     * @param currentUrl 当前页面地址（取 `WebView.url`）。拿不到时（首跳前）
     *   退化成「只按白名单判」：白名单内放行，白名单外照拦。
     * @param isMainFrame 是否主框架导航。非主框架**一律放行**（见类说明）。
     */
    fun shouldBlock(url: String?, currentUrl: String?, isMainFrame: Boolean = true): Boolean {
        if (!isMainFrame) return false
        val target = parse(url) ?: return true // 畸形/非 http(s)：拦
        // 同源（同一注册域）放行：站点内部的正常跳转
        parse(currentUrl)?.let { current ->
            if (sameRegisteredDomain(target, current)) return false
        }
        return !hostAllowed(target)
    }

    /** 简化签名：只给测试和已知主框架的场景用。 */
    fun shouldBlock(url: String?, currentHostPageUrl: String?): Boolean =
        shouldBlock(url, currentHostPageUrl, isMainFrame = true)

    /**
     * 拦下时给用户看的短说明；放行时返回空串。
     *
     * ⚠️ 只输出**域名**，不带 query/fragment：
     * 源站把签名凭证放在 query 里，错误提示会显示在界面上、也会被截图带出去。
     */
    fun blockReason(url: String?, currentUrl: String?, isMainFrame: Boolean = true): String {
        if (!shouldBlock(url, currentUrl, isMainFrame)) return ""
        val host = parse(url)?.host
        return if (host.isNullOrBlank()) {
            "已拦截一个异常的页面跳转"
        } else {
            "已拦截广告跳转（$host）"
        }
    }

    private data class Target(val scheme: String, val host: String)

    /**
     * 解析出 scheme + host。畸形、无 host、非 http(s) 一律返回 null（调用方按"拦"处理）。
     *
     * 用 `java.net.URI` 而不是手写字符串切分：它会把 `http:///x`、`://x`、
     * `http://` 这类残缺地址判为无效，而手写切分容易在这些形态上给出空 host
     * 却继续往下走。这里**不引 Android 的 `Uri`**：它依赖 android.net，
     * JVM 单测里拿到的是返回默认值的 stub，测不出真实行为。
     *
     * ⚠️ 第二个参数（当前页）允许传**裸 host**：
     * 测试与部分调用点只掌握域名而没有完整 URL，
     * 对"是否同源/同注册域"的判定来说 host 就够了。
     */
    private fun parse(url: String?): Target? {
        val text = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 裸 host（无 scheme、无路径）：测试与部分调用点只掌握域名，
        // 对"是否同源/同注册域"的判定来说 host 就够了，按 https 补齐。
        if (!text.contains("://") && !text.contains("/")) {
            val host = text.lowercase().substringBefore(':')
            if (host.matches(BARE_HOST)) return Target("https", host)
            return null
        }
        val scheme = runCatching { java.net.URI(text).scheme }.getOrNull()?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = runCatching { java.net.URI(text).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank()) return null
        return Target(scheme, host)
    }

    /** 裸 host 形态：字母数字/连字符/点，末尾必须是至少两字母的顶级域。 */
    private val BARE_HOST = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\.[a-z]{2,}$""")

    /** host 是否落在白名单注册域内（含其任意子域）。 */
    private fun hostAllowed(target: Target): Boolean =
        ALLOWED_REGISTERED_DOMAINS.any { domain -> matchesDomain(target.host, domain) }

    /** 两个 host 是否属于同一注册域。 */
    private fun sameRegisteredDomain(a: Target, b: Target): Boolean =
        a.host == b.host ||
            matchesDomain(a.host, b.host) ||
            matchesDomain(b.host, a.host) ||
            registeredDomain(a.host) == registeredDomain(b.host)

    /**
     * host 是否等于 domain 或形如 `*.domain`。
     *
     * ⚠️ 关键：`jrszbt.com.attacker.net` **不能**匹配 `jrszbt.com`。
     * 用 `endsWith` 会匹配（都以 jrszbt.com 结尾吗？不会 —— 它以 .net 结尾），
     * 但 `xjrszbt.com` 用 `endsWith("jrszbt.com")` **会**误匹配，
     * 所以必须要求分隔点存在或整串相等。
     */
    private fun matchesDomain(host: String, domain: String): Boolean =
        host == domain || (host.length > domain.length && host.endsWith(".$domain"))

    /** 取注册域（这里简化为"白名单命中的那个域，否则取 host 末两段"）。 */
    private fun registeredDomain(host: String): String {
        ALLOWED_REGISTERED_DOMAINS.firstOrNull { matchesDomain(host, it) }?.let { return it }
        val parts = host.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }
}
