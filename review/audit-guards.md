# 守卫审计报告：永远不会红的守卫 & 零消费点的声明

- 审计人：`guard-auditor`（只读，本文件是唯一写入物）
- 审计时间：2026-10-28 19:09 ~ 20:20 (+08:00)
- 基线版本：`HEAD = 9e84d75`（"docs: 更新会话状态（本轮 5 个提交，448 测试全绿）"）
- 基线测试结果（锁内拷贝 `app/build/test-results/testDebugUnitTest/*.xml`，18:52 生成，共 68 个 suite）：
  **`tests=448  skipped=0  failures=0  errors=0`**
  → 结论先行：**当前没有任何守卫是靠 `assumeTrue` 静默跳过才绿的**（skipped=0）。
  下面记的 `assumeTrue` 类问题都是「现在恰好 armed、但一旦路径变化就整片变绿」的**脆弱性**，不是当前已瞎。
- ⚠️ 审计期间工作区被并行改写：`git status --porcelain` = ` M app/src/main/java/com/nbalive/app/ui/MainViewModel.kt`
  + 未跟踪 `ui/NavState.kt`、`test/.../NavRestoreTest.kt`、`test/.../NavStateTest.kt`。
  `MainViewModel.kt` 的行号在 19:15→20:15 之间移动过（同符号三次出现在 224→263→311）。
  本文所有 `MainViewModel.kt` 行号均按 **20:15 的工作区快照** 核对；符号名是主身份，行号可能随后续提交漂移。

## 方法与证据

- 张 sample：`app/src/test/java/**` 全部 **68 个测试类**逐个读过（清单见第四节），不是抽样。
- 判断「能否变红」的方法：读断言 → 写出它的**完整判据（predicate）** → 问"生产侧做哪种改动会让它为假"。
- 对最关键的几条，用**与守卫等价的内存判据**在真实文件上做了「把代码删掉后断言是否仍成立」的模拟
  （只在 `$env:TEMP` 里对拷出的字符串做替换，**没有修改仓库里任何文件**）：

| 文件 | 守卫判据（needle） | 现状 | 删掉生产代码后 | 证明 |
|---|---|---|---|---|
| `screens/PlayerScreen.kt` | `key(webFallbackUrl)` | True | **True** | :370 是注释，:376 是调用；删掉调用行（21 字符）判据不变 |
| `ui/MainViewModel.kt` | `SourceFailureBook` | True | **True** | 只有 KDoc 引用（`规则见 [SourceFailureBook]`），删掉无代码引用仍成立 |
| `ui/MainViewModel.kt` | `isSourceFailed(` | True | **True** | 命中**声明** `private fun isSourceFailed(...)`；删掉两处调用（55 字符）仍成立 |
| `screens/PlayerScreen.kt` | `FIRST_FRAME_WATCHDOG_MS` | True | **True** | 命中 `private const val` 声明；删掉 `delay(FIRST_FRAME_WATCHDOG_MS)` 仍成立 |
| `res/values/themes.xml` | `statusBarColor` | True | **True** | :5 注释含该 token；删掉 `<item>`（69 字符）仍成立 |
| `res/values/themes.xml` | `navigationBarColor` | True | **True** | 同上（73 字符） |
| `ui/NbaLiveApp.kt` | `SquareIconButton(` | True | True | 对照组：这条剔了注释、且是真调用，删调用会变红（**好守卫**） |

---

## 一、确认「永远不会红」的守卫（按严重度排序）

### A1【高】`DeadCodeWiringGuardTest` 的 4 条 raw-`contains` 断言：注释 / 声明即可满足

文件：`app/src/test/java/com/nbalive/app/ui/DeadCodeWiringGuardTest.kt`

| # | 测试 | 断言行 | 判据 | 为什么不可能失败 |
|---|---|---|---|---|
| 1 | `网页播放器必须按 URL 重建 否则切线路会串流` | :96 | `playerScreen.readText().contains("key(webFallbackUrl)")` | **没剔注释**。`PlayerScreen.kt:370` 的注释就写着 `// ⚠️ key(webFallbackUrl) 是必须的…`，:376 才是调用。删掉 :376（1.2-web 层重建 WebView 这个唯一防线）后判据仍 True（实测） |
| 2 | `失败记账的 TTL 必须真的被用上` | :55 | `mainVm.readText().contains("SourceFailureBook")` | 命中 `MainViewModel.kt` 的 **KDoc** `规则见 [SourceFailureBook]`（现 :264）与 `private val failedSources = SourceFailureBook()`（现 :311）。把字段类型改成裸 `Set`、只留注释，断言照样绿 |
| 3 | 同上 | :59 | `text.contains("isSourceFailed(")` | 命中**声明** `private fun isSourceFailed(...)`（现 :317），不是调用（现 :807、:828 附近）。删掉全部调用点仍绿（实测 RemovedChars=55） |
| 4 | `嗅探升级必须校验页面归属` | :108 | `mainVm.readText().contains("belongsToPage")` | 命中**声明** `private fun belongsToPage(...)`（现 :828）。删掉唯一调用（现 :807 `!belongsToPage(referer, …)`）仍绿 |
| 5 | `首帧看门狗阈值不能退回 15 秒` | :74 | `text.contains("FIRST_FRAME_WATCHDOG_MS")` | 命中 `private const val FIRST_FRAME_WATCHDOG_MS`（`PlayerScreen.kt:2195`）声明。**把看门狗整段删掉**（:1697-1700）仍绿；只有"退回裸 `delay(15_000)`"会被 :77-80 那半条抓到 |

**修法（去掉修复就变红）**
原则：铁律①先 `stripComments`、铁律②匹配**调用形状**、铁律③去掉声明与注释引用后再计数。逐条见下。
- #1 改成 `Regex("""key\(\s*webFallbackUrl\s*\)\s*\{""").containsMatchIn(stripComments(text))`。
- #2/#3 改成"**调用点计数**"而不是 contains：先按 `Regex("""\bSourceFailureBook\s*\(""")` 找实例化（≥1），
  再按 `Regex("""\bisSourceFailed\s*\(""")` 去掉 `private fun` 声明行后计数 ≥2。
- #4 改成 `!Regex("""\bfun\s+belongsToPage\s*\(""").containsMatchIn(code) && Regex("""belongsToPage\s*\(""").findAll(code).count() >= 2`。
- #5 改成断言 `Regex("""delay\(\s*FIRST_FRAME_WATCHDOG_MS\s*\)""")` **存在**（当前只查了"常量存在"，没查"常量被用"）。

### A2【高】`AndroidConfigVersionTest.基础主题必须保留系统栏颜色` 被文件自己的注释满足

文件：`app/src/test/java/com/nbalive/app/security/AndroidConfigVersionTest.kt:53-66`
- 断言：`base.contains("statusBarColor")` / `base.contains("navigationBarColor")`，`base` 是
  `app/src/main/res/values/themes.xml` 的 **raw** 文本。
- 该文件第 5 行的头部注释就写着：`⚠️ statusBarColor / navigationBarColor 在 **API 35…**`。
- 实测模拟：删掉 `<item name="android:statusBarColor">…</item>`（themes.xml:14）与 :15 两行后，
  两条断言仍为 `True`（RemovedChars=69/73）→ **删掉这两个（对 API 24~34 仍生效的）属性不会变红**。
- 讽刺的是**同一个类**对 `values-v35/themes.xml` 用了 `stripXmlComments`（:34），并写明"不剔除的话断言会命中注释（写的时候踩到了）"——只是忘了对 base 文件也用。

**修法**：`repoFileOrFail("app/src/main/res/values/themes.xml").readText()` 外面套同一个 `stripXmlComments`；
更强的写法是断言**属性 XML 语义**：`Regex("""<item\s+name="android:statusBarColor"\s*>\s*@android:color/transparent\s*</item>""")`。

### A3【高】`DeadCodeWiringGuardTest` 的 21 处 `assumeTrue` + 全类没有"扫描自检"锚点

- `assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())` 出现在
  :28、:45、:66、:88、:103、:115、:137、:162、:185、:203、:223；
  `assumeTrue("找不到 X.kt，跳过", x != null)` 出现在 :50、:68、:90、:105、:117、:164、:187、:205、:226、:243。
- `productionFiles()`（:265-272）在三个候选路径都找不到时 `return emptyList()` —— **不报错**。
- 该文件**没有**任何"扫到了多少文件"的硬断言；而同类文件都有：
  `DuplicateComponentTest:135`（`files.size >= 10`）、`NavHostGuardTest:219`、`ComponentPerfTest:170`、
  `ThemeSlotTest:243`、`ContextExtTest:74`、`TopBarAndLiveFilterTest:115`、`ScreenConsistencyGuardTest:95`。
- 后果：Gradle 单测工作目录一变（例如仓库改成多模块 / IDE 从根目录跑），**这 11 条测试全部 skipped→绿**，
  而报告里仍然是"448 全绿"。

**修法**：加一条锚点（照抄 `DuplicateComponentTest.扫描必须真的扫到源码`），并把 :28 等 11 处"生产源码为空就跳过"的
`assumeTrue(...files.isNotEmpty())` 换成 `assertTrue("扫不到生产源码，守卫会静默全绿", files.size >= 20)`；
`firstOrNull { it.name == "X.kt" } ?: error("…")`（`NavHostGuardTest.source()` 已是这个写法，可直接复用思路）。

### A4【中】`PlayerLockTest.切场后新的一场可以被正常锁定` 是本地变量自证

文件：`app/src/test/java/com/nbalive/app/ui/PlayerLockTest.kt:251-257`
```kotlin
var lockedMatchId: String? = "match-A"
lockedMatchId = "match-B"                       // 只改了一个局部变量
assertTrue(PlayerLock.isLocked(lockedMatchId, "match-B"))
assertFalse(PlayerLock.isLocked(lockedMatchId, "match-A"))
```
- 两条断言只是 `isLocked` 定义的复述（与 :231-249 重复），**没有任何生产切换逻辑参与**；
  把 `MainViewModel.playMatch` 的"锁随比赛 id 失效"改坏，这条不会红（真正管这件事的是
  `PlayerLockTest:231-249` + `MainViewModel` 的 `lockedMatchId` 传递，但这里没测）。
- 不可能失败的原因：断言对象是测试自己刚赋值的局部 String 与纯函数的相等语义。

**修法**：删掉；若要守"切场解锁"，用 `MainViewModel` 夹具驱动（照 `PlayerBackStackTest.vmWith`）：
`vm.playMatch(m1)` → 记下锁定 → `vm.playMatch(m2)` → 断言锁定态对 m2 不生效。

### A5【中】`MergeStatusTest.合并后 id 必须唯一` 单元素恒真

文件：`app/src/test/java/com/nbalive/app/data/repository/MergeStatusTest.kt:77-86`
- 两场 fixture 的队名/日期完全相同（`match()` 默认 `湖人/勇士/2026-10-24`），merge 后 `merged.size == 1`，
  于是 `assertEquals(ids.size, ids.distinct().size)` 在 size=1 时**恒真**。
- 对比 `MatchRepositoryTest.duplicate ids from both sources never collide in grid`（:127-134）用**不同队名 + 同 id**
  才真正测住去重 —— 同一份仓库里两种写法的差别正好说明这条是瞎的。

**修法**：改成 `jrs=[match(id="same","A队","B队")]`、`jrkan=[match(id="same","C队","D队")]`，
断言 `merged.size == 2 && merged.map{id}.distinct().size == 2`；或至少 `assertTrue(merged.size > 1 && …)`。

### A6【中】`AccessibilitySemanticsTest.呼吸红点不能只靠颜色传达信息` 只断言符号名

文件：`app/src/test/java/com/nbalive/app/ui/AccessibilitySemanticsTest.kt:73-84`
- 断言：`stripComments(common).contains("LiveDot")`。而 `Common.kt:588` 就是 `fun LiveDot(size: Dp…)` **定义**。
- 也就是说：这个"若哪天只留红点，色盲用户无法分辨状态"的守卫，**只要函数名还在就永远绿** ——
  删掉旁边所有"直播中"文字、把红点改成只在颜色上区分，它都不会响。
- 同类：同一文件 :60-71 `标签必须声明选中状态` 是 `contains("selectable(") || contains("selected = ")` 的
  全文件析取；`NbaLiveApp.kt` 里 `selected = ` 至少出现在 :332（分页指示一类）与 :704（底栏）两处，
  断言不绑定底栏。

**修法**：
- A6：断言"LiveDot 的**调用点**所在 composable 同时渲染了 `直播中` 文字"：
  `Regex("""LiveDot\([^)]*\)[\s\S]{0,200}?"直播中"""`（或反过来），或给 LiveDot 加
  `semantics { contentDescription = "直播中" }` 后断言该字符串出现在 `fun LiveDot(` 的函数体里（用现成的 `functionBody()`）。
- 选中态：把断言收进底栏 composable 的函数体（`functionBody(code, "fun BottomBar(")` 之类），
  或断言 `Regex("""\.selectable\(\s*selected\s*=\s*active""")`。

### A7【中】`TopBarAndLiveFilterTest.直播页不得自己再过滤一遍 LIVE` 正则过窄（负向守卫漏判）

文件：`app/src/test/java/com/nbalive/app/ui/TopBarAndLiveFilterTest.kt:93`
```kotlin
!Regex("""filter\s*\{\s*it\.status\s*==\s*[A-Za-z0-9_.]*MatchStatus\.LIVE""").containsMatchIn(code)
```
- 只匹配"lambda 参数恰好叫 `it`、且 `==` 右边以 `MatchStatus.LIVE` 结尾"。
  等价但漏判的写法：`filter { m -> m.status == MatchStatus.LIVE }`、
  `filter { it.status == LIVE }`（`import MatchStatus.LIVE`）、`.filterNot { it.status != … }`、
  `liveMatches.filter { it.status.name == "LIVE" }`。
- 负向守卫漏判**不会变红**，等价于"没有守卫"。这是本仓库历史上"正则只匹配当前写法"的同款形态。

**修法**：改为对**结构**断言：`LiveNowScreen` 内不得出现 `status` 与 `LIVE` 同处一个 filter lambda
（`Regex("""filter(?:Not)?\s*\{[^}]{0,120}?status[^}]{0,120}?LIVE""")`），或更根本地断言
`fun LiveNowScreen(` 的签名有 `liveMatches: List<Match>` 且函数体内不再出现 `.matches`。

### A8【中】`LiveDotAnimationTest.LiveDot 必须接受外部传入的透明度` 参数声明即满足

文件：`app/src/test/java/com/nbalive/app/ui/LiveDotAnimationTest.kt:49-58`，断言 `body!!.contains("alpha")`。
- `fun LiveDot(size: Dp = 6.dp, alpha: Float = LocalLiveDotAlpha.current)` 的**参数声明**就含 `alpha`，
  所以即使函数体根本不用它（红点不呼吸），也绿。
- 生产实际使用点是 `Common.kt:592 .graphicsLayer { this.alpha = alpha }`。

**修法**：断言 `Regex("""\.alpha\s*=\s*alpha\b""")` 出现在 `fun LiveDot(` 的函数体内。

### A9【中】`SourceZoneDisciplineTest.守卫确实能抓到违规写法` 是永真断言

文件：`app/src/test/java/com/nbalive/app/data/remote/SourceZoneDisciplineTest.kt:52-59`
```kotlin
val bad = "java.time.…atZone(java.time.ZoneId.systemDefault())"
assertTrue("必须能抓到 systemDefault()", bad.contains("systemDefault()"))
```
- 断言的是 **Kotlin `String.contains`**，与守卫（:23-50 的 `walkTopDown` 扫描 + 行内 `contains`）无关，
  除非标准库坏掉否则不可能为假。它给人一种"自检过了"的错觉，却完全没验证扫描范围/白名单是否漏文件。
- 同类写法里做得对的是 `MatchListContrastDisciplineTest:71-77` 与 `LoggingDisciplineTest:95-107`
  （它们把**真正的正则常量**喂样本）。

**修法**：把扫描逻辑提成 `internal fun scanViolations(files: List<File>, allow: Set<String>): List<String>`
并让自检传**内存样本文件**（`File.createTempFile`），或直接删掉这条自检并在报告里承认其无价值。

### A10【中】另外 6 个文件的 `assumeTrue` 路径静默跳过（均无 `error()`/数量自检）

| 文件:行 | 跳过条件 | 路径或文件缺失时 |
|---|---|---|
| `app/src/test/java/com/nbalive/app/security/WebViewFileAccessTest.kt:30,61` | `playerScreenText() == null` | 两条断言全绿 |
| `.../security/CleartextPolicyTest.kt:38,57,78` | `configText() == null` | 三条全绿（明文策略守卫消失） |
| `.../ui/screens/MatchListContrastDisciplineTest.kt:56` | `MatchListScreen.kt` 找不到 | 对比度守卫消失 |
| `.../data/remote/LoggingDisciplineTest.kt:61,77` | `ktFiles().isEmpty()` | 脱敏纪律消失 |
| `.../data/remote/SourceZoneDisciplineTest.kt:25` | `sourceRoot() == null` | 时区纪律消失 |
| `.../data/repository/SourceEndpointsWiringTest.kt:160,164` | `root == null` / 生产文件为空 | **正是上一轮 `PLAY_HOSTS` 零消费点漏网的那条守卫** |

对照组（做得对的）：`security/RepoConfigDisciplineTest.kt:92-105`（注释里明说"刻意不用 assumeTrue"）、
`ui/DayLabelTest.kt:148-157`、`ui/ComponentPerfTest.kt:179-193`、`ui/DuplicateComponentTest.kt:146-149`、
`ui/NavHostGuardTest.kt:227-241`、`ui/TopBarAndLiveFilterTest.kt:123-137`（全部 `error()`）。

**修法**：统一改成 `?: error("…守卫会静默全绿")` + 一条"扫到 ≥N 个文件"的锚点。

### A11【低】`MatchRepositoryRetryTest.重试次数有上限` 的上界随候选列表自动放大

文件：`.../data/repository/MatchRepositoryRetryTest.kt:108-114`
```kotlin
val bound = SourceEndpoints.JRS_LIST.size.let { 3 + it - 1 } +
    SourceEndpoints.JRKAN_LIST_JS.size.let { 3 + it - 1 }
assertTrue("失败重试次数必须有上限（≤$bound）…", attempts <= bound)
```
- 判据用的是**同一个候选列表**算出来的上界，注释里恰恰担心"每个镜像都重试 3 次"被镜像数量放大 ——
  但断言跟着放大，所以永远追得上。`assertTrue("至少要尝试过一次", attempts >= 1)` 也近乎恒真。

**修法**：写死与候选数无关的上界（如 `attempts <= 8`），或断言"每个候选 host 的尝试次数 ≤ 3"
（按 host 分组计数）。

### A12【低】`WebMediaPauseScriptTest` 全是字符串子串断言，且不执行脚本

文件：`app/src/test/java/com/nbalive/app/ui/WebMediaPauseScriptTest.kt:26-67`
- 断言 `js.contains("video")` / `"audio"` / `"pause()"` / `"querySelectorAll"` / `Regex("""forEach|for\s*\(""")` / `"if ("`。
- 脚本从不被执行，"子串存在"不等于"真的会暂停"：把 `m.pause();` 注释成 `// m.pause();`，
  `contains("pause()")` 仍为真；把 `list.length` 判断删掉仍含 `if (`。

**修法**：断言前先剔 JS 注释（`//…`、`/*…*/`），并断结构：
`Regex("""querySelectorAll\([^)]*\)[\s\S]{0,200}?for\s*\([\s\S]{0,200}?\.pause\(\)""")`；
或在 CI 里用 Node/QuickJS 真跑一次（`video`/`audio` 各造一个 mock 对象）——那才是"去掉修复就红"。

### A13【低】`StreamSniffOwnershipTest` 负向用例缺前置条件（夹具失效也通过）

文件：`.../ui/StreamSniffOwnershipTest.kt:77-81,106-124`
- `enterWebFallback` 是 `while (…== null && spins < 10_000) { runCurrent(); Thread.yield() }` ——
  到点上界后**静默退出**。若夹具坏掉（根本没进网页兜底），`streamUrl` 本来就是 null，
  `assertNull("不属于当前播放页的流不能被升级")` 会以"通过"掩盖夹具故障。

**修法**：`enterWebFallback` 末尾加 `checkNotNull(vm.playerState.value.webFallbackUrl) { … }`，
或在该测试里先 `assertEquals(WEB_PAGE, vm.playerState.value.webFallbackUrl)`。

### A14【低】`NavHostGuardTest.onNewIntent 不能是只调 setIntent 的死代码` 的早退 + 字面量

文件：`.../ui/NavHostGuardTest.kt:29-44`，`:34` `if (!code.contains("override fun onNewIntent")) return`。
- 设计意图是"删掉了就算达标"，但配合 `contains` 的字面量匹配，`override` 与 `fun` 之间换行
  （Kotlin 合法写法）或 `override fun onNewIntent(intent: Intent?)` 之前插入注解仍会漏；
  更重要的是**这条测试当前永远绿**（方法已删），它唯一的作用域是"以后有人加回来且写在同一行"。

**修法**：`Regex("""\boverride\s+fun\s+onNewIntent\b""")`；并保留早退语义但加注释与
"若存在则必须读 intent"的强断言（已有 `INTENT_READ`）。

### A15【低】`DayLabelTest` 两条源码断言是"文件级谓词"

文件：`.../ui/DayLabelTest.kt:109,113,127`
- `code.contains("produceState")`、`code.contains("delay(DAY_LABEL_TICK_MS)")`、`code.contains("LocalDate.now(systemZone)")`
  —— `MatchListScreen.kt` 里任意位置满足即可，不绑定"今天/明天"那个 `today` 状态。
  （同类写法 `ScreenConsistencyGuardTest` 已用 `functionBody()` 收敛，这里没有。）

**修法**：`functionBody(code, "fun MatchListScreen(")`（该仓库已有两种 functionBody 实现可复用）后断言。

### A16【低】`AccessibilitySemanticsTest` 的文件定位按 `name` 取第一个

`AccessibilitySemanticsTest.kt:93` `root.walkTopDown().firstOrNull { it.isFile && it.name == name }?.readText()`
—— 若出现同名文件（`ui/` 与 `ui/screens/` 各一份），会静默取遍历序第一个。路径失败本身是硬失败（:30/:46/:62
`assertTrue("找不到 X.kt", code != null)`），这点是好的；只是"找到哪个"不确定。

---

## 二、确认「零消费点」的声明

判定口径：`app/src/main/java/**` 内**除声明自身与注释引用之外的引用**为 0，且引用只出现在 `app/src/test/**`。

### B1【高】`PipState.shouldKeepPip` —— 整个 `PipExitOnErrorTest` 测的是没人调的函数

- 声明：`app/src/main/java/com/nbalive/app/ui/PipState.kt:96` `fun shouldKeepPip(hasStream, hasPlaybackError) = hasStream && !hasPlaybackError`
- 生产引用：**零**。`grep -n "shouldKeepPip" app/src/main/java` 只命中：声明行，以及
  `ManualPipPolicy.kt:26`、`PlaybackHealth.kt:26`、`MainViewModel.kt:113` **三处注释**。
- 测试引用：`app/src/test/java/com/nbalive/app/ui/PipExitOnErrorTest.kt:33,41,48,51`（整个类 3 条用例全测它）。
- 旁证（文件自己承认过）：`PlaybackHealthTest.kt:17-19` 写"旧链路的处置条件是
  `PipState.shouldKeepPip(hasStream = false, hasPlaybackError = true)` —— 传的是常量 false/true，
  结果恒为 false，所以 v1.2.9 之后『小窗里播不出来就收窗』其实**从来没生效过**"。
  现在生产真正的小窗处置走 `PlaybackHealth.pipDisposition`（`MainActivity.kt:250-252`）与
  `PlaybackHealth.blocksAutoEnterPip`（`NbaLiveApp.kt:200`）。
- 建议：**删函数 + 删 `PipExitOnErrorTest`**（或把该类的 3 条用例改写到
  `MainActivity.finishPipIfBroken(level)` 上：`SUSPECT → 不收窗`、`DEAD → 收窗`），
  否则"448 全绿"里永远有 3 条在守护一段线上跑不到的代码。
- 判据写法（若决定保留并接线）：源码扫描断言 `PipState.shouldKeepPip(` 在 `src/main` 里的**调用点** ≥1
  （排除声明行），并同时断言 `MainActivity.finishPipIfBroken` 用了它的返回值做分支。

### B2【高】`PlaybackHealth.showsSuspectHint` —— "疑似无信号"提示从未接线

- 声明：`app/src/main/java/com/nbalive/app/ui/PlaybackHealth.kt:222` `fun showsSuspectHint(level) = level == Level.SUSPECT`
- 生产引用：**零**（`grep -n "showsSuspectHint" app/src/main/java` 只有声明行与上方 KDoc）。
- 测试引用：`PlaybackHealthTest.kt:98-101`（纯函数断言，不是接线断言）。
- 与文档矛盾：`PlaybackHealth.kt:20-24` 与 `:216-219` 说 `SUSPECT`"只用于**显示提示** + 停掉按 Home 自动进小窗"；
  生产只实现了后半（`NbaLiveApp.kt:200`），前半没有任何 UI 消费者 →
  用户看到的现象就是"嗅探超时（推断）后界面毫无提示"。这是 review 反复点名的
  `partialError` 同款形态（**算好了、测了、没人用**）。
- 建议：**接线**（在 `NbaLiveApp` 或播放页根据 `playbackLevel == SUSPECT` 显示一句"疑似无信号，可换线路"），
  或**删函数并修正注释**（注释不许再声称它会显示提示）。
- 判据写法：源码扫描断言 `showsSuspectHint(` 在 `src/main/.../ui/screens/**` 或 `NbaLiveApp.kt` 有调用点，
  且该调用点出现在一个渲染分支里（`{\s*if\s*\(\s*PlaybackHealth.showsSuspectHint`）。

### B3【高】`PipAspectReset.ratioFor` —— 生产自己另写了一份"视频比例 + 16:9 兜底"

- 声明：`app/src/main/java/com/nbalive/app/ui/ManualPipPolicy.kt:73`（`PipAspectReset.ratioFor(width, height)`，
  含"宽高非法回退 16:9、绝不返回 NaN/负数"的注释）
- 生产引用：**零**。`grep -n "ratioFor" app/src/main/java` 只有声明行。
- 测试引用：`ManualPipGuardTest.kt:99,105,113`（3 条用例）。
- 重复实现（真实消费点）：`MainActivity.kt:156-168 pipAspectRatio()`
  —— 自己读 `PipState.videoSize.value`，自己写 `if (w <= 0 || h <= 0) return Rational(16, 9)`、
  自己夹取上下限（`MAX_PIP_RATIO`）、自己约分。**"视频比例"这条规则在生产里有两份实现，
  被测试钉住的那份没人用**（正是 `PlayerLock.kt:53` 注释里批评过的模式）。
- 建议：`pipAspectRatio()` 内部先 `val ratio = PipAspectReset.ratioFor(w, h)` 再据此夹取/约分；
  或删 `ratioFor` 并把那 3 条用例改测 `pipAspectRatio`（需要把 `Rational` 造出来，JVM 可用 stub）。
- 判据写法：源码扫描断言 `MainActivity.kt` 里出现 `PipAspectReset.ratioFor(`（调用而非声明）。

### B4【中】`MissingMatchPolicy.forPlayer` —— 只有测试在调

- 声明：`app/src/main/java/com/nbalive/app/ui/BackPolicy.kt:111`
- 生产引用：**零**。`NbaLiveApp.kt:400-401` 只用 `MissingMatchPolicy.forDetail`；
  `Route.PLAYER -> PlayerScreen(match = playerState.match, …)`（:422-424）直接把可空 match 交给播放页自查。
- 测试引用：`BackAndMissingDataTest.kt:126-129`。
- 建议：要么在 `Route.PLAYER` 分支接线（播放页缺 match 时给占位而不是交给 `PlayerScreen` 内部处理），
  要么删函数 + 删那两条用例；不要保留"文档承诺了但没人用"的第三个分支。
- 判据写法：源码扫描断言 `MissingMatchPolicy.forPlayer(` 在 `src/main` 出现 ≥1（调用）。

### B5【中】`StalledPlayback.evaluate` + `Verdict` —— 生产只用 Tracker，判据被重写了一份

- 声明：`app/src/main/java/com/nbalive/app/ui/StalledPlayback.kt:49-58`（`enum Verdict`）、`:67-71`（`evaluate`）
- 生产引用：**零**。真实消费点是 `PlayerScreen.kt:1724-1738`：`StalledPlayback.Tracker()` +
  `tracker.onProgress(...)` + `tracker.onTick(...)`（返回 Boolean）。
  `grep -n "Verdict\." app/src/main/java` 只命中 `StalledPlayback.kt` 自身。
- 测试引用：`StalledPlaybackTest.kt:34,50,63,75,90`（前 5 条用例全测 `evaluate`；
  后 3 条测 `Tracker` 才是有效覆盖）。
- 风险：阈值/就绪判据在 `evaluate`（:67-71）与 `Tracker.onTick`（:98-115）**各写一份**
  （`STALL_THRESHOLD_MS` 与 `nowMs - since < thresholdMs`）；改一处不影响另一处，
  而测试对两处都有覆盖，看起来"都测了"。
- 建议：删 `evaluate`/`Verdict`（把 `Verdict` 语义折进 `Tracker.onTick` 的返回值文档），
  或让 `Tracker.onTick` **委托** `evaluate`（`evaluate(...)==STALLED` 时才计时上报）。
- 判据写法：源码扫描断言 `StalledPlayback.evaluate(` 在 `src/main` 出现 ≥1，或断言
  `Tracker.onTick` 体内出现 `evaluate(`（用现成的 `functionBody`）。

### B6【中】`CardSemantics` 的两个参数：一个生产传常量、一个被实现忽略

- `app/src/main/java/com/nbalive/app/ui/CardSemantics.kt:27` `fun shouldMergeIntoParent(hasInnerButton) = hasInnerButton`（恒等函数）
  - 唯一生产调用：`LiveNowScreen.kt:219` `mergeDescendants = CardSemantics.shouldMergeIntoParent(hasInnerButton = true)` —— **传常量 true**。
  - 测试 `CardButtonSemanticsTest.kt:39` 断言 `hasInnerButton = false → false`，即断言的是**生产永不走的路径**。
    （和 `PlaybackHealthTest` 记载的 `shouldKeepPip(常量)` 旧 bug 完全同形。）
- `CardSemantics.kt:35` `fun actionLabel(hasInnerButton) = "播放比赛"` —— **参数被完全忽略**；
  生产两处调用分别传 `true`（`LiveNowScreen.kt:223`）与 `false`（`MatchListScreen.kt:301`），返回同一字符串。
- 建议：要么让参数真的参与判定（`LiveNowScreen` 的卡确实有内嵌按钮 → true；`MatchListScreen` 的卡若无内嵌按钮 → false 并让它影响 mergeDescendants），
  要么删参数并在调用点直接传字面量。当前形态是"参数挡在签名里制造已区分的假象"。
- 判据写法：源码扫描断言生产调用点的实参**不是字面量**：
  `Regex("""shouldMergeIntoParent\(\s*hasInnerButton\s*=\s*(true|false)\s*\)""")` 不得命中（反向断言）；
  或断言 `actionLabel(` 的实参来源是 `hasInnerButton` 这类局部变量/表达式。

### B7【低】`PlaybackHealth.WEB_ERROR_ABORTED` 只被测试引用

- 声明：`PlaybackHealth.kt:75`（连同说明"导航被取代不是失败"的 KDoc）。
- 生产引用：**零**（它**不在** `FATAL_WEB_ERRORS` 里，`PlayerScreen.kt` 也不引用它 —— 这是设计）；
  测试引用：`PlaybackHealthTest.kt:127`。
- 性质：这是一条"为了文档/测试而存在的常量"。建议保留但**注明用途**，
  或改由 `isConfirmedWebFailure(-3, …) == false` 表达（删常量）。
- 判据写法：`WEB_ERROR_ABORTED` 若保留，应有一条**负向**测试在语义层（已有 `ERR_ABORTED 不判死`），
  源码层面无需消费点断言 —— 记为"已知的有意零消费"。

### B8【低】`StalledPlayback.STATE_IDLE` / `STATE_ENDED` 只被测试引用

- 声明：`StalledPlayback.kt:35`、`:38`；生产引用零（生产只传真实 `player.playbackState`）。
  测试：`StalledPlaybackTest.kt:86`。
- 建议：删掉这两个常量（用 `1`/`4` 字面量会损害可读性，可改为 `@VisibleForTesting` 标注），
  或将 `检测暂停和结束状态不算卡死` 改为直接传 `1`/`4` 并注释对齐 `Player.STATE_*`。

### B9【低】`PlayerLock.gesturesEnabled` / `tapShowsUnlockUi` 只作测试 oracle

- 声明：`PlayerLock.kt:34`、`:149`；生产引用零（`PlayerLock.kt:53-58` 自己写明"以前是死函数…
  现在 PlayerScreen 调这一个函数"即 `gestureConfig`）。
- 测试引用：`PlayerLockTest.kt:35,40,121,126,213`；其中 :113-130 用它们作为 `gestureConfig` 的
  一致性 oracle（这条一致性断言本身是有价值的）。
- 建议：保留即好，但为免下一个读代码的人误以为生产在用，应在 KDoc 上标 `@VisibleForTesting` 语义
  （或直接把 `gestureConfig` 的单项视图暴露为 `GestureConfig` 的 extension，让"没有第二个入口"显式化）。

### B10【低】`WebNavigationPolicy.shouldBlock(url, currentHostPageUrl)` 2 参重载只给测试用

- 声明：`WebNavigationPolicy.kt:73-74`（注释自称"简化签名：只给测试和已知主框架的场景用"）。
- 生产引用：零（`PlayerScreen.kt:1982` 用 3 参的 `blockReason`；3 参 `shouldBlock` 由它内部调用）。
- 测试引用：`WebNavigationPolicyTest.kt` 全部 8 条。
- 建议：删掉重载并让测试显式写 `isMainFrame = true`（默认值已等价），或标注 `@VisibleForTesting`。

### B11【低】`AccentContrast` 的 `HeroGradientStops` 是**第三份**渐变色副本 —— 对比度守卫测的是副本

- 声明：`app/src/main/java/com/nbalive/app/ui/theme/AccentContrast.kt:53-57`
  `listOf(Color(0xFFFF2E4D), Color(0xFFFF6B2C), Color(0xFFFF8A3D))`；
  另外两份同源字面量在 `theme/Theme.kt:107`（`GradLive`）与 `:112`（`GradLiveOnWhite`，生产真正用的那条，
  `MatchListScreen.kt:294 .background(GradLiveOnWhite)`）。
- 生产引用：`HeroGradientStops` **零**（`grep -n "HeroGradientStops" app/src/main/java` 只有声明行）；
  `composite`（:75）、`luminance`（:87）、`contrast`（:96）同样零生产引用（生产用的是
  `AccentContrast.accentStops`（Theme.kt:125）与 `OnAccent`/`HeroPillBackground`）。
- 测试引用：`AccentContrastTest.kt:42,53,67`（3 条"hero 卡 AA 4.5:1"用例全部遍历 `HeroGradientStops`）。
- 后果：**改 `Theme.kt:112` 的那三档 → 界面 hero 卡对比度可能掉出 AA，而 3 条断言照样绿。**
  这属于"守卫测的是一份平行副本"的假安全感。
- 建议：把三档提成**单一来源**（例如 `Theme.kt` 里 `val LiveGradientSource = listOf(…)`，
  `GradLive`/`GradLiveOnWhite`/`AccentContrast.HeroGradientStops` 都从它派生），
  再让 `AccentContrastTest` 断言这条共享源 + 断言 `MatchListScreen` 用的是 `GradLiveOnWhite`
  （源码扫描：`Regex("""\.background\(\s*GradLiveOnWhite\s*\)""")`）。
- 判据写法：`HeroGradientStops` 必须 `= LiveGradientSource.map { it.whiteTextSafe() }` 这类派生式，
  并加一条"生产侧 hero 卡背景就是 GradLiveOnWhite"的调用点断言。

### B12【低·已知代价】`PlayerControls.shouldShowBottomBar` 的两个 `@Suppress("UNUSED_PARAMETER")` 参数

- `PlayerControls.kt:53-60`：`streamUrl` / `webFallbackUrl` 有意不参与判定，KDoc 已承认这是 P2-10 的"假安全感"，
  并说明保留理由（自文档 + 将来扩展）。
- 这条**不算缺陷**：`WebFallbackControlsTest.kt:131-171` 用 4 种 URL 组合 × 全部布尔组合做
  `results.toSet().size == 1` 的等价性断言，真的能做到红（也不是"文件级 contains"）。
- 记录在此，供以后判断"参数零消费"时区分**有判据兜底的有意零消费**与**没人发现的无意零消费**。

---

## 三、未验证的怀疑（需要什么证据才能确认）

> 下面每条都写清了"我已经验证到哪一步"和"还缺哪一次实验"。**它们没有被算进第一、二节的结论。**

1. **测试夹具用设备默认时区、生产按 `Asia/Shanghai` 解释 → 只有在非东八区机器上才会暴露。**
   涉及 `JrkanParserTest.kt:258-271`（`ZonedDateTime.now(systemDefault()).minusHours(5/1)` 造 label，
   生产按 `SOURCE_ZONE` 判定）、`:293-334`（用 `systemDefault()` 读回解析结果）。
   证据需求：`testOptions.unitTests.all { systemProperty("user.timezone", "UTC") }` 后跑
   `./gradlew :app:testDebugUnitTest --tests '*JrkanParserTest' --rerun-tasks`；若变红，
   夹具应改用 `MatchStatusClock.SOURCE_ZONE` 构造/读回。**我不能改任何构建或测试文件，所以未做。**

2. **`SourceEndpointsTest.kt:77-79` 用的是 Kotlin `assert()`（`-ea` 才生效），不是 JUnit 断言。**
   `grep -n "enableAssertions" app/build.gradle.kts` → 0 命中（未显式配置）。
   Gradle `Test` 任务默认 `enableAssertions = true`，所以当前**有效**；
   一旦有人加 `enableAssertions = false`（或换执行器），`候选列表不能为空` 这 3 行会静默消失。
   证据需求：打印该任务的 `enableAssertions` 值，或临时置 false 重跑确认"不报错"。修法：换 `assertEquals/assertTrue`。

3. **CI 慢机上的时间/线程相关用例可能红（不是恒绿，但会假红）。**
   `LoadDeadlineTest.kt:27-45`（真实墙钟，`elapsedMs < 5_000`，deadline 300ms）、
   `HttpClientFetchTest.kt`（真实 loopback `ServerSocket`，沙箱禁网即失败/挂）、
   `StreamSniffOwnershipTest.kt:77-81` / `MainViewModelTest.kt:61-69`（`Thread.yield()` 自旋）。
   证据需求：在受限网络 + 高负载下重复跑 10 次，统计 flake。建议 `LoadDeadlineTest` 放宽到 2s 或改虚拟时间。

4. **`LiveStatusCorrectionTest.kt:143-160` 依赖机器时钟晚于夹具日 2026-10-24。**
   该用例不传 `now`（对比 :162-179 那条显式传了 `now`），夹具 `startAtMillis` 固定为 2026-10-24 00:00。
   今天（2026-10-28）机器时钟晚于它 → `FINISHED` 成立；把系统日期设回 2026-10-24 之前即会红。
   证据需求：改夹具为相对日期（`LocalDate.now().minusDays(1)`）后重跑。

5. **`MainViewModelTest.kt:43-44` 的 `liveIds` 是测试侧**重实现**的 LIVE 过滤，
   生产过滤在 `NbaLiveApp.kt:133-135`（`listState.matches.filter { it.status == MatchStatus.LIVE }`）。**
   两者当前逐字一致，但生产侧一旦加条件（例如排除延期场次），这 2 条用例不会红。
   证据需求：临时给 `NbaLiveApp.kt:134` 加一个条件重跑（**需要写者动手**，只读审计不能改生产）。

6. **`stripComments` 有"严格"与"宽松"两套实现并存。**
   宽松版（只把"整行注释"置空，行尾 `// …` 与行内 `/* … */` 保留）：`DeadCodeWiringGuardTest:257-263`、
   `NavHostGuardTest:290-296`、`TopBarAndLiveFilterTest:140-146`、`AccessibilitySemanticsTest:96-102`、
   `DayLabelTest:159-165`、`DuplicateComponentTest:203-209`、`ScreenConsistencyGuardTest:156-162`、
   `LiveDotAnimationTest:115-121`、`ContextExtTest:92-98`、`WebViewFileAccessTest:47-53`、
   `MatchListContrastDisciplineTest` 无、`LoggingDisciplineTest:43-55`。
   严格版（词法级，处理块注释/字符串）：`ComponentPerfTest:274-319`、`ThemeSlotTest:268-313`。
   证据需求：逐条验证"行尾注释是否能让某条断言为真"（我未逐条构造反例）。
   建议统一到严格版实现（两份已经写好了，抽到 test 源集里的 `TestSources.kt` 共享）。

7. **`AccessibilitySemanticsTest.productionCode()` 按文件名取第一个**（:93）；
   当前 `ui/` 下无重名文件，未验证"新增同名文件"的影响。证据需求：临时加一个同名文件后跑。

---

## 四、覆盖范围声明

**基线证据**：锁内拷贝 `app/build/test-results/testDebugUnitTest/*.xml` 到 `$env:TEMP\guard-audit-xml`
（68 个 XML，18:52），用 `[System.Text.Encoding]::UTF8.GetString(ReadAllBytes(...))` 读，
解析出 `tests=448, skipped=0, failures=0, errors=0`。

### 4.1 实际读过的测试类（68/68）

| 目录 | 类（全部逐个读过） |
|---|---|
| `data/model` (4) | LeagueInferenceTest、MatchStatusClockTest、MatchStatusTest、PostponedMatchTest |
| `data/remote` (10) | HttpClientFetchTest、JrkanParserTest、JrsParserTest、JrsStatusCorrectionTest、LoggingDisciplineTest、RedactUrlEdgeCaseTest、RedactUrlTest、SourceTimeZoneTest、SourceZoneDisciplineTest、StreamUrlResolverTest |
| `data/repository` (12) | DetailBudgetTest、DetailSingleFlightTest、LiveStatusCorrectionTest、LoadDeadlineTest、LoadOutcomeTest、MatchRepositoryRetryTest、MatchRepositoryTest、MergeKeyFallbackTest、MergeStatusTest、SignalIdUniquenessTest、SourceEndpointsTest、SourceEndpointsWiringTest |
| `security` (4) | AndroidConfigVersionTest、CleartextPolicyTest、RepoConfigDisciplineTest、WebViewFileAccessTest |
| `ui` (32) | AccessibilitySemanticsTest、AudioFocusPolicyTest、BackAndMissingDataTest、CardButtonSemanticsTest、ComponentPerfTest、ContextExtTest、DarkModeResolveTest、DayLabelTest、DeadCodeWiringGuardTest、DuplicateComponentTest、FullscreenOrientationTest、ListNoticePolicyTest、LiveDotAnimationTest、MainViewModelTest、ManualPipGuardTest、NavHostGuardTest、PipAutoEnterTest、PipExitOnErrorTest、PlaybackHealthTest、PlayerBackStackTest、PlayerLockTest、PostponedLabelTest、RefreshRaceTest、ScreenConsistencyGuardTest、SourceFailureBookTest、StalledPlaybackTest、StreamSniffOwnershipTest、TopBarAndLiveFilterTest、WebFallbackControlsTest、WebFullscreenAspectTest、WebMediaPauseScriptTest、WebNavigationPolicyTest |
| `ui/screens` (3) | MatchListContrastDisciplineTest、PlayerGestureMathTest、VideoResolutionTest |
| `ui/theme` (3) | AccentContrastTest、ThemeContrastTest、ThemeSlotTest |

> 说明：`data/remote` 一行里我把 10 个类名列出（共 10 个类：HttpClientFetchTest、JrkanParserTest、
> JrsParserTest、JrsStatusCorrectionTest、LoggingDisciplineTest、RedactUrlEdgeCaseTest、RedactUrlTest、
> SourceTimeZoneTest、SourceZoneDisciplineTest、StreamUrlResolverTest）。上表 68 = 4+10+12+4+32+3+3。

### 4.2 读过的生产代码

- `ui/` 全部 **34 个文件**：其中 `BackPolicy`、`SystemBarsPolicy`、`ManualPipPolicy`、`PipState`、
  `StalledPlayback`、`PlaybackHealth`、`PlayerControls`、`PlayerLock`、`AudioFocusPolicy`、
  `WebNavigationPolicy`、`CardSemantics`、`StatusLabel`、`MatchFormat`、`WebMediaPauseScript`、
  `SourceFailureBook`（grep）、`ListNotice`（grep）、`ContextExt`（grep）、`Match`/`MatchStatusClock`（grep）
  全文读过；`NbaLiveApp.kt`（787 行）定点读 :123-147、:380-424、:750-790（grep 定位）；
  `PlayerScreen.kt`（2220 行）定点读 :370-380、:508-516、:1680-1740、:1715-1774、:1920-2030、:2190-2200；
  `Common.kt`/`Theme.kt` 定点读被断言涉及的区域。
- `data/repository/SourceEndpoints.kt` 全文；`data/repository/MatchRepository.kt` 只看被守卫/证据涉及的行段
  （:324-325、:559、:648-672、:1004-1027 等）。
- `MainActivity.kt` :120-268 全文；`res/values/themes.xml`、`res/values-v35/themes.xml`、
  `res/xml/network_security_config.xml`、`AndroidManifest.xml`（grep）全文/定点；
  `app/build.gradle.kts`（grep `enableAssertions|testOptions`）。

### 4.3 明确**没**看的范围（及其影响）

- `data/remote/JrsParser.kt`、`JrkanParser.kt`、`HttpClient.kt`、`StreamUrlResolver.kt`、`Logs.kt`、
  `FetchResult.kt` 的完整实现：只通过测试与 grep 交叉核对，**没有逐个函数做"零消费点"扫描**。
  → 二节的零消费点结论**不覆盖 data 层内部函数/常量**，只覆盖了 `SourceEndpoints`（上一轮点名处）。
- `MatchRepository.kt`（1100+ 行）其余实现、`MainViewModel.kt`（800+ 行）其余实现：
  未逐行审计，因此"未声明的死代码"可能仍藏在里面。
- `app/src/androidTest/**`（若存在）未看；`NbaLiveApplication.kt`、`ProfileScreen.kt`、
  `PlayerGestureLayer.kt`、`VideoResolution.kt`、`PipState` 之外的 `ui/` 细节未读全。
- 我没有跑 Gradle（未做 `--rerun-tasks` 全量），只读了 18:52 生成的 XML 基线。
  审计期间工作区已有并行改动（见开头），因此**最终验收窗口必须由 Lead 在所有写者停工后重跑**。
- 未做任何"回退生产改动 → 变红"的实测（我是只读审计者，不允许改生产/测试代码）：
  第一节的"删掉代码后判据仍成立"是对**判据本身**的内存模拟（第七行表格），
  不是真实的 RED/GREEN 实验。真实回退实验（改生产 → 观察红）应由写者执行。