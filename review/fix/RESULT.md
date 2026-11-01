# 修复 + 实机验证结果（75 条审查项）

- 起点：`HEAD = ba6b247`（v1.2.24，工作区干净），审查结论见 [optimization-review.md](../optimization-review.md) 与四份分册
- 终点：**工作区 59 个文件改动（+3427 / −1123）+ 17 个新增文件**；**未提交**（等你的指令）
- 执行方式：3 名修复工程师并行（文件范围互不重叠）+ Lead 负责构建/CI/守卫 + 1 名复核员前序复核；**所有 Gradle 构建由 Lead 串行执行**，修复者不跑构建
- 验证物：`review/fix/{data,player,ui-state,test-reconcile}.md`（逐条：改动位置/判据测试名/回退即红实验）

## 1. 结论

**75 条里 65 条全落地（每条配可失败的判据），5 条部分落地，5 条未落地**（后两类逐条给了原因，多数属审查报告 §7 自己建议的"独立一轮"，因为都是 M/L 重构）。

**验收结果（全部由 Lead 实跑）**：

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | **835 通过 / 0 失败 / 0 跳过**（118 suites）— 基线是 714，**新增 121 条判据** |
| `:app:testReleaseUnitTest` | **835 通过 / 0 失败** |
| `:app:assembleDebug` | 成功 · `app-debug.apk` 21.26 MB |
| `:app:assembleRelease` | 成功 · `app-release.apk` 2.65 MB（且**无** `app-UNSIGNED-*` 残留） |
| `:app:lintDebug`（新 CI 的阻塞步骤） | **成功**（3 条 warning、0 error → 新加的 `abortOnError = true` 不误红） |
| 雷电模拟器 · debug 包 | 装机/启动/列表/比分/详情/播放/我的/直播全通，**全程 0 次 `FATAL EXCEPTION`** |
| 雷电模拟器 · release 包（另一实例） | 装机成功、冷启动 **477 ms**、列表 14 场、无崩溃 |

## 2. 逐条落地台账

### 2.1 数据层（18 条 · `review/fix/data.md`）

**全落地 16**：H3(=D1 超时丢数据·每源 deadline + `SourceOutcome.TimedOut`)、H4(=D2 `betterLeague` 进 `MatchMerger`)、D3(`FetchResult.NotFound`·四条 when 路径)、D4(播放页解析加固+空名剔除)、D5(30s TTL + single-flight + `forceRefresh`)、D6(别名集合 + 补 3 队)、D7(失败不覆盖 last-good + `fetchedAtMs`)、D8(`enabled` 真消费)、D9(60s 源冷却·可注入时钟)、D11(迁 `data/model/TeamNameBridge`，**结尾删掉 ui 转发件**)、D12、D13、D14、D16、D17、D18(缓存三路 `debugLog`)

**部分 2**：
- **D10 拆仓库** → 只抽 `MatchMerger`（`detailCache`/`sort` 被既有测试**反射**钉住，抽走即红；完整拆分 = 审查报告 §7 建议的独立一轮）
- **D15** → `JsonCursor` 本轮之前已是 `internal`，本次补 KDoc + 判据；"VM 复用"由 U12 的 `ReleaseRepository` 落地（`MainViewModel.jsonString` 已删 → 两份手写 JSON 取值器收敛为一套）

### 2.2 播放链路（18 条 + 2 项追加 · `review/fix/player.md`）

**全落地 15 + 追加项**：H6(=P1 回壳页重解析 + 导航代次 `navigationTag`/`reloadSeq`)、P2(`hasStreamNow` 2s 窗口，**刻意不接 `sawMediaRequest`**——那会关掉该功能唯一目标场景)、P3(轮询路径补恢复 + 重投条件化)、P4/P5/P10(抽 `WebSniffState`：媒体记账 + 加载基准 + 导航代次)、P6(两个 effect 合并 + `panelWasOpen`)、P8(`fun PlayerScreen(` **915 → 726 行**，抽 5 块)、P11、P12(探测退避)、P13(壳页超时 900ms)、P14(`PlayerClock` 可注入，9 处替换)、P15、P16(`setInPip`)、P17(真实判据)、**P18 端到端**（判据 + `onDeadEndSuspect` 形参 + `MainViewModel.onDeadEndSuspectOnly`（只置标记不换线）+ 根节点接线）、**lint 追加**（`mutableIntStateOf`/`mutableLongStateOf`）、**U9 播放侧 14 处裸 `clickable`**（比原报告估的 8 处多：4 处是尾随 lambda 形态）

**部分 2**：
- **P7** → 失效面已收窄（判据下沉 `derivedStateOf` + 3 处 `remember`），但 ticker 未搬出 `fun PlayerScreen(`：搬走会让既有 `PlayerLoadingHintPolicyTest` 变红，报告 §2.2 有说明
- **P9** → 只落地安全一半（`RefererDataSourceFactory` 每请求可读 Referer）；**`key(streamUrl, streamReferer)` 与重建路径原样保留** —— 拆掉要改播放器生命周期语义并补热切换守卫，风险高于收益（与报告 §7 一致）

**追加（真机发现的新缺陷，已修）**：见 §3.2。

### 2.3 状态/界面层（23 条 + 3 项追加 · `review/fix/ui-state.md`）

**全部落地**：H7(U1 两个 ticker 改 `repeatOnLifecycle(RESUMED)`)、H8(U2 红点相位改稳定引用 + draw 期读)、H9(U4 空列表/0 场也给刷新反馈)、U3(只有赛事页轮询比分)、U5(六个注入点)、U6(`@Immutable`)、U7(`heightIn`)、U8(三份 SP 首次读盘挪出主线程)、U9(6 处动作词)、U10、U12(检查更新下沉 `ReleaseRepository`)、U13～U21、**P16 三处调用点**、**P18 接线**、**`forceRefresh` 一路透传**（冷启动 false / 用户刷新 true）

### 2.4 构建 / CI / 仓库卫生 / 测试体系（19 条 · Lead）

**全落地 10**：H1(CI 断言**产物** `versionName`/`versionCode` 与 tag/源码三方一致 + `versionCode` 严格递增，含上一版 Release 下载复读，取不到时降级为下限+警告)、H2(两条守卫改用新的 `GuardSourceScan.stripComments`，并加**负控**证明"注释里的字面量不再满足判据")、H5(收集数守卫重写为"根体内直接收集 ≤1 + 必须走 `rememberAppUiState` + `AppUiState` 字段 ≤6 + 收集数 ≤ 字段数"，**搬文件绕不过去**)、B6(新增 `.github/workflows/ci.yml`：push/PR 跑两变体单测 + `assembleDebug` + `lintDebug` 并上传报告，带 `timeout-minutes`)、B7(lint 配置显式化 + 不设 baseline；CI 跑 lintDebug)、B8(依赖锁定判据改为**通用比对**全部带版本声明；已实测 18 条声明与 lockfile 全部一致)、B9(`timeout-minutes: 30`)、B13(`stripNonCode` 的原样字符串分支现在处理 `${…}`，与 KDoc 承诺一致)、B16(mapping/APK 归档到 `G:\flutter\work\nba-release-archive\`：183.2 MB → 24.2 MB zip + `SHA256SUMS.txt`，README 写明 artifact 只有 90 天)、B17(`git rm --cached` 38 个设计截图 + `.gitignore` 补规则，已 `git check-ignore` 验证)、B18(把 `.forensics/` 证据**内联**进 `network_security_config.xml`，逐字复核过抓包原文)

**部分 1**：
- **B5** → `META-INF/README.md` 已被 excludes 挡下 ✅；`META-INF/version-control-info.textproto` **挡不住**（带/不带前导斜杠都试过，产物逐 entry 验证仍在；它是 AGP 后加的 java 资源）。要真去掉得挂 AGP 内部任务类型，而本仓库**明确不碰 AGP 内部实现**（04-P2-7 的教训）→ 如实保留并在构建脚本里写明（120 B、内容只是本次构建的 git revision，公开仓库本就是公开信息）

**未落地 5**（均为 M/L 且无用户可见故障，属报告 §7 建议的独立一轮）：
- **B3** `material-icons-extended`（37.4 MB 输入 → release 侧 R8 只留 28 个图标）：需要为约 18 个图标自绘 vector + 逐个目视核对，风险高于本轮收益
- **B10** 覆盖率（JaCoCo）：`dependencyLocking { lockAllConfigurations() }` 下新增插件会引入新 configuration，**必须 `--write-locks` 重新生成 lockfile**（需要联网解析），本轮改会让构建在离线/CI 上直接失败 —— 有意不做
- **B11** `TestPaths` 抽取（49 处 `user.dir`）：大范围测试改写，与 B12/B14 同批做更省
- **B12** 结构守卫的"数量阈值"改造（180 行 / ≤2 处等）
- **B14** 测试体系瘦身（714 → 610~630 的合并方案）

另：**B4** 按报告口径交付（"给可执行定位法"而非改产物行为）——复现步骤与逐 entry CRC / 首个差异字节偏移的定位法已写进 `review/fix/build-test.md`。

### 2.5 本轮新增的守卫（防回退）

`CiWorkflowGuardTest`(3) · `LogCallDisciplineTest`(2，`ui/` 与 `MainActivity` 不许裸调 `Log.`) · `ReleasePipelineGuardTest` 新增 H1 步骤块级断言 · `GuardSourceScan.stripComments` + B13 自检 · `GuardHygieneTest` 新增原样字符串模板自检 · `PlayerFixTest`(27→含 P1 真机回归判据) · `UiFixPolicyTest` / `UiFixWiringTest` / `UiFixInjectionTest` · `MatchMergerTest` / `ListLoadFixesTest` / `ScoreboardFixesTest` / `TeamAliasAndVersionFixesTest` / `ChannelParseFixesTest` / `DetailCacheLogFixesTest`。

## 3. 雷电模拟器实测

设备：`nbatest`（Android 14 / 1080x1920 / 480dpi，debug 包）+ `yuedu_test`（720x1280，release 包）。全部证据来自 `adb logcat`、`uiautomator dump`、WebView DevTools CDP 与 APK 逐 entry 检查。

### 3.1 通过项

| 验证 | 方法 | 结果 |
|---|---|---|
| 装机与启动 | `adb install` + `am start -W` | debug 冷启动 **1367 / 1166 ms**；release 冷启动 **477 ms** |
| 崩溃 | 全程 `logcat` 扫 `FATAL EXCEPTION` | **0 次**（含 404 流、WebView 加载、播放器起停、频繁切页） |
| 列表 | `uiautomator dump` | **14 场**（NBA 7 / CBA 7）、日期与状态正确 |
| **比分渲染（D6 别名修复的可见证据）** | dump 文本 | 卡片上出现真实比分 **`121 : 111`**；logcat `ESPN 比分板 7 场，匹配上 4 场`（修复前是"匹配上 0 场"） |
| **H7 后台不再轮询** | 按 Home 后 190 秒计数 | 前台节奏 **45.0/44.9/44.3 s**（4 次）；**后台 190 秒新增 0 次** |
| **U3 只有赛事页轮询** | 切「直播」页 130 s / 切回 70 s | 直播页 **0 次**；切回赛事 **2 次**（前台功能未被改坏） |
| 我的页 | dump | 播放设置/画面模式三选、**数据源 JRS·JRKAN 开关（"正在解析这个源的线路"）**、外观/深色模式（已开启）、关于 —— 全部渲染 |
| 直播页空态 | dump | 「当前没有正在直播的比赛 / 去「赛事」页看看今天还有哪些场次」 |
| 详情页缓存可观测（D18） | logcat | `MatchRepo: 详情页缓存过期: 364220 (age=868770ms >= 300000ms)` |
| 播放页错误链 | logcat | `ExoPlayerImpl Init` → `Source error … Response code: 404` → `ExoPlayerImpl Release`（源站事实，非 App 回归） |
| **恢复条渲染（H6/P18 的 UI）** | dump | 「源站播放器报错了，换一条线路试试」+「重新加载」+「换线路」 |
| **H6 重新加载拿到新签名** | CDP 轮询 URL + logcat | 点击前 `paps.html?id=Pl2AMkcP…` → 点击后 **`paps.html?id=3n+b3p3+Ieg…`（新 `encodedStr`）**；同时 `WebSurface: reload requested #1 from …/paps.html?<redacted>` + `dead-end bar -> hide` |
| release 产物可装可跑 | 第二实例安装 | 成功，无崩溃，列表正常 |

### 3.2 真机/模拟器**发现并修掉**的新缺陷（只读审查看不出来）

**恢复条的两个按钮点不动。** 现象：恢复条渲染正常，但合成点击（3 次、两种坐标来源、两种注入方式）**没有任何状态变化与日志**，WebView URL 也不变；同一时刻 `KEYCODE_BACK` 能被 App 正常处理。

- 根因（fix-player 读码确认，并**纠正了 Lead 的机制假设**）：恢复条原先画在 **`WebSurface` 内部**，是视频区 Box 的**第一个**子；手势层 `PlayerMediaGestures(Modifier.fillMaxSize())` 是它**之后**的兄弟 → 命中测试先落进手势层那棵子树，恢复条拿不到这次点击。**手势层并不消费事件**（它不用 `detectTapGestures`，且现场态 `tapAction = PASS_THROUGH`、`onTap = null`、`consumeTap = false`）。仓库内同层对照：顶栏/底栏/选择面板**全部声明在手势层之后**、真机点得动；恢复条是唯一例外。
- 修法：把恢复条上提为 `BoxScope.PlayerDeadEndBar`，渲染点**紧随手势层之后**；状态仍由 `WebSurface` 持有（`onDeadEndBarChanged` 上报显示/收起、`reloadRequest` 计数式请求、文案仍由 `WebPageDeadEndPolicy` 产出 → 既有接线守卫不变红）；**9.5 秒自动换线兜底原样保留**。
- 判据：`PlayerFixTest.P1真机回归 恢复条必须渲染在手势层之后 否则按钮点不动`（下标断言 + 反向锚点 + 计数式重载 + 9.5s 兜底仍在）。
- 复测：**通过**（上表 H6 一行）。

> 这条是本轮最有价值的一次实机收获：审查报告把 H6 定为"高"，而它真正的拦路虎是**叠放层次**——静态审查看不出来，只有点下去才知道。

## 4. 诚实边界

1. **未做真机量化**的项（机制/接线已证实，幅度未测）：H8/U2/U6/U10/U13 的重组与分配、U7 的字号裁切、U8 的主线程读盘毫秒数。验证法写在 `review/fix/{ui-state,player}.md` 的"需运行/真机"一节。
2. **未做真机验证**的行为项：P3 的通话恢复（需"小窗看球→抖音→来电挂断"）、P2 的误判概率、P5 的误判率、P9 的换线延迟占比、D4 的源站真实 `data-play` 引号形态、D9 的真实 403/429 频率、D5 的流量量级。
3. **CI 仍未在真实 runner 上跑过**：`ci.yml` / `release.yml` 都是静态核对 + 本地等价命令通过（`testDebugUnitTest` / `testReleaseUnitTest` / `assembleDebug` / `lintDebug` 全部实跑成功），第一次推送才有最终答案。
4. **release 逐字节不可复现**仍是未解现象（B4 只交付定位法）。
5. 本轮改动**未提交**；`git status` 有 39 个删除（38 张设计截图移出索引 + ui 转发件）与 17 个新增文件，需要你决定提交粒度（建议：按 scope 拆 4 个提交 + 1 个"删除入库截图"提交）。

## 5. 复现命令

```powershell
# 全量验收（两个变体 + 两个产物 + lint）
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease --rerun-tasks
.\gradlew.bat :app:lintDebug

# 装机（雷电实例 nbatest = index 1）
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk

# H7/U3 后台轮询验证：前台看 logcat -s Scores 是否 45s 一次；按 Home 后 3 分钟应再无新行
adb -s emulator-5556 logcat -s Scores

# H6 验证：恢复条出现后点「重新加载」，CDP 读当前 URL（应变成新的 paps id）
adb -s emulator-5556 forward tcp:9222 localabstract:webview_devtools_remote_<pid>
curl -s http://127.0.0.1:9222/json/list
```