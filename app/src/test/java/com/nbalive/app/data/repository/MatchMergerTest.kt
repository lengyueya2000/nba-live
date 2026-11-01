package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.MatchStatusClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽出 `MatchMerger` 之后的判据（task-6 / fix-data）。
 *
 * **optimization-review.md D10**：`MatchRepository` 1180 行、职责过宽
 * （抓取/重试 + 合并 + 时钟校正 + 详情页缓存 + 播放页展开 + 候选校验），
 * "判据类"逻辑与 IO/协程样板混在一起 —— D2 的 `league` 丢数据 bug 就长在合并里。
 *
 * 本文件证明：
 *  - 合并器是**纯函数**（无网络、无协程），可单独钉住每一条规则；
 *  - `MatchRepository.mergeForTest`（既有测试在用的签名）与 `MatchMerger.merge` **同源**，
 *    不是又抄了一份实现。
 */
class MatchMergerTest {

    @Test
    fun `betterLeague 优先级 supported 高于非 OTHER 高于其它`() {
        assertEquals("supported 压过 OTHER", League.NBA, MatchMerger.betterLeague(League.OTHER, League.NBA))
        assertEquals("supported 压过 OTHER（反方向）", League.CBA, MatchMerger.betterLeague(League.CBA, League.OTHER))
        assertEquals("两个 OTHER 平手时保持先合并的那个", League.OTHER, MatchMerger.betterLeague(League.OTHER, League.OTHER))
        assertEquals(
            "两个都 supported 时保持先合并的那个（平手不改语义）",
            League.CBA,
            MatchMerger.betterLeague(League.CBA, League.NBA),
        )
    }

    @Test
    fun `betterStatus 取更可信的 而不是后合并的`() {
        assertEquals(MatchStatus.LIVE, MatchMerger.betterStatus(MatchStatus.LIVE, MatchStatus.UPCOMING))
        assertEquals(MatchStatus.FINISHED, MatchMerger.betterStatus(MatchStatus.LIVE, MatchStatus.FINISHED))
        assertEquals(
            "更不可信的那个不许把更可信的改差",
            MatchStatus.FINISHED,
            MatchMerger.betterStatus(MatchStatus.FINISHED, MatchStatus.UPCOMING),
        )
    }

    @Test
    fun `normalizeKey 与主客顺序无关 但区分日期`() {
        val day1 = dayMillis(2026, 10, 23)
        val day2 = dayMillis(2026, 10, 24)
        val a = match("jrs-1", "湖人", "凯尔特人", day1)
        val swapped = match("jrkan-1", "凯尔特人", "湖人", day1)
        val nextDay = match("jrs-2", "湖人", "凯尔特人", day2)

        assertEquals(
            "实测两源主客顺序相反，key 必须与顺序无关（否则同一场拆成两张卡）",
            MatchMerger.normalizeKey(a),
            MatchMerger.normalizeKey(swapped),
        )
        assertNotEquals(
            "背靠背/系列赛要靠日期区分，不能只按队名归并",
            MatchMerger.normalizeKey(a),
            MatchMerger.normalizeKey(nextDay),
        )
    }

    @Test
    fun `拿不到开赛时间戳时用当天而不是字面量`() {
        val jrs = match("jrs-1", "湖人", "凯尔特人", null, timeLabel = "直播中")
        val jrkan = match("jrkan-1", "湖人", "凯尔特人", null, timeLabel = "10-25 07:30")
        assertTrue(
            "旧兜底 `substringBefore(' ')` 会得到字面量「直播中」，与 JRKAN 的日期永不相等",
            MatchMerger.normalizeKey(jrs).isNotBlank(),
        )
        assertEquals(
            "同一天同一对阵必须落同一个 key（旧实现里 JRS 侧是「直播中|A|B」、JRKAN 侧是日期）",
            MatchMerger.normalizeKey(jrs).substringAfter('|'),
            MatchMerger.normalizeKey(jrkan).substringAfter('|'),
        )
    }

    @Test
    fun `合并时信号列表按 pageUrl 去重并累计条数`() {
        val jrkan = match(
            "jrkan-1", "湖人", "凯尔特人", dayMillis(2026, 10, 23),
            sources = listOf(source("jrkan-1-s0", "http://h/play/pao/?id=1")),
        )
        val jrs = match(
            "jrs-1", "湖人", "凯尔特人", dayMillis(2026, 10, 23),
            sources = listOf(
                source("jrs-1-s0", "http://h/play/pao/?id=1"), // 与 JRKAN 同址：必须去重
                source("jrs-1-s1", "http://h/play/mgxl/?id=2"),
            ),
        )

        val merged = MatchMerger.merge(jrs = listOf(jrs), jrkan = listOf(jrkan)).matches
        assertEquals(1, merged.size)
        assertEquals("同一 pageUrl 只留一条", 2, merged.single().sources.size)
        assertEquals("signalCount 必须跟着去重后的条数", 2, merged.single().signalCount)
    }

    @Test
    fun `仓库的测试入口与合并器同源 不是又抄了一份`() {
        val jrkan = match("jrkan-1", "湖人", "凯尔特人", dayMillis(2026, 10, 23))
        val jrs = match("jrs-1", "洛杉矶湖人", "凯尔特人", dayMillis(2026, 10, 23))
        val viaRepository = MatchRepository().mergeForTest(listOf(jrs), listOf(jrkan))
        val viaMerger = MatchMerger.merge(listOf(jrs), listOf(jrkan)).matches
        assertEquals(
            "mergeForTest 的签名保持不变（既有测试在用），实现必须委托给 MatchMerger",
            viaMerger,
            viaRepository,
        )
    }

    // ---------------- 夹具 ----------------

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day)
            .atTime(19, 35)
            .atZone(MatchStatusClock.SOURCE_ZONE)
            .toInstant()
            .toEpochMilli()

    private fun source(id: String, url: String) =
        StreamSource(id = id, label = "线路", provider = Provider.JRS, pageUrl = url)

    private fun match(
        id: String,
        home: String,
        away: String,
        startAtMillis: Long?,
        timeLabel: String = "19:35",
        sources: List<StreamSource> = emptyList(),
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
        status = MatchStatus.UPCOMING,
        signalCount = sources.size,
        sources = sources,
        jrsDetailId = null,
    )
}