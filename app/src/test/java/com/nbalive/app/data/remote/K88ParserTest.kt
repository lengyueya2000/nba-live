package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 88看球（88kanqiu.cx）源解析器判据（第三源接入，2026-11-15）。
 *
 * 实测结构（2026-11-15 抓包，`build-shots/sources88/`）：
 *  - 列表页 `/match/1/live`：`li.live-list-group-time` 日期分组 +
 *    `li.group-game-item` 每场一条，比赛 id 在 `a[href=/live/{id}/play]`。
 *  - 线路接口 `/live/{id}/source`：`{"data": "<乱码头6 + base64(JSON) + 乱码尾2>"}`，
 *    JSON 里 `links[] = {url, name, shareLock}`。
 *
 * 线路三类（价值不同）：
 *  1. `play.sportsteam368.com/play/{kbs,mgxl,txf,pao,oo,vxt}/?id=` —— 与 JRS/JRKAN
 *     **同一批云直播线路**（368/356 互为镜像，已验证同后端），靠
 *     [LineEquivalence] 在合并层去重，这里照常解析；
 *  2. `play.88player.top/m3u8.html?url=<encodeURI(m3u8)>` —— **裸 m3u8 代理壳**，
 *     可静态还原直连（本 App 直连 HLS 链路能直接消费，实测 200 + `#EXTM3U`）；
 *  3. `taifood-blog.asia` / `streamfree.top` / CCTV —— 海外/官源网页，
 *     **整类剔除**（白名单外的 WebView 域承载不了、也没有解析价值）。
 */
class K88ParserTest {

    private val parser = K88Parser()

    // ---------- 列表页 ----------

    @Test
    fun `列表页解析出 NBA 场次并带 source 接口线路入口`() {
        // 解析层不按联赛过滤（与 JRS/JRKAN 一致）：英超场会被判 OTHER，
        // 由 MatchMerger 的 supported 过滤统一丢弃。
        val matches = parser.parseList(listHtml())
        assertEquals(3, matches.size)
        assertEquals(League.OTHER, matches.first { it.homeTeam == "阿森纳" }.league)

        val nba = matches.first { it.homeTeam == "鹈鹕" }
        assertEquals("k88-131401", nba.id)
        assertEquals(League.NBA, nba.league)
        assertEquals(MatchStatus.LIVE, nba.status)
        assertEquals("11-15 08:00", nba.timeLabel)
        // 列表页拿不到线路数：-1 = 未解析（与 JRS 列表行同一约定）
        assertEquals(-1, nba.signalCount)
        // sources 必须带一条"source 接口"入口，loadSignals 靠它展开线路
        val entry = nba.sources.single()
        assertEquals(Provider.K88, entry.provider)
        assertTrue("入口必须指向线路接口：${entry.pageUrl}", entry.pageUrl.endsWith("/live/131401/source"))
    }

    @Test
    fun `未开赛的暂无行按时间判 UPCOMING`() {
        val matches = parser.parseList(listHtml())
        val cba = matches.first { it.homeTeam == "湖人" }
        assertEquals(League.CBA, cba.league)
        assertEquals(MatchStatus.UPCOMING, cba.status)
        assertTrue(cba.startAtMillis!! > 0)
    }

    @Test
    fun `日期分组与时间合成北京时间戳`() {
        val nba = parser.parseList(listHtml()).first { it.homeTeam == "鹈鹕" }
        // 2026-11-15 08:00 北京时间 = 2026-11-15 00:00 UTC
        val expected = java.time.ZonedDateTime.of(
            2026, 11, 15, 8, 0, 0, 0, com.nbalive.app.data.model.MatchStatusClock.SOURCE_ZONE,
        ).toInstant().toEpochMilli()
        assertEquals(expected, nba.startAtMillis)
    }

    @Test
    fun `href 不合预期的行跳过而不是产出空 id`() {
        val broken = listHtml().replace("/live/131401/play", "/other/131401")
        val matches = parser.parseList(broken)
        assertTrue(matches.none { it.id == "k88-" || it.id.endsWith("-") })
        assertEquals(2, matches.size) // 鹈鹕那场被跳过，剩湖人 + 阿森纳
    }

    // ---------- 线路接口 ----------

    @Test
    fun `source 接口解码出线路 头尾乱码与 padding 都容忍`() {
        val payload = sourceJson(
            """
            {"shareStatus":0,"links":[
              {"url":"http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=","name":"国内线路 Q ⑤","shareLock":0},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8","name":"腾讯超清1","shareLock":0}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals(2, sources.size)
        assertEquals(Provider.K88, sources[0].provider)
        // 可直连的排在最前（排序纪律），所以壳线路在 0 位、云直播在 1 位
        assertEquals("腾讯超清1", sources[0].label)
        assertEquals("国内线路 Q ⑤", sources[1].label)
        assertEquals(
            "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=",
            sources[1].pageUrl,
        )
    }

    @Test
    fun `88player 代理壳线路可静态直连`() {
        val payload = sourceJson(
            """{"links":[{"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8","name":"腾讯超清1"}]}""",
        )
        val s = parser.parseSourcePage(payload, k88Match()).single()
        assertTrue("m3u8.html?url= 壳必须判为可直连，否则白白走 WebView", s.isDirectHls)
    }

    @Test
    fun `海外与官源线路整类剔除`() {
        val payload = sourceJson(
            """
            {"links":[
              {"url":"https://taifood-blog.asia/embed/nba/2026-11-14/den-no","name":"HD | Home"},
              {"url":"https://streamfree.top/embed/basketball/denver-nuggets-vs-new-orleans-pelicans","name":"HD"},
              {"url":"http://tv.cctv.com/live/cctv5/","name":"CCTV5"},
              {"url":"http://play.sportsteam368.com/play/mgxl/?id=00588403&id2=","name":"国内线路 M⑥"}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals("只剩云直播线路", listOf("国内线路 M⑥"), sources.map { it.label })
    }

    @Test
    fun `主播类线路名沿用同一套准入过滤`() {
        val payload = sourceJson(
            """
            {"links":[
              {"url":"http://play.sportsteam368.com/play/pao/?id=39316732&id2=","name":"主播解说①"},
              {"url":"http://play.sportsteam368.com/play/pao/?id=39316733&id2=","name":"国内线路⑦"}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals(1, sources.size)
        assertEquals("国内线路⑦", sources[0].label)
    }

    @Test
    fun `可直连线路排在最前`() {
        val payload = sourceJson(
            """
            {"links":[
              {"url":"http://play.sportsteam368.com/play/pao/?id=39316732&id2=","name":"国内线路⑦"},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8","name":"腾讯超清1"}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals("腾讯超清1", sources.first().label)
    }

    @Test
    fun `同一接口内 url 完全相同的线路只保留一条`() {
        val payload = sourceJson(
            """
            {"links":[
              {"url":"http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=","name":"国内线路 Q ⑤"},
              {"url":"http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=","name":"国内线路 Q ⑤ 重复"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, parser.parseSourcePage(payload, k88Match()).size)
    }

    @Test
    fun `auth_key 不同的两条线路都保留 不误合并`() {
        // 实测 88 会给两条"线路Q3"，只差 auth_key 时间戳 —— 都是有效签名快照，
        // 归一化 key 必须包含完整签名，否则第二条被当成重复删掉。
        val payload = sourceJson(
            """
            {"links":[
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fplay.aarray.cn%2Frrty%2Fhd-zh-2-3931673_gqzm.m3u8%3Fauth_key%3D1794703894-0-0-4908f1c2","name":"线路Q3"},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fplay.aarray.cn%2Frrty%2Fhd-zh-2-3931673_gqzm.m3u8%3Fauth_key%3D1794707697-0-0-e08ea51b","name":"线路Q3"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, parser.parseSourcePage(payload, k88Match()).size)
    }

    @Test
    fun `列表入口地址跟随取到正文的那个 host`() {
        // 主域名死了、正文是从镜像拿到的时候，入口地址若仍拼写死的首位域名，
        // 展开线路敲的还是死域名 —— 换域名等于没做（P1-9 同一条纪律，
        // JrkanParser.parseChannels 的 playHost 参数是同一个教训）。
        val matches = parser.parseList(listHtml(), origin = "https://www.88zhibo.me")
        val entry = matches.first { it.homeTeam == "鹈鹕" }.sources.single()
        assertTrue(
            "线路入口必须落在真正取到正文的 host 上，实际：${entry.pageUrl}",
            entry.pageUrl.startsWith("https://www.88zhibo.me/live/131401/source"),
        )
    }

    @Test
    fun `噪声位数变化时仍能解码 不是按固定位数切`() {
        // 实测见过 6/2（131401）与 6/1（另一场）两种头尾噪声长度；
        // 若实现按"固定去头 6 去尾 2"切，头 6 尾 2 的样本能过、尾 1 的样本直接解不出，
        // 整个源静默 0 条线路 —— 所以位数必须多样本钉住。
        listOf(2 to 1, 6 to 2, 3 to 3, 0 to 0).forEach { (head, tail) ->
            val inner = """{"links":[{"url":"http://play.sportsteam368.com/play/kbs/?id=1&id2=","name":"线路1"}]}"""
            val b64 = java.util.Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8))
            val payload = """{"data":"${"x".repeat(head)}$b64${"y".repeat(tail)}"}"""
            assertEquals(
                "头 $head 位 / 尾 $tail 位噪声时必须仍能解出线路（实际解出 0 条）",
                1,
                parser.parseSourcePage(payload, k88Match()).size,
            )
        }
    }

    @Test
    fun `data 解不出合法 JSON 时返回空表而不是抛异常`() {
        // base64 里全是非法字符、或整个 payload 不是 JSON：都给空列表，绝不抛出去
        //（单个源的解析异常不许带走别的源的数据）。
        // ⚠️ 第一个样本用的必须是**不是 base64 字母表**的字符：
        // 噪声 + 补齐的 `=` 落在字母表内时（例："@@@"），严格解码会把它当成
        // 合法 padding 提前收尾、解出一段乱码 —— 那是 fixture 骗人，不是判据没生效。
        assertTrue(parser.parseSourcePage("""{"data":"!!!!not-base64!!!!"}""", k88Match()).isEmpty())
        assertTrue(parser.parseSourcePage("garbage", k88Match()).isEmpty())
        assertTrue(parser.parseSourcePage("", k88Match()).isEmpty())
    }

    // ---------- 线路优先级（2026-11-17 用户点名） ----------

    @Test
    fun `腾讯超清排在其它可直连线路之前`() {
        // 真实抓包里 88 的可直连线路顺序是 线路Q1 → 腾讯超清1 → 线路A1 → 线路Q3，
        // 旧实现只按"能不能直连"排序（稳定排序 ⇒ 保持源站顺序），
        // 于是默认选中的永远是 线路Q1。用户点名要 腾讯超清 优先。
        val payload = sourceJson(
            """
            {"links":[
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fhello.ooo0ooo.top%2Flive%2F3931673.m3u8","name":"线路Q1"},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8","name":"腾讯超清1"},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fp-342.m3u8","name":"线路A1"}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals(3, sources.size)
        assertEquals("腾讯超清1 必须是第一条（默认选中它）", "腾讯超清1", sources[0].label)
        // 其余线路之间仍按源站顺序（稳定排序，不许顺手打乱别的线）
        assertEquals(listOf("线路Q1", "线路A1"), sources.drop(1).map { it.label })
    }

    @Test
    fun `腾讯超清优先不许把只能网页播放的线路提到直连之前`() {
        // 两条纪律的先后必须是：可直连 > 不可直连，**然后**才是 腾讯超清 优先。
        // 反过来说：如果哪天 腾讯超清 变成一条只能走 WebView 的线路，
        // 它也不许越过可直连的线路 —— 否则默认选中项从"秒开"退化成"0.1~27 秒的网页加载"。
        val payload = sourceJson(
            """
            {"links":[
              {"url":"http://play.sportsteam368.com/play/mgxl/?id=00588403&id2=","name":"腾讯超清（改版后变成网页壳）"},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fwww.rtmpcdn.com%2Flive%2Fqq1.m3u8","name":"可直连线路"}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals("可直连必须仍然优先", listOf("可直连线路"), sources.take(1).map { it.label })
    }

    @Test
    fun `没有腾讯超清时顺序与旧实现完全一致`() {
        // 防"加了优先级就把整张表打乱"：不含关键字时，必须退回既有的
        // 「可直连在前 + 源站顺序」，一条都不许动。
        val payload = sourceJson(
            """
            {"links":[
              {"url":"http://play.sportsteam368.com/play/kbs/?id=10022600231&id2=","name":"国内线路 Q ⑤"},
              {"url":"https://play.88player.top/m3u8.html?url=https%3A%2F%2Fhello.ooo0ooo.top%2Flive%2F3931673.m3u8","name":"线路Q1"},
              {"url":"http://play.sportsteam368.com/play/mgxl/?id=00588403&id2=","name":"国内线路 M⑥"}
            ]}
            """.trimIndent(),
        )
        val sources = parser.parseSourcePage(payload, k88Match())
        assertEquals(listOf("线路Q1", "国内线路 Q ⑤", "国内线路 M⑥"), sources.map { it.label })
    }

    // ---------- 夹具 ----------

    private fun k88Match() = Match(
        id = "k88-131401",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "鹈鹕",
        awayTeam = "掘金",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "11-15 08:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 1,
        sources = listOf(
            StreamSource(
                id = "k88-131401-s0",
                label = "线路",
                provider = Provider.K88,
                pageUrl = "https://www.88kanqiu.cx/live/131401/source",
            ),
        ),
    )

    /** 真实形态：`{"data":"乱码头6 + base64(JSON) + 乱码尾2"}`（头尾长度会变，解码要容错）。 */
    private fun sourceJson(inner: String): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8))
        return """{"data":"lfLsjR${b64}8H"}"""
    }

    private fun listHtml() = """
        <ul class="list-group">
            <li class="list-group-item live-list-group-time">
                <span class="date"></span>
                2026-11-15
            </li>
            <li class="list-group-item group-game-item">
                <div class="row">
                    <div class="col-md-1 category-game-time">08:00</div>
                    <div class="col-md-3"><div><span class="game-type">NBA</span></div></div>
                    <div class="col-md-4 text-right"><span class="team-name" title="鹈鹕">鹈鹕</span></div>
                    <div class="col-xs-1"><img class="lazyload" alt="鹈鹕" data-src="https://mat1.gtimg.com/sports/nba/logo/1602/3.png"></div>
                    <div class="col-md-1 text-center">VS</div>
                    <div class="col-xs-1"><img class="team-logo lazyload" alt="掘金" data-src="https://mat1.gtimg.com/sports/nba/logo/1602/20187.png"></div>
                    <div class="col-md-3 text-left"><span class="team-name" title="掘金">掘金</span></div>
                    <div data-id="131401" class="col-md-3 pay-btn text-right">
                        <a target="_blank" class="btn btn-primary" href='/live/131401/play' role="button">直播中<i class="glyphicon glyphicon-play"></i></a>
                    </div>
                </div>
            </li>
            <li class="list-group-item group-game-item">
                <div class="row">
                    <div class="col-md-1 category-game-time">05:00</div>
                    <div class="col-md-3"><div><span class="game-type">CBA</span></div></div>
                    <div class="col-md-4 text-right"><span class="team-name" title="湖人">湖人</span></div>
                    <div class="col-xs-1"><img class="lazyload" alt="湖人" data-src="https://mat1.gtimg.com/sports/nba/logo/1602/1.png"></div>
                    <div class="col-md-1 text-center">VS</div>
                    <div class="col-xs-1"><img class="team-logo lazyload" alt="太阳" data-src="https://mat1.gtimg.com/sports/nba/logo/1602/2.png"></div>
                    <div class="col-md-3 text-left"><span class="team-name" title="太阳">太阳</span></div>
                    <div data-id="131407" class="col-md-3 pay-btn text-right">
                        <a target="_blank" class="btn btn-primary" href='/live/131407/play' role="button">暂无<i class="glyphicon glyphicon-play"></i></a>
                    </div>
                </div>
            </li>
            <li class="list-group-item group-game-item">
                <div class="row">
                    <div class="col-md-1 category-game-time">05:30</div>
                    <div class="col-md-3"><div><span class="game-type">英超</span></div></div>
                    <div class="col-md-4 text-right"><span class="team-name" title="阿森纳">阿森纳</span></div>
                    <div class="col-md-1 text-center">VS</div>
                    <div class="col-md-3 text-left"><span class="team-name" title="曼城">曼城</span></div>
                    <div data-id="131900" class="col-md-3 pay-btn text-right">
                        <a target="_blank" class="btn btn-primary" href='/live/131900/play' role="button">暂无</a>
                    </div>
                </div>
            </li>
        </ul>
    """.trimIndent()
}
