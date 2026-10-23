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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * "正在播放"期间的列表自动刷新回归测试。
 *
 * 背景："同时在播"面板的数据来自 listState，而 listState 原来只在打开 App 和
 * 手动下拉刷新时更新，播放页又没有刷新入口 —— 看一场两个小时的比赛，中途开赛的
 * 新场次永远不出现在面板里，已经结束的也一直挂着。
 *
 * 这些用例把 viewModelScope 的 Main dispatcher 换成测试调度器，用虚拟时钟推进
 * 自动刷新周期，全程不触网（loadMatches 注入；进入播放路径的场次不带线路，
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

    /** 推进一个自动刷新周期（+10ms 防止边界处任务恰好在目标时刻被排除）。 */
    private suspend fun TestScope.tick() {
        advanceTimeBy(MainViewModel.AUTO_REFRESH_INTERVAL_MS + 10)
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
     * detailState.loadingSignals 落回 false 即说明这条链路跑完了。
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
    fun `正在播放时会自动刷新列表，新开赛的场次随后进入同时在播`() = vmTest {
        var invocation = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = {
                invocation++
                val fresh = match("newcomer", "骑士", "76人", MatchStatus.LIVE)
                when (invocation) {
                    1 -> Result.success(listOf(watching))             // 打开 App 时的快照
                    2 -> Result.success(
                        listOf(watching, fresh.copy(status = MatchStatus.UPCOMING)),
                    )                                                 // 进场即时刷新：还没开赛
                    else -> Result.success(listOf(watching, fresh))   // 两分钟后开赛了
                }
            },
        )
        settle()
        assertEquals("打开 App 时就一场正在直播", listOf("watching"), liveIds(vm))

        vm.playMatch(watching)
        settle()
        waitForLoadSignals(vm)
        try {
            assertEquals("进场刷新后新场次未开赛，面板仍只有一场", listOf("watching"), liveIds(vm))
            tick()
            assertEquals(
                "两分钟后新场次开赛，必须出现在同时在播面板",
                listOf("watching", "newcomer"),
                liveIds(vm),
            )
        } finally {
            // 断言失败也要把后台循环停下：否则 runTest 收尾会被无限刷新挂住
            vm.back()
        }
    }

    @Test
    fun `播放中的静默刷新不转圈，失败时保留旧列表`() = vmTest {
        var invocation = 0
        val loadingDuringLoad = mutableListOf<Boolean>()
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        // 构造期 lambda 还不能引用 vm 自己：用可空 holder，运行时才读
        var vmHolder: MainViewModel? = null
        vmHolder = MainViewModel(
            loadMatches = {
                loadingDuringLoad += vmHolder!!.listState.value.loading
                invocation++
                if (invocation <= 2) {
                    Result.success(listOf(watching, match("newcomer", "骑士", "76人", MatchStatus.LIVE)))
                } else {
                    Result.failure(IOException("两个数据源都没有返回赛事"))
                }
            },
        )
        val vm = vmHolder!!
        settle()
        assertTrue("启动刷新期间要显示加载中", loadingDuringLoad.first())

        vm.playMatch(watching)
        settle()
        waitForLoadSignals(vm)
        assertEquals(listOf("watching", "newcomer"), liveIds(vm))

        tick() // 这一轮静默刷新失败
        try {
            assertEquals("后台刷新失败不能把旧列表清掉", listOf("watching", "newcomer"), liveIds(vm))
            assertFalse("播放期间不该闪加载中", vm.listState.value.loading)
            assertNull("播放期间静默失败不该弹错误", vm.listState.value.error)
            assertFalse("静默刷新期间不应出现加载中", loadingDuringLoad.drop(1).any { it })
        } finally {
            vm.back()
        }
    }

    @Test
    fun `退出播放页后停止自动刷新`() = vmTest {
        var invocation = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(loadMatches = { invocation++; Result.success(listOf(watching)) })
        settle()

        vm.playMatch(watching)
        settle()
        waitForLoadSignals(vm)
        val atBack = invocation

        vm.back()
        tick()
        tick()
        assertEquals("返回后循环必须停下，否则后台持续请求源站", atBack, invocation)
    }

    @Test
    fun `手动刷新仍会显示加载中并在失败时提示错误`() = vmTest {
        val loadingDuringLoad = mutableListOf<Boolean>()
        var vmHolder: MainViewModel? = null
        vmHolder = MainViewModel(
            loadMatches = {
                loadingDuringLoad += vmHolder!!.listState.value.loading
                Result.failure(IOException("网络断了"))
            },
        )
        val vm = vmHolder!!
        settle()
        assertTrue("启动刷新期间要显示加载中", loadingDuringLoad.single())
        assertFalse(vm.listState.value.loading)
        assertEquals("网络断了", vm.listState.value.error)

        loadingDuringLoad.clear()
        vm.refresh()
        settle()
        assertTrue("下拉刷新期间也要显示加载中", loadingDuringLoad.single())
        assertEquals("网络断了", vm.listState.value.error)
    }
}
