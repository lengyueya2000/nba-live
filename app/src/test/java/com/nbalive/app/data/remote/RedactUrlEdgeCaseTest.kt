package com.nbalive.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `redactUrl` 的两类遗漏（review/02-data.md P2-9）。
 *
 * ## 现状
 *
 * [RedactUrlTest] 覆盖了 query/fragment/非法地址等 5 类情形，但原实现有两个洞：
 *
 * 1. **userinfo 不脱敏**：`https://user:pass@host/path` 里的口令原样保留。
 *    源站备用地址里偶尔带这种形态（`.forensics/detail_364127.html` 里就有
 *    指向第三方推广页的 `https://.../?agent=j&app=jrs` 一类链接）。
 * 2. **无 query 时整串原样返回**：`https://cdn/x/live/a.m3u8` 这种"签名在 path 里"
 *    的形态（部分 CDN 用 `/sign/hash/path` 形式）会完整进日志。
 *    原注释承认"path 可能带签名但选择保留" —— 保留 path 对排障有价值，
 *    但**无限长**的 path 既没有排障价值、又可能夹带凭证，应当截断。
 */
class RedactUrlEdgeCaseTest {

    @Test
    fun `userinfo 里的口令必须抹掉`() {
        val out = redactUrl("https://user:secret@cdn.example.com/live/a.m3u8")
        assertFalse("口令不能进日志：$out", out.contains("secret"))
        assertFalse("用户名也不必保留：$out", out.contains("user:"))
        assertTrue("host 与 path 要保留（排障需要）：$out", out.contains("cdn.example.com"))
    }

    @Test
    fun `userinfo 与 query 同时存在时两者都抹`() {
        val out = redactUrl("https://u:p@cdn.example.com/live/a.m3u8?auth_key=abc&t=1")
        assertFalse("口令：$out", out.contains(":p@"))
        assertFalse("签名：$out", out.contains("abc"))
        assertTrue(out.contains("cdn.example.com"))
    }

    @Test
    fun `超长 path 要截断 而不是整串进日志`() {
        val longPath = "https://cdn.example.com/live/" + "x".repeat(400) + "/a.m3u8"
        val out = redactUrl(longPath)
        assertTrue(
            "超长 path 必须截断（原实现无 query 时原样返回整串）：实际长度 ${out.length}",
            out.length < longPath.length,
        )
        assertTrue("必须留可辨认的截断标记：$out", out.contains("…") || out.contains("..."))
    }

    @Test
    fun `正常长度的 path 不受影响`() {
        // 不能因为截断把排障信息全砍了：常见 path 要原样保留
        val url = "https://cdn.example.com/live/abc.m3u8"
        assertTrue(
            "正常 path 应完整保留：${redactUrl(url)}",
            redactUrl(url).contains("/live/abc.m3u8"),
        )
    }

    @Test
    fun `非法地址仍返回占位符`() {
        assertTrue(redactUrl("not a url").contains("invalid"))
    }
}