package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.FetchResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表页"直播中"校正的回归测试。
 *
 * 实测回归（v1.2.8，用户截图 2026-10-24 23:01）：
 * 三场 19:35 开球的 CBA 早已结束，列表里仍标着「直播中」。
 * 这三场在 JRS 列表行里的 `list-info-time` 文本就是"直播中"
 * （时间被状态文案顶掉了），解析出来的 startAtMillis 是当天 00:00 占位。
 *
 * 校正链路本身是存在的（[MatchRepository.correctLiveStatuses] + 详情页），
 * 问题是它只对 `status == LIVE` 的场次跑，且依赖详情页里
 * `.match-info-time` 的 HH:mm。这里钉住三条契约：
 *
 *  1) 详情页拿到 HH:mm 后，超 3.5 小时判已结束
 *  2) 详情页拉不到（源站 404 / 超时）时**不能**把场次留在 LIVE
 *  3) JRS"直播中"行（startAtMillis 是 00:00 占位）也要能校正
 */
class LiveStatusCorrectionTest {

    /**
     * ⚠️ 必须用**生产解释时间所用的那个时区**（`MatchStatusClock.SOURCE_ZONE`），
     * 不能用 `ZoneId.systemDefault()`（审计「未验证的怀疑 #1」）。
     *
     * 原来用设备默认时区造 `startAtMillis` / `now`，而生产在 `SOURCE_ZONE` 里解释它们 ——
     * 于是"现在 20:00、日期还没过完"这种断言**只在北京时间（东八区）机器上成立**：
     * 把单测时区钉成 UTC 后，`details 页没时间但日期还是今天时保留直播中` 等 2 条立刻变红
     * （机器时区一变，夹具与生产就差 8 小时）。现在两边同一个时区，任何机器上都成立。
     */
    private val zone: ZoneId = MatchStatusClock.SOURCE_ZONE

    /**
     * 夹具用**相对今天**的日期，不用写死的 2026-10-24。
     *
     * ⚠️ 写死日期会让"日期已过完"这类用例**依赖机器时钟晚于那一天**
     * （审计「未验证的怀疑 #4」）：机器日期回拨到 2026-10-24 之前时它会假红。
     */
    private fun today(): LocalDate = LocalDate.now(zone)

    /** 构造一场 JRS"直播中"行：列表行没有 HH:mm，startAtMillis 是当天 00:00 */
    private fun liveJrsRow(
        id: String,
        teams: Pair<String, String>,
        day: LocalDate = today(),
    ): Match {
        val start = day.atStartOfDay(zone)
        return Match(
            id = id,
            league = League.CBA,
            leagueRawName = "CBA",
            homeTeam = teams.first,
            awayTeam = teams.second,
            homeLogo = null,
            awayLogo = null,
            timeLabel = "直播中",
            startAtMillis = start.toInstant().toEpochMilli(),
            status = MatchStatus.LIVE,
            signalCount = -1,
            sources = emptyList(),
            jrsDetailId = id.removePrefix("jrs-"),
        )
    }

    /** 详情页正文：只有 `.match-info-time` 里的 HH:mm 是有效信息 */
    private fun detailPage(hhmm: String): String = """
        <html><body>
        <div class="con-match">
          <div class="match-info-con">
            <p class="match-info-league">CBA</p>
            <p class="match-info-time">$hhmm</p>
          </div>
        </div>
        <div class="liveshow">
          <a class="toLive" data-url="https://play.sportsteam368.com/play/kbs/?id=1">信号1</a>
        </div>
        </body></html>
    """.trimIndent()

    private fun repo(): MatchRepository = MatchRepository()

    @Test
    fun `详情页时间超三小时半判已结束`() {
        // 19:35 开球，现在是 23:10 -> 已过 3h35m，应判已结束
        val match = liveJrsRow("jrs-1", "上海" to "福建")
        val fixed = com.nbalive.app.data.remote.JrsParser().correctedStatus(
            match = match,
            scheduledTime = "19:35",
            now = today().atTime(23, 10).atZone(zone),
        )
        assertEquals(
            "开球 3.5 小时之后必须判已结束",
            MatchStatus.FINISHED,
            fixed?.status,
        )
    }

    @Test
    fun `详情页时间还在窗口内保持直播中`() {
        // 19:35 开球，现在 21:40 -> 2h05m，仍在直播窗口内
        val match = liveJrsRow("jrs-1", "上海" to "福建")
        val fixed = com.nbalive.app.data.remote.JrsParser().correctedStatus(
            match = match,
            scheduledTime = "19:35",
            now = today().atTime(21, 40).atZone(zone),
        )
        assertEquals(
            MatchStatus.LIVE,
            fixed?.status,
        )
    }

    @Test
    fun `拉不到详情页时不能把场次留在直播中`() {
        // 源站 404 / 超时：详情页正文拿不到，scheduledTime 为 null。
        // 老链路在这种情况下原样返回 LIVE，就是用户看到的 bug。
        val match = liveJrsRow("jrs-1", "上海" to "福建")
        val fixed = com.nbalive.app.data.remote.JrsParser().correctedStatus(
            match = match,
            scheduledTime = null,
            now = today().atTime(23, 10).atZone(zone),
        )
        assertEquals(
            "拿不到详情页时间时不该擅自判定，但也不能算已结束",
            null,
            fixed,
        )
    }

    @Test
    fun `校正只处理直播中的场次`() {
        // 未开赛/已结束的场次不该被详情页改动
        val finished = liveJrsRow("jrs-1", "上海" to "福建")
            .copy(status = MatchStatus.FINISHED)
        val fixed = com.nbalive.app.data.remote.JrsParser().correctedStatus(
            match = finished,
            scheduledTime = "19:35",
            now = today().atTime(23, 10).atZone(zone),
        )
        assertEquals(
            "已结束的场次不该被校正成别的状态",
            null,
            fixed,
        )
    }

    /**
     * 端到端：详情页拿不到时间时，已过完的日期必须判已结束。
     *
     * 这条正是用户报的 bug：19:35 开球、23:01 还在列表里标「直播中」。
     * 详情页这条路要么被 TTL 缓存挡了、要么源站当时没给时间，
     * 老代码 `else -> m` 把场次原样留在 LIVE。
     */
    @Test
    fun `详情页没时间且日期已过完时判已结束`() {
        val repo = MatchRepository()
        // ⚠️ 夹具必须是**昨天**：这条用例不传 `now`（用真实时钟），
        // 写死日期或写今天都会让"整天都过去了"这个前提随机器日期变化
        // （审计「未验证的怀疑 #4」）。
        val row = liveJrsRow("jrs-1", "上海" to "福建", day = today().minusDays(1))
        // 详情页返回一个没有 .match-info-time 的正文（源站改版/超时占位）
        val htmlWithoutTime = "<html><body><p>no time here</p></body></html>"
        val result = kotlinx.coroutines.runBlocking {
            repo.correctLiveStatusesForTest(
                matches = listOf(row),
                htmlFor = { htmlWithoutTime },
            )
        }
        assertEquals(
            "整天都过去了，19:35 开球的比赛不可能还在打",
            MatchStatus.FINISHED,
            result.single().status,
        )
    }

    @Test
    fun `详情页没时间但日期还是今天时保留直播中`() {
        val repo = MatchRepository()
        val row = liveJrsRow("jrs-1", "上海" to "福建")
        val result = kotlinx.coroutines.runBlocking {
            repo.correctLiveStatusesForTest(
                matches = listOf(row),
                htmlFor = { "<html><body><p>no time here</p></body></html>" },
                // 同一天 20:00：日期还没过完，不该判已结束（可能是 19:35 开球、正在打）
                now = today().atTime(20, 0).atZone(zone),
            )
        }
        assertEquals(
            "日期还是今天就该保留源站状态，宁可多标一会儿也不能误杀",
            MatchStatus.LIVE,
            result.single().status,
        )
    }
}
