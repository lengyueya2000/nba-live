package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.repository.MatchRepository
import com.nbalive.app.data.remote.StreamUrlResolver
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * 断言目标就是播放器"同时在播"面板实际消费的那份过滤结果。
     *
     * ⚠️ 这里**不再自己写** `filter { it.status == MatchStatus.LIVE }`
     * （audit「未验证的怀疑 #5」）：生产与测试各写一份规则时，生产加条件
     * （例如排除延期场次）测试不会红。现在两边调的是同一个 [liveOnly]。
     */
    private fun liveIds(vm: MainViewModel): List<String> =
        liveOnly(vm.listState.value.matches).map { it.id }

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
        try {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                block()
            }
        } finally {
            // 断言失败时 runTest 会抛异常：resetMain 必须照样执行，
            // 否则 Main dispatcher 模块在同一 JVM 内泄漏，后续用例报
            // "Module with the Main dispatcher had failed to initialize"
            Dispatchers.resetMain()
        }
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

    /** 可静态解析出 m3u8 的直连线路（isDirectHls 判据：路径含 /play/sm.html） */
    private val DIRECT_PAGE = "http://play.example.com/play/sm.html?id=1"

    /** 只能网页播放的线路（JRS 加密播放器等） */
    private val WEB_PAGE = "http://play.example.com/play/mgxl/?id=9&id2="

    private fun source(id: String, pageUrl: String) = StreamSource(
        id = id,
        label = "信号1",
        provider = Provider.JRKAN,
        pageUrl = pageUrl,
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
    fun `点刷新按钮时显示刷新中（保留旧列表），失败时给出错误`() = vmTest {
        var invocation = 0
        val busyDuringLoad = mutableListOf<Boolean>()
        val loadingDuringLoad = mutableListOf<Boolean>()
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        // 构造期 lambda 还不能引用 vm 自己：用可空 holder，运行时才读
        var vmHolder: MainViewModel? = null
        vmHolder = MainViewModel(
            loadMatches = {
                busyDuringLoad += vmHolder!!.listState.value.busy
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

        busyDuringLoad.clear()
        loadingDuringLoad.clear()
        try {
            vm.refresh() // 播放中手动点刷新
            settle()
            assertTrue("点刷新期间要显示刷新中", busyDuringLoad.single())
            assertFalse(
                "已有数据时刷新不能再走整屏 loading（会把旧列表闪空）",
                loadingDuringLoad.single(),
            )
            assertEquals("两个数据源都没有返回赛事", vm.listState.value.error)
            assertEquals(
                "刷新失败也不能清空已有列表",
                listOf("watching"),
                vm.listState.value.matches.map { it.id },
            )
        } finally {
            vm.back()
        }
    }

    // ---------- C1：VM 异常安全 / 状态复位 / 故障切换状态机 ----------

    @Test
    fun `进场拉信号期间按返回：信号区不卡在加载中`() = vmTest {
        // playMatch 进播放页时 loadSignals 在途，用户立刻按返回：
        // loadJob 被取消，它尾部那句 loadingSignals=false 永远不执行，
        // back() 自己也不复位——详情页信号区曾永久停在"正在获取信号列表…"。
        val gate = CompletableDeferred<Unit>()
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { gate.await(); MatchRepository.Detail(signals = emptyList()) },
        )
        settle()
        try {
            vm.playMatch(watching)
            settle()
            assertTrue("进场后应进入加载中", vm.detailState.value.loadingSignals)

            vm.back() // 拉信号途中返回
            // playMatch 从 LIST 直接进 PLAYER，返回必须回 LIST
            // （旧断言写的是 DETAIL —— 那正是"返回掉进没见过的详情页"的 bug 本身）
            assertEquals(Route.LIST, vm.route.value)
            assertFalse("返回后信号区不能卡在加载中", vm.detailState.value.loadingSignals)
        } finally {
            gate.complete(Unit) // 放行被取消的协程，让它干净收尾
            vm.back()
        }
    }

    @Test
    fun `loadSignals 抛异常时不崩溃：详情页给出错误`() = vmTest {
        // 畸形地址/解析器回归会让 repository.loadSignals 抛非取消异常。
        // 三个 job 都没有 try/catch 时它会逃逸出 viewModelScope 直接闪退。
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { throw IllegalArgumentException("unexpected url: http://a b/x") },
        )
        settle()

        vm.playMatch(watching)
        settle()

        assertEquals(Route.PLAYER, vm.route.value)
        assertFalse("异常不能把加载态冻住", vm.detailState.value.loadingSignals)
        assertNotNull("拉信号失败要给详情页一个错误态", vm.detailState.value.error)
        assertNotNull("播放页也要能看到失败原因", vm.playerState.value.errorMessage)
        assertFalse(vm.playerState.value.resolving)
    }

    @Test
    fun `resolveStream 抛异常时不崩溃：播放页给出错误可重试`() = vmTest {
        // 最现实的触发点：候选 m3u8 解出空格等畸形串，
        // OkHttp Request.Builder().url() 抛 IllegalArgumentException。
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = listOf(source("watching-s0", DIRECT_PAGE))) },
            resolveStream = { throw IllegalArgumentException("unexpected url: http://a b/x.m3u8") },
        )
        settle()

        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        assertFalse("解析异常必须落回非加载态", vm.playerState.value.resolving)
        assertNotNull(vm.playerState.value.errorMessage)
        assertTrue(
            "错误提示要说明是解析失败",
            vm.playerState.value.errorMessage!!.contains("解析失败"),
        )
        // 未逃逸成未捕获异常即通过：否则 runTest 会直接失败
    }

    @Test
    fun `嗅探升级失败后：同一播放页不再反复升级`() = vmTest {        // m3u8 带时效签名，每轮嗅探到的地址都不同。
        // 只挡"同一个 URL"（failedSniffUrl）挡不住回环：
        // 升级→直连失败→退回 WebView→再嗅探→再升级，每轮销毁重建 WebView、闪黑、耗流量。
        // 修法：记下升级失败的**播放页**，同页本轮不再升级。
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = listOf(source("watching-s0", WEB_PAGE))) },
        )
        settle()

        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()
        assertEquals("网页线路直接交 WebView 承载", WEB_PAGE, vm.playerState.value.webFallbackUrl)
        assertNull(vm.playerState.value.streamUrl)

        // 第一次嗅探升级成功
        vm.onStreamSniffed("http://cdn.example.com/aaa.m3u8?auth=1", WEB_PAGE)
        assertTrue(vm.playerState.value.sniffedStream)
        assertEquals("http://cdn.example.com/aaa.m3u8?auth=1", vm.playerState.value.streamUrl)

        // 直连失败：退回网页，同时记下这个播放页升级失败过
        vm.onPlaybackFailed()
        assertEquals(WEB_PAGE, vm.playerState.value.webFallbackUrl)
        assertNull(vm.playerState.value.streamUrl)
        assertEquals(WEB_PAGE, vm.playerState.value.failedSniffPage)

        // WebView 重载后又嗅到一个**不同**的地址：同一播放页不应再次升级
        vm.onStreamSniffed("http://cdn.example.com/bbb.m3u8?auth=2", WEB_PAGE)
        assertNull("同一播放页升级失败后不应再升级", vm.playerState.value.streamUrl)
        assertEquals(
            "应停留在网页兜底，等待用户操作",
            WEB_PAGE,
            vm.playerState.value.webFallbackUrl,
        )
    }

    @Test
    fun `刷新失败时保留旧列表不闪空`() = vmTest {
        var invocation = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = {
                invocation++
                if (invocation == 1) Result.success(listOf(watching))
                else Result.failure(IOException("两个数据源都没有返回赛事"))
            },
        )
        settle()
        vm.refresh()
        settle()
        assertEquals("失败不能清空已有列表", listOf("watching"), vm.listState.value.matches.map { it.id })
        assertEquals("两个数据源都没有返回赛事", vm.listState.value.error)
    }

    @Test
    fun `用户手动换线后 嗅探升级的封禁要解除`() = vmTest {
        // 回归：failedSniffPage 的清理条件写反了 ——
        // 旧写法 `if (it.failedSniffPage == newPage) null else it.failedSniffPage`
        // 只会清掉"切回同一个页"的情况。
        // 用户切到**另一条**线时旧记录还在，之后切回那条线时
        // 嗅探升级会被静默拒绝，用户看不到任何提示。
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = {
                MatchRepository.Detail(
                    signals = listOf(
                        source("watching-s0", WEB_PAGE),
                        source("watching-s1", "http://play.example.com/other.html"),
                    ),
                )
            },
        )
        settle()

        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        // 第一条线嗅探升级失败 -> 记下 failedSniffPage
        vm.onStreamSniffed("http://cdn.example.com/aaa.m3u8", WEB_PAGE)
        vm.onPlaybackFailed()
        assertEquals(WEB_PAGE, vm.playerState.value.failedSniffPage)

        // 用户手动切到另一条线：封禁应该解除
        vm.selectSignal(1)
        settle()
        assertNull(
            "用户主动换线后不该再留着旧播放页的嗅探封禁",
            vm.playerState.value.failedSniffPage,
        )
    }

    @Test
    fun `返回键逐层退出并清干净播放态`() = vmTest {
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = emptyList()) },
        )
        settle()
        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        vm.back() // 从列表直接进播放（playMatch），返回应回 LIST
        // 旧断言是 DETAIL：那是"返回掉进从未见过的详情页"的 bug 本身。
        // 逐层退出的语义由 openPlayer 路径覆盖（见 PlayerBackStackTest）。
        assertEquals(Route.LIST, vm.route.value)
        assertNull("离开播放页要清掉直连地址", vm.playerState.value.streamUrl)
        assertNull(vm.playerState.value.webFallbackUrl)
        assertFalse(vm.playerState.value.resolving)
        assertNull("离开播放页要清掉错误提示（原来靠 UI 层 route 条件遮住）", vm.playerState.value.errorMessage)
        assertFalse(vm.detailState.value.loadingSignals)
    }

    @Test
    fun `故障切换的直连候选有上限 不会把源站打爆`() = vmTest {
        // 每条直连线路的解析代价 ≈ 9 次请求（4 跳 + 5 候选）。
        // 故障切换原本会把**所有**直连线路扫一遍（9D 次），
        // 15 秒看门狗每次触发再扫一遍 → 9D²，最容易触发源站限流。
        var resolveCount = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val manySources = (0 until 8).map { i ->
            source("watching-s$i", "http://play.example.com/play/sm.html?id=$i")
        }
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = manySources) },
            resolveStream = {
                resolveCount++
                null // 全部解析失败
            },
        )
        settle()

        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        assertTrue(
            "直连候选的尝试次数必须有上限，实际尝试了 $resolveCount 次",
            resolveCount <= 3,
        )
        assertFalse("解析失败要落回非加载态", vm.playerState.value.resolving)
        assertNotNull("全失败时要给出错误提示", vm.playerState.value.errorMessage)
    }

    @Test
    fun `重连不会重放嗅探回环`() = vmTest {
        // 嗅探升级失败后，failedSniffPage 必须**保留**：
        // 早先每次 play() 都把它清零，用户每点一次"重连"就能再触发
        // 一整轮 WebView 销毁重建 + 页面链重放（4~6 个文档 + m3u8）。
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = { MatchRepository.Detail(signals = listOf(source("watching-s0", WEB_PAGE))) },
        )
        settle()

        vm.playMatch(watching)
        waitForLoadSignals(vm)
        settle()

        vm.onStreamSniffed("http://cdn.example.com/aaa.m3u8", WEB_PAGE)
        vm.onPlaybackFailed()
        assertEquals(WEB_PAGE, vm.playerState.value.failedSniffPage)

        // 用户点重连：不应该清掉 failedSniffPage
        vm.reconnect()
        settle()
        assertEquals(
            "重连不能清掉 failedSniffPage，否则每次点击都是一轮完整重放",
            WEB_PAGE,
            vm.playerState.value.failedSniffPage,
        )

        // 但用户主动切到别的线路时应该清掉（换了个播放页）
        vm.selectSignal(0)
        settle()
    }

    // ---------- 02-P2-5：从播放页返回必须取消在途的详情页请求 ----------

    @Test
    fun `从播放页返回后 在途详情请求晚到也不能覆写详情态`() = vmTest {
        // 复现：用户点「刷新线路」（detailJob 在途）后立刻进播放页再按返回。
        // 旧实现只取消 playJob/loadJob，detailJob 会在用户已经离开详情页之后
        // 才落地并写 _detailState —— 界面被一个早该结束的请求改掉。
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val watching = match("watching", "湖人", "勇士", MatchStatus.LIVE)
        val late = source("watching-late", WEB_PAGE)
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = {
                calls++
                if (calls == 1) {
                    // 进场那次：立刻返回空信号，让路由稳定停在 PLAYER
                    MatchRepository.Detail(signals = emptyList())
                } else {
                    // 「刷新线路」那次：挂在 gate 上，模拟真实的在途请求
                    gate.await()
                    MatchRepository.Detail(signals = listOf(late))
                }
            },
        )
        settle()
        try {
            vm.playMatch(watching)
            waitForLoadSignals(vm)
            settle()
            assertEquals("进场后应停在播放页", Route.PLAYER, vm.route.value)

            vm.refreshSignals()
            settle()
            assertTrue("刷新线路应处于在途", vm.detailState.value.loadingSignals)

            vm.back() // 播放页返回：PLAYER 分支必须取消 detailJob
            assertEquals(Route.LIST, vm.route.value)
            assertFalse(vm.detailState.value.loadingSignals)

            // 放行旧请求的挂起点：若 detailJob 没被取消，它会写回详情态
            gate.complete(Unit)
            repeat(5) { runCurrent() }

            assertTrue(
                "已离开播放页后，旧详情请求不能再写 _detailState" +
                    "（signals=${vm.detailState.value.signals.map { it.pageUrl }}）",
                vm.detailState.value.signals.isEmpty(),
            )
            assertNull(vm.detailState.value.error)
        } finally {
            gate.complete(Unit) // 被取消的协程也要能干净收尾
            vm.back()
        }
    }
}
