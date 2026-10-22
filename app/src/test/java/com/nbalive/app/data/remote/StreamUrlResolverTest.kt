package com.nbalive.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 域名反混淆还原与候选地址测试。
 *
 * 已验证：hdl2.gnimixuiq.com -> hdl2.qiuximing.com（DNS 可解析，m3u8 返回 200）。
 */
class StreamUrlResolverTest {

    private val resolver = StreamUrlResolver()

    @Test
    fun `reverses second to last label`() {
        val result = resolver.restoreObfuscatedHost("http://hdl2.gnimixuiq.com/live/72212869.m3u8?auth_key=abc")
        assertEquals("http://hdl2.qiuximing.com/live/72212869.m3u8?auth_key=abc", result)
    }

    @Test
    fun `keeps port and path intact`() {
        val result = resolver.restoreObfuscatedHost("//hdl2.gnimixuiq.com:8080/live/a.m3u8")
        assertEquals("http://hdl2.qiuximing.com:8080/live/a.m3u8", result)
    }

    @Test
    fun `returns null for non http url`() {
        assertNull(resolver.restoreObfuscatedHost("ftp://example.com/a.m3u8"))
    }

    // ---- 回归：反混淆是有损的，不能当唯一答案 ----

    @Test
    fun `resolve offers both obfuscated and original host as candidates`() = runBlocking {
        // 源站目前是混淆的 -> 反混淆版应当排在第一个
        val html = """<iframe src="//demo.songzenet.com/msss.html?id=${encode("//hdl2.gnimixuiq.com/live/a.m3u8")}"></iframe>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }

        assertNotNull(resolved)
        val candidates = resolved!!.m3u8Candidates
        assertEquals(
            "反混淆版应当优先",
            "http://hdl2.qiuximing.com/live/a.m3u8",
            candidates.first(),
        )
        assertTrue(
            "原文版也必须在候选里：源站哪天取消混淆时只有它是对的",
            candidates.contains("http://hdl2.gnimixuiq.com/live/a.m3u8"),
        )
    }

    @Test
    fun `resolve keeps the original host when the source stops obfuscating`() = runBlocking {
        // 源站不再混淆：反混淆会得到乱码域名，但原文版必须还在候选里
        val html = """<iframe src="//demo.songzenet.com/msss.html?id=${encode("//hls.example.com/live/a.m3u8")}"></iframe>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }

        assertNotNull(resolved)
        val candidates = resolved!!.m3u8Candidates
        assertTrue(
            "未混淆的原始地址必须保留，否则整条直连线路会被判死",
            candidates.contains("http://hls.example.com/live/a.m3u8"),
        )
    }

    @Test
    fun `m3u8 payload is not truncated at a bare ampersand`() = runBlocking {
        // payload 里的 & 属于 m3u8 自身的 query（auth_key 等），不能被截断
        val payload = "//hdl2.gnimixuiq.com/live/a.m3u8?auth_key=abc&t=123"
        val html = """<iframe src="//demo.songzenet.com/msss.html?id=$payload&id2="></iframe>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }

        assertNotNull(resolved)
        assertTrue(
            "query 参数被截断了：${resolved!!.m3u8Candidates}",
            resolved.m3u8Candidates.any { it.contains("auth_key=abc&t=123") },
        )
    }

    @Test
    fun `payload is url decoded before host restoration`() = runBlocking {
        // 源站给的是 encodeURIComponent 之后的 payload，解码后才有可反混淆的主域名
        val html = """<iframe src="//demo.songzenet.com/msss.html?id=${encode("//hdl2.gnimixuiq.com/live/a.m3u8")}"></iframe>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }
        assertNotNull(resolved)
        assertEquals("http://hdl2.qiuximing.com/live/a.m3u8", resolved!!.m3u8Candidates.first())
    }

    /** 等价于 JS 的 encodeURIComponent */
    private fun encode(raw: String): String = buildString {
        for (ch in raw) {
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> append(ch)
                ch in "-_.!~*'()" -> append(ch)
                else -> append(ch.toString().toByteArray(Charsets.UTF_8).joinToString("") {
                    "%" + it.toInt().toString(16).uppercase().padStart(2, '0')
                })
            }
        }
    }
}
