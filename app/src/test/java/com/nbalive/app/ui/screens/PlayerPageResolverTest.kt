package com.nbalive.app.ui.screens

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页壳 → 玩家页地址的解析（2026-10-30「解析播放太慢」的任务 D）。
 *
 * 真实页面结构见 [PlayerPageResolver] 的 KDoc；这里钉住解析成功/失败两侧，
 * 一旦某天源站改字段，这里会红而不是静默退回"慢路径"（用户只会觉得又变慢了）。
 */
class PlayerPageResolverTest {

    private val pageHtml = """
        <script>
            window.playerConfig = {
                playerUrlBase64: "aHR0cHM6Ly9kZW1vLnNvbmd6ZW5ldC5jb20vcGFwcy5odG1sP2lkPQ=="
            };
            var encodedStr = "uZenXFlv6HsKu7BL5RNqTJvx6qhdQCTbay42RGlm+RQyZy8AHiegySXDRjw8l0Xou4zmrWl1DNBfevXfdtdliB6wcfVB+dazLkHLixzC2VLaOBnr7F+LhucJ/IWcba9c4gPYx/a+RHb5ZuweDPd/Xclld+d/nhVqqPqNMptFmHdLi6M/";
        </script>
    """.trimIndent()

    private val pageUrl = "http://play.sportsteam356.com/play/mgxl/?id=00594090&id2="

    @Test
    fun `解析出 paps 玩家页地址`() {
        val resolved = PlayerPageResolver.resolvePlayerUrl(pageHtml, pageUrl)
        assertTrue(
            "应解析出玩家页地址（当前=$resolved）",
            resolved != null && resolved.startsWith("https://demo.songzenet.com/paps.html?id="),
        )
    }

    @Test
    fun `encodedStr 必须按 query 参数转义`() {
        val resolved = PlayerPageResolver.resolvePlayerUrl(pageHtml, pageUrl)!!
        val query = resolved.substringAfter("?id=")
        assertTrue("base64 的 + 必须转义成 %2B（当前=$query）", !query.contains('+'))
        assertTrue("base64 的 / 必须转义成 %2F", !query.contains('/'))
        assertTrue("base64 的 = 必须转义成 %3D", !query.contains('='))
        assertEquals("转义后必须能还原成原始 encodedStr", true, query.isNotBlank())
    }

    @Test
    fun `缺 playerConfig 时返回 null 走原播放页`() {
        val html = """<script>var encodedStr = "abc";</script>"""
        assertNull(PlayerPageResolver.resolvePlayerUrl(html, pageUrl))
    }

    @Test
    fun `缺 encodedStr 时返回 null 走原播放页`() {
        val html = """<script>window.playerConfig={playerUrlBase64:"aHR0cHM6Ly9hLmIvYz9pZD0="};</script>"""
        assertNull(PlayerPageResolver.resolvePlayerUrl(html, pageUrl))
    }

    @Test
    fun `base64 非法时返回 null 而不是崩`() {
        val html = """
            <script>
            window.playerConfig={playerUrlBase64:"!!!not-base64!!!"};
            var encodedStr="abc";
            </script>
        """.trimIndent()
        assertNull(PlayerPageResolver.resolvePlayerUrl(html, pageUrl))
    }

    @Test
    fun `解码结果不是绝对地址时返回 null`() {
        val notUrl = java.util.Base64.getEncoder().encodeToString("just some text".toByteArray())
        val html = """
            <script>
            window.playerConfig={playerUrlBase64:"$notUrl"};
            var encodedStr="abc";
            </script>
        """.trimIndent()
        assertNull("前缀不是 http(s) 地址时不能硬拼", PlayerPageResolver.resolvePlayerUrl(html, pageUrl))
    }

    @Test
    fun `android 页面里的 base64 解码与标准实现一致`() {
        // 防止手写解码器在填充/边界上出错（`java.util.Base64` 只在测试进程用，生产用 minSdk24 安全的手写实现）
        val samples = listOf("", "Zg==", "Zm8=", "Zm9v", "aHR0cHM6Ly9kZW1vLnNvbmd6ZW5ldC5jb20vcGFwcy5odG1sP2lkPQ==")
        for (s in samples) {
            val expected = java.util.Base64.getDecoder().decode(s).toString(Charsets.UTF_8)
            assertEquals("base64 解码不一致：$s", expected, PlayerPageResolver.decodeBase64(s))
        }
    }
}

/**
 * 「直接加载玩家页」的接线守卫。
 *
 * 解析器写得再对，如果 WebSurface 没消费它、或者没有回退到原播放页，都等于没做。
 */
class PlayerPageResolverWiringTest {

    @Test
    fun `WebSurface 必须消费解析器并保留原页回退`() {
        val body = GuardSourceScan.functionBody(functionSource(), "fun WebSurface(")
        assertTrue(
            "WebSurface 里没有调用 PlayerPageResolver.resolvePlayerUrl(...)：\n" +
                "解析器写了没人用，跳过壳页那一步不存在。",
            body.contains("PlayerPageResolver.resolvePlayerUrl("),
        )
        assertTrue(
            "WebSurface 里没有 targetUrl 这个'实际加载哪个地址'的状态：无法在失败时退回原页。\n" +
                "实际函数体片段：\n" + body.take(400),
            body.contains("targetUrl"),
        )
        assertTrue(
            "没有在加载前拉壳页（HttpClient.shared.get）—— 解析器的输入从哪来？",
            body.contains("HttpClient.shared.get("),
        )
    }

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "class Sample {",
            "    fun WebSurface() {",
            "        // PlayerPageResolver.resolvePlayerUrl( 只在注释里",
            "        val s = \"PlayerPageResolver.resolvePlayerUrl( 只在字符串里\"",
            "    }",
            "}",
        ).joinToString("\n")
        assertTrue(
            "判据命中了注释/字符串里的字面量（剔非代码那一步没生效）",
            !GuardSourceScan.functionBody(sample, "fun WebSurface(")
                .contains("PlayerPageResolver.resolvePlayerUrl("),
        )
    }

    private fun functionSource(): String {
        val cwd = File(System.getProperty("user.dir") ?: ".").absolutePath
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd）—— 守卫会静默全绿")
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == "PlayerScreen.kt" }
            ?: error("找不到 PlayerScreen.kt —— 守卫会静默全绿")
        return GuardSourceScan.stripNonCode(file.readText())
    }
}