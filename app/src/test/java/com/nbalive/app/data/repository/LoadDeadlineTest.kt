package com.nbalive.app.data.repository

import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.remote.FetchResult
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 列表加载的**整体 deadline**（review/02-data.md P2-3）。
 *
 * ## 要解决什么
 *
 * 单源 3 次尝试 × `callTimeout = 35s`，两源并发 => 约 105s；
 * 之后状态校正最多 16 场 / 4 并发 = 4 批 × 35s = 140s。
 * 最坏情况用户盯着转圈接近 **3 分钟**，期间无任何 UI 超时、
 * 也没有"放弃并给个可重试提示"的出口。
 *
 * ## 规则
 *
 * 整体必须在 deadline 内结束，超时返回"部分结果 + 失败源"，
 * 而不是无限等 —— 用户宁可看到"部分场次 + 可重试"，也不要盯着转圈。
 */
class LoadDeadlineTest {

    @Test
    fun `源站整体无响应时 在 deadline 内返回而不是无限等`() = runBlocking {
        val started = System.nanoTime()
        val outcome = MatchRepository(loadDeadlineMs = 300L).loadMatchesWithOutcome { _, _ ->
            // 模拟"连接建立后就再也不回数据"：永远挂起
            kotlinx.coroutines.delay(60_000)
            FetchResult.Unreachable("timeout")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            "整体必须有 deadline，否则最坏要等近 3 分钟（实际耗时 ${elapsedMs}ms）",
            elapsedMs < 5_000,
        )
        assertTrue(
            "超时后必须报出失败源，而不是静默返回空列表让用户以为今天没比赛",
            outcome.failedProviders.isNotEmpty(),
        )
        assertTrue("超时也必须能返回（不抛异常）", outcome.matches.isEmpty())
    }

    @Test
    fun `超时时把两个源都记为失败`() = runBlocking {
        val outcome = MatchRepository(loadDeadlineMs = 200L).loadMatchesWithOutcome { _, _ ->
            kotlinx.coroutines.delay(60_000)
            FetchResult.Unreachable("timeout")
        }
        assertTrue(
            "两个源都没在 deadline 内回来，就该都记失败（实际：${outcome.failedProviders}）",
            outcome.failedProviders.containsAll(listOf(Provider.JRS, Provider.JRKAN)),
        )
    }

    @Test
    fun `正常响应远快于 deadline 时不受影响`() = runBlocking {
        // deadline 不能把正常路径也拖慢或改坏：给一个足够大的上限，
        // 源站立刻返回 -> 结果照常
        val outcome = MatchRepository(loadDeadlineMs = 60_000L).loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml()) else FetchResult.Ok(jrkanJs())
        }
        assertTrue("正常路径必须有数据", outcome.matches.isNotEmpty())
        assertTrue("正常路径不该报失败源", outcome.failedProviders.isEmpty())
    }

    private fun jrsHtml() = """
        <div class="list"><p class="list-title">2026-10-23</p>
        <a href="/live/555.html" class="list-con">
          <div class="list-team-con"><p class="list-team-name">湖人</p></div>
          <div class="list-team-con"><p class="list-team-name">凯尔特人</p></div>
          <p class="list-info-league">NBA</p><p class="list-info-time">10-23 07:00</p>
        </a></div>
    """.trimIndent()

    private fun jrkanJs() = """
        document.write('<ul class="item play active" data-lid="3931527,2,3931527">');
        document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
        document.write('  <li class="lab_time">10-23 08:00</li>');
        document.write('  <li class="lab_team_home"><strong class="name">勇士</strong></li>');
        document.write('  <li class="lab_team_away"><strong class="name">太阳</strong></li>');
        document.write('  <li class="lab_channel"><a class="item" href="' + getPlayUrl("line1", "824761") + '"></a></li>');
        document.write('</ul>');
    """.trimIndent()
}