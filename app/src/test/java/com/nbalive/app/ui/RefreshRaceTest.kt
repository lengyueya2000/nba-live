package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.repository.MatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新任务的**状态收尾竞态**（review/02-data.md P2-4）。
 *
 * ## 要解决什么
 *
 * `refresh()` 先 `refreshJob?.cancel()` 再起新协程。旧协程的
 * `catch (CancellationException)` 里无条件写 `loading=false, refreshing=false`。
 * 若旧协程的收尾比新协程写 `refreshing=true` 更晚落地，就会把新任务的
 * 指示器抹掉 —— 用户看到指示器闪一下消失，但请求还在跑。
 *
 * 规则：被替换掉的旧任务不该再写任何状态（按 Job 身份判断）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshRaceTest {

    private fun match(id: String) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "勇士",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-23 07:00",
        startAtMillis = null,
        status = MatchStatus.UPCOMING,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )

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

    @Test
    fun `旧刷新的取消收尾不能抹掉新刷新的进行中状态`() = vmTest {
        // 复现真实的竞态顺序：**旧任务的取消收尾比新任务落地更晚**。
        // 默认调度器下 cancel 总是先于新协程跑（那样永远看不到这个 bug），
        // 所以让被取消的那次加载在收尾时先耗掉一段虚拟时间 ——
        // 这正是真机上"取消信号要等挂起点才被观察到"的形态。
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var call = 0
        val vm = MainViewModel(
            repository = MatchRepository(),
            loadMatches = {
                call++
                if (call == 1) {
                    try {
                        firstGate.await()
                    } catch (ce: CancellationException) {
                        // 模拟"取消收尾比新任务慢"：旧协程在抛出前还挣扎一会儿
                        withContext(NonCancellable) { delay(50) }
                        throw ce
                    }
                    Result.success(emptyList<Match>())
                } else {
                    secondGate.await()
                    Result.success(listOf(match("second")))
                }
            },
        )
        advanceTimeBy(1)
        runCurrent()

        // 第二次刷新：旧任务被 cancel（但它的收尾要 50ms 后才落地），新任务立刻开始
        vm.refresh()
        advanceTimeBy(1)
        runCurrent()
        assertTrue(
            "第二次刷新已开始，界面上必须显示进行中",
            vm.listState.value.loading || vm.listState.value.refreshing,
        )

        // 让旧任务的取消收尾落地 —— 这是 bug 发生的时刻
        advanceTimeBy(100)
        runCurrent()

        assertTrue(
            "旧任务（已被替换）的取消收尾不该把新任务的进行中状态抹掉 —— " +
                "否则刷新指示器闪一下就消失，而请求还在跑。" +
                "（loading=${vm.listState.value.loading}, refreshing=${vm.listState.value.refreshing}）",
            vm.listState.value.loading || vm.listState.value.refreshing,
        )

        // 新任务正常完成
        secondGate.complete(Unit)
        advanceTimeBy(1)
        runCurrent()
        val after = vm.listState.value
        assertFalse("完成后不该还停在加载态", after.loading || after.refreshing)
        assertTrue("第二次刷新的结果必须落地", after.matches.any { it.id == "second" })
    }

    @Test
    fun `连点刷新最终只保留最后一次的结果`() = vmTest {
        var call = 0
        val vm = MainViewModel(
            repository = MatchRepository(),
            loadMatches = {
                call++
                Result.success(listOf(match("call$call")))
            },
        )
        advanceTimeBy(1)
        runCurrent()
        vm.refresh()
        advanceTimeBy(1)
        runCurrent()
        vm.refresh()
        advanceTimeBy(1)
        runCurrent()

        val state = vm.listState.value
        assertFalse("最终不该停在加载态", state.loading || state.refreshing)
        assertTrue("必须有最后一次刷新的数据", state.matches.isNotEmpty())
    }
}