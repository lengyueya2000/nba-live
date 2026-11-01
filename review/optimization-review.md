# 全量代码审查：可优化点与价值分级

- 审查对象：`NBA Live`（Kotlin + Jetpack Compose 单 Activity Android 应用；WebView 嗅探 m3u8 → ExoPlayer 播放；手写正则解析 JRS / JRKAN 两个源站）
- 快照：`HEAD = ba6b247`（v1.2.24），`git status --porcelain` **0 行**；`git hash-object` 与索引 blob 逐文件一致（工作区 = HEAD，无 assume-unchanged / skip-worktree）
- 范围：`app/src/main/java/**` **68 个文件 / 16,563 行**、`app/src/test/java/**` **96 个文件 / 19,972 行**、构建脚本、`.github/workflows/release.yml`、清单、资源、既有构建产物
- 方法：**4 名审查员并行只读**（范围互不重叠）+ **1 名对抗式复核员**（逐条回源码证伪）+ Lead 亲自复核全部"高"档条目
- **未做**：未运行 Gradle / 构建 / 装包 / 真机。**未修改任何生产代码**。凡"运行期后果"都标注 `推测` 并给出验证方法
- 分册（完整原文引用、逐条位置、门禁清单、复核明细）：
  [播放链路](opt/player.md) · [数据层](opt/data.md) · [状态与界面层](opt/ui-state.md) · [构建/CI/测试](./opt/build-test.md) · [对抗式复核](opt/verification.md) · 前次 95 条台账 [coverage-ledger.md](coverage-ledger.md)

## 0. 一句话结论

代码的**下限很高**：关键判据抽成纯函数、并发与取消严谨、注释记录"为什么"（含真实回归的来龙去脉）、安全基本面干净（无密钥泄漏、无 JS 桥、keystore 在 122 个提交里从未入库、178 MB 混淆映射与 APK 全被 ignore 挡住）。

本次共得 **75 条可优化点（高 9 / 中 35 / 低 31）+ 1 份测试体系瘦身方案**。复核覆盖 61 条：**确认 46 / 部分确认 14 / 证伪 1**（证伪的那条已从本报告删除并换成修正后的表述）。

真正该马上动的三类：

1. **防线形同虚设（3 条，各 ≤ 半小时）**：CI 不核对产物版本号与 `versionCode` 递增；两条构建守卫用未剔注释的裸文本断言，**注释就能骗过它**；`AppStructureGuardTest` 的"根节点收集点 ≤2"守卫**已不可能再变红**（合规样本 = 搬迁后的当前生产形状）。
2. **静默丢数据 / 静默误判（3 条）**：60 秒整体超时会丢掉另一个源**已经拿到**的场次并报成"超时"；合并时漏拷 `league` 会让一个源的联赛识别失败**整场吞掉**另一个源的记录；死胡同判据的 `hasStream` 参数被写死，`!hasStream` 这道否决从未武装（复核同时指出：把 `hasStream` 直接接上"历史上见过媒体请求"会**永久关掉该功能唯一的正经场景**，修法要重新定义）。
3. **可持续的资源浪费（3 条）**：两个定时轮询不是 lifecycle 驱动，**切后台仍每 45/60 秒真发请求**（HTTP，不是内存）；共享呼吸红点用 `staticCompositionLocal` **每帧**刷新整个标签页子树；筛选后为 0 场时刷新**毫无进度反馈**。

## 1. 价值口径（本次统一使用）

| 等级 | 判据 |
|---|---|
| **高** | 用户可见故障 / 数据或安全风险 / 稳定性问题（崩溃、静默丢数据、发版事故、不可逆误判），**或** 收益明显且成本 ≤ S（半小时级） |
| **中** | 收益明确但成本 M（半天级），或只影响少数场景 / 边界情况 |
| **低** | 整洁性、命名、注释、**无测量支撑**的微优化（后者一律封顶"低"） |

- **成本**：`S` < 半小时 · `M` 半天 · `L` > 1 天
- **判定**：`已证实` = 已从代码/产物读到确定事实；`推测` = 需真机/构建/抓包才能确认
- **复核**：`V✓` = 对抗式复核员逐条回源码确认 · `V~` = 复核后修正过表述 · `L` = Lead 亲自复读

## 2. 与前次 95 条审查的关系

前次（[00-summary.md](00-summary.md)，v1.2.12）的 95 条已由 [coverage-ledger.md](coverage-ledger.md) 收敛为 **87 已落地 / 8 有据取舍**。本报告不重复已落地条目、不为"有据取舍"翻案；四条以"该修复的余量 / 回归风险 + 新证据"给出：

| 新条目 | 关系 |
|---|---|
| D1 整体 deadline 丢数据 | 02-P2-3（已落地 60s deadline）的**余量**：落地文案与 `partialError` 的设计意图，被同一段实现的 `matches = emptyList()` 抵消 |
| D3 404 被当成 host 不可达 | 02-P1-9（已落地镜像候选）的**余量**：`Unreachable` 分类把 404 也算成"域名挂了" |
| P5 看门狗计时基准 | 第九回合"25→15s / 45→30s"的**回归风险**：新证据是 `advanceAfterNoSignal` 的"疑似即换线"消费链与台账自记的 0.1~27s 加载重叠 |
| B2 守卫命中注释 | 04-P2-7/8（已落地改名闸）的**守卫形同虚设** |
| U11 收集数守卫 | 03-P2-15（已落地"根节点只消费一个状态对象"）的**度量被搬家规避** |

## 3. 高价值 9 条（建议本轮处理）

| # | 结论 | 位置 | 成本 | 判定 | 复核 |
|---|---|---|---|---|---|
| H1 | 发版流水线**只核对源码 `versionName`**：产物版本号只"打印"不断言，`versionCode` 无任何判据 → 发一个 `versionCode` 不递增的包，流水线全绿 | `.github/workflows/release.yml:73-82`、`:149-158`、`app/build.gradle.kts:37-38` | S | 已证实 | V✓ |
| H2 | `BuildScriptHygieneTest` 7 条里 **2 条用未剔注释的 `raw.contains`**；`app/build.gradle.kts:81`/`:174` 的**注释**里就有这两个字面量 → 删掉真改名逻辑（`:135`/`:160`），守卫照样绿 | `app/src/test/.../security/BuildScriptHygieneTest.kt:55-58`（对照 `:42-47`） | S | 已证实 | V✓ |
| H3 | 60 秒整体 deadline 一旦触发就 `matches = emptyList()`：**另一个源已拿到、2 秒就返回的十几场被整体丢弃**，还被报成"两源都超时"（JRS 首候选要跑满 3×35s=105s，必然触发） | `MatchRepository.kt:622-642` | M | 已证实 | V✓ |
| H4 | 合并用 `existing.copy(...)` 且**不含 `league`** → 先合并的 JRKAN 判 `OTHER` 时，同场 JRS 记录（NBA/CBA）一起变 `OTHER`，被 `supported` 过滤**整场静默丢弃**（连 JRS 线路一起） | `MatchRepository.kt:819-849` | S | 已证实 | V✓ |
| H5 | `AppStructureGuardTest` 的"根节点收集点 ≤2"**已不可能再红**：判定实现数的是 `fun NbaLiveApp(` 体内的 `collectAsStateWithLifecycle(` 个数，而当前生产根函数只剩 `:67` 一处（其余 5 条搬进了 `rememberAppUiState`）→ 下次照抄"搬到别的文件"即可绕过 | `AppStructureGuardTest.kt:107-123,316-318`、`ui/AppUiState.kt:48-52`、`NbaLiveApp.kt:67` | S | 已证实 | V✓ |
| H6 | 网页线路的「重新加载」执行 `webView.reload()`，而当前页面在 `PlayerPageResolver` 走捷径时就是 `paps.html?id=<旧 encodedStr>` → **重载的还是那条死地址**（注释承诺"壳页会给新 encodedStr"只在回退路径成立） | `PlayerScreen.kt:2422-2428`、`:2010-2027` | S | 代码事实已证实；后果需真机 | V✓ |
| H7 | 两个定时轮询用 `LaunchedEffect` 而非 lifecycle 驱动：**切后台/画小窗后仍在跑**，比分链每 45 秒真发一次 ESPN 请求（TTL 20s 吸不掉 45s 节拍；KDoc 却写"切后台自然停"） | `ui/AppEffects.kt:67-73`（同款 `:155-158`） | S | 机制已证实；频率需真机 | V✓ |
| H8 | 共享呼吸红点用 `staticCompositionLocalOf` 且**每帧换值** → 提供者内的整棵子树每帧被标记失效，而它包着「赛事/直播/我的」三个标签页（注释却写"只在绘制阶段读状态"） | `ui/components/Common.kt:561,571-580,595`、`ui/AppChrome.kt:473-475` | S | 机制已证实；掉帧需 Profiler | V✓ |
| H9 | 联赛筛选后场次为 0（或直播页无直播）时，下拉刷新/刷新按钮**没有任何进度反馈**：指示器门槛是"页内列表非空"，`firstLoad` 看的却是**未筛选**列表 | `screens/MatchListScreen.kt:117-137`、`ui/MainViewModel.kt:684-685`、`LiveNowScreen.kt:87` | S | 已证实 | V✓ |

**编号对照**（本报告为"高"档重新编号，与分册编号一一对应，便于回查原文引用）：

| 本报告 | 分册编号 | 本报告 | 分册编号 |
|---|---|---|---|
| H1 | `build-test.md` 第 1 条 | H6 | `player.md` 第 1 条 |
| H2 | `build-test.md` 第 2 条 | H7 | `ui-state.md` 第 1 条 |
| H3 | `data.md` D1 | H8 | `ui-state.md` 第 2 条 |
| H4 | `data.md` D2 | H9 | `ui-state.md` 第 4 条 |
| H5 | `ui-state.md` 第 11 条 | — | — |

其余条目沿用分册原编号（`player.md` P2~P18、`data.md` D3~D18、`ui-state.md` U3/U5~U10/U12~U21、`build-test.md` B3~B18）。

### H1/H2/H5 详述：为什么这三条"防线"要先做

- **H1**：`versionCode = 28` 手工维护（`app/build.gradle.kts:37`）。CI 只做 tag ↔ 源码 `versionName` 字符串比对（`:77-81`）；随后 `aapt2 dump badging` 的产物已落盘 `/tmp/badging.txt`，却只 `head -1` 贴进 Step Summary（`:149-158`）——**拦事故的数据就在手边，只是没拿去断言**。后果：`versionCode` 变小 → 已安装用户 `INSTALL_FAILED_VERSION_DOWNGRADE` 永久收不到更新。
- **H2**：同文件 `:42-47` 已正确用 `GuardSourceScan.stripNonCode(raw)`，偏偏 `:55-58` 四条（`endsWith("-unsigned.apk")`、`app-UNSIGNED-`、`.renameTo(target)`、`PackageAndroidArtifact`）仍是裸 `raw.contains`，而 `app/build.gradle.kts:81`/`:174` 的注释逐字包含这些串。**这正是 `GuardHygieneTest.kt:7-31` 明文讨伐的"断言命中注释"形态**，在 04-P2-7/8 的硬化里漏了两条。改法：`raw` → `stripNonCode(raw)`，再真删一次改名逻辑验证会变红。
- **H5**：这条守卫是 03-P2-15（"根节点只消费一个状态对象"）的度量点，而它数的是**某个函数体内的调用点个数**。5 条 flow 只是被搬进 `AppUiState.kt:48-52`，根作用域的失效面没变；合规样本（测试文件内嵌的"正确形状"）与当前生产逐字同形，所以它今后**不可能再红**。改法：要么断言 `AppUiState` 字段数上限，要么统计 `NbaLiveApp.kt` + `AppUiState.kt` 两文件合计。

## 4. 中价值 35 条

### 4.1 数据正确性与源站协同（data，9 条）

| # | 结论 | 位置 | 成本 | 判定 | 复核 |
|---|---|---|---|---|---|
| D3 | 所有非 2xx（含 404/410）都归 `Unreachable` → 单场页面 404 就登记 host 失败、主域名降到候选队尾，而**新首位要跑满 3 次重试（105s > deadline）**，与 D1 叠加得空列表 | `remote/HttpClient.kt:105-109`、`MatchRepository.kt:732-742` | S | 已证实 / 链末端推测 | V✓ |
| D4 | 播放页解析**没跟上列表页的加固**：只认双引号 `data-play="`、只认裸 `<strong>` → 上游一改写法，JRKAN 线路整表静默归零；名字取不到时 `rawLabel=""` 让"过滤主播线路"这条唯一规则失效，可直连者反而排到第 0 条 | `remote/JrkanParser.kt:301-310` | S | 不对称已证实 / 触发推测 | V✓ |
| D5 | 列表层**零 TTL、零 single-flight**：每次下拉刷新重下 JRKAN 索引 **271,323 B** + JRS 列表 11 KB；而详情页有 5 分钟缓存、比分有 20s TTL | `MatchRepository.kt:607-672`、`MainViewModel.kt:671-674` | M | 已证实 | V✓ |
| D6 | 队名表**两份且互相不一致**：`NBA_TEAM_KEYWORDS`（28 条）缺雷霆/猛龙/奇才，`TeamNameBridge`（30 条）有；反向 `NICK_TO_CN` 每昵称只一个中文名（源站写"小牛"时 ESPN 比分永远对不上） | `data/model/Match.kt:105-110`、`ui/TeamNameBridge.kt:36-108` | S | 已证实 / 源站写法推测 | V✓ |
| D7 | 比分抓取失败时**旧比分不清**（`snapshot.isEmpty()` 直接 `return`），而失败会写进 20s TTL 缓存 → ESPN 不通期间卡片一直挂着"冻结的比分"，无任何陈旧提示 | `MainViewModel.kt:350-354`、`ScoreboardRepository.kt:75-91` | S | 已证实 | V✓ |
| D8 | `ScoreboardRepository.load(enabled)` 的 `enabled` **一次都没被读**，KDoc 却写"全关掉时也没必要拉比分" | `ScoreboardRepository.kt:62-69` | S | 已证实 | V✓ |
| D9 | 403/429 只做到"这一次不重试"，**没有跨调用冷却**：限流窗口内用户每次下拉刷新都再撞一次（最坏用法恰好是用户直觉） | `MatchRepository.kt:732-737` | M | 推测 | V✓ |
| D10 | 1180 行的 `MatchRepository` 职责过宽（抓取/重试/合并/时钟校正/缓存/播放页展开/候选校验），且 `mergeForTest`/`correctLiveStatusesForTest` 这类测试入口混在生产 API 里 | `MatchRepository.kt:213,798,802` | L | 已证实 | V✓ |
| D11 | **数据层反向 import UI 包**（`ScoreboardRepository` → `ui.TeamNameBridge`），全仓唯一一处；该类本就无 Compose 依赖，属数据层 | `ScoreboardRepository.kt:9,101` | S | 已证实 | V✓ |

### 4.2 播放链路（player，9 条）

| # | 结论 | 位置 | 成本 | 判定 | 复核 |
|---|---|---|---|---|---|
| P2 | `hasStream` 参数被**写死 `false`** → `WebPageDeadEndPolicy` 的 `!hasStream` 这道否决从未武装（这是"判据参数已死的死代码"）。**⚠️ 复核修正**：不能直接改成 `sawMediaRequest.get()` —— 该功能唯一的目标场景就是"页面确实发过媒体请求、只是那条请求 404"，接上它会把功能永久关掉。真修法是让 `hasStream` 表示"当前是否真的在出流"（最近 N 秒仍有新分片请求 / 未进入错误类），或删掉这个已写死的参数 | `PlayerScreen.kt:2109-2124`、`WebPageDeadEndPolicy.kt:74,90`、`WebErrorProbeScript.kt:11-12,68-73` | S | 代码事实已证实；误判概率推测 | V~（已由"高"降为"中"） |
| P3 | **轮询路径**只 `pause()` 没有对称的 `resume`，而收 GAIN 事件需要我们在焦点栈里（被抖音用永久 GAIN 抢走后就不在）→ 通话结束可能永久停在暂停态；且 `postDelayed` 无条件自投（注释写"只在播放中轮询"） | `PlayerScreen.kt:1696-1726`、`AudioFocusPolicy.kt:127,148` | S | 轮询分支已证实；恢复链需真机 | V~（"没有 resume 分支"表述过宽：`:1667-1676` 有 GAIN→`play()`） |
| P4 | `sawMediaRequest` 是 `AtomicBoolean` 且**只 `set(true)`、跨导航不清零** → 点「重新加载」或回退原页后嗅探看门狗**永久失效**；若页面又卡住且播放器不画错误面板，则三条感知通道都沉默（黑屏无提示） | `PlayerScreen.kt:1986,2055-2060,2218` | S | 已证实；"静默"前提需真机 | V✓ |
| P5 | 看门狗计时基准（`loadStartedAt`/`pageFinishedAt`）**不随导航重置**，30s 硬上限与台账自记的"加载 0.1~27s"重叠 → 慢但健康的线路被判疑似，而 `onPlaybackSuspect` 会**真的换线**并记账 | `PlayerScreen.kt:1990-1994,2266-2268`、`WebSniffWatchdog.kt:95-97` | S | 基准已证实；误判率推测 | V~（`pageFinishedAt` 是可变状态、每次 finish 都重写，"沿用旧基准"仅当回退页迟迟不 finish） |
| P6 | 两个以 `fullscreen` 为 key 的 `LaunchedEffect` 互写 `controlsVisible`（一个收起、一个回补），净结果可能是**进全屏后浮层不收起**（等 10 秒自动隐）；规则依赖 Compose 启动顺序，而 `AppEffects` 自己批评过这种依赖 | `PlayerScreen.kt:306-308`、`:317-325`、`SystemBarsPolicy.kt:81-85` | S | 推测 | V✓ |
| P7 | 250ms 加载提示 ticker 的状态**在同一 restart 作用域内被读**，`others`/`providers` 派生列表未 `remember` → 网页加载期间 4 次/秒重组该块 content lambda（含过滤与列表），正是 chromium 与播放器 JS 最吃 CPU 的时段。**复核修正**：读点在 `:334` 起的 `Column` content lambda 内，不是 `PlayerScreen` 函数体顶层 | `PlayerScreen.kt:444-459,978-980,1107` | S | 重组面已证实；帧时间推测 | V~ |
| P8 | 播放页单函数约 **990 行**（`:147/150`–`:1140`）、**15 个 `LaunchedEffect`**，结构守卫只约束根组合项（≤180 行）→ 最大的这个函数没有对应守卫，P6 这类 effect 互写就出在这里 | `PlayerScreen.kt:147-1140` | M | 已证实 | V~（"20 个"修正为 **15**） |
| P9 | ExoPlayer **每次换线路整体重建**（含解码器/工厂），只为把 Referer 烧进工厂；可用 `ResolvingDataSource`/可变 Referer 复用实例，缩短换线延迟与黑帧 | `PlayerScreen.kt:346,1838-1844` | L | 重建已证实；收益推测 | V✓（复核认为其价值不宜占"高"位） |
| P10 | WebSurface 内 **3 条 `while(true)` + 6 个 effect** 的嗅探状态机不可测：`sawMediaRequest` 生命周期、`pageErrorAt` 复位、循环互斥都没有任何 JVM 判据（对照已抽出的 `StalledPlayback.Tracker`） | `PlayerScreen.kt:1944-2441,2053-2131` | M | 已证实 | V✓ |

### 4.3 状态与界面层（ui-state，8 条）

| # | 结论 | 位置 | 成本 | 判定 | 复核 |
|---|---|---|---|---|---|
| U3 | 「直播」标签页也在轮询比分，而该页**根本不渲染比分**（判据漏了"只有 MATCHES 消费 `scores`"） | `AppEffects.kt:68`、`LiveNowScreen.kt:64-80` | S | 已证实 | V✓ |
| U5 | VM 三处外部依赖不可注入（`ScoreboardRepository()` 就地 new、`HttpClient.shared`、`System.nanoTime/currentTimeMillis/ZonedDateTime.now`）→ 比分链、检查更新链、缓存过期语义在 JVM 单测里驱动不了 | `MainViewModel.kt:325,401,409,544,773` | S | 已证实 | V✓ |
| U6 | 三个 UiState **未标 `@Immutable`** 且含 `List`/`Map` → 传它们的 composable 无法进入 skip 路径（数据层 `Match`/`StreamSource` 已用同一手段） | `MainViewModel.kt:35,87,95`；对照 `Match.kt:166,190` | S | 已证实；省多少需 Profiler | V✓ |
| U7 | 固定 `height(24.dp)` 压随 `fontScale` 缩放的 `10.5.sp`（同类 20dp/9.5sp、26dp）→ 大字号下 chip 文字被裁；Manifest 明确不接管 `fontScale` | `Common.kt:473-490,367-413`、`AndroidManifest.xml:25` | S | 组合已证实；裁切幅度需真机 | V~ |
| U8 | `onCreate` 里同步 `AndroidPrefsStore(...)+init()`：三份 SharedPreferences 首次读取会**主线程等磁盘加载**（StrictMode `DiskReadViolation`） | `MainActivity.kt:53-54`、`AppPrefs.kt:39-42` | M | 机制已证实；毫秒需量化 | V~ |
| U9 | 网格卡、搜索结果行、「检查更新/下载」行仍是**裸 `clickable`**（无 `onClickLabel`/`role`）→ TalkBack 只播"双击以激活"；新增可点区域不会让任何守卫变红 | `MatchListScreen.kt:525`、`SearchScreen.kt:248`、`ProfileScreen.kt:210,251` | S | 已证实 | V✓ |
| U10 | `TeamLogo` 每次重组都新建 `ImageRequest`（不在 `remember` 里）；与 H8 叠加后可能"每帧每个可见队徽一次" | `Common.kt:149-153` | S | 分配已证实；重下载推测 | V✓ |
| U12 | `MainViewModel` 职责过载（1469 行：网络编排 + 导航 + 偏好 + 播放控制 + 比分 + 更新检查），且 **`ui/` 层直接发 HTTP + 手写 47 行 JSON 状态机**，越过 `data/` 边界、自建一份超时/脱敏策略 | `MainViewModel.kt:151-208,542-611,922-1136` | M | 已证实 | 未复核 |

### 4.4 构建与测试体系（build-test，9 条）

| # | 结论 | 位置 | 成本 | 判定 | 复核 |
|---|---|---|---|---|---|
| B3 | `material-icons-extended`：报告称 `classes.jar` **37,425,981 B** 只为 28 个图标（release 侧 R8 已只留 28 个）；debug 包 **22.26 MB / 12 dex**。**这是构建与调试循环问题，不是 APK 体积问题** | `app/build.gradle.kts:237` | M | debug/release 体积已独立复核；AAR 体积与图标数未独立复算 | V~ |
| B4 | release **逐字节不可复现**（同源码同大小、sha 不同）：复核独立确认 **292 个 entry 的时间戳全部相同（`1981-01-01 01:01:02`）**、`META-INF` 下无 `.SF`/`.RSA`；报告另外排除了 EC 签名（证书是 `rsaEncryption`）与 VCS 元数据。剩 D8/R8 或签名块 | `coverage-ledger.md:865-866`、`app/build.gradle.kts:54-66` | M | 推测 | V~ |
| B6 | 唯一工作流是 **tag 触发**（`git ls-files .github` 只有一个文件）→ 日常 push/PR **零 CI**，714 条用例只在发版日跑（那时 tag 已推出、`concurrency` 又禁并行取消）；且从不跑 `assembleDebug` | `release.yml:12-26,85-86` | S | 已证实 | V✓ |
| B7 | lint 不是闸：只有 `lintVital`（fatal-only，release 报告 "No issues found."），唯一完整 debug 报告是 **4 天前**的 2 条。**复核修正**：旧报告里的 `line=204` 在当前代码中已是 KDoc，`mutableStateOf(0)` 实际在 `PlayerScreen.kt:249` —— 旧报告不能当现状证据 | `app/build.gradle.kts:106-121`；`app/build/reports/lint-results-debug.xml` | S | 已证实 | V~ |
| B11 | **49/96 个测试文件各自实现路径解析**（复核独立计数：49 个含 `user.dir`、35 个用 `walkTopDown`；报告另称"38 处候选根"，复核按 `File(cwd` 计得 **26**，口径需标明）→ 换执行器/挪目录时大面积假红，或被改成 `assumeTrue` 后静默变绿 | `security/RepoConfigDisciplineTest.kt:92-100`、`ui/AppStructureGuardTest.kt:356-367` | S | 已证实 | V~ |
| B12 | 结构"形状"守卫是重构阻力、**失败信息指向写法而不是行为**：`根组合项函数体 ≤180 行` 是某轮拆分的测量残留；`collectAsStateWithLifecycle ≤2`、`whenBodyWithCall` 的最内层 `when` 唯一、`functionBody` 遇表达式体直接 `error()` —— 把 `when` 改成 `if`、把函数改成 `= run { }` 都会以"判据找不到锚点"变红 | `AppStructureGuardTest.kt:45-53`、`GuardSourceScan.kt:135-137` | M | 已证实 | V✓ |
| B13 | 共享扫描器有**真实盲区**：原样字符串分支不处理 `${…}`（与 `:30-31` 的 KDoc 承诺相反）→ 写进 `"""…${Foo.bar()}…"""` 的调用数成 0：正向守卫**假红**、反向守卫**假绿** | `GuardSourceScan.kt:30-31` vs `:412-424`（普通字符串分支 `:436-440` 有处理） | S | 实现已证实；是否已有实例推测 | V✓ |
| B14 | **45.4% 的用例是"把 Kotlin 源码当字符串匹配"（324/714），但只占 19.8 s 里的 5.1 s** → 成本在维护面不在速度：`注释与字符串里的调用不算接线` 这条自检在 8 个文件近乎逐字重复，剔注释实现有 3 份 | `app/build/test-results/testDebugUnitTest` | M | 已证实 | 未复核 |
| B15 | **不要一起砍的部分**（反向红线/完整性/单一实现纪律）：`ReleasePipelineGuardTest`（mapping 不许进 Release assets）、修好后的 `BuildScriptHygieneTest`、`CleartextPolicyTest`、`AppStructureGuardTest.definitionViolations`、`ZeroConsumerGuardTest`、`SourceZoneDisciplineTest`、`LoggingDisciplineTest` | 见分册第三节 | — | 保留清单 | 未复核 |

### 4.5 测试体系瘦身方案（分册第四节，摘要）

- **可合并**：抽 `TestPaths`（49 处 → 1，约 −300 行）；合并 4 个仓库/构建纪律 suite（−3 条）；合并 8 份重复自检（−7 条）；合并三套 WCAG 对比度算式（−2~4 条）；合并 7 个"接线"守卫里的重复断言（−15~20 条）。
- **可删**：`180 行`/`≤2 处` 这类数量阈值 + 2 条结构自检；`NavHostGuardTest` 与 `AppStructureGuardTest` 重叠部分；`BuildScriptHygieneTest` 的两条纯锚点。
- **可替换成行为级**：`PrefsBoundaryGuardTest`(9) 里 ≥5 条、`DeadCodeWiringGuardTest`(14) 里 9~10 条、`ZeroConsumerGuardTest`(6) 的正向条目 —— 台账§四已证明行为用例能覆盖同一批。
- **估算**：用例 714 → **约 610~630**；测试源码 19,972 行 → **约 16,500~17,500 行**；**运行时长几乎不变**（买的是维护面，别为"更快"扩大改动）。

## 5. 低价值 31 条

### 5.1 数据层（data，7 条）

| # | 结论 | 位置 | 成本 | 判定 |
|---|---|---|---|---|
| D12 | `fetchJrsDetail(forceRefresh=true)` 不注册自己的 deferred 却在 `finally` 里 `remove`，可能删掉别人在飞的条目 → 偶发重复请求 | `MatchRepository.kt:285-305` | S | 推测（机制已证实） |
| D13 | 热路径逐项新建 `Regex`；`playId` 正则 `line\d` 只认单数字（`line10` → 0 条线路，卡片连徽标都不画） | `JrkanParser.kt:129-130,306,309` | S | 已证实 |
| D14 | `GameScore.startAtMillis` 是**死字段**，且每场多一次 `Calendar` 分配 | `EspnScoreboard.kt:49,84,110-113` | S | 已证实 |
| D15 | 两份手写 JSON 取值器；`MainViewModel.jsonString` 缺"前一个是 `{`/`,`"边界校验。**复核修正**：它**要求闭引号 + 冒号**，故"命中更长的 key"不成立，真实残余风险只剩"文档里更早出现同名嵌套 key" | `MainViewModel.kt:565-571` vs `EspnScoreboard.kt:227-244` | S | 已证实 |
| D16 | `SourceOutcome.enabled` 零消费点，语义与注释含糊 | `MatchRepository.kt:540-541` | S | 已证实 |
| D17 | 版本号比较把 `1.2.24-hotfix.1` 判成比 `1.2.24` 新、`1.2.24-hotfix` 判成相等 | `UpdateCheckPolicy.kt:56-66,123-128` | S | 已证实 |
| D18 | 详情页缓存的命中/TTL 过期/失效三条路径**零日志**，现场只能靠请求数反推 | `MatchRepository.kt:263-267,954-976` | S | 已证实 |

### 5.2 状态与界面层（ui-state，9 条）

| # | 结论 | 位置 | 成本 | 判定 |
|---|---|---|---|---|
| U13 | `MatchGrid` 每次重组做 O(n) `firstOrNull` + `filter` 分配（未 `remember`） | `MatchListScreen.kt:225-227` | S | 分配已证实 |
| U14 | `Decision.Available.notes` 零消费（release body 被解析并长期持有，却不显示） | `UpdateCheckPolicy.kt:32-39` | S | 已证实 |
| U15 | "直播中"有两份来源，`StatusLabel.of` 的 LIVE 分支在生产不可达（改文案不会影响界面） | `Common.kt:429,468-472`、`StatusLabel.kt:32-33` | S | 已证实 |
| U16 | 详情页/播放页持有的 `Match` 副本不参与状态老化（ticker 只在 LIST 路由跑） | `MainViewModel.kt:770-779` | S | 已证实 |
| U17 | `navStateKeyFor` 的 `else` 兜底把字段名笔误变成"静默取错字段" | `NavState.kt:112-117` | S | 已证实 |
| U18 | `route` 是 `nav.route` 的同步镜像，根节点同时收集两份同一信息 | `MainViewModel.kt:264-272`、`AppUiState.kt:31,48-49` | M | 已证实 |
| U19 | `playerState` 被 `RouteHost` 与 `AppTopBar` 各收集一次（顶栏只用两个队名） | `NbaLiveApp.kt:180`、`AppChrome.kt:152,166` | S | 已证实 |
| U20 | 播放侧用裸 `Log.w`（`LoggingDisciplineTest` 只认 `, t` 形态，拦不住 URL 进第二参数） | `MainViewModel.kt:1167` | S | 已证实 |
| U21 | 首屏 loading 三页不一致（列表页转圈 vs 直播页一行静止文字） | `LiveNowScreen.kt:126-132` vs `MatchListScreen.kt:720-733` | S | 已证实 |

### 5.3 播放链路（player，8 条）

| # | 结论 | 位置 | 成本 | 判定 |
|---|---|---|---|---|
| P11 | `update` 里的 `setMediaItem` 分支在正常路径不可达（`key(streamUrl)` 保证同一实例 URL 不变），异常时会退化成"每次重组都 prepare" | `PlayerScreen.kt:1915-1925` | S | 已证实 |
| P12 | 死胡同探测 1Hz `evaluateJavascript` 无退避无上限（与嗅探看门狗叠加成 2Hz） | `PlayerScreen.kt:2085-2102`、`WebErrorProbeScript.kt:53-79` | S | 推测 |
| P13 | 每次进网页线路都多一次壳页 OkHttp 请求（2s 超时） | `PlayerScreen.kt:2010-2027` | S | 已证实 / 代价推测 |
| P14 | 播放页 9 处直用 `SystemClock`/`nanoTime`（原报告写"7 处"，复核校正为 **9**），无法注入 | `PlayerScreen.kt:447,449,1820,1824,1994,2058,2091,2108,2268` | S | 已证实 |
| P15 | `VideoResolution.findTier` 每次调用重建 `Regex`（调用点在重组路径上） | `screens/VideoResolution.kt:97-100` | S | 已证实 |
| P16 | `PipState.inPip` 是唯一可被外部直写的 `public MutableStateFlow`（其余私有 + 写入口）；复核计数为 **3 处**写点 | `ui/PipState.kt:20`、`MainActivity.kt:49,250,302` | S | 已证实 |
| P17 | `PipAspectReset.shouldResetOnMatchSwitch()` 恒 `true`，effect 里的 `if` 是运行时死条件（测试断言的是常量） | `ManualPipPolicy.kt:64-65`、`PlayerScreen.kt:1775-1779` | S | 已证实 |
| P18 | **复核修正后的版本**：`onPageDeadEnd` **已经**置 `playbackSuspect`（`MainViewModel.kt:1284`），所以真正可进"坏小窗"的窗口是**恢复条出现（1.5s）到自动换线（9.5s）之间的那 8 秒**；修法是恢复条出现时（`PlayerScreen.kt:2117`）同步上报 suspect | `NbaLiveApp.kt:221-226,375`、`MainViewModel.kt:1278-1285` | S | 已证实（原第 18 条的前提经复核**被证伪**，已改写） |

### 5.4 构建与仓库卫生（build-test，7 条）

| # | 结论 | 位置 | 成本 | 判定 |
|---|---|---|---|---|
| B5 | `packaging.resources.excludes` 只挡两条，release 产物仍带 `META-INF/README.md`(3,218 B) 与 `version-control-info.textproto`(120 B，内含构建 git revision) | `app/build.gradle.kts:103-105` | S | 已证实 |
| B8 | 依赖锁定一致性判据只写死 `kotlinx-coroutines-test` 一个 artifact；`lockAllConfigurations()` 又锁进约 80 个 AGP 内部 configuration。**复核已逐个核对 12 条直接依赖，当前全部一致** | `RepoConfigDisciplineTest.kt:54-58`、`app/build.gradle.kts:265-267` | S | 已证实 |
| B9 | CI 缺 `timeout-minutes`（复核：全文件 0 次），而 `cancel-in-progress: false` → 某步挂住可堵发版窗口数小时 | `release.yml:24-32` | S | 已证实 |
| B10 | 无 ktlint/detekt/JaCoCo（复核：`app/build.gradle.kts` 里 `lint` 命中 0 次）→ 风格与覆盖率零自动化 | `app/build.gradle.kts:223-259` | M | 已证实 |
| B16 | 大文件确实都没入库，但 **mapping 归档只活在这一份工作区**：`git clean -xfd` 即永久丢失 1.2.13~1.2.16 的映射（178 MB），而 CI artifact 默认只留 90 天 | `.gitignore:9,19`、`README.md:91-94`、`release.yml:160-165` | S | 已证实 |
| B17 | `design/**/out/`、`design/minimal/tmp/` 截图**入库**（54 文件 / 7.12 MB），与 `.gitignore` 自己"截图不入库"的声明矛盾 | `.gitignore:38-40` | S | 已证实 |
| B18 | 生产文件把 git-ignored 的 `.forensics/` 抓包当**安全豁免的证据**引用（换机器无法复核） | `res/xml/network_security_config.xml:8-9`、`.gitignore:43` | S | 已证实 |

## 6. 复核结论（对抗式复核员 + Lead）

复核覆盖 **61/75 条**（全部"高"与"中"档 + 抽样低档）：**确认 46 / 部分确认 14 / 证伪 1**。未复核：`ui-state.md` 第 12、13、14、17~21 条，`build-test.md` 第 10、14~18 条（结构债/低档与仓库级统计）。

| 类型 | 条目 | 处理 |
|---|---|---|
| **证伪**（前提与代码相反） | player 第 18 条原写"`onPageDeadEnd` 不改 `playbackSuspect`"，实际 `MainViewModel.kt:1284` 就是 `markPlaybackSuspect(true)`——**它建议的修法已经在生产里** | 已按"8 秒窗口"改写为 P18 |
| **修法有害** | player 第 2 条建议 `hasStream = sawMediaRequest.get()`：该功能唯一目标场景正是"页面发过媒体请求但那条 404"，接上它会把功能永久关掉 | 价值由"高"降为"中"，修法改为"表示当前是否真在出流" |
| **表述过宽** | player 第 3 条"没有对应 resume 分支"——`:1667-1676` 有 GAIN→`play()` | 改为"轮询路径没有 resume" |
| **计数修正** | player 第 8 条 `LaunchedEffect` 20 → **15**；第 14 条时钟调用 7 → **9**；第 16 条"两处"→ **3 处**写点；build-test 第 11 条"38 处候选根"→ 复核计 **26**（口径未标明） | 已改 |
| **范围修正** | player 第 7 条"整个 PlayerScreen 函数体"→ 实际读点在 `Column` content lambda 内；第 5 条"必然沿用旧基准"→ 仅当回退页迟迟不 finish | 已改 |
| **论证修正** | data D15"命中更长的 key"不成立（needle 含两侧引号） | 已删该论证 |
| **行号修正** | build-test 第 7 条的 lint `line=204` 映射错位（现为 KDoc，变量在 `:249`） | 已改 |
| **该降级的** | player 第 9 条（收益全在真机测量、成本 L）、player 第 11 条（三行洁癖）、build-test 第 3 条（release 已被 R8 砍到 28 图标，只影响 debug 与构建时间） | 均已在"中/低"归位 |
| **未独立复算** | build-test 第 3 条的 37.4 MB AAR 与"28 个图标"数自原报告；第 4 条的 RSA OID 未重做 | 已标注 |

**复核员重排后的 Top 5**（可作为执行顺序的输入）：① `AppEffects.kt:67-72` 后台 45s 真发请求 ② `AppStructureGuardTest` 收集数守卫已不可能再红 ③ `BuildScriptHygieneTest.kt:55-58` 注释骗过发布事故守卫 ④ D2 静默丢整场 ⑤ U4/H9 空列表刷新无反馈。

## 7. 建议执行顺序

| 顺序 | 项目 | 理由 |
|---|---|---|
| 1 | **H2** 两条守卫改用 `stripNonCode` | 半小时内；改完**真删一次**改名逻辑验证会变红 |
| 2 | **H1** CI 断言产物 `versionName` + `versionCode` 单调 | 半小时内；数据已在 `/tmp/badging.txt` |
| 3 | **H5** 修 `AppStructureGuardTest` 的收集数守卫 | 半小时内；把"度量某个函数体内的调用点"改成"两文件合计/字段数上限" |
| 4 | **H4** `betterLeague(a,b)` 进 `copy(...)` | 半小时内；补"JRKAN=OTHER + JRS=NBA 同键"用例 |
| 5 | **H7 + U3** 两个 ticker 改 `repeatOnLifecycle(RESUMED)`；判据改 `tab != MATCHES` | 半小时；后台真实 HTTP 请求优先于观感项 |
| 6 | **H9** 空列表也让忙碌态可见 | 半小时；"按钮点了没反应"且触发条件常见 |
| 7 | **H8 + U6** 红点相位改 draw 期读 / `State<Float>`；三个 UiState 加 `@Immutable` | 半小时；两者叠加才构成"每帧每子树"成本 |
| 8 | **H3** 超时下移到每个源，保留已成功侧 | 半天；分开组装"超时源/正常源"的 `failedProviders/reasons` |
| 9 | **H6** 「重新加载」回到壳页而非 `reload()` | 半小时；**先真机确认** paps 服务端是否重新签名，否则降为中 |
| 10 | **U5 + B6 + B7** 补 VM 注入点；新增 `ci.yml`（push/PR 跑 `testDebugUnitTest + assembleDebug`）+ lint 报告作 artifact | 半天；714 条用例只占 19.8 s，成本极低 |

**不要在本轮一起做**：D10/R 仓库拆分、U12 VM 分层、P9 ExoPlayer 复用、B3 图标瘦身、B12 结构守卫改造 —— 都是 M/L 且无用户可见故障，应与测试瘦身方案（§4.5）合并成独立一轮，**先有 CI 兜底再动**。

## 8. 明确"不值得改"的（防止下一轮重复提）

四份分册共列 33 条，要点：

- **产品硬需求 / 实测取舍**：明文 HTTP 白名单（源站播放页本身是明文）、`MIXED_CONTENT_ALWAYS_ALLOW`、`mediaPlaybackRequiresUserGesture=false`、WebView 每次换线重建（P2-4 防线）、UI 文案不进 `strings.xml`（无多语言需求）、`USER_AGENT` 写死 Chrome/120、`ChannelAdmission` 只有 4 个敏感词（用户 2026-10-29 定的产品规则）。
- **已有的正确取舍**：`rememberClockText` 1Hz（只在分钟变化时写状态）、`PlayerControls.shouldShowBottomBar` 两个未用参数（有理由 + 等价性测试）、`PlayerLock.gesturesEnabled`（测试 oracle）、`stableFallbackId` 用 `hashCode()`、`SniffedPlaylistCache` 用墙钟、`debugLog` 打完整签名地址（仅 DEBUG）、单测钉死 UTC 时区、`FAIL_ON_PROJECT_REPOS`、Gradle wrapper 校验和。
- **已复核为干净**：keystore 在 122 个提交里从未入库、`gradlew` 模式位 `100755` + CI 自行 `chmod`、`.gitattributes` 与"工作区 = HEAD"一致、178 MB mapping/APK 不会被 `git add -A` 误提交、53 个 `META-INF/*.version` 属标准做法。

## 9. 必须真机 / 真跑 CI 才能定的（归并 10 类）

| 主题 | 待定问题 | 验证方法 |
|---|---|---|
| H6 | paps 捷径下点「重新加载」能否拿到新签名 | 真机点按钮后看 chromium 是否回到壳页、随后 m3u8 的 `expire` 是否变化 |
| P2 | 源站页面里是否真存在"会报错的同源 xgplayer iframe" | `chrome://inspect` 看 iframe/class；或探测命中 `'1'` 时把节点 outerHTML 打 logcat |
| H7 / U3 | 后台是否真每 45/60s 发请求；进程冻结策略（Android 12+ Freezer / OEM） | `adb logcat -s Scores` 后台 5 分钟计数 + 抓包 |
| H8 / U6 / U10 / U13 | 重组面与分配速率 | Layout Inspector recomposition/skip 计数、`perfetto`、Coil `EventListener` 请求计数 |
| P5 / P9 / P12 | 30s 硬上限的实际误判率、换线延迟中"重建播放器"占比、1Hz 探测开销 | `dumpsys gfxinfo framestats`；真机对比 `ExoPlayerImpl Init`→首帧；弱网限速 |
| P6 | 两个 effect 的实际启动顺序（Compose 版本相关） | 真机：非全屏播放页点全屏，看底栏立刻消失还是可见 10 秒 |
| D3 / D4 / D6 | 镜像域名存活与延迟、`data-play` 引号形态、源站 NBA 队名写法 | 抓一份存活 `steam<id>.html` 与含 NBA 的列表页 |
| D5 / D7 / D9 | 列表 TTL 收益量级、ESPN 连续失败时"冻结比分"、403/429 真实频率 | 真机抓包计数 + 断网/限速实验 |
| U7 / U8 | 大字号 chip 是否裁切、TalkBack 朗读、SharedPreferences 首读毫秒 | fontScale 1.3/1.5/2.0× 截图；TalkBack 走查；`am start -W` + StrictMode |
| B3 / B4 / B6 / B7 | release 不可复现成因、图标瘦身实际占比、流水线在真实 runner 是否可跑（`ANDROID_HOME`/build-tools/`gh`/4 个 secret）、完整 lint 现状 | 同一工作树连跑两次 `assembleRelease` 逐 entry 比 CRC + 首个差异字节；`--no-build-cache -Dorg.gradle.workers.max=1` 对照；`gh workflow run` 演练；`apkanalyzer dex packages`；`:app:lintDebug` |

## 10. 方法论附注

- **行号陷阱（本轮实测）**：PowerShell `Get-Content` 会把 UTF-8 中文里含 `0x85` 的字节当成换行，行数被少算约 30%（`PlayerScreen.kt` 实测 **2485** 行而非 2149；`MatchRepository.kt` **1180** 而非 912）。本报告与五份分册的行号一律以 `read`/`grep` 为准；后续脚本统计请用 `[System.IO.File]::ReadAllLines()`。
- **复核方法**：复核员对每条被复核项都 `read` 回原文件、逐字比对"原文引用"是否存在、再独立判断结论，并独立重算了可量化的部分（APK 体积、292 个 entry 时间戳、测试文件计数、`lint`/`timeout-minutes` 命中次数）。**未发现"引用了文件里不存在的代码"这类硬错误**；最接近的两处是 P18 的前提与同一函数第 1284 行相反、B7 把旧 lint 报告行号映射到当前 KDoc 行——均已修正。
- 本报告**不含任何代码改动**；`git status` 仍只有 `?? review/`（新增 6 个文件）。所有"高"档条目都给了可执行的门禁实验。