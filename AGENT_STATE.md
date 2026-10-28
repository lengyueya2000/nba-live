# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）
- 构建/测试：`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`（全量 **448 通过 / 0 失败 / 0 跳过**）
- 验收还要跑 `.\gradlew.bat :app:assembleDebug`
- 公网无关；测试结果 XML 在 `app/build/test-results/testDebugUnitTest/`（**多人并行时会被覆盖，要在锁内拷走再计数**）

## 总任务
按 95 条审查清单（review/00~04）逐条落地修复。
纪律：先 TDD 写纯判据 RED 测试 → 生产代码 → 编译 → 全量测试 → 提交。
**最核心的风险形态是「假安全感」**：写了/测了但生产代码零调用的东西、以及永远不会变红的守卫。

## 本轮已完成并提交（2026-10-28，`7cdf50e` → `29c43b3`，共 5 个提交）
- `29c43b3` **03-P2-21/22/10**：`findActivity()` 抽到 `ui/ContextExt.kt`（原来 NbaLiveApp 递归
  vs Theme.kt 强转两套并存，强转遇包装静默失败）；三份"方形图标按钮"统一到
  `components/SquareIconButton`（视觉 40dp / 触摸 48dp，5 个调用点）；LiveNowScreen
  入参改成**已过滤的** `liveMatches`，删掉内部重复过滤。
  新增 ContextExtTest(4) + TopBarAndLiveFilterTest(6)
- `7828a6b` **03-P2-4/9/16/17/31/35**：删死代码 `onNewIntent`；去掉两个冗余 `remember`；
  「null 怎么解释」收敛到 `AppPrefs.resolveDarkMode`（注释原来宣称"null=跟随系统"，
  与实现相反 —— 已改成真实语义）；UI 层不再持有 Context/写 SharedPreferences
  （改为 `onDarkModeChange`/`onAspectChange` 回调，平台层落盘）；去掉 `AppPrefs.loaded`
  短路并拆出 `darkModeFromStorage`；顶栏占位改 `SubPageBarSideSlot`。
  新增 NavHostGuardTest(11) + DarkModeResolveTest(3)
- `532a7b0` **03-P2-11/12/27/28/29**：回退 lambda 提成 `LogoFallback`；`StatusChip` 的
  remember 移出 `when`；`logoFallbackFontSize` 抵消 fontScale；两套 colorScheme 补齐
  surfaceContainer 系列 9 槽；色板对象收 internal。
  新增 ComponentPerfTest(6) + ThemeSlotTest(10)
- `7fe2ead` **03-P2-8/26/30/32/34 + P2-33(部分)**：日期分组逻辑移到 `MatchFormat.kt`
  纯函数区（dayLabel/groupByDay，可单测）＋ `today` 改 `produceState`+60s ticker
  （原来注释承诺"跨零点自动变新"但实现做不到 —— 注释在撒谎）；hero 卡补
  `onClickLabel`+`Role.Button`；HeroPill 颜色字面量改用 `AccentContrast`；
  三个页面时间口径统一到 `hhmm()`（含播放页 3 处，审查未点名）；
  详情页 96dp 底部留白删除。新增 DayLabelTest(7) + ScreenConsistencyGuardTest(5)
- `7cdf50e` **03-P2-19/20**：`InfoRow` 两份、错误横幅两组、全屏错误态两份收敛到
  `ui/components`；`PartialBanner`/`SignalErrorState` **有意**保持私有（注释写明差异）。
  新增 DuplicateComponentTest(6)

## 已验证落地（不必重做）
P0-1 判死分级、P0-2 m3u8 正则、01-P1-2/3/4/6、01-P2-1/2/3/4/5/6/7/8/10/11/12、
02-P1-1~9、02-P2-1~13、03-P1-1~4、**03-P2-1/4/5/8/9/10/11/12/13/16/17(见缺口)/19/20/21/22/
23/24/25/26/27/28/29/30/31/32/34/35**、04-P1-1~5、04-P2-3/4/6/7(部分)/8/9

## 仍存在的主要缺口
1. **03-P2-2/3/6/15/18**：结构性大改（SavedStateHandle、tab/route 两状态机、
   DisposableEffect 顺序、根节点 collect 6 个 flow、681/820 行上帝组件拆分）。
   要单独一轮做，动 `NbaLiveApp.kt` 为主。
2. **03-P2-17 剩余部分**：UI 仍直接读偏好 `StateFlow`（读没有问题、写已挪到平台层），
   setter 仍带 Context 形参。要走完整"经过 ViewModel"需把偏好读进 `MainViewModel`，
   而它是纯 `ViewModel` → 得改成 `AndroidViewModel`（会牵动 `MainViewModelTest`），
   属结构性改动。
3. **03-P2-14**：**已做有据的取舍**——信号列表保持 `forEachIndexed`，注释写明
   "线路数假设 < 20"与重估条件（本工程无 instrumentation 测试，改 LazyColumn 的
   布局回归没有任何自动化信号）。不是漏做，重估条件写在 `MatchDetailScreen.kt`。
4. **03-P2-7** 判定为**不成立/无效修复**：新 `showSnackbar` 本就会取消上一条，
   显式 `dismiss()` 只是多写一行（已回退）。**别再做这条**。
5. **03-P2-33 剩余**：全量文案硬编码。审查自己判定"按现状不是缺陷"，
   只要求 `dayLabel` 可测（已做）。要本地化才需要动。
6. **04-P2-5** release mapping 归档（仓库外流程，需 CI/发布脚本）。
7. **04-P2-7 彻底版**需升级 AGP；当前已把 `as?` 静默失败改成显式报错。

## 教训（务必遵守）
- **本轮 3 条守卫第一版是「瞎的」**（回退验证只红了 3/7，才发现的）。写正则时别只匹配
  "当前的写法"：
  - 全限定名 `fun android.content.Context.findActivity(` **不**匹配 `fun Context\.findActivity(`
    → 用 `fun\s+[A-Za-z0-9_.]*Context\.findActivity\(`
  - "私有方形按钮"若只匹配 `minimumInteractiveComponentSize() + size()`，就抓不到
    搜索页那份**没有** minimumInteractive 的裸 `.size(48.dp)` —— 而它正是分叉的形态
    → 用"`.size(40|48).dp` 与 `RoundedCornerShape(Radius.btn)` 相邻"这种形状判据
  - `全文件 contains("liveMatches = liveMatches")` 被播放页同样合法的调用点蒙混过关
    → 必须**只看目标调用块**（正则抓 `LiveNowScreen(...)` 的参数区）
  - 结论：**守卫写完必须回退验证**，红了才算数；"看起来会红"不算
- **`git stash push -- <paths>` + 跑测试**是对"重构前的 HEAD"做端到端回退验证的省事办法
  （比手工改一处更可信）；`stash pop` 后核对 diffstat/字节一致。
- 源码一律只用 `read/edit/write` 改。PowerShell 的 `Set-Content` / `-replace`
  会把 UTF-8 中文写坏（本仓库踩过三次）。
- **`--rerun-tasks` 不能省**：Gradle UP-TO-DATE 会让"验证守卫会红"变成假验证。
- 守卫三铁律：① 断言前**剔除注释**；② 匹配**调用**而非字符串出现；③ 路径失败 **error()**，
  **绝不用 `assumeTrue`**（会静默跳过变全绿）。
- 多人并行（本轮用 Agent Teams）：
  - 写范围互斥、每个 task 记 write scope；**只有 Lead 提交**（`git add <明确的路径>` +
    `commit -F 文件`，避免中文引号被 shell 吃掉）
  - Gradle 用文件锁串行化（`$env:TEMP\nba-dsh-gradle.lock` + 20 分钟陈旧判定）
  - 并行写者的 WIP 会让工作区中途变红（先写 RED/放 `TODO()` 桩是正常状态）——
    **最终验收窗口必须放在所有写者停工之后**
  - `app/build/test-results/` 是共享目录，会被别人的 `--rerun-tasks` 覆盖 →
    在锁内拷 XML 再计数

## 下一次接手的建议顺序
1. `git status` 确认没有未提交的并行工作 / 没有 `TEMP-REVERT` 之类临时标记
2. 跑全量 + `assembleDebug` 建立基线
3. 从「仍存在的主要缺口」第 1 条开始（结构性大改），每组照旧：纯判据 RED →
   生产 → 编译 → 全量 → 回退验证 → 提交