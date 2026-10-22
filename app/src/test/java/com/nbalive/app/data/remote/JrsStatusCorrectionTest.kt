package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JrsStatusCorrectionTest {

    private val parser = JrsParser()

    private fun liveMatch(startMillis: Long?) = Match(
        id = "jrs-1",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "老鹰",
        awayTeam = "魔术",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "直播中",
        startAtMillis = startMillis,
        status = MatchStatus.LIVE,
        signalCount = 2,
        sources = emptyList(),
        jrsDetailId = "1",
    )

    @Test
    fun `scheduled time is parsed from detail page`() {
        val html = """<p class="match-info-league">NBA</p> <p class="match-info-time">07:00</p>"""
        assertEquals("07:00", parser.parseScheduledTime(html))
    }

    @Test
    fun `game started well over 3 hours ago is corrected to finished`() {
        // 20 小时前开赛：远超篮球赛 3.5 小时上限 -> 已结束（源站列表仍滞后标着直播中）
        val started = java.time.ZonedDateTime.now().minusHours(20)
        val m = liveMatch(started.toInstant().toEpochMilli())
        val time = "%02d:%02d".format(started.hour, started.minute)
        val c = parser.correctedStatus(m, time)
        assertEquals(MatchStatus.FINISHED, c?.status)
        // 回填真实开赛时间戳（列表页"直播中"行只有 00:00 兜底）
        assertEquals(started.toInstant().toEpochMilli() / 60000, c!!.startAtMillis / 60000)
    }

    @Test
    fun `game started within 3 hours is still live`() {
        val now = java.time.ZonedDateTime.now()
        val twoHoursAgo = now.minusHours(2)
        val m = liveMatch(twoHoursAgo.toInstant().toEpochMilli())
        val time = "%02d:%02d".format(twoHoursAgo.hour, twoHoursAgo.minute)
        assertEquals(MatchStatus.LIVE, parser.correctedStatus(m, time)?.status)
    }

    @Test
    fun `game scheduled in the future is corrected to upcoming`() {
        // 源站会提前把未开赛的场次标成"直播中"（实测 CBA 19:35 场下午就被标）
        val inTwoHours = java.time.ZonedDateTime.now().plusHours(2)
        val m = liveMatch(inTwoHours.toInstant().toEpochMilli())
        val time = "%02d:%02d".format(inTwoHours.hour, inTwoHours.minute)
        assertEquals(MatchStatus.UPCOMING, parser.correctedStatus(m, time)?.status)
    }

    @Test
    fun `no correction when match is not live`() {
        val started = java.time.ZonedDateTime.now().minusHours(20)
        val m = liveMatch(started.toInstant().toEpochMilli()).copy(status = MatchStatus.UPCOMING)
        assertNull(parser.correctedStatus(m, "%02d:%02d".format(started.hour, started.minute)))
    }

    @Test
    fun `no correction when scheduled time missing`() {
        val started = java.time.ZonedDateTime.now().minusHours(20)
        assertNull(parser.correctedStatus(liveMatch(started.toInstant().toEpochMilli()), null))
    }

    @Test
    fun `no correction when start date unknown`() {
        // startAtMillis 为 null（无法确定是哪一天）时不猜测
        assertNull(parser.correctedStatus(liveMatch(null), "07:00"))
    }

    // ---- 跨零点：源站把 00:30 的比赛归到前一天的分组 ----

    @Test
    fun `game grouped under the previous day is corrected to the next day`() {
        // 源站把 11-01 00:30 的比赛归在 10-31 分组里。
        // 现在是 11-01 01:00 —— 按分组日期算会得到"10-31 00:30"，凭空"结束 24 小时前"。
        val groupDay = java.time.LocalDate.of(2026, 10, 31)
        val m = liveMatch(groupDay.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val now = java.time.ZonedDateTime.of(
            java.time.LocalDate.of(2026, 11, 1),
            java.time.LocalTime.of(1, 0),
            java.time.ZoneId.systemDefault(),
        )

        val c = parser.correctedStatus(m, "00:30", now)
        assertEquals(MatchStatus.LIVE, c?.status)
        val start = java.time.Instant.ofEpochMilli(c!!.startAtMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(java.time.LocalDateTime.of(2026, 11, 1, 0, 30), start)
    }

    @Test
    fun `late night game grouped the same day is still finished`() {
        // 同样 00:30，但分组日期就是当天且现在已是深夜 -> 确实该判已结束
        val groupDay = java.time.LocalDate.of(2026, 11, 1)
        val m = liveMatch(groupDay.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val now = java.time.ZonedDateTime.of(
            java.time.LocalDate.of(2026, 11, 1),
            java.time.LocalTime.of(20, 0),
            java.time.ZoneId.systemDefault(),
        )

        val c = parser.correctedStatus(m, "00:30", now)
        assertEquals(MatchStatus.FINISHED, c?.status)
    }
}
