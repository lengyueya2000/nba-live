# 既有守卫测试收口报告（task-10 / fix-data）

- **目标**：fix-data（task-6）与 fix-player（task-7）删改了一批生产符号后，既有守卫测试里针对这些符号的断言会**红**、或退化成**空转**（还在跑但已经测不到东西）。逐符号收口。
- **写入范围**：只改 `app/src/test/**` 的**既有**文件；**未碰** `app/src/test/java/com/nbalive/app/data/**` 与 `ui/playerfix/**`、`ui/uifix/**`（分别是 fix-data / fix-player / fix-ui-state 的新判据）。
- **未改任何生产代码**；**未运行 Gradle/adb**（Lead 统一构建）。已做词法定界符自查（8 个被改文件全部平衡）。

## 0. 结论速览

| # | 符号 | 结论 | 动过的文件 |
|---|---|---|---|
| 1 | `SourceOutcome.enabled`（D16 删） | **无需改**：全测试树零引用（唯一提及是 `ListNoticePolicyTest` 的 KDoc 文字） | — |
| 2 | `Decision.Available.notes`（U14 删） | **无需改**：task-6 已同步 `NewFeaturesTest` 的 4 处 `decide(...)` 实参；现全树零引用 | — |
| 3 | `GameScore.startAtMillis` / `parseIso8601`（D14 删） | **无需改**：task-6 已删 `EspnScoreboardTest.ISO 时间解析`、8 处位置参数去掉 `, 0L`；现全树零引用 | — |
| 4 | `PipAspectReset.shouldResetOnMatchSwitch()` 无参版 → 带参 | **已收口（断言由弱变强）** | `ManualPipGuardTest.kt`、`DeadCodeWiringGuardTest.kt` |
| 5 | `PipState.inPip` 可变 → 只读 + `setInPip` | **无需改**：既有测试只**读**（`AppStructureGuardTest` 的自检样本）；写入点只在 `MainActivity`（由 `uifix` 新守卫钉住） | — |
| 6 | `PlayerScreen` 写死的 `hasStream` / `update` 里的 `setMediaItem` 死分支 | **无需改**：既有测试只用**策略参数**（`WebPageDeadEndPolicyTest` / `SniffRecoveryPolicyTest` / `PlayerLoadingHintPolicyTest` / `ManualPipGuardTest`），没有任何既有守卫扫生产源码里的这两处 | — |
| 7 | `PlaybackHealth.isMediaRequest` 旧语义（新增 `hasStreamNow`） | **无需改**：`isMediaRequest` 仍在（`PlaybackHealth.kt:148`），`PlaybackHealthTest` 4 条断言仍成立；新判据 `hasStreamNow` 由 `playerfix/PlayerFixTest` 覆盖 | — |
| 8 | `MatchRepository` 的 `merge`/`normalizeKey`/`betterStatus`（D10 抽到 `MatchMerger`） | **无需改断言**：`mergeForTest` 签名保留且被 3 个既有族仍在使用；反射锚点 `detailCache`/`sort` 也保留。只修正了 3 处**已失真的注释/KDoc** | `MatchRepositoryTest.kt`、`SourceTimeZoneTest.kt`、`MergeStatusTest.kt` |
| 9 | `ui.TeamNameBridge` 转发件（D11） | **已收口**：测试改成显式指向 `data.model.TeamNameBridge`（别名为 `DataTeamNameBridge`） | `NewFeaturesTest.kt` |
| B13 | 原样字符串里的 `${…}` 算代码 | **已补自检**（防能力被回退） | `GuardHygieneTest.kt` |
| B6/B7 | `ci.yml` + `CiWorkflowGuardTest` | **确认无冲突**（该文件与我的符号零交集，未改动） | — |

**判据：没有任何既有守卫因这批生产改动而红。** 唯一"会红"的入口在 task-6 已随生产同步修好（见第 1~3 行）。

## 1. 逐条：改前 → 改后 → 为什么等价或更强

### 4. `PipAspectReset.shouldResetOnMatchSwitch`（P17）

生产已从 `fun shouldResetOnMatchSwitch(): Boolean = true` 改成
`fun shouldResetOnMatchSwitch(hasRecordedVideoSize: Boolean = true): Boolean = hasRecordedVideoSize`
（`ManualPipPolicy.kt:82`），调用点传 `PipState.videoSize.value.width > 0`（`PlayerScreen.kt:1961-1962`）。
默认值让**无参调用仍能编译**，所以旧断言不会红 —— 但它**变成了空转**：只测默认值，实现把参数忽略掉也照样绿。

| 位置 | 改前断言 | 改后断言 | 为什么更强 |
|---|---|---|---|
| `ManualPipGuardTest.kt:89-115`（`切场后小窗比例要复位 不能沿用上一场的宽高`） | `assertTrue(PipAspectReset.shouldResetOnMatchSwitch())` —— 只钉住默认值 `true` | 三条：`(hasRecordedVideoSize = true) == true`、`(hasRecordedVideoSize = false) == false`、`() == true`（默认分支） | 旧断言在"参数被忽略、恒返回 true"的实现下**也为真**；新断言把 `false` 分支钉住，恒真实现立刻红。默认分支单独保留 = 同时守住 01-player.md P2-5 的"拿不到信息时保守复位"语义。 |
| `DeadCodeWiringGuardTest.kt:510`（`pipOk` 自检样本） | 样本里写 `shouldResetOnMatchSwitch()` | 样本改成 `shouldResetOnMatchSwitch(hasRecordedVideoSize = true)` | 自检样本必须**镜像生产调用形状**，否则它证明的是"旧形状能被抓到"，而生产的带参形状没被证明过。判据本身（`callSites(videoBody, "PipAspectReset.shouldResetOnMatchSwitch") >= 1`）按前缀匹配，两种形状都能命中 —— 改样本是"让证明覆盖当前形状"，是加强不是放宽。 |
| `DeadCodeWiringGuardTest.kt:537`（`pipMutant` 变异样本） | 同上（无参） | 同上（带参） | 同上；并且变异体的"合规部分"与生产一致后，它被否决的原因仍然**只**是"判据被搬出了 PlayerScreen/VideoSurface"，判据的判别力没被稀释。 |
| `DeadCodeWiringGuardTest.kt:210`（失败文案） | 文案写 `` `PipAspectReset.shouldResetOnMatchSwitch()` `` | 文案写 `` `PipAspectReset.shouldResetOnMatchSwitch(…)` `` | 纯文案：避免下一个人照文案把生产改回无参形状。不影响判据。 |

### 9. `ui.TeamNameBridge` 转发件（D11）

| 位置 | 改前 | 改后 | 为什么等价或更强 |
|---|---|---|---|
| `NewFeaturesTest.kt`（`TeamNameBridgeTest`，17 处调用） | 未限定的 `TeamNameBridge.*` —— 因该文件在 `com.nbalive.app.ui` 包内，**隐式解析到 ui 转发件** | 新增 `import com.nbalive.app.data.model.TeamNameBridge as DataTeamNameBridge`，17 处调用改成 `DataTeamNameBridge.*` | 转发件与实现体是 1:1 委托，**行为完全等价**（断言、夹具、期望值一字未改）。更强的点：现在钉的是实现体本身，转发件哪天被删（`MatchListScreen.kt:66` 改完后）本文件不再受牵连。⚠️ 用 `as` 起别名而不是直接同名 import：既避免与同包同名 object 的解析歧义（无法本地编译验证，取零风险方案），又让引用意图显式。 |
| `NewFeaturesTest.kt:433`（`NewFeaturesWiringTest.比分必须接到卡片上`） | `card.contains("TeamNameBridge.")`（断言 `MatchListScreen.kt` 里真的画了比分） | **不动** | 这条断言的对象是 `MatchListScreen.kt`（生产，归 fix-ui-state），必须保持"简单名 `TeamNameBridge.`"的形态 —— 见"仍待观察"第 1 条。 |

### B13. 原样字符串里的 `${…}` 必须算代码

| 位置 | 改前 | 改后 | 为什么更强 |
|---|---|---|---|
| `GuardHygieneTest.kt:147-189`（新增 `原样字符串里的模板插值算代码 其中的调用必须被数到`） | 无这条判据（B13 修好之后**没有任何测试**守住它 —— 能力可以再次被回退而全绿） | 三个样本喂给**真正参与扫描的** `GuardSourceScan.callSites`：(a) `"val s = \"\"\"${'$'}{a.b()}\"\"\""` → `a.b` 计 1；(b) 普通字符串模板 → 同样计 1；(c) `"val s = \"\"\"a.b() 只在字符串里\"\"\""` → 计 0 | (a)(b) 是两个独立分支（`appendStripped` 的原样字符串分支与普通字符串分支都各自处理 `${…}`），只修一半也能被这条钉住；(c) 是**反向**约束：证明"插值算代码"没有把"字符串内容也要剔掉"这条一起放宽 —— 少了它，一个"整段不剔"的坏实现也能让 (a)(b) 为真。 |

### 8. `MatchRepository` 抽走 `merge` / `normalizeKey` / `betterStatus`

**断言层不需要改**（判据全在 `mergeForTest` 上，签名刻意保留）：`MatchRepositoryTest`、`MergeStatusTest`、`MergeKeyFallbackTest` 三族仍通过它驱动真实合并逻辑；`MatchRepositoryTest:98` 反射的 `sort`、`DetailCacheTtlTest`/`MatchRepositoryRetryTest` 反射的 `detailCache` 都还在 `MatchRepository` 里（抽 `MatchJrsDetailCache` 会打红这些既有测试，这正是 task-6 只落地 `MatchMerger` 的原因）。

只修正了 3 处**已失真的说明文字**（不改任何断言）：

| 位置 | 改前 | 改后 |
|---|---|---|
| `MatchRepositoryTest.kt:58-64`（KDoc） | "merge 的返回类型已改成带过滤计数的 `Merged`…" | 明确"合并逻辑已抽到 `MatchMerger`；`mergeForTest` 是刻意保留的入口；`MatchMerger.Merged`" |
| `SourceTimeZoneTest.kt:22` | `` - `MatchRepository.normalizeKey`（跨源合并键） `` | `` - `MatchMerger.normalizeKey`（…D10 从 `MatchRepository` 抽到合并器） `` |
| `MergeStatusTest.kt:84` | "：`normalizeKey` 不同 ⇒ …" | "：`MatchMerger.normalizeKey` 不同 ⇒ …" |

### H3/D1：`LoadDeadlineTest` 描述的语义已从"整体 deadline"变成"每源 deadline"

断言**全部保留**（两条用例在新实现下仍成立：两源都无响应 → 注入的 deadline 内返回、空列表 + 两源失败；原因是两个源各自 300ms 并发起算，总耗时仍 ≈ 300ms）。改的是会把读者带偏的说明：

| 位置 | 改前 | 改后 |
|---|---|---|
| `LoadDeadlineTest.kt:9-43`（类 KDoc） | "列表加载的**整体 deadline**" / "整体必须在 deadline 内结束，超时返回部分结果" | 明确 deadline 是**每源一份**（`sourceWithDeadline`），旧的整体实现是 H3/D1 的丢数据缺陷；并注明"单源超时保留另一源"这条新判据在 `ListLoadFixesTest`（task-6 新文件），本文件只管"两源都超时" |
| `LoadDeadlineTest.kt:34-36` | 硬编码行号 `MatchRepository.kt:586,595`（已漂移） | 改为符号引用 `MatchRepository.loadMatchesWithOutcome` / `sourceWithDeadline`，并注明"不再写行号，行号漂移过一次" |
| 用例 1 的失败文案 | "整体必须在**注入的 300ms** deadline 内结束" | "每个源都必须在**注入的 300ms** deadline 内结束"（断言仍是 `elapsedMs in 250..1500`） |

### 1~3、5~7：确认"无需改"的依据（逐条 grep 证据）

- `SourceOutcome.enabled`：全测试树仅 `ListNoticePolicyTest:14` 的 KDoc 文字提到 `SourceOutcome`（不是该属性），零编译期引用。
- `Decision.Available.notes` / `.notes`：零引用（task-6 已改 `NewFeaturesTest` 的 4 处 `decide` 实参）。
- `parseIso8601` / `startAtMillis = 0L`：零引用（`GameScore(...)` 现全为 7 参）。
- `PipState.inPip`：既有测试只有 `AppStructureGuardTest:158` 的**自检样本** `val inPip by PipState.inPip.collectAsStateWithLifecycle()` —— 只读用法在 `asStateFlow()` 下完全成立（且它正是生产 `NbaLiveApp.kt:67` 的形状）；`PipState.inPip.value =` 的写点只在 `MainActivity`，由 `uifix/UiFixWiringTest` 与 `playerfix/PlayerFixTest` 两个新守卫钉住。
- `PlayerScreen` 的 `hasStream`/`setMediaItem`：既有测试对 `hasStream` 只作为**策略参数**传入（`ManualPipPolicy.decide` / `WebPageDeadEndPolicy.shouldOffer|shouldAutoAdvance` / `SniffRecoveryPolicy` / `PlayerLoadingHintPolicy`），无源码扫描；`setMediaItem` 零既有引用。生产现状已核对：`val hasStream = false` 已消失，`setMediaItem` 只剩工厂一处（`PlayerScreen.kt:2057`）。
- `isMediaRequest`：仍在（`PlaybackHealth.kt:148`），`PlaybackHealthTest:226-269` 四条断言语义未变；`hasStreamNow` 由 `playerfix/PlayerFixTest`（新）覆盖。

### 顺手复核（未改动，只记录结论）

- `ZeroConsumerGuardTest` 的 9 条 `WIRINGS` 与 3 条 `BANNED` 逐条对过当前生产：全部仍有真实调用点（`PipAspectReset.ratioFor(` → `MainActivity.kt:192`、`MissingMatchPolicy.forPlayer(` → `NbaLiveApp.kt:307`、`showsSuspectHint(` → `:385`、`pipDisposition(` → `MainActivity.kt:294`、`shouldResetOnMatchSwitch(` → `PlayerScreen.kt:1961`、`shouldAutoEnterPip(` → `ManualPipPolicy.kt:42` + `AppEffects.kt:150`、`CardSemantics.ACTION_LABEL` → 3 处），**没有一条空转**。
- `WebPageDeadEndWiringTest` 的锚点全在：`WebSurface`（`PlayerScreen.kt:2137` 起）体内有 `WebErrorProbeScript.HAS_ERROR`(`:2334`)、`WebPageDeadEndPolicy.shouldOffer(`(`:2378`)、`reloadLabel()`(`:2700`)、`switchLabel()`(`:2715`)；`NbaLiveApp.kt:375` 仍是 `onPageDeadEnd = viewModel::onPageDeadEnd,`；`MainViewModel.onPageDeadEnd`（`:1359-1367`）体内有 `advanceAfterNoSignal(` + `WebPageDeadEndPolicy.advanceNote()`（`:1366`）。
- `DeadCodeWiringGuardTest` 其它锚点（`delay(FIRST_FRAME_WATCHDOG_MS)`、`key(webFallbackUrl)`、`belongsToPage`、`SourceFailureBook`/`isSourceFailed`、`shouldShowAspectChip`、`backPolicyViolations`、`detailRouteViolations`、`systemBarsViolations`、pause/resume 脚本）逐条对照当前生产，全部命中。
- `GuardHygieneTest` 的两条元守卫（禁 `assume` 家族、禁 Kotlin 原生 `assert(`）在新加的 B13 用例上仍然通过（新代码只用 JUnit 断言）。

## 2. 仍会红 / 仍待观察

1. **`NewFeaturesWiringTest.比分必须接到卡片上`（`NewFeaturesTest.kt:432-433`）是"跨 scope 的软肋"**：它断言 `MatchListScreen.kt` 的**代码**里含 `"TeamNameBridge."`。当前通过（生产用简单名 `TeamNameBridge.displayableScore(...)`）。**若 fix-ui-state 把它改成全限定写法**（`com.nbalive.app.data.model.TeamNameBridge.displayableScore(...)`）或改用局部别名，这条会红。建议：改动 `MatchListScreen.kt` 时保持"import + 简单名调用"的形态；若确实要全限定，请把该断言改成 `contains("TeamNameBridge")`（去掉点号）——但那样会放宽到能命中 import 行，所以更好的做法是断言 `contains("TeamNameBridge.displayableScore(")`。
2. **`ZeroConsumerGuardTest` 对 `shouldResetOnMatchSwitch` 的守卫只要求"≥1 个调用点"**，不校验调用真的把 `hasRecordedVideoSize` 传进去。该参数目前由 `playerfix/PlayerFixTest:689-693` 的新守卫钉住（要求 `hasRecordedVideoSize = PipState.videoSize.value.width > 0`）。task-10 未动 `ZeroConsumerGuardTest` 的逻辑（它是"接线"登记表，语义正确），在此点名这条分工。
3. **`LoadDeadlineTest` 的用例名 `源站整体无响应时 在 deadline 内返回而不是无限等` 保留未改**：它描述的是"两个源都不响应"这一事实，仍准确；改名会牵动 Lead 的构建输出比对，收益不足。语义变化已写进类 KDoc。
4. **本任务无法验证编译**（铁律：不跑 Gradle）。三处需要特别留意的编译面：`NewFeaturesTest` 的新 import 别名（同包同名 object 的歧义风险已用 `as` 规避）、`GuardHygieneTest` 的 `${'$'}` 转义写法、`LoadDeadlineTest` 只改了字符串与注释。若构建报出与这三处有关的错，请把错误贴回，我立刻修。