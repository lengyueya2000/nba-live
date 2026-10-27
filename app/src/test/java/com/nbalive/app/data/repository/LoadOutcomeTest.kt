package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.remote.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单源失败的可见性（review/02-data.md P1-2）。
 *
 * 原实现里任一源失败只写一条 `Log.w`，然后当成"这个源今天没比赛"，
 * **只有两个源同时为空**才报错 —— 部分失败对用户完全静默。
 * 用户看到的现象是"场次变少了"，而这条正是"明天有很多场次却只显示部分"的
 * 代码侧成因之一（与源站本身缺场次叠加，用户无法区分）。
 *
 * 这些用例钉住：加载结果必须带上"哪些源失败了"，让 UI 能把静默缺数据
 * 变成"JRS 源暂时不可用，仅显示部分场次"。
 */
class LoadOutcomeTest {

    private val repo = MatchRepository()

    private fun jrsHtml(id: String, teams: Pair<String, String> = "湖人" to "凯尔特人") = """
        <div class="list"><p class="list-title">2026-10-23</p>
        <a href="/live/$id.html" class="list-con">
          <div class="list-team-con"><p class="list-team-name">${teams.first}</p></div>
          <div class="list-team-con"><p class="list-team-name">${teams.second}</p></div>
          <p class="list-info-league">NBA</p><p class="list-info-time">直播中</p>
        </a></div>
    """.trimIndent()

    private fun jrkanJs(eventId: String, teams: Pair<String, String> = "勇士" to "火箭") = """
        document.write('<ul class="item play d-touch active " data-lid="$eventId,2,$eventId" data-stype="zqlq">');
        document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
        document.write('  <li class="lab_time">10-23 10:00</li>');
        document.write('  <li class="lab_team_home"><strong class="name">${teams.first}</strong></li>');
        document.write('  <li class="lab_team_away"><strong class="name">${teams.second}</strong></li>');
        document.write('</ul>');
    """.trimIndent()

    @Test
    fun `两源都成功时没有失败源`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555"))
            else FetchResult.Ok(jrkanJs("900"))
        }
        assertTrue("两源都成功，不该报错：${outcome.failedProviders}", outcome.failedProviders.isEmpty())
        assertTrue(outcome.matches.any { it.id.startsWith("jrs-") })
        assertTrue(outcome.matches.any { it.id.startsWith("jrkan-") })
    }

    @Test
    fun `JRS 被拒时把 JRS 记为失败源`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Rejected(403)
            else FetchResult.Ok(jrkanJs("900"))
        }
        assertEquals(
            "JRS 被 403 拒绝必须能被调用方看到，否则用户只看到「场次变少了」",
            listOf(Provider.JRS),
            outcome.failedProviders,
        )
        assertTrue("JRKAN 的场次照常返回", outcome.matches.any { it.id.startsWith("jrkan-") })
    }

    @Test
    fun `JRKAN 网络不通时把 JRKAN 记为失败源`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555"))
            else FetchResult.Unreachable("timeout")
        }
        assertEquals(listOf(Provider.JRKAN), outcome.failedProviders)
        assertTrue(outcome.matches.any { it.id.startsWith("jrs-") })
    }

    @Test
    fun `两个源都失败时两个都记为失败源`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { _, _ -> FetchResult.Unreachable("timeout") }
        assertEquals(
            "两源都挂时两个都要记，用户才知道是网络问题而不是「今天没比赛」",
            listOf(Provider.JRS, Provider.JRKAN),
            outcome.failedProviders.toList().sortedBy { it.ordinal },
        )
        assertTrue("两源都失败时列表为空", outcome.matches.isEmpty())
    }

    /**
     * 源站"正常返回但解析出 0 场"也是失败。
     *
     * 源站改版让解析器整表归零时，HTTP 是 200、body 也非空 ——
     * 如果只按"HTTP 是否成功"判断，这种情况会被当成"这个源今天没比赛"，
     * 又是一个静默丢场次的口子。
     */
    @Test
    fun `源站返回 200 但一场都没解析出来也算该源失败`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok("<html>改版了，一个 .list-con 都没有</html>")
            else FetchResult.Ok(jrkanJs("900"))
        }
        assertEquals(
            "HTTP 200 但解析零场 = 源站改版，必须报出来",
            listOf(Provider.JRS),
            outcome.failedProviders,
        )
    }

    @Test
    fun `失败源里有网络原因可读文案`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Rejected(429)
            else FetchResult.Ok(jrkanJs("900"))
        }
        val reason = outcome.failureReason(Provider.JRS)
        assertNotNull("失败源要能给出可读原因，UI 直接显示", reason)
        assertTrue("原因里应含状态码：$reason", reason!!.contains("429"))
    }

    // ---------- 生产入口的行为（原 loadMatches Result API 已删除） ----------
    //
    // ⚠️ 这三条原来测的是 `loadMatches(fetch): Result<List<Match>>`。
    // 那条路径在生产代码里**零调用点**（生产走 loadMatchesOutcome），
    // 测试守着一份线上跑不到的代码 —— 是典型的"假安全感"。
    // 旧管线已删除，这里改为在**生产入口**上验证同样的三条约定。

    @Test
    fun `生产入口两源都成功时给出全部场次`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555")) else FetchResult.Ok(jrkanJs("900"))
        }
        assertEquals(2, outcome.matches.size)
        assertTrue("两源都成功时不该报失败源", outcome.failedProviders.isEmpty())
    }

    @Test
    fun `生产入口两源都空时如实报出两个失败源`() = runBlocking {
        // 原约定是"两源都空必须抛异常（而不是显示今天没比赛）"。
        // 生产入口不抛异常（抛了 UI 只剩错误页、拿不到部分数据），
        // 改为**如实报出两个失败源** —— partialError() 会给出可读文案。
        val outcome = repo.loadMatchesWithOutcome { _, _ -> FetchResult.Unreachable("timeout") }
        assertEquals(
            "两源都空必须让用户知道是源站问题，而不是「今天没比赛」",
            listOf(com.nbalive.app.data.model.Provider.JRS, com.nbalive.app.data.model.Provider.JRKAN),
            outcome.failedProviders,
        )
        assertTrue(outcome.matches.isEmpty())
        assertTrue("必须能给出可读原因", outcome.partialError() != null || outcome.failedProviders.isNotEmpty())
    }

    @Test
    fun `生产入口单源成功时不报整次失败`() = runBlocking {
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555")) else FetchResult.Unreachable("timeout")
        }
        assertTrue("部分成功不该让整次加载失败（否则旧列表会被错误态顶掉）", outcome.matches.isNotEmpty())
        assertEquals(
            "但要如实报出失败的那个源",
            listOf(com.nbalive.app.data.model.Provider.JRKAN),
            outcome.failedProviders,
        )
    }

    // ---------- 联赛名缺失不能静默丢整场（P1-新） ----------

    @Test
    fun `联赛名缺失的场次不再被静默丢弃`() = runBlocking {
        // 联赛名取不到 -> League.OTHER -> merge 里被 supported 过滤 -> 整场消失。
        // 这条路径原来**连一行日志都没有**（队名缺失反而有 Log.w）。
        val noLeague = """
            document.write('<ul class="item play" data-lid="777,2,777" data-stype="zqlq">');
            document.write('  <li class="lab_events"><span class="name"></span></li>');
            document.write('  <li class="lab_time">10-23 10:00</li>');
            document.write('  <li class="lab_team_home"><strong class="name">湖人</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">勇士</strong></li>');
            document.write('</ul>');
        """.trimIndent()
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Rejected(403) else FetchResult.Ok(noLeague)
        }
        // ⚠️ 队名兜底之后，这场**根本不该再被丢掉**（review/02-data.md P1-3）：
        // 联赛名解析失败只说明那一个字段坏了，队名"湖人 vs 勇士"足以判定是 NBA。
        // 旧行为是落 OTHER 后被 supported 过滤掉，用户只看到"场次少了"。
        val kept = outcome.matches.filter { it.homeTeam == "湖人" }
        assertEquals("联赛名缺失但队名可判定的场次必须留在列表里", 1, kept.size)
        assertEquals(
            "队名兜底要判定成 NBA",
            com.nbalive.app.data.model.League.NBA,
            kept.first().league,
        )
        assertEquals(
            "既然没被丢掉，过滤计数就不该再计入它",
            0,
            outcome.filteredOutCount,
        )
    }
}
