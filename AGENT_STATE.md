# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）
- 构建/测试：`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`（全量 **467 通过 / 0 失败 / 0 跳过**）
- 验收还要跑 `.\gradlew.bat :app:assembleDebug`
- 测试结果 XML 在 `app/build/test-results/testDebugUnitTest/`（**多人并行时会被覆盖，要在 Gradle 锁内拷走再计数**；XML 带 BOM，用 `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 读）

## 总任务
按 95 条审查清单（review/00~04）逐条落地修复。
纪律：先 TDD 写纯判据 RED 测试 → 生产代码 → 编译 → 全量测试 → 提交。
**最核心的风险形态是「假安全感」**：写了/测了但生产零调用、以及永远不会变红的守卫。

## 最近两个回合的提交
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
审计覆盖 68/68 测试类 + ui/ 全目录，发现两类"假安全感"：

**B-1 永远不红的守卫（A1/A2/A3/A10 已修，其余待做）**
- ✅ A1 `DeadCodeWiringGuardTest` 4 条 raw-contains 断言（命中注释/声明）→ 已重写
- ✅ A2 `AndroidConfigVersionTest` 基础 themes.xml 未剔注释 → 已修
- ✅ A3 该类 22 处 `assumeTrue` + 无非空性锚点 → 已重写
- ✅ **A10** 另 7 个文件 18 处（实测 20 处含 import）assumeTrue → 已清零；
  新增元守卫 `GuardHygieneTest`（剔注释+剔字符串、≥60 文件锚点、9 个文件锚点、
  "自己必须在扫描范围内"反向锚点、自检直接喂真函数）
- ⬜ **残留（已如实标注）**：`DeadCodeWiringGuardTest` 另有 9 条仍是"文件级 contains"
  （`WebMediaPauseScript.PAUSE_ALL`、`BackPolicy.shouldIntercept`、`MissingMatchPlaceholder`、
  `SystemBarsPolicy.*` …）——严格剔注释/字符串已消掉"命中注释"那类，但"**声明即满足**"
  与"换个等价写法就漏"仍可能存在，应逐条升级成调用点计数
- ⬜ **A4** `PlayerLockTest`「切场锁定」= 局部变量自证；**A5** `MergeStatusTest`「id 唯一」
  在 `size==1` 时恒真；**A6** `AccessibilitySemanticsTest`「红点不靠颜色」只断言 `LiveDot`
  函数名；**A7** 直播页 LIVE 过滤负向守卫正则过窄；**A8** `alpha` 参数"声明即满足"；
  **A9** `SourceZoneDisciplineTest` 自检是 String.contains 永真；**A12** `WebMediaPauseScript`
  全子串断言；**A13** `StreamSniffOwnershipTest` 负向用例缺前置；**A14/A15** 同类
- ⬜ 未验证怀疑 7 条（时区耦合、Kotlin `assert()` 依赖 Gradle 默认 enableAssertions、
  `LiveStatusCorrectionTest` 依赖机器时钟晚于夹具日、`MainViewModelTest.liveIds` 测试侧重实现）

**B-2 零消费点的声明（全部待办）**
- ⬜ **B1【高】** `PipState.shouldKeepPip` 生产零调用 → **整个 `PipExitOnErrorTest`(3 条)
  测的是没人调的函数**（`PlaybackHealthTest:17` 自己承认旧路径传常量"从来没生效过"）
- ⬜ **B2【高】** `PlaybackHealth.showsSuspectHint` 零调用 → 文档承诺的"疑似无信号**提示**"
  从未接线（与 `partialError` 同款形态）；**要接就得改 UI 行为 = 产品决策**
- ⬜ **B3【高】** `PipAspectReset.ratioFor` 零调用 → `MainActivity.pipAspectRatio()`
  又写了一份"比例 + 16:9 兜底"，规则两份实现（应二选一 + 守卫）
- ⬜ B4 `MissingMatchPolicy.forPlayer` 零调用；B5 `StalledPlayback.evaluate`+`Verdict`
  零调用（生产只用 Tracker，阈值判据两份）；
- ⬜ **B6** `CardSemantics.shouldMergeIntoParent` 生产唯一调用**传常量 true**、
  `actionLabel` 的参数被实现忽略 → 测试断的是生产永不走的路径
- ⬜ B7-B11 低：`WEB_ERROR_ABORTED`、`STATE_IDLE/ENDED`、`PlayerLock.gesturesEnabled`/
  `tapShowsUnlockUi`、`WebNavigationPolicy` 2 参重载、**`AccentContrast.HeroGradientStops`
  是红橙三档的第三份副本**（hero 卡 AA 断言测的是副本，改 Theme.kt 不会红）

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
- **绝不用 `assumeTrue` 静默跳过**：路径一变整片变绿，而"448 全绿"看不出区别。
  现在有元守卫 `GuardHygieneTest` 钉住这条。
- **回退验证必须"编译得过"**：故意删掉被守卫保护的调用（保留注释/声明）才是有效验证；
  把仓库改到编译不过只是"炸了"，不是"守卫红了"。
- **有些测试编码了"生产走不到的时序"**：`PlayerBackStackTest` 原来在 PLAYER 上再调
  `openPlayer()` 来模拟详情页（而 `openPlayer` 当时写死来源=DETAIL）。收紧规则后它红了 ——
  这时要判断是"测试错了"还是"代码错了"：本例是测试错了（生产点不到详情页的线路行），
  已改成走真实路径且**断言不变**。
- 源码一律只用 `read/edit/write` 改；PowerShell 的 `Set-Content`/`-replace` 会写坏中文。
- `--rerun-tasks` 不能省；Gradle 用文件锁串行化；计数在锁内拷 XML。
- 守卫三铁律：① 剔注释；② 匹配调用而非字符串出现；③ 路径失败 `error()`，禁止 `assumeTrue`。
- 并行协议（Agent Teams，本会话已跑两轮）：写范围互斥并记在 task 上；**只有 Lead 提交**；
  只读审计可与写作并行；**最终验收窗口放在所有写者停工之后**。

## 下一次接手的建议顺序
1. `git status` 确认没有未提交的并行工作 / 没有 `TEMP-REVERT` 之类临时标记
2. 跑全量 + `assembleDebug` 建立基线
3. 先做 **B-2 的 B1/B3**（死代码/重复实现，机械且价值高），再做 **A 组残余守卫**，
   最后才碰 **A. 结构性大改**（上帝组件拆分）
4. **C 的产品缺口**需要问用户，不要自行删页面