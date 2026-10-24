package com.nbalive.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态关键词的判定顺序。
 *
 * 源站的文案会同时含多个关键词（"直播已结束" / "直播未开始"），
 * 判定必须从"确定"到"不确定"，否则会出现"直播未开始"被判成直播中。
 */
class MatchStatusTest {

    @Test
    fun `finished wins over live`() {
        assertEquals(MatchStatus.FINISHED, MatchStatus.fromSource("直播已结束"))
        assertEquals(MatchStatus.FINISHED, MatchStatus.fromSource("已结束"))
        assertEquals(MatchStatus.FINISHED, MatchStatus.fromSource("完场"))
    }

    @Test
    fun `not started wins over live`() {
        // 回归：以前这里会被 "直播" 规则判成 LIVE
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("直播未开始"))
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("未开赛"))
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("延期"))
    }

    @Test
    fun `live text is live`() {
        assertEquals(MatchStatus.LIVE, MatchStatus.fromSource("直播中"))
        assertEquals(MatchStatus.LIVE, MatchStatus.fromSource("直播"))
    }

    @Test
    fun `plain schedule is upcoming`() {
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("10-22 07:00"))
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource(""))
    }

    @Test
    fun `即将开赛不能判成直播中`() {
        // 回归："开赛"规则会把"即将开赛"也判成 LIVE，
        // 于是一场还没开始的球在列表里就亮红了
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("即将开赛"))
        assertEquals(MatchStatus.UPCOMING, MatchStatus.fromSource("19:00 即将开赛"))
        // 单纯的"开赛"仍表示已经开打
        assertEquals(MatchStatus.LIVE, MatchStatus.fromSource("开赛"))
    }
}
