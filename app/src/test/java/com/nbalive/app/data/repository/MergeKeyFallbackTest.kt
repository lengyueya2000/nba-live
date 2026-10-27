package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源合并键在「拿不到开赛时间戳」时的兜底（review/02-data.md P2-12）。
 *
 * ## 要解决什么
 *
 * 合并键是 `日期|队名对`。旧实现在 `startAtMillis == null` 时退化成
 * `timeLabel.substringBefore(' ')` —— JRS 的"直播中"行 timeLabel 就是
 * `"直播中"`，key 变成 `"直播中|A|B"`；JRKAN 同一场是 `"10-25|A|B"`。
 * 两者永不相等 → 同一场被拆成两张卡，各只有半边信号。
 *
 * 触发条件：JRS 列表项缺 `p.list-title`（日期分组标题）。真实抓包有，
 * 当前不触发；但这类"少一个 DOM 节点就静默拆卡"的路径正是源站改版时最先坏的地方。
 */
class MergeKeyFallbackTest {

    private val repo = MatchRepository()

    private fun match(
        id: String,
        home: String,
        away: String,
        timeLabel: String,
        startAtMillis: Long?,
        provider: Provider,
        detailId: String? = null,
    ) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = timeLabel,
        startAtMillis = startAtMillis,
        status = MatchStatus.LIVE,
        signalCount = 1,
        sources = listOf(
            StreamSource(
                id = "$id-s0",
                label = "信号1",
                provider = provider,
                pageUrl = if (provider == Provider.JRS) {
                    "http://play.sportsteam368.com/play/mgxl/?id=1&id2="
                } else {
                    "http://play.sportsteam356.com/play/steam1.html"
                },
            ),
        ),
        jrsDetailId = detailId,
    )

    @Test
    fun `JRS 直播中行与 JRKAN 同日同场次应当合并`() {
        // JRS 缺 dateLabel -> startAtMillis = null，timeLabel = "直播中"；
        // 此时兜底到**当天**（北京时间）。所以 JRKAN 那边也必须落在当天，
        // 才构成"同一场"的真实场景（缺 dateLabel 只会发生在当天那批场次上）。
        val today = java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE)
        val label = "%02d-%02d 07:30".format(today.monthValue, today.dayOfMonth)
        val jrs = match("jrs-1", "湖人", "凯尔特人", "直播中", null, Provider.JRS, detailId = "555")
        // JRKAN 有完整时间；主客顺序相反（实测源站就是反的）
        val jrkan = match(
            "jrkan-1", "凯尔特人", "湖人", label,
            today.atStartOfDay(MatchStatusClock.SOURCE_ZONE).toInstant().toEpochMilli(),
            Provider.JRKAN,
        )

        val merged = repo.mergeForTest(listOf(jrs), listOf(jrkan))
        assertEquals(
            "同一场（队名对相同）不该被拆成两张卡，实际 ${merged.size} 张：${merged.map { it.id }}",
            1,
            merged.size,
        )
        assertTrue(
            "合并后应当同时带上两个源的信号（实际 ${merged.first().sources.size} 条）",
            merged.first().sources.size >= 2,
        )
    }

    @Test
    fun `两个源都没有时间戳时 同一天同队名对仍能合并`() {
        // 极端情况：两源都缺日期。旧实现会得到 "直播中|..." 与 "未开始|..." 两个 key
        val jrs = match("jrs-1", "湖人", "凯尔特人", "直播中", null, Provider.JRS, detailId = "555")
        val jrkan = match("jrkan-1", "湖人", "凯尔特人", "未开始", null, Provider.JRKAN)

        val merged = repo.mergeForTest(listOf(jrs), listOf(jrkan))
        assertEquals(
            "两源都缺时间戳时也应落到同一个日期兜底（当天），不该拆成两张卡",
            1,
            merged.size,
        )
    }

    @Test
    fun `JRKAN 的 MM-dd 文案在缺时间戳时仍能提供真实日期`() {
        // JRKAN 的 timeLabel 带真实日期，即使 startAtMillis 解析失败也要用它
        val a = match("jrkan-1", "湖人", "凯尔特人", "10-25 07:30", null, Provider.JRKAN)
        val b = match("jrkan-2", "湖人", "凯尔特人", "10-25 08:30", null, Provider.JRKAN)
        val merged = repo.mergeForTest(listOf(a), listOf(b))
        assertEquals("同一天同一对队应当合并", 1, merged.size)
    }

    @Test
    fun `不同日期的同队名对不会被误合并`() {
        // 兜底不能过头：背靠背两场必须分开
        val a = match("jrkan-1", "湖人", "凯尔特人", "10-25 07:30", dayMillis(10, 25), Provider.JRKAN)
        val b = match("jrkan-2", "湖人", "凯尔特人", "10-26 07:30", dayMillis(10, 26), Provider.JRKAN)
        val merged = repo.mergeForTest(listOf(a), listOf(b))
        assertEquals("不同日期的同队名对必须分开（背靠背两场）", 2, merged.size)
    }

    private fun dayMillis(month: Int, day: Int): Long {
        val year = java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE).year
        return java.time.LocalDate.of(year, month, day)
            .atStartOfDay(MatchStatusClock.SOURCE_ZONE)
            .toInstant()
            .toEpochMilli()
    }
}