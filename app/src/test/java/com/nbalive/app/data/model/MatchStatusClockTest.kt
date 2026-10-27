package com.nbalive.app.data.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态随时间"老化"的纯逻辑。
 *
 * 背景（review/02-data.md P1-1）：状态原来只在两个时刻算一次 —— 解析列表时、
 * 列表加载完成后校正一次。算完就写进 `Match.status`，**之后没有任何机制让它老化**。
 * 用户早上打开 App，把 App 放一下午再切回来，列表不会重新拉取（这是刻意的取舍），
 * 于是 13:30 就该结束的比赛到晚上仍显示「直播中」。
 *
 * 修法不是加网络轮询（会打爆源站），而是把这套判定提成**可注入 now 的纯函数**，
 * 让 ViewModel 在列表可见时每 60 秒纯内存重算一次。这些用例钉住它的契约。
 */
class MatchStatusClockTest {

    private val zone: ZoneId = MatchStatusClock.SOURCE_ZONE

    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(LocalDate.of(2026, 10, day), LocalTime.of(hour, minute), zone)

    private fun millis(d: ZonedDateTime): Long = d.toInstant().toEpochMilli()

    private fun match(
        id: String = "m1",
        startAtMillis: Long?,
        timeLabel: String,
        status: MatchStatus,
        home: String = "湖人",
        away: String = "勇士",
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
        status = status,
        signalCount = 0,
        sources = emptyList(),
    )

    // ---------- 时钟窗口 ----------

    @Test
    fun `开赛超过三小时半判已结束`() {
        val start = at(24, 19, 35)
        assertEquals(
            MatchStatus.FINISHED,
            MatchStatusClock.byClock(millis(start), at(24, 23, 10)),
        )
    }

    @Test
    fun `窗口内判直播中`() {
        val start = at(24, 19, 35)
        assertEquals(
            MatchStatus.LIVE,
            MatchStatusClock.byClock(millis(start), at(24, 21, 40)),
        )
    }

    @Test
    fun `开赛前十五分钟以上判未开赛`() {
        val start = at(24, 19, 35)
        assertEquals(
            MatchStatus.UPCOMING,
            MatchStatusClock.byClock(millis(start), at(24, 18, 0)),
        )
    }

    /**
     * P1-4 的核心契约：`now` 必须真的被用上。
     *
     * 原来的 `statusByClock` 内部自己取 `ZonedDateTime.now()`，
     * 于是 `correctLiveStatuses(now = 固定时刻)` 对 JRKAN 场次完全无效 ——
     * 同一份测试上午跑和晚上跑结果不同，跨零点分支根本钉不住。
     */
    @Test
    fun `同一个开赛时间在不同 now 下得到不同状态`() {
        val start = at(24, 19, 35)
        val startMillis = millis(start)
        assertEquals(
            "下午还没开赛",
            MatchStatus.UPCOMING,
            MatchStatusClock.byClock(startMillis, at(24, 12, 0)),
        )
        assertEquals(
            "晚上正在打",
            MatchStatus.LIVE,
            MatchStatusClock.byClock(startMillis, at(24, 21, 0)),
        )
        assertEquals(
            "深夜已经结束",
            MatchStatus.FINISHED,
            MatchStatusClock.byClock(startMillis, at(25, 1, 0)),
        )
    }

    @Test
    fun `源站时间按北京时间解释`() {
        // 源站给的时间串是北京时间，与设备时区无关。
        // 设备时区不是东八区时，用 systemDefault 解释会让状态整体偏移。
        assertEquals(ZoneId.of("Asia/Shanghai"), MatchStatusClock.SOURCE_ZONE)
    }

    // ---------- 老化：只向前，不回正 ----------

    @Test
    fun `直播中过了窗口会老化成已结束`() {
        val start = at(24, 7, 0)
        val aged = MatchStatusClock.aged(
            listOf(match(startAtMillis = millis(start), timeLabel = "10-24 07:00", status = MatchStatus.LIVE)),
            now = at(24, 12, 0),
        )
        assertEquals(MatchStatus.FINISHED, aged.single().status)
    }

    @Test
    fun `未开赛过了开赛点会老化成直播中`() {
        val start = at(24, 19, 35)
        val aged = MatchStatusClock.aged(
            listOf(match(startAtMillis = millis(start), timeLabel = "10-24 19:35", status = MatchStatus.UPCOMING)),
            now = at(24, 19, 40),
        )
        assertEquals(MatchStatus.LIVE, aged.single().status)
    }

    @Test
    fun `状态只向前老化 已结束绝不回正成直播中`() {
        // ⚠️ 这条防的是"老化"引入的新 bug：
        // 已结束的判定可能来自详情页真实开赛时间（比列表的 00:00 占位更准），
        // 如果老化时无条件按时钟重算，一场真正结束的比赛会在"占位时间 + 3.5 小时"
        // 的窗口里被改回「直播中」—— 比不老化更糟。
        val start = at(24, 19, 35)
        val alreadyFinished = match(
            startAtMillis = millis(start),
            timeLabel = "10-24 19:35",
            status = MatchStatus.FINISHED,
        )
        // 开赛时刻本身：按窗口算是 LIVE，但已经判死的不能被"救活"
        val aged = MatchStatusClock.aged(listOf(alreadyFinished), now = at(24, 20, 0))
        assertEquals(MatchStatus.FINISHED, aged.single().status)
        assertSame(
            "状态没变就不该产生新对象（ViewModel 靠 equals 判断要不要更新）",
            alreadyFinished,
            aged.single(),
        )
    }

    @Test
    fun `已结束的场次不会被老化改回未开赛`() {
        val future = at(30, 19, 35)
        val finished = match(
            startAtMillis = millis(future),
            timeLabel = "10-30 19:35",
            status = MatchStatus.FINISHED,
        )
        assertEquals(
            MatchStatus.FINISHED,
            MatchStatusClock.aged(listOf(finished), now = at(24, 12, 0)).single().status,
        )
    }

    @Test
    fun `开赛时间未知的场次不动`() {
        val unknown = match(startAtMillis = null, timeLabel = "直播中", status = MatchStatus.LIVE)
        assertEquals(
            MatchStatus.LIVE,
            MatchStatusClock.aged(listOf(unknown), now = at(24, 23, 0)).single().status,
        )
    }

    // ---------- JRS"直播中"占位行：不能按 00:00 套窗口 ----------

    /**
     * JRS 列表里"直播中"的行，`list-info-time` 的文本就是"直播中"，
     * 真实 HH:mm 被顶掉了，解析出来的 startAtMillis 只是**分组日期的 00:00 占位**。
     *
     * 直接套时钟窗口会在 03:30 就把一场 19:35 开球的比赛判成已结束 —— 误杀正在直播的比赛。
     * 所以这类行只能做"整天过完"的下界判断。
     */
    @Test
    fun `直播中占位行当天不按零点误杀`() {
        val dayStart = at(24, 0, 0)
        val placeholder = match(
            startAtMillis = millis(dayStart),
            timeLabel = "直播中",
            status = MatchStatus.LIVE,
        )
        val aged = MatchStatusClock.aged(listOf(placeholder), now = at(24, 20, 0))
        assertEquals(
            "19:35 开球的比赛在 20:00 还可能在打，占位行不能按 00:00 判死",
            MatchStatus.LIVE,
            aged.single().status,
        )
    }

    @Test
    fun `直播中占位行整日过完后判已结束`() {
        val dayStart = at(24, 0, 0)
        val placeholder = match(
            startAtMillis = millis(dayStart),
            timeLabel = "直播中",
            status = MatchStatus.LIVE,
        )
        val aged = MatchStatusClock.aged(listOf(placeholder), now = at(25, 1, 0))
        assertEquals(
            "整天都过完了，当天任何时刻开球的比赛都不可能还在打",
            MatchStatus.FINISHED,
            aged.single().status,
        )
    }

    @Test
    fun `占位行的识别依据是时间文案里没有时刻`() {
        assertTrue(
            MatchStatusClock.isDayPlaceholder(
                match(startAtMillis = millis(at(24, 0, 0)), timeLabel = "直播中", status = MatchStatus.LIVE),
            ),
        )
        assertTrue(
            "「已结束」这类文案同样没有 HH:mm，startAtMillis 也是占位",
            MatchStatusClock.isDayPlaceholder(
                match(startAtMillis = millis(at(24, 0, 0)), timeLabel = "已结束", status = MatchStatus.FINISHED),
            ),
        )
        assertFalse(
            "「10-24 07:00」有真实时刻，不是占位",
            MatchStatusClock.isDayPlaceholder(
                match(startAtMillis = millis(at(24, 7, 0)), timeLabel = "10-24 07:00", status = MatchStatus.LIVE),
            ),
        )
    }

    // ---------- 列表级老化 ----------

    @Test
    fun `老化整表时保持顺序与场次数`() {
        val a = match("a", millis(at(24, 7, 0)), "10-24 07:00", MatchStatus.LIVE)
        val b = match("b", millis(at(25, 19, 35)), "10-25 19:35", MatchStatus.UPCOMING)
        val c = match("c", null, "直播中", MatchStatus.LIVE)
        val aged = MatchStatusClock.aged(listOf(a, b, c), now = at(24, 12, 0))
        assertEquals(listOf("a", "b", "c"), aged.map { it.id })
        assertEquals(MatchStatus.FINISHED, aged[0].status)
        assertEquals(MatchStatus.UPCOMING, aged[1].status)
    }
}
