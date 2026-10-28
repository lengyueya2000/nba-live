package com.nbalive.app.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 的本地文件访问必须显式关闭（review/04-security.md P2-3）。
 *
 * ## 要解决什么
 *
 * `WebSettings.allowFileAccess` / `allowContentAccess` 在 **API 24~29 上默认是 true**。
 * 播放页里跑的是源站（第三方、明文 http）的 JS，默认值意味着它能
 * 通过 `file://` / `content://` 做**本地文件存在性探测**：
 * 探测结果可以用来做设备指纹、判断装了哪些 App、读可公开读取的文件。
 *
 * 这个 App 的播放页完全不需要读本地文件 —— 关掉零成本。
 *
 * ## 为什么扫源码而不是运行时断言
 *
 * `WebSettings` 是 Android 类，JVM 单测里拿不到真实实例（只有 stub）。
 * 源码扫描是此处唯一能自动化、且在引入回归的那一轮就变红的方式。
 */
class WebViewFileAccessTest {

    @Test
    fun `播放页 WebView 必须显式关闭本地文件访问`() {
        val text = playerScreenText()
        // ⚠️ 硬失败而不是跳过（审计 A10）：PlayerScreen.kt 是这条守卫**唯一**的锚点，
        // 锚点缺失时它本来会"零违规"地静默全绿 —— 那种绿和"真的守住了"看不出区别。
        assertTrue(
            "找不到 PlayerScreen.kt（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时这条守卫会静默全绿，所以直接失败而不是跳过",
            text != null,
        )
        // ⚠️ 必须先剔除注释再断言：注释里也会出现 "allowContentAccess = false"
        // 这个字面量（说明"为什么这么写"），不剔除的话守卫会永远全绿。
        val code = stripComments(text!!)

        assertTrue(
            "必须显式设置 allowFileAccess = false：API 24~29 默认 true，" +
                "源站第三方 JS 可借 file:// 做本地文件存在性探测（04-P2-3）",
            code.contains("allowFileAccess = false"),
        )
        assertTrue(
            "必须显式设置 allowContentAccess = false（同上，content:// 形态）",
            code.contains("allowContentAccess = false"),
        )
    }

    /** 去掉行注释与块注释，只留代码。 */
    private fun stripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")

    @Test
    fun `JS 桥必须不存在`() {
        // 04-P1-3 的风险边界：没有 addJavascriptInterface 时，
        // 恶意 JS 拿不到原生桥，注入面被限制在 WebView 沙箱内。
        // 这条守住"以后别为了某个功能顺手加上去"。
        val text = playerScreenText()
        // ⚠️ 硬失败而不是跳过（审计 A10）：PlayerScreen.kt 是这条守卫**唯一**的锚点，
        // 锚点缺失时它本来会"零违规"地静默全绿 —— 那种绿和"真的守住了"看不出区别。
        assertTrue(
            "找不到 PlayerScreen.kt（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时这条守卫会静默全绿，所以直接失败而不是跳过",
            text != null,
        )
        assertTrue(
            "WebView 绝不能挂 addJavascriptInterface：源站页面是第三方、明文 http，" +
                "挂上桥等于把原生能力交给它",
            !text!!.contains("addJavascriptInterface"),
        )
    }

    private fun playerScreenText(): String? {
        val candidates = listOf(
            File("src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt"),
            File("app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}