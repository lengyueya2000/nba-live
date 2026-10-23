package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 播放期间列表刷新的回归测试。
 *
 * 背景：原来"同时在播"面板的数据只在打开 App 和手动下拉刷新时更新，播放页又没有
 * 刷新入口。**第一版改成播放期间每 2 分钟自动刷新**，用户随即要求去掉：
 * 自动刷新不可控（看球时背后持续请求源站、还怕抖），改为面板上的刷新按钮手动触发。
 *
 * 这些用例把 viewModelScope 的 Main dispatcher 换成测试调度器，用虚拟时钟推进，
 * 全程不触网（loadMatches 注入；进入播放路径的场次不带线路，
 * loadSignals 会零请求短路返回空信号）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    /** 断言目标就是播放器"同时在播"面板实际消费的那份过滤结果。 */
    private fun liveIds(vm: MainViewModel): List<String> =
        vm.listState.value.matches.filter { it.status == MatchStatus.LIVE }.map { it.id }

    private suspend fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    /**
     * 等 playMatch 里的 loadSignals 落地。
     *
     * loadSignals 内部是 withContext(Dispatchers.IO)——真实线程，虚拟时钟等不到它。
     * 不等到它跑完，它的回程可能落在已 resetMain 的 Main 上，抛
     * "Module with the Main dispatcher had failed to initialize"。
     *
     * 用纯测试调度器自旋等它：不挂起真实 dispatcher、不推进虚拟时钟，
     * 避免 runTest 里混用真实/虚拟时间带来的不确定性。
     */
    private fun TestScope.waitForLoadSignals(vm: MainViewModel) {
        var spins = 0
        while (vm.detailState.value.loadingSignals) {
            check(spins < 10_000) { "loadSignals 迟迟未落地：IO 线程被饿死，或实现已改成发真实请求" }
            spins++
            runCurrent()
            Thread.yield()
        }
    }

    private fun vmTest(block: suspend TestScope.() -> Unit) {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            block()
        }
        // ⚠️ resetMain 必须等 runTest 完全收尾（含它内部的 advanceUntilIdle）之后：
        // 收尾阶段视图模型协程还在 resume，落在已重置的 Main 上就是上面注释里那个异常。
        Dispatchers.resetMain()
    }

    private fun match(id: String, home: String, away: String, status: MatchStatus) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-23 07:00",
        startAtMillis = null,
        status = status,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )

    @Test
    fun `进入播放页后不会在后台定时刷新列表`() = vmTest {
        // 用户明确要求：去掉播放期间的 2 分钟自动刷新，改为面板按钮手动触发。
        // 这条守住"没有定时器"——防止以后有人把循环加回来。
        var invocation = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(loadMatches = { invocation++; Result.success(listOf(watching)) })
        settle()
        assertEquals(1, invocation)

        try {
            vm.playMatch(watching)
            settle()
            waitForLoadSignals(vm)
            assertEquals("进场时不应主动刷新列表", 1, invocation)

            advanceTimeBy(10 * 60 * 1000L) // 看球十分钟
            runCurrent()
            assertEquals("播放期间不应有定时刷新", 1, invocation)
        } finally {
            // 断言失败也要把视图模型停干净：否则 runTest 收尾会被后台循环挂住
            vm.back()
        }
    }

    @Test
    fun `点刷新按钮后新开赛的场次进入同时在播`() = vmTest {
        var invocation = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = {
                invocation++
                val fresh = match("newcomer", "骑士", "76人", MatchStatus.LIVE)
                when (invocation) {
                    1 -> Result.success(listOf(watching))            // 打开 App 时的快照
                    else -> Result.success(listOf(watching, fresh))  // 点刷新后：新场次开赛了
                }
            },
        )
        settle()
        assertEquals("打开 App 时就一场正在直播", listOf("watching"), liveIds(vm))

        vm.playMatch(watching)
        settle()
        waitForLoadSignals(vm)
        try {
            assertEquals("进场后面板应保持进场时的快照", listOf("watching"), liveIds(vm))

            vm.refresh() // 用户点"同时在播"面板上的刷新按钮
            settle()
            assertEquals("点刷新后新开赛的场次要出现", listOf("watching", "newcomer"), liveIds(vm))
        } finally {
            // 断言失败也要把视图模型停干净：否则 runTest 收尾会被后台循环挂住
            vm.back()
        }
    }

    @Test
    fun `点刷新按钮时会显示加载中，失败时给出错误`() = vmTest {
        var invocation = 0
        val loadingDuringLoad = mutableListOf<Boolean>()
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        // 构造期 lambda 还不能引用 vm 自己：用可空 holder，运行时才读
        var vmHolder: MainViewModel? = null
        vmHolder = MainViewModel(
            loadMatches = {
                loadingDuringLoad += vmHolder!!.listState.value.loading
                invocation++
                if (invocation == 1) {
                    Result.success(listOf(watching))
                } else {
                    Result.failure(IOException("两个数据源都没有返回赛事"))
                }
            },
        )
        val vm = vmHolder!!
        settle()
        assertEquals(listOf("watching"), liveIds(vm))

        vm.playMatch(watching)
        settle()
        waitForLoadSignals(vm)

        loadingDuringLoad.clear()
        try {
            vm.refresh() // 播放中手动点刷新
            settle()
            assertTrue("点刷新期间要显示加载中", loadingDuringLoad.single())
            assertEquals("两个数据源都没有返回赛事", vm.listState.value.error)
        } finally {
            vm.back()
        }
    }
}
