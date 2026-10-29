# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）
- 构建/测试：`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`（全量 **566 通过 / 0 失败 / 0 跳过**，
  debug 与 release 两个变体同数）。**单测 JVM 时区已钉死为 UTC**（见 `app/build.gradle.kts` 的
  `testOptions`，审计「怀疑 #1」的落地），所以验收口径是"**UTC 下**全绿"，比东八区更严格。
- 验收还要跑 `.\gradlew.bat :app:assembleDebug`
- 测试结果 XML 在 `app/build/test-results/testDebugUnitTest/`（**多人并行时会被覆盖，要在 Gradle 锁内拷走再计数**；XML 带 BOM，用 `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 读）

## 总任务
按 95 条审查清单（review/00~04）逐条落地修复。
纪律：先 TDD 写纯判据 RED 测试 → 生产代码 → 编译 → 全量测试 → 提交。
**最核心的风险形态是「假安全感」**：写了/测了但生产零调用、以及永远不会变红的守卫。

## 最近两个回合的提交
### 2026-10-29 第八回合（用户紧急反馈）：明文策略误伤 → 「只有主播线路能看、其他线路全黑」
1 个提交 + 发版 **v1.2.16**（`45fdb89`）。全量 debug+release 各 **567/0/0**。
| 提交 | 内容 |
|---|---|
| `45fdb89` | **回归定位与修复**：用户实测「比赛能播，但只有主播线路（直连）能看，pao/mgxl/wen/wlive 全黑」，并指出**v1.2.12 能看** → 同机同场同坐标 A/B：v1.2.12 视频带 99.5% 非黑 / v1.2.13 整屏 `mean=0.0`；`isDirectHls` 两版一致、356/368 播放页均正常 → 问题在网页播放链。加探针拿到直接证据：`ERR main=false code=-1 desc=net::ERR_CLEARTEXT_NOT_PERMITTED url=http://im-imgs-bucket.oss-accelerate.aliyuncs.com/pjs.js` —— **源站用来创建播放器 iframe 的脚本被 WebView 拒绝** → 零媒体请求 → 黑屏。根因：**04-P1-5 的明文收紧（v1.2.13）把该主机列进禁明文**，只考虑了"App 自己以 https 访问它"，漏了"WebView 里的源站页面以 http 从它加载播放器脚本"。修法：该主机**显式允许明文**（配置 KDoc 写清事故证据），`jrszbt.com` 继续禁；`CleartextPolicyTest` 改成**双向**（新增「WebView 承载的源站脚本主机必须允许明文」，回退即红 1 条）。验证：链路跑通（pjs.js→paps.html→xgplayer→真实 m3u8→ExoPlayer 初始化，画面 99.8% 非黑）、R8 release 构建同样跑通、发布包实测有画面 |

**教训（已写进配置与守卫 KDoc）**：收紧安全边界前必须列出**所有发起者** ——
同一主机可能既被 App 以 https 访问，又被 WebView 里的第三方页面以 http 访问；
后者是**功能硬需求**，前者才是要防的降级风险。

**本轮挂起未做（在共享任务板上，下一轮继续）**：`task-22`（给 6 条"改了没人红"的条目补护栏，
含 P1 的 03-P1-3 与需实验的 04-P2-8）、`task-23`（04-P2-7 去 AGP 内部类依赖）。
另：模拟器现装 **v1.2.16 release**、全局代理已清空。

### 2026-10-29 第七回合（goal round 6）：95 条清单覆盖台账 + 03-P2-17/03-P2-30 收尾 + CI 预检
4 个提交。全量 debug+release 各 **566/0/0**（80 suites）+ `assembleDebug` 成功。
| 提交 | 内容 |
|---|---|
| `7f0d2d6` | **`review/coverage-ledger.md`**：把 01~04 的 P0/P1/P2 编号条目**逐条**核成 已落地/有据取舍/未落地/无法判定，每条附**可复核证据**，并宣布**不把本文件当证据**。口径先说清（01:19 + 02:23 + 03:4+35 + 04:14 = **95**）。结果 **85 / 8 / 2 / 0**；点名本文件 3 处错（03-P2-30 被错记入落地区间、03-P2-17 自相矛盾、02-P2-13 口径错）——**均已按此修正**。末尾第四节挑出 6 条"改了没人红"（含 **03-P1-3** 这条 P1 零信号）与 1 处守卫白名单洞 |
| `48a3f3a` | **03-P2-30 收尾**：队徽圆底硬编码白（浅色主题压白卡 **1.00:1**，边缘消失）→ 主题 token `SportLight.LogoPlate=#B8C0D4`（**1.82:1**）/ 深色仍纯白（**16.83:1**）+ 纯函数 `logoPlateFor` + `CompositionLocal`；守卫 `LogoPlateContrastTest`(7)，回退即红 ×2（组件改回白 → 1 failed；token 改回白 → 2 failed） |
| `db528f0` | **03-P2-17 收尾**：写路径收口 `UI → viewModel.setDarkMode/setAspectMode → prefs.* → AndroidPrefsStore → AppPrefs/PlayerPrefs`，**消灭零消费点**（接口写方法此前 0 生产调用点）；旧接线判据**重锚**为新链路三点（UI 必须调 VM 写入口 / 落盘实现只许在 `PrefsStore.kt` 且恰好两处 / MainActivity 必须仍注入平台存储）；先 RED（2 failed）+ 单变量回退 ×3（分别 2/1/1 failed）|
| `7549b8b` | **CI 预检**：修掉真实 runner 上必炸的两处 —— ① `gradlew` 模式位 `100644`（ubuntu 上 `./gradlew` Permission denied）→ `git update-index --chmod=+x` + workflow 自行 `chmod +x`；② `set -euo pipefail` 下 `aapt2 dump badging \| head -1` 的 SIGPIPE 陷阱 → 先落盘再 head + 显式兜底。守卫断言"必须真执行 chmod"（剔注释后判定），回退 → 恰好 1 条红。本地把 `assembleRelease`+`bundleRelease` 跑通（APK 2,733,921 B / AAB 5,951,069 B）|

**第七回合结论**：95 条里 **86 条已落地**（新增 P2-17、P2-30）、**8 条有据的取舍**、
**1 条未落地（04-P2-7 需升 AGP）**；另有 **6 条"代码已落地但缺护栏"** 与 **1 处守卫白名单洞**
进入下一轮议程（见「下一次接手的建议顺序」⑧⑨）。`task-16` 第 (3) 步（ChannelAdmission
回退实验的独立复核）在 guard-auditor 手上，若其未完成，**Lead 自己的等价回退实验已在本文件
第五回合记录**（去掉补偿 → 恰好 3 条红）。

### 2026-10-29 第六回合（goal round 4 被暂存 → 本轮收尾 + 线上黑屏修复）：全量 **557/0/0**
8 个提交（含一次发版）。全量 debug+release 各 **557/0/0**（79 suites）+ `assembleDebug` 成功。
| 提交 | 内容 |
|---|---|
| `007ad88` | **线上「点比赛就黑屏」的根因修复**：`parseChannels` 按名字（主播/解说…）删除线路，而实测一场 NBA 的 8 条有效线路里**只有 4 条 `/play/sm.html` 能被静态解析成直连 HLS**，它们恰好叫「主播解说①②③④」→ 被删光，只剩 4 条只能走 WebView 的线路 ⇒ App **一次直连都没试过**（logcat 实测解析器 0 次、ExoPlayer 0 次），WebView 那条链在这些页面上跑不起播放器 ⇒ 整屏纯黑。修法：新增纯判据 `ChannelAdmission`（规则一隐名、规则二"过滤不许把可直连线路清零"，补偿时用中性名）+ `isDirectHls` 收敛到 `isStaticallyResolvablePlayPage` 单一来源并补上链路 A 入口 `/play/steam<数字>.html`。**同机单变量 A/B**：旧行为整屏 `mean=0.0 max=0.0`（纯黑）/新行为视频带 95.7% 非黑、中心 99.5% 非黑、帧间差 43~54（在动） |
| `641a8a1` | **发版 v1.2.15**（versionCode 19，签名证书与 v1.2.11~14 同一把 `62bd4ee4…`，APK sha256 `51476662…`）。GitHub Release 已挂 APK；repo 为 PUBLIC + `DISCLAIMER.md`（免责声明） |
| `c3841de` | **03-P2-17 剩余 + 审计#5**：`ui/PrefsStore.kt`（接口 + `InMemoryPrefsStore` + 平台层 `AndroidPrefsStore`），VM 注入偏好、UI 只读 `vm.darkPref/aspectMode`；`AppPrefs/PlayerPrefs` 改 `attach(context)` + **无 Context setter**（未 attach 直接 `error()`）；LIVE 过滤收敛到 `ui/MatchFilters.kt` 的 `liveOnly`。守卫 `PrefsBoundaryGuardTest`(8)/`MatchFilterGuardTest`(5)，并把 `NavHostGuardTest` 旧判据**收紧重锚**。5 处生产回退 → 37 tests / 8 failed |
| `42c6b9d` | 03-P2-7 定论写进 `PlayerNoticeEffect` KDoc（有意"只显示最新一条"，别再改） |
| `789ed0b` | **04-P2-5 自动化**：`.github/workflows/release.yml`（14 步，tag 触发，测试不过不发，mapping 只作 artifact）+ README「CI 发版」小节 + `ReleasePipelineGuardTest`(5)（步骤块级判据，回退即红×2）。⚠️ 未在真实 runner 上跑过 |
| `474ca1d` | **黑屏第二种形态**：WebView 看门狗原先第一句 `if (pageFinishedAt == 0L) return@LaunchedEffect` → 页面永不 finish 时**永久黑屏且无声**。抽纯判据 `ui/WebSniffWatchdog.kt`（媒体请求→不报；永不 finish→加载起算 45s；已 finish→25s，与旧语义一致）+ `WebSniffWatchdogTest`(8，含嵌套实参按括号配对的提取)。回退 → 恰好 2 条接线守卫红 |
| `20d36df` | 审计#3 取证 + `LoadDeadlineTest` 判据收紧（双边窗口 250..1500ms）+ `review/flake-audit.md` |

**第六回合遗留（下一轮候选）**：① `PrefsStore` 的写路径只收口一半（见「仍存在的主要缺口 A2」）；
② 看门狗/CI 已落地但**未发版**（用户未要求；要让用户拿到需发 v1.2.16）；
③ `task-15/task-16/task-17/task-18` 已收尾，`review/flake-audit.md` 是新增证据文件。

### 2026-10-29 第五回合（goal round 3）：结构性拆分 + 审计"未验证的怀疑"落地
4 个提交，全量 **509/0/0**（UTC 钉死下，debug+release）+ `assembleDebug` 成功。
| 提交 | 内容 |
|---|---|
| `d95aa5f` | **审计怀疑 #1/#2/#4 从猜测变实测结论**：钉死单测时区为 UTC 后立刻红了 3 条 → 确认**夹具按设备时区造时间**是真缺陷（生产按 `SOURCE_ZONE` 解释）；全部夹具改用 `SOURCE_ZONE`；`SourceEndpointsTest` 的 Kotlin 原生 `assert(...)`（-ea 关掉就静默消失）改 JUnit，并在 `GuardHygieneTest` 加硬门禁 + 3 条自检 |
| `d35a25e` | **03-P2-18**：`NbaLiveApp.kt` 817→391 行、`fun NbaLiveApp(` 391→110 行、根节点 `collectAsStateWithLifecycle` 8→1 处；拆出 `AppChrome/AppEffects/AppBackHandling/AppUiState/SearchOverlay` 五个文件；顺带消掉 **03-P2-6**（两个 effect 的隐式顺序 → 一个显式有序 `LaunchedEffect`）与 **03-P2-15**；新增 `AppStructureGuardTest`(9) |
| `781e906` | **审计第一节最后残余**：`DeadCodeWiringGuardTest` 的 12 处文件级 `contains` 全部升级为函数体/分支块/调用块级 + 跨 `ui/` 全目录扫描（现在零文件级 contains）；5 个自写 `stripComments` + XML 实现统一到 `GuardSourceScan`（+3 个可执行反例证明宽松版能骗过守卫）；11 处"按文件名取第一个"改 `uniqueFile` |
| `79867e4` | 登记表守卫跟随拆分同步调用点（`PipState.shouldAutoEnterPip(` → `AppEffects.kt`），体现"接线搬家、登记表必须跟着改" |

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
⚠️ **权威口径以 `review/coverage-ledger.md` 为准**（第七回合逐条核对、每条附可复核证据：
commit / 测试类·用例名 / `文件:行号` / grep 输出）。下面只是摘要。
P0-1、P0-2、01-P1-2/3/4/6、01-P2-1~8/10/11/12、02-P1-1~9、02-P2-1~12、
03-P1-1~4、**03-P2-1~6/8~32/34/35**（含第七回合收尾的 **P2-17 写路径** 与 **P2-30**）、
04-P1-1~5、04-P2-3/4/5/6/8/9
（03 组只剩 **P2-14**（有据取舍）、**P2-33 剩余**（审查自判可接受）；04 组只剩 **P2-7**（需升 AGP））

**有据的取舍（不修，但理由有明确落点）**：01-P2-10、**02-P2-13**（不是"删死代码"，
而是加注释说明这是**防御性兜底**，落点见 `MainViewModel` 的 KDoc —— 第七回合台账点名本文件
旧摘要把这条记成"已落地"，已改）、03-P2-7（有意"只显示最新一条"）、03-P2-14、
03-P2-33（文案硬编码，审查自判可接受）、04-P2-1、04-P2-2、04-P2-9。

**只落地了代码、但缺护栏的 6 条（下一轮议程 —— 台账第四节的镜像问题「改了没人红」）**：
02-P2-5（`back()` 取消 `detailJob`：测试源集 grep `detailJob` **0 命中**）、
02-P2-7（详情缓存 TTL 时间戳位置）、02-P2-11（2 月 29 日）、
**03-P1-3（浅色主题导航栏图标 —— P1·用户可见，全仓只有 `Theme.kt` 一行赋值、测试源集 0 命中）**、
03-P2-25（TalkBack 双节点，需实机语义树）、04-P2-8（未签名 AAB 拦截点，缺一次
"缺 keystore 跑 bundleRelease，断言失败且不产出 `.aab`" 的实验）。
另有一条**守卫有洞**：`SourceZoneDisciplineTest` 的 `DISPLAY_ONLY` 白名单仍含**判定层**文件
`MatchStatusClock.kt` —— 以后有人在 `byClock/aged` 里写回 `systemDefault()` 不会红
（该文件当前只有注释、无实际调用，移出白名单是安全的）。

**审计 A/B 两组（2026-10-29 第四回合）**：
- ✅ **B1–B8、B10、B11 全部落地**：B1/B3 删死链路+唯一来源、B2/B4 接线（提示真的画出来、
  缺数据真的给占位）、B5 判据唯一来源、B6 去假参数、B7/B8 有意零消费 KDoc 说明、
  B10 删冗余重载、B11 渐变单一来源。
- ✅ **A4–A9、A11–A16 全部硬化**：原先"永远不会红"的守卫改成会红 + 内存样本自检 + 非空锚点。
- ✅ **A1/A2/A3/A10**（第三回合）+ 元守卫 `GuardHygieneTest`、登记表守卫
  `ZeroConsumerGuardTest`、结构守卫 `AppStructureGuardTest`。
- ✅ **第一节最后残余（第五回合）**：`DeadCodeWiringGuardTest` 12 处文件级 `contains`
  全部升级为结构级判据（现在**零文件级 contains**）；`stripComments` 全仓统一为
  `GuardSourceScan.stripNonCode`（+`stripXmlComments`）。
- ⬜ **B9/B12** 属"有判据兜底的有意零消费"（`PlayerLock.gesturesEnabled`/`tapShowsUnlockUi`
  只作测试 oracle；`PlayerControls.shouldShowBottomBar` 两个参数有 4×全布尔组合的等价性断言），
  审计判定**不算缺陷**，仅记录，无需处理。

**审计「未验证的怀疑」7 条 —— 第五回合已处理 4 条**：
- ✅ **#1 时区耦合 = 真缺陷**（钉死 UTC 后红 3 条），夹具全部改用 `MatchStatusClock.SOURCE_ZONE`，
  时区已永久钉死；
- ✅ **#2 Kotlin 原生 `assert(...)`** 改 JUnit，`GuardHygieneTest` 加硬门禁
  （回退实验：换回 `assert` → 元守卫 1 条红并点名行号，而被守护的类自己**全绿** = 危险形态）；
- ✅ **#4 夹具写死日期依赖机器时钟** → 改相对日期；
- ✅ **#6 `stripComments` 两套实现并存** → 统一（3 个可执行反例留在测试里）；
- ✅ **#7 按文件名取第一个** → 11 处改 `uniqueFile`（0 或 >1 命中都 `error()`）。
- ✅ **#3 慢机/受限网络下的假红** → **第六回合已取证**：三个可疑类各 18 轮（10 空闲 +
  5 轮 8 进程忙等 + 3 轮 15 进程占满 16 核）= 54 个"类×轮"**全部 0 失败**，未见 flake；
  报告 `review/flake-audit.md`（含"未验证的部分"）。顺带**证实并修掉**一条"判据过松"：
  `LoadDeadlineTest` 的 `elapsedMs < 5_000` 抓不住"注入的 300ms 被忽略"（生产写死 3_000L
  时旧判据仍绿、耗时 3.008s）→ 收紧成双边窗口 `elapsedMs in 250L..1_500L`
  （EXP2 同一回退变红、EXP3 改成 100L 也红；收紧后含全核负载 4 轮 0.305~0.306s 未假红）。
- ✅ **#5 `MainViewModelTest.liveIds` 测试侧重实现 LIVE 过滤** → **第六回合已落地**：
  过滤提成 `ui/MatchFilters.kt` 的 `liveOnly` 唯一实现，生产（`NbaLiveApp.kt` 的 `RouteHost`）
  与测试侧都调它；`MatchFilterGuardTest`(5) 钉住"ui/ 里规范形状只许出现在 `MatchFilters.kt`
  且恰好一处 / 生产必须真的调用 liveOnly / 测试侧必须复用"。判据边界（多条件过滤是另一种语义）
  写进了 KDoc，避免后来人一刀切。

## 仍存在的主要缺口
### A. 结构性大改（03 组残余）
1. ~~**03-P2-18** 820 行上帝组件拆分~~ → **第五回合已完成**（817→391 行，`fun NbaLiveApp(`
   391→110 行，根 collect 8→1），顺带消掉 **03-P2-6** 与 **03-P2-15**。
2. **03-P2-17**：**已收尾（第七回合 `db528f0`）**。读写都收口到 `PrefsStore`，
   写路径是 `UI → viewModel.setDarkMode/setAspectMode → prefs.* → AndroidPrefsStore → AppPrefs/PlayerPrefs`，
   顺带消灭了"接口声明了却没人消费"的零消费点（`prefs.setDarkMode/setAspect` 现在各有 1 处真实调用点）。
   旧接线判据（要求 `MainActivity` 出现 `AppPrefs.setDarkMode(`、UI 保留 `onDarkModeChange =` 透传）
   已**重锚**为"UI 必须调 VM 写入口 / 落盘实现只许在 `PrefsStore.kt` 且恰好两处 / MainActivity 必须
   仍注入 `AndroidPrefsStore`"，并各有内存样本自检与单变量回退实验。
3. **03-P2-14**：**已做有据的取舍**（信号列表保持 `forEachIndexed`，注释写明
   "线路数假设 < 20"与重估条件）。不是漏做。
4. **03-P2-7** 判定为**不成立/无效修复**（显式 `dismiss()` 是多写一行，已回退）。**别再做**。
5. **03-P2-33 剩余**：全量文案硬编码（审查自己判定现状可接受）。
6. **04-P2-5** release mapping 归档 → **已完成（第六回合 `789ed0b`）**：
   手工流程早在 README「发版清单」，本轮补上 **tag 触发自动流水线**
   `.github/workflows/release.yml`（14 步：两变体单测不过就不发 → 打包/验签 → mapping 只作
   artifact → gh release 只挂 APK+AAB），守卫 `ReleasePipelineGuardTest`(5) 用步骤块级判据
   钉住"不许把 mapping 混进公开 Release"。⚠️ **从未在真实 GitHub runner 上跑过**
   （本地无 Actions/actionlint），首次真跑前需确认 runner 上 Android SDK 35/build-tools/gh CLI。
   **04-P2-7 彻底版**仍需升级 AGP（未做）。

### B. 只读审计的产物（`review/audit-guards.md`，498 行，**必读**）
审计覆盖 68/68 测试类 + ui/ 全目录，发现两类"假安全感"。**A、B 两组已于第四回合清零**：
- ✅ **A1/A2/A3/A10**（第三回合）+ **A4–A9、A11–A16**（第四回合 `6ad6679`）；
  元守卫 `GuardHygieneTest`（禁 assume 家族、剔注释、≥60 文件锚点、自检喂真函数）
  与登记表守卫 `ZeroConsumerGuardTest` 一起防复发。
- ✅ **B1–B8、B10、B11**（第四回合 `ecdea2e`/`e4f4177`/`8acbf68`）。
- ⬜ **B9/B12**：审计自己判定为"**有判据兜底的有意零消费**"，不算缺陷，仅记录未处理。
- ✅ **`DeadCodeWiringGuardTest` 的文件级 `contains` 已于第五回合清零**（12 处升级为
  函数体/分支块/调用块级 + 跨 `ui/` 全目录扫描）。

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
- **绝不用 `assumeTrue` 静默跳过**：路径一变整片变绿，而"全绿"看不出区别。
  现在有元守卫 `GuardHygieneTest` 钉住这条；**Kotlin 原生 `assert(...)` 同理**
  （只在 `-ea` 打开时求值，构建里一关就静默消失）→ 也已加硬门禁。
- **测试时间必须用生产解释时间所用的时区**：生产按 `MatchStatusClock.SOURCE_ZONE`
  （Asia/Shanghai）读源站的 "MM-dd HH:mm"，夹具若用 `systemDefault()`/无参
  `LocalDate.now()` 造时间，就**只在东八区成立**（第五回合钉死 UTC 后立刻红 3 条）。
  单测时区现已钉死为 UTC —— 这不是"环境问题"，而是把隐患变成**常驻守卫**：
  以后谁再用设备时区造时间，在任何机器上都会红。
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
  ⚠️ `Copy-Item` 绕过编辑器的版本观测：恢复后用 `edit` 工具改同一文件会报
  "file changed since it was read"，**先 `read` 一次再改**（我本轮踩到过）。
- **测试自己也会并发丢更新**：`MatchRepositoryRetryTest` 用普通 `MutableList` 在两个源并发下
  计数，实测 8 次只记到 ≤7 —— 于是一条"重试有上限"的断言看起来永远成立。
  测试夹具里的计数器要用 `ConcurrentLinkedQueue`/`AtomicInteger`（A11 抓到）。
- **`git add` 的 pathspec 只要有一条不存在，整条命令什么都不加**（`fatal: pathspec ...`）：
  本轮就因此把 8 个文件漏在提交外，靠 `--amend` 补回。加完要 `git show --stat` 核对。
- 守卫三铁律：① 剔注释；② 匹配调用而非字符串出现；③ 路径失败 `error()`，禁止 `assumeTrue`。
- 并行协议（Agent Teams，本会话已跑三轮）：写范围互斥并记在 task 上；**只有 Lead 提交**；
  只读审计可与写作并行；**最终验收窗口放在所有写者停工之后**。
  ⚠️ **测试源集是全局串行点**：任何写者留下一个编译不过的测试文件，**所有人**都跑不了
  测试（本轮发生 3 次：`PipExitOnErrorTest` 中间态、`AccentContrastTest` 缺 import、
  `AppStructureGuardTest` 未转义引号截断字符串）。对策：① 每改一段就
  `:app:compileDebugUnitTestKotlin`；② 断言消息里的中文引号用 `「」`，
  别在双引号字符串里嵌半角 `"`；③ 被阻塞的人在汇报里**指名文件与报错行**，
  让作者优先修（比各自猜快得多）。
- **队友创建的槽位有限（本会话上限 8）**：达到上限后 `spawn_teammate` 会失败；
  此时**复用 inactive 的队友**（`send_message` 唤醒并给新任务）比新开更快，
  他们的上下文里还有上一轮对该文件的认知（但要提醒"重新 read 现状，别用记忆"）。

## 下一次接手的建议顺序
1. `git status` 确认没有未提交的并行工作 / 没有 `TEMP-REVERT` 之类临时标记
2. 跑全量 + `assembleDebug` 建立基线（**计数前先删 `app/build/test-results/*`**）
3. 剩下的活按价值排序：
   ① ~~审计「未验证的怀疑」时区那条~~ → **已完成**，结论是**真缺陷**（夹具改用 `SOURCE_ZONE`，
      并永久钉死 UTC）；
   ② ~~`DeadCodeWiringGuardTest` 残余文件级 contains~~ → **已清零**（12 处升级 + stripComments 统一）；
   ③ ~~**03-P2-18** 上帝组件拆分~~ → **已完成**（817→391 行，顺带消 03-P2-6 / 03-P2-15）；
   ④ ~~审计怀疑 #3~~ → **已完成**（18 轮重复跑取证未见 flake）+ 顺带把 `LoadDeadlineTest`
      的过松判据收紧成双边窗口；
   ⑤ ~~审计怀疑 #5~~ → **已完成**（`liveOnly` 单一实现 + `MatchFilterGuardTest`）；
   ⑥ ~~**03-P2-17**~~ → **已完成**（读写都过 `PrefsStore`；写路径零消费点已消灭）；
      **03-P2-33 剩余**（文案硬编码）**审查自判可接受**，只在台账里记为"有据的取舍"；
   ⑦ ~~04-P2-5 自动化~~ → **已完成**（`789ed0b` + 第七回合 `7549b8b` 修掉 runner 上必炸的两处）；
      **04-P2-7 彻底版**（升 AGP）**仍未做**，是 95 条里唯一"未落地"的构建类条目。
   现在真正剩下的（按台账第四节）：
   ⑧ **给 6 条"改了没人红"的条目补护栏**（02-P2-5 / 02-P2-7 / 02-P2-11 / **03-P1-3（P1）** /
      03-P2-25 / 04-P2-8），其中 04-P2-8 是"缺一次实验"（缺 keystore 跑 `bundleRelease`）；
   ⑨ 补 `SourceZoneDisciplineTest` 白名单的洞（把判定层 `MatchStatusClock.kt` 移出 `DISPLAY_ONLY`）；
   ⑩ 04-P2-7（升 AGP，风险大，单独一轮）。
4. **要问用户/需要拍板的**（不要自行决定）：
   - `Route.DETAIL` 无入口：加"选择线路"入口，还是删掉详情页那条链；
   - `LogoFallback`（队徽加载/失败态盘面 `surfaceVariant`，浅色 1.17:1）要不要一起 token 化
     （统一换亮盘会让深色回退盘由"暗盘+浅字"变白，属退化，需先定字色）；
   - `Common.kt` 里 `LeagueChip` 的 9.5sp 白字是否改走 `AccentContrast.OnAccent`（03-P2-30 同族）；
   - **是否发 v1.2.16**（看门狗/CI/偏好边界等已进 main 但未发版）。
5. 新增守卫一律登记进 `ZeroConsumerGuardTest`（声明必须真有调用点）、
   `GuardHygieneTest`（不许 assume 家族 / Kotlin 原生 assert）或
   `AppStructureGuardTest`（结构上界），别再另铺一套散装断言；
   重构搬动调用点后**登记表要跟着改**（`79867e4` 就是这件事）。