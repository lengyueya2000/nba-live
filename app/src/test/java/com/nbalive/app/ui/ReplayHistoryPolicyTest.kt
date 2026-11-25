package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.data.model.ReplayLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录像历史的纯判据（2026-11-25 用户需求：我的页看播放历史，可续播）。
 *
 * - 同一场去重，保留**最近一次**观看的位置与时间；
 * - 按 watchedAt 倒序（最近看的在前）；
 * - 上限 10 条，超出淘汰最旧的。
 */
class ReplayHistoryPolicyTest {

    @Test
    fun `续播状态机时长未知等待合法seek越界直接完成`() {
        // 时长未知（0）→ PENDING：不能 seek，也不能上报（等 READY）
        assertEquals(
            ReplayHistoryPolicy.ResumeState.PENDING,
            ReplayHistoryPolicy.resumeState(initialSeekMs = 5_000L, durationMs = 0L, consumedMs = -1L),
        )
        // 无续播意图（0）→ DONE：立即允许正常上报
        assertEquals(
            ReplayHistoryPolicy.ResumeState.DONE,
            ReplayHistoryPolicy.resumeState(initialSeekMs = 0L, durationMs = 100_000L, consumedMs = -1L),
        )
        // 合法续播点 → SEEK
        assertEquals(
            ReplayHistoryPolicy.ResumeState.SEEK,
            ReplayHistoryPolicy.resumeState(initialSeekMs = 5_000L, durationMs = 100_000L, consumedMs = -1L),
        )
        // 越界（>=时长）→ DONE：从头播，但必须允许上报（否则历史永久卡在旧位置）
        assertEquals(
            ReplayHistoryPolicy.ResumeState.DONE,
            ReplayHistoryPolicy.resumeState(initialSeekMs = 150_000L, durationMs = 100_000L, consumedMs = -1L),
        )
        // 已消费（换线/重连后 initialSeekMs 未变但已消费过）→ DONE：不再 seek，不倒退
        assertEquals(
            ReplayHistoryPolicy.ResumeState.DONE,
            ReplayHistoryPolicy.resumeState(initialSeekMs = 5_000L, durationMs = 100_000L, consumedMs = 5_000L),
        )
    }

    private fun item(id: String, watchedAt: Long, positionMs: Long) = ReplayHistoryItem(
        matchId = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "球队$id",
        awayTeam = "客队$id",
        homeScore = 100,
        awayScore = 98,
        timeLabel = "11-25 08:00",
        lines = listOf(ReplayLine("l-$id", "标清回放", "https://x/$id.m3u8")),
        lastPositionMs = positionMs,
        watchedAt = watchedAt,
    )

    @Test
    fun `同一场去重保留最近一次观看的位置与时间`() {
        val older = item("1", watchedAt = 100L, positionMs = 1000L)
        val newer = item("1", watchedAt = 200L, positionMs = 5000L)
        val result = ReplayHistoryPolicy.merge(listOf(older, newer), limit = 10)
        assertEquals(1, result.size)
        assertEquals("1", result.first().matchId)
        assertEquals(5000L, result.first().lastPositionMs)
        assertEquals(200L, result.first().watchedAt)
    }

    @Test
    fun `按最近观看时间倒序`() {
        val result = ReplayHistoryPolicy.merge(
            listOf(item("a", 100L, 0L), item("b", 300L, 0L), item("c", 200L, 0L)),
            limit = 10,
        )
        assertEquals(listOf("b", "c", "a"), result.map { it.matchId })
    }

    @Test
    fun `超过上限淘汰最旧的`() {
        val input = (1..12).map { item("$it", watchedAt = it.toLong(), positionMs = 0L) }
        val result = ReplayHistoryPolicy.merge(input, limit = 10)
        assertEquals(10, result.size)
        // 保留最新的 10 条（watchedAt 3..12），最旧的 1、2 被淘汰
        assertEquals((3L..12L).toList(), result.map { it.watchedAt }.sorted())
        assertTrue(result.all { it.watchedAt >= 3L })
    }

    @Test
    fun `从ReplayItem与位置构造历史条目`() {
        val replay = ReplayItem(
            matchId = "2451", league = League.NBA, leagueRawName = "NBA",
            homeTeam = "老鹰", awayTeam = "活塞", homeScore = 107, awayScore = 97,
            timeLabel = "11-23 04:30", startAtMillis = 1L,
            lines = listOf(ReplayLine("l", "标清回放", "https://x/a.m3u8")),
        )
        val h = ReplayHistoryPolicy.fromReplay(replay, positionMs = 42_000L, watchedAt = 999L)
        assertEquals("2451", h.matchId)
        assertEquals(42_000L, h.lastPositionMs)
        assertEquals(999L, h.watchedAt)
        assertEquals(1, h.lines.size)
    }

    @Test
    fun `续播位置合法时才生效`() {
        // 位置 <=0 或超过时长不应续播（交给播放器从头播）
        assertTrue(ReplayHistoryPolicy.shouldResume(positionMs = 5_000L, durationMs = 100_000L))
        org.junit.Assert.assertFalse(ReplayHistoryPolicy.shouldResume(positionMs = 0L, durationMs = 100_000L))
        org.junit.Assert.assertFalse(ReplayHistoryPolicy.shouldResume(positionMs = 120_000L, durationMs = 100_000L))
        org.junit.Assert.assertFalse(ReplayHistoryPolicy.shouldResume(positionMs = 5_000L, durationMs = 0L))
    }
}
