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
        // ⚠️ 断言必须**精确相等**，不能用 contains：
        // 早先这里写的是 contains("auth_key=abc&t=123")，而带 bug 的结果
        // "...&t=123&id2=" 恰好也满足 contains —— 测试写了却没测住，
        // 让"&id2= 被吞进 payload"这个 bug 一直潜伏。
        assertTrue(
            "query 参数被截断了：${resolved!!.m3u8Candidates}",
            resolved.m3u8Candidates.any {
                it == "http://hdl2.qiuximing.com/live/a.m3u8?auth_key=abc&t=123"
            },
        )
    }

    @Test
    fun `trailing id2 is stripped from payload`() = runBlocking {
        // 源站播放页的形态是 msss.html?id=<payload>&id2=
        // 捕获组必须停在 payload 末尾，不能把 &id2= 一起吃掉
        val html = """<iframe src="//demo.songzenet.com/msss.html?id=ENC&id2="></iframe>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }

        // "ENC" 不是合法 http 地址，两个候选都还原不出来 -> 应返回 null
        // 关键是不能出现 "ENC&id2=" 这种被污染的中间结果
        assertNull("&id2= 被吞进了 payload：${resolved?.m3u8Candidates}", resolved)
    }

    @Test
    fun `id2 is not carried into any candidate url`() = runBlocking {
        // 端到端：任何候选地址里都不该出现 "id2="
        // 它属于源站播放页的第二个参数，不属于 m3u8 地址本身
        val payload = "//hdl2.gnimixuiq.com/live/a.m3u8?auth_key=abc"
        val html = """<iframe src="//demo.songzenet.com/msss.html?id=$payload&id2="></iframe>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }

        assertNotNull(resolved)
        val polluted = resolved!!.m3u8Candidates.filter { it.contains("id2=") }
        assertTrue(
            "候选地址被 &id2= 污染了：$polluted（全部候选：${resolved.m3u8Candidates}）",
            polluted.isEmpty(),
        )
        assertEquals(
            "反混淆后的地址应当是第一个候选且完全干净",
            "http://hdl2.qiuximing.com/live/a.m3u8?auth_key=abc",
            resolved.m3u8Candidates.first(),
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

    // ---- 回归：兜底正则不能把 ".m3u8" 后面的非分隔字符一起吞掉 ----

    @Test
    fun `fallback regex does not produce garbage candidates`() = runBlocking {
        // 页面里出现 "xxx.m3u8path=1" 这种字面量时，旧的 `\.m3u8[^\s"'<>]*`
        // 会产出 "http://cdn.example.com/a.m3u8path=1" 这种垃圾地址，
        // 虽然调用方会逐个探测校验掉，但每次都白费一次请求。
        val html = """<script>var u="http://cdn.example.com/a.m3u8path=1";</script>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }
        assertNull("没有真正可用的 m3u8 时应返回 null，而不是垃圾候选", resolved)
    }

    @Test
    fun `fallback regex keeps query string of a real m3u8`() = runBlocking {
        // 真正的 m3u8 后面常跟 ?auth_key=...，不能被上面的收紧误伤
        val html = """<script>var u="http://cdn.example.com/live/a.m3u8?auth_key=xyz";</script>"""
        val resolved = resolver.resolve("http://play.example.com/play/sm.html?id=1") { html }
        assertNotNull(resolved)
        assertTrue(
            "带 query 的真实 m3u8 必须保留: ${resolved!!.m3u8Candidates}",
            resolved.m3u8Candidates.any { it == "http://cdn.example.com/live/a.m3u8?auth_key=xyz" },
        )
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
