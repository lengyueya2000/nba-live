package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 里允许哪些导航（review/01-P2「广告把播放页导航走」与 04-P1-3/4 合并）。
 *
 * ## 要挡的是什么
 *
 * 旧代码只挡非 http(s)（`intent://` / `market://`），于是
 * **所有 http/https 导航都放行**。源站播放页里混着的广告脚本做
 * `location.href = "https://广告页"` 时，主框架被导航走：
 *  - 源站播放器随页面销毁，画面变黑或变成广告页；
 *  - **不触发** `onPlayerError`（那不是 ExoPlayer）；
 *  - 也**不触发** `onReceivedError`（导航本身是成功的）；
 *  - 连「疑似无信号」的嗅探计时器也可能不报 —— 用户面对一个
 *    挂着广告、点不动、而 App 认为「还在播」的播放页。
 *
 * 这是判死逻辑之外的第三条**静默失败**路径，所以必须有显式判据。
 *
 * ## 为什么不能一刀切成「只许同源」
 *
 * 源站播放页自己就会跨域跳转（实测链路）：
 * `www.jrszbt.com/live/555.html` -> `play.sportsteam368.com/...`
 * -> 内层 `*.com/play/sm.html`。按严格同源挡，正常播放链路第一步就走不通。
 *
 * 所以这里的策略是**注册域白名单**：允许源站及其已知播放镜像域，
 * 其余 http(s) 一律拦掉。新增域要显式加进白名单 ——
 * 把"默认允许一切"换成"默认拒绝"。
 */
class WebNavigationPolicyTest {

    private val sourceHost = "www.jrszbt.com"

    @Test
    fun `非 http 协议一律拦截`() {
        listOf(
            "intent://#Intent;",
            "market://details?id=x",
            "weixin://dl/business",
            "tel:10086",
        ).forEach {
            assertTrue("$it 必须拦掉，否则会拉起商店/其它 App", WebNavigationPolicy.shouldBlock(it, sourceHost))
        }
    }

    @Test
    fun `源站与已知播放镜像域放行`() {
        // 实测的正常链路：列表页 -> 播放页 -> 内层页，跨的是自己的镜像域
        listOf(
            "https://www.jrszbt.com/live/555.html",
            "https://jrszbt.com/live/555.html",
            "https://play.sportsteam368.com/play/steam1.html",
            "http://www.jrkan.com/index.js",
            "https://m.jrkan.com/live/1.html",
            "http://play.sportsteam356.com/play/steam9.html",
        ).forEach {
            assertFalse(
                "$it 是源站自身/镜像，必须放行，否则正常播放链路第一步就走不通",
                WebNavigationPolicy.shouldBlock(it, sourceHost),
            )
        }
    }

    @Test
    fun `广告与钓鱼域默认拒绝`() {
        listOf(
            "https://ads.example.com/landing?from=jrs",
            "https://vip-999-bet.click/live",
            "https://www.taobao.com/",
            "http://43.135.8.77/index.php",
        ).forEach {
            assertTrue("$it 不在白名单，必须拦下（旧代码在这里是放行的）", WebNavigationPolicy.shouldBlock(it, sourceHost))
        }
    }

    @Test
    fun `同源地址永远放行`() {
        assertFalse(WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/other/path", sourceHost))
    }

    @Test
    fun `拿不到当前页面 host 时保守放行`() {
        // 第一次导航前 currentUrl 可能是空或 about:blank，这时无从判断"从哪来"。
        // 保守放行，避免把正常播放页挡在门外。
        assertFalse(WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/live/1.html", null))
        assertFalse(WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/live/1.html", "about:blank"))
        assertFalse(WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/live/1.html", ""))
        // 但目标本身是广告域时仍拦：首跳的 target 一定来自我们自己 loadUrl，
        // 白名单外的 target 说明是页面自己跳出去的
        assertTrue(WebNavigationPolicy.shouldBlock("https://ad.example.com/x", null))
    }

    @Test
    fun `畸形地址拦截而不是抛异常`() {
        listOf("http://", "://x", "   ", "", "http:///x").forEach {
            assertTrue("$it 不能崩、也不能放行", WebNavigationPolicy.shouldBlock(it, sourceHost))
        }
    }

    @Test
    fun `白名单匹配注册域而不是子串`() {
        // ⚠️ 经典绕过：把白名单域塞进自己域名的后半段。
        // 用 contains / endsWith("jrszbt.com") 的实现会放行
        // "https://jrszbt.com.attacker.net/"，白名单就成了摆设。
        listOf(
            "https://jrszbt.com.attacker.net/",
            "https://notjrszbt.com/",
            "https://jrkan.com.attacker.net/x",
            "https://attacker.net/?redirect=https://www.jrszbt.com/",
        ).forEach {
            assertTrue("$it 不能因为字符串里含有白名单域就放行", WebNavigationPolicy.shouldBlock(it, sourceHost))
        }
    }

    @Test
    fun `拦截时给出可显示的原因而不是静默`() {
        // 拦截不能只 return true 把用户丢在原地：画面突然定住、毫无解释，
        // 和原来的静默失败没有本质区别。
        val reason = WebNavigationPolicy.blockReason("https://ad.example.com/x?y=1", sourceHost)
        assertTrue("拦下的导航必须有原因文本，实际: $reason", reason.isNotBlank())
        assertTrue("原因里应含目标域名便于排障，实际: $reason", reason.contains("ad.example.com"))
        // 放行时没有原因
        assertEquals("", WebNavigationPolicy.blockReason("https://jrszbt.com/live/1.html", sourceHost))
        // 原因文本不能带 query：query 里可能是源站的签名凭证
        assertFalse("原因文本不能泄漏 query，实际: $reason", reason.contains("y=1"))
    }
}
