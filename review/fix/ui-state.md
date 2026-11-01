# 状态/界面层修复报告（fix-ui-state / task-8）

- 基线：`HEAD = ba6b247`（v1.2.24），工作区干净时开跑；本轮**未运行** Gradle/adb/构建/安装（Lead 统一编译并回报错误）。
- 范围：`ui/**` + `MainActivity.kt` + `NbaLiveApplication.kt`（另按 Lead 授权：`ui/PlayerPrefs.kt` 的 `attachStore` 两行、新建 `data/remote/ReleaseRepository.kt`）。
- 唯一报告文件 = 本文件；测试只**新建**，未改任何既有测试文件。
- 新增测试（3 个文件，均在 `app/src/test/java/com/nbalive/app/ui/uifix/`）：
  - `UiFixPolicyTest.kt`：纯函数/纯解析判据（H7、U3、H9、U15、U17、U12/D15）
  - `UiFixWiringTest.kt`：接线与结构判据（H7、H8、H9、U6~U10、U12、U13、U16、U18~U21、U8、P16、P18、forceRefresh）
  - `UiFixInjectionTest.kt`：注入依赖驱动真实链路（U5、U16、P18、forceRefresh）
- 口径遵循 `review/opt/verification.md`：凡"机制已证实、幅度/概率需真机"的条目，只声称机制与接线，**不声称量化收益**。

## 1. 逐条台账

| 编号 | 状态 | 改动（文件:行） | 判据测试名 | 回退即红实验 |
|---|---|---|---|---|
| **H7(=U1)** 后台仍轮询 | 已修 | `ui/AppEffects.kt:78,105-117,199-209`（纯判据 + `repeatOnLifecycle(RESUMED)`）；KDoc 的"切后台自然停"假承诺一并改写（`:53-58`） | `UiFixPolicyTest.H7 只有 RESUMED 且停在列表页时才该 tick`；`UiFixWiringTest.H7 两个 ticker 必须用 repeatOnLifecycle 驱动` | 把 `repeatOnLifecycle` 换回裸 `LaunchedEffect(route, tab) { while(true)… }` → 接线判据红；把 `shouldRunTicker` 的状态项删掉 → 纯判据红 |
| **H8(=U2)** 每帧失效 | 已修 | `ui/components/Common.kt:611`（`staticCompositionLocalOf<() -> Float>` 稳定引用）、`:624-638`（`remember(t) { { a } }` + `provides phase`）、`:655-664`（`graphicsLayer { alpha ?: phase() }` draw 期读） | `UiFixWiringTest.H8 共享相位必须是稳定引用且在绘制期读` | 改回 `staticCompositionLocalOf { 1f }` + `provides a` → 稳定性断言红；把 `phase()` 挪到组合期（`alpha = alpha ?: phase()`）→ draw 期正则红 |
| **H9(=U4)** 空列表刷新无反馈 | 已修 | `ui/screens/MatchListScreen.kt:115,124,140,754-756,814-833`；`ui/screens/LiveNowScreen.kt:86,91,140-146,198-214` | `UiFixPolicyTest.H9 页内 0 场时刷新也必须给反馈`（+ 另两条：有旧列表挂细条／旧门槛自检）；`UiFixWiringTest.H9 两个列表页都必须用共享的刷新反馈判据` | 把 `shouldShowRefreshing` 的 `visibleCount` 门槛改回 `visibleCount > 0` → 纯判据红；把 `isRefreshing` 写回 `refreshing && matches.isNotEmpty()` → 接线判据红 |
| **U3** 直播页也轮询比分 | 已修 | `ui/AppEffects.kt:90-91,110`（`shouldRunScoresTicker` = … && `tab == MATCHES`） | `UiFixPolicyTest.U3 比分轮询只该在赛事页跑 直播页不渲染比分` | 把 `tab == MATCHES` 删回 `tab != PROFILE` → 「直播」页断言立即红 |
| **U5** 依赖不可注入 | 已修 | `ui/MainViewModel.kt:198`（`loadOutcome` 收 `(enabled, forceRefresh)`）、`:241`（`scoreboard`）、`:248`（`clock`）、`:255`（`wallClock`）、`:262`（`now`）、`:270`（`fetchRelease`）、`:281`（`releaseRepository`）；就地 `ScoreboardRepository()` 删除 | `UiFixInjectionTest.U5 注入的比分源与时钟能驱动 refreshScores`（+ 静默失败、`checkForUpdate` Available/UpToDate、fake 计数 4 条） | 把任一形参改回函数体内就地构造/直读时钟 → 对应用例**编译不过**；把 `scoreboard.load` 换成默认 ESPN 源 → 用例变真实网络并超时 |
| **U6** 三个 UiState 未标 `@Immutable` | 已修 | `ui/MainViewModel.kt:49,103,113` | `UiFixWiringTest.U6 三个 UiState 必须标 Immutable` | 删任一 `@Immutable` → 判据红 |
| **U7** 固定 dp 压 sp | 已修（口径：组合已证实；裁切幅度需真机/预览） | `ui/components/Common.kt:387,417,513`、`ui/screens/MatchListScreen.kt:508`（`height → heightIn(min = …)`，下限值与设计稿一致，正常字号像素级不变） | `UiFixWiringTest.U7 芯片高度必须用 heightIn min 而不是固定 dp`（内含"旧写法必须命中"的自检） | 任一 `.heightIn(min = X.dp)` 写回 `.height(X.dp)` → 判据红 |
| **U8** 启动主线程等磁盘 | 已修（best-effort，见 §3） | `ui/AppPrefs.kt:44,58`、`ui/PlayerPrefs.kt:66,76`、`ui/SourcePrefs.kt:38,48`（`attach = attachStore + init`）、`ui/PrefsStore.kt:142-147,171-207`（`PrefsPreload`）、`NbaLiveApplication.kt:24`（`warmUp`）、`MainActivity.kt:50-56`（注释） | `UiFixWiringTest.U8 三份偏好的首次读盘必须在后台预热` | 让 `attachStore` 里出现一次 `sp.contains(...)` → 判据红；删 `PrefsPreload.warmUp(` → 判据红；把 `executor.execute` 换成同步 `readAll()` → 断言红 |
| **U9** 无障碍动作词 | 已修 | `ui/screens/MatchListScreen.kt:188`（联赛胶囊）、`:550`（网格卡）；`ui/screens/SearchScreen.kt:180`（清空）、`:258`（搜索结果行）；`ui/screens/ProfileScreen.kt:215`（检查更新）、`:262`（下载） | `UiFixWiringTest.U9 screens 里的可点区域必须带 onClickLabel`（含裸/带标签两个自检样本） | 把任一改回 `.clickable(onClick = …)` → 判据红 |
| **U10** 每次重组新建 ImageRequest | 已修 | `ui/components/Common.kt:160,163`（`val context` + `remember(url, context)`） | `UiFixWiringTest.U10 TeamLogo 的 ImageRequest 必须 remember` | 把 `remember(url, context)` 拆掉、直接 `ImageRequest.Builder(context)` → 判据红 |
| **U12** 分层：检查更新下沉 | 已修 | **新文件** `data/remote/ReleaseRepository.kt`；`ui/MainViewModel.kt:270,281,630-645`（删 47 行 `jsonString`，改用仓库）；`fetchRelease` 默认值仍走 `HttpClient.shared.fetch(..., fastFail = true)` | `UiFixWiringTest.U12 检查更新的取值已下沉到 ReleaseRepository`；`UiFixPolicyTest.U12 ReleaseRepository 用 JsonCursor 取 tag 与 html_url`、`U12 D15 更长的 key 不能命中 缺 tag 视为查不到` | 把取值搬回 VM（重新出现 `fun jsonString(`）→ 接线判据红；`ReleaseRepository` 不用 `JsonCursor` → 断言红 |
| **U13** O(n) 扫描未 remember | 已修 | `ui/screens/MatchListScreen.kt:242-246` | `UiFixWiringTest.U13 MatchGrid 的扫描与过滤必须 remember` | 删 `remember(matches)` → 判据红 |
| **U14** `Decision.Available.notes` 零消费 | **主体归 fix-data**；VM 侧已配合 | fix-data：`ui/UpdateCheckPolicy.kt:32-37,109-119`（删字段与形参，已落地）；本侧：`ui/MainViewModel.kt:630-645`（不再解析/持有 release body，`decide` 三参调用） | （fix-data 的判据；本侧由 `U12` 的三条覆盖"不再解析 body"） | 在 VM 里重新解析 `body` 并传给 `decide` → `UiFixPolicyTest.U12…` 的样本语义与 `UiFixWiringTest.U12` 一起暴露 |
| **U15** "直播中"两份来源 | 已修 | `ui/StatusLabel.kt:32-52`（`liveLabel: String? = null` 覆盖参数 + `LIVE_TEXT` 常量）；`ui/components/Common.kt:447-461,501-506`（LIVE 也走 `StatusLabel.of`） | `UiFixPolicyTest.U15 StatusLabel 的 LIVE 分支可覆盖且默认走唯一来源`；`UiFixWiringTest.U15 StatusChip 的 LIVE 文案必须走 StatusLabel` | 把 StatusChip 改回 `if (LIVE) liveLabel else StatusLabel.of(...)` → 接线判据红（且 `AccessibilitySemanticsTest` 也仍绿，说明它拦不住这条） |
| **U16** 详情/播放页副本不老化 | 已修 | `ui/MainViewModel.kt:836,847-865`（`syncAgedMatchToDetailAndPlayer`，按 id 只替换已持有的那一场） | `UiFixInjectionTest.U16 老化必须同时更新详情页与播放页持有的 match 副本`（+ 没到窗口不乱改）；`UiFixWiringTest.U16 老化必须同步详情页与播放页持有的 match` | 删 `syncAgedMatchToDetailAndPlayer(aged)` 调用 → 注入用例红（`detailState.match.status` 停在 LIVE） |
| **U17** `else` 兜底静默取错字段 | 已修 | `ui/NavState.kt:123-129`（`"beforePlayer"` 显式分支 + `else -> error(field)`） | `UiFixPolicyTest.U17 未知的存档字段名必须当场报错 而不是静默取错字段` | 把 `else` 改回 `KEY_BEFORE_PLAYER` → 用例红（`navStateKeyFor("macth")` 不再抛错） |
| **U18** 根节点同订 `nav`+`route` | 已修（**取低风险形态**，见 §3） | `ui/AppUiState.kt:30-52,57-66`（只订 `nav`，`route` 从 `nav.route` 派生；VM 的 `route` StateFlow 保留，因为 5 个既有测试类在用它） | `UiFixWiringTest.U18 根作用域不再同时订阅 nav 与 route` | 把 `viewModel.route.collectAsStateWithLifecycle()` 加回 `rememberAppUiState` → 判据红 |
| **U19** `playerState` 被收集两次 | 已修 | `ui/MainViewModel.kt:500-512`（新窄状态 `playerTitle`，`distinctUntilChanged`）；`ui/AppChrome.kt:139-169`（顶栏读窄状态） | `UiFixWiringTest.U19 顶栏只读它要的窄状态` | 顶栏改回 `viewModel.playerState.collectAsStateWithLifecycle()` → 判据红 |
| **U20** 裸 `Log.w` | 已修 | `ui/MainViewModel.kt:1248`（→ 两参 `safeLogWarn`）；另按 Lead 追加纪律：`MainActivity.kt:141`（→ `safeLogWarn`，只拼 `javaClass.simpleName`，不透传 Throwable），并删 `import android.util.Log` | `UiFixWiringTest.U20 播放侧不再有裸 Log 调用`；Lead 的 `LogCallDisciplineTest`（新增守卫） | 把任一改回 `Log.w(...)` → 本判据 + `LogCallDisciplineTest` 同时红 |
| **U21** 首屏 loading 三页不一致 | 已修 | `ui/components/Common.kt:671-683`（共享 `LoadingState(text)`）；`ui/screens/MatchListScreen.kt:136`、`ui/screens/LiveNowScreen.kt:127`（消费；两页各自的私有实现已删） | `UiFixWiringTest.U21 首屏 loading 必须是共享组件` | 任一页面自带 `fun LoadingState()` → 判据红 |
| **P16**（跨 scope 调用点） | 已修 | `MainActivity.kt:49,258,311` → `PipState.setInPip(...)`（fix-player 的 `PipState.kt:29-30,87-89` 已只读 `inPip` + 新写入口） | `UiFixWiringTest.P16 PipState 的写入口只有一个`（含"恰好 3 处"计数） | 任一处写回 `PipState.inPip.value = …` → 判据红（且主源集编译不过） |
| **P18**（Lead 追加：恢复条上报） | 已修 | `ui/MainViewModel.kt:1374-1391`（`onDeadEndSuspectOnly()`：只 `markPlaybackSuspect(true)`，**不含** `advanceAfterNoSignal`；KDoc 写明为何不能复用 `onPlaybackSuspect`）；`ui/NbaLiveApp.kt:380`（`onDeadEndSuspect = viewModel::onDeadEndSuspectOnly`） | `UiFixInjectionTest.P18 恢复条上报只置疑似标记 绝不换线`；`UiFixWiringTest.P18 恢复条上报必须只置标记 不换线` | 让 `onDeadEndSuspectOnly` 调 `advanceAfterNoSignal(...)` → 两条判据同时红（注入用例会看到 `selectedIndex` 被换到 1） |
| **forceRefresh**（Lead 追加：穿透 30s 短缓存） | 已修 | `ui/MainViewModel.kt:198`（`loadOutcome` 类型扩为 `(Set<Provider>, Boolean)`）、`:703`（冷启动 `false`）、`:715`（`refresh()` = 用户主动 → `true`）、`:723`（`refreshWith`）、`:767`（把 `forceRefresh` 传进 `loadOutcome`） | `UiFixInjectionTest.冷启动不强制刷新 用户主动刷新必须强制`；`UiFixWiringTest.用户主动刷新必须穿透 30 秒列表短缓存` | 忽略第二个实参（`loadOutcome(enabled)`）→ 注入用例红（`forces == [false]`）；照旧只收 `enabled` → 接线判据红 |

## 2. 判据的可失败性（"不能失败"的自检）

每条结构判据都自带"违规样本必须命中"的自检，避免本仓库反复出现的"守卫永远绿"：

- `U7`：`Regex("""\.height\(\s*20\.dp\s*\)""")` 必须命中内存样本 `modifier.height(20.dp)`。
- `U9`：裸样本 `modifier.clickable(onClick = onClick)` 必须被判违规；带 `onClickLabel` 的样本必须放过。
- `H9`：`UiFixPolicyTest.H9 旧门槛在 0 场时确实为假` 用**旧实现的等价函数**证明新旧判据给出相反答案。
- `H8`/`U8`/`U12`/`U18`/`U19`：全部是"删掉/改回旧写法就变红"的正向断言（见上表最后一列）。
- `UiFixWiringTest.扫描必须真的扫到生产源码`：≥40 个生产 kt + 三个锚点文件，防"扫不到所以全绿"。

## 3. 有据的取舍与偏差（与 lead/审查口径一致）

1. **U8 = "预热 + 未就绪时同步兜底"，不是"未就绪先用默认值"。**
   若让主线程直接返回默认值，浅色用户冷启动会先按 `darkPref = null`（→深色）渲染一帧再跳变（`resolveDarkMode` 的口径），属用户可见回归。现在的形态：`NbaLiveApplication.onCreate`（早于 `MainActivity.onCreate`）把三份 SP 的**首次数据访问**丢到 daemon 单线程，`AndroidPrefsStore.init()` 正常路径零磁盘 IO；竞态没赶上时退回改动前的同步读（宁可偶尔慢一次，也不闪主题）。
   **验证法（需真机）**：`adb shell am start -W` 看冷启动 `TotalTime`；debug 版开 StrictMode 看 `DiskReadViolation` 是否仍指向 `AppPrefs.attachStore` 之后的 `init()`。
2. **U18 取低风险形态**：只让根作用域不再**同时订阅** `route` 与 `nav`（`AppUiState.route` 派生自 `nav.route`），VM 的 `route` StateFlow 保留 —— `PlayerBackStackTest`/`MainViewModelTest`/`NavRestoreTest`/`SniffRecoveryTest` 共 5 个既有测试类在断言 `vm.route.value`，删镜像要改既有测试（本轮纪律不允许）。守卫侧同步成立：`AppStructureGuardTest` 要求根节点直接收集 ≤1（当前 1，`PipState.inPip`）、`AppUiState` 字段 ≤6（当前 4）、`rememberAppUiState` 收集数 ≤ 字段数（4 ≤ 4）。
3. **U7 的收益口径**：固定 dp + 可缩放 sp 的**组合**已证实；是否真裁切、裁多少**需真机/预览**（`fontScale 1.3/1.5/2.0` 截图）—— 本轮只改形状，未做真机验证。
4. **H7/H8 的收益口径**：机制已证实（`LaunchedEffect` 非 lifecycle-aware；`staticCompositionLocalOf` 值变 ⇒ 提供者内整棵子树失效），"后台是否真每 45 秒发请求""掉帧多少"需真机抓包/Profiler。

## 4. 未完成与原因

| 项 | 状态 | 原因 / 归属 |
|---|---|---|
| **H5 / U11**（`AppStructureGuardTest` 收集数守卫重写） | 不在本任务清单；**已由他人完成**（守卫现为"根体内收集 ≤1 + 必走 `rememberAppUiState` + `AppUiState` 字段 ≤6"，见 `AppStructureGuardTest.kt:106-151`） | 测试文件不归我；本轮我的改动需与之兼容 —— 已核过：4 字段 / 4 收集 / 根内 1 处 |
| **U14 主体**（删 `notes` 字段与形参） | 由 fix-data 落地 | `ui/UpdateCheckPolicy.kt` 不在我的允许清单且当时正被 D17 修改；Lead 裁决归 fix-data。VM 侧已删 `notes = ""`，`decide` 三参调用对齐（已核对：`NewFeaturesTest.kt:65-83` 也已同步为 3 参） |
| **U9 的播放链路裸 `clickable`** | 未修（**已知缺口**） | `ui/screens/PlayerScreen.kt` 里仍有 8 处裸 `.clickable(`（1162/1248/1286/1332/1397/1480/1516/1541），归 fix-player 且不在我的写清单。新判据把 `PlayerScreen.kt`/`PlayerGestureLayer.kt` 写死为**显式排除**（新增文件不会被静默放过）。下一轮建议同一守卫扩展到播放链路 |
| **`EspnScoreboard.JsonCursor` 的 KDoc** | 未改（他人文件） | `data/remote/EspnScoreboard.kt:93-96` 仍写"UI 侧（`MainViewModel.jsonString`）复用它" —— 该消费者已删除，现在的复用点是 `ReleaseRepository`。`data/**` 不在我的写清单，需 fix-data 改这一句（已在给 Lead 的摘要里登记） |
| **D15 的处置** | 已随 U12 消解 | 全仓只剩 `EspnScoreboard.JsonCursor` 一套 JSON 取值器；VM 的 `jsonString`（缺前边界校验的那一份）已删，它的 GitHub 取值点由 `ReleaseRepository` 复用 `JsonCursor` 承担（更长的 key 不再能命中，见 `UiFixPolicyTest.U12 D15…`）。fix-data 那一半（`JsonCursor` 自身）无需再加 |
| **收益量化（H7/H8/U6/U7/U8/U10/U13）** | 未测 | 需真机：`adb logcat -s Scores` 后台计数 + 抓包（H7）、Layout Inspector recomposition/skip（H8/U6/U10/U13）、fontScale 截图（U7）、`am start -W` + StrictMode（U8）。本轮结论按 `verification.md` 的口径只到"机制/接线已证实" |

## 5. 对既有测试的影响（Lead 编译时请对照）

- 预期**不受影响**（已逐条对照守卫源码，未改这些文件/形态）：
  - `NewFeaturesWiringTest`：`MainViewModel.kt` 仍含 `fun checkForUpdate(`、`REPO_OWNER`/`REPO_NAME`、`UpdateCheckPolicy.decide(`、`HttpClient.shared.fetch(`（在 `fetchRelease` 默认值里）。
  - `LiveDotAnimationTest` / `AccessibilitySemanticsTest` / `ComponentPerfTest` / `ScreenConsistencyGuardTest` / `CardButtonSemanticsTest` / `LiveNowCardSemanticsTest` / `ZeroConsumerGuardTest` / `PrefsBoundaryGuardTest` / `NavHostGuardTest` / `SourceToggleTest` / `DeadCodeWiringGuardTest`：相关形态全部保留（`alpha: Float?` 形参、`liveLabel: String?` 形参、`if (status == LIVE) { liveLabel`、`LocalLiveDotAlpha` + `fun LiveDotPhase`、`model = remember(` 且无局部 `val x = … {`、`PrefsStore.kt` 的 2 处落盘实现、`SourcePrefs.fun init()`…）。
- 唯一需要一起看的跨文件项：`LogCallDisciplineTest`（Lead 新增）覆盖 `MainActivity.kt:141`，我已改为两参 `safeLogWarn`。
- 若编译报错集中在 `MainViewModel`，优先查两处：`releaseRepository` 的默认值引用了前一个形参 `fetchRelease`（Kotlin 允许，但若编译器版本不认可，改成在 `init` 里赋值即可）；`playerTitle` 的 `stateIn(viewModelScope, Eagerly, null)` 位于 `_playerState` 之后。

## 6. 测试调试（2026-11-01，Lead 跑 `:app:testDebugUnitTest` 833 用例 / 5 失败）

### 6.1 `UiFixWiringTest > U20 播放侧不再有裸 Log 调用` —— **测试错，生产没错**

- 现象：`AssertionError: 原来那一处必须改成 safeLogWarn`（我的第二条断言）。
- 根因：我的断言写成了 `vm.contains("safeLogWarn(\"MainViewModel\"")`，**钉的是字符串字面量**；
  而 `src()` 走的是本仓铁律①的 `GuardSourceScan.stripNonCode`（**连字符串字面量一起剔**），
  判据输入里 `"MainViewModel"` 已经不存在 → 生产完全正确却误红。
  （第一版注释里我甚至写了"字符串会被剔掉"，却仍踩了同一个坑 —— 判据自身也要遵守铁律①。）
- 修法（[UiFixWiringTest.kt](app/src/test/java/com/nbalive/app/ui/uifix/UiFixWiringTest.kt:294)）：
  - 保留 `assertFalse(裸 Log. 正则)`（这一条本来就过）；
  - 正向断言改成**结构判据**：`body(vm, "fun onStreamSniffed(")` 里必须出现 `safeLogWarn(` 调用形状
    —— 不判定文案/字面量，抗文案重构；也不与 Lead 新增的 `LogCallDisciplineTest`
    （扫 `ui/**` + `MainActivity.kt` 的裸 `Log.`）重复：那条管"不许裸调"，这条管"原来那一处确实被改了"。
- 复核：`MainViewModel.kt:1248` 的 `safeLogWarn(` 在 `onStreamSniffed` 体内（已 `read` 确认），修后判据为真。

### 6.2 顺带修掉的编译错（Lead 已代改，**不再改回**）

- `UiFixInjectionTest.kt:288-289`：`Provider.JRKS` 不存在（枚举只有 `JRS`/`JRKAN`）→ 已是 `Provider.JRS`。
  （根因：我改了 import 却没改用例里的两处引用。）
- `UiFixWiringTest.kt:5`：补 `import org.junit.Assert.assertEquals`（P16 用例用到 `assertEquals` 计数却漏了 import）。
- 纪律：这两处是**编译级**错误，说明我本地没跑编译时的手工核对不够 —— 已在 §7 记下"提交前自查清单"。

### 6.3 同类隐患自查（防第二次误红）

把 `UiFixWiringTest` 里全部 `contains(...)` 断言过了一遍，除已修的 U20 外，
其余断言的正向实参都是**纯代码标识/调用形状**（`repeatOnLifecycle(`、`shouldShowRefreshing(`、
`provides phase`、`attachStore(`、`PrefsPreload.isFinished`、`releaseRepository.latest(`、
`EspnScoreboard.JsonCursor(`、`syncAgedMatchToDetailAndPlayer(`、`viewModel.playerTitle`、
`LoadingState(text =`、`liveLabel =`、`markPlaybackSuspect(true)`、`PipState.setInPip(`…），
不会因 `stripNonCode` 剔字符串而失效。

## 7. 提交前自查清单（本轮新增，供后续同类改动复用）

1. 判据读的是 `stripNonCode` 后的文本时，**禁止**用生产源码里的字符串字面量（含日志文案、`onClickLabel` 的值）做正向断言；正向一律钉**调用/声明形状**。
2. 新增测试至少手工核对三类编译依赖：`import` 是否齐全（尤其 `assertEquals`/`assertNull`）、枚举常量名是否真实存在（`Provider.JRS`，不是 `JRKS`）、构造函数实参个数/名称。
3. 新增构造形参后，逐一核对既有测试的构造调用点与"注入 lambda 的形参个数"（`loadOutcome` 从 1 参扩到 2 参时已 grep 确认无既有调用点）。