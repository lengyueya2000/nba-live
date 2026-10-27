package com.nbalive.app.ui

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
        val vm = vmWith(listOf(match("m1")))
        settle()

        // playMatch 会填充 detailState.match（openPlayer 依赖它）
        vm.playMatch(match("m1"))
        settle()

        // 模拟"从详情页点线路进播放"：openPlayer 把来源记为 DETAIL
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
        val vm = vmWith(listOf(match("m1")))
        settle()

        // 第一次：列表进播放 -> 返回（回 LIST）
        vm.playMatch(match("m1"))
        settle()
        vm.back()
        assertEquals(Route.LIST, vm.route.value)

        // 走一次 openPlayer 把来源写成 DETAIL
        vm.playMatch(match("m1"))
        settle()
        vm.openPlayer(0)
        settle()
        vm.back()
        assertEquals(Route.DETAIL, vm.route.value)

        // 回到列表
        vm.back()
        assertEquals(Route.LIST, vm.route.value)

        // 第二次从列表直接播放：返回必须回 LIST，
        // 不能被上一次的 DETAIL 污染
        vm.playMatch(match("m1"))
        settle()
        vm.back()
        assertEquals(
            "上一次的 DETAIL 来源不能泄漏到下一次从列表播放",
            Route.LIST,
            vm.route.value,
        )
    }
}
