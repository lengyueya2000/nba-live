package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「嗅探成功的地址复用」缓存（2026-10-30「解析播放太慢」）。
 *
 * 规则见 [SniffedPlaylistCache]；这里钉住有效期语义与失效路径 ——
 * 有效期算错会让死地址活太久（反复 404），或者把还能播的地址当过期
 * （等于没优化）。
 */
class SniffedPlaylistCacheTest {

    private val now = 1_790_000_000_000L // 2026 年的某个墙钟时刻（毫秒）

    private fun urlWithExpire(expire: Long, extra: String = ""): String =
        "http://cdn.example.com/live/a.m3u8?expire=$expire&sign=deadbeef$extra"

    private fun cache() = SniffedPlaylistCache()

    @Test
    fun `没有 expire 的地址按默认 TTL 有效`() {
        val c = cache()
        c.put("p1", "http://cdn.example.com/live/a.m3u8?sign=1", null, now)
        assertNotNull(
            "有效期内必须命中（否则等于没做缓存）",
            c.get("p1", now + SniffedPlaylistCache.DEFAULT_TTL_MS - 1),
        )
        assertNull(
            "超过默认 TTL 必须失效（不能把陈旧地址留成永久）",
            c.get("p1", now + SniffedPlaylistCache.DEFAULT_TTL_MS),
        )
    }

    @Test
    fun `expire 秒级时间戳按秒解释`() {
        val c = cache()
        val expireSec = now / 1000 + 600 // 10 分钟后
        c.put("p1", urlWithExpire(expireSec), null, now)
        assertNotNull("600 秒内应命中", c.get("p1", now + 599_000))
        assertNull("过了源站签名时间必须失效", c.get("p1", now + 601_000))
    }

    @Test
    fun `expire 毫秒级时间戳也兼容`() {
        val c = cache()
        c.put("p1", urlWithExpire(now + 120_000), null, now)
        assertNotNull(c.get("p1", now + 119_000))
        assertNull(c.get("p1", now + 121_000))
    }

    @Test
    fun `签名已经过期时不缓存`() {
        val c = cache()
        c.put("p1", urlWithExpire(now / 1000 - 60), null, now)
        assertNull("不能把已经过期的地址写进缓存", c.get("p1", now))
        assertEquals(0, c.size(now))
    }

    @Test
    fun `源站签名很长时按上限截断`() {
        val c = cache()
        c.put("p1", urlWithExpire(now / 1000 + 24 * 3600), null, now) // 一天后
        assertNotNull("上限内应命中", c.get("p1", now + SniffedPlaylistCache.MAX_TTL_MS - 1))
        assertNull(
            "超过上限（60 分钟）必须失效，不能把地址留成半天",
            c.get("p1", now + SniffedPlaylistCache.MAX_TTL_MS),
        )
    }

    @Test
    fun `失效后立刻读不到`() {
        val c = cache()
        c.put("p1", urlWithExpire(now / 1000 + 600), "http://play.example.com/", now)
        assertNotNull(c.get("p1", now))
        c.invalidate("p1")
        assertNull("直连失败后必须立刻作废", c.get("p1", now))
    }

    @Test
    fun `不同播放页互不影响`() {
        val c = cache()
        c.put("p1", urlWithExpire(now / 1000 + 600), null, now)
        c.put("p2", urlWithExpire(now / 1000 + 900), null, now)
        c.invalidate("p1")
        assertNull(c.get("p1", now))
        assertNotNull("另一条线路的缓存不该被误删", c.get("p2", now))
    }

    @Test
    fun `referer 跟着地址一起复用`() {
        val c = cache()
        c.put("p1", urlWithExpire(now / 1000 + 600), "http://play.example.com/", now)
        val hit = c.get("p1", now)
        assertNotNull(hit)
        assertEquals(
            "播放需要同一个 Referer，丢了解析出来也可能被源站拒绝",
            "http://play.example.com/",
            hit!!.referer,
        )
    }
}