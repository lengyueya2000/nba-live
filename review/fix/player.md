# 播放链路修复报告（task-7 / fix-player）

- **范围**：H6(=P1) + P2~P18 + Lead 追加的 lint 项（`AutoboxingStateCreation`）。
- **方法与诚实边界**：全程只读/只改代码，**未跑 Gradle / adb / 构建 / 安装**（铁律 3）。
  因此"编译通过"与"测试全绿"都**不是**本报告能给出的结论；我做的是：
  1) 逐处 `read` 复核改动区的 import 与语法；2) 用词法扫描器（忽略注释/字符串/`${}`/原样字符串）
     对 10 个改动文件做括号配对校验（全部 `parenDepth=0 braceDepth=0`）；
  3) 把每条的判据抽成纯函数/纯状态类，放进**新建**的
     `app/src/test/java/com/nbalive/app/ui/playerfix/PlayerFixTest.kt`（既有测试文件一个都没动）。
- **既有守卫的锚点被刻意保留**（否则会因重构变红）：`fun PlayerScreen(` 内的
  `ManualPipPolicy.decide`、`PlayerLoadingHintPolicy.shouldShow`；`fun VideoSurface(` 内的
  `PipAspectReset.shouldResetOnMatchSwitch`；`fun WebSurface(` 内的 PAUSE_ALL/RESUME_PAUSED、
  `WebSniffWatchdog.shouldReport` 四个具名实参、`SystemClock.elapsedRealtime()`、
  `WebPageDeadEndPolicy.shouldOffer/reloadLabel/switchLabel`、`key(webFallbackUrl)`。

## 1. 逐条落地表

| 编号 | 状态 | 改动文件:行 | 判据测试名（`PlayerFixTest`） | 回退即红实验 |
|---|---|---|---|---|
| **lint（追加）** | 已修 | `PlayerScreen.kt:278`（`controlsTouchSeq → mutableIntStateOf(0)`）、`:482`（`sinceLoadMs → mutableLongStateOf(0L)`） | `P14 顺带修掉 lint 的 AutoboxingStateCreation` | 把 `mutableIntStateOf(0)` 改回 `mutableStateOf(0)` → 该测试红 |
| **H6(=P1)** | 已落地 | `WebPageDeadEndPolicy.kt:152`（新纯函数 `targetOnReload`）；`PlayerScreen.kt:2779`（onClick 不再 `reload()`）、`:2300`（`reloadSeq` 代次）、`:2304`（`navigationTag`）、`:2305`（捷径 effect 键含代次） | `H6 重新加载的导航目标必须是原始壳页 而不是当前 paps 地址`、`H6 生产代码不再 reload 当前地址 且回到壳页的重解析真的接线` | 把 `targetUrl = targetOnReload(url, targetUrl); reloadSeq++` 换回 `webView?.reload()` → 两条测试红（`webView?.reload()` 命中 + 调用点计数 0） |
| **P2** | 已落地（复核修正版） | `PlaybackHealth.kt:185`（`hasStreamNow` + `MEDIA_REQUEST_STREAM_WINDOW_MS`）；`PlayerScreen.kt:2419`（探测间隔里的"在出流"）、`:2438`（`shouldOffer/shouldAutoAdvance` 的实参） | `P2 hasStreamNow 只在窗口内还有新媒体请求时算在出流`、`P2 目标场景 页面发过媒体请求但那条 404 时出口仍必须出现`、`P2 生产代码不许再把 hasStream 写成常量` | 改回 `val hasStream = false` → 第 3 条红；改成 `sniff.sawMediaRequest()` → 第 2 条红（目标场景的出口被关掉） |
| **P3** | 已落地（复核修正版：只补轮询路径） | `AudioFocusPolicy.kt:174`（`shouldResumeForAudioMode`，委托 `shouldResumeOnFocusGain`）；`PlayerScreen.kt:1928`（轮询恢复分支）、`:1946`（重投改为"播放中 / 被焦点暂停"）、`:1829`+`onIsPlayingChanged`（`FocusPollRearm` 开关与接回） | `P3 通话结束后轮询路径必须自己恢复播放`、`P3 轮询必须消费恢复判据 且重投不再是无条件` | 删掉轮询里的恢复分支 → 第 2 条红；把重投改回无条件 → 第 2 条红（`player.playWhenReady \|\| pausedByFocus` 找不到） |
| **P4** | 已落地 | `WebSniffWatchdog.kt:155/185`（`WebSniffState.onNavigationStarted`）；`PlayerScreen.kt:2266`（`sniff`）、`:2283`（`beginNavigation`）、`:2782`（reload）、`:2316`（壳页→paps）、`:2327`（paps→原页） | `P4 导航之后 sawMediaRequest 必须归零 否则看门狗永久失效`、`P4 P5 P10 生产代码必须走 WebSniffState 且每次导航都复位` | 让 `onNavigationStarted()` 只加代次不复位 → 第 1 条红；把 `set(false)` 那类散写搬走 → 第 2 条红 |
| **P5** | 已落地（按复核后的口径） | `PlayerScreen.kt:2273`（`pageFinishedAt` 改为可变状态）、`:2283-2286`（导航时归零）、`WebSniffWatchdog.kt:185`（`loadStartedAt` 随导航重置） | `P5 P10 导航代次变化时加载基准必须重置` | 把 `beginNavigation()` 里的 `sniff.onNavigationStarted()` 去掉 → 测试红（新基准不再更新，30100 判定反转） |
| **P6** | 已落地 | `SystemBarsPolicy.kt:91`（第 4 个入参 `panelWasOpen`，带默认值保旧语义）；`PlayerScreen.kt:339-361`（两个 effect 合并为一个显式排序的 effect） | `P6 进全屏收起浮层不能被面板回补抵消`、`P6 默认参数保持 P2-8 旧语义 且生产合并成一个显式排序的 effect` | 把 `panelWasOpen = false` 分支的判断去掉（回到只看当前状态）→ 第 1 条红；拆回两个 `LaunchedEffect` → 第 2 条红 |
| **P7** | 部分落地（失效面已收窄，ticker 位置保留） | `PlayerScreen.kt:497-507`（判据下沉 `derivedStateOf`）；`PlayerOtherLiveSection` 内 `:1161` `remember(otherMatches, currentMatch)`；`PlayerPickerPanel` 内 `:1273/:1276` `remember(signals, selectedIndex)`/`remember(signals)` | `P7 派生列表必须 remember 提示判据必须下沉到 derivedStateOf` | 把 `derivedStateOf` 去掉（直接 `if (shouldShow(...))`）→ 测试红；去掉两处 `remember` → 测试红 |
| **P8** | 已落地 5 块（原函数体 **915 → 670 行**；U9 + P1 回归后 **725**） | `PlayerScreen.kt`：`PlayerMatchCard:938`、`PlayerSignalStrip:981`、`PlayerNoticeCards:1074`、`PlayerOtherLiveSection:1154`、`BoxScope.PlayerPickerPanel:1209`；调用点 `:841/847/857/861` | `P8 播放页函数体必须被拆小 且拆出来的块都还在` | 把 5 块搬回 `fun PlayerScreen(` → 函数体回到 915 行，`lines <= 800` 红 |
| **P9** | 只落地安全一半（Referer 提供者；重建路径原样保留） | `PlayerScreen.kt:2094`（工厂改走 `RefererDataSourceFactory`）、`:2840`（类声明）；`key(streamUrl, streamReferer)` **保持不变**（`:382`） | `P9 Referer 必须变成每请求可读 重建路径暂时原样保留` | 把工厂改回 build 期烧 headers → 测试红（找不到 `RefererDataSourceFactory(`） |
| **P10** | 已落地（状态对象可 JVM 单测） | `WebSniffWatchdog.kt:155-197`（`WebSniffState`：媒体请求记账 + 加载起点 + 导航代次）；`PlayerScreen.kt:2266/2283` | `P4 P5 P10 导航代次变化时加载基准必须重置`、`P4 导航之后 sawMediaRequest 必须归零 否则看门狗永久失效` | 见 P4/P5 两条 |
| **P11** | 已落地 | `PlayerScreen.kt:2176`（`update` 里删掉 `setMediaItem`/`prepare` 死分支，只留 `:2183` 的 `resizeMode`） | `P11 update 里的 setMediaItem 死分支必须删掉` | 把 `if (current != streamUrl) { … }` 加回来 → 测试红（`current != streamUrl` 命中 + `setMediaItem` 计数 2） |
| **P12** | 已落地 | `WebPageDeadEndPolicy.kt:132`（`probeIntervalMs` + 3 个间隔常量）；`PlayerScreen.kt:2417`（消费）、`:2389/2395/2418`（`cleanProbes` 计数与清零） | `P12 探测必须退避而不是无条件 1Hz`、`P12 生产代码必须消费退避判据 且探到错误要把计数清零` | 把 `delay(probeIntervalMs(...))` 换回 `delay(PROBE_INTERVAL_MS)` → 第 2 条红 |
| **P13** | 已落地 | `PlayerPageResolver.kt:34`（`SHELL_FETCH_TIMEOUT_MS = 900`）；`PlayerScreen.kt:2308`（消费） | `P13 壳页请求超时必须压到 1 秒以内且生产消费该常量` | 改回 `withTimeoutOrNull(2_000L)` → 测试红（裸字面量命中 + 常量无消费点） |
| **P14** | 已落地（9 处全部替换，现共 10 个 `clock.nowMs()` 调用点） | `WebSniffWatchdog.kt:118`（`fun interface PlayerClock`，默认实现 `SystemClock.elapsedRealtime()`）；`PlayerScreen.kt:2260`（WebSurface 默认实现）、`:484/486`（提示 ticker）、`:2073/2077`（卡死看门狗）、`:2361`（看门狗 nowMs）、`:2402/2432`（死胡同计时）、`:2610`（onPageFinished）、`:2421/2440`（P2 判据的时刻） | `P14 时钟必须可注入 且播放页不再散落 SystemClock`、`P14 顺带修掉 lint 的 AutoboxingStateCreation` | 把 `clock.nowMs()` 换回 `SystemClock.elapsedRealtime()`/`System.nanoTime()` → 测试红（计数 != 1 / `System.nanoTime` 命中） |
| **P15** | 已落地 | `VideoResolution.kt:21`（顶层 `private val TIER_REGEX`）；`findTier` 改用它 | `P15 档位正则必须是顶层 private val 且行为不变` | 把正则在 `findTier` 里重建 → 测试红（`Regex(` 计数 2） |
| **P16** | 已落地（**API 名：`fun setInPip(value: Boolean)`**） | `PipState.kt:29-33`（`private _inPip` + 只读 `inPip`）、`:87`（写入口） | `P16 inPip 必须只读 写入口是 setInPip value Boolean` | 把 `inPip` 改回 `val inPip = MutableStateFlow(false)` → 测试红 |
| **P17** | 已落地（选"把真实判据放进来"） | `ManualPipPolicy.kt:77`（`shouldResetOnMatchSwitch(hasRecordedVideoSize = true)`）；`PlayerScreen.kt:2026`（调用点现查 `PipState.videoSize.value.width > 0`） | `P17 切场复位不再是恒真函数` | 改回 `= true` → 测试红（旧实现残留 + 生产调用点没传判据） |
| **P18** | **已完整落地**（判据 + 轻回调 + 端到端接线） | `WebPageDeadEndPolicy.kt:167`（`shouldEscalateSuspectOnOffer`）；`PlayerScreen.kt:207`（`onDeadEndSuspect` 形参）、`:2455-2457`（恢复条出现处调用）、`:2245`（WebSurface 形参）；接线（**另一 scope**，已在工作区就位）：`MainViewModel.kt:1358 onDeadEndSuspectOnly()` + `NbaLiveApp.kt:380 onDeadEndSuspect = viewModel::onDeadEndSuspectOnly` | `P18 恢复条出现时必须同步上报疑似 且只报一次`、`P18 上报必须走轻回调 不能直接复用会立刻换线的 onPlaybackSuspect` | 把 `shouldEscalateSuspectOnOffer` 的判断删掉 → 第 2 条红；把 `onDeadEndSuspect` 接到 `viewModel::onPlaybackSuspect` → 第 2 条红（正则/入口断言）；在 `onDeadEndSuspectOnly` 里加 `advanceAfterNoSignal(` → 第 2 条红 |
| **P1 真机回归**（2026-11-01 雷电模拟器） | 已修（结构性，见 §5） | `PlayerScreen.kt`：新增 `WebDeadEndBar` 数据类与 `BoxScope.PlayerDeadEndBar`；调用点紧随 `PlayerMediaGestures(...)` 之后；`WebSurface` 新增 `onDeadEndBarChanged` / `reloadRequest` 两个形参（旧的内部渲染块删除）；新增 2 行 `debugLog` | `P1真机回归 恢复条必须渲染在手势层之后 否则按钮点不动` | 把调用点搬回 `WebSurface` 内部 → 下标断言红；删掉恢复条 → 红 |

## 2. 未完成与原因（**必读**）

1. **P18 已由两个 scope 拼齐（无需再动）**。
   复核把第 18 条改写为"恢复条出现时（原 `:2117`）同步上报 suspect"，但**直接复用 `onPlaybackSuspect` 是有害的**：
   `MainViewModel.onPlaybackSuspect` 在 `markPlaybackSuspect(true)` 之后**紧跟** `advanceAfterNoSignal(...)`
   （现 `:1298/:1310`），于是 1.5 秒就换线 —— 恢复条与那 8 秒选择窗口会一起消失（比原缺陷更糟）。
   最终形态（已全部落在工作区）：
   - 我这一半：纯判据 `WebPageDeadEndPolicy.shouldEscalateSuspectOnOffer(pageDeadEnd)`；
     `PlayerScreen`/`WebSurface` 形参 `onDeadEndSuspect: () -> Unit = {}`；恢复条出现处调用（`:2455-2457`）。
   - 另一半（其他 scope）：`MainViewModel.onDeadEndSuspectOnly()`（`:1358`，只写 `markPlaybackSuspect(true)`、不换线）
     + `NbaLiveApp.kt:380` 的接线。
   - 判据不依赖"约定"：`PlayerFixTest` 的 P18 第 2 条断言 `onPlaybackSuspect` 体内确有 `advanceAfterNoSignal(`、
     `onDeadEndSuspectOnly` 体内**没有**它、且 `NbaLiveApp` 接的是 `onDeadEndSuspectOnly`。
2. **P7 只收窄了失效面，没有把 ticker 搬进独立 composable**。
   `PlayerLoadingHintPolicyTest.接线守卫` 明确要求 `PlayerLoadingHintPolicy.shouldShow(` 出现在
   **`fun PlayerScreen(` 函数体内**（该锚点被复核确认为刻意设计），把 ticker + 判定整体下沉会直接让那条既有守卫变红。
   我改用 `derivedStateOf`：250ms 的写入只让那个布尔失效、且仅在翻转时通知读取方，
   等价达成"失效范围收在它自己"的目标；`others`/`providers`/`currentProvider` 已 `remember`。
   —— 若有更彻底的方案，需要同时改那条既有守卫（我按铁律 2 没改）。
3. **P9 完整版（不重建 ExoPlayer）不做**：拆掉 `key(streamUrl, streamReferer)` 要改
   Referer 生命周期语义 + 补"换线后 CDN 不吃旧 Referer"/热切换的回归守卫，风险集中在最贵的链路上，
   与 `optimization-review.md` §7「不要在本轮一起做」一致。只落地了可注入的 Referer 提供者（行为等价）。
4. **P2 的窗口 = 2 秒**是判断取值：≥15 秒会把"页面发过媒体请求、随后那条 404"这个**唯一目标场景**
   在窗口内判成"在出流"（复核明确点名修法有害）；2 秒只给"错误面板出现的同一时刻页面仍在拉分片"多留一口气。
   若真机上发现源站播放器以 <2 秒节奏重试死地址（导致出口被推迟），把 `MEDIA_REQUEST_STREAM_WINDOW_MS` 调到 1 秒即可，
   判据测试会跟着走（它不含硬编码的 2000）。
5. **`WebSniffState` 仍持有不了 `pageFinishedAt`/`pageErrorAt`**：它们是 `LaunchedEffect` 的 **key**，
   key 必须是 Compose 快照状态才会触发重启；这两个量由 `WebSurface.beginNavigation()` 与探测循环
   （`LaunchedEffect(url, targetUrl)` 起手）在**同一入口**一起归零。这一点写在 `WebSniffState` 的 KDoc 里。

## 3. 真机验证法（回退即红，无法跑 Gradle 的替代证据）

| 条目 | 真机实验 | 期望 |
|---|---|---|
| H6 | 网页线路进死胡同 → `chrome://inspect` 看导航链 → 点「重新加载」 | 请求回到**原始壳页**（不是 `paps.html?id=<同一个>`），随后 m3u8 的 `expire` **变化** |
| P2 | 在 `hasStreamNow` 命中处打一行 `debugLog`，采样一天 | 死胡同场景（单条 404）仍给出恢复条；只有"仍在拉分片"的页面被推迟 |
| P3 | 小窗看球 → 打开抖音 → 来电挂断 | 直播**自动恢复**（不再永久暂停） |
| P4/P5 | 点「重新加载」后在页面再卡住（拔网）| 15s/30s 内仍报「疑似无信号」（不再永久沉默） |
| P6 | 非全屏播放页点全屏 | 底部控制栏**立刻消失**（不是可见 10 秒） |
| P7 | `adb shell dumpsys gfxinfo <pkg> framestats` 对比"网页加载中/已出流" | 加载期帧时间不再被 4 次/秒重组拉高 |
| P12 | 同上，观察网页播放 5 分钟 | 稳定页面里探测频率降到 0.2Hz |
| P13 | 弱网下点网页线路 | 进线不再固定多等 2 秒（最坏 0.9s 回退） |
| P18 | 死胡同恢复条出现后**立刻**按 Home | 不进坏小窗（接线后） |

## 4. U9 播放侧收尾（无障碍动作词，Lead 追加）

**缺口来源**：`fix-ui-state` 给 6 处裸 `clickable` 补 `onClickLabel` + `role` 时，
把 `PlayerScreen.kt` **从自己的源码扫描判据里排除**了 → 这个文件的缺口不会被任何守卫抓住。

**实际处数 = 14，不是 8**（Lead 说"以 grep 为准"）：
- `grep -n "clickable(" …` 只给 10 处；
- 另 **4 处是尾随 lambda** `.clickable { … }`（`clickable(` 的括号形式抓不到），必须用
  `grep -n "\.clickable\b"` 才全。**下一次改这里请用后者。**

**统一形态**（与全仓已修的 6 处同形，见 `MatchDetailScreen.kt:300-304`）：
`.clickable(onClickLabel = …, role = Role.Button, onClick = …)`；新增 import
`androidx.compose.ui.semantics.Role` 与 `com.nbalive.app.ui.CardSemantics`。

| 行号 | 可点区域 | 动作词 |
|---|---|---|
| `:1220` | 播放源/线路面板的遮罩（点外面关闭） | `关闭面板` |
| `:1254` | 面板右上关闭按钮 | `关闭` |
| `:1327` | `PickerRow`（源/线路行） | `切换线路` |
| `:1417` | 「同时在播」面板的刷新按钮（`enabled = !refreshing` 保留） | `刷新同时在播列表` |
| `:1460` | 「同时在播」面板的关闭按钮 | `关闭` |
| `:1510` | `LiveNowRow`（整卡切场） | `CardSemantics.ACTION_LABEL`（"播放比赛"） |
| `:1579` | `VideoControlButton`（画中画/全屏/锁定） | `description`（"画中画"/"退出全屏"/"锁定（防误触）"…） |
| `:1666` | `PlayerIconButton`（暂停/播放/重连） | `description`（"暂停"/"播放"/"重连"） |
| `:1718` | `PlayerChip`（画面/源/线路/同时在播/重新加载/换线路） | 新增形参 `actionLabel: String = label` |
| `:1747` | `OtherMatchRow`（"其他直播"整卡） | `CardSemantics.ACTION_LABEL` |
| `:882` | 「同时在播」遮罩（尾随 lambda） | `关闭面板` |
| `:1037` | `PlayerSignalStrip` 的线路胶囊（尾随 lambda） | `切换线路` |
| `:2674` | "该线路暂无信号"提示的遮罩（尾随 lambda） | `关闭提示` |
| `:2709` | "已拦下一个可疑跳转"提示的遮罩（尾随 lambda） | `关闭提示` |

**`PlayerChip` 的动作词不是名词复读**：`actionLabel` 默认 `= label`（对「重新加载」「换线路」
这类标签本身就是动作的胶囊来说正好，且**不加实参也能编译**），但 4 处名词式胶囊由调用方给准确动作词：
`:788 切换画面比例`、`:803 切换播放源`、`:811 切换线路`、`:820 打开同时在播面板`。

**没有破坏任何刻意保留的源码扫描锚点**（逐一复验仍为 1 处命中）：
`fun PlayerScreen(` 内的 `ManualPipPolicy.decide` / `PlayerLoadingHintPolicy.shouldShow`、
`fun VideoSurface(` 内的 `PipAspectReset.shouldResetOnMatchSwitch`、
`fun WebSurface(` 内的 `WebSniffWatchdog.shouldReport(loadStartedAt=/pageFinishedAt=/nowMs=/sawMediaRequest=)`、
`evaluateJavascript(WebMediaPauseScript.PAUSE_ALL`、`WebPageDeadEndPolicy.reloadLabel/switchLabel`、
`key(webFallbackUrl) {`、`suspectHint && errorMessage == null` 分支、`delay(FIRST_FRAME_WATCHDOG_MS)`。
`fun PlayerScreen(` 函数体 **674 → 677 行**（P8 判据上限 800）；文件词法级括号配对 `pd=0 bd=0`。

## 5. P1 真机回归：死胡同恢复条「两个按钮点不动」（2026-11-01 雷电模拟器）

### 5.1 现场（Lead 提供，可复现）

已结束的比赛 → 网页线路 → `paps.html` 加载成功 → 云端 404 → `ExoPlayerImpl Release`；
**我们自己的恢复条渲染成功**（`note / reloadLabel / switchLabel` 全部生效），但：
连点三次（含 uiautomator 精确 bounds）**没有任何状态变化、没有一行日志**；
同一时刻 `KEYCODE_BACK` 正常；CDP 轮询 6 秒里 WebView URL **完全没变**。

### 5.2 根因判定：**声明位置（层归属）**，同时**纠正 Lead 的一处机制假设**

1. **结构事实（已读代码确认）**：恢复条原来画在 `WebSurface` **内部**的 `Box` 里。
   `WebSurface` 是视频区 `Box` 的**第一个**子（更早的兄弟子树），而手势层
   `PlayerMediaGestures(modifier = Modifier.fillMaxSize())` 是它**之后**的兄弟 ——
   命中测试先落进手势层那棵 `fillMaxSize()` 的子树，更早的兄弟子树（恢复条在其中）拿不到这次点击。
2. **⚠️ 机制纠正**：手势层**没有"消费"点击**。`PlayerMediaGestures` **不用** `detectTapGestures`
   （它自己 `awaitEachGesture` + `awaitPointerEvent`）；而在"未锁定 + 非全屏"这个现场态下，
   `PlayerLock.gestureConfig(...)`（`PlayerLock.kt:64-92`）给的是
   `layerEnabled = true`、`dragEnabled = false`（→ `tapOnly = true`）、
   `tapAction = PASS_THROUGH`（→ `onTap = null`）、`consumeTap = false` ——
   它**一个事件都不消费、也不做任何动作**（`consumeTap` 只在锁定态为 true）。
   所以问题不是"被消费掉了"，而是"**这次点击的命中路径根本没走到恢复条那棵子树**"。
   —— 结论仍然是"层次问题"，修法与两种机制都兼容。
3. **同层对照（本仓库内的经验证据）**：顶栏时钟/画中画/全屏、底部控制栏、源·线路选择面板
   **全部声明在手势层之后**，真机上点得动；恢复条是唯一"我们自己的按钮却画在手势层之前"的例外
   —— 与"按钮点不动"一一对应。
4. **诚实边界（不能排除的第二假设）**：模拟器旋转 + `input tap` 的坐标偏移。
   `KEYCODE_BACK` 不是坐标事件（`PlayerBackStackTest` 那类逻辑走的是 key 事件），
   它正常**不能**证明注入坐标正确。判别实验见 5.4。

### 5.3 修法（结构性，`PlayerScreen.kt` 单文件）

| 改动 | 说明 |
|---|---|
| 新增 `private data class WebDeadEndBar(note, reloadLabel, switchLabel)` | 文案的**唯一来源仍是 `WebPageDeadEndPolicy`**，由"探测到死胡同"的那一处产出 |
| 新增 `BoxScope.PlayerDeadEndBar(bar, onReload, onSwitchLine)` | 从 `WebSurface` 内部提出来的恢复条本体（布局/配色逐字照搬） |
| 调用点紧跟 `PlayerMediaGestures(...)` 之后、顶栏之前 | **这是修复的核心**：与"点得动的按钮"同层；同时保留"底部控制栏在它上面"的原意 |
| `WebSurface` 新增 `onDeadEndBarChanged: (WebDeadEndBar?) -> Unit` | 只上报"该不该显示 + 文案"（`LaunchedEffect(pageDeadEnd)`） |
| `WebSurface` 新增 `reloadRequest: Int`（**计数式**） | 把「重新加载」下传；导航逻辑仍留在 `WebSurface`（`targetUrl/reloadSeq/pageErrorAt/beginNavigation`）。用计数而不是布尔事件，与 `PipState.expandRequests` 同一取舍 |
| 「换线路」直接复用既有出口 `onPageDeadEnd()` | 自动换线判据（`PlaybackAutoAdvance`）的唯一入口，行为与旧实现一致 |
| **自动兜底不变** | `shouldAutoAdvance` + `onPageDeadEndState.value()` 原样留在 `WebSurface` → 9.5 秒后仍自动换下一条 |
| 顺带补 **2 行 `debugLog`** | 这条路径原来**一行日志都没有**。补上"bar 显示/收起"与"reload requested #n from `<redactUrl(targetUrl)>`"（DEBUG-only、URL 走 `redactUrl`，签名不进 logcat） |
| 既有守卫不变红 | `reloadLabel()/switchLabel()/note()` 仍在 `fun WebSurface(` 体内产出 → `WebPageDeadEndWiringTest` 的两条断言照旧成立 |

判据（新）：**`PlayerFixTest.P1真机回归 恢复条必须渲染在手势层之后 否则按钮点不动`** ——
断言 `fun PlayerScreen(` 体内 `indexOf("PlayerDeadEndBar(") > indexOf("PlayerMediaGestures(")`，
外加反向锚点（不许再画回 `WebSurface` 内部）、文案来源、计数式重载、`onPageDeadEnd()` 复用、
9.5 秒兜底仍在。谁把恢复条搬回去 → 红。

### 5.4 判别实验（给 Lead 真机复测）

1. **同层对照（区分"层归属"与"注入坐标"）**：恢复条出现时，先点**顶栏右上「全屏」**
   （同样声明在手势层之后）。
   - 它正常切全屏、而恢复条仍点不动 → 确认是**层次/命中**问题（5.2.1），本次修法对症；
   - 它也不响应 → 是**注入/坐标**问题（5.2.4 的第二假设），与层次无关，需要改用
     `adb shell getevent -l` 看注入坐标、或在 App 内加 `pointer_location` 观察落点。
2. **修后验收（同一路径）**：点「重新加载」应同时看到
   (a) 恢复条**立刻消失**（最容易观察的状态变化）、
   (b) logcat `WebSurface: reload requested #1 from https://…/paps.html?<redacted>`、
   (c) CDP 里 URL 在 ~1s 内变成**壳页**（`…/play/mgxl/?id=…`；H6 的修法就是把 targetUrl 先拉回壳页），
       若捷径重解析成功则变成**新的** `paps.html?id=<新 encodedStr>`。
3. **分流**：(b) 有日志而 (c) 无导航 → 问题在 WebView 导航侧（另一条线）；
   (b) 也没有 → 点击仍未到达实现（回到实验 1）。