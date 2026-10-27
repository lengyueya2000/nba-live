package com.nbalive.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL 脱敏。
 *
 * 源站把播放凭证（auth_key / sign / token）放在 m3u8 的 query 里。
 * 那个签名就是"谁能播这条流"的凭证，release 包写进 logcat 等于公开它
 * （adb logcat 不需要任何权限）。
 */
class RedactUrlTest {

    @Test
    fun `query 被抹掉 签名不外泄`() {
        val url = "https://cdn.example.com/live/abc.m3u8?auth_key=secret123&sign=xyz"
        val out = redactUrl(url)
        assertFalse(
            "签名绝不能出现在日志里，实际: $out",
            out.contains("secret123") || out.contains("xyz"),
        )
        assertTrue("应保留 host 便于排障", out.contains("cdn.example.com"))
        assertTrue("应保留 path 便于排障", out.contains("/live/abc.m3u8"))
    }

    @Test
    fun `fragment 也被抹掉`() {
        val out = redactUrl("https://cdn.example.com/a.m3u8#token=abc")
        assertFalse("fragment 里也可能有凭证，实际: $out", out.contains("abc"))
    }

    @Test
    fun `没有 query 的地址原样保留`() {
        val url = "https://jrszbt.com/live/555.html"
        assertEquals(url, redactUrl(url))
    }

    @Test
    fun `非法地址不炸`() {
        assertEquals("<invalid-url>", redactUrl("not a url"))
        assertEquals("<invalid-url>", redactUrl(""))
    }

    @Test
    fun `http 明文地址也能脱敏`() {
        val out = redactUrl("http://play.sportsteam356.com/play/steam123.html?key=abc")
        assertFalse(out.contains("abc"))
        assertTrue(out.contains("play.sportsteam356.com"))
    }

    // ---------- redactText：混合文本里的 URL 也要脱敏 ----------
    // review/02-P1-5：异常 message 往往是「前缀 + 完整 URL」而不是纯 URL，
    // redactUrl 要求以 scheme 开头，这种文本会整段漏网。

    @Test
    fun `文本中间夹的 URL 也被脱敏`() {
        val out = redactText("Failed to connect to https://cdn.example.com/live/a.m3u8?auth_key=SECRET")
        assertFalse("签名绝不能出现在日志里，实际: $out", out.contains("SECRET"))
        assertTrue("应保留 host 便于排障", out.contains("cdn.example.com/live/a.m3u8"))
        assertTrue("应保留异常前缀便于排障", out.startsWith("Failed to connect to"))
    }

    @Test
    fun `一段文本里的多个 URL 全部脱敏`() {
        val out = redactText(
            "redirect https://a.example.com/x?token=A1 -> http://b.example.com/y?sign=B2"
        )
        assertFalse(out.contains("A1"))
        assertFalse(out.contains("B2"))
        assertTrue(out.contains("a.example.com/x"))
        assertTrue(out.contains("b.example.com/y"))
    }

    @Test
    fun `URL 后的标点不会被吞进 query`() {
        // 异常 message 常见形态："...url (context)"：右括号不是 URL 字符，
        // 若被吞进 query 就会连带把后面的文本一起抹掉，丢失排障线索。
        val out = redactText("see https://a.example.com/p?k=1 (retry 3)")
        assertFalse(out.contains("k=1"))
        assertTrue("括号后的文本应保留，实际: $out", out.contains("(retry 3)"))
    }

    @Test
    fun `不含 URL 的文本原样保留`() {
        val text = "no business here"
        assertEquals(text, redactText(text))
        assertEquals("", redactText(null))
    }

    @Test
    fun `源站原始 href 里的签名也不外泄`() {
        // review/04-P1-2：JrsParser 曾把原始 href 直接打进 Log.w
        val out = redactText("跳过：/live/555.html?auth_key=ABC123")
        assertFalse("实际: $out", out.contains("ABC123"))
        assertTrue(out.contains("/live/555.html"))
    }
}
