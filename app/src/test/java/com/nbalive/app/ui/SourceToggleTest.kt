package com.nbalive.app.ui

import com.nbalive.app.TestPaths
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
    fun `关掉一个后其余源仍然开着`() {
        // ⚠️ 第三源（2026-11-15）接入后不写死 `setOf(Provider.JRKAN)`：
        // 这条判据要守的是"**只**关掉被点的那一个源"，与源总数无关。
        // 写死集合的话，加一个源就红一次，而红了也说明不了行为错了。
        val after = SourceTogglePolicy.toggle(SourceTogglePolicy.ALL, Provider.JRS, on = false)
        assertEquals(SourceTogglePolicy.ALL - Provider.JRS, after)
        assertFalse(SourceTogglePolicy.isEnabled(after, Provider.JRS))
        assertTrue(SourceTogglePolicy.isEnabled(after, Provider.JRKAN))
        assertTrue(SourceTogglePolicy.isEnabled(after, Provider.XXZB5))
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
        // 名字保留（"两个都开着"是历史语境），判据本身是"全开时任一源可关"
        SourceTogglePolicy.ALL.forEach { provider ->
            assertTrue("全开时 $provider 必须可以被关掉", SourceTogglePolicy.canDisable(SourceTogglePolicy.ALL, provider))
        }
    }

    @Test
    fun `已经关掉的源不用再关 但开关仍可点回来`() {
        val onlyJrs = setOf(Provider.JRS)
        assertFalse("它已经是关的，不需要再关", SourceTogglePolicy.canDisable(onlyJrs, Provider.JRKAN))
        assertEquals(
            "关着的源必须能重新打开（结果 = 原集合 + 它自己，而不是'回到全开'——" +
                "第三源接入后 ALL 含 XXZB5，写成 ALL 会把这条判据偷换成'点一个开全部'）",
            setOf(Provider.JRS, Provider.JRKAN),
            SourceTogglePolicy.toggle(onlyJrs, Provider.JRKAN, on = true),
        )
    }

    @Test
    fun `持久化往返不丢信息`() {
        val names = SourceTogglePolicy.toStorage(setOf(Provider.JRKAN))
        assertEquals(setOf("JRKAN"), names)
        assertEquals(setOf(Provider.JRKAN), SourceTogglePolicy.fromStorage(names))

        // 第三源的枚举名也必须能往返：存的是**枚举名集合**（不是序号/位掩码），
        // 加源时旧数据不受影响，但新名字读不回来的话用户开关就白点了。
        val three = SourceTogglePolicy.toStorage(SourceTogglePolicy.ALL)
        assertEquals(SourceTogglePolicy.ALL, SourceTogglePolicy.fromStorage(three))
        assertTrue("XXZB5 的名字必须能落盘并读回：$three", three.contains("XXZB5"))
    }

    /**
     * 第三源换源升级：老用户磁盘上存的是 `K88`（枚举已删），原样读回会把它丢掉，
     * xxzb5 开关显示"已关闭"且永不解析（真机 2026-11-25 命中的缺陷）。
     *
     * 第三源槽位的开/关选择要保留：存了 K88 = 用户开着第三源 → 映射为 XXZB5；
     * 只存 `{JRS, JRKAN}`（用户当时关掉了第三源）→ 仍旧关着，不许替他打开。
     */
    @Test
    fun `旧第三源名字映射到新源并保留用户的开关选择`() {
        assertEquals(
            "存着已删除的 K88 时必须映射成 XXZB5，不能让第三源静默消失",
            setOf(Provider.JRS, Provider.XXZB5),
            SourceTogglePolicy.fromStorage(setOf("JRS", "K88")),
        )
        assertEquals(
            "第三源当时是关着的（没有 K88）就不许被顺带打开",
            setOf(Provider.JRS, Provider.JRKAN),
            SourceTogglePolicy.fromStorage(setOf("JRS", "JRKAN")),
        )
        assertEquals(
            "只开着旧第三源时也要完整映射",
            setOf(Provider.XXZB5),
            SourceTogglePolicy.fromStorage(setOf("K88")),
        )
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

    /**
     * 老用户升级后的迁移判据（2026-11-15 第三源接入时新增）。
     *
     * ## 这不是可选的润色，是"功能上线但用户看不见"的缺陷
     *
     * 存储的是**开着的集合**。老用户存的是 `{JRS, JRKAN}`，升级后按 [fromStorage]
     * 原样读回 → XXZB5 = 关 → 第三源**静默不解析**，界面上开关显示"已关闭"，
     * 用户不会知道是自己关的还是没升级。
     * [SourceTogglePolicy] 的类 KDoc 早就写了取舍："前者（默认开）符合功能默认可用"，
     * 但那份推理只覆盖了"空集/未知值"两种坏数据，**没覆盖"合法的旧集合"**这一种。
     *
     * 迁移必须只发生一次，且不许覆盖用户自己的选择：
     *  1. 没迁移过 + 存的是旧时代集合 → 补上"旧时代之后新增的源"；
     *  2. 用户自己关掉的旧源**不许**被顺带打开（那不是升级，是改他的设置）；
     *  3. 已经迁移过 → 原样读回，此后用户关新源的意愿必须被尊重。
     */
    @Test
    fun `老用户升级后新增的源默认打开 但用户自己关掉的旧源不许被顺带打开`() {
        val legacy = setOf("JRS", "JRKAN")
        assertEquals(
            "旧时代集合升级后必须补上新增源",
            SourceTogglePolicy.ALL,
            SourceTogglePolicy.fromStorage(legacy, migrated = false),
        )
        assertEquals(
            "用户明确关掉的 JRS 不许被升级顺带打开",
            setOf(Provider.JRKAN, Provider.XXZB5),
            SourceTogglePolicy.fromStorage(setOf("JRKAN"), migrated = false),
        )
        assertEquals(
            "迁移只做一次：已迁移过的数据原样读回（用户后来关掉新源的意愿必须被尊重）",
            setOf(Provider.JRS, Provider.JRKAN),
            SourceTogglePolicy.fromStorage(legacy, migrated = true),
        )
    }

    @Test
    fun `迁移不许把坏数据读成可用状态`() {
        // 空集/null/全是未知值仍是"配置坏了"，走原有的全开回落，
        // 不许被迁移逻辑改出第四个状态（两套语义会让人读不懂哪个是哪个）。
        assertEquals(SourceTogglePolicy.ALL, SourceTogglePolicy.fromStorage(emptySet(), migrated = false))
        assertEquals(SourceTogglePolicy.ALL, SourceTogglePolicy.fromStorage(null, migrated = false))
        assertEquals(SourceTogglePolicy.ALL, SourceTogglePolicy.fromStorage(setOf("BOGUS"), migrated = false))
    }

    @Test
    fun `旧时代集合的判定不依赖枚举顺序`() {
        // 判据是"存进磁盘的名字 ⊆ 旧时代已知源"，不是 size==2 ——
        // 否则将来再加第四个源时，"两源都开"与"三源里开两个"两种数据会撞车。
        assertTrue(SourceTogglePolicy.isLegacyShapedNames(setOf("JRS", "JRKAN")))
        assertFalse(SourceTogglePolicy.isLegacyShapedNames(setOf("JRS", "XXZB5")))
        assertFalse(SourceTogglePolicy.isLegacyShapedNames(setOf("JRS", "JRKAN", "XXZB5")))
    }

    @Test
    fun `摘要文案要能说清当前在解析哪些源`() {
        // ⚠️ 期望值从 Provider.entries 派生而不是写死 "JRS / JRKAN"：
        // 这条判据要守的是"摘要把开着的源都列出来、顺序按枚举"，
        // 写死字符串的话加一个源就红一次 —— 而红的原因是夹具过期，不是行为错了。
        assertEquals(
            Provider.entries.joinToString(" / ") { it.displayName },
            SourceTogglePolicy.summary(SourceTogglePolicy.ALL),
        )
        assertEquals("仅 JRKAN", SourceTogglePolicy.summary(setOf(Provider.JRKAN)))
        assertEquals("仅 JRS", SourceTogglePolicy.summary(setOf(Provider.JRS)))
        assertEquals("仅 xxzb5", SourceTogglePolicy.summary(setOf(Provider.XXZB5)))
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
        // ⚠️ 期望 = "全开减去 JRS"，不是写死的 {JRKAN}：
        // 第三源接入后默认全开含 XXZB5，写死两个会让这条判据把
        // "XXZB5 也被顺手关了"这种真故障读成"正常"。
        val expected = SourceTogglePolicy.ALL - Provider.JRS
        assertEquals(expected, prefs.enabledProviders.value)
        assertEquals(expected, vm.enabledProviders.value)
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

    /**
     * 迁移判据的**接线**（判据写了没人消费 = 假安全感，本仓库的老问题）。
     *
     * `migrated` 形参与 [SourceTogglePolicy.isLegacyShapedNames] 都可以
     * "声明得漂亮但生产零调用" —— 那样升级用户照样看不见新源，
     * 而 SourceTogglePolicyTest 全绿。所以这里扫源码钉死三件事：
     * 读标记、把标记**传进**判据、迁移那一次**同时**写回标记与集合
     * （只写标记会留下"第二次启动又变回关"的窗口，见 SourcePrefs 的注释）。
     */
    @Test
    fun `迁移判据在生产里真的被消费 且迁移那一次同时写回标记与集合`() {
        val prefsFile = TestPaths.productionFile("SourcePrefs.kt")
        val prefs = GuardSourceScan.stripNonCode(prefsFile.readText())
        // ⚠️ 查**标识符**而不是字面量 "new_source_migrated"：那个值在字符串里，
        // 而 stripNonCode 连字符串一起剔（本仓库守卫铁律①），查字面量只会假红。
        // 标识符被 putBoolean / contains 两处消费，恰好就是"真的在用"的证据。
        assertTrue("SourcePrefs 没有独立的迁移标记键", prefs.contains("KEY_NEW_SOURCE_MIGRATED"))
        assertTrue(
            "SourcePrefs 没有把磁盘标记传给 fromStorage(migrated=…)",
            prefs.contains("migrated = migrated"),
        )
        val body = GuardSourceScan.functionBody(prefsFile.readText(), "fun init(")
        assertTrue(
            "迁移那一次必须同时写回标记与迁移后的集合：只写标记的话第二次启动会被读成旧数据",
            body.contains("putBoolean(KEY_NEW_SOURCE_MIGRATED, true)") &&
                body.contains("putStringSet(KEY_ENABLED, SourceTogglePolicy.toStorage(resolved))"),
        )
        assertTrue(
            "只有**未迁移过**才写回（已迁移用户的设置一个字节都不许动）",
            body.contains("if (!migrated)"),
        )

        val policyFile = TestPaths.productionFile("SourceTogglePolicy.kt")
        assertTrue(
            "isLegacyShapedNames 必须在 fromStorage 里当闸门用，而不是只被测试引用",
            GuardSourceScan.functionBody(policyFile.readText(), "fun fromStorage(")
                .contains("isLegacyShapedNames("),
        )
        assertTrue(
            "旧时代名单不许写成 Provider.entries 的减法（加第四源时会把用户关掉的源再打开一次）",
            GuardSourceScan.stripNonCode(policyFile.readText()).contains("LEGACY_NAMES"),
        )
        // ⚠️ 上一句只钉住"名字还在"，钉不住值被改成 `entries - XXZB5` 的减法派生
        //（回退实验第 4 轮实测：改成减法后守卫全绿）。这里钉**声明形状**：
        // 写死的名单 = 赋值右侧出现两个旧源的标识符；减法派生 = 出现 entries/filter。
        val declaration = Regex("""LEGACY_NAMES\s*:\s*Set<String>\s*=\s*(.+)""")
            .find(GuardSourceScan.stripNonCode(policyFile.readText()))
            ?: error("LEGACY_NAMES 的声明找不到了 —— 这条守卫会静默全绿，所以直接失败")
        val value = declaration.groupValues[1]
        assertTrue(
            "LEGACY_NAMES 必须写死 JRS/JRKAN 两个标识符（历史名单不随枚举长大）",
            value.contains("Provider.JRS.name") && value.contains("Provider.JRKAN.name"),
        )
        assertFalse(
            "LEGACY_NAMES 不许从 Provider.entries 派生（减法会把未来的第四源用户也判成旧时代）",
            value.contains("entries") || value.contains("filter"),
        )
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

    private fun productionRoot(): File = TestPaths.productionRoot
}
