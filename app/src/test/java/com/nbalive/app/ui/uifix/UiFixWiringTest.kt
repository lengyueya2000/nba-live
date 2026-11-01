package com.nbalive.app.ui.uifix

import com.nbalive.app.TestPaths
import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态/界面层修复的**接线 + 结构判据**（review/fix/ui-state.md）。
 *
 * ## 为什么必须扫源码
 *
 * 这一轮里大部分修复的形态是"**编译器与运行期都没有信号**"：
 *  - `@Immutable` 少一个：只表现为跳过率下降；
 *  - 固定 `height(20.dp)` 写回去：只有大字号用户能看出文字被裁；
 *  - 裸 `clickable`：只有读屏用户能听出"双击以激活"；
 *  - `LaunchedEffect` 换回 `while(true)`：只有抓包能看出后台还在发请求。
 *
 * 所以判据落在**调用点/结构**上（本项目既有守卫的同一形态），
 * 且每条判据都喂了内存样本自检（违规样本必须命中、合规样本必须不命中）。
 *
 * 三条自律与全仓守卫一致：断言前 [GuardSourceScan.stripNonCode] 剔注释与字符串；
 * 匹配调用而不是裸标识符；文件定位不唯一直接 `error()`，绝不用 `assumeTrue` 跳过。
 */
class UiFixWiringTest {

    // ---------------- H7 / U3 / H9：轮询与刷新反馈的接线 ----------------

    @Test
    fun `H7 两个 ticker 必须用 repeatOnLifecycle 驱动`() {
        val code = src("ui/AppEffects.kt")
        val scores = body(code, "fun AppScoresRefreshEffect(")
        assertTrue(
            "H7：比分 ticker 必须接 lifecycle（否则切后台仍在每 45 秒真发请求）",
            scores.contains("repeatOnLifecycle(") && scores.contains("Lifecycle.State.RESUMED"),
        )
        assertTrue("U3：比分的可见性必须走 shouldRunScoresTicker(", scores.contains("shouldRunScoresTicker("))
        val age = body(code, "fun AppListStatusAgeEffect(")
        assertTrue("H7：状态老化 ticker 同样必须 lifecycle 驱动", age.contains("repeatOnLifecycle("))
        assertTrue("状态老化的判据走 shouldRunTicker(", age.contains("shouldRunTicker("))
        // 反向：不许再出现"裸 while(true) 直接挂在 LaunchedEffect 上"的旧形态
        assertFalse(
            "H7 回退：AppScoresRefreshEffect 里又出现 `LaunchedEffect(route, tab) {` 的裸循环",
            Regex("""LaunchedEffect\s*\(\s*route\s*,\s*tab\s*\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `H9 两个列表页都必须用共享的刷新反馈判据`() {
        for (file in listOf("ui/screens/MatchListScreen.kt", "ui/screens/LiveNowScreen.kt")) {
            val code = src(file)
            assertTrue(
                "$file 没有消费 shouldShowRefreshing（空列表刷新又会毫无反馈）",
                code.contains("shouldShowRefreshing("),
            )
            assertTrue("$file 没有消费 refreshingFeedbackFor（它决定反馈「画在哪」）", code.contains("refreshingFeedbackFor("))
            assertFalse(
                "$file 又用回 `refreshing && <列表>.isNotEmpty()` 这个旧门槛",
                Regex("""isRefreshing\s*=\s*refreshing\s*&&\s*\w+\.isNotEmpty\(\)""").containsMatchIn(code),
            )
        }
    }

    // ---------------- H8：红点相位改 draw 期读 ----------------

    @Test
    fun `H8 共享相位必须是稳定引用且在绘制期读`() {
        val code = src("ui/components/Common.kt")
        assertTrue(
            "LocalLiveDotAlpha 必须提供一个**引用**（`() -> Float`），不是每帧变化的 Float",
            Regex("""staticCompositionLocalOf\s*<\s*\(\s*\)\s*->\s*Float\s*>""").containsMatchIn(code),
        )
        assertFalse(
            "H8 回退：又变成 `LocalLiveDotAlpha provides a`（每帧换值 → 提供者内整棵子树失效）",
            Regex("""LocalLiveDotAlpha\s+provides\s+a\b""").containsMatchIn(code),
        )
        val phase = body(code, "fun LiveDotPhase(")
        assertTrue("相位 lambda 必须被 remember 固定成同一实例", phase.contains("remember("))
        assertTrue("下发的必须是那个稳定 lambda", phase.contains("provides phase"))
        val dot = body(code, "fun LiveDot(")
        assertTrue(
            "alpha 必须在 graphicsLayer 的 lambda 里读相位（组合期读会让子树每帧失效）",
            Regex("""graphicsLayer\s*\{[^}]*phase\s*\(\s*\)""").containsMatchIn(dot),
        )
    }

    // ---------------- U6 / U7 ----------------

    @Test
    fun `U6 三个 UiState 必须标 Immutable`() {
        val code = src("ui/MainViewModel.kt")
        for (name in listOf("MatchListUiState", "MatchDetailUiState", "PlayerUiState")) {
            assertTrue(
                "$name 没有 @Immutable：含 List/Map 的接口类型字段 ⇒ 推断为 unstable，" +
                    "传它的 composable 无法进入 skip 路径（对照 data/model/StreamSource 已标注）",
                Regex("""@Immutable\s+data\s+class\s+$name\s*\(""").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `U7 芯片高度必须用 heightIn min 而不是固定 dp`() {
        val common = src("ui/components/Common.kt")
        val list = src("ui/screens/MatchListScreen.kt")
        assertTrue(
            "LeagueChip / SignalBadge 必须以 heightIn(min = 20.dp) 为下限",
            Regex("""heightIn\(\s*min\s*=\s*20\.dp\s*\)""").findAll(common).count() >= 2,
        )
        assertTrue(
            "StatusChip 必须以 heightIn(min = 24.dp) 为下限",
            Regex("""heightIn\(\s*min\s*=\s*24\.dp\s*\)""").containsMatchIn(common),
        )
        assertTrue(
            "HeroPill 必须以 heightIn(min = 26.dp) 为下限",
            Regex("""heightIn\(\s*min\s*=\s*26\.dp\s*\)""").containsMatchIn(list),
        )
        for ((file, code, dp) in listOf(
            Triple("Common.kt", common, 20),
            Triple("Common.kt", common, 24),
            Triple("MatchListScreen.kt", list, 26),
        )) {
            assertFalse(
                "$file 里又出现固定 `.height(${dp}.dp)`：大字号下 chip 文字会被裁掉（U7）",
                Regex("""\.height\(\s*$dp\.dp\s*\)""").containsMatchIn(code),
            )
        }
        // 判据自检：旧写法必须命中
        assertTrue(
            "判据抓不到 `.height(20.dp)`（它已经退化成摆设）",
            Regex("""\.height\(\s*20\.dp\s*\)""").containsMatchIn("modifier.height(20.dp)"),
        )
    }

    // ---------------- U9：可点区域必须有动作词 ----------------

    @Test
    fun `U9 screens 里的可点区域必须带 onClickLabel`() {
        // ⚠️ 播放链路两个文件不在本轮写清单内（归 fix-player），
        // 它们的裸 clickable 是**已知未修**项，见 review/fix/ui-state.md。
        // 排除清单写死在这里：新增文件不会被静默放过。
        val excluded = setOf("PlayerScreen.kt", "PlayerGestureLayer.kt")
        val files = screensFiles().filter { it.name !in excluded }
        assertTrue("扫不到 screens 源码（排除播放链路后应 ≥5 个）", files.size >= 5)

        val violations = files.flatMap { f ->
            clickableCalls(strip(f.readText()))
                .filterNot { it.contains("onClickLabel") }
                .map { "${f.name}: ${it.replace('\n', ' ').trim().take(70)}" }
        }
        assertTrue(
            "以下可点区域没有 onClickLabel：TalkBack 只播「双击以激活」，读屏用户无法预判动作\n" +
                violations.joinToString("\n").prependIndent("  "),
            violations.isEmpty(),
        )

        // 判据自检：旧写法必须命中、新写法必须不命中
        val bare = clickableCalls(strip("modifier.clickable(onClick = onClick)"))
        assertTrue("判据抓不到裸 clickable", bare.size == 1 && bare.none { it.contains("onClickLabel") })
        val labelled = clickableCalls(
            strip("modifier.clickable(onClickLabel = \"播放比赛\", role = Role.Button, onClick = onClick)"),
        )
        assertTrue("判据把带动作词的写法也判成违规", labelled.size == 1 && labelled.single().contains("onClickLabel"))
    }

    // ---------------- U10 / U13 ----------------

    @Test
    fun `U10 TeamLogo 的 ImageRequest 必须 remember`() {
        val dot = body(src("ui/components/Common.kt"), "fun TeamLogo(")
        assertTrue(
            "U10：ImageRequest 必须写在 remember 里，否则每次重组都新建一个（列表页每个可见队徽一份分配）",
            Regex("""model\s*=\s*remember\s*\(""").containsMatchIn(dot),
        )
    }

    @Test
    fun `U13 MatchGrid 的扫描与过滤必须 remember`() {
        val grid = body(src("ui/screens/MatchListScreen.kt"), "fun MatchGrid(")
        assertTrue(
            "U13：firstOrNull / filter 必须包在 remember(matches) 里（O(n) 扫描 + List 分配）",
            Regex("""remember\(\s*matches\s*\)""").containsMatchIn(grid),
        )
    }

    // ---------------- U8：启动时的磁盘读挪出主线程 ----------------

    @Test
    fun `U8 三份偏好的首次读盘必须在后台预热`() {
        val app = src("NbaLiveApplication.kt")
        assertTrue(
            "NbaLiveApplication.onCreate 必须调用 PrefsPreload.warmUp（早于 MainActivity.onCreate）",
            app.contains("PrefsPreload.warmUp("),
        )
        for (file in listOf("ui/AppPrefs.kt", "ui/PlayerPrefs.kt", "ui/SourcePrefs.kt")) {
            val code = src(file)
            assertTrue("$file 缺少 fun init()（既有守卫依赖它）", code.contains("fun init()"))
            val attachStore = body(code, "fun attachStore(")
            assertFalse(
                "$file 的 attachStore 里出现了数据访问（contains/getBoolean/getStringSet）—— " +
                    "首次访问会 awaitLoadedLocked 等 XML 解析完，正是 U8 要挪走的那一步",
                Regex("""\b(contains|getBoolean|getString|getStringSet)\s*\(""").containsMatchIn(attachStore),
            )
            val attach = body(code, "fun attach(")
            assertTrue(
                "$file 的 attach 必须 = attachStore + init（两步分开，预热点才有立足处）",
                attach.contains("attachStore(") && attach.contains("init()"),
            )
        }

        val store = src("ui/PrefsStore.kt")
        assertTrue("PrefsStore.kt 必须提供预热点 PrefsPreload", store.contains("object PrefsPreload"))
        assertTrue(
            "预热必须真的把读盘丢到后台线程（executor.execute）",
            Regex("""executor\.execute""").containsMatchIn(store),
        )
        // 只看平台层实现那一段（InMemoryPrefsStore.init 是表达式体，不能整文件找）
        val androidImpl = store.substringAfter("internal class AndroidPrefsStore")
        assertTrue("AndroidPrefsStore 必须存在（平台层实现）", androidImpl.isNotBlank())
        assertTrue("AndroidPrefsStore.init 必须走 attachStore（不读盘）", androidImpl.contains("attachStore("))
        assertTrue(
            "预热未完成时要有同步兜底（否则主题会先按默认值渲染一帧再跳变）",
            androidImpl.contains("PrefsPreload.isFinished"),
        )
        // 反向：不许退回 `AppPrefs.attach(` 这种"注入即读盘"的形态
        assertFalse(
            "PrefsStore 的平台层实现又用回 AppPrefs.attach( / PlayerPrefs.attach( / SourcePrefs.attach(",
            Regex("""(?:AppPrefs|PlayerPrefs|SourcePrefs)\s*\.\s*attach\s*\(""").containsMatchIn(androidImpl),
        )
    }

    // ---------------- U12 / U14 / U16 / U17 / U18 / U19 / U20 / U21 ----------------

    @Test
    fun `U12 检查更新的取值已下沉到 ReleaseRepository`() {
        val vm = src("ui/MainViewModel.kt")
        assertFalse("U12：VM 里的 47 行 jsonString 状态机必须删除", vm.contains("fun jsonString("))
        assertTrue("VM 必须委托 ReleaseRepository", vm.contains("releaseRepository.latest("))
        val repo = src("data/remote/ReleaseRepository.kt")
        assertTrue(
            "ReleaseRepository 必须复用 EspnScoreboard.JsonCursor（D15：全仓只留一套取值器）",
            repo.contains("EspnScoreboard.JsonCursor("),
        )
        // 既有守卫（NewFeaturesWiringTest）要求这两条仍在 VM 源码里 —— 别顺手删掉
        assertTrue(vm.contains("HttpClient.shared.fetch("))
        assertTrue(vm.contains("REPO_OWNER") && vm.contains("REPO_NAME"))
    }

    @Test
    fun `U16 老化必须同步详情页与播放页持有的 match`() {
        val vm = src("ui/MainViewModel.kt")
        val age = body(vm, "fun ageListStatuses(")
        assertTrue(
            "U16：只看 _listState 会让详情页/播放页一直显示那份过期的「直播中」",
            age.contains("syncAgedMatchToDetailAndPlayer("),
        )
        val sync = body(vm, "fun syncAgedMatchToDetailAndPlayer(")
        assertTrue("同步函数必须同时更新详情态", sync.contains("_detailState.update"))
        assertTrue("同步函数必须同时更新播放态", sync.contains("_playerState.update"))
    }

    @Test
    fun `U18 根作用域不再同时订阅 nav 与 route`() {
        val code = src("ui/AppUiState.kt")
        assertFalse(
            "U18：route 是 nav.route 的镜像，同一个事实不许订阅两条 flow",
            code.contains("viewModel.route.collectAsStateWithLifecycle()"),
        )
        assertTrue(
            "rememberAppUiState 必须收 nav",
            code.contains("viewModel.nav.collectAsStateWithLifecycle()"),
        )
        assertTrue(
            "route 必须从 nav 派生（对外 API 不变）",
            Regex("""val\s+route\s*:\s*Route\s+get\(\)\s*=\s*nav\.route""").containsMatchIn(code),
        )
    }

    @Test
    fun `U19 顶栏只读它要的窄状态`() {
        val chrome = src("ui/AppChrome.kt")
        val topBar = body(chrome, "fun AppTopBar(")
        assertFalse(
            "U19：顶栏又收集整份 playerState（播放页抖动会同时失效顶栏与 RouteHost 两个重启范围）",
            topBar.contains("viewModel.playerState.collectAsStateWithLifecycle()"),
        )
        assertTrue("顶栏必须读窄状态 playerTitle", topBar.contains("viewModel.playerTitle"))
        val vm = src("ui/MainViewModel.kt")
        assertTrue("VM 必须提供 playerTitle（distinctUntilChanged 收口）", vm.contains("val playerTitle"))
        assertTrue(vm.contains("distinctUntilChanged()"))
    }

    @Test
    fun `U20 播放侧不再有裸 Log 调用`() {
        val vm = src("ui/MainViewModel.kt")
        assertFalse(
            "U20：src/main 里不许再有裸 Log.* 调用（纪律统一走 safeLogWarn）",
            Regex("""\bLog\s*\.\s*[dwevi]\s*\(""").containsMatchIn(vm),
        )
        // ⚠️ 这里**故意不钉文案/字面量**：`src()` 走的是 `GuardSourceScan.stripNonCode`，
        // 它把字符串字面量一起剔掉（全仓铁律①）—— 我第一版写成
        // `vm.contains("safeLogWarn(\"MainViewModel\"")`，判据输入里那个字面量已经不存在，
        // 于是**生产完全正确却误红**（2026-11-01 测试调试，见 review/fix/ui-state.md §6）。
        // 现在只判"原来那处裸 Log.w 所在的函数体里出现了 `safeLogWarn(` 调用形状"：
        // 既抗文案重构，也不与 Lead 的 `LogCallDisciplineTest`（扫 ui/** + MainActivity
        // 的裸 `Log.`）重复 —— 那条管"不许裸调"，这条管"原来那一处确实被改了"。
        val sniffed = body(vm, "fun onStreamSniffed(")
        assertTrue(
            "onStreamSniffed 里那处裸 Log.w 必须改走 safeLogWarn（U20；不判定文案字面量）",
            sniffed.contains("safeLogWarn("),
        )
    }

    @Test
    fun `U21 首屏 loading 必须是共享组件`() {
        val common = src("ui/components/Common.kt")
        assertTrue("components/Common.kt 必须提供 fun LoadingState(text: String)", common.contains("fun LoadingState(text: String)"))
        for (file in listOf("ui/screens/MatchListScreen.kt", "ui/screens/LiveNowScreen.kt")) {
            val code = src(file)
            assertTrue("$file 必须消费共享 LoadingState(text = …)", code.contains("LoadingState(text ="))
            assertFalse(
                "$file 又自带一份私有 LoadingState()（三页又会分成三种样子）",
                Regex("""fun LoadingState\s*\(\s*\)""").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `U15 StatusChip 的 LIVE 文案必须走 StatusLabel`() {
        val chip = body(src("ui/components/Common.kt"), "fun StatusChip(")
        assertTrue(
            "U15：LIVE 也必须走 StatusLabel.of（否则它的 LIVE 分支在生产不可达）",
            chip.contains("StatusLabel.of("),
        )
        assertTrue("liveLabel 只作覆盖参数（传进 StatusLabel.of）", chip.contains("liveLabel ="))
        val label = src("ui/StatusLabel.kt")
        assertTrue(
            "StatusLabel.of 必须收 liveLabel: String? 覆盖参数",
            Regex("""liveLabel\s*:\s*String\?""").containsMatchIn(label),
        )
    }

    // ---------------- fix-data 的列表短缓存：forceRefresh 必须一路传下去 ----------------

    @Test
    fun `用户主动刷新必须穿透 30 秒列表短缓存`() {
        val vm = src("ui/MainViewModel.kt")
        assertTrue(
            "loadOutcome 形参类型必须扩成 (Set<Provider>, Boolean) —— 只收 enabled 时" +
                "refresh() 无法把 forceRefresh 传下去（用户刷新拿到 30 秒前的列表）",
            vm.contains("suspend (Set<Provider>, Boolean) ->"),
        )
        val refresh = body(vm, "private fun refreshWith(")
        assertTrue(
            "refreshWith 必须把 forceRefresh 传进 loadOutcome",
            Regex("""loadOutcome\([^)]*forceRefresh\s*\)""").containsMatchIn(refresh),
        )
        assertTrue(
            "用户主动刷新（下拉/顶栏/重试）必须 force = true",
            Regex("""fun refresh\(\)\s*=\s*refreshWith\(forceRefresh\s*=\s*true\)""").containsMatchIn(vm),
        )
        assertTrue(
            "冷启动必须 force = false（允许命中短缓存；进程重建同理）",
            Regex("""refreshWith\(forceRefresh\s*=\s*false\)""").containsMatchIn(vm),
        )
    }

    // ---------------- P16 / P18：写入口与恢复条上报 ----------------

    @Test
    fun `P16 PipState 的写入口只有一个`() {
        val activity = src("MainActivity.kt")
        assertFalse(
            "P16：MainActivity 又直写 `PipState.inPip.value = …`（inPip 已是只读 asStateFlow，" +
                "全仓的写入口只允许 PipState.setInPip）",
            Regex("""PipState\s*\.\s*inPip\s*\.\s*value\s*=""").containsMatchIn(activity),
        )
        assertTrue("三处写点必须都改成 PipState.setInPip(", activity.contains("PipState.setInPip("))
        // 三处（onCreate / onPictureInPictureModeChanged / onDestroy）都要在
        assertEquals(
            "MainActivity 里的 setInPip 调用点应为 3 处",
            3,
            Regex("""PipState\s*\.\s*setInPip\s*\(""").findAll(activity).count(),
        )
    }

    @Test
    fun `P18 恢复条上报必须只置标记 不换线`() {
        val app = src("ui/NbaLiveApp.kt")
        assertTrue(
            "P18：根节点必须把 PlayerScreen 的 onDeadEndSuspect 接到 VM 入口",
            app.contains("onDeadEndSuspect = viewModel::onDeadEndSuspectOnly"),
        )
        val only = body(src("ui/MainViewModel.kt"), "fun onDeadEndSuspectOnly(")
        assertFalse(
            "P18：这个入口绝不能换线 —— 出现 advanceAfterNoSignal 会把恢复条与那 8 秒选择窗口一起取消 " +
                "（fix-player 明确拦下过这个写法）",
            only.contains("advanceAfterNoSignal"),
        )
        assertTrue("它只该置疑似标记", only.contains("markPlaybackSuspect(true)"))
    }

    // ---------------- 扫描自检 ----------------

    @Test
    fun `扫描必须真的扫到生产源码`() {
        val files = appFiles()
        assertTrue("扫不到生产源码，上面的判据会静默全绿", files.size >= 40)
        assertTrue("没扫到 MainViewModel.kt", files.any { it.name == "MainViewModel.kt" })
        assertTrue("没扫到 Common.kt", files.any { it.name == "Common.kt" })
        assertTrue("没扫到 ReleaseRepository.kt（U12 的新文件）", files.any { it.name == "ReleaseRepository.kt" })
    }

    // ---------------------------------------------------------------
    // 基础设施
    // ---------------------------------------------------------------

    /** `.clickable(...)` 的**完整实参文本**（按括号配对截取，跨行调用也能取全）。 */
    private fun clickableCalls(code: String): List<String> {
        val out = mutableListOf<String>()
        val marker = ".clickable"
        var idx = code.indexOf(marker)
        while (idx >= 0) {
            var j = idx + marker.length
            while (j < code.length && code[j].isWhitespace()) j++
            if (j < code.length && code[j] == '(') {
                var depth = 0
                var k = j
                while (k < code.length) {
                    when (code[k]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    k++
                }
                out += code.substring(j, minOf(k + 1, code.length))
            }
            idx = code.indexOf(marker, idx + 1)
        }
        return out
    }

    /** `marker`（必须带左括号）的花括号函数体；找不到直接 `error()`。 */
    private fun body(code: String, marker: String): String {
        val start = code.indexOf(marker)
        require(start >= 0) { "源码里找不到 $marker —— 判据会静默全绿" }
        var i = start + marker.length - 1
        require(i < code.length && code[i] == '(') { "marker 必须以左括号结尾：$marker" }
        var paren = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '{' -> if (paren == 0) return balanced(code, i)
            }
            i++
        }
        error("$marker 找不到函数体 —— 判据会静默全绿")
    }

    private fun balanced(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        error("花括号不配对 —— 判据会静默全绿")
    }

    private fun strip(raw: String): String = GuardSourceScan.stripNonCode(raw)

    /** 生产源码目录下全部 kt。 */
    private fun appFiles(): List<File> {
        val cwd = TestPaths.workingDirPath()
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd，候选=${roots.map { it.path }}）—— 判据会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun screensFiles(): List<File> =
        appFiles().filter { it.parentFile?.name == "screens" }.sortedBy { it.name }

    /** 按相对路径取唯一一份生产源码（已剔注释与字符串）；命中数 ≠1 直接 `error()`。 */
    private fun src(relative: String): String {
        val normalized = "/com/nbalive/app/$relative"
        val hits = appFiles().filter { it.path.replace('\\', '/').endsWith(normalized) }
        val file = hits.singleOrNull()
            ?: error("$relative 命中 ${hits.size} 份（${hits.map { it.path }}）—— 判据会守错文件")
        return strip(file.readText())
    }
}