package com.nbalive.app.data.remote

import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatusClock
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * 源站时间一律按**北京时间**解释（review/02-data.md P2-2）。
 *
 * ## 要解决什么
 *
 * 源站是中国站点，它给的 "10-25 07:30" / "19:35" 都是北京时间。
 * 但解析侧一直在用 `ZoneId.systemDefault()`：
 *  - `JrkanParser.parseDateTime` / `parseStatus`
 *  - `JrsParser` 的日期与 HH:mm 合成
 *  - `MatchRepository.normalizeKey`（跨源合并键）
 *
 * 设备时区设成 UTC-5 时，同一份源站数据会被解释成完全不同的时刻 ——
 * 「直播中」判定整体偏移 13 小时，跨源合并键也对不上（同一场变成两张卡）。
 *
 * `MatchStatusClock` 已经固定了 `SOURCE_ZONE = Asia/Shanghai`，但那只覆盖
 * **判定**阶段；解析阶段仍然跟着设备走，所以偏移在进入判定前就产生了。
 *
 * ## 这个测试怎么证明
 *
 * 把 JVM 默认时区切到 UTC-5，然后断言解析出来的 epoch millis
 * 与"按北京时间解释"一致。若实现用 `systemDefault()`，结果会差 13 小时。
 */
class SourceTimeZoneTest {

    private lateinit var original: TimeZone

    @Before
    fun pinNonChinaZone() {
        original = TimeZone.getDefault()
        // 与审查报告里举的例子一致：UTC-5
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(original)
    }

    @Test
    fun `JRKAN 列表时间按北京时间解析 与设备时区无关`() {
        val js = """
            document.write('<ul class="item play active" data-lid="3931527,2,3931527">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">10-22 07:00</li>');
            document.write('  <li class="lab_team_home"><strong class="name">凯尔特人</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">老鹰</strong></li>');
            document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line1", "824761") + '"></a></li>');
            document.write('</ul>');
        """.trimIndent()

        val match = JrkanParser().parseList(js).first()
        val start = match.startAtMillis
        assertNotNull("必须解析出开赛时间", start)

        // 期望值：按北京时间解释 "10-22 07:00"。
        // 年份由解析器的 ±6 个月窗口决定，所以不硬编码年份，
        // 而是断言"该时刻在 Asia/Shanghai 下正好是 07:00"。
        val beijing = java.time.Instant.ofEpochMilli(start!!).atZone(MatchStatusClock.SOURCE_ZONE)
        assertEquals(
            "设备时区是纽约时，源站的 07:00 仍必须被解释成北京时间 07:00",
            7,
            beijing.hour,
        )
        assertEquals(0, beijing.minute)
    }

    @Test
    fun `JRKAN 状态判定不受设备时区影响`() {
        // 用"现在"构造一场刚开赛的直播：北京时间现在往回 30 分钟
        val now = java.time.ZonedDateTime.now(MatchStatusClock.SOURCE_ZONE)
        val startedAt = now.minusMinutes(30)
        val label = "%02d-%02d %02d:%02d".format(
            startedAt.monthValue, startedAt.dayOfMonth, startedAt.hour, startedAt.minute,
        )
        val js = """
            document.write('<ul class="item play active" data-lid="3931527,2,3931527">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">$label</li>');
            document.write('  <li class="lab_team_home"><strong class="name">凯尔特人</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">老鹰</strong></li>');
            document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line1", "824761") + '"></a></li>');
            document.write('</ul>');
        """.trimIndent()

        val match = JrkanParser().parseList(js).first()
        assertEquals(
            "刚开赛 30 分钟的比赛，在设备时区为纽约时也必须判为直播中（实际 ${match.status}）",
            com.nbalive.app.data.model.MatchStatus.LIVE,
            match.status,
        )
    }

    @Test
    fun `JRS 详情页时间按北京时间合成`() {
        val html = """
            <div class="match-info">
              <p class="list-title">2026-10-22</p>
              <p class="match-info-time">19:35</p>
            </div>
        """.trimIndent()
        val parser = JrsParser()
        val time = parser.parseScheduledTime(html)
        assertEquals("19:35", time)

        // correctedStatus 需要 match.startAtMillis 作为"哪一天"的锚点
        // （列表分组日期，00:00 占位），这里按北京时间造一个。
        val dayAnchor = java.time.LocalDate.of(2026, 10, 22)
            .atStartOfDay(MatchStatusClock.SOURCE_ZONE)
            .toInstant()
            .toEpochMilli()
        val match = Match(
            id = "jrs-1", league = com.nbalive.app.data.model.League.NBA, leagueRawName = "NBA",
            homeTeam = "湖人", awayTeam = "凯尔特人", homeLogo = null, awayLogo = null,
            timeLabel = "19:35", startAtMillis = dayAnchor,
            status = com.nbalive.app.data.model.MatchStatus.LIVE,
            signalCount = 0, sources = emptyList(), jrsDetailId = "1",
        )
        val correction = parser.correctedStatus(match, time)
        assertNotNull("应当能算出校正后的开赛时间", correction)
        val correctedStart = correction!!.startAtMillis
        assertNotNull("校正结果必须带开赛时间", correctedStart)
        val beijing = java.time.Instant.ofEpochMilli(correctedStart!!)
            .atZone(MatchStatusClock.SOURCE_ZONE)
        assertEquals("源站 19:35 必须落成北京时间 19:35", 19, beijing.hour)
        assertEquals(35, beijing.minute)
        assertEquals("日期也要落在北京时间的 10-22", 22, beijing.dayOfMonth)
    }
}