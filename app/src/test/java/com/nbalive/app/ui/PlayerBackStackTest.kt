package com.nbalive.app.ui

import androidx.lifecycle.SavedStateHandle
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.repository.MatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放页返回栈回归测试。
 *
 * ## 要解决什么
 *
 * 进入播放页有两条路：
 *  1. 列表卡片的「立即观看」/ 直播页 / 搜索页 / "同时在播"切场
 *     → `playMatch()`，**从 LIST 直接跳 PLAYER**
 *  2. 详情页的线路列表 → `openPlayer()`，DETAIL -> PLAYER
 *
 * 原来 `back()` 的 PLAYER 分支写死 `_route.value = Route.DETAIL`，
 * 于是从列表直接播放的用户按返回会掉进一个**从未见过的详情页**，
 * 要再按一次才回到列表。
 *
 * ## 修法
 *
 * 记住"进入播放页之前在哪"（routeBeforePlayer），返回时回到那里。
 *
 * 这些用例不触网：`loadMatches` / `loadSignals` 都注入固定实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerBackStackTest {

    private fun match(id: String) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "主队$id",
        awayTeam = "客队$id",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-23 07:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )

    /**
     * 构造一个不触网的 ViewModel。
     *
     * `MainViewModel` 的构造参数是可注入的 lambda（不是接口），
     * 直接给 `loadMatches` / `loadSignals` 注入固定实现：
     *  - `loadMatches` 返回给定列表
     *  - `loadSignals` 返回空信号（零请求），让 playMatch 的后续流程短路
     */
    private fun vmWith(matches: List<Match>): MainViewModel =
        MainViewModel(
            loadMatches = { Result.success(matches) },
            loadSignals = { MatchRepository.Detail(signals = emptyList()) },
        )

    /**
     * 造一个"正停在详情页"的 ViewModel。
     *
     * ⚠️ 这条路是**生产上唯一能到详情页的方式**（review/03-ui.md P2-2 的进程重建恢复）：
     * 底部标签与列表卡片都直接进播放页 —— NbaLiveApp 里那句注释写得很直白
     * "点比赛直接全屏自动播放，不再先跳详情页选线路"。
     *
     * 原来下面两条用例是直接 `playMatch()` 之后再调 `openPlayer()` 来"模拟详情页"，
     * 而 `openPlayer()` 当时把来源**写死**成 DETAIL。那个时序生产上走不到
     * （播放页不会渲染详情页的线路行，点不到"进入详情页的播放"），
     * 所以一旦把"来源取当前路由"这条正确规则落到 NavState，用例就红了。
     * 现在改成走真实路径：播放 → 被回收 → 重建回详情页。
     */
    private fun TestScope.vmOnDetail(matches: List<Match>): MainViewModel {
        val handle = SavedStateHandle()
        fun create() = MainViewModel(
            savedStateHandle = handle,
            loadMatches = { Result.success(matches) },
            loadSignals = { MatchRepository.Detail(signals = emptyList()) },
        )
        // 播放中（存档里是 PLAYER + matchId）
        create().also { it.playMatch(matches.first()) }
        settle()
        // 进程被杀后重建：回到详情页（P2-2）
        return create().also { settle() }
    }

    private fun TestScope.settle() {
        runCurrent()
    }

    private fun vmTest(block: suspend TestScope.() -> Unit) {
        try {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                block()
            }
        } finally {
            // 断言失败也要 resetMain，否则 Main dispatcher 泄漏到后续用例
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `列表直接播放 按返回回到列表而不是详情页`() = vmTest {
        val vm = vmWith(listOf(match("m1")))
        settle()

        // 起始在列表
        assertEquals(Route.LIST, vm.route.value)

        // 列表卡片「立即观看」：LIST -> PLAYER
        vm.playMatch(match("m1"))
        settle()
        assertEquals(Route.PLAYER, vm.route.value)

        // 按返回：必须回 LIST（此前 bug 是回 DETAIL）
        vm.back()
        assertEquals(
            "从列表直接播放后按返回应回列表；回详情页会让用户看到从未见过的页面",
            Route.LIST,
            vm.route.value,
        )
    }

    @Test
    fun `详情页进播放 按返回回到详情页`() = vmTest {
        // 走真实路径停在详情页（进程重建恢复），见 vmOnDetail 的说明
        val vm = vmOnDetail(listOf(match("m1")))
        assertEquals(Route.DETAIL, vm.route.value)

        // 详情页点第一条线路进播放：来源应被记成 DETAIL
        vm.openPlayer(0)
        settle()
        assertEquals(Route.PLAYER, vm.route.value)

        // 按返回：回 DETAIL（这条路径本来就是对的，要保住）
        vm.back()
        assertEquals(
            "从详情页进入播放，返回应回详情页",
            Route.DETAIL,
            vm.route.value,
        )
    }

    @Test
    fun `切场后再按返回 仍回到进入播放前的路由`() = vmTest {
        val vm = vmWith(listOf(match("m1"), match("m2")))
        settle()

        // 从列表直接播放第一场
        vm.playMatch(match("m1"))
        settle()
        assertEquals(Route.PLAYER, vm.route.value)

        // 用"同时在播"面板切到第二场：仍在 PLAYER，来源不变
        vm.playMatch(match("m2"))
        settle()
        assertEquals(Route.PLAYER, vm.route.value)

        // 返回应回 LIST（不是任何一场的详情页）
        vm.back()
        assertEquals(
            "切场不应改变来源；从列表进来的始终回列表",
            Route.LIST,
            vm.route.value,
        )
    }

    @Test
    fun `上一次进详情页的来源不会泄漏到下一次从列表播放`() = vmTest {
        val vm = vmOnDetail(listOf(match("m1"), match("m2")))
        assertEquals(Route.DETAIL, vm.route.value)

        // 详情页 -> 播放 -> 返回：来源被记成 DETAIL
        vm.openPlayer(0)
        settle()
        vm.back()
        assertEquals(Route.DETAIL, vm.route.value)

        // 从详情页返回列表
        vm.back()
        assertEquals(Route.LIST, vm.route.value)

        // 第二次从列表直接播放：返回必须回 LIST，
        // 不能被上一次的 DETAIL 来源污染
        vm.playMatch(match("m2"))
        settle()
        vm.back()
        assertEquals(
            "上一次的 DETAIL 来源不能泄漏到下一次从列表播放",
            Route.LIST,
            vm.route.value,
        )
    }
}
