package com.nbalive.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延期场次的识别（review/02-data.md P2-10）。
 *
 * 源站把"延期 / 推迟 / 待定"的场次和"未开赛"用同一套文案归类，
 * 界面上一律显示「未开赛」。用户按时间找过去发现没有直播 —— 这是真实投诉场景。
 *
 * ⚠️ 这里刻意**没有**在 [MatchStatus] 里加 `POSTPONED`：
 * `StatusChip` 等处是穷尽 `when (status)`，加枚举值会让 UI 层编译不过，
 * 那是跨层改动。数据层先把"这是延期场次"这个事实暴露出来，
 * UI 决定怎么呈现（不同文案/颜色）时再消费。
 */
class PostponedMatchTest {

    private fun match(timeLabel: String, status: MatchStatus = MatchStatus.fromSource(timeLabel)) =
        Match(
            id = "m1",
            league = League.CBA,
            leagueRawName = "CBA",
            homeTeam = "辽宁",
            awayTeam = "广东",
            homeLogo = null,
            awayLogo = null,
            timeLabel = timeLabel,
            startAtMillis = null,
            status = status,
            signalCount = 0,
            sources = emptyList(),
        )

    @Test
    fun `延期与推迟与待定都算延期场次`() {
        listOf("延期", "推迟", "待定", "比赛延期", "19:35 推迟").forEach { label ->
            assertTrue("「$label」应识别为延期场次", match(label).isPostponed)
        }
    }

    @Test
    fun `正常时间文案不算延期`() {
        listOf("10-22 07:00", "19:35", "直播中", "已结束", "").forEach { label ->
            assertFalse("「$label」不该被当成延期", match(label).isPostponed)
        }
    }

    /**
     * 延期场次在状态上仍然落在 UPCOMING。
     *
     * 这条钉住"不改枚举"的前提：`fromSource` 的判定顺序里
     * "延期/推迟/待定 -> UPCOMING" 在 "直播" 之前，所以延期文案里
     * 即使带「直播」字样也不会被误判成直播中。
     */
    @Test
    fun `延期场次状态仍是未开赛 不会亮红`() {
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("延期"))
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("直播延期"))
        assertTrue(match("直播延期").isPostponed)
        assertEquals(MatchStatus.UPCOMING, match("直播延期").status)
    }
}
