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
}
