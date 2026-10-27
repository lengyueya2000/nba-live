package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放线路的"临时拉黑"记账（review/01-P1-5）。
 *
 * ## 要解决什么
 *
 * 15 秒看门狗把弱网首帧 >15s 的**健康线路**判成卡死，然后
 * `failedSources += pageUrl` —— 而这个集合在会话内**永不清除**。
 * 用户看到的是"线路莫名被跳走且回不来"：那条线其实是好的，
 * 只是第一帧慢了点，之后整场都不会再被自动选中。
 *
 * 这与 `02-P1-9` 的域名候选是同一个错误：**把暂时故障写成永久故障**。
 * 限流窗口、弱网、CDN 抖动都会过去，永久黑名单只是把暂时问题变成长期问题。
 *
 * ## 规则
 *
 * 1. 拉黑带时间戳，超过 [SourceFailureBook.TTL_MS] 自动失效；
 * 2. 用户显式点这条线 -> 立刻解除（这条已有，保留）；
 * 3. 切场 / 刷新列表 -> 整本清空（这条已有，保留）；
 * 4. 查不到时间戳 = 未拉黑（不能因为时钟/初始状态误伤）。
 */
class SourceFailureBookTest {

    private val ttl = SourceFailureBook.TTL_MS

    @Test
    fun `刚拉黑的线路算不可用`() {
        val book = SourceFailureBook()
        book.markFailed("a", nowMs = 1_000)
        assertTrue(book.isFailed("a", nowMs = 1_000))
    }

    @Test
    fun `过期后自动解除 不再永久拉黑`() {
        val book = SourceFailureBook()
        book.markFailed("a", nowMs = 1_000)
        assertFalse(
            "拉黑必须能过期：弱网/限流窗口会过去，永久黑名单只是把暂时故障变长期",
            book.isFailed("a", nowMs = 1_000 + ttl + 1),
        )
    }

    @Test
    fun `过期边界前一刻仍然有效`() {
        val book = SourceFailureBook()
        book.markFailed("a", nowMs = 1_000)
        assertTrue("TTL 边界内不该提前解除", book.isFailed("a", nowMs = 1_000 + ttl - 1))
    }

    @Test
    fun `重复拉黑会刷新时间戳`() {
        val book = SourceFailureBook()
        book.markFailed("a", nowMs = 1_000)
        book.markFailed("a", nowMs = 1_000 + ttl / 2)
        assertTrue(
            "再次失败应重新计时，否则第一次失败的时间戳会让它提前解封",
            book.isFailed("a", nowMs = 1_000 + ttl + 1),
        )
    }

    @Test
    fun `用户显式点这条线立刻解除`() {
        val book = SourceFailureBook()
        book.markFailed("a", nowMs = 1_000)
        book.clear("a")
        assertFalse(book.isFailed("a", nowMs = 1_000))
    }

    @Test
    fun `从没拉黑过的线路永远可用`() {
        val book = SourceFailureBook()
        assertFalse(book.isFailed("never", nowMs = 0))
        assertFalse(book.isFailed("never", nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `清空整本后全部恢复`() {
        val book = SourceFailureBook()
        book.markFailed("a", nowMs = 1_000)
        book.markFailed("b", nowMs = 1_000)
        book.clearAll()
        assertFalse(book.isFailed("a", nowMs = 1_000))
        assertFalse(book.isFailed("b", nowMs = 1_000))
    }

    @Test
    fun `过期的条目不会无限堆积`() {
        val book = SourceFailureBook()
        repeat(100) { i -> book.markFailed("url$i", nowMs = 1_000) }
        // 全部过期后，写入新条目时应顺带清理旧条目
        book.markFailed("fresh", nowMs = 1_000 + ttl + 1)
        assertEquals("过期条目应被清理，否则长时间播放会一直堆着", 1, book.size(nowMs = 1_000 + ttl + 1))
    }
}