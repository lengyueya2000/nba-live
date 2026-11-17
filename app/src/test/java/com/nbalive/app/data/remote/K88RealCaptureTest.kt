package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **真实抓包**字节判据（2026-11-15 抓的 131401 那场，非合成夹具）。
 *
 * ## 为什么单独一个文件
 *
 * 其余 K88 用例的正文是我按结构**手写**的 —— 那挡不住"夹具与源站不一致"这类错误：
 * 手写样本恰好符合我自己的理解时，解析器全绿，线上照样解不出来。
 * 这里直接用抓到的原始响应（下面 `REAL_SOURCE_BODY`，
 * sha256 前缀 `4cc97f215e1d0ee0`，13 条 links），
 * 断言的是**那一份字节**今天解出什么：
 *  - 5 条云直播线路必须解析出来（它们与 JRS/JRKAN 重复，交给 LineEquivalence 收敛）；
 *  - m3u8 代理壳必须判为可直连（本源增量价值的实证）；
 *  - taifood / streamfree 两类必须整类剔除。
 *
 * ⚠️ 这份字节会过期（源站签名/线路都会变）。过期时的正确动作是**重新抓一份并更新常量**，
 * 而不是把断言放宽 —— 放宽之后这条判据就成了摆设，
 * 而它守的恰恰是"我对源站结构的理解是不是真的"。
 */
class K88RealCaptureTest {

    private val parser = K88Parser()

    @Test
    fun `真实响应解出全部云直播线路与壳线路 海外线路被剔除`() {
        val sources = parser.parseSourcePage(REAL_SOURCE_BODY, match())
        val urls = sources.map { it.pageUrl }

        // 1) 云直播播放器：真实数据里的 5 条必须都在
        val cloud = urls.filter { it.contains("sportsteam") }
        assertEquals("云直播线路数（实际：$cloud）", 5, cloud.size)
        listOf("kbs", "mgxl", "pao", "oo", "vxt").forEach { player ->
            assertTrue("缺 $player 线路：$cloud", cloud.any { it.contains("/play$player/") || it.contains("/play/$player/") })
        }

        // 2) 88player 壳：本源独有，必须留下且判为可直连
        val wrappers = urls.filter { it.contains("88player.top") }
        assertTrue("壳线路一条都没留下（实际：$urls）", wrappers.isNotEmpty())
        assertTrue(
            "壳线路必须判为可静态直连（这是接入本源的全部理由）",
            sources.filter { it.pageUrl.contains("88player.top") }.all { it.isDirectHls },
        )

        // 3) 海外/官源必须整类剔除
        assertTrue(
            "taifood/streamfree 这类网页线路不许进列表（实际：$urls）",
            urls.none { it.contains("taifood") || it.contains("streamfree") || it.contains("cctv") },
        )

        // 4) 线路 id 带来源维度且不重复（播放器拿它当 LazyColumn 的 key）
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        assertTrue(sources.all { it.provider == Provider.K88 })
    }

    @Test
    fun `真实云直播线路与既有两源的地址可被等价键收敛`() {
        val urls = parser.parseSourcePage(REAL_SOURCE_BODY, match()).map { it.pageUrl }
        // 实测：JRS 信号1 = sportsteam368/play/mgxl/?id=00588403、
        // JRKAN 直播⑥ = sportsteam**356**/play/mgxl/?id=00588403 ——
        // 与本源那条同路，三个 key 必须相等（这就是"重复的播放源"的判据形态）。
        val k88Mgxl = urls.first { it.contains("/play/mgxl/") }
        assertEquals(
            LineEquivalence.key("http://play.sportsteam356.com/play/mgxl/?id=00588403&id2="),
            LineEquivalence.key(k88Mgxl),
        )
        assertEquals(
            LineEquivalence.key("http://play.sportsteam368.com/play/mgxl/?id=00588403&id2="),
            LineEquivalence.key(k88Mgxl),
        )
    }

    @Test
    fun `真实抓包里腾讯超清排第一`() {
        // 用户 2026-11-17 点名「88看球里优先把腾讯超清线路排第一」。
        // 这条判据必须打在**真实字节**上，不是只打在手写夹具上：
        // 那份抓包里可直连线路的源站顺序是 线路Q1 → 腾讯超清1 → 线路A1 → 线路Q3，
        // 旧实现（只按可直连排序）默认选中的是 线路Q1 —— 也就是说
        // "腾讯超清排第一"在真实数据上当时并不成立，只有真字节能证明它现在成立。
        val sources = parser.parseSourcePage(REAL_SOURCE_BODY, match())
        assertEquals(
            "真实抓包的第一条必须是腾讯超清（实际：${sources.map { it.label }}）",
            "腾讯超清1",
            sources.first().label,
        )
        // 且不许把只能走网页的云直播线路提到它前面（两级顺序不可颠倒）
        assertTrue("第一条必须可直连", sources.first().isDirectHls)
    }

    private fun match() = Match(
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

    private companion object {
        /** 抓包原文（2026-11-15 09:38，sha256 前缀 `4cc97f215e1d0ee0`）。 */
        val REAL_SOURCE_BODY: String = """{"data":"oYfsoreyJzaGFyZVN0YXR1cyI6MCwic2hhcmVTdGF0dXNDaGVjayI6MCwic2hhcmVJZCI6IiIsInNoYXJlTGluayI6Imh0dHBzOi8vd3d3Ljg4a2FucWl1LmN4L2xpdmUvMTMxNDAxL3BsYXk/c2hhcmVfaWQ9IiwibGlua3MiOlt7InVybCI6Imh0dHA6Ly9wbGF5LnNwb3J0c3RlYW0zNjguY29tL3BsYXkva2JzLz9pZD0xMDAyMjYwMDIzMVx1MDAyNmlkMj0iLCJuYW1lIjoi5Zu95YaF57q/6LevIFEg4pGkIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHA6Ly9wbGF5LnNwb3J0c3RlYW0zNjguY29tL3BsYXkvbWd4bC8/aWQ9MDA1ODg0MDNcdTAwMjZpZDI9IiwibmFtZSI6IuWbveWGhee6v+i3ryBN4pGlIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHA6Ly9wbGF5LnNwb3J0c3RlYW0zNjguY29tL3BsYXkvcGFvLz9pZD0zOTMxNjczMlx1MDAyNmlkMj0iLCJuYW1lIjoi5Zu95YaF57q/6Lev4pGmIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHA6Ly9wbGF5LnNwb3J0c3RlYW0zNjguY29tL3BsYXkvb28vP2lkPTM5MzE2NzNvXHUwMDI2aWQyPSIsIm5hbWUiOiLlm73lhoXnur/ot6/ikaciLCJzaGFyZUxvY2siOjB9LHsidXJsIjoiaHR0cDovL3BsYXkuc3BvcnRzdGVhbTM2OC5jb20vcGxheS92eHQvP2lkPTEwMDIyNjAwMjMxXHUwMDI2aWQyPSIsIm5hbWUiOiLlm73lhoXnur/ot68gUSDikagiLCJzaGFyZUxvY2siOjB9LHsidXJsIjoiaHR0cHM6Ly9wbGF5Ljg4cGxheWVyLnRvcC9tM3U4Lmh0bWw/dXJsPWh0dHBzJTNBJTJGJTJGaGVsbG8ub29vMG9vby50b3AlMkZsaXZlJTJGMzkzMTY3My5tM3U4IiwibmFtZSI6Iue6v+i3r1ExIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHBzOi8vcGxheS44OHBsYXllci50b3AvbTN1OC5odG1sP3VybD1odHRwcyUzQSUyRiUyRnd3dy5ydG1wY2RuLmNvbSUyRmxpdmUlMkZxcTEubTN1OCIsIm5hbWUiOiLohb7orq/otoXmuIUxIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHBzOi8vcGxheS44OHBsYXllci50b3AvbTN1OC5odG1sP3VybD1odHRwcyUzQSUyRiUyRnd3dy5ydG1wY2RuLmNvbSUyRmxpdmUlMkZwLTM0MjQ3Y2YzZjBjYjQ4MWNiNzY0YjFjMmI3ZGJmYTU5Lm0zdTgiLCJuYW1lIjoi57q/6LevQTEiLCJzaGFyZUxvY2siOjB9LHsidXJsIjoiaHR0cHM6Ly90YWlmb29kLWJsb2cuYXNpYS9lbWJlZC9uYmEvMjAyNi0xMS0xNC9kZW4tbm8iLCJuYW1lIjoiSEQgfCBIb21lIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHBzOi8vdGFpZm9vZC1ibG9nLmFzaWEvZW1iZWQvbmJhLzIwMjYtMTEtMTQvZGVuLW5vXzU0MHAiLCJuYW1lIjoiU0QgfCBIb21lIiwic2hhcmVMb2NrIjowfSx7InVybCI6Imh0dHBzOi8vcGxheS44OHBsYXllci50b3AvbTN1OC5odG1sP3VybD1odHRwcyUzQSUyRiUyRnBsYXkuYWFycmF5LmNuJTJGcnJ0eSUyRmhkLXpoLTItMzkzMTY3M19ncXptLm0zdTglM0ZhdXRoX2tleSUzRDE3OTQ3MDM4OTQtMC0wLTQ5MDhmMWMyN2FiYjUzMzg4ZmMyNzFiYzNlYWFmOGFkJTI2dG9rZW4lM0Q5YTczN2ZkMzI5NjM0YjgxJTI2ZGV2aWNlJTNENCUyNnBsYXRmb3JtJTNEcnJ0eSIsIm5hbWUiOiLnur/ot69RMyIsInNoYXJlTG9jayI6MH0seyJ1cmwiOiJodHRwczovL3N0cmVhbWZyZWUudG9wL2VtYmVkL2Jhc2tldGJhbGwvZGVudmVyLW51Z2dldHMtdnMtbmV3LW9ybGVhbnMtcGVsaWNhbnMiLCJuYW1lIjoiSEQiLCJzaGFyZUxvY2siOjB9LHsidXJsIjoiaHR0cHM6Ly9wbGF5Ljg4cGxheWVyLnRvcC9tM3U4Lmh0bWw/dXJsPWh0dHBzJTNBJTJGJTJGcGxheS5hYXJyYXkuY24lMkZycnR5JTJGaGQtemgtMi0zOTMxNjczX2dxem0ubTN1OCUzRmF1dGhfa2V5JTNEMTc5NDcwNzY5Ny0wLTAtZTA4ZWE1MWIyYjJjYTEzMDNjNjljZGRmYWM2MDEzYWIlMjZ0b2tlbiUzRGE3NjkyNWVkZTVkOTE0N2UlMjZkZXZpY2UlM0Q0JTI2cGxhdGZvcm0lM0RycnR5IiwibmFtZSI6Iue6v+i3r1EzIiwic2hhcmVMb2NrIjowfV0sInRpbWUiOiIyMDI2LTExLTE1VDA5OjMyOjM2LjI4MzgwMzI2NSswODowMCJ9ZK"}"""
    }
}
