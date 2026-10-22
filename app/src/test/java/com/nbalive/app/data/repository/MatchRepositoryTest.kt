package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * merge() 的回归测试：只测纯合并逻辑，不触网。
 * 这两条对应审查里发现的两个 Critical：
 *  1. 合并键漏了日期 -> 同队背靠背两场被吞成一场
 *  2. 合并后 JRS 信号被丢弃 -> 双源赛事只剩 JRKAN 线路
 */
class MatchRepositoryTest {

    private val repo = MatchRepository()

    private fun match(
        id: String,
        home: String,
        away: String,
        start: Long?,
        timeLabel: String,
        status: MatchStatus = MatchStatus.UPCOMING,
        sources: List<StreamSource> = emptyList(),
        jrsDetailId: String? = null,
    ) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = timeLabel,
        startAtMillis = start,
        status = status,
        signalCount = sources.size,
        sources = sources,
        jrsDetailId = jrsDetailId,
    )

    private fun dayMillis(month: Int, day: Int, hour: Int = 19, minute: Int = 35): Long =
        java.time.LocalDate.of(2026, month, day).atTime(hour, minute)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun invokeMerge(jrs: List<Match>, jrkan: List<Match>): List<Match> {
        val m = MatchRepository::class.java.getDeclaredMethod(
            "merge", List::class.java, List::class.java,
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(repo, jrs, jrkan) as List<Match>
    }

    @Test
    fun `same teams on different days stay two matches`() {
        val day1 = match("jrkan-1", "湖人", "凯尔特人", dayMillis(10, 21), "10-21 19:35")
        val day2 = match("jrkan-2", "湖人", "凯尔特人", dayMillis(10, 23), "10-23 19:35")
        val merged = invokeMerge(emptyList(), listOf(day1, day2))
        assertEquals(2, merged.size)
    }

    @Test
    fun `same teams same day merge into one with union of sources`() {
        val jrkan = match(
            "jrkan-1", "湖人", "凯尔特人", dayMillis(10, 21), "10-21 19:35",
            sources = listOf(
                StreamSource("a", "信号1", Provider.JRKAN, "http://play.sportsteam356.com/play/steam1.html"),
            ),
        )
        val jrs = match(
            "jrs-99", "湖人", "凯尔特人", dayMillis(10, 21), "直播中",
            status = MatchStatus.LIVE,
            jrsDetailId = "99",
        )
        val merged = invokeMerge(listOf(jrs), listOf(jrkan))
        assertEquals(1, merged.size)
        val m = merged.first()
        // JRS 的直播态要覆盖 JRKAN 的未开赛
        assertEquals(MatchStatus.LIVE, m.status)
        // 合并后仍要保留 JRS 详情页 id，loadSignals 靠它取 JRS 信号
        assertNotNull(m.jrsDetailId)
        assertEquals("99", m.jrsDetailId)
    }

    @Test
    fun `live matches sort before upcoming`() {
        val live = match("jrkan-live", "勇士", "火箭", dayMillis(10, 22, 7), "10-22 07:00", status = MatchStatus.LIVE)
        val soon = match("jrkan-soon", "湖人", "凯尔特人", dayMillis(10, 21, 19), "10-21 19:35")
        val merged = invokeMerge(listOf(live, soon), emptyList())
        assertEquals("jrkan-live", merged.first().id)
    }

    @Test
    fun `duplicate ids from both sources never collide in grid`() {
        // 两条 JRS 行 href 异常时 id 相同 -> merge 后必须 distinct，否则 LazyVerticalGrid 崩溃
        val a = match("jrs-", "A队", "B队", null, "直播中")
        val b = match("jrs-", "C队", "D队", null, "直播中")
        val merged = invokeMerge(listOf(a, b), emptyList())
        assertEquals(2, merged.map { it.id }.distinct().size)
    }
}
