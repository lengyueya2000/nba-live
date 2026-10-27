package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JrkanParserTest {

    private val parser = JrkanParser()

    /** 真实 index.js 中单场赛事的 document.write 结构（已验证）。 */
    private val indexJs = """
        document.write('<ul class="item play d-touch active hot" data-lid="3931527,2,3931527" data-stype="zqlq">');
        document.write('  <li class="lab_events" style="background-color:#FF0000"><span class="name">NBA</span></li>');
        document.write('  <li class="lab_time">10-22 07:00</li>');
        document.write('  <li class="lab_team_home"><strong class="name">凯尔特人</strong><em class="bf" title="2"></em><span class="avatar"><img loading="lazy" class="" src="https://cdn.sportnanoapi.com/basketball/team/0dc24d08ef0b5584e0b70f967db64b36.png" referrerpolicy="no-referrer" style=""></span></li>');
        document.write('  <li class="lab_vs active"><div class="sub_status"><em class="spin_ant"></em></div></li>');
        document.write('  <li class="lab_team_away"><strong class="name">老鹰</strong><em class="bf" title="2"></em><span class="avatar"><img loading="lazy" class="" src="https://cdn.sportnanoapi.com/basketball/team/4e67b14905a05af9e1bd04406bf8690f.png"  referrerpolicy="no-referrer" style=""></span></li>');
        document.write('  <li class="lab_bc"><span></span></li>');
        document.write('  <li class="lab_channel">');
        document.write('    <a class="item ok type1 me" data-urlid="546190" href="' + getPlayUrl("line1", "824761") + '" data-play=""><em class="icon-play-circle"></em><strong>直播①</strong></a>');
        document.write('    <a class="item ok type1 me" data-urlid="546190" href="' + getPlayUrl("line2", "824761") + '" data-play=""><em class="icon-play-circle"></em><strong>直播②</strong></a>');
        document.write('    <a class="item ok type1 me" data-urlid="546190" href="' + getPlayUrl("line3", "824761") + '" data-play=".html"><em class="icon-play-circle"></em><strong>直播③</strong></a>');
        document.write('  </li>');
        document.write('</ul>');
    """.trimIndent()

    /**
     * 上游给 li 多加属性 / 换行时也要能解析出来。
     *
     * 回归：早先 home/away 用的是写死字面量 `lab_team_home"><strong class="name">`，
     * 源站哪天在 li 上加个 class 就整场（其实是**全部**场次）静默消失。
     */
    @Test
    fun `tolerates extra attributes on team li`() {
        val variant = indexJs
            .replace(
                """<li class="lab_team_home"><strong class="name">凯尔特人</strong>""",
                """<li class="lab_team_home hot" data-x="1">
                     <strong class="name">凯尔特人</strong>""",
            )
            .replace(
                """<li class="lab_team_away"><strong class="name">老鹰</strong>""",
                """<li class="lab_team_away hot" data-x="2">
                     <strong class="name">老鹰</strong>""",
            )
            .replace(
                """<li class="lab_events" style="background-color:#FF0000"><span class="name">NBA</span></li>""",
                """<li class="lab_events hot" style="background-color:#FF0000">
                     <span class="name">NBA</span></li>""",
            )
            .replace(
                """<li class="lab_time">10-22 07:00</li>""",
                """<li class="lab_time" data-t="1">10-22 07:00</li>""",
            )

        val m = parser.parseList(variant).firstOrNull()
        assertTrue("上游加属性不能让整场消失", m != null)
        assertEquals("凯尔特人", m!!.homeTeam)
        assertEquals("老鹰", m.awayTeam)
        assertEquals("NBA", m.leagueRawName)
        assertEquals("10-22 07:00", m.timeLabel)
    }

    @Test
    fun `parses NBA match with league teams and time`() {
        val matches = parser.parseList(indexJs)
        assertEquals(1, matches.size)
        val m = matches.first()
        assertEquals("凯尔特人", m.homeTeam)
        assertEquals("老鹰", m.awayTeam)
        assertEquals("NBA", m.leagueRawName)
        assertEquals(League.NBA, m.league)
        assertEquals("10-22 07:00", m.timeLabel)
        assertTrue(m.homeLogo!!.contains("0dc24d08ef0b5584e0b70f967db64b36"))
    }

    @Test
    fun `builds play page on the verified live host`() {
        val m = parser.parseList(indexJs).first()
        // 列表页只给一个播放页，具体线路进详情页再展开
        assertEquals(1, m.sources.size)
        assertEquals(
            "http://play.sportsteam356.com/play/steam824761.html",
            m.sources[0].pageUrl,
        )
    }

    @Test
    fun `parses CBA league`() {
        val cbaJs = indexJs
            .replace("data-lid=\"3931527,2,3931527\"", "data-lid=\"3941083,2,3941083\"")
            .replace("<span class=\"name\">NBA</span>", "<span class=\"name\">CBA</span>")
        val m = parser.parseList(cbaJs).first()
        assertEquals(League.CBA, m.league)
    }

    // ---- 源站改版容错（review/02-data.md P1-7 / P2-1） ----
    //
    // 旧实现是「手写正则切 HTML」，三个脆弱点都源于写死了字面量：
    //  1. 块起点写死 `<ul class="item play"`；
    //  2. `data-lid` 严格三段纯数字；
    //  3. 队名正则用 DOT_MATCHES_ALL 跨 li 匹配。
    // 任何一个被上游改掉，结果都是**整表归零且静默**。
    // 改用 Jsoup 之后，块边界交给 HTML 解析器，字段交给 CSS 类选择器。

    @Test
    fun `块起点写法变了也要能解析`() {
        val variants = mapOf(
            "属性顺序颠倒" to indexJs.replace(
                """<ul class="item play d-touch active hot" data-lid="3931527,2,3931527" data-stype="zqlq">""",
                """<ul data-lid="3931527,2,3931527" data-stype="zqlq" class="item play d-touch active hot">""",
            ),
            "class 用单引号" to indexJs.replace(
                """<ul class="item play d-touch active hot" """,
                """<ul class='item play d-touch active hot' """,
            ),
            "class 里多一个空格" to indexJs.replace(
                """<ul class="item play d-touch active hot" """,
                """<ul class="item  play d-touch active hot" """,
            ),
            "class 顺序颠倒" to indexJs.replace(
                """<ul class="item play d-touch active hot" """,
                """<ul class="play item d-touch active hot" """,
            ),
        )
        for ((name, js) in variants) {
            val m = parser.parseList(js).firstOrNull()
            assertTrue("[$name] 上游改版后整场消失", m != null)
            assertEquals("[$name] 队名解析错", "凯尔特人", m!!.homeTeam)
            assertEquals("[$name] 队名解析错", "老鹰", m.awayTeam)
        }
    }

    @Test
    fun `data-lid 多出一段时仍能解析`() {
        // 上游给 data-lid 加第四段（eventId,sportId,leagueId,matchId）是常见改法
        val js = indexJs.replace("data-lid=\"3931527,2,3931527\"", "data-lid=\"3931527,2,3931527,9\"")
        val m = parser.parseList(js).firstOrNull()
        assertTrue("data-lid 多一段不能让整场消失", m != null)
        assertEquals("凯尔特人", m!!.homeTeam)
        // eventId 取第一段
        assertTrue("id 应由 data-lid 第一段构成：${m.id}", m.id.contains("3931527"))
    }

    @Test
    fun `主队没有 strong name 时不能串味到客队`() {
        // review/02-data.md P2-1：旧正则是 DOT_MATCHES_ALL 跨 li 匹配，
        // 主队 li 里没有 strong.name 时会一路扫到客队 li，
        // 把客队名当成主队名 —— 产出一张「篮网 vs 篮网」的卡片。
        val js = """
            document.write('<ul class="item play" data-lid="777,2,777" data-stype="zqlq">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">10-23 10:00</li>');
            document.write('  <li class="lab_team_home"><em class="tbd">待定</em></li>');
            document.write('  <li class="lab_team_away"><strong class="name">篮网</strong></li>');
            document.write('</ul>');
        """.trimIndent()
        val m = parser.parseList(js).firstOrNull()
        assertTrue("不该整场消失", m != null)
        assertEquals("主队不能被客队名顶替", "待定", m!!.homeTeam)
        assertEquals("篮网", m.awayTeam)
        assertTrue("主客队不能解析成同一支队", m.homeTeam != m.awayTeam)
    }

    @Test
    fun `队名容器换成别的标签也能解析`() {
        // 源站把 <strong> 换成 <span>：CSS 选择器只认 class，不认标签
        val js = indexJs
            .replace(
                """<strong class="name">凯尔特人</strong>""",
                """<span class="name">凯尔特人</span>""",
            )
            .replace(
                """<strong class="name">老鹰</strong>""",
                """<span class="name">老鹰</span>""",
            )
        val m = parser.parseList(js).firstOrNull()
        assertTrue("换标签不能让整场消失", m != null)
        assertEquals("凯尔特人", m!!.homeTeam)
        assertEquals("老鹰", m.awayTeam)
    }

    @Test
    fun `document write 的引号形式变了也能解析`() {
        // 源站改成 document.write("...") 双引号形式
        val js = indexJs.replace("document.write('", "document.write(\"")
            .replace("');", "\");")
        val m = parser.parseList(js).firstOrNull()
        assertTrue("换引号形式不能让整场消失", m != null)
        assertEquals("凯尔特人", m!!.homeTeam)
    }

    /** 真实播放页 sub_playlist 结构（已实测）。 */
    private val playPage = """
        <div class="sub_playlist">
          <div class="sub_channel">
            <a class="item play ok me" target="myplayer" data-group="云直播①" data-providerid="27592"
               data-play="/play/sm.html?id=439&id2=" href="" data-urlid="549270">
              <em class="icon-play-circle"></em><strong>主播解说①</strong></a>
            <a class="item ok me" target="myplayer" data-group="云直播③" data-providerid="27608"
               data-play="/play/sm.html?id=919&id2=" href="" data-urlid="553582">
              <em class="icon-play-circle"></em><strong>主播解说②</strong></a>
            <a class="item ok me" target="myplayer" data-group="云直播③" data-providerid="27608"
               data-play="/play/wen/?id=112fae77aa2b3f9e768036b61b86d589&id2=" href="" data-urlid="553582">
              <em class="icon-play-circle"></em><strong>高清直播③</strong></a>
            <a class="item ok me" target="myplayer" data-group="云直播③" data-providerid="27608"
               data-play="/play/pao/?id=39410882&id2=" href="" data-urlid="553582">
              <em class="icon-play-circle"></em><strong>高清直播④</strong></a>
            <a class="item ok me" data-play="=&id2=" href=""><strong>占位</strong></a>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun `expands play page and drops streamer lines`() {
        val channels = parser.parseChannels(playPage, "jrkan-3941088")

        // 含"主播/解说"的线路被删除，只剩非主播线路
        assertEquals(2, channels.size)
        assertTrue(channels.none { it.label.contains("主播") })
        assertTrue(channels.none { it.label.contains("解说") })

        // 保留的是高清直播③④
        assertEquals("高清直播③", channels[0].label)
        assertEquals("高清直播④", channels[1].label)
        assertTrue(channels[0].pageUrl.contains("/play/wen/"))
        assertTrue(channels[1].pageUrl.contains("/play/pao/"))
    }

    @Test
    fun `marks sm channels as direct hls`() {
        val html = """
            <a data-play="/play/sm.html?id=439&id2="><strong>高清直播①</strong></a>
        """.trimIndent()
        val channels = parser.parseChannels(html, "jrkan-1")
        assertEquals(1, channels.size)
        assertTrue(channels[0].pageUrl.contains("/play/sm.html"))
    }

    // ---- 回归：lab_vs active / spin_ant 是静态模板类，不携带状态 ----
    // 实测源站 75/75 场次恒带这两个类（含未开赛 3 天后的、昨天已结束的）。
    // 拿它判"进行中"会让列表每一行都亮红"直播中"。

    @Test
    fun `static active class does not mark a future match as live`() {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        val label = "%02d-%02d 19:35".format(tomorrow.monthValue, tomorrow.dayOfMonth)
        val m = parser.parseList(statusBlock(label)).first()
        assertEquals(MatchStatus.UPCOMING, m.status)
    }

    @Test
    fun `static active class does not mark an old match as live`() {
        // 5 小时前开赛：超过篮球赛 3.5 小时上限 -> 已结束
        val past = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).minusHours(5)
        val label = "%02d-%02d %02d:%02d".format(past.monthValue, past.dayOfMonth, past.hour, past.minute)
        val m = parser.parseList(statusBlock(label)).first()
        assertEquals(MatchStatus.FINISHED, m.status)
    }

    @Test
    fun `match that tipped off within three and a half hours is live`() {
        // 特征测试：去掉静态类判据后，进行中的场次仍必须判成直播中
        val started = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).minusHours(1)
        val label = "%02d-%02d %02d:%02d".format(started.monthValue, started.dayOfMonth, started.hour, started.minute)
        val m = parser.parseList(statusBlock(label)).first()
        assertEquals(MatchStatus.LIVE, m.status)
    }

    /**
     * 上游给 li 多加属性 / 换行时也要能解析出来。
     *
     * 回归：早先 home/away 用的是写死字面量 `lab_team_home"><strong class="name">`，
     * 源站哪天在 li 上加个 class 就整场（其实是**全部**场次）静默消失。
     */
    private fun statusBlock(timeLabel: String): String = """
        document.write('<ul class="item play d-touch active hot" data-lid="1,2,3" data-stype="zqlq">');
        document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
        document.write('  <li class="lab_time">$timeLabel</li>');
        document.write('  <li class="lab_team_home"><strong class="name">A</strong></li>');
        document.write('  <li class="lab_vs active"><div class="sub_status"><em class="spin_ant"></em></div></li>');
        document.write('  <li class="lab_team_away"><strong class="name">B</strong></li>');
        document.write('</ul>');
    """.trimIndent()

    // ---- 回归：时间串没有年份，跨年时必须落在 ±6 个月窗口内 ----

    @Test
    fun `date without year resolves to the nearest occurrence within six months`() {
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        // 每个月都试一遍：无论今天落在几月，解析结果都必须离今天不超过 6 个月
        (0..11).forEach { monthOffset ->
            val target = today.plusMonths(monthOffset.toLong())
            val label = "%02d-%02d 19:35".format(target.monthValue, target.dayOfMonth)
            val parsed = parseStartDate(label)
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, parsed)
            assertTrue(
                "$label 解析成 $parsed，距今天 $today 已超出 ±6 个月窗口（$days 天）",
                kotlin.math.abs(days) <= 200,
            )
        }
    }

    @Test
    fun `a date more than six months in the past rolls forward one year`() {
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        // 7 个月前的月日：按"今年"算会落到过去 7 个月，必须顺延一年
        val target = today.minusMonths(7)
        val label = "%02d-%02d 19:35".format(target.monthValue, target.dayOfMonth)
        val parsed = parseStartDate(label)
        assertEquals(
            "$label 应该顺延到下一年",
            target.plusYears(1),
            parsed,
        )
    }

    /** 走公开入口 parseList，拿到解析后的开赛日期。 */
    private fun parseStartDate(timeLabel: String): java.time.LocalDate {
        val js = """
            document.write('<ul class="item play" data-lid="1,2,3" data-stype="zqlq">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">$timeLabel</li>');
            document.write('  <li class="lab_team_home"><strong class="name">A</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">B</strong></li>');
            document.write('</ul>');
        """.trimIndent()
        val m = parser.parseList(js).first()
        return java.time.Instant.ofEpochMilli(m.startAtMillis!!)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }
}
