package com.nbalive.app.security

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.TestPaths
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
            "找不到 network_security_config.xml（cwd=${File(TestPaths.workingDirPath()).absolutePath}）—— " +
                "锚点缺失时明文策略守卫会静默全绿，所以直接失败而不是跳过",
            xml != null,
        )

        // ⚠️ 只列"App 自己发起 + 只走 https + WebView 从不加载"的主机。
        // 把 WebView 要加载的源站资源（如 im-imgs-bucket 的播放器脚本）列进来 =
        // 网页播放链路第一步就断（线上黑屏事故，见类 KDoc）。
        val mustRejectCleartext = listOf(
            "jrszbt.com",
            // App 自己发起、代码里只写 https、WebView 从不加载的主机（review/04-security.md P1-5）
            "site.api.espn.com", // 比分板 ESPN
            "api.github.com", // 更新检查
            "m.jrkan.com", // JRKAN 入口页
            "www.xixizb.com", // xxzb5 跳转后的 API 主机
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
            "找不到 network_security_config.xml（cwd=${File(TestPaths.workingDirPath()).absolutePath}）—— " +
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
        assertFalse(
            "同一台 OSS 主机绝不能同时出现在禁明文清单里（黑屏事故的根因）：双向钉住，允许=true 与禁止=false 互斥",
            hasDomainConfig(xml!!, host, cleartextPermitted = false),
        )
    }

    @Test
    fun `确实以明文提供服务的主机不能被误伤`() {
        val xml = configText()
        // ⚠️ 硬失败而不是跳过（审计 A10）：这份 xml 是明文策略纪律**唯一**的输入，
        // 解析不到时下面几条断言本来会"零违规"地一起变绿 —— 那是假安全感。
        assertTrue(
            "找不到 network_security_config.xml（cwd=${File(TestPaths.workingDirPath()).absolutePath}）—— " +
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
            "找不到 network_security_config.xml（cwd=${File(TestPaths.workingDirPath()).absolutePath}）—— " +
                "锚点缺失时明文策略守卫会静默全绿，所以直接失败而不是跳过",
            xml != null,
        )
        // ⚠️ 判据锚在 **base-config 开标签本身**（v1.2.26 修正）：原来是对全文
        // `contains("cleartextTrafficPermitted=\"true\"")` —— 任何一个 domain-config
        // 写成 true、甚至注释里留一句（注释现在已被剥掉），都能替 base-config 满足它，
        // 于是"删掉 base-config 许可"这种真正会断播放的改动静默绿。
        val baseOpen = Regex("""<base-config\b[^>]*>""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(xml!!)
        assertTrue(
            "base-config 的明文许可不能删：m3u8 清单主机名是源站运行时混淆生成的，\n" +
                "写死白名单就等于播放器拿不到清单（这是产品硬需求，不是疏忽）。",
            baseOpen != null &&
                Regex("""cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(baseOpen.value),
        )
    }

    // ---------- 守卫自检（审计纪律：判据本体也要被钉住） ----------

    /** 假配置写在注释里 → 剥注释后必须不被看见（审计 A2 的 XML 侧）。 */
    @Test
    fun `注释里的假配置不能骗过判据`() {
        val fake = """
            <network-security-config>
                <!-- <domain-config cleartextTrafficPermitted="false">
                        <domain includeSubdomains="true">jrszbt.com</domain>
                     </domain-config> -->
                <base-config cleartextTrafficPermitted="true"/>
            </network-security-config>
        """.trimIndent()
        assertFalse(
            "禁明文条目只存在于注释里 —— 判据必须看不见它",
            hasDomainConfig(GuardSourceScan.stripXmlComments(fake), "jrszbt.com", cleartextPermitted = false),
        )
        val fakeAllow = """
            <network-security-config>
                <!-- 这里写一段"曾经允许明文"的事故证据：
                     <domain-config cleartextTrafficPermitted="true">
                        <domain includeSubdomains="true">play.sportsteam368.com</domain>
                     </domain-config>
                -->
                <base-config/>
            </network-security-config>
        """.trimIndent()
        val stripped = GuardSourceScan.stripXmlComments(fakeAllow)
        assertFalse(
            "允许明文条目只存在于注释里 —— hasDomainConfig 必须看不见它",
            hasDomainConfig(stripped, "play.sportsteam368.com", cleartextPermitted = true),
        )
        val baseOpen = Regex("""<base-config\b[^>]*>""", setOf(RegexOption.DOT_MATCHES_ALL)).find(stripped)
        assertTrue(
            "base-config 判据同样不能把注释里的属性当真：真标签没写许可时判据必须为 false",
            baseOpen != null &&
                !Regex("""cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(baseOpen.value),
        )
    }

    /** 域匹配必须有点边界：`.jrszbt.com` 式后缀不能覆盖 `eviljrszbt.com`。 */
    @Test
    fun `域匹配必须带点边界`() {
        val xml = """
            <network-security-config>
                <domain-config cleartextTrafficPermitted="false">
                    <domain includeSubdomains="true">jrszbt.com</domain>
                </domain-config>
            </network-security-config>
        """.trimIndent()
        assertTrue("真子域必须被覆盖", hasDomainConfig(xml, "www.jrszbt.com", cleartextPermitted = false))
        assertFalse("同后缀的旁域（eviljrszbt.com）不许被覆盖", hasDomainConfig(xml, "eviljrszbt.com", cleartextPermitted = false))
    }

    /** includeSubdomains="false" 的条目只覆盖该域本身，不覆盖子域。 */
    @Test
    fun `includeSubdomains 为 false 时不覆盖子域`() {
        val xml = """
            <network-security-config>
                <domain-config cleartextTrafficPermitted="false">
                    <domain includeSubdomains="false">jrszbt.com</domain>
                </domain-config>
            </network-security-config>
        """.trimIndent()
        assertTrue("域本身仍被覆盖", hasDomainConfig(xml, "jrszbt.com", cleartextPermitted = false))
        assertFalse("子域不被 includeSubdomains=false 的条目覆盖", hasDomainConfig(xml, "www.jrszbt.com", cleartextPermitted = false))
    }

    /** 属性缺省按 Android minSdk 24 语义视为 false：删属性放松纪律不许变绿。 */
    @Test
    fun `cleartextTrafficPermitted 缺省按 false 处理`() {
        val xml = """
            <network-security-config>
                <domain-config>
                    <domain includeSubdomains="true">jrszbt.com</domain>
                </domain-config>
            </network-security-config>
        """.trimIndent()
        assertFalse(
            "条目删掉 cleartextTrafficPermitted 属性后不能仍被算作「允许明文」（minSdk 24 默认 false）",
            hasDomainConfig(xml, "jrszbt.com", cleartextPermitted = true),
        )
        assertTrue(
            "缺省按禁止明文：把真配置改成无属性（= 收紧），「必须禁止明文」判据仍成立",
            hasDomainConfig(xml, "jrszbt.com", cleartextPermitted = false),
        )
    }

    // ---------- helpers ----------

    /**
     * 读配置**并剥掉全部 XML 注释**（v1.2.26 守卫修正）。
     *
     * ⚠️ 原来三个判据都直接在**原文**上跑正则，而这份文件恰恰以大段注释
     * 记录黑屏事故（`<!-- … cleartextTrafficPermitted="false" … -->` 形态的证据文字
     * 就在文件里）。把真配置挪进注释、或只留注释里的假配置，原文匹配会
     * **跟着注释一起变绿** —— 审计 A2 在 XML 侧的原形。
     * 现在统一走 `stripXmlComments`（保留换行，行号仍与文件对应）。
     */
    private fun configText(): String? = configCandidates().firstOrNull { it.isFile }
        ?.let { GuardSourceScan.stripXmlComments(it.readText()) }

    private fun configCandidates() = listOf(
        File("src/main/res/xml/network_security_config.xml"),
        File("app/src/main/res/xml/network_security_config.xml"),
        File(TestPaths.workingDirPath(), "src/main/res/xml/network_security_config.xml"),
    )

    /**
     * 是否存在对 `host` **生效**的 domain-config，且明文许可为期望值。
     *
     * 判据按 Android `NetworkSecurityConfig` 的真实语义实现（v1.2.26 修正）：
     *  1. 域匹配：条目与 host **全等**，或条目是 host 的真父域且**该条**
     *     `<domain includeSubdomains="true">` —— 原来用 `host.endsWith(tail)`
     *     没有点边界，`.jrszbt.com` 形态的条目会匹配到 `eviljrszbt.com`；
     *     而本仓库配置实际用的是 `includeSubdomains` 属性（`.example.com`
     *     只是书写惯例，Android 并不按前导点开子域）。
     *  2. 明文许可：`cleartextTrafficPermitted` 缺省时，**API 24+ 默认 false**
     *     （Android 文档语义；本应用 minSdk 24）。原来缺省按 true 处理，
     *     恰好把"有人删掉属性想放松纪律"判成合规 —— 方向性错误。
     *  3. 没有任何匹配条目的 host 不在此判定（由 base-config 那条测试单独钉）。
     */
    private fun hasDomainConfig(xml: String, host: String, cleartextPermitted: Boolean): Boolean {
        val blocks = Regex(
            """<domain-config\b([^>]*)>(.*?)</domain-config>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(xml)
        for (b in blocks) {
            val attrs = b.groupValues[1]
            val permitted = Regex("""cleartextTrafficPermitted="(true|false)"""")
                .find(attrs)?.groupValues?.get(1)?.toBoolean() ?: false // minSdk 24：缺省 false
            if (permitted != cleartextPermitted) continue
            val matchesHost = Regex("""<domain([^>]*)>([^<]+)</domain>""")
                .findAll(b.groupValues[2])
                .any { m ->
                    val d = m.groupValues[2].trim().lowercase().removePrefix(".")
                    val sub = Regex("""includeSubdomains\s*=\s*"true"""").containsMatchIn(m.groupValues[1])
                    d == host || (sub && host.endsWith(".$d"))
                }
            if (matchesHost) return true
        }
        return false
    }
}