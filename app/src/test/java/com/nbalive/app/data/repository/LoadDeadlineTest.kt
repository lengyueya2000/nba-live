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
 *
 * ## 判据窗口为什么是 250..1500ms（2026-10-29 实测，见 review/flake-audit.md）
 *
 * 本类注入 `loadDeadlineMs = 300L`，所以「honor 注入值」的实现耗时必然 ≈ 300ms
 * 加上"取消后的续体调度"延迟：
 *  - **下限 250ms（低于注入的 300ms）**：抓「根本没等就返回 / deadline 被改短」。
 *  - **上限 1500ms**：抓「注入值被忽略、退化成几秒硬编码」——
 *    旧的单边 `< 5_000` 抓不住它：实验一（生产写死 `3_000L`）本条耗时 **3.008s，
 *    旧断言仍然绿**；收紧后才变红（实验二）。
 *    同时给慢机留余量：task-17 的 18 轮里（含 15 个忙等进程占满 16 核的全核负载档）
 *    本条耗时最大 **0.315s** ⇒ 上限是实测最大值的 4.8 倍。
 *  - **不建议改用虚拟时钟**：deadline 跑在 `withContext(Dispatchers.IO)` 里
 *    （`MatchRepository.kt:586,595`），`runTest` 的虚拟时钟不进真实 dispatcher，
 *    只改测试无法虚拟化这条路径；要做就得改生产（注入 dispatcher/时钟）。
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

        // ⚠️ **双边窗口**，不是单边上界（审计口径：判据过松 = 红了也说明不了什么）。
        // 理由与实测依据见类 KDoc「判据窗口为什么是 250..1500ms」。
        assertTrue(
            "整体必须在**注入的 300ms** deadline 内结束（不是「随便小于几秒」）：" +
                "实测耗时 ${elapsedMs}ms，判据要求落在 250..1500ms 窗口内。\n" +
                "低于 250ms ⇒ 根本没等 deadline 就返回（或 deadline 被改短）；" +
                "高于 1500ms ⇒ deadline 被忽略或改坏（最坏会等近 3 分钟）。",
            elapsedMs in 250L..1_500L,
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