package com.nbalive.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 域名反混淆还原测试。
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
}
