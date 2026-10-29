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
 * 线路准入判据。
 *
 * ## 现行规则（2026-10-29 用户明确要求）
 *
 * 名字涉及 主播/解说/美女/聊天 的线路 —— **整条过滤掉**：
 * 既不"隐名保留"，也不"清零时补回来"。
 *
 * ## 被删掉的「补偿规则」与它为什么是错的（别再把它加回来）
 *
 * `007ad88`（2026-10-29 13:08）曾加过规则 2：若按名字过滤会把「可直连
 * (`/play/sm.html`)」线路清零，就把这些敏感线路以中性名（"线路N"）补回列表。
 *
 * 那条规则是**误诊的产物**：当时的证据是"网页线路一条媒体请求都不发、页内播放器
 * 没跑起来"，被解释成"这些页面只能直连"。真实根因是 v1.2.13 的明文策略误禁了
 * 源站创建播放器的脚本主机（`net::ERR_CLEARTEXT_NOT_PERMITTED`，日志逐字见
 * `network_security_config.xml` 的 KDoc），已在 v1.2.16（`45fdb89`）修掉 ——
 * 网页线路本身是能播的。
 *
 * 补偿生效后 App 的默认线路变成「主播解说①」（直连优先），用户看到的就是
 * "只有主播线路能看"，于是要求把这类线路**过滤掉**。
 *
 * ## 2026-10-29 21:00 实测（23 个场次、95 条线路）
 *
 * - 48 条敏感线路 **全部** 是 `/play/sm.html`（可直连 HLS）；
 * - 47 条非敏感线路 **全部** 是 `/play/pao|wen|mgxl|wlive/`（只能走 WebView）。
 * ⇒ "过滤后一条可直连线路都不剩"是**正常结果**，不是故障；
 *   过滤结果为 0 条也是合法结果（`admit` 返回空，界面显示"该赛事暂无可用线路"）。
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
    // 规则：名字命中敏感词
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
    // 过滤：敏感线路整条进不了列表
    // ------------------------------------------------------------------

    @Test
    fun `名字敏感的线路一律不进列表 即使它是唯一的可直连线`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/sm.html?id=384&id2=", "主播解说①"),
                RawChannel("/play/pao/?id=5&id2=", "高清直播②"),
            ),
        )

        assertEquals("敏感的可直连线路不许以任何名义留在列表里", 1, admitted.size)
        assertEquals("/play/pao/?id=5&id2=", admitted.single().path)
        assertFalse(ChannelAdmission.isSensitive(admitted.single().rawLabel))
    }

    @Test
    fun `只剩主播线路时返回空列表 而不是把主播线路补回来`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/sm.html?id=1&id2=", "主播解说①"),
                RawChannel("/play/sm.html?id=2&id2=", "主播解说②"),
            ),
        )

        assertEquals(
            "这一场只剩主播线路（用户要求过滤）→ 结果必须是 0 条；" +
                "补回来就会让 App 默认播主播线路（007ad88 的误诊补偿）",
            0,
            admitted.size,
        )
    }

    @Test
    fun `敏感线路无论能不能直连都整条过滤`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/pao/?id=1&id2=", "主播解说①"),
                RawChannel("/play/mgxl/?id=2&id2=", "高清直播②"),
            ),
        )

        assertEquals(1, admitted.size)
        assertEquals("/play/mgxl/?id=2&id2=", admitted.single().path)
    }

    // ------------------------------------------------------------------
    // 防回退：admit 里不许再出现"补回敏感线路"的例外分支
    // ------------------------------------------------------------------

    @Test
    fun `admit 里不许出现把敏感线路补回来的分支`() {
        val text = productionFile("com/nbalive/app/data/remote/ChannelAdmission.kt").readText()
        // 非空性锚点：扫到的确实是判据文件
        assertTrue("扫错文件了", text.contains("object ChannelAdmission"))

        val body = GuardSourceScan.functionBody(text, "fun admit(")
        // 非空性锚点：函数体真被截出来了（截空时下面两条断言会静默全绿）
        assertTrue("admit 的函数体扫出来是空的 —— 守卫会静默全绿", body.isNotBlank())
        assertTrue(
            "admit 必须只做名字过滤（filterNot），实际函数体：$body",
            Regex("""filterNot\s*\{""").containsMatchIn(body),
        )
        assertFalse(
            "admit 里出现了 isDirectPath —— 「可直连线路被过滤清零就补回敏感线路」" +
                "那条误诊规则又回来了：它会让 App 默认播主播线路，与本次产品要求直接冲突",
            Regex("""isDirectPath\s*\(""").containsMatchIn(body),
        )
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
    // 真实页面回归：只剩非主播（网页）线路，且默认选中项不是主播线路
    // ------------------------------------------------------------------

    @Test
    fun `真实 sub_playlist 解析后不出现任何主播线路`() {
        val channels = parser.parseChannels(liveSubPlaylist, "jrkan-live")

        assertTrue(
            "界面/播放列表里不许出现任何主播/解说名义：${channels.map { it.label }}",
            channels.none { ChannelAdmission.isSensitive(it.label) },
        )
        assertEquals(
            "夹具里 3 条主播线路被过滤，只剩 4 条非敏感线路（vxt/kbs/mgxl/pao）",
            4,
            channels.size,
        )
        assertFalse(
            "直连线路在该夹具里恰好全是主播线路 → 过滤后一条都不该剩" +
                "（剩了说明有线路绕过了过滤）：${channels.map { it.pageUrl }}",
            channels.any { it.isDirectHls },
        )
        // 默认选中第一条：必须是非主播的网页线路（这就是用户要的「其他线路」）
        assertEquals("中文高清 Q ④", channels.first().label)
        assertFalse(channels.first().isDirectHls)
    }

    @Test
    fun `整页都是主播线路时 parseChannels 返回空列表而不是抛出`() {
        val html = """
            <a data-play="/play/sm.html?id=1&id2="><strong>主播解说①</strong></a>
            <a data-play="/play/sm.html?id=2&id2="><strong>主播解说②</strong></a>
        """.trimIndent()

        assertEquals(0, parser.parseChannels(html, "jrkan-empty").size)
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
            "parseChannels 必须走 ChannelAdmission.admit（自己写过滤就会与判据分叉）",
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