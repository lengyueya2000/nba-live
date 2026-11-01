package com.nbalive.app.data.remote

import com.nbalive.app.data.model.isDirectHls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页解析加固的判据（task-6 / fix-data）。
 *
 *  - **data.md D4**：`data-play` 引号放宽（单/双）、线路名剥标签后取值、
 *    **名字取不到 → 视为敏感整条剔除**（不能默认放过）；
 *  - **data.md D13**：`Regex` 提成 `companion object` 常量 + `line\d` → `line\d+`
 *    （`line10` 也要能解析出 `playId`）。
 *
 * ## 为什么"名字取不到"必须整条剔除
 *
 * `ChannelAdmission` 只有一条产品规则：名字含主播/解说/美女/聊天 → 整条过滤。
 * 名字取不到时旧实现给空串，规则对这条线路完全失效；而
 * `sortedByDescending { isDirectPath(path) }` 会把可直连的 `sm.html`
 * 排到第 0 条（默认播放）—— 用户 2026-10-29「过滤掉涉及主播的线路」
 * 的要求就被静默绕过了。
 */
class ChannelParseFixesTest {

    private val parser = JrkanParser()

    @Test
    fun `单引号 data-play 也能解析出线路`() {
        val html =
            """<a class="item" data-play='/play/pao/?id=1&id2='><strong>高清直播①</strong></a>"""
        val channels = parser.parseChannels(html, "jrkan-quote")

        assertEquals(
            "上游把属性引号改成单引号时，旧正则匹配归零 → 整源线路静默清空（D4）",
            1,
            channels.size,
        )
        assertTrue(channels.first().pageUrl.contains("/play/pao/"))
        assertEquals("高清直播①", channels.first().label)
    }

    @Test
    fun `线路名内嵌标签时剥标签后再判敏感`() {
        val html =
            """<a data-play="/play/sm.html?id=1&id2="><strong><span>主播解说①</span></strong></a>"""
        val channels = parser.parseChannels(html, "jrkan-nested")

        assertEquals(
            "`<strong><span>主播解说①</span></strong>` 取不到名字就等于绕过过滤；" +
                "剥标签后必须认出它是主播线路并整条剔除（实际 ${channels.map { it.label }}）",
            0,
            channels.size,
        )
    }

    @Test
    fun `名字完全取不到的线路必须整条剔除`() {
        val html = """<a data-play="/play/sm.html?id=9&id2="><em class="icon-play-circle"></em></a>"""
        assertEquals(
            "拿不到名字就无法证明它不是主播线路 —— 不能默认放过（D4）",
            0,
            parser.parseChannels(html, "jrkan-noname").size,
        )
    }

    @Test
    fun `名字正常且可直连的线路仍然保留`() {
        val html = """<a data-play="/play/sm.html?id=3&id2="><strong>高清直播②</strong></a>"""
        val channels = parser.parseChannels(html, "jrkan-ok")
        assertEquals("加固不能把正常线路一起误杀", 1, channels.size)
        assertEquals("高清直播②", channels.first().label)
        assertTrue(channels.first().isDirectHls)
    }

    @Test
    fun `准入判据对空名字也整条剔除 但 isSensitive 语义不变`() {
        val admitted = ChannelAdmission.admit(
            listOf(
                RawChannel("/play/pao/?id=1&id2=", ""),
                RawChannel("/play/pao/?id=2&id2=", "高清直播③"),
            ),
        )
        assertEquals("空名字必须被剔除", 1, admitted.size)
        assertEquals("高清直播③", admitted.single().rawLabel)
        assertFalse(
            "isSensitive(\"\") 必须仍是 false（既有用例钉着这个语义，剔除由 admit 负责）",
            ChannelAdmission.isSensitive(""),
        )
    }

    @Test
    fun `line10 也能解析出 playId`() {
        val js = """
            document.write('<ul class="item play active" data-lid="3931527,2,3931527">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">10-23 08:00</li>');
            document.write('  <li class="lab_team_home"><strong class="name">勇士</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">太阳</strong></li>');
            document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line10", "824761") + '"></a></li>');
            document.write('</ul>');
        """.trimIndent()

        val sources = parser.parseList(js).single().sources
        assertEquals(
            "旧正则 `line\\d` 只认单数字：line10 会让 playId 为 null → 该场次" +
                "既不报错也没有线路（卡片连徽标都不画）",
            1,
            sources.size,
        )
        assertTrue("playId 必须真的解析出来", sources.single().pageUrl.contains("824761"))
    }

    @Test
    fun `line1 的老形态不受影响`() {
        val js = """
            document.write('<ul class="item play active" data-lid="3931527,2,3931527">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">10-23 08:00</li>');
            document.write('  <li class="lab_team_home"><strong class="name">勇士</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">太阳</strong></li>');
            document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line1", "111222") + '"></a></li>');
            document.write('</ul>');
        """.trimIndent()

        assertTrue(parser.parseList(js).single().sources.single().pageUrl.contains("111222"))
    }
}