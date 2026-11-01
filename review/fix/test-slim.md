# 测试体系改造报告（B11 / B12 / B14）

- 基线检查点：`57eb33a`（Lead 报 119 suites / **846 用例 / 0 失败**，`app/src/test` 109 个 kt / **20,317 行**）。
- 范围：**只改 `app/src/test/**`**（一行生产代码未动）。`data/**` 与 `ui/playerfix/**` **只读**（Lead 指定）。
- 未运行 Gradle/adb（Lead 统一跑）。所有结构性改动都用脚本做了"被删文本括号自平衡 + 删除前后括号收支不变 + `@Test`/`fun` 声明数与检查点逐文件对比"的自查。
- 本轮终点实测：`app/src/test` **111 个 kt / 20,373 行 / `@Test` 844**（`@Test` 是"用例数代理"，Lead 的 846 是执行器口径，两者差 2，见 §4）。

---

## 1. B11：抽 `TestPaths`（`user.dir` 99 处 → 1 处）

### 1.1 新增 `TestPaths.kt`（唯一读 `user.dir` 的地方）

`app/src/test/java/com/nbalive/app/TestPaths.kt`（+165 行）。API：

| 成员 | 语义 |
|---|---|
| `workingDir()` / `workingDirPath()` | **全仓唯一**读 `System.getProperty("user.dir")` 的位置 |
| `repoRoot` / `repoFile(relative)` | 从 cwd 与其父目录里找含 `app/src/main/java/com/nbalive/app` 或 `settings.gradle(.kts)` 的那一层，找不到 `error()` |
| `productionJavaRoot` / `productionRoot` / `uiRoot` / `screensRoot` / `themeRoot` / `testRoot` | 各目录；候选**先 cwd 相对、再 repo 相对**，找不到一律 `error()`（铁律③，无 assume 家族） |
| `productionFiles()` / `productionFilesAll()` / `uiFiles()` / `screensFiles()` / `themeFiles()` / `testFiles()` | 文件列表（`productionFiles()` = `extension == "kt"`；`screensFiles()` 保持改造前的**非递归** `listFiles` 语义） |
| `productionFile(name)` / `productionFileAny(name)` / `productionFileAt(relative)` / `testFile(name)` | 唯一文件名 / 含非 kt / 相对 `src/main/java` 的路径定位（0 或 >1 命中都 `error()`，与 `GuardSourceScan.uniqueFile` 同语义） |
| `codeByName(files)` | 文件名 → 原文（各文件 `productionCode()`/`uiRaw()` 的同一形态） |

### 1.2 Phase A：48 个文件、88 处 `user.dir` 统一替换（**语义零变化**）

`System.getProperty("user.dir") ?: "."` → `TestPaths.workingDirPath()`（逐字同义：后者就是
`File(System.getProperty("user.dir") ?: ".").absolutePath`）。**候选列表、顺序、`firstOrNull`、
`error(...)` 文案全部不动** —— 这是纯搬运，改完后：

```
grep 'user.dir' app/src/test  →  仅 TestPaths.kt（+ 只读目录 11 处，见 1.4）
```

### 1.3 Phase B：28 个"纯路径解析函数"委托给 `TestPaths`（−225 行）

**没有**用通用正则批量改（第一次尝试的 dry-run 发现"函数体提取"会把相邻函数一起吞掉，
会静默破坏文件），改成**白名单**（文件 + 函数名 + 目标调用三元组）+ 逐条校验：

- 例：`private fun productionRoot(): File { …9 行… }` → `private fun productionRoot(): File = TestPaths.productionRoot`
- 例：`private fun productionSource(name: String): String {…}` → `= TestPaths.productionFileAny(name).readText()`
- 例：`private fun productionCode(): Map<String, String> {…}` → `= TestPaths.codeByName(TestPaths.productionFiles())`
- 覆盖 `productionRoot`（13 个文件）、`uiFiles/uiSources/productionFiles(ui)`（4）、`screens()`、`themeFile`、
  `productionFile(relative)`（3）、`productionSource`（2）、`vmSource/functionSource/productionCode` 等。

自查（每批后跑）：
- 被删文本**括号自平衡**，否则跳过该条（0 条被跳过）；
- 替换前后**括号收支不变**（28/28 通过）；
- 48 个使用 `TestPaths.` 的文件**全部有 import**（`ui/**`、`security/**` 等非 `com.nbalive.app` 包），0 缺失；
- 28 个委托函数现在都是**单行**表达式。

### 1.4 未替换 / 跳过（诚实清单）

**(a) Lead 指定只读的两个目录**（共 11 处 `user.dir` 未动，请 Lead 统一收）：

| 文件 | 处数 |
|---|---|
| `data/remote/JrkanChannelAdmissionTest.kt` | 2 |
| `data/remote/LoggingDisciplineTest.kt` | 3 |
| `data/remote/SourceZoneDisciplineTest.kt` | 1 |
| `data/repository/DetailCacheLogFixesTest.kt` | 1 |
| `data/repository/SourceEndpointsWiringTest.kt` | 2 |
| `ui/playerfix/PlayerFixTest.kt` | 2 |

**(b) 我 scope 内、但仍保留本地候选列表的 13 个文件**（Phase A 已生效，`user.dir` 已集中；
保留是因为它们的形态不在白名单里，或替换会改变语义）：

`GuardHygieneTest`（`testRoot()` 解析的是 `src/test/java`，与 `TestPaths.testRoot` 的
`.../app` 包目录**不同义**，需再加一个 `testJavaRoot` 才能托管）、`security/LogCallDisciplineTest`、
`security/CleartextPolicyTest`、`security/AndroidConfigVersionTest`、`security/RepoConfigDisciplineTest`、
`security/BuildScriptHygieneTest`、`security/CiWorkflowGuardTest`、`security/WebViewFileAccessTest`、
`ui/AppStructureGuardTest`(已由 §2 的改动顺带委托了 `productionFiles`)、`ui/MatchFilterGuardTest`、
`ui/NavHostGuardTest`(余 `productionFiles()` 单行)、`ui/PlayerLockTest`、`ui/LiveDotAnimationTest`、
`ui/PlayerHealthWiringTest`、`ui/TopBarAndLiveFilterTest`、`ui/uifix/UiFixWiringTest`（我自己的，
下一轮可换成 `TestPaths.productionFiles()/screensFiles()`）。

**非空性锚点**：`AppStructureGuardTest`/`ZeroConsumerGuardTest` 等原有的"生产 kt 文件数 ≥30/≥40"
断言全部保留；B11 没有删除任何范围锚点。

---

## 2. B12：把"数量阈值/写法导向"的守卫换成可测不变量

| 项 | 改前断言什么 | 改后断言什么 | 为什么**仍然是可失败的** |
|---|---|---|---|
| **1. `AppStructureGuardTest` 的 `根组合项函数体不得超过 180 行`** | `fun NbaLiveApp(` 的行数 ≤180（形状/数量阈值；格式化或加注释就会逼近边界，失败文案只说"超了 N 行"） | `根组合项不得内联系统副作用`：根体内不得出现 `LaunchedEffect(` / `DisposableEffect(` / `rememberInfiniteTransition(` / `BackHandler(`（`INLINED_EFFECTS`），**并且**必须真的装配 `AppShell(` + `RouteHost(` | 把任一系统副作用写回根体 → 第一条断言红（这正是 P2-18 要拦的行为：副作用写根体时任何状态抖动都连带整棵 Scaffold/顶栏/底栏重组）；"把根体删空"骗过第一条 → 正向锚点红。原自检改写为 `functionBody 自检 参数表里的默认 lambda 不能骗过它`（测共享 helper，覆盖范围比原来只测 `bodyLineCount` 更大） |
| **2. `NavHostGuardTest` 两条占位宽度断言**（`无更多时的占位必须用 SubPageBarSideSlot` + `顶栏侧位常量必须是 48dp`） | 分别只查"占位写法"和"那个魔数" | **合并为一条**三连断言：① 占位用 `SubPageBarSideSlot` 且无裸 dp；② 该常量 == 48dp；③ 48dp 的**来源**仍在（共享 `SquareIconButton` 调 `.minimumInteractiveComponentSize()`） | 占位写裸 dp / 常量改 40dp / 共享按钮不再撑触摸目标 —— 三段任一断裂即红。第三段是新加的真契约：把 48 抄成常量而来源消失时，原来两条都绿 |
| **3. `GuardSourceScan.functionBody` 遇表达式体直接 `error()`** | `error("…看起来是表达式体函数…本判据不支持")`（只说"不支持"） | 同一 `error()`，但**文案写明契约与修法**：为什么不能放行（表达式体边界靠猜，猜错会把整个文件尾巴当函数体 ⇒ 判据静默变绿）、两条可选修法 | 行为未变（仍硬失败），失败信息从"形状"改成"行为后果 + 可执行修法" |
| **4. `GuardSourceScan.whenBodyWithCall` 的"最内层 `when` 必须唯一"** | 多个最内层候选时 `error("…有 N 个，无法唯一确定判据位置")` | 同一选择器逻辑 + 失败文案给出**候选所在行号**与修法（`让 callee 只出现在目标分支；或抽成具名函数`），并点明它是**选择器**：选错块会把判据锚到别的路由分支 | 选择器逻辑刻意不动（改它会改变 `DeadCodeWiringGuardTest` 等消费者的语义，本轮无编译能力不宜冒险）；唯一性仍是硬失败，且现在能一眼看出是哪两处冲突 |

**明确保留的真契约**（未动）：`definitionViolations`（六个实现不许回写根文件）、
`CardSemantics`/`PrefsStore` 的"唯一实现/唯一消费点"类断言、`AppStructureGuardTest` 的
H5 判据（根体内直接收集 ≤1、`AppUiState` 状态面 ≤6、收集数 ≤ 字段数）。

---

## 3. B14：瘦身（**部分完成**）

### 3.1 已完成：8 份"注释与字符串里的调用不算接线"自检 → 1 份

| 动作 | 文件 | 结果 |
|---|---|---|
| **删除**（同名逐字重复） | `NewFeaturesTest`、`PlayerLoadingHintPolicyTest`、`SourceToggleTest`、`WebPageDeadEndPolicyTest`、`ui/screens/PlayerPageResolverTest`（共 5 条，**−83 行**） | 它们的语义**被更强的判据覆盖**：所有判据都建立在共享 `GuardSourceScan.stripNonCode` / `functionBody` 上，因此改为**直接测共享 helper** |
| **合并进 1 份** | `GuardHygieneTest.扫描器自检 能抓到违规调用且不误报注释与字符串`（在原有断言后追加：`functionBody` 跳过参数表默认 lambda、不吃过函数结尾、`callSites` 不数字符串/注释里的调用） | 覆盖是**超集**：实现漂移时只在这一处红一次（原来 7 处各红一次、且各测各的副本） |
| **保守跳过 2 条** | `PipExitWiringTest`、`SniffRecoveryPolicyTest` | 它们的自检体含带花括号的**原样字符串**样本，我的词法校验器无法证明删除后配平（会假红），**宁可不删** |
| **保留 1 条** | `ManualPipGuardTest.比例判据自检 …且不吃过函数结尾` | 名字与覆盖不同（它还断言"函数体不吃过结尾"，该性质已并入 canonical，但它同时是本地判据的真自检），保留 |

### 3.2 已核实**无需改动**：三套 WCAG 对比度算式

复核结论：**算式已经只有一套**。`AccentContrastTest` / `LogoPlateContrastTest` /
`MatchListContrastDisciplineTest` 三个文件都直接调用**生产**的
`AccentContrast.contrast/luminance/composite`；全测试树不存在 `0.2126` / `0.03928` / `12.92` 等
本地 WCAG 常量，也没有本地 `luminance/contrast` 实现。故本项**零改动**（原报告里"三套算式"
的形态已在更早的轮次收敛于 `ui/theme/AccentContrast.kt`）。

### 3.3 未完成（**停下汇报**，原因 + 建议计划）

| 未做项 | 为什么停 | 建议 |
|---|---|---|
| 合并 7 个接线 suite 里重复的"生产必须真的调用 X"断言（`PipExitWiringTest` / `SniffRecoveryPolicyTest` / `WebPageDeadEndPolicyTest` / `SourceEndpointsWiringTest` / `SourceToggleWiringTest` / `PlayerHealthWiringTest` / `PlayerPageResolverTest`） | 需要新建共享断言 helper 并**逐个重写** 7 个文件的断言点；本轮无编译能力，任何一处签名/作用域失误都会挡住 Lead 的构建（本轮已有两次教训）。逐文件改写要约 30+ 次读改，当前预算不够 | 下一批：先在 `app/src/test/.../WiringAssert.kt` 落 `assertWired(symbol, file, minCalls)` + `assertNotPresent(fragment)`，再按文件迁移（每迁 2 个文件跑一次编译） |
| 行为级替换试点（`PrefsBoundaryGuardTest` 的 5 条、`DeadCodeWiringGuardTest` 一部分） | 同上：这是**判据语义**变更（源码扫描 → 行为断言），必须在能跑测试的环境里逐条确认真红/真绿 | 下一批：先做 `PrefsBoundaryGuardTest` 的 5 条（`InMemoryPrefsStore` 已能承接读/写行为），逐条给出"改前断源码 → 改后断行为 → 回退即红" |
| 2 条跳过的自检（`PipExitWiringTest` / `SniffRecoveryPolicyTest`） | 词法校验器对原样字符串样本不可靠 | 用 `GuardSourceScan` 的 Kotlin 实现做校验（或人工核对后删除） |

---

## 4. 用例数与源码行数的前后对比

| 指标 | 检查点 `57eb33a`（Lead 口径） | 本轮终点（实测，含其他队友同期改动） | 我的改动净额 |
|---|---|---|---|
| 执行用例数 | **846** | 未跑（`@Test` 代理 = **844**，代理与执行口径差 2） | **−5 条**：B14 删 5 条自检；B12 净 0（NavHost 2→1，AppStructure 2→3）；B14 合并把 7 条语义收进 1 条测试内 |
| 测试源码行数 | **20,317** | **20,373**（+56） | 见下 |
| 测试 kt 文件数 | **109** | **111**（+`TestPaths.kt`；另一新增文件是队友的） | +1 |

我的行数明细：**新增 `TestPaths.kt` +165**；B11 Phase B 委托 **−225**；B14 删除自检 **−83**；
GuardHygiene canonical 追加 **+18**；B12（AppStructure 重写 + KDoc、NavHost 合并）**≈ +40**；
Phase A（88 处表达式缩短）≈ **−5**。合计 ≈ **−90 行**（叠加新增文件后总 +56）。

⚠️ **B14 的行数目标（−13%~−17%）未达成**：本轮只做到 −90 行。剩下的大头（7 个接线 suite 合并、
行为级试点）在上表 §3.3，需要能编译的环境分批做。

## 5. 自查与风险

- 每个脚本化改动都做三重校验：① 被删文本括号自平衡；② 文件删除前后**括号收支不变**；
  ③ `@Test` / 顶层 `fun` 声明数与 `57eb33a` **逐文件对比符合预期**（例如
  `NewFeaturesTest -1`、`SourceToggleTest -1`、`NavHostGuardTest -1`、`AppStructureGuardTest +1`
  全部与设计一致；`PlayerFixTest`/`DetailCache*`/`MatchRepository*` 的差异是他人的改动）。
- `@Test` 计数用原始文本正则，会把**原样字符串样本里的 `@Test`** 也数进来，故 844 只作代理。
- **无编译验证**（铁律：禁止 Gradle）：`TestPaths.kt` 的编译风险点已逐条自查
  （`by lazy` 属性、`listFiles { f: File -> … }` 的 SAM 写法、`internal`/`public` 可见性、
  `GuardSourceScan.uniqueFile` 的签名），但最终以 Lead 的编译为准。
- 未触碰任何生产代码；未触碰 `data/**` 与 `ui/playerfix/**`。

## 6. Lead 收尾（编译后实测，与本节前面"未编译"的口径不同）

### 6.1 编译期修掉的两个真问题

1. **`TestPaths.kt` 的注释陷阱**（BP-1）：KDoc 正文里写了通配路径 `ui/**` 与
   `ui/screens/*.kt` —— 其中的斜杠加星号序列会让 Kotlin 把它当成**嵌套块注释的开始**，
   于是文件后半段（`resolveDir`/`ktFiles`/`allFiles`）整段被吞掉，报
   "Unresolved reference 'resolveDir' … Unclosed comment"。**你报告里的"编译风险点已自查"没能覆盖它** ——
   这正是"无编译验证"的代价。已改成中文说法（`ui 目录下递归的 .kt`），并在文件里留了警示。
2. **`productionFileAt` 的口径不一致**（BP-2）：实现按"相对 `src/main/java`"解析，
   而调用方传的是"相对 `com/nbalive/app`"（`ui/screens/LiveNowScreen.kt`、`theme/Theme.kt`）→
   4 条既有测试直接 `error()` 失败。已改成**两种口径都试**（命中即返回，都不命中才报错并打出两条候选）。
   这两处由 Lead 编译后修复，随后 **844 用例全绿**。

### 6.2 B11 收尾（你留的 11 处）

用 `review/fix/b11-leftover.py` 逐模式等价替换 6 个文件：`val cwd = System.getProperty("user.dir") ?: "."`
→ `TestPaths.workingDirPath()`；`File(System.getProperty("user.dir"), rel)` → `File(TestPaths.workingDir(), rel)`；
错误文案里的 `${File(...).absolutePath}` → `${TestPaths.workingDirPath()}`。并补了 5 个文件的
`import com.nbalive.app.TestPaths`。**实测结果：全仓只剩 `TestPaths.kt:46` 一处读 `user.dir`** ✓。

### 6.3 B14 的口径修正（重要）

你如实上报"行数目标未达成"，Lead 复核后认为**原目标本身在本轮不成立**，应记在报告里而不是当欠账：

| 指标 | 检查点 `57eb33a` | 本轮终点 | 说明 |
|---|---|---|---|
| 执行用例数 | 846（debug） | **844** | 新判据净增远大于删除：714 → 844 是**含**新增守卫的总数 |
| 测试源码行数 | 19,972（基线） | **24,694** | **+23%**：本轮把 75 条修复的守卫一起加了进来 |
| 文件数 | 96 | 111 | +15（新守卫与新判据） |

原报告估的"714 → 610~630 用例 / −13~17% 行"是在**不新增任何判据**的前提下算的；
本轮同时新增 **130 条判据**，两个目标互相矛盾。**B14 的真正目标（减少维护点）已达成**：
`user.dir` 99→**1**、重复自检 8→**1**、三套 WCAG 算式复核后**本来就只有一套**（零改动是对的）。
剩余"7 个接线 suite 合并 + 2 组行为级试点"仍按 §3.3 的顺序留给独立一轮。