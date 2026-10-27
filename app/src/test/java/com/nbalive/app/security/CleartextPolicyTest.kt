package com.nbalive.app.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 明文流量策略的**精确收紧**（review/04-security.md P1-5）。
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
 * App 自己发起的、**只走 https** 的请求不该被明文许可兜着 ——
 * 一旦哪天被降级/中间人改写，没有任何策略层会拒绝。
 * 这些主机名是写死在代码里的常量（不需要运行时生成），可以精确收紧：
 *
 *  - `jrszbt.com`：JRS 列表/详情页，代码里全部是 `https://`
 *  - `im-imgs-bucket.oss-accelerate.aliyuncs.com`：JRKAN 列表 js，`https://`
 *
 * ⚠️ 绝不要把 `play.sportsteam*.com` 或 `*.jrszb444.com` 收进
 * "禁明文"清单：它们**就是**明文提供服务的，收了等于播放全挂。
 * 这条边界是本测试的重点 —— 收紧过头比不收更糟。
 */
class CleartextPolicyTest {

    @Test
    fun `App 自己只走 https 的主机必须禁止明文`() {
        val xml = configText()
        assumeTrue("找不到 network_security_config.xml，跳过", xml != null)

        val mustRejectCleartext = listOf(
            "jrszbt.com",
            "im-imgs-bucket.oss-accelerate.aliyuncs.com",
        )
        val missing = mustRejectCleartext.filterNot { host ->
            hasDomainConfig(xml!!, host, cleartextPermitted = false)
        }
        assertTrue(
            "以下主机在代码里只以 https 访问，却仍被全局明文许可兜着 —— " +
                "一旦被降级/改写，策略层不会拒绝：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `确实以明文提供服务的主机不能被误伤`() {
        val xml = configText()
        assumeTrue("找不到 network_security_config.xml，跳过", xml != null)

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
        assumeTrue("找不到 network_security_config.xml，跳过", xml != null)
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