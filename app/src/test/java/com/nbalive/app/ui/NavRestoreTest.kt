package com.nbalive.app.ui

import androidx.lifecycle.SavedStateHandle
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.repository.MatchRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程被回收后回到详情页，而不是无声地掉回列表（review/03-ui.md P2-2）。
 *
 * ## 要解决什么
 *
 * `route` 在 ViewModel 里是普通 `MutableStateFlow`，没有 `SavedStateHandle`。
 * 播放中切后台被系统回收后从最近任务回来：`route` 回到 `LIST`、
 * `playerState.match` 为 null，**用户在播放页被回收后回来直接落在列表**；
 * 而 `tab`/`fullscreen` 这些 UI 态反而被 `rememberSaveable` 恢复了 ——
 * 同一件事两套重建语义，且用户被无声地踢出了正在看的那场比赛。
 *
 * ## 修法
 *
 * 把 route / tab / matchId 存进 `SavedStateHandle`；重建时按审查的建议回到
 * **DETAIL**（"线路列表 + 重新播放"），而不是播放页 ——
 * 直播地址带时效签名，回到播放页只能是一块黑屏加一条错误。
 *
 * ## 怎么在 JVM 单测里模拟"进程被杀"
 *
 * Android 的模型是：**同一个 `SavedStateHandle` 交给新创建的 ViewModel**。
 * 所以测试里两个 ViewModel 共用一个 handle 就是进程重建的忠实模拟
 * （不需要 Robolectric，也不需要真实进程）。
 *
 * 注：列表是异步加载的，重建时 detail 需要的 match 对象也在列表里 ——
 * 所以"恢复详情"必须发生在列表加载落地**之后**，找不到就不硬跳（见最后一条）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavRestoreTest {

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

    private fun vm(
        handle: SavedStateHandle,
        matches: List<Match>,
        signalsLoaded: MutableList<String>? = null,
    ): MainViewModel = MainViewModel(
        savedStateHandle = handle,
        loadMatches = { Result.success(matches) },
        loadSignals = { m ->
            signalsLoaded?.add(m.id)
            MatchRepository.Detail(signals = emptyList())
        },
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
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `播放中被回收后重建 回到详情页而不是静默掉回列表`() = vmTest {
        val handle = SavedStateHandle()
        val m = match("m1")

        val before = vm(handle, listOf(m))
        settle()
        before.playMatch(m)
        settle()
        assertEquals(Route.PLAYER, before.route.value)

        // 进程被杀：同一个 handle 交给新 VM
        val after = vm(handle, listOf(m))
        settle()

        assertEquals(
            "重建后必须让用户看到那场比赛的线路列表，而不是无声地掉回列表（review/03-ui.md P2-2）",
            Route.DETAIL,
            after.route.value,
        )
        assertEquals(m.id, after.detailState.value.match?.id)
    }

    @Test
    fun `重建后会自动重新拉这场比赛的线路`() = vmTest {
        val handle = SavedStateHandle()
        val m = match("m1")
        vm(handle, listOf(m)).also { it.playMatch(m) }
        settle()

        val loaded = mutableListOf<String>()
        vm(handle, listOf(m), signalsLoaded = loaded)
        settle()

        assertEquals(
            "回到详情页就要把线路拉起来，否则用户看到的是一直转圈的信号区",
            listOf("m1"),
            loaded,
        )
    }

    @Test
    fun `重建时那场比赛已经不在列表里 就留在列表 不硬跳详情`() = vmTest {
        val handle = SavedStateHandle()
        val m = match("m1")
        vm(handle, listOf(m)).also { it.playMatch(m) }
        settle()

        // 源站已经把这场撤了（比赛结束）
        val after = vm(handle, emptyList())
        settle()

        assertEquals(
            "找不到 match 时硬跳详情只会显示占位提示，不如老老实实留在列表",
            Route.LIST,
            after.route.value,
        )
        assertNull(after.detailState.value.match)
    }

    @Test
    fun `标签也被持久化 重建后还在原来那一页`() = vmTest {
        val handle = SavedStateHandle()
        val m = match("m1")
        val before = vm(handle, listOf(m))
        settle()
        before.selectTab(NavTab.PROFILE)
        settle()

        val after = vm(handle, listOf(m))
        settle()
        assertEquals(
            "标签在 ViewModel 里之后也走同一个 Saver，重建语义与 route 一致（P2-2）",
            NavTab.PROFILE,
            after.nav.value.tab,
        )
    }

    @Test
    fun `生产必须注入真正可持久化的 SavedStateHandle`() {
        // ⚠️ 这是本轮最容易被"静默降级"的地方：MainViewModel 的 savedStateHandle
        // 有默认值（为了不动现有测试），若生产用无参 viewModel()，拿到的是
        // **没接 SavedStateRegistry** 的默认 handle —— 持久化会静默失效，
        // 测试全绿而线上重建照旧掉回列表。所以必须扫源码钉住注入点。
        val f = listOf(
            File("src/main/java/com/nbalive/app/MainActivity.kt"),
            File("app/src/main/java/com/nbalive/app/MainActivity.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 MainActivity.kt，守卫会静默全绿")
        val code = f.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "MainActivity 没有用 createSavedStateHandle() 构造 MainViewModel：\n" +
                "无参 viewModel() 会拿到没有注册表的默认 handle，进程重建时 route/tab 静默丢失（P2-2）",
            code.contains("createSavedStateHandle()"),
        )
        assertTrue(
            "MainActivity 必须把 handle 交进 MainViewModel",
            code.contains("savedStateHandle"),
        )
    }
}