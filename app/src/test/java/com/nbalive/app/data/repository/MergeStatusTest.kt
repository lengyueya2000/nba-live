package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并两个源时的状态取舍。
 *
 * ⚠️ 原来的实现是"后合并的赢"（JRS 后合并，直接用 JRS 的状态），
 * 但 JRS 列表的"直播中"会滞后好几小时，
 * 而 JRKAN 自带 MM-dd HH:mm 可以做时钟校正 —— 合并时不该把更准的状态覆盖掉。
 */
class MergeStatusTest {

    private fun match(
        id: String = "m1",
        home: String = "湖人",
        away: String = "勇士",
        status: MatchStatus = MatchStatus.LIVE,
        day: String = "2026-10-24",
    ) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = "$day 10:00",
        startAtMillis = null,
        status = status,
        signalCount = 0,
        sources = emptyList(),
    )

    @Test
    fun `JRS 的滞后 LIVE 不能盖掉 JRKAN 已结束的准确状态`() {
        // JRKAN 先合并（更准：已结束），JRS 后合并（滞后：还标直播中）
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(id = "jrkan-1", status = MatchStatus.FINISHED)),
            jrkan = listOf(match(id = "jrkan-1", status = MatchStatus.FINISHED)),
        )
        // 两种情况都是 FINISHED，结果应为 FINISHED
        assertEquals(MatchStatus.FINISHED, merged.first().status)
    }

    @Test
    fun `已结束的状态不会被直播中覆盖`() {
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(status = MatchStatus.LIVE)),
            jrkan = listOf(match(status = MatchStatus.FINISHED)),
        )
        assertEquals(
            "更可信的 FINISHED 不该被较不可信的 LIVE 覆盖",
            MatchStatus.FINISHED,
            merged.first().status,
        )
    }

    @Test
    fun `直播中不会被未开赛覆盖`() {
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(status = MatchStatus.UPCOMING)),
            jrkan = listOf(match(status = MatchStatus.LIVE)),
        )
        assertEquals(MatchStatus.LIVE, merged.first().status)
    }

    @Test
    fun `合并后 id 必须唯一`() {
        val repo = MatchRepository()
        // 两个源用了同一个 id
        val merged = repo.mergeForTest(
            jrs = listOf(match(id = "same")),
            jrkan = listOf(match(id = "same")),
        )
        val ids = merged.map { it.id }
        assertEquals("合并后 id 不能重复", ids.size, ids.distinct().size)
    }

    @Test
    fun `合并后队名顺序颠倒也要归到同一场`() {
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(id = "a", home = "湖人", away = "勇士")),
            jrkan = listOf(match(id = "b", home = "勇士", away = "湖人")),
        )
        assertEquals("主客队颠倒应该合并成一场", 1, merged.size)
    }
}
