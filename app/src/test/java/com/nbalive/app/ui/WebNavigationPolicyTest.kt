package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
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
 *
 * ## B10：只留一个入口（review/audit-guards.md B10）
 *
 * 原来还有一个 2 参重载 `shouldBlock(url, currentHostPageUrl)`，
 * 自称"只给测试和已知主框架的场景用"—— 而 3 参版本的 `isMainFrame`
 * 本来就有默认值 `true`，两者完全等价。多余的入口只会让人以为
 * 生产用的是"另一个更简洁的判据"。已删除：本文件所有调用点**显式**写
 * `isMainFrame = true`，语义不变，且能看出自己在断言哪条路径。
 * 反向守卫见本文件末尾的「shouldBlock 不许再出现 2 参重载」。
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
            assertTrue(
                "$it 必须拦掉，否则会拉起商店/其它 App",
                WebNavigationPolicy.shouldBlock(it, sourceHost, isMainFrame = true),
            )
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
                WebNavigationPolicy.shouldBlock(it, sourceHost, isMainFrame = true),
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
            assertTrue(
                "$it 不在白名单，必须拦下（旧代码在这里是放行的）",
                WebNavigationPolicy.shouldBlock(it, sourceHost, isMainFrame = true),
            )
        }
    }

    @Test
    fun `同源地址永远放行`() {
        assertFalse(
            WebNavigationPolicy.shouldBlock(
                "https://www.jrszbt.com/other/path",
                sourceHost,
                isMainFrame = true,
            ),
        )
    }

    @Test
    fun `拿不到当前页面 host 时保守放行`() {
        // 第一次导航前 currentUrl 可能是空或 about:blank，这时无从判断"从哪来"。
        // 保守放行，避免把正常播放页挡在门外。
        assertFalse(
            WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/live/1.html", null, isMainFrame = true),
        )
        assertFalse(
            WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/live/1.html", "about:blank", isMainFrame = true),
        )
        assertFalse(
            WebNavigationPolicy.shouldBlock("https://www.jrszbt.com/live/1.html", "", isMainFrame = true),
        )
        // 但目标本身是广告域时仍拦：首跳的 target 一定来自我们自己 loadUrl，
        // 白名单外的 target 说明是页面自己跳出去的
        assertTrue(
            WebNavigationPolicy.shouldBlock("https://ad.example.com/x", null, isMainFrame = true),
        )
    }

    @Test
    fun `畸形地址拦截而不是抛异常`() {
        listOf("http://", "://x", "   ", "", "http:///x").forEach {
            assertTrue(
                "$it 不能崩、也不能放行",
                WebNavigationPolicy.shouldBlock(it, sourceHost, isMainFrame = true),
            )
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
            assertTrue(
                "$it 不能因为字符串里含有白名单域就放行",
                WebNavigationPolicy.shouldBlock(it, sourceHost, isMainFrame = true),
            )
        }
    }

    @Test
    fun `非主框架一律放行`() {
        // 内层播放器整条链路都靠 iframe 承载：若对非主框架也拦，播放器会被自己的守卫挡死。
        // 这个显式调用点也是 2 参重载被删后的替代写法（review/audit-guards.md B10）。
        assertFalse(
            WebNavigationPolicy.shouldBlock("https://ad.example.com/x", sourceHost, isMainFrame = false),
        )
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

    // ---------- B10：判据只有一个入口，不许再出现 2 参重载 ----------

    @Test
    fun `shouldBlock 不许再出现 2 参重载`() {
        val declarations = shouldBlockDeclarations(webNavigationPolicyCode())
        assertTrue(
            "WebNavigationPolicy 里找不到 shouldBlock 的声明 —— 这条守卫会静默全绿（扫描范围错了）",
            declarations.isNotEmpty(),
        )
        val redundant = declarations.filterNot { it.contains("isMainFrame") }
        assertTrue(
            "又出现了 2 参 shouldBlock 重载：$redundant —— 3 参版本已有默认值 " +
                "`isMainFrame = true`，多一个入口只是给同一条判据造出「第二个入口」的假象" +
                "（review/audit-guards.md B10 已删过一次）。测试请显式写 isMainFrame = true。",
            redundant.isEmpty(),
        )
    }

    @Test
    fun `重载判据自检 能认出 2 参声明且不误报 3 参与调用点`() {
        val twoArg = "fun shouldBlock(url: String?, currentHostPageUrl: String?): Boolean = true"
        assertTrue(
            "自检抓不到 2 参重载声明（判据已退化成摆设）",
            shouldBlockDeclarations(twoArg).any { !it.contains("isMainFrame") },
        )
        val threeArg =
            "fun shouldBlock(url: String?, currentUrl: String?, isMainFrame: Boolean = true): Boolean = true"
        assertTrue(
            "自检把 3 参声明误判成冗余重载",
            shouldBlockDeclarations(threeArg).all { it.contains("isMainFrame") },
        )
        assertTrue(
            "自检把**调用点**当成了声明（剔非代码/只找声明那一步没生效）",
            shouldBlockDeclarations("val blocked = WebNavigationPolicy.shouldBlock(a, b, isMainFrame = true)")
                .isEmpty(),
        )
    }

    // ---------------------------------------------------------------

    /** [rawText] 里 `fun shouldBlock(...)` 声明的形参表（已剔注释与字符串）。 */
    private fun shouldBlockDeclarations(rawText: String): List<String> =
        Regex("""fun\s+shouldBlock\(([^)]*)\)""")
            .findAll(GuardSourceScan.stripNonCode(rawText))
            .map { it.groupValues[1].trim() }
            .toList()

    private fun webNavigationPolicyCode(): String = productionCode()["WebNavigationPolicy.kt"]
        ?: error(
            "找不到 WebNavigationPolicy.kt（cwd=${cwd()}）—— 重载守卫会静默全绿，所以直接失败",
        )

    /** 生产源码：文件名 → 已剔除注释与字符串的代码。 */
    private fun productionCode(): Map<String, String> {
        val root = productionRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to GuardSourceScan.stripNonCode(it.readText()) }
    }

    private fun productionRoot(): File =
        listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=${cwd()}）—— 重载守卫会静默全绿")

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath
}