# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）
- 构建/测试：`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`（全量 **496 通过 / 0 失败 / 0 跳过**，debug 与 release 两个变体同数）
- 验收还要跑 `.\gradlew.bat :app:assembleDebug`
- 测试结果 XML 在 `app/build/test-results/testDebugUnitTest/`（**多人并行时会被覆盖，要在 Gradle 锁内拷走再计数**；XML 带 BOM，用 `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 读）

## 总任务
按 95 条审查清单（review/00~04）逐条落地修复。
纪律：先 TDD 写纯判据 RED 测试 → 生产代码 → 编译 → 全量测试 → 提交。
**最核心的风险形态是「假安全感」**：写了/测了但生产零调用、以及永远不会变红的守卫。

## 最近两个回合的提交
### 2026-10-29 第四回合（goal round 2）：审计 A/B 两组「假安全感」清零
一个回合 5 个提交，全量 **496/0/0**（debug+release）+ `assembleDebug` 成功。
| 提交 | 内容 |
|---|---|
| `ecdea2e` | **B1/B3/B7/B8/B10** 删死链路：`PipState.shouldKeepPip` + 整个 `PipExitOnErrorTest`(3) 删除；`MainActivity.pipAspectRatio()` 改为唯一来源 `PipAspectReset.ratioFor`；删 2 参 `shouldBlock` 重载；两处"有意零消费"KDoc 说明 |
| `e4f4177` | **B2/B4/B5/B8** 接线：疑似无信号提示真正画到播放页（`suspectHint = PlaybackHealth.showsSuspectHint(playbackLevel)` + `PlayerScreen` 常驻提示卡）；`Route.PLAYER` 缺 match 走 `MissingMatchPolicy.forPlayer` + 占位；`Tracker.onTick` 委托 `evaluate`（阈值判据唯一来源） |
| `8acbf68` | **B6/B11**：`CardSemantics` 只留 `ACTION_LABEL` 常量（删恒等函数与被忽略的参数）；`Theme.kt` 提出 `LiveGradientSource` 唯一色值来源，三处渐变全部派生 |
| `6ad6679` | **A4–A9、A11–A16** 11 个测试类的"永远不会红"守卫全部改成会红（含 A11 发现的**测试自身并发丢更新**真 bug） |
| `2a3715c` | **`ZeroConsumerGuardTest`**：零消费点登记表守卫（9 条 WIRINGS + 3 条函数体委托 + 3 条禁止复活 + 唯一色值来源），是这一轮的"防复发"机制 |

回退实验（每条都是单变量、回退后可编译、只有目标断言红、逐字节恢复）：
Lead 做 B5/B4/B2 各 1 次（各 2 条目标断言红，其余守卫全绿，SHA-256 恢复一致）；
`pip-health` E1–E4、`card-theme` E1–E6（含 **E3「抄一份同值副本」照样全绿** → 补源码派生断言）、
`guard-fixups` 11 条变异（83/11，逐条给出红点消息）；`guard-auditor` 只读审计报告入库。

### 2026-10-28 发版：v1.2.13
- `release: v1.2.13`：versionCode 16→17、versionName 1.2.12→1.2.13，tag `v1.2.13`
- 发版前验证：`:app:testDebugUnitTest :app:testReleaseUnitTest` **467/0/0**（两个变体）；
  `:app:assembleRelease` 产出 `app-release.apk` 2,733,925 bytes
- **验签**：`apksigner verify --print-certs` → Signer #1 `CN=NBA Live, OU=App, O=lengyueya2000, L=Beijing, C=CN`，
  证书 SHA-256 `62bd4ee4ebddb7c7789ee15573273afbf7550789b3bbc27ce87f3b0cc73552a8`；
  `aapt2 dump badging` → `versionCode=17 versionName=1.2.13`；APK SHA-256 `DBD8B332…547F`
- 归档（仓库根，均已被 `.gitignore` 排除）：`nba-live-1.2.13.apk` +
  `nba-live-1.2.13-mapping.txt`（44 MB，R8 混淆映射 —— **必须与版本一一对应**，
  丢了线上崩溃就只剩 `a.b.c`）
- **04-P2-5（release mapping 归档）本轮落地为流程**：README 新增「发版清单」
  （版本号 → 两变体测试 → assembleRelease → **apksigner 验签** → 归档 APK+mapping → tag），
  `.gitignore` 增补 `*-mapping.txt`（对照表会暴露代码结构，绝不入库）。

### 2026-10-28 第三回合（goal round 1）
- `3ef8a3a` **03-P2-3 + 03-P2-2**：`ui/NavState.kt`（tab+route 合成**一个**状态机，
  纯迁移函数 selectTab/openDetail/openPlayer/back；NavTab 不带图标所以可单测）；
  route/tab/matchId/beforePlayer 存进 `SavedStateHandle`，重建后回到 **DETAIL**
  （播放页地址带时效签名，回去只有黑屏）；详情层**等列表加载落地**再跳，找不到就留在列表；
  `MainActivity` 用 `viewModelFactory { initializer { createSavedStateHandle() } }` 注入
  真正可持久化的 handle（默认值只为不动既有测试 —— 守卫钉住注入点）。
  新增 `NavStateTest`(10) + `NavRestoreTest`(5，用"两个 VM 共用一个 SavedStateHandle"
  忠实模拟进程重建)。**回退验证：4 处生产改动一次回退 → 19 tests / 8 failed**。
- `579cb45` **守卫硬化（审计 A1/A2/A3/A10）**：`DeadCodeWiringGuardTest` 重写
  （剔注释、**声明不算使用**、加非空性锚点）、`AndroidConfigVersionTest` 基础主题
  也剔 XML 注释、7 个文件 **20 处 `assumeTrue` 清零**成硬失败；新增元守卫
  `GuardHygieneTest` + 词法级 `GuardSourceScan.stripNonCode`。
  验证方式：**4 次单变量回退实验**（每次 `tests=16 failures=1`，只有目标断言红），
  生产文件 `git checkout --` 逐字恢复、`git diff app/src/main` 为空。
- `282d034` docs：本轮状态 + 只读审计报告 `review/audit-guards.md`（498 行）入库
### 2026-10-28 第二回合（上一轮）
- `29c43b3` 03-P2-21/22/10（`ContextExt.kt` 唯一 findActivity + `SquareIconButton` + liveMatches 单源）
- `7828a6b` 03-P2-4/9/16/17/31/35（删死代码 onNewIntent、去冗余 remember、
  `resolveDarkMode` 唯一判据、UI 不再持 Context、去 `loaded` 短路、`SubPageBarSideSlot`）
- `532a7b0` 03-P2-11/12/27/28/29（LogoFallback 提层、remember 移出 when、
  `logoFallbackFontSize` 抵消 fontScale、surfaceContainer 9 槽、色板 internal）
- `7fe2ead` 03-P2-8/26/30/32/34 + P2-33(部分)（跨零点 ticker、hero 动作词、
  颜色字面量、时间口径统一 hhmm()、删 96dp）
- `7cdf50e` 03-P2-19/20（InfoRow/错误态收敛 + 结构化重复守卫）

## 已验证落地（不必重做）
P0-1、P0-2、01-P1-2/3/4/6、01-P2-1~8/10/11/12、02-P1-1~9、02-P2-1~13、
03-P1-1~4、**03-P2-1/2/3/4/5/8/9/10/11/12/13/16/18(未)/19/20/21/22/23/24/25/26/27/28/29/30/31/32/34/35**、
04-P1-1~5、04-P2-3/4/6/7(部分)/8/9
（03 组未做的只有 P2-6/14(取舍)/15/17(剩余)/18/33(剩余) —— 见下）

**审计 A/B 两组（2026-10-29 第四回合）**：
- ✅ **B1–B8、B10、B11 全部落地**：B1/B3 删死链路+唯一来源、B2/B4 接线（提示真的画出来、
  缺数据真的给占位）、B5 判据唯一来源、B6 去假参数、B7/B8 有意零消费 KDoc 说明、
  B10 删冗余重载、B11 渐变单一来源。
- ✅ **A4–A9、A11–A16 全部硬化**：原先"永远不会红"的守卫改成会红 + 内存样本自检 + 非空锚点。
- ✅ **A1/A2/A3/A10**（上一回合已修）+ 新元守卫 `GuardHygieneTest`、新登记表守卫
  `ZeroConsumerGuardTest`（零消费点复发即红）。
- ⬜ **B9/B12** 属"有判据兜底的有意零消费"（`PlayerLock.gesturesEnabled`/`tapShowsUnlockUi`
  只作测试 oracle；`PlayerControls.shouldShowBottomBar` 两个参数有 4×全布尔组合的等价性断言），
  审计判定**不算缺陷**，仅记录，无需处理。
- ⬜ 审计「未验证的怀疑」7 条仍未验证（时区耦合、Kotlin `assert()` 依赖 Gradle
  enableAssertions、`LiveStatusCorrectionTest` 依赖机器时钟、`MainViewModelTest.liveIds` 测试侧重实现 等）。

## 仍存在的主要缺口
### A. 结构性大改（03 组残余）
1. **03-P2-18** 820 行上帝组件拆分（路由/系统栏/画中画/返回键/搜索覆盖层各自独立）；
   做完它会顺带消掉 **03-P2-6**（`syncAutoEnterEnabled` 靠 DisposableEffect 先于
   LaunchedEffect 执行的隐式顺序）与 **03-P2-15**（根节点 collect 6 个 flow）。
2. **03-P2-17 剩余**：UI 仍直接读偏好 `StateFlow`；setter 仍带 Context 形参。
   彻底做要把偏好读进 `MainViewModel`，它是纯 `ViewModel` → 得改成 `AndroidViewModel`
   （牵动 `MainViewModelTest`），属结构性改动。
3. **03-P2-14**：**已做有据的取舍**（信号列表保持 `forEachIndexed`，注释写明
   "线路数假设 < 20"与重估条件）。不是漏做。
4. **03-P2-7** 判定为**不成立/无效修复**（显式 `dismiss()` 是多写一行，已回退）。**别再做**。
5. **03-P2-33 剩余**：全量文案硬编码（审查自己判定现状可接受）。
6. **04-P2-5** release mapping 归档 → **已落地为流程**（README「发版清单」+ `.gitignore` 排除
   `*-mapping.txt`，本次 v1.2.13 已按流程归档）；仍可做的是**自动化**（CI 在 tag 上
   产出 APK+mapping 并挂到 Release，目前是手工按清单执行）。
   **04-P2-7 彻底版**需升级 AGP。

### B. 只读审计的产物（`review/audit-guards.md`，498 行，**必读**）
审计覆盖 68/68 测试类 + ui/ 全目录，发现两类"假安全感"。**A、B 两组已于第四回合清零**：
- ✅ **A1/A2/A3/A10**（第三回合）+ **A4–A9、A11–A16**（第四回合 `6ad6679`）；
  元守卫 `GuardHygieneTest`（禁 assume 家族、剔注释、≥60 文件锚点、自检喂真函数）
  与登记表守卫 `ZeroConsumerGuardTest` 一起防复发。
- ✅ **B1–B8、B10、B11**（第四回合 `ecdea2e`/`e4f4177`/`8acbf68`）。
- ⬜ **B9/B12**：审计自己判定为"**有判据兜底的有意零消费**"，不算缺陷，仅记录未处理。
- ⬜ **`DeadCodeWiringGuardTest` 里仍有 9 条"文件级 contains"**
  （`WebMediaPauseScript.PAUSE_ALL`、`BackPolicy.shouldIntercept`、`MissingMatchPlaceholder`、
  `SystemBarsPolicy.*` …）：剔注释后"命中注释"那类已消，但"**声明即满足**/换等价写法就漏"
  仍可能存在 → 建议逐条升级成调用点计数（低优先，`ZeroConsumerGuardTest` 已覆盖其中
  最危险的那些）。
- ⬜ 审计「未验证的怀疑」7 条仍未验证（时区耦合、Kotlin `assert()` 依赖 Gradle
  enableAssertions、`LiveStatusCorrectionTest` 依赖机器时钟晚于夹具日、
  `MainViewModelTest.liveIds` 测试侧重实现、`JrkanParserTest` 用 `systemDefault()` 造夹具 等）。
  验证成本最低的一条：`testOptions.unitTests.all { systemProperty("user.timezone", "UTC") }`
  后跑 `--tests '*JrkanParserTest'`，若变红即为真缺陷。

### C. 产品缺口（需要决策，不是纯技术问题）
**`Route.DETAIL` 当前没有任何入口**：所有入口都直接 `playMatch()` 进播放页
（`NbaLiveApp.kt` 注释："点比赛直接全屏自动播放，不再先跳详情页选线路"）。
于是 `MatchDetailScreen`(395 行)、`MissingMatchPlaceholder`、`openPlayer`、`refreshSignals`
只能靠本回合新增的"**进程重建恢复**"到达。
两种走法：① 加一个明确的"选择线路"入口（产品决策）；② 认定详情页不再是产品的一部分，
把 DETAIL 路由/屏幕/VM 函数删掉（会连带删掉一批测试）。**别在没决策前顺手删。**

## 教训（务必遵守）
- **守卫写"存在性"时，声明行也算存在**：`contains("FIRST_FRAME_WATCHDOG_MS")` 会被
  `const val FIRST_FRAME_WATCHDOG_MS` 满足，`contains("isSourceFailed(")` 会被
  `private fun isSourceFailed(` 满足 → **排除声明行再计数**，或换到别的文件查调用点。
- **断言前必须剔注释**（本轮审计里 4 条盲守卫全是"命中了注释"）。XML 同理（`<!-- -->`，
  含跨行）。
- **绝不用 `assumeTrue` 静默跳过**：路径一变整片变绿，而"496 全绿"看不出区别。
  现在有元守卫 `GuardHygieneTest` 钉住这条。
- **提取"函数体"必须先配对跳过参数表**：直接找声明后的第一个 `{` 会拿到 Compose
  形参默认值里的空 lambda（`onPlaybackError: () -> Unit = {}`），"函数体"永远是空的 ——
  于是一条专门查"函数体内是否调用 X"的守卫**永远不会红**。
  `ZeroConsumerGuardTest` 第一次跑就是这么暴露的（`fun PlayerScreen(` 报"体内没有
  ManualPipPolicy.decide"，实际在体内）。自检样本现在包含这个形态。
- **反向断言（"这个符号不许出现"）也必须剔注释**：`BANNED` 最初用裸 `contains`，
  被"解释为什么删掉它"的 KDoc 冤枉（队友评审抓到）。注释就该能解释"为什么删"，
  别为了守卫过而牺牲可读性 —— 改守卫，别改注释。
- **"同值副本"能骗过相等断言**：把单一来源抄成**同样的字面量**，`assertEquals(派生, 副本)`
  照样绿（实测 E3 22/0）。要拦这种形态只能加**源码派生断言**（`X = Source.map{...}`）。
- **回退验证必须"编译得过"**：故意删掉被守卫保护的调用（保留注释/声明）才是有效验证；
  把仓库改到编译不过只是"炸了"，不是"守卫红了"。
- **有些测试编码了"生产走不到的时序"**：`PlayerBackStackTest` 原来在 PLAYER 上再调
  `openPlayer()` 来模拟详情页（而 `openPlayer` 当时写死来源=DETAIL）。收紧规则后它红了 ——
  这时要判断是"测试错了"还是"代码错了"：本例是测试错了（生产点不到详情页的线路行），
  已改成走真实路径且**断言不变**。
- 源码一律只用 `read/edit/write` 改；PowerShell 的 `Set-Content`/`-replace` 会写坏中文。
- `--rerun-tasks` 不能省；Gradle 用文件锁串行化；计数在锁内拷 XML。
- **编译失败时 Gradle 不清理 `test-results/*.xml`**：会把上一轮的 XML 当成本轮结果，
  于是虚报数字（本轮实测被虚报成 83/11）。**计数前先删结果目录**。
- **并行写者的中间态会互相阻塞编译**：`PipExitOnErrorTest` 在"生产已删 `shouldKeepPip`、
  测试还没删"的窗口里让整份测试源集编译不过。这不是谁的错，但**要认出它**：
  报错指向自己没碰过的文件时，先看 `git status` 判断是谁的中间态，别去改别人的文件。
- **改完未提交的代码不能用 `git checkout --` 恢复**（会把整段实现冲掉，还可能编译不过）：
  回退实验前用 **SHA-256 快照 + 逐字节 `Copy-Item` 恢复**，恢复后核对哈希一致。
- **测试自己也会并发丢更新**：`MatchRepositoryRetryTest` 用普通 `MutableList` 在两个源并发下
  计数，实测 8 次只记到 ≤7 —— 于是一条"重试有上限"的断言看起来永远成立。
  测试夹具里的计数器要用 `ConcurrentLinkedQueue`/`AtomicInteger`（A11 抓到）。
- **`git add` 的 pathspec 只要有一条不存在，整条命令什么都不加**（`fatal: pathspec ...`）：
  本轮就因此把 8 个文件漏在提交外，靠 `--amend` 补回。加完要 `git show --stat` 核对。
- 守卫三铁律：① 剔注释；② 匹配调用而非字符串出现；③ 路径失败 `error()`，禁止 `assumeTrue`。
- 并行协议（Agent Teams，本会话已跑两轮）：写范围互斥并记在 task 上；**只有 Lead 提交**；
  只读审计可与写作并行；**最终验收窗口放在所有写者停工之后**。

## 下一次接手的建议顺序
1. `git status` 确认没有未提交的并行工作 / 没有 `TEMP-REVERT` 之类临时标记
2. 跑全量 + `assembleDebug` 建立基线（**计数前先删 `app/build/test-results/*`**）
3. 剩下的活按价值排序：
   ① **审计「未验证的怀疑」7 条**（先做时区那条：把单测时区钉成 UTC 跑 `JrkanParserTest`，
      红了就是真缺陷；成本最低、覆盖面最广）；
   ② `DeadCodeWiringGuardTest` 残余 9 条"文件级 contains"升级成调用点计数；
   ③ **03-P2-18 上帝组件拆分**（820 行 `NbaLiveApp`，做完顺带消掉 03-P2-6 与 03-P2-15，
      属结构性大改，单独一轮 + 独立写范围）；
   ④ 03-P2-17 剩余（UI 直接读偏好 `StateFlow`）+ 03-P2-33 剩余（文案硬编码）；
   ⑤ 04-P2-5 自动化（CI 在 tag 上产出 APK+mapping 挂 Release）。
4. **C 的产品缺口**（`Route.DETAIL` 无入口）需要问用户，不要自行删页面。
5. 新增守卫一律登记进 `ZeroConsumerGuardTest`（声明必须真有调用点）或
   `GuardHygieneTest`（不许 assume 静默跳过），别再另铺一套散装断言。