package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

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

    private fun invokeSort(matches: List<Match>): List<Match> {
        val m = MatchRepository::class.java.getDeclaredMethod("sort", List::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(repo, matches) as List<Match>
    }

    @Test
    fun `live matches sort before upcoming`() {
        val live = match("jrkan-live", "勇士", "火箭", dayMillis(10, 22, 7), "10-22 07:00", status = MatchStatus.LIVE)
        val soon = match("jrkan-soon", "湖人", "凯尔特人", dayMillis(10, 21, 19), "10-21 19:35")
        val merged = invokeMerge(listOf(live, soon), emptyList())
        assertEquals("jrkan-live", merged.first().id)
    }

    @Test
    fun `live matches are ordered latest first`() {
        // 直播中按开赛时间倒序：最晚开赛（最新鲜）的在最上面
        val early = match("live-7", "勇士", "火箭", dayMillis(10, 23, 7), "10-23 07:00", status = MatchStatus.LIVE)
        val mid = match("live-930", "湖人", "凯尔特人", dayMillis(10, 23, 9, 30), "10-23 09:30", status = MatchStatus.LIVE)
        val late = match("live-1935", "雄鹿", "热火", dayMillis(10, 23, 19, 35), "10-23 19:35", status = MatchStatus.LIVE)
        val sorted = invokeSort(listOf(early, mid, late))
        assertEquals(listOf("live-1935", "live-930", "live-7"), sorted.map { it.id })
    }

    @Test
    fun `upcoming matches still sort earliest first`() {
        // 非直播组保持升序：最近要开的在前
        val tomorrow = match("up-t", "湖人", "凯尔特人", dayMillis(10, 24, 19, 35), "10-24 19:35")
        val today = match("up-d", "勇士", "火箭", dayMillis(10, 23, 7), "10-23 07:00")
        val sorted = invokeSort(listOf(tomorrow, today))
        assertEquals(listOf("up-d", "up-t"), sorted.map { it.id })
    }

    @Test
    fun `duplicate ids from both sources never collide in grid`() {
        // 两条 JRS 行 href 异常时 id 相同 -> merge 后必须 distinct，否则 LazyVerticalGrid 崩溃
        val a = match("jrs-", "A队", "B队", null, "直播中")
        val b = match("jrs-", "C队", "D队", null, "直播中")
        val merged = invokeMerge(listOf(a, b), emptyList())
        assertEquals(2, merged.map { it.id }.distinct().size)
    }

    // ---- 回归：源站把未开赛场次标成"直播中"时，本地时钟要能纠回未开赛 ----

    @Test
    fun `future match without a detail page is corrected from live to upcoming`() = runBlocking {
        val start = java.time.LocalDate.now().plusDays(1).atTime(19, 35)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 无 jrsDetailId（没有详情页可查）且 id 不带任何已知源前缀：
        // 判定必须基于"有没有详情页可查 + 计划开赛时间"，而不是猜 id 前缀
        val m = match(
            "other-9", "湖人", "凯尔特人", start, "10-24 19:35",
            status = MatchStatus.LIVE,
        )
        val fixed = repo.correctLiveStatuses(listOf(m))
        assertEquals(MatchStatus.UPCOMING, fixed.single().status)
    }

    // ---- 回归：多个 JRKAN 播放页展开出的线路 id 不能重复 ----

    @Test
    fun `expanding several jrkan play pages yields unique source ids`() = runBlocking {
        // 同一场比赛在源站出现两行 -> merge 后带两个 JRKAN 播放页。
        // 每个播放页都要展开出自己的线路；id 重复会让播放器的 LazyColumn key 冲突而崩溃。
        val m = match(
            "jrkan-1", "湖人", "凯尔特人", dayMillis(10, 21), "10-21 19:35",
            sources = listOf(
                StreamSource("jrkan-1-s0", "信号1", Provider.JRKAN, "http://play.sportsteam356.com/play/steam1.html"),
                StreamSource("jrkan-1-s1", "信号2", Provider.JRKAN, "http://play.sportsteam356.com/play/steam2.html"),
            ),
        )

        fun page(vararg ids: String) = ids.joinToString("") { id ->
            """<a class="item" data-play="/play/sm.html?id=$id&id2="><strong>直播$id</strong></a>"""
        }

        val detail = repo.loadSignals(m) { url, _ ->
            if (url.contains("steam1")) page("439", "919") else page("111", "222")
        }

        assertEquals("两个播放页各展开出 2 条线路", 4, detail.signals.size)
        assertEquals(
            "线路 id 必须唯一，否则播放器 LazyColumn 会因 key 重复崩溃",
            detail.signals.size,
            detail.signals.map { it.id }.distinct().size,
        )
    }
}
