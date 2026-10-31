package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.repository.MatchRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的」页的数据源开关（2026-10-31 用户需求）：
 * 「增加一个控制 jrs 和 jrkan 的开关，比如我可以选择不解析某一个播放源，就可以手动关掉」。
 *
 * 三条硬约束：
 *  1. 关掉的源**真的不解析**（不发请求），不是"解析了再过滤"——用户明确说的是"不解析"；
 *  2. **至少保留一个**：两个都关 = 永远空列表，那是把 App 变成砖头；
 *  3. 关掉的源不能出现在"源暂时不可用"的失败提示里 —— 那是用户自己关的，不是故障。
 */
class SourceTogglePolicyTest {

    @Test
    fun `默认两个源都开`() {
        assertEquals(Provider.entries.toSet(), SourceTogglePolicy.default())
    }

    @Test
    fun `关掉一个后另一个仍然开着`() {
        val after = SourceTogglePolicy.toggle(SourceTogglePolicy.ALL, Provider.JRS, on = false)
        assertEquals(setOf(Provider.JRKAN), after)
        assertFalse(SourceTogglePolicy.isEnabled(after, Provider.JRS))
        assertTrue(SourceTogglePolicy.isEnabled(after, Provider.JRKAN))
    }

    @Test
    fun `再打开就回到两个都开`() {
        val off = SourceTogglePolicy.toggle(SourceTogglePolicy.ALL, Provider.JRS, on = false)
        val on = SourceTogglePolicy.toggle(off, Provider.JRS, on = true)
        assertEquals(SourceTogglePolicy.ALL, on)
    }

    @Test
    fun `最后一个是关不掉的`() {
        val onlyJrs = setOf(Provider.JRS)
        assertFalse(
            "两个都关 = 永远空列表，把 App 变成砖头",
            SourceTogglePolicy.canDisable(onlyJrs, Provider.JRS),
        )
        assertEquals(
            "强行 toggle 也必须原样返回（判据与执行同一份实现）",
            onlyJrs,
            SourceTogglePolicy.toggle(onlyJrs, Provider.JRS, on = false),
        )
    }

    @Test
    fun `两个都开着时任何一个都能关`() {
        assertTrue(SourceTogglePolicy.canDisable(SourceTogglePolicy.ALL, Provider.JRS))
        assertTrue(SourceTogglePolicy.canDisable(SourceTogglePolicy.ALL, Provider.JRKAN))
    }

    @Test
    fun `已经关掉的源不用再关 但开关仍可点回来`() {
        val onlyJrs = setOf(Provider.JRS)
        assertFalse("它已经是关的，不需要再关", SourceTogglePolicy.canDisable(onlyJrs, Provider.JRKAN))
        assertEquals(
            "关着的源必须能重新打开",
            SourceTogglePolicy.ALL,
            SourceTogglePolicy.toggle(onlyJrs, Provider.JRKAN, on = true),
        )
    }

    @Test
    fun `持久化往返不丢信息`() {
        val names = SourceTogglePolicy.toStorage(setOf(Provider.JRKAN))
        assertEquals(setOf("JRKAN"), names)
        assertEquals(setOf(Provider.JRKAN), SourceTogglePolicy.fromStorage(names))
    }

    @Test
    fun `持久化里的空集或未知值回落到全开`() {
        assertEquals(
            "空集意味着'一个源都没有'，那是不可用状态；宁可全开也不要让用户对着空列表",
            SourceTogglePolicy.ALL,
            SourceTogglePolicy.fromStorage(emptySet()),
        )
        assertEquals(
            "null（从未存过）同样回落到全开",
            SourceTogglePolicy.ALL,
            SourceTogglePolicy.fromStorage(null),
        )
        assertEquals(
            "源站改名/枚举删值后旧键名读不出来，不能因此把用户锁死在空列表",
            SourceTogglePolicy.ALL,
            SourceTogglePolicy.fromStorage(setOf("BOGUS", "GONE")),
        )
        assertEquals(
            "能认出来的值照常保留，未知值丢掉（不能因为夹了一个脏值就把整份配置作废）",
            setOf(Provider.JRS),
            SourceTogglePolicy.fromStorage(setOf("JRS", "BOGUS")),
        )
    }

    @Test
    fun `摘要文案要能说清当前在解析哪些源`() {
        assertEquals("JRS / JRKAN", SourceTogglePolicy.summary(SourceTogglePolicy.ALL))
        assertEquals("仅 JRKAN", SourceTogglePolicy.summary(setOf(Provider.JRKAN)))
        assertEquals("仅 JRS", SourceTogglePolicy.summary(setOf(Provider.JRS)))
    }
}

/**
 * 数据源开关的接线与行为。
 *
 * 判据写了没人消费 = 假安全感（本仓库的老问题），所以每一环都扫源码钉住：
 * 存储 → 仓库（真的跳过请求）→ ViewModel（过滤信号 / 拒绝关掉最后一个）→ 界面。
 */
class SourceToggleWiringTest {

    // ---------------- 数据层：关掉的源真的不解析 ----------------

    private fun jrsHtml(id: String) = """
        <div class="list"><p class="list-title">2026-10-23</p>
        <a href="/live/$id.html" class="list-con">
          <div class="list-team-con"><p class="list-team-name">湖人</p></div>
          <div class="list-team-con"><p class="list-team-name">凯尔特人</p></div>
          <p class="list-info-league">NBA</p><p class="list-info-time">直播中</p>
        </a></div>
    """.trimIndent()

    private fun jrkanJs(eventId: String) = """
        document.write('<ul class="item play d-touch active " data-lid="$eventId,2,$eventId" data-stype="zqlq">');
        document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
        document.write('  <li class="lab_time">10-23 10:00</li>');
        document.write('  <li class="lab_team_home"><strong class="name">勇士</strong></li>');
        document.write('  <li class="lab_team_away"><strong class="name">火箭</strong></li>');
        document.write('</ul>');
    """.trimIndent()

    @Test
    fun `关掉 JRS 后完全不去请求 JRS`() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = MatchRepository().loadMatchesWithOutcome(enabled = setOf(Provider.JRKAN)) { url, _ ->
            synchronized(seen) { seen += url }
            if (url.contains("jrszbt") || url.contains("jrszb")) FetchResult.Ok(jrsHtml("555"))
            else FetchResult.Ok(jrkanJs("900"))
        }
        assertTrue(
            "关掉 JRS 后仍在请求 JRS 的地址：$seen —— 用户说的是「不解析」，不是「解析完再过滤」",
            seen.none { it.contains("jrszbt") || it.contains("jrszb") },
        )
        assertTrue("JRKAN 的场次照常返回", outcome.matches.any { it.id.startsWith("jrkan-") })
        assertFalse("关掉的源不该有 JRS 场次", outcome.matches.any { it.id.startsWith("jrs-") })
    }

    @Test
    fun `关掉的源不能被报成失败源`() = runBlocking {
        val outcome = MatchRepository().loadMatchesWithOutcome(enabled = setOf(Provider.JRKAN)) { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Rejected(403) else FetchResult.Ok(jrkanJs("900"))
        }
        assertTrue(
            "用户自己关掉的源被报成「JRS 源暂时不可用」是误导（当前=${outcome.failedProviders}）",
            outcome.failedProviders.isEmpty(),
        )
        assertEquals("没有失败源就不该有半失败提示", null, outcome.partialError())
    }

    @Test
    fun `关掉 JRKAN 后只剩 JRS`() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = MatchRepository().loadMatchesWithOutcome(enabled = setOf(Provider.JRS)) { url, _ ->
            synchronized(seen) { seen += url }
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555")) else FetchResult.Ok(jrkanJs("900"))
        }
        assertTrue(
            "关掉 JRKAN 后仍在请求它：$seen",
            seen.none { it.contains("oss-accelerate") },
        )
        assertTrue(outcome.matches.any { it.id.startsWith("jrs-") })
        assertFalse(outcome.matches.any { it.id.startsWith("jrkan-") })
    }

    @Test
    fun `空集合按全开处理 不能把用户锁在空列表里`() = runBlocking {
        val outcome = MatchRepository().loadMatchesWithOutcome(enabled = emptySet()) { url, _ ->
            if (url.contains("jrszbt")) FetchResult.Ok(jrsHtml("555")) else FetchResult.Ok(jrkanJs("900"))
        }
        assertTrue("空集是异常输入，防御成全开", outcome.matches.size >= 2)
    }

    // ---------------- ViewModel：过滤信号 + 拒绝关最后一个 ----------------

    @Test
    fun `关掉的源发出的线路不会出现在播放页`() = vmTest {
        val watching = match()
        val vm = MainViewModel(
            loadMatches = { Result.success(listOf(watching)) },
            loadSignals = {
                MatchRepository.Detail(
                    signals = listOf(
                        StreamSource("jrs-1", "JRS线路", Provider.JRS, "http://a.example.com/play/sm.html?id=1"),
                        StreamSource("jrkan-1", "JRKAN线路", Provider.JRKAN, "http://b.example.com/play/mgxl/?id=2"),
                    ),
                )
            },
            prefs = InMemoryPrefsStore(initialProviders = setOf(Provider.JRKAN)),
        )
        settle()
        vm.playMatch(watching)
        waitForSignals(vm)
        settle()

        val providers = vm.playerState.value.signals.map { it.provider }
        assertEquals("关掉 JRS 后只该剩 JRKAN 线路（当前=$providers）", listOf(Provider.JRKAN), providers)
    }

    @Test
    fun `最后一个是关不掉的 存储也不会被写坏`() = vmTest {
        val prefs = InMemoryPrefsStore(initialProviders = setOf(Provider.JRS))
        val vm = MainViewModel(
            loadMatches = { Result.success(emptyList()) },
            prefs = prefs,
        )
        settle()
        vm.setProviderEnabled(Provider.JRS, enabled = false)
        settle()
        assertEquals(
            "两个都关 = 永远空列表，必须被挡住",
            setOf(Provider.JRS),
            vm.enabledProviders.value,
        )
        assertEquals("被挡住时不能写存储", setOf(Provider.JRS), prefs.enabledProviders.value)
    }

    @Test
    fun `关掉一个源会真的落进存储`() = vmTest {
        val prefs = InMemoryPrefsStore()
        val vm = MainViewModel(loadMatches = { Result.success(emptyList()) }, prefs = prefs)
        settle()
        vm.setProviderEnabled(Provider.JRS, enabled = false)
        settle()
        assertEquals(setOf(Provider.JRKAN), prefs.enabledProviders.value)
        assertEquals(setOf(Provider.JRKAN), vm.enabledProviders.value)
    }

    // ---------------- 界面：开关真的渲染出来并接到 ViewModel ----------------

    @Test
    fun `我的页必须渲染两个数据源开关并消费判据`() {
        val body = GuardSourceScan.functionBody(source("ProfileScreen.kt"), "fun ProfileScreen(")
        assertTrue(
            "ProfileScreen 没有消费 SourceTogglePolicy.canDisable(...)：最后一条能被关掉，App 变砖头",
            body.contains("SourceTogglePolicy.canDisable("),
        )
        assertTrue(
            "ProfileScreen 没有把开关接给上层（onProviderToggle 写了没人用）",
            body.contains("onProviderToggle"),
        )
        assertTrue(
            "ProfileScreen 没有渲染源名/摘要（开关上没有文字用户不知道关的是哪个）",
            body.contains("SourceTogglePolicy.summary(") || body.contains("provider.displayName"),
        )
    }

    @Test
    fun `AppChrome 与 AppUiState 要把开关状态送到我的页`() {
        val chrome = source("AppChrome.kt")
        assertTrue(
            "AppChrome 没把 enabledProviders 传给 ProfileScreen",
            chrome.contains("enabledProviders = enabledProviders"),
        )
        assertTrue(
            "AppChrome 没把切换回调接到 viewModel",
            chrome.contains("onProviderToggle = viewModel::setProviderEnabled"),
        )
        val state = source("AppUiState.kt")
        assertTrue("AppUiState 没有 enabledProviders 字段", state.contains("enabledProviders"))
        assertTrue(
            "rememberAppUiState 没有从 VM 收集 enabledProviders",
            state.contains("viewModel.enabledProviders"),
        )
    }

    @Test
    fun `平台层必须真的落盘`() {
        val store = source("PrefsStore.kt")
        assertTrue("PrefsStore 接口没有 setProviderEnabled", store.contains("setProviderEnabled"))
        assertTrue("AndroidPrefsStore 没有委托给 SourcePrefs", store.contains("SourcePrefs.setEnabled("))
        val prefs = source("SourcePrefs.kt")
        assertTrue("SourcePrefs 没有写 SharedPreferences", prefs.contains("putStringSet"))
        assertTrue("SourcePrefs 没有在 attach 后读一次", prefs.contains("fun init()"))
    }

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "class Sample {",
            "    fun ProfileScreen() {",
            "        // SourceTogglePolicy.canDisable( 只在注释里",
            "        val s = \"onProviderToggle 只在字符串里\"",
            "    }",
            "}",
        ).joinToString("\n")
        val body = GuardSourceScan.functionBody(sample, "fun ProfileScreen(")
        assertFalse("判据命中了注释", body.contains("SourceTogglePolicy.canDisable("))
        assertFalse("判据命中了字符串", body.contains("onProviderToggle"))
    }

    // ---------------------------------------------------------------

    private fun match() = Match(
        id = "watching",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "勇士",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-23 07:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )

    private suspend fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    private fun TestScope.waitForSignals(vm: MainViewModel) {
        var spins = 0
        while (vm.detailState.value.loadingSignals) {
            check(spins < 10_000) { "loadSignals 迟迟未落地" }
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
            Dispatchers.resetMain()
        }
    }

    private fun source(name: String): String {
        val root = productionRoot()
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("找不到 $name —— 这条守卫会静默全绿，所以直接失败")
        return GuardSourceScan.stripNonCode(file.readText())
    }

    private fun productionRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").absolutePath
        return listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd）—— 守卫会静默全绿")
    }
}
