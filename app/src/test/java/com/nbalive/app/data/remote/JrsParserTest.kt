package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JrsParserTest {

    private val parser = JrsParser()

    /** 真实页面结构（已验证 jrszbt.com 首页）。 */
    private val listHtml = """
        <div class="list">
          <p class="list-title">2026-10-21</p>
          <a href="/live/364085.html" class="list-con">
            <div class="list-team-con">
              <img class="list-team-image" src="http://example.com/zj.jpg" alt="浙江" />
              <p class="list-team-name">浙江</p>
            </div>
            <div class="list-info-con">
              <p class="list-info-league">CBA</p>
              <p class="list-info-time">直播中</p>
            </div>
            <div class="list-team-con">
              <img class="list-team-image" src="http://example.com/sd.jpg" alt="山东" />
              <p class="list-team-name">山东</p>
            </div>
          </a>
        </div>
    """.trimIndent()

    @Test
    fun `parses teams league and live status`() {
        val matches = parser.parseList(listHtml)
        assertEquals(1, matches.size)
        val m = matches.first()
        assertEquals("浙江", m.homeTeam)
        assertEquals("山东", m.awayTeam)
        assertEquals("CBA", m.leagueRawName)
        assertEquals(League.CBA, m.league)
        assertEquals(MatchStatus.LIVE, m.status)
        assertEquals("http://example.com/zj.jpg", m.homeLogo)
        assertEquals("http://example.com/sd.jpg", m.awayLogo)
    }

    @Test
    fun `parses signal list from detail page`() {
        val detailHtml = """
            <div class="match-iframe">
              <iframe src="http://play.sportsteam368.com/play/vxt/?id=1&id2=" id="pp"></iframe>
            </div>
            <div class="liveshow">
              <a href="javascript:" data-url="http://play.sportsteam368.com/play/vxt/?id=1&id2=" class="toLive nl picked">信号1</a>
              <a href="http://play.sportsteam368.com/play/vxt/?id=1&id2=" target="_blank" class="toLive">信号2</a>
              <a href="http://play.sportsteam368.com/play/mgxl/?id=2&id2=" target="_blank" class="toLive">信号3</a>
              <a href="https://www.fszb667.com/?agent=j" target="_blank" class="toLive">更多信号</a>
              <a href="https://hvpeugs6.net/" target="_blank" class="toLive">备用地址</a>
            </div>
        """.trimIndent()

        val match = parser.parseList(listHtml).first()
        val signals = parser.parseDetail(detailHtml, match)

        // 信号1 与 信号2 是同一地址 -> 去重；信号3 是另一条线路 -> 保留；推广外链 -> 过滤
        assertEquals(2, signals.size)
        assertEquals("信号1", signals[0].label)
        assertEquals("信号3", signals[1].label)
        assertTrue(signals.none { it.pageUrl.contains("fszb667") })
        assertTrue(signals.none { it.pageUrl.contains("hvpeugs6") })
    }

    @Test
    fun `falls back to iframe src when no data-url exists`() {
        val detailHtml = """
            <div class="match-iframe">
              <iframe src="http://play.sportsteam368.com/play/vxt/?id=9&id2=" id="pp"></iframe>
            </div>
        """.trimIndent()

        val match = parser.parseList(listHtml).first()
        val signals = parser.parseDetail(detailHtml, match)
        assertEquals(1, signals.size)
        assertTrue(signals.first().pageUrl.contains("id=9"))
    }

    // ---------- 详情页计划开赛时间（review/02-data.md P1-8） ----------

    /**
     * 源站给这个 `<p>` 加一个状态 class（`live` / `picked` / `active`）是极常见的改版动作。
     *
     * 旧正则要求 `match-info-time"` —— class 值**必须以它结尾且紧跟引号**，
     * 于是 `class="match-info-time live"` 直接返回 null。
     * 后果不是少一场，而是 `correctedStatus` 全部拿到 null，
     * 列表里当天**所有**已结束场次继续亮红「直播中」（比"超出 8 场"覆盖面更大）。
     */
    @Test
    fun `计划开赛时间容忍 class 上追加的状态 token`() {
        val cases = listOf(
            """<p class="match-info-time">19:35</p>""" to "19:35",
            """<p class="match-info-time live">19:35</p>""" to "19:35",
            """<p class="match-info-time picked">07:00</p>""" to "07:00",
            """<p class="foo match-info-time">19:35</p>""" to "19:35",
            """<p class="match-info-time live" id="t">19:35</p>""" to "19:35",
        )
        for ((html, expected) in cases) {
            assertEquals(
                "形态 [$html] 解析不出计划开赛时间",
                expected,
                parser.parseScheduledTime(html),
            )
        }
    }

    @Test
    fun `计划开赛时间在文案不是时刻时返回 null`() {
        // 不猜、不误杀：文案型内容（"直播中"）拿不到 HH:mm 就该返回 null
        assertEquals(null, parser.parseScheduledTime("""<p class="match-info-time">直播中</p>"""))
        assertEquals(null, parser.parseScheduledTime("""<p class="other">19:35</p>"""))
    }
}
