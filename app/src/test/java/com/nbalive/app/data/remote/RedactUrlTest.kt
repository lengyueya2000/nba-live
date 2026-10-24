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
}
