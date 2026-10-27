package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.isPostponed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延期场次的**状态文案**（review/02-data.md P2-10）。
 *
 * ## 要解决什么
 *
 * `MatchStatus.fromSource` 把"延期/推迟/待定"归为 `UPCOMING`，
 * 界面上一律显示「未开赛」。用户按时间找过去发现没有直播，
 * 以为 App 数据错了 —— 实际是这场被延期了。
 *
 * 数据层已经把事实暴露出来了（`Match.isPostponed`，见 `Match.kt` 的说明：
 * 不加 `POSTPONED` 枚举是因为那是跨层改动，会让所有 `when(status)` 编译不过），
 * 但**没有任何 UI 读它** —— 又一个"算好了没人用"。
 *
 * ## 规则
 *
 * - 延期场次文案显示「延期」，与「未开赛」区分；
 * - 只影响**文案**，不影响配色/排序（仍按未开赛排，用户还能按时间去等）；
 * - 非延期场次文案完全不变（不能把普通未开赛也标成延期）。
 */
class PostponedLabelTest {

    private fun match(timeLabel: String, status: MatchStatus = MatchStatus.UPCOMING) = Match(
        id = "m1", league = League.NBA, leagueRawName = "NBA",
        homeTeam = "湖人", awayTeam = "勇士", homeLogo = null, awayLogo = null,
        timeLabel = timeLabel, startAtMillis = null, status = status,
        signalCount = 0, sources = emptyList(), jrsDetailId = null,
    )

    @Test
    fun `延期场次的标签是延期 而不是未开赛`() {
        val m = match("延期")
        assertTrue("夹具本身应当被识别为延期", m.isPostponed)
        assertEquals("延期", StatusLabel.of(m.status, m.isPostponed))
    }

    @Test
    fun `推迟与待定同样显示延期`() {
        for (label in listOf("推迟", "待定", "延期举行", "比赛推迟")) {
            val m = match(label)
            assertEquals(
                "「$label」应当显示为延期",
                "延期",
                StatusLabel.of(m.status, m.isPostponed),
            )
        }
    }

    @Test
    fun `普通未开赛不受影响`() {
        val m = match("10-25 07:30")
        assertFalse(m.isPostponed)
        assertEquals("未开赛", StatusLabel.of(m.status, m.isPostponed))
    }

    @Test
    fun `直播中与已结束不受影响`() {
        // 延期标记只在"未开赛"语义下才有意义：一场已经打完的比赛
        // timeLabel 里不会出现"延期"，但万一出现也不该把状态改坏
        assertEquals(
            "直播中的文案不能被延期覆盖",
            "直播中",
            StatusLabel.of(MatchStatus.LIVE, isPostponed = true),
        )
        assertEquals(
            "已结束的文案不能被延期覆盖",
            "已结束",
            StatusLabel.of(MatchStatus.FINISHED, isPostponed = true),
        )
    }

    @Test
    fun `文案与状态默认映射一致 避免两处各写一份`() {
        // StatusLabel 必须与 StatusChip 原来的 when 完全一致，
        // 否则会出现"列表卡片写未开赛、详情页写别的"
        assertEquals("直播中", StatusLabel.of(MatchStatus.LIVE, isPostponed = false))
        assertEquals("未开赛", StatusLabel.of(MatchStatus.UPCOMING, isPostponed = false))
        assertEquals("已结束", StatusLabel.of(MatchStatus.FINISHED, isPostponed = false))
    }
}