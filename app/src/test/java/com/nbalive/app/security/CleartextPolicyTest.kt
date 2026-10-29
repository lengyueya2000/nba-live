package com.nbalive.app.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文流量策略的**精确收紧**（review/04-security.md P1-5），以及它撞过的那次线上黑屏。
 *
 * ## 现状与取舍
 *
 * `network_security_config.xml` 全局放行明文。这个许可本身是硬需求：
 * 实测抓包（`.forensics/detail_364127.html`）里播放页地址就是
 * `http://play.sportsteam368.com/play/mgxl/?id=...`，JRS 列表页自身
 * 大量引用 `http://www.jrszb444.com`（28 处 http vs 3 处 https），
 * 而 m3u8 清单所在的 CDN 主机名是源站**运行时混淆生成**的，无法预先写死。
 *
 * ## 那还能收紧什么
 *
 * App 自己发起的、**只走 https**、且 **WebView 从不加载**的请求不该被明文许可兜着 ——
 * 一旦哪天被降级/中间人改写，没有任何策略层会拒绝。
 * 这些主机名是写死在代码里的常量（不需要运行时生成），可以精确收紧：
 *
 *  - `jrszbt.com`：JRS 列表/详情页，代码里全部是 `https://`，WebView 从不加载它
 *
 * ## ⚠️ 收紧过头造成过一次线上事故（这条边界比"收紧"更重要）
 *
 * `im-imgs-bucket.oss-accelerate.aliyuncs.com` 曾被按"App 自己的 JRKAN 列表 js 走 https"
 * 收进**禁明文**清单。但源站播放器自己的脚本 `pjs.js` / `8.js` 正是**从这台主机以 http
 * 加载**的，而且发起者是 WebView 里的源站页面（不是 App 自己）：
 *
 *  - v1.2.12（全局放行明文）：脚本加载成功 → 网页播放链路正常 → 非主播线路都能看；
 *  - v1.2.13 起（收紧了该主机）：WebView 报 `net::ERR_CLEARTEXT_NOT_PERMITTED`
 *    （实测探针日志：`ERR main=false code=-1 desc=net::ERR_CLEARTEXT_NOT_PERMITTED
 *    url=http://im-imgs-bucket.oss-accelerate.aliyuncs.com/pjs.js?ver=3`）
 *    → 播放器 iframe 永不创建 → 整条链路**零媒体请求** → 用户看到的正是
 *    「只有主播线路（走 ExoPlayer 直连）能看，其他线路全黑」。
 *
 * 所以本测试是**双向**的：该禁的禁（App 自己的 https-only 主机），
 * 该放的必须放（由 WebView 承载的源站资源，哪怕它是 http）。
 */
class CleartextPolicyTest {

    @Test
    fun `App 自己只走 https 的主机必须禁止明文`() {
        val xml = configText()
        // ⚠️ 硬失败而不是跳过（审计 A10）：这份 xml 是明文策略纪律**唯一**的输入，
        // 解析不到时下面几条断言本来会"零违规"地一起变绿 —— 那是假安全感。
        assertTrue(
            "找不到 network_security_config.xml（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时明文策略守卫会静默全绿，所以直接失败而不是跳过",
            xml != null,
        )

        // ⚠️ 只列"App 自己发起 + 只走 https + WebView 从不加载"的主机。
        // 把 WebView 要加载的源站资源（如 im-imgs-bucket 的播放器脚本）列进来 =
        // 网页播放链路第一步就断（线上黑屏事故，见类 KDoc）。
        val mustRejectCleartext = listOf(
            "jrszbt.com",
        )
        val missing = mustRejectCleartext.filterNot { host ->
            hasDomainConfig(xml!!, host, cleartextPermitted = false)
        }
        assertTrue(
            "以下主机在代码里只以 https 访问、且 WebView 从不加载，却仍被全局明文许可兜着 —— " +
                "一旦被降级/改写，策略层不会拒绝：$missing",
            missing.isEmpty(),
        )
    }

    /**
     * WebView 承载的源站脚本主机**必须**允许明文。
     *
     * 反例就是那次线上黑屏：把它禁掉 → `pjs.js` 加载被拒 → 没有播放器 → 其他线路全黑。
     * 这条判据与上面那条是同一枚硬币的两面（该禁的禁、该放的放）。
     */
    @Test
    fun `WebView 承载的源站脚本主机必须允许明文`() {
        val xml = configText()
        assertTrue(
            "找不到 network_security_config.xml（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "这条判据会静默全绿，所以直接失败",
            xml != null,
        )
        val host = "im-imgs-bucket.oss-accelerate.aliyuncs.com"
        assertTrue(
            "$host 没有显式的「允许明文」domain-config：源站播放器脚本 pjs.js/8.js 由 WebView 里的\n" +
                "源站页面以 **http** 加载，禁掉它 → ERR_CLEARTEXT_NOT_PERMITTED → 播放器 iframe\n" +
                "永不创建 → 其它线路全黑（v1.2.13 起的线上事故，实测探针日志见类 KDoc）。\n" +
                "注意：这**不是**放松纪律 —— App 自己发起的 https-only 主机仍由上面那条判据钉住。",
            hasDomainConfig(xml!!, host, cleartextPermitted = true),
        )
    }

    @Test
    fun `确实以明文提供服务的主机不能被误伤`() {
        val xml = configText()
        // ⚠️ 硬失败而不是跳过（审计 A10）：这份 xml 是明文策略纪律**唯一**的输入，
        // 解析不到时下面几条断言本来会"零违规"地一起变绿 —— 那是假安全感。
        assertTrue(
            "找不到 network_security_config.xml（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时明文策略守卫会静默全绿，所以直接失败而不是跳过",
            xml != null,
        )

        // 实测证据：detail 页里播放页地址是 http://play.sportsteam368.com/...
        // JRS 页面自身 28 处引用 http://www.jrszb444.com
        val mustAllowCleartext = listOf(
            "play.sportsteam368.com",
            "play.sportsteam356.com",
            "jrszb444.com",
        )
        val wronglyBlocked = mustAllowCleartext.filter { host ->
            hasDomainConfig(xml!!, host, cleartextPermitted = false)
        }
        assertTrue(
            "这些主机是**明文**提供服务的（抓包实测），禁止明文等于播放全挂：$wronglyBlocked",
            wronglyBlocked.isEmpty(),
        )
    }

    @Test
    fun `全局明文许可仍在 因为 CDN 主机名无法预先枚举`() {
        val xml = configText()
        // ⚠️ 硬失败而不是跳过（审计 A10）：这份 xml 是明文策略纪律**唯一**的输入，
        // 解析不到时下面几条断言本来会"零违规"地一起变绿 —— 那是假安全感。
        assertTrue(
            "找不到 network_security_config.xml（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时明文策略守卫会静默全绿，所以直接失败而不是跳过",
            xml != null,
        )
        assertTrue(
            "base-config 的明文许可不能删：m3u8 清单主机名是源站运行时混淆生成的，\n" +
                "写死白名单就等于播放器拿不到清单（这是产品硬需求，不是疏忽）。",
            xml!!.contains("cleartextTrafficPermitted=\"true\""),
        )
    }

    // ---------- helpers ----------

    private fun configText(): String? {
        val candidates = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
            File(System.getProperty("user.dir"), "src/main/res/xml/network_security_config.xml"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }

    /** 是否存在对 `host`（含其子域）的 domain-config，且明文许可为期望值。 */
    private fun hasDomainConfig(xml: String, host: String, cleartextPermitted: Boolean): Boolean {
        // 抓出所有 <domain-config ...>...</domain-config> 块（含跨行）
        val blocks = Regex(
            """<domain-config\b([^>]*)>(.*?)</domain-config>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(xml)
        for (b in blocks) {
            val attrs = b.groupValues[1]
            val permitted = Regex("""cleartextTrafficPermitted="(true|false)"""")
                .find(attrs)?.groupValues?.get(1)?.toBoolean() ?: true
            if (permitted != cleartextPermitted) continue
            val domains = Regex("""<domain[^>]*>([^<]+)</domain>""")
                .findAll(b.groupValues[2])
                .map { it.groupValues[1].trim().lowercase() }
                .toList()
            // Android 语义：`example.com` 只匹配该域本身，`.example.com` 才含子域。
            // 这里按"能覆盖该 host"来判断：要么显式列了它，要么列了 .父域。
            if (domains.any { d -> d == host || d == ".$host" || host.endsWith(d.removePrefix(".")) && d.startsWith(".") }) {
                return true
            }
        }
        return false
    }
}