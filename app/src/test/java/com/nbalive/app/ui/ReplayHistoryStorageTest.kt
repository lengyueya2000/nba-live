package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.ReplayLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录像历史 JSON 落盘的序列化往返（纯 JVM，不碰 Android）。 */
class ReplayHistoryStorageTest {

    private fun sample() = ReplayHistoryItem(
        matchId = "2451",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "老鹰",
        awayTeam = "活塞",
        homeScore = 107,
        awayScore = 97,
        homeLogo = "https://www.rtmpcdn.com/uploads/a.png",
        awayLogo = "https://www.rtmpcdn.com/uploads/b.png",
        timeLabel = "11-23 04:30",
        lines = listOf(
            ReplayLine("l0", "标清回放", "https://www.rtmpcdn.com/vodplay/a.m3u8"),
            ReplayLine("l1", "中文高清回放", "https://www.rtmpcdn.com/vodplay/b.m3u8"),
        ),
        lastPositionMs = 42_000L,
        watchedAt = 1_700_000_000_000L,
    )

    @Test
    fun `编码后解码往返保持所有字段`() {
        val original = sample()
        val json = ReplayHistoryStorage.encode(listOf(original))
        val decoded = ReplayHistoryStorage.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(original, decoded.first())
    }

    @Test
    fun `空列表编码解码`() {
        assertEquals(emptyList<ReplayHistoryItem>(), ReplayHistoryStorage.decode(ReplayHistoryStorage.encode(emptyList())))
    }

    @Test
    fun `旧JSON没有logo字段读成null`() {
        val old = """{"items":[{"matchId":"1","league":"NBA","leagueRawName":"NBA",
            "homeTeam":"A","awayTeam":"B","homeScore":1,"awayScore":2,"timeLabel":"11-25",
            "lines":[],"lastPositionMs":5,"watchedAt":9}]}"""
        val decoded = ReplayHistoryStorage.decode(old)
        assertEquals(1, decoded.size)
        org.junit.Assert.assertNull(decoded.first().homeLogo)
        org.junit.Assert.assertNull(decoded.first().awayLogo)
    }

    @Test
    fun `垃圾正文返回空表不崩`() {
        assertTrue(ReplayHistoryStorage.decode("not json").isEmpty())
        assertTrue(ReplayHistoryStorage.decode("").isEmpty())
        assertTrue(ReplayHistoryStorage.decode("""{"items":"not-array"}""").isEmpty())
    }

    @Test
    fun `缺字段的坏条目被跳过`() {
        val json = """{"items":[{"matchId":"ok","league":"NBA","homeTeam":"A","awayTeam":"B",
            "lines":[],"lastPositionMs":1,"watchedAt":2}, {"league":"NBA"}]}"""
        val decoded = ReplayHistoryStorage.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.first().matchId)
    }
}
