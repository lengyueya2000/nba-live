# 状态层与界面层优化点清单（只读审查）

- 审查员：`rev-ui-state`；任务 `task-3`；唯一写入物 = 本文件。**未修改任何生产代码，未运行 Gradle/adb。**
- 快照：`HEAD = ba6b247`（v1.2.24 文档提交），`git status --short` **干净**。行号全部按 `read`/`grep` 实测（不用 PowerShell `Get-Content` 行数）。
- 范围：`ui/`（含 `theme/`、`screens/`、`components/`）+ `MainActivity.kt`/`NbaLiveApplication.kt` 骨架段。播放页与播放小文件归 `rev-player`，数据层归 `rev-data`，此处只在"状态流来源"上引用。
- 去重口径：先读 `review/coverage-ledger.md`（95 条：87 已落地 / 8 有据取舍），**已落地的不当新发现、有据取舍不翻案**；下面 21 条均为该台账未覆盖的点。唯一的例外在第 11 条（09 号守卫的"度量与主张脱节"，`audit-guards.md` A 类未列此条）。

## Top 5 高价值

| # | 结论 | 位置 | 等级 | 成本 | 判定 |
|---|---|---|---|---|---|
| 1 | 两个定时轮询 effect 用 `LaunchedEffect` 而非 lifecycle 驱动：**切后台/小窗后仍在跑**，其中比分链每 45s 真发一次 ESPN 请求 | `AppEffects.kt:67-72` | 高 | S | 已证实（机制）+ 需真机日志确认频率 |
| 2 | `LocalLiveDotAlpha` 是 **static** CompositionLocal 且每帧换值 → 提供者整个内容子树每帧被标记失效（列表/直播/「我的」三个页面都在里面） | `Common.kt:561,578-580` | 高 | S | 机制已证实；重组面需 Profiler |
| 3 | `AppScoresRefreshEffect` 在「直播」标签页也轮询比分，而该页**根本不渲染比分**（`LiveNowScreen` 无 `scores` 参数） | `AppEffects.kt:68` | 中 | S | 已证实 |
| 4 | 联赛筛选后场次为 0（或直播页无直播）时，下拉刷新/刷新按钮**没有任何进度反馈**：指示器与 `RefreshingBar` 都被"页内列表为空"挡掉 | `MatchListScreen.kt:118,133-137` | 中 | S | 已证实 |
| 5 | VM 依赖有三处不可注入（`ScoreboardRepository()` 就地 new、`HttpClient.shared`、`System.nanoTime/currentTimeMillis/ZonedDateTime.now`）→ 比分链、检查更新链、缓存过期语义在 JVM 单测里驱动不了 | `MainViewModel.kt:325,544,401,409,773` | 中 | S | 已证实 |

---

## 1. 【高】切后台后两个轮询 effect 仍在跑，比分每 45 秒真发一次网络请求

一句话：`LaunchedEffect` 不是 lifecycle-aware，只有路由/标签变化才会停——KDoc 说的"切后台时组合被移出也会自然停"不成立。

`app/src/main/java/com/nbalive/app/ui/AppEffects.kt:67-72`
```kotlin
    LaunchedEffect(route, tab) {
        if (route != Route.LIST || tab == NavTab.PROFILE) return@LaunchedEffect
        while (true) {
            onTick()
            delay(SCORES_REFRESH_INTERVAL_MS)
        }
    }
```
同文件 `:50-51`（被证伪的承诺）与 `:251`（本文件自证组合在 PAUSED 时仍活着）：
```kotlin
 * ⚠️ 只在**列表标签可见**时跑（见 [AppScoresRefreshEffect]）：
 * 用户进播放页看球时后台不该还在轮询，切后台时组合被移出也会自然停。
...
 * 小窗期间 Activity 是 PAUSED，`collectAsStateWithLifecycle` 停止收集。
```
`app/src/main/java/com/nbalive/app/ui/AppEffects.kt:155-158` 同款循环（状态老化），每 60s 一次。

**影响**：用户按 Home / 切到别的 App（进程未被冻结期间，含画小窗期间），只要最后停在「赛事/直播」标签，`:70` 就会继续每 45 秒调用 `refreshScores()` → `ScoreboardRepository.load` 真发请求（其 TTL 是 20s，吸不掉 45s 的节拍，见 `ScoreboardRepository.kt:43`），直到进程被系统冻结/回收。用户观感=后台耗电与流量；对 ESPN 也是无意义的重复轮询（KDoc 明确说"其它页面轮询它没有任何意义，只是白耗流量和电"，实际连后台都在轮）。状态老化链（纯内存）同样不停，只是代价小。
**等级：高**（后台网络 + 耗电，且与文档承诺相反，属"代码与主张不一致"的稳定性/资源问题）。**成本：S**（把 ticker 改成 `LocalLifecycleOwner.current.lifecycle.repeatOnLifecycle(RESUMED)` 驱动，或让 `onTick` 只在 `Lifecycle.State.RESUMED` 时执行）。
**判定：已证实（机制）**；"后台实际多久发一次/发了多少"需真机验证：`adb logcat` 抓 `Scores` 的 `debugLog`（`MainViewModel.kt:360`）或宿主抓包，后台放 5 分钟看是否有 6~7 次请求。

## 2. 【高】`LiveDotPhase` 用 static CompositionLocal 每帧换值 → 整个内容子树每帧失效

一句话：呼吸 alpha 走 `staticCompositionLocalOf`，而它的语义是"值变化 ⇒ 提供者内的整棵子树重组"，与紧随其后的注释（"只在绘制阶段读状态，不触发重组"）相反。

`app/src/main/java/com/nbalive/app/ui/components/Common.kt:561`
```kotlin
val LocalLiveDotAlpha = androidx.compose.runtime.staticCompositionLocalOf { 1f }
```
`Common.kt:571-580`
```kotlin
    val t = rememberInfiniteTransition(label = "live-dot-phase")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalLiveDotAlpha provides a) {
        content()
    }
```
`Common.kt:586-587` 的注释声称"只在**绘制阶段**读状态，不触发重组"，但 `LiveDot` 的 alpha 是**组合期读**的参数（`Common.kt:595` 的 `alpha: Float = LocalLiveDotAlpha.current`），不是 draw 期读的 State：
`app/src/main/java/com/nbalive/app/ui/AppChrome.kt:473-475,513`（子树边界包含第三个标签页）：
```kotlin
    LiveDotPhase {
        when (tab) {
            NavTab.MATCHES -> MatchListScreen(
...
            NavTab.PROFILE -> ProfileScreen(
```
**影响**：`a` 每帧（900ms 往返、约 60fps）变化，`static` 提供者把 `content()` 标记为整体失效 → 只要列表页可见，`MatchListScreen`/`LiveNowScreen`/`ProfileScreen` 所在的子树每帧都走一次失效/跳过判定；能跳过的部分取决于参数稳定性，而三个 UiState 都未标 `@Immutable`（见第 6 条）⇒ 至少 `LiveDot` 自身每帧重执行，`ProfileScreen` 这个**一颗红点都没有**的页面也每帧被卷进同一棵树。另注：`graphicsLayer { this.alpha = alpha }` 里读的是**闭包捕获的 Float**，没有任何可观察 State，所以"只在绘制阶段读状态"这句话对本用法不成立（draw 期读 State 的写法是 `graphicsLayer { alpha = state.value }`）。
**等级：高**（可持续的每帧浪费，作用在用户停留最久的列表页）；**成本：S**（把本地改成 `staticCompositionLocalOf<() -> Float>`-式稳定引用，或让 `LiveDot` 接收 `State<Float>` 并在 `graphicsLayer{}` 内读）。
**判定：机制已证实**（static local 语义 + 每帧换值 = 子树失效）；"实际掉帧/CPU"需 Profiler：Layout Inspector 的 recomposition counts 或 `perfetto` — 看列表页静止时 `MatchListScreen` 的 recomposition 次数是否随时间线性增长即证伪/证实。

## 3. 【中】「直播」标签页也在轮询比分，而它没有任何地方画比分

一句话：可见性判据是 `tab == NavTab.PROFILE`，漏了"只有 MATCHES 页消费 scores"这个事实。

`app/src/main/java/com/nbalive/app/ui/AppEffects.kt:68`
```kotlin
        if (route != Route.LIST || tab == NavTab.PROFILE) return@LaunchedEffect
```
`app/src/main/java/com/nbalive/app/ui/screens/LiveNowScreen.kt:64-80`（参数表里没有 scores）
```kotlin
fun LiveNowScreen(
    liveMatches: List<Match>,
    loading: Boolean,
    onMatchClick: (Match) -> Unit,
```
`app/src/main/java/com/nbalive/app/ui/AppChrome.kt:500-510`（`scores` 只传给 MATCHES 分支）。
**影响**：用户停在「直播」页（看球高峰的常驻页）时，每 45 秒发一次 ESPN 请求，而这些数据在 UI 上无处可去；配合第 1 条还会一直发到后台。**等级：中**（纯浪费请求/流量，无功能损失）；**成本：S**（判据改成 `tab != NavTab.MATCHES`）。**判定：已证实**（`LiveNowScreen` 签名无 `scores`，`ListBranch` 只往 MATCHES 传）。

## 4. 【中】筛选后列表为空时，刷新没有任何进度反馈

一句话：`refreshing` 只被"页内列表非空"允许显示，而"首载 loading"又只认**未筛选**的列表 —— 两者之间的空档没有任何指示器。

`app/src/main/java/com/nbalive/app/ui/screens/MatchListScreen.kt:117-137`
```kotlin
    PullToRefreshBox(
        isRefreshing = refreshing && matches.isNotEmpty(),
...
                matches.isEmpty() -> EmptyState()
                else -> {
                    if (refreshing) RefreshingBar()
```
`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:684-685`（`firstLoad` 看的是**未筛选**列表，而页面收到的 `matches` 已按联赛筛过）
```kotlin
            val firstLoad = _listState.value.matches.isEmpty()
            _listState.update {
```
**影响**：选一个当天 0 场的联赛（例如无 CBA 比赛时切到 CBA），此时 `_listState.matches` 非空 ⇒ `loading=false`、`refreshing=true`；而页面收到的 `matches` 为空 ⇒ 走 `EmptyState()`，`isRefreshing` 也是 false、`RefreshingBar` 根本不在这个分支里 ⇒ 用户点右上角「刷新」或下拉，**整屏毫无变化**（持续到请求结束）。`LiveNowScreen` 同款：`LiveNowScreen.kt:87` 用 `live.isNotEmpty()` 作门槛，非比赛时段下拉刷新同样毫无反馈（`:140 live.isEmpty() -> EmptyLiveState()`）。
**等级：中**（用户可见的"按钮点了没反应"，且触发条件常见）；**成本：S**（把指示器门槛换成 `listState.busy` 或未筛选列表的忙碌态，并允许空态分支渲染 `RefreshingBar`）。**判定：已证实**（分支结构与参数传递链均可读出来）。

## 5. 【中】VM 的三处外部依赖不可注入，连带三条链路无法在 JVM 单测驱动

一句话：`repository`/`loadMatches`/`loadSignals`/`resolveStream`/`prefs`/`savedStateHandle` 都做了注入，但比分源、GitHub HTTP、时钟没做。

`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:325` 与 `:544`
```kotlin
    private val scoreboard = ScoreboardRepository()
...
        val body = when (val r = HttpClient.shared.fetch(url, null, fastFail = true)) {
```
`MainViewModel.kt:401,409,773`
```kotlin
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
...
    private fun wallNowMs(): Long = System.currentTimeMillis()
...
        val aged = repository.agedMatches(current, java.time.ZonedDateTime.now())
```
（`ScoreboardRepository` 自己是有注入点的：`ScoreboardRepository.kt:38-41` 收 `fetch`；但 VM 就地 `new` 后这个注入点在生产路径上不可达。）
**影响**：`refreshScores()`（比分快照映射、`debugLog` 文案、失败静默）与 `checkForUpdate()`（`UpToDate`/`Available` 落状态、防连点）都只能靠"看代码"验证；`SniffedPlaylistCache` 的过期/作废语义在 VM 层用的是真实墙钟，也无法把"过期前后"钉成单测（缓存本身的纯逻辑在 `rev-data` 范围，本条目只指 VM 侧的时钟与它不可注入）。
**等级：中**（可测试性，直接决定这两条新链路后续改动是否有判据兜底）；**成本：S**（构造函数补 `scoreboard: ScoreboardRepository = ScoreboardRepository()`、`clock: () -> Long`、`now: () -> ZonedDateTime`；`fetchLatestRelease` 走注入的 `fetch: suspend (String) -> FetchResult`）。**判定：已证实**（构造函数签名 + 就地 new）。

## 6. 【中】三个 UiState 未标 `@Immutable`，含 `List`/`Map` ⇒ 传它们的 composable 不可跳过

一句话：数据层已经用了这个手段（`Match`/`StreamSource` 都标了），UI 状态层漏了。

`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:35,41,78`
```kotlin
data class MatchListUiState(
...
    val matches: List<Match> = emptyList(),
...
    val counts: Map<League, Int> = mapOf(
```
对照数据层已用同一手段：`app/src/main/java/com/nbalive/app/data/model/Match.kt:166` 的 `@Immutable data class Match` 与 `:190` 的 `@Immutable data class StreamSource`。`MatchDetailUiState`（`MainViewModel.kt:87`）与 `PlayerUiState`（`:95`，含 `List<StreamSource>` + 7 个 String?）同问题。
**影响**：`List`/`Map`（接口类型）⇒ 类被推断为 unstable ⇒ 以它为参数的 `ListBranch`/`MatchListScreen`/`MatchDetailScreen`… 无法进入 skip 路径，任何父级失效都要重跑它们的函数体（与第 2 条的"每帧失效"叠加时尤其明显）。**等级：中**（重组面放大，配合第 2 条从"理论浪费"变"每帧浪费"）；**成本：S**（加 `@Immutable`；这些类只在构造时一次性算出列表，标注是诚实的）。
**判定：已证实**（`@Immutable` 缺席 + 接口类型字段；Compose 稳定性推断规则）。"省下多少"需 Layout Inspector 的 skip 计数。

## 7. 【中】固定 dp 高度压随 fontScale 缩放的 sp —— 大字号下 chip 里文字被裁

一句话：`height(24.dp)` 不随字体缩放，里面的 `10.5.sp` 却会乘 fontScale，而 Manifest 明确不接管 `fontScale`。

`app/src/main/java/com/nbalive/app/ui/components/Common.kt:473-479`
```kotlin
    Row(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(Radius.chipLg))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.chipLg))
            .padding(horizontal = 9.dp),
```
同类：`Common.kt:367 .height(20.dp)` + `:384 fontSize = 9.5.sp`（LeagueChip）、`Common.kt:396 .height(20.dp)` + `:413 9.5.sp`（SignalBadge）、`Common.kt:487 .height(26.dp)`（HeroPill）。`app/src/main/AndroidManifest.xml:25` 已把 `fontScale` 放进 `configChanges`（不重建 Activity，但缩放照旧生效）。
**影响**：`Text` 未指定 `style`，行高继承 `LocalTextStyle`（M3 默认 `bodyLarge`，lineHeight 20.sp）；fontScale 1.3 ⇒ 行高 26dp > 24dp，`clip` 又把绘制裁在 24dp 内 ⇒ 状态标签/联赛标签/信号数徽章的上下被切（放大越多越明显）。本仓库对 fontScale 已有先例意识（`logoFallbackFontSize` 就是为它写的），这几处漏了。
**等级：中**（特定用户群可直接看到的显示缺陷，非崩溃）；**成本：S**（`height(...)` → `heightIn(min = ...)`）。**判定：机制已证实**；视觉裁切幅度需真机确认：开发者选项字体大小/显示大小设 1.5×、2.0× 截图列表页（或 Compose 预览 `fontScale = 2f`）。

## 8. 【中】SharedPreferences 的首次读取在主线程上等磁盘加载

一句话：`onCreate` 里同步跑 `attach+init`，而 `getSharedPreferences` 的首次数据访问会阻塞到 XML 读完。

`app/src/main/java/com/nbalive/app/MainActivity.kt:53-54`
```kotlin
        val prefs: PrefsStore = AndroidPrefsStore(applicationContext)
        prefs.init()
```
`app/src/main/java/com/nbalive/app/ui/AppPrefs.kt:39-42`（同一模式在 `PlayerPrefs`/`SourcePrefs` 各一份，`PrefsStore.kt:124-128` 依次调它们）
```kotlin
    fun attach(context: Context) {
        store = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        init()
    }
```
**影响**：三个偏好文件（`app_prefs`/`player_prefs`/`source_prefs`）的首次 `contains`/`getBoolean`/`getStringSet` 都会 `awaitLoadedLocked` 等后台线程把 XML 解析完 ⇒ 冷启动在 `setContent` **之前**被主线程磁盘 IO 挡住（StrictMode 会记为 `DiskReadViolation`；设备冷启动/文件较大时是几十毫秒级）。**等级：中**（启动路径性能，属"可感知但不致命"）；**成本：M**（在 `Application.onCreate` 用后台线程预热并让 `PrefsStore` 暴露"未就绪"读值，或迁到 DataStore/`SharedPreferences` 异步加载）。**判定：机制已证实**（Android 文档语义）；具体毫秒需量化：`adb shell am start -W` 冷启动 `TotalTime`，或 debug 版开 StrictMode 看 `DiskReadViolation` 是否指向 `AppPrefs.attach`。

## 9. 【中】卡片/行是裸 `clickable`，缺 `onClickLabel` 与 `role`

一句话：P2-26 只给 hero 卡与直播卡加了动作词，网格卡、搜索结果行、「我的」页两行仍是"双击以激活"。

`app/src/main/java/com/nbalive/app/ui/screens/MatchListScreen.kt:525`
```kotlin
            .clickable(onClick = onClick),
```
对照同文件 `:343-347`（已修的那条）：
```kotlin
            .clickable(
                onClickLabel = CardSemantics.ACTION_LABEL,
                role = Role.Button,
                onClick = onClick,
            )
```
`app/src/main/java/com/nbalive/app/ui/screens/SearchScreen.kt:248`、`app/src/main/java/com/nbalive/app/ui/screens/ProfileScreen.kt:210,251` 同为裸 `clickable(onClick=...)`。
**影响**：TalkBack 焦点落到网格卡/搜索结果/「检查更新」/「下载 x.y.z」时只播"双击以激活"，不播动作语义（网格卡是"播放比赛"、更新行是"检查更新"、下载行是"打开浏览器"），视障用户无法预判；`CardButtonSemanticsTest` 只扫 `CardSemantics.ACTION_LABEL` 的两个既有调用点，**新增可点区域不会让任何守卫变红**。**等级：中**（无障碍缺口，且已有统一手段可复用）；**成本：S**（统一走 `CardSemantics.ACTION_LABEL` 或各自给 `onClickLabel`+`role`；守卫改成"`screens/` 里的 `clickable` 必须带 onClickLabel"）。**判定：已证实**（源码扫描）；"实际朗读内容"需实机 TalkBack 走查。

## 10. 【中】`TeamLogo` 每次重组都新建 `ImageRequest`

一句话：`ImageRequest.Builder(...).build()` 写在组合体内（不在 `remember` 里）。

`app/src/main/java/com/nbalive/app/ui/components/Common.kt:149-153`
```kotlin
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
```
**影响**：列表页每张卡的每个队徽、每个重组都会新建一个 `ImageRequest` 对象（`hero` 卡 2 个、网格卡 2 个、搜索结果 2 个、直播卡 2 个）；叠加第 2 条（每帧子树失效）后，这个分配会变成"每帧每个可见队徽一次"，是列表滚动/静止时持续的 GC 压力源。**等级：中**（分配已确证；是否连带重新发请求取决于 Coil 对 `model` 的相等性判断，需要实测）；**成本：S**（`remember(url) { ImageRequest.Builder(...)…build() }`）。**判定：分配=已证实；"是否触发重新下载/闪图"=推测**。验证：给 `TeamLogo` 加一个请求计数（或在 ImageLoader 上挂 `EventListener`）看静止页面 1 分钟内的请求数是否 > 0；Profiler 看分配速率。

## 11. 【中】"根节点只消费一个状态对象"没有真的减少重组面，守卫的度量点被搬家规避

一句话：5 条 flow 只是被移进 `rememberAppUiState`，根作用域的失效面没变；而守卫数的是"`fun NbaLiveApp(` 体内的 `collectAsStateWithLifecycle(` 个数"。

`app/src/main/java/com/nbalive/app/ui/AppUiState.kt:48-52`
```kotlin
    val route by viewModel.route.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val darkPref by viewModel.darkPref.collectAsStateWithLifecycle()
    val aspectMode by viewModel.aspectMode.collectAsStateWithLifecycle()
    val enabledProviders by viewModel.enabledProviders.collectAsStateWithLifecycle()
```
`app/src/test/java/com/nbalive/app/ui/AppStructureGuardTest.kt:107-123`（合规样本就是当前生产形状）
```kotlin
    fun `根节点里的状态收集不得超过 2 处`() {
        val collects = rootCollects(source("NbaLiveApp.kt"))
...
            fun NbaLiveApp(viewModel: MainViewModel) {
                val ui = rememberAppUiState(viewModel)
                val inPip by PipState.inPip.collectAsStateWithLifecycle()
```
判据实现（`AppStructureGuardTest.kt:318`）是 `Regex("""collectAsStateWithLifecycle\s*\(""").findAll(body).count()`。
**影响**：`route`/`nav`/`darkPref`/`aspectMode`/`enabledProviders` 任一变化仍会让根作用域重跑 `Scaffold`+顶栏+底栏级代码（包装成 data class 不改变这点）；`route` 还与 `nav.route` 是同一信息（见第 18 条）。判据层面：这条守卫今后**不可能**再红 —— 把收集搬到别的文件即可（本轮就是这么做的），它度量的是"某个函数里的调用点个数"，而不是它声称守住的"根作用域读取了几条 flow"。**等级：中**（并非用户可见故障，但它是"判据与主张脱节"的新实例，属 `audit-guards.md` A 类模式；好在当前 5 条都属低频变化）；**成本：S**（守卫改成统计 `NbaLiveApp.kt` + `AppUiState.kt` 两文件的收集点，或直接断言 `AppUiState` 字段数上限）。**判定：已证实**（守卫源码 + 合规样本与实际生产逐字同形）。

## 12. 【中】ViewModel 职责过载：网络编排 + 导航 + 偏好 + 播放控制 + 比分 + 更新检查 + 手写 JSON 都在 1469 行里，并绕过分层直接发 HTTP

一句话：`MainViewModel` 既做编排又做 HTTP 客户端与 JSON 解析，与新功能（检查更新）一起越过了 `data/` 的边界。

`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:542-551`（VM 直接发请求 + 手写解析）
```kotlin
    private suspend fun fetchLatestRelease(): UpdateCheckPolicy.Decision? {
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        val body = when (val r = HttpClient.shared.fetch(url, null, fastFail = true)) {
            is FetchResult.Ok -> r.body
            else -> return null
        }
        // 手写取字段（本工程没有 JSON 库，见 EspnScoreboard 的同款理由）
        val tag = jsonString(body, "tag_name")
```
`MainViewModel.kt:565-611` 是 47 行的字符串状态机 `jsonString`；`MainViewModel.kt:151-208` 是 10 个构造参数/默认值、922-982 行（`playMatch`）与 996-1136 行（`play`）是完整播放故障切换。
**影响**：`ui/` 层出现 HTTP/JSON ⇒ 与项目已有的"平台/网络/解析都归 `data/`，`ui/` 只消费"约定相反：`Logs` 脱敏纪律、超时/重试策略、缓存 TTL 都各自在 VM 里重新实现一份（例如这里的 `fastFail` 选型与 `MatchRepository` 的候选试探共用 `probeClient` 的 10s `callTimeout`，属隐式耦合）；同时 1469 行/7 个状态源让"改动影响面"难以收敛。
**等级：中**（结构债，非当前故障；但新功能每加一个就往 VM 里加一段网络代码）；**成本：M**（把"检查更新"提成 `data/remote/ReleaseRepository`（注入 `fetch`），`jsonString` 随之下沉，VM 只保留状态机）。（手写 JSON 的健壮性问题——代理对/嵌套——归 `rev-data` 视角，此处不重复计一条。）**判定：已证实**（代码位置与分层约定对照）。

## 13. 【低】`MatchGrid` 每次重组做 O(n) 扫描 + 一次 List 分配

一句话：`firstOrNull`/`filter` 写在组合体里，没有 `remember`。

`app/src/main/java/com/nbalive/app/ui/screens/MatchListScreen.kt:225-227`
```kotlin
    val liveMatch = matches.firstOrNull { it.status == MatchStatus.LIVE }
    // 直播中的比赛已用全宽 hero 卡呈现，网格里不再重复
    val gridMatches = if (liveMatch != null) matches.filter { it.id != liveMatch.id } else matches
```
**影响**：与第 2 条叠加时，这条路径可能每帧跑一次（全部场次一次扫描 + 一次 List 分配）。**等级：低**（"是否真的每帧发生"取决于 skip 判定，未测量）；**成本：S**（`remember(matches) { ... }`，注意 `firstOrNull` 的结果可直接用于 hero 卡）。**判定：分配=已证实；每帧发生=推测**，验证方式同第 2 条（Layout Inspector recomposition 计数）。

## 14. 【低】`Decision.Available.notes` 是零消费状态

一句话：GitHub release body 被解析、塞进状态流，但没有任何 UI 读它。

`app/src/main/java/com/nbalive/app/ui/UpdateCheckPolicy.kt:32-39`
```kotlin
        data class Available(
            /** 远端最新版本号（已规范化，如 `1.2.24`） */
            val latest: String,
            /** 下载页地址 */
            val downloadUrl: String,
            /** release 说明（可能为空） */
            val notes: String,
        ) : Decision
```
写入点 `MainViewModel.kt:551,560`（`val notes = jsonString(body, "body").orEmpty()` / `notes = notes`）；消费点只有 `ProfileScreen.kt:230 UpdateCheckPolicy.statusText(updateState)` 与 `:263 available.latest`，`notes` 全仓无引用（`grep '\.notes|notes ='` 仅这两处写入）。
**影响**：release 说明往往几十 KB，被逐字符扫描进 `StringBuilder` 后随状态流长期持有，却永远不显示（将来要展示时也没有任何判据钉住"展示在哪"）。**等级：低**（浪费很小、无功能影响）；**成本：S**（删字段，或真在「我的」页展示）。**判定：已证实**（grep 全仓消费点）。

## 15. 【低】"直播中"有两份来源，`StatusLabel.of` 的 LIVE 分支在生产路径不可达

`app/src/main/java/com/nbalive/app/ui/components/Common.kt:429,468-472`
```kotlin
    liveLabel: String = "直播中",
...
    val label = if (status == MatchStatus.LIVE) {
        liveLabel
    } else {
        StatusLabel.of(status, isPostponed)
    }
```
`app/src/main/java/com/nbalive/app/ui/StatusLabel.kt:32-33` 里还有一份 LIVE → "直播中"。
**影响**：改 `StatusLabel` 的 LIVE 文案不会影响任何界面（`Common.kt` 提前分支了），而 `StatusLabel.of(LIVE)` 只有单测在覆盖 ⇒ 又一处"测试测的是生产走不到的分支"。**等级：低**（文案重复，未来改词会踩）；**成本：S**（让 `status == LIVE` 时也走 `StatusLabel.of`，`liveLabel` 只作覆盖参数）。**判定：已证实**（全仓 `StatusLabel.of` 仅 `Common.kt:471` 一处调用）。

## 16. 【低】详情页/播放页持有的 `Match` 副本不参与状态老化

`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:770-779` 只老化 `_listState`：
```kotlin
    fun ageListStatuses() {
        val current = _listState.value.matches
        if (current.isEmpty()) return
        val aged = repository.agedMatches(current, java.time.ZonedDateTime.now())
```
而 `_detailState.match` / `_playerState.match` 只在 `loadDetailSignals`（`:834-838`）与 `playMatch`（`:958-970`）里被覆盖。
**影响**：列表页已经把某场从「直播中」老化成「已结束」，但从列表进入的详情页/播放页持有的是那份旧 `Match`；老化 ticker 又只在 `route == LIST` 时跑（`AppEffects.kt:154`），所以详情页停留期间标题下的状态标签可以一直显示「直播中」（需要停留跨越 3.5 小时窗口才可见，实际发生概率低）。**等级：低**（少数场景 + 需长时间停留）；**成本：S**（老化时一并更新 `_detailState`/`_playerState` 的 match，或在进入 DETAIL 时按当前时刻重算一次）。**判定：已证实**（三处状态的写入点可读出来）。

## 17. 【低】`navStateKeyFor` 的 `else` 兜底把字段名笔误变成"静默取错字段"

`app/src/main/java/com/nbalive/app/ui/NavState.kt:112-117`
```kotlin
internal fun navStateKeyFor(field: String): String = when (field) {
    "tab" -> KEY_TAB
    "route" -> KEY_ROUTE
    "match" -> KEY_MATCH
    else -> KEY_BEFORE_PLAYER
}
```
调用点 `MainViewModel.kt:238-241` 用字符串字面量取档。**影响**：把 `"match"` 打成 `"macth"`，`else` 会安静地返回 `KEY_BEFORE_PLAYER` ⇒ 重建时 `matchId` 读到 `beforePlayer` 的值（`navStateFromStored` 有独立兜底，所以不会崩，只会把导航恢复到错的比赛/丢详情）——一个错字换来"看起来能跑但恢复错了"的静默降级，且现有 `NavRestoreTest` 只测纯函数、不测这组字符串。**等级：低**；**成本：S**（去掉间接层，直接 `savedStateHandle[navStateKeyFor(...)]` → 具名常量/枚举键；或 `else -> error(field)`）。**判定：已证实**（分支语义可读出来）。

## 18. 【低】`route` 是 `nav.route` 的同步镜像，根节点同时收集两份同一信息

`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:264-272`
```kotlin
    private val _route = MutableStateFlow(_nav.value.route)
    val route: StateFlow<Route> = _route.asStateFlow()
...
    private fun setNav(next: NavState) {
        if (next == _nav.value) return
        _nav.value = next
        _route.value = next.route
```
`AppUiState.kt:31,48-49` 同时收集 `route` 与 `nav`。**影响**：同一事实有两个 `StateFlow`，一致性只靠"唯一写入口"这条纪律维持（KDoc 自己也写了"直接写 `_route.value` 会让三者不一致"）——这是 P2-3"两个状态机"修好之后留下的兼容层，对根作用域而言是多余的一次订阅/一次失效来源。**等级：低**（有守卫与单测兜着，暂无实际故障）；**成本：M**（删镜像要改 `AppUiState`/`AppChrome`/测试中对 `viewModel.route` 的用法）。**判定：已证实**（代码事实）。

## 19. 【低】`playerState` 被两处各收集一次

`app/src/main/java/com/nbalive/app/ui/NbaLiveApp.kt:180` 与 `app/src/main/java/com/nbalive/app/ui/AppChrome.kt:152`
```kotlin
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
```
**影响**：播放页任何抖动（`resolving`/`errorId`/`streamUrl` 变化）会同时失效 `RouteHost` 与 `AppTopBar` 两个重启范围；顶栏其实只用了 `playerState.match` 的两个队名（`AppChrome.kt:166`）。**等级：低**（播放页状态变化频率不高，且顶栏那一路很便宜）；**成本：S**（顶栏从一个更窄的 flow 读，或在 `RouteHost` 把标题字符串算好传下去）。**判定：已证实**（两处调用点）。

## 20. 【低】播放侧用裸 `Log.w`，与全项目的 `safeLogWarn` 纪律不一致

`app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:1167`
```kotlin
            Log.w("MainViewModel", "忽略不属于当前播放页的嗅探结果（P2-4）")
```
`LoggingDisciplineTest` 扫的是 `Log.[we](.*, t)`（把 throwable 直接交出）这一形态，所以这里不会红。此处文案是纯字面量、**没有**泄漏风险，问题在纪律的一致性：`src/main` 里只剩这一处直接 `Log.w`，将来有人照着它把 URL 拼进第二参数，守卫也拦不住（它只认 `, t`）。**等级：低**；**成本：S**（改 `safeLogWarn` 或把守卫扩到"`ui/` 不得直接调 `Log.`"）。**判定：已证实**（调用点 + 守卫正则）。

## 21. 【低】首屏 loading 态三页不一致

`app/src/main/java/com/nbalive/app/ui/screens/LiveNowScreen.kt:126-128`（只有一行文字，没有进度指示）
```kotlin
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在加载赛事…",
```
对照 `MatchListScreen.kt:720-733`（`CircularProgressIndicator` + 文案 + 间距）。搜索页的空/未输入态用 `HintState`、直播页空态用图标+标题+副标题，也都自成一套。
**影响**：同一次冷启动在「赛事」页看到转圈、在「直播」页看到一句静止文字，观感像"这页挂了"（尤其首载要 1~3s）。**等级：低**（一致性/观感）；**成本：S**（抽一个共享 `LoadingState(text)` 放 `components/Common.kt`，三页共用）。**判定：已证实**（三处源码对比）。

---

## 我读到但认为不值得改的

- **全部 UI 文案硬编码在 Kotlin（`strings.xml` 只有 `app_name`）**：`review/03-ui.md:474` 已自判"按项目现状不是缺陷"，台账记为有据的取舍，本轮不翻案（翻案需要新的多语言需求，证据不存在）。
- **`MatchDetailScreen` 的信号列表用 `Column + forEachIndexed` 而非 `LazyColumn`**：`MatchDetailScreen.kt:186-194` 写明了取舍依据与**重估条件**（常态 >20 条时改），当前线路数为个位数。
- **纯整洁性四项**：`AppShell` 的内层 `Box(fillMaxSize)` 多套一层全屏容器（`AppChrome.kt:104-119`，不影响测量与绘制）；`LiveNowScreen.kt:91-159` 缩进错乱（重排会污染被当证据用的注释坐标）；`SearchScreen.kt:3` 未使用的 `import android.util.Log`；`ProfileScreen` 只剩一个源时 `summary` 与 `lastOneHint` 并列（一个说状态、一个说约束，分工在 `SourceTogglePolicy` 注释里）。
- **两个 60s 节拍的 ticker 未合并**（`AppListStatusAgeEffect` 与 `MatchGrid` 的 `produceState`）：合并省一个 `delay` 循环，但会把页面自有的日期标签与 VM 状态老化耦合，收益不如耦合代价。`NbaLiveApplication.onCreate` 也只有 debug 版 WebView 调试开关，启动路径本身无可优化点。
- **`Common.kt:446-453` 把单一颜色包成 `Brush.linearGradient`（同色两档）**：已用 `remember` 缓存且注释解释了"remember 必须无条件调用"的坑；换成 `Color` 要动 6 个调用点的类型，收益不明显。

## 需要运行/真机才能定的

1. **第 1 条（后台轮询）**：debug 包停在「赛事」页 → 按 Home → `adb logcat -s Scores` 看后台 5 分钟内 `ESPN 比分板 …`（`MainViewModel.kt:360`）的行数 + 抓包；同时要确认**进程冻结**策略（Android 12+ Freezer / OEM 差异）——它决定该条停在"高"还是降到"中"。
2. **第 2、6、10、13 条（重组面与分配）**：Layout Inspector 的 recomposition/skip 计数：列表页静止 30s 看 `MatchListScreen` 是否随时间线性重组、`MatchCard` 的 skip 比例、`ImageRequest` 创建速率（挂 `EventListener` 或加临时计数）。
3. **第 7、9 条（真机 UI 走查）**：字体缩放 1.3×/1.5×/2.0× 截图列表页与直播页看 chip 是否裁切（Compose 预览看不到最终裁切）；TalkBack 走查网格卡/搜索结果/「检查更新」行的朗读文本（JVM 单测跑不到语义树）。
4. **第 4、8 条（可感知但需量化）**：切到 0 场的联赛后点刷新，看 3s 内屏幕是否有任何变化；冷启动用 `adb shell am start -W` 的 `TotalTime` + debug 版 StrictMode 的 `DiskReadViolation` 是否落在 `AppPrefs.attach`。
5. **第 5 条（不可注入）**：本身是代码结构事实，不需要真机；"补齐注入后覆盖哪些用例"要写测试才能验证（本轮只读，不写测试）。