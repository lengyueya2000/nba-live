package com.nbalive.app.data.remote

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.model.isStaticallyResolvablePlayPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 线路准入判据（线上黑屏的直接原因所在）。
 *
 * 背景（2026-10-29 实测）：视频区纯黑、控件还在、没声音。根因是
 * [JrkanParser.parseChannels] 按 `主播/解说/美女/聊天` **删除**线路，
 * 而某场 NBA 的 7 条线路里只有 3 条 `/play/sm.html` 能被静态解析成直连 HLS，
 * 它们恰好叫「主播解说①②③」→ 全被删掉 → App 一次直连都没试过，
 * 只剩只能走 WebView 的 vxt/kbs/mgxl/pao（那几条在当前网络下零媒体请求）。
 */
class JrkanChannelAdmissionTest {

    private val parser = JrkanParser()

    /** 真实播放页 `steam825208.html` 的 sub_playlist（2026-10-29 抓取，路径与名字原样）。 */
    private val liveSubPlaylist = """
        <div class="sub_playlist">
            <div class="sub_channel">
                <a class="item play ok me" target="myplayer" data-group="云直播①" data-providerid="27592"
                   data-play="/play/sm.html?id=384&id2=" href="" data-urlid="549270">
                  <em class="icon-play-circle"></em><strong>主播解说①</strong></a>
                <a class="item ok me" target="myplayer" data-group="云直播②" data-providerid="27608"
                   data-play="/play/sm.html?id=416&id2=" href="" data-urlid="553582">
                  <em class="icon-play-circle"></em><strong>主播解说②</strong></a>
                <a class="item ok me" target="myplayer" data-group="云直播③" data-providerid="27608"
                   data-play="/play/sm.html?id=297&id2=" href="" data-urlid="553583">
                  <em class="icon-play-circle"></em><strong>主播解说③</strong></a>
                <a class="item ok me" data-play="/play/vxt/?id=10022600132&id2=" href="">
                  <strong>中文高清 Q ④</strong></a>
                <a class="item ok me" data-play="/play/kbs/?id=10022600132&id2=" href="">
                  <strong>中文高清 Q ⑤</strong></a>
                <a class="item ok me" data-play="/play/mgxl/?id=00588305&id2=" href="">
                  <strong>中文高清 M⑥</strong></a>
                <a class="item ok me" data-play="/play/pao/?id=39315742&id2=" href="">
                  <strong>高清直播⑦</strong></a>
                <a class="item ok me" data-play="=&id2=" href=""><strong>占位</strong></a>
            </div>
        </div>
    """.trimIndent()

    // ------------------------------------------------------------------
    // 规则 1：名字命中敏感词
    // ------------------------------------------------------------------

    @Test
    fun `四个敏感词都判为敏感`() {
        assertTrue(ChannelAdmission.isSensitive("主播解说①"))
        assertTrue(ChannelAdmission.isSensitive("美女直播"))
        assertTrue(ChannelAdmission.isSensitive("聊天室"))
        assertTrue(ChannelAdmission.isSensitive("国语解说"))
    }

    @Test
    fun `源站的普通线路名不算敏感`() {
        assertFalse(ChannelAdmission.isSensitive("中文高清 Q ④"))
        assertFalse(ChannelAdmission.isSensitive("高清直播⑦"))
        assertFalse(ChannelAdmission.isSensitive(""))
    }

    // ------------------------------------------------------------------
    // 规则 2：过滤不允许把"可直连线路"清零
    // ------------------------------------------------------------------

    @Test
    fun `过滤清零可直连线路时 用中性名补回可直连的敏感线路`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/sm.html?id=384&id2=", "主播解说①"),
                RawChannel("/play/mgxl/?id=5&id2=", "中文高清 M⑥"),
            ),
        )

        assertEquals(2, admitted.size)
        // admit 只管"准入"，顺序由调用方排序决定 → 这里按路径定位，不假设下标
        val rescued = admitted.single { it.path.contains("sm.html") }
        // 敏感线路的可直连路径被补回来，且**原始名不落进结果**（界面不能出现主播名义）
        assertEquals("", rescued.rawLabel)
        assertTrue("留在结果里的那条不能是敏感线路", !ChannelAdmission.isSensitive(rescued.rawLabel))
        // 非敏感的网页线路照旧保留
        assertTrue(admitted.any { it.path.contains("mgxl") && it.rawLabel == "中文高清 M⑥" })
    }

    @Test
    fun `已存在非敏感可直连线路时不补回`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/sm.html?id=1&id2=", "高清直播①"),
                RawChannel("/play/sm.html?id=2&id2=", "主播解说②"),
                RawChannel("/play/pao/?id=3&id2=", "高清直播③"),
            ),
        )

        assertEquals(2, admitted.size)
        assertTrue(admitted.none { it.path.contains("id=2") })
        assertTrue(admitted.none { ChannelAdmission.isSensitive(it.rawLabel) })
    }

    @Test
    fun `敏感但不可直连的线路永远不补回`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/pao/?id=1&id2=", "主播解说①"),
                RawChannel("/play/mgxl/?id=2&id2=", "高清直播②"),
            ),
        )

        assertEquals(1, admitted.size)
        assertEquals("/play/mgxl/?id=2&id2=", admitted[0].path)
    }

    @Test
    fun `补回只在可直连线路真的被清零时发生`() {
        // 夹具里没有任何可直连线路（既没有被删的，也没有留下的）→ 什么都不用补
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/pao/?id=1&id2=", "主播解说①"),
                RawChannel("/play/kbs/?id=2&id2=", "主播解说②"),
            ),
        )
        assertEquals(0, admitted.size)
    }

    // ------------------------------------------------------------------
    // 判据唯一来源：什么算"静态可解析"
    // ------------------------------------------------------------------

    @Test
    fun `静态可解析判据覆盖 sm 与 steam 并排除加密播放器`() {
        val host = "http://play.sportsteam356.com"
        assertTrue(isStaticallyResolvablePlayPage("$host/play/sm.html?id=384&id2="))
        assertTrue(isStaticallyResolvablePlayPage("$host/play/steam825208.html"))
        // 链路 A 的入口页（JRKAN 列表兜底用的就是它）本来被门禁挡住
        assertTrue(isStaticallyResolvablePlayPage("$host/play/steam824761.html?from=list"))

        assertFalse(isStaticallyResolvablePlayPage("$host/play/mgxl/?id=00588305&id2="))
        assertFalse(isStaticallyResolvablePlayPage("$host/play/pao/?id=39315742&id2="))
        assertFalse(isStaticallyResolvablePlayPage("$host/play/vxt/?id=1&id2="))
        assertFalse(isStaticallyResolvablePlayPage("$host/play/kbs/?id=1&id2="))
        assertFalse(isStaticallyResolvablePlayPage("$host/play/wen/?id=1&id2="))
        // ⚠️ 不许过宽：steam 后面必须是数字（`steamX.html` 不是真入口）
        assertFalse(isStaticallyResolvablePlayPage("$host/play/steamX.html"))
    }

    @Test
    fun `准入判据与模型判据必须同源`() {
        val samples = listOf(
            "/play/sm.html?id=1&id2=",
            "/play/steam825208.html",
            "/play/mgxl/?id=1&id2=",
            "/play/pao/?id=1&id2=",
            "/play/steamX.html",
        )
        for (path in samples) {
            assertEquals(
                "「准入能不能直连」与「要不要尝试直连」必须是同一个判据（各写一份就会分叉）",
                isStaticallyResolvablePlayPage(path),
                ChannelAdmission.isDirectPath(path),
            )
        }
    }

    // ------------------------------------------------------------------
    // 真实页面回归：活站点的 7 条线路必须留下可直连线路
    // ------------------------------------------------------------------

    @Test
    fun `真实 sub_playlist 解析后必须留下可直连线路且不出现主播名义`() {
        val channels = parser.parseChannels(liveSubPlaylist, "jrkan-live")

        assertTrue(
            "源站给了 3 条可直连线路，解析结果里一条都没有 → App 只会走 WebView（线上黑屏）",
            channels.any { it.isDirectHls },
        )
        assertTrue(
            "界面不许出现主播/解说名义",
            channels.none { ChannelAdmission.isSensitive(it.label) },
        )
        // 可直连优先：默认选中第一条即可播
        assertTrue("可直连线路必须排在最前面", channels.first().isDirectHls)
        assertEquals(7, channels.size)
    }

    // ------------------------------------------------------------------
    // 接线守卫：判据必须被生产代码消费，且不许出现第二份关键字表
    // ------------------------------------------------------------------

    @Test
    fun `parseChannels 必须消费准入判据而不是自己写过滤`() {
        val text = productionFile("com/nbalive/app/data/remote/JrkanParser.kt").readText()
        // 非空性锚点：扫到的确实是那个文件
        assertTrue("扫错文件了", text.contains("class JrkanParser"))

        val body = GuardSourceScan.functionBody(text, "fun parseChannels(")
        assertEquals(
            "parseChannels 必须走 ChannelAdmission.admit（自己写过滤就会再次出现线上黑屏那个分支）",
            1,
            Regex("""ChannelAdmission\s*\.\s*admit\s*\(""").findAll(body).count(),
        )
        assertTrue(
            "排序必须用准入判据认定的「可直连」，不能自己写路径字面量",
            Regex("""ChannelAdmission\s*\.\s*isDirectPath\s*\(""").containsMatchIn(body),
        )
        assertFalse(
            "敏感词过滤已搬到 ChannelAdmission：JrkanParser 里不许再有第二份实现",
            GuardSourceScan.stripNonCode(text).contains("EXCLUDED_CHANNEL_KEYWORDS"),
        )
    }

    @Test
    fun `敏感词表在生产代码里只能有一份`() {
        val files = productionSources()
        assertTrue("扫描范围太小，守卫可能是空扫全绿", files.size >= 20)

        // 逐字匹配表头形状 —— 注释里提到「主播」不算（这个仓库的守卫就栽在过"匹配注释"上）
        val declaration = Regex("""listOf\s*\(\s*"主播"""")
        val hits = files.filter { declaration.containsMatchIn(it.readText()) }
        assertEquals(
            "敏感词表必须只有一份（ChannelAdmission），实际命中：${hits.joinToString { it.name }}",
            1,
            hits.size,
        )
        assertEquals("ChannelAdmission.kt", hits.single().name)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun productionFile(relative: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        return listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File(cwd, "src/main/java/$relative"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${File(cwd).absolutePath}）—— 守卫会静默全绿，所以直接失败")
    }

    private fun productionRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        return listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(cwd, "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码根目录（cwd=${File(cwd).absolutePath}）")
    }

    private fun productionSources(): List<File> =
        productionRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `StreamSource 的直连判据能区分两类线路`() {
        // 夹具自检：上面所有「可直连」断言都依赖它真的区分得开
        val direct = StreamSource("a", "线路1", Provider.JRKAN, "http://h/play/sm.html?id=1")
        val web = StreamSource("b", "线路2", Provider.JRKAN, "http://h/play/pao/?id=1")
        assertTrue(direct.isDirectHls)
        assertFalse(web.isDirectHls)
        // 列表页兜底用的入口页（链路 A 起点）也必须算可直连，
        // 否则 `MatchRepository` 的 `ifEmpty` 兜底线路永远只能走 WebView
        assertTrue(
            StreamSource("c", "线路3", Provider.JRKAN, "http://h/play/steam824761.html").isDirectHls,
        )
    }
}