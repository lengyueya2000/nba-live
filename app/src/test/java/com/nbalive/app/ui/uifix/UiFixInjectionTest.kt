package com.nbalive.app.ui.uifix

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.repository.MatchRepository
import com.nbalive.app.data.repository.ScoreboardRepository
import com.nbalive.app.ui.MainViewModel
import com.nbalive.app.ui.UpdateCheckPolicy
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态层的**可注入性**行为测试（U5 / U16，review/fix/ui-state.md）。
 *
 * ## 为什么这本身就是判据
 *
 * U5 的结论是"三条链路在 JVM 单测里驱动不了"。唯一的证明方式是**真的用注入的
 * 依赖把链路驱动起来** —— 本文件就是这么做的：
 *  - 比分链：注入 `scoreboard`（假 fetch）+ 时钟，断言 `scores` 落地；
 *  - 检查更新链：注入 `fetchRelease`，断言 `updateState` 落 `Available`/`UpToDate`；
 *  - 状态老化链：注入 `now`，断言 3.5 小时窗口边界能钉住（改前只能用真实时钟）。
 *
 * 改前这些用例**根本写不出来**（`ScoreboardRepository()` / `HttpClient.shared` /
 * `System.currentTimeMillis()` 都写在函数体里）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiFixInjectionTest {

    /** 与 `MainViewModelTest` 同一套调度器纪律：Main 换成测试调度器，失败也 resetMain。 */
    private fun vmTest(block: suspend TestScope.() -> Unit) {
        try {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                block()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    /**
     * 自旋等一个由**真实线程**（`withContext(Dispatchers.IO)`）回程写下的状态。
     *
     * 虚拟时钟等不到真实线程的挂起点（`MainViewModelTest` 里同一个理由），
     * 所以用"跑一遍待办 → 让出时间片"的自旋。
     */
    private fun TestScope.waitUntil(what: String, ready: () -> Boolean) {
        var spins = 0
        while (!ready()) {
            check(spins < 20_000) { "$what 迟迟未落地：IO 线程被饿死，或实现又走了真实网络" }
            spins++
            runCurrent()
            Thread.yield()
        }
    }

    private fun match(
        id: String,
        home: String,
        away: String,
        league: League,
        status: MatchStatus,
        startAtMillis: Long?,
        timeLabel: String,
    ): Match = Match(
        id = id,
        league = league,
        leagueRawName = league.name,
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = timeLabel,
        startAtMillis = startAtMillis,
        status = status,
        signalCount = 1,
        sources = emptyList(),
        jrsDetailId = null,
    )

    // ---------------- U5：比分链 ----------------

    @Test
    fun `U5 注入的比分源与时钟能驱动 refreshScores`() = vmTest {
        val nba = match(
            id = "m1", home = "勇士", away = "湖人", league = League.NBA,
            status = MatchStatus.LIVE, startAtMillis = START, timeLabel = "10:00",
        )
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(nba)) },
            // ⚠️ 改前这里没法注入：VM 函数体里就地 `ScoreboardRepository()`
            scoreboard = ScoreboardRepository(fetch = { _, _ -> FetchResult.Ok(ESPN_SAMPLE) }),
            clock = { 0L },
            wallClock = { 0L },
        )
        settle()
        assertEquals(1, vm.listState.value.matches.size)

        vm.refreshScores()
        waitUntil("比分落地") { vm.scores.value.isNotEmpty() }

        val score = vm.scores.value["m1"]
        assertNotNull("注入的假比分板必须能按场次匹配上（走 TeamNameBridge.findGame）", score)
        assertEquals(112, score!!.homeScore)
        assertEquals(95, score.awayScore)
    }

    @Test
    fun `U5 比分源拿不到数据时静默 不清空既有比分`() = vmTest {
        val nba = match(
            id = "m1", home = "勇士", away = "湖人", league = League.NBA,
            status = MatchStatus.LIVE, startAtMillis = START, timeLabel = "10:00",
        )
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(nba)) },
            scoreboard = ScoreboardRepository(fetch = { _, _ -> FetchResult.Unreachable("offline") }),
            clock = { 0L },
            wallClock = { 0L },
        )
        settle()
        vm.refreshScores()
        // 失败是静默的：比分表保持空，不抛异常、不影响列表。
        // 这里自旋让 IO 回程落地，避免"还没跑完就断言空表"的假通过。
        repeat(50) {
            runCurrent()
            Thread.yield()
        }
        assertTrue("比分拿不到时只能是空表（绝不能拖垮列表）", vm.scores.value.isEmpty())
        assertEquals("列表不受影响", 1, vm.listState.value.matches.size)
    }

    // ---------------- U5 / U12：检查更新链 ----------------

    @Test
    fun `U5 注入的 fetchRelease 能驱动 checkForUpdate 落 Available`() = vmTest {
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            fetchRelease = {
                FetchResult.Ok(
                    """{"tag_name":"v9.9.9","html_url":"https://example.com/release","body":"notes"}""",
                )
            },
        )
        settle()
        vm.checkForUpdate()
        advanceUntilIdle()

        val decision = vm.updateState.value
        assertTrue("注入的响应必须能推到 Available，实际=$decision", decision is UpdateCheckPolicy.Decision.Available)
        assertEquals("9.9.9", (decision as UpdateCheckPolicy.Decision.Available).latest)
        assertEquals("https://example.com/release", decision.downloadUrl)
        assertEquals("检查完必须把「正在检查」复位", false, vm.checkingUpdate.value)
    }

    @Test
    fun `U5 网络失败静默落 UpToDate 且不弹任何错误`() = vmTest {
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            fetchRelease = { FetchResult.Unreachable("offline") },
        )
        settle()
        vm.checkForUpdate()
        advanceUntilIdle()
        assertEquals(
            "GitHub 不通必须静默（只把那一行显示成「已是最新」）",
            UpdateCheckPolicy.Decision.UpToDate,
            vm.updateState.value,
        )
    }

    @Test
    fun `U5 防御性：fake fetch 每次调用都真的被请求`() = vmTest {
        var calls = 0
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            fetchRelease = {
                calls++
                FetchResult.Ok("""{"tag_name":"v1.0.0"}""")
            },
        )
        settle()
        vm.checkForUpdate()
        advanceUntilIdle()
        assertEquals("检查更新必须真的发一次请求（fake 计数）", 1, calls)
        // 没有 html_url：走 fallbackReleaseUrl 兜底
        val d = vm.updateState.value
        assertTrue(d is UpdateCheckPolicy.Decision.UpToDate || d is UpdateCheckPolicy.Decision.Available)
    }

    // ---------------- U16：老化同时更新详情页/播放页 ----------------

    @Test
    fun `U16 老化必须同时更新详情页与播放页持有的 match 副本`() = vmTest {
        val live = match(
            id = "m-live", home = "湖人", away = "勇士", league = League.NBA,
            status = MatchStatus.LIVE, startAtMillis = START, timeLabel = "10:00",
        )
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(live)) },
            loadSignals = { MatchRepository.Detail(signals = emptyList()) },
            // 注入"开赛 5 小时后"：3.5 小时窗口必然已过 → LIVE 应老化成 FINISHED
            now = { ZonedDateTime.ofInstant(Instant.ofEpochMilli(START + 5 * 3_600_000L), MatchStatusClock.SOURCE_ZONE) },
        )
        settle()

        // 进场拉线路：详情/播放页都会拿到那份**旧副本**（status = LIVE）
        vm.playMatch(live)
        settle()
        assertEquals("夹具前提：进播放页时还是「直播中」", MatchStatus.LIVE, vm.playerState.value.match?.status)
        assertEquals(MatchStatus.LIVE, vm.detailState.value.match?.status)

        // 老化（界面上由 60 秒 ticker 调；这里直接驱动）
        vm.ageListStatuses()

        assertEquals("列表老化", MatchStatus.FINISHED, vm.listState.value.matches.single().status)
        assertEquals(
            "U16：详情页持有的是同一场比赛的旧副本，也必须跟着老化 —— " +
                "否则用户停在详情页时状态标签一直是「直播中」",
            MatchStatus.FINISHED,
            vm.detailState.value.match?.status,
        )
        assertEquals(
            "U16：播放页同理",
            MatchStatus.FINISHED,
            vm.playerState.value.match?.status,
        )
    }

    @Test
    fun `U16 没到窗口时不许乱改状态`() = vmTest {
        val live = match(
            id = "m-live", home = "湖人", away = "勇士", league = League.NBA,
            status = MatchStatus.LIVE, startAtMillis = START, timeLabel = "10:00",
        )
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(live)) },
            loadSignals = { MatchRepository.Detail(signals = emptyList()) },
            now = { ZonedDateTime.ofInstant(Instant.ofEpochMilli(START + 30 * 60_000L), MatchStatusClock.SOURCE_ZONE) },
        )
        settle()
        vm.playMatch(live)
        settle()
        vm.ageListStatuses()
        assertEquals("窗口内仍是直播中", MatchStatus.LIVE, vm.listState.value.matches.single().status)
        assertEquals(MatchStatus.LIVE, vm.playerState.value.match?.status)
    }

    // ---------------- P18：恢复条上报只置标记 ----------------

    @Test
    fun `P18 恢复条上报只置疑似标记 绝不换线`() = vmTest {
        val web = match(
            id = "m-web", home = "湖人", away = "勇士", league = League.NBA,
            status = MatchStatus.LIVE, startAtMillis = START, timeLabel = "10:00",
        )
        val pageA = "https://play.example.com/paps.html?id=A"
        val pageB = "https://play.example.com/paps.html?id=B"
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(web)) },
            loadSignals = {
                MatchRepository.Detail(
                    signals = listOf(
                        StreamSource(id = "s0", label = "信号1", provider = Provider.JRS, pageUrl = pageA),
                        StreamSource(id = "s1", label = "信号2", provider = Provider.JRS, pageUrl = pageB),
                    ),
                )
            },
        )
        settle()
        vm.playMatch(web)
        // 两次 settle：playMatch 的 loadJob 跑完后才会把 play() 排进队列，
        // 再跑一轮才真正落到 webFallbackUrl
        settle()
        settle()
        assertEquals("夹具前提：首条线路只能走网页承载", pageA, vm.playerState.value.webFallbackUrl)
        assertEquals(0, vm.playerState.value.selectedIndex)

        vm.onDeadEndSuspectOnly()

        assertTrue(
            "P18：恢复条出现（1.5s）就该置疑似 —— 否则 1.5~9.5s 那 8 秒里 " +
                "blocksAutoEnterPip 不生效，按 Home 会进一块坏小窗",
            vm.playerState.value.playbackSuspect,
        )
        assertEquals("绝不许换线（换线会让恢复条与选择窗口消失）", 0, vm.playerState.value.selectedIndex)
        assertEquals("网页兜底地址不许被改", pageA, vm.playerState.value.webFallbackUrl)
        assertNull("也不许留提示（提示会盖住恢复条）", vm.playerState.value.errorMessage)
    }

    // ---------------- fix-data 的短缓存：forceRefresh 的传递 ----------------

    @Test
    fun `冷启动不强制刷新 用户主动刷新必须强制`() = vmTest {
        val forces = mutableListOf<Boolean>()
        val vm = MainViewModel(
            loadOutcome = { _, forceRefresh ->
                forces += forceRefresh
                MatchRepository.LoadOutcome(matches = emptyList())
            },
        )
        advanceUntilIdle()
        assertEquals(
            "冷启动必须 force=false：命中 30 秒短缓存时连一次 HTTP 都不发",
            listOf(false),
            forces.toList(),
        )

        vm.refresh()
        advanceUntilIdle()
        assertEquals(
            "下拉刷新/重试按钮必须 force=true：否则用户拿到最多 30 秒前的列表（D5 的回归）",
            listOf(false, true),
            forces.toList(),
        )
    }

    private companion object {
        /** 固定的开赛时刻（epoch millis），避免用例随时钟漂移。 */
        const val START = 1_700_000_000_000L

        /**
         * ESPN 比分板的**真实形态**裁剪样本（未开赛的场次没有 score 字段）。
         * 与 `NewFeaturesTest` 用的是同一形态；主客与 `match()` 的 home/away 对齐。
         */
        val ESPN_SAMPLE = """
            {"events":[{"date":"2026-10-31T02:00Z","competitions":[{
              "competitors":[
                {"homeAway":"home","score":"112","team":{"displayName":"Golden State Warriors","name":"Warriors"}},
                {"homeAway":"away","score":"95","team":{"displayName":"Los Angeles Lakers","name":"Lakers"}}
              ],
              "status":{"displayClock":"12:00","period":4,"type":{"state":"post","shortDetail":"Final"}}
            }]}]}
        """.trimIndent()
    }
}