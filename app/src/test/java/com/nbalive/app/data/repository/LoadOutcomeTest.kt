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

    // ---------- 原有 Result API 不能被破坏 ----------

    @Test
    fun `原有 loadMatches 仍然可用`() = runBlocking {
        val result = repo.loadMatches { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555")) else FetchResult.Ok(jrkanJs("900"))
        }
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
    }

    @Test
    fun `两源都空时原有 API 仍然报错`() = runBlocking {
        val result = repo.loadMatches { _, _ -> FetchResult.Unreachable("timeout") }
        assertTrue("两源都空必须报错，而不是显示「今天没比赛」", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("数据源") == true)
    }

    @Test
    fun `单源成功时原有 API 不报错`() = runBlocking {
        val result = repo.loadMatches { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555")) else FetchResult.Unreachable("timeout")
        }
        assertTrue("部分成功不该让整次加载失败（否则旧列表会被错误态顶掉）", result.isSuccess)
        assertFalse(result.getOrThrow().isEmpty())
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
        // 联赛名缺失仍按"非 NBA/CBA"过滤（产品范围不变），
        // 但必须能被观测到：过滤数量要出现在结果里，不能静默。
        assertTrue(
            "被过滤掉的场次数要能被观测（实际: ${outcome.filteredOutCount}）",
            outcome.filteredOutCount >= 1,
        )
    }
}
