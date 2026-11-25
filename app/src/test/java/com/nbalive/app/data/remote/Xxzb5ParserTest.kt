package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.model.isStaticallyResolvablePlayPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * xxzb5.com（跳转至 xixizb.com）解析器判据（替换 88看球，2026-11-25）。
 *
 * 与 88看球不同，本站是 Nuxt 应用，线路不在 base64 里，而在干净 JSON API：
 *  - 窗口赛程 `GET /api/v1/matches/window?from=yyyy-MM-dd&to=yyyy-MM-dd`
 *    → `{items:[{id,league,sport,home,away,startsAt,status,...}]}`
 *  - 线路 `GET /api/v1/matches/{id}/progress`
 *    → `{signals:[{name,url}], match, available, ...}`，`url` 是**裸 m3u8 直连**。
 *
 * 夹具取自 2026-11-25 真机/真网抓包；注释里的域名与 id 都是实测值，不是猜的。
 */
class Xxzb5ParserTest {

    private val parser = Xxzb5Parser()

    // ---------- 列表 ----------

    @Test
    fun `窗口接口解析出篮球场次并挂 progress 线路入口`() {
        val matches = parser.parseList(windowBody())
        assertEquals(4, matches.size) // 解析层不过滤联赛：英超判 OTHER，由 MatchMerger 统一丢弃

        val nba = matches.first { it.id == "xxzb5-2469" }
        assertEquals(League.NBA, nba.league)
        assertEquals(MatchStatus.LIVE, nba.status)
        assertEquals("11-25 09:00", nba.timeLabel)
        assertEquals(-1, nba.signalCount) // 列表阶段线路还没展开

        val entry = nba.sources.single()
        assertEquals(Provider.XXZB5, entry.provider)
        assertEquals(
            "线路入口必须指向 progress 接口，实际：${entry.pageUrl}",
            "https://www.xixizb.com/api/v1/matches/2469/progress",
            entry.pageUrl,
        )
    }

    @Test
    fun `startsAt 的 UTC 时间按北京时间解释`() {
        val nba = parser.parseList(windowBody()).first { it.id == "xxzb5-2469" }
        val expected = java.time.Instant.parse("2026-11-25T01:00:00Z").toEpochMilli()
        assertEquals(expected, nba.startAtMillis)
        // "11-25 09:00" 是 UTC+8 的结果，用设备默认时区解释会整体偏移
        assertTrue(nba.startAtMillis!! > 0)
    }

    @Test
    fun `未开赛与已结束的英文状态也正确映射`() {
        val matches = parser.parseList(windowBody())
        assertEquals(MatchStatus.UPCOMING, matches.first { it.id == "xxzb5-2471" }.status)
        assertEquals(MatchStatus.FINISHED, matches.first { it.id == "xxzb5-2468" }.status)
    }

    @Test
    fun `入口地址跟随真正取到正文的 origin`() {
        val matches = parser.parseList(windowBody(), origin = "https://www.xixizb.com/")
        val entry = matches.first { it.id == "xxzb5-2469" }.sources.single()
        assertTrue(entry.pageUrl.startsWith("https://www.xixizb.com/api/v1/"))
        assertFalse("不能出现双斜杠 origin 漂移", entry.pageUrl.contains("com//api"))
    }

    // ---------- 线路 ----------

    @Test
    fun `progress 接口解出裸 m3u8 线路且全部判为可直连`() {
        val signals = parser.parseSignals(progressBody(qqFirst = false), xxzb5Match())
        assertEquals(3, signals.size)
        assertTrue(signals.all { it.pageUrl.startsWith("https://www.rtmpcdn.com/live/") })
        assertTrue("裸 m3u8 必须判为可直连，否则白白走 WebView", signals.all { it.isDirectHls })
        assertEquals(Provider.XXZB5, signals.first().provider)
    }

    @Test
    fun `腾讯超清排第一 其余保持源站顺序`() {
        // 夹具故意把 腾讯超清2 放在第二位；用户点名要它排第一。
        val signals = parser.parseSignals(progressBody(qqFirst = false), xxzb5Match())
        assertEquals("腾讯超清2", signals.first().label)
        assertEquals(listOf("高清直播1", "原声直播1"), signals.drop(1).map { it.label })
    }

    @Test
    fun `没有腾讯超清时顺序与源站一致`() {
        val signals = parser.parseSignals(progressBodyNoQq(), xxzb5Match())
        assertEquals(listOf("高清直播1", "原声直播1"), signals.map { it.label })
    }

    @Test
    fun `主播解说线路被准入规则整条剔除`() {
        val signals = parser.parseSignals(progressBodyWithAnchor(), xxzb5Match())
        assertEquals(listOf("高清直播1"), signals.map { it.label })
    }

    @Test
    fun `available 为 false 或 signals 缺失时返回空表`() {
        assertTrue(parser.parseSignals("""{"available":false,"signals":[]}""", xxzb5Match()).isEmpty())
        assertTrue(parser.parseSignals("""{"available":true}""", xxzb5Match()).isEmpty())
        assertTrue(parser.parseSignals("garbage", xxzb5Match()).isEmpty())
    }

    // ---------- 裸 m3u8 的直连判据与解析器 ----------

    @Test
    fun `裸 m3u8 判据认 query 形态`() {
        assertTrue(isStaticallyResolvablePlayPage("https://www.rtmpcdn.com/live/qq2.m3u8"))
        assertTrue(
            isStaticallyResolvablePlayPage(
                "https://play.aarray.cn/rrty/hd.m3u8?auth_key=1794703894-0-0-4908f1c2",
            ),
        )
        assertFalse(isStaticallyResolvablePlayPage("https://www.xixizb.com/matches/2469"))
    }

    @Test
    fun `StreamUrlResolver 对裸 m3u8 不再发请求`() = runBlocking {
        val url = "https://www.rtmpcdn.com/live/qq2.m3u8"
        var fetches = 0
        val resolved = StreamUrlResolver().resolve(url) { fetches++; null }
        assertNotNull("裸 m3u8 必须能直接解析，不能返回 null", resolved)
        assertEquals(url, resolved!!.m3u8Url)
        assertEquals("裸 m3u8 的地址已经在手，不该再发一次请求", 0, fetches)
    }

    // ---------- 夹具 ----------

    private fun xxzb5Match() = Match(
        id = "xxzb5-2469",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "奇才",
        awayTeam = "凯尔特人",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "11-25 09:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 1,
        sources = listOf(
            StreamSource(
                id = "xxzb5-2469-s0",
                label = "直播信号",
                provider = Provider.XXZB5,
                pageUrl = "https://www.xixizb.com/api/v1/matches/2469/progress",
            ),
        ),
    )

    /** 2026-11-25 真实窗口接口的一个精简切片（NBA / CBA / 足球各一场）。 */
    private fun windowBody() = """
    {"items":[
      {"id":2468,"league":"NBA","leagueSlug":"nba","sport":"篮球",
       "home":{"name":"魔术","logoPath":"https://www.rtmpcdn.com/uploads/a.png"},
       "away":{"name":"活塞","logoPath":"https://www.rtmpcdn.com/uploads/b.png"},
       "startsAt":"2026-11-25T00:00:00Z","status":"finished"},
      {"id":2469,"league":"NBA","leagueSlug":"nba","sport":"篮球",
       "home":{"name":"奇才","logoPath":"https://www.rtmpcdn.com/uploads/c.png"},
       "away":{"name":"凯尔特人","logoPath":"https://www.rtmpcdn.com/uploads/d.png"},
       "startsAt":"2026-11-25T01:00:00Z","status":"live"},
      {"id":2471,"league":"CBA","leagueSlug":"cba","sport":"篮球",
       "home":{"name":"辽宁","logoPath":null},
       "away":{"name":"广东","logoPath":null},
       "startsAt":"2026-11-25T14:00:00Z","status":"upcoming"},
      {"id":9999,"league":"英超","leagueSlug":"premier-league","sport":"足球",
       "home":{"name":"阿森纳","logoPath":null},
       "away":{"name":"曼城","logoPath":null},
       "startsAt":"2026-11-25T20:00:00Z","status":"upcoming"}
    ],"hasMoreBefore":false,"hasMoreAfter":true}
    """.trimIndent()

    /** progress 接口精简切片；qqFirst=false 时把腾讯超清放第二位，专门测排序。 */
    private fun progressBody(qqFirst: Boolean) = """
    {"available":true,"signals":[
      ${if (qqFirst) qq() else hd()},
      ${if (qqFirst) hd() else qq()},
      {"name":"原声直播1","url":"https://www.rtmpcdn.com/live/ad7c6925fb1a74a4a0528b49eeb0d50d19a1b2af5466b81057366f975c9c2099.m3u8"}
    ]}
    """.trimIndent()

    private fun qq() =
        """{"name":"腾讯超清2","url":"https://www.rtmpcdn.com/live/qq2.m3u8"}"""

    private fun hd() =
        """{"name":"高清直播1","url":"https://www.rtmpcdn.com/live/9c3959e60c02bc33dd0aaad3334d0f92758a129575644189e723227e07b97a07.m3u8"}"""

    private fun progressBodyNoQq() = """
    {"available":true,"signals":[${hd()},
      {"name":"原声直播1","url":"https://www.rtmpcdn.com/live/origin.m3u8"}]}
    """.trimIndent()

    private fun progressBodyWithAnchor() = """
    {"available":true,"signals":[
      {"name":"主播解说①","url":"https://www.rtmpcdn.com/live/anchor.m3u8"},
      ${hd()}
    ]}
    """.trimIndent()
}
