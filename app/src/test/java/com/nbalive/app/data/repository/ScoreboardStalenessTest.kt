package com.nbalive.app.data.repository

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.TestPaths
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **陈旧比分的剔除**（review D7 的承诺、v1.2.26 接线补全）。
 *
 * ## 修的是什么
 *
 * `Snapshot.fetchedAtMs` 在 D7 就写下了（注释与 `ScoreboardFixesTest` 都写明
 * "UI 靠它剔除陈旧比分"），但生产里**没有任何消费点**：
 * `MainViewModel.refreshScores` 只取 `Map<String, GameScore>`，时间戳被丢弃。
 * ESPN 持续失败时，卡片把几十分钟前的旧比分当实时比分无标记常显 ——
 * 对用户的误导比空白更糟（用户以为比赛就停在这个比分）。
 *
 * 现在：判据在 [ScoreboardRepository.isStale]，消费点在 [MainViewModel.refreshScores]
 * （陈旧 → 清空比分表，卡片如实降级）。本文件把两头钉住：判据边界 + 端到端剔除，
 * 外加一条源码锚点 —— 防止消费点再次蒸发（这正是本类测试存在的理由）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardStalenessTest {

    private val lock = Any()

    private val sample = """
        {"events":[{"competitions":[
          {"competitors":[
            {"homeAway":"home","score":"112","team":{"displayName":"Golden State Warriors","name":"Warriors"}},
            {"homeAway":"away","score":"95","team":{"displayName":"Los Angeles Lakers","name":"Lakers"}}
          ],
          "status":{"displayClock":"12:00","period":4,"type":{"state":"post","shortDetail":"Final"}}}]}]}
    """.trimIndent()

    // ---------------- 判据边界 ----------------

    @Test
    fun `isStale 判据边界`() {
        val repo = ScoreboardRepository(fetch = { _, _ -> FetchResult.Unreachable("unused") })
        val snap = snapshot(games = 1, fetchedAtMs = 1_000L)
        assertFalse("刚抓到不算陈旧", repo.isStale(snap, 1_000L))
        assertFalse("恰好到上限仍算新鲜（判据是严格大于，留一次 20 秒 tick 的余地）",
            repo.isStale(snap, 1_000L + ScoreboardRepository.STALE_AFTER_MS))
        assertTrue("超过上限立刻陈旧",
            repo.isStale(snap, 1_000L + ScoreboardRepository.STALE_AFTER_MS + 1L))
    }

    @Test
    fun `空快照永远不算陈旧`() {
        val repo = ScoreboardRepository(fetch = { _, _ -> FetchResult.Unreachable("offline") })
        assertFalse(
            "EMPTY / 从未成功（fetchedAtMs=0）没有比分可标旧，由调用方按 isEmpty 处理",
            repo.isStale(ScoreboardRepository.Snapshot.EMPTY, Long.MAX_VALUE / 2),
        )
    }

    // ---------------- 端到端接线（修复前的盲区） ----------------

    /**
     * 线上时序：首刷成功 → ESPN 持续失败 → 20 秒 ticker 继续刷新。
     * 修复前：`load()` 正确保留 last-good，但 VM 把旧 GameScore 原样重发上屏，
     * `fetchedAtMs` 从头到尾没人读 —— 旧比分永远挂着。
     */
    @Test
    fun `持续失败超过陈旧上限后 上屏比分必须被清空`() = runStaleTest {
        var wall = 0L
        var failing = false
        val repo = ScoreboardRepository(
            fetch = { _, _ ->
                // ⚠️ 可见性纪律：fetch 在 Dispatchers.IO 线程上跑，
                // 裸 var 跨线程读写没有保证（本仓库实测过并发丢更新）。
                val shouldFail = synchronized(lock) { failing }
                if (shouldFail) FetchResult.Unreachable("offline") else FetchResult.Ok(sample)
            },
            ttlMs = 0L,
        )
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(nbaMatch())) },
            scoreboard = repo,
            clock = { synchronized(lock) { wall } },
            wallClock = { synchronized(lock) { wall } },
        )
        advanceUntilIdle()

        vm.refreshScores().join()
        assertTrue("夹具前提：首刷必须把比分画上屏", vm.scores.value.isNotEmpty())

        // 时间前进到上限 + 1 分钟之后（先推进、再失败，顺序与真实线上无关：
        // join 保证这一次抓取开始时读到的就是新 wall），此后 ESPN 全挂
        //（load 会正确保留 last-good，fetchedAtMs 停在 0）
        synchronized(lock) {
            wall = ScoreboardRepository.STALE_AFTER_MS + 60_000L
            failing = true
        }
        vm.refreshScores().join()

        assertTrue(
            "last-good 已陈旧（抓取时刻停在 0，现在钟已越过上限）—— 上屏比分必须被清空，" +
                "而不是把几分钟前的数字当实时比分挂着（D7 承诺的消费点）",
            vm.scores.value.isEmpty(),
        )
        assertTrue("比分剔除不许影响列表本身", vm.listState.value.matches.isNotEmpty())
    }

    /** 失败但**未超**上限：继续显示 last-good（容忍瞬时抖动，不许一断网就摘比分）。 */
    @Test
    fun `陈旧上限之内 瞬时失败继续显示上一次成功比分`() = runStaleTest {
        var wall = 0L
        var failing = false
        val repo = ScoreboardRepository(
            fetch = { _, _ ->
                val shouldFail = synchronized(lock) { failing }
                if (shouldFail) FetchResult.Unreachable("offline") else FetchResult.Ok(sample)
            },
            ttlMs = 0L,
        )
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(nbaMatch())) },
            scoreboard = repo,
            clock = { synchronized(lock) { wall } },
            wallClock = { synchronized(lock) { wall } },
        )
        advanceUntilIdle()
        vm.refreshScores().join()
        assertTrue("夹具前提：首刷成功", vm.scores.value.isNotEmpty())

        synchronized(lock) {
            wall = 60_000L // 1 分钟 < 2 分钟上限
            failing = true
        }
        vm.refreshScores().join()
        assertTrue("上限内的失败不许把比分摘掉", vm.scores.value.isNotEmpty())
    }

    // ---------------- 接线守卫：消费点不许再次蒸发 ----------------

    /**
     * v1.2.26 的教训本体：`fetchedAtMs` 曾经"数据侧齐全、测试侧钉死、生产零消费"。
     * 这条源码锚点把消费点钉成可执行证据 —— 将来谁删掉 VM 里那句 isStale，
     * 本测试要红，而不是继续全绿。
     */
    @Test
    fun `refreshScores 必须真的消费 isStale`() {
        val vm = GuardSourceScan.stripNonCode(productionFile("MainViewModel.kt").readText())
        val body = GuardSourceScan.functionBody(vm, "fun refreshScores(")
        assertTrue(
            "MainViewModel.refreshScores 不再消费 isStale —— D7 的「剔除陈旧比分」退回半接线",
            body.contains("isStale("),
        )
        val repo = GuardSourceScan.stripNonCode(productionFile("ScoreboardRepository.kt").readText())
        assertTrue(
            "isStale 必须继续消费 fetchedAtMs（换成别的钟就不算陈旧剔除）",
            repo.contains("snapshot.fetchedAtMs"),
        )
    }

    // ---------------------------------------------------------------

    private fun nbaMatch(home: String = "勇士", away: String = "湖人") = Match(
        id = "m1",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )

    private fun snapshot(games: Int, fetchedAtMs: Long): ScoreboardRepository.Snapshot {
        val one = requireNotNull(EspnScoreboard.parse(sample)?.takeIf { it.isNotEmpty() }) {
            "sample 夹具必须能解析出至少 1 场（见 ScoreboardFixesTest 的夹具自检教训）"
        }.single()
        val list = List(games.coerceAtLeast(1)) { one.copy(homeName = "T$it", awayName = "A$it") }
        return ScoreboardRepository.Snapshot(list, setOf(League.NBA), fetchedAtMs)
    }

    private fun runStaleTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private fun productionFile(name: String): File {
        val root = TestPaths.productionRoot
        return root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("找不到 $name —— 这条守卫会静默全绿，所以直接失败")
    }
}
