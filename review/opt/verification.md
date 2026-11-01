# 对抗式独立复核报告（rev-verify / task-5）

## 0. 复核范围与诚实边界

- **复核了什么**：`review/opt/player.md` 全部 18 条、`review/opt/data.md` 全部 D1~D18、`review/opt/ui-state.md` 第 1~11 条 + 第 15、16 条、`review/opt/build-test.md`
  第 1~9 条 + 第 11~13 条。方法一律是 `read`/`grep` 回到被引用文件与行号，**逐字比对"原文"是否真在该处**、再独立判断结论；未采信任何推理链。
- **未复核到（诚实边界）**：`ui-state.md` 第 12、13、14、17、18、19、20、21 条（结构债/低档，未逐条开文件）；`build-test.md` 第 10、14、15、16、17、18 条及其"瘦身方案/仓库卫生"两节（需 `git ls-files`/`count-objects` 等仓库级统计，本轮未做）。
  `ui-state.md` 第 5 条只核到"就地 `new`/`HttpClient.shared`/三处时钟"的行号与内容，未穷举全部不可注入点。
- **独立量化复核（用 pwsh 只读，未跑 Gradle/adb）**：`app-debug.apk` = 22,264,122 B、`app-release.apk` = 2,766,693 B、release APK **292** 个 entry 的 `LastWriteTime` **全部**为 `1981-01-01 01:01:02`、`META-INF/README.md` = 3,218 B、`META-INF/version-control-info.textproto` = 120 B、META-INF 下无 `.SF`/`.RSA`；`app/src/test` **96** 个文件里 **49** 个含 `user.dir`、**35** 个用 `walkTopDown`；`app/build.gradle.kts` 里 `lint` 出现 **0** 次；`release.yml` 里 `timeout-minutes` **0** 次。
- **本文件是唯一写入物**；未改任何生产代码/测试/构建脚本，未跑 Gradle/adb。

## 1. 总表

### 1.1 `player.md`（行号口径：`read` 实测，`PlayerScreen.kt` 共 2485 行）

| 编号 | 判定 | 实际行号 | 依据（我实际读到的关键代码） |
|---|---|---|---|
| P1 | 确认 | `PlayerScreen.kt:2427`（原文逐字一致）；`2008/2019/2294/2302` 亦一致 | `webView?.reload()`；`loadUrl(targetUrl)` / `tag = targetUrl`。`reload()` 只重发当前 URL，拿不到新 `encodedStr`，结论成立（Lead 已确认，复读一致） |
| P2 | 部分确认 | `:2109`、`:2111-2126`、`WebPageDeadEndPolicy.kt:74/90`、`WebErrorProbeScript.kt:68-73` **全部逐字一致** | `val hasStream = false`；`shouldOffer = !hasStream && …`；iframe 遍历在 68-71。代码事实成立，但**建议的修法有害**（见第 2 节） |
| P3 | 部分确认 | `:1696-1709`、`:1712-1726`、`AudioFocusPolicy.kt:127-128` 一致 | 轮询体只有 `pause()`；但 `:1667-1676` **有** `player.play()` 的 GAIN 分支 —— "没有对应的 resume 分支"表述过宽 |
| P4 | 确认 | `:1986`、`:2218`、`:2055-2060`、`:2026` | `remember(url){AtomicBoolean(false)}`；全仓仅 `set(true)` 一次，无任何 `set(false)`（grep 已证）；`WebSniffWatchdog.kt:93 sawMediaRequest -> false` |
| P5 | 部分确认 | `:1990/1994`、`:2266-2268`、`WebSniffWatchdog.kt:95-97` 一致 | `pageFinishedAt` 是可变状态、每次 `onPageFinished` 都会被**重新赋值**；只有回退页迟迟不 finish 时才"沿用旧基准" |
| P6 | 部分确认 | `:306-308`、`:317-325` 逐字一致 | `shouldRestoreControlsOnPanelClose = fullscreen && !pickerOpen && !livePanelOpen`（`SystemBarsPolicy.kt:81-85`）确实不区分变化方向；净效果取决于两个 effect 的启动顺序 |
| P7 | 部分确认 | `:444-459`、`:978-980`、`:1208` 一致 | ticker 与读取都在 `webFallbackUrl?.let`（`:444`）内，位于 `:334` 起的 `Column` content lambda 中，**不是** `PlayerScreen` 函数体顶层 |
| P8 | 部分确认 | 函数区间 `:147/150`–`:1140`（`:227` 是体内第一条语句）；`LaunchedEffect` 全文件 **15** 个 | grep 命中 260/267/286/306/310/317/446/1775/1790/1814/2010/2023/2053/2085/2105 = 15，**不是 20** |
| P9 | 确认 | `:346`、`:1838-1844`、`:1595-1596` 逐字一致 | `key(streamUrl, streamReferer)`；工厂与 `ExoPlayer.Builder`；KDoc"换线路必须整体重建" |
| P10 | 确认 | `:1944-2441`、`:2053-2071`、`:2085-2102`、`:2105-2131` 一致 | `WebSurface` 起止与三条 `while(true)` 位置正确 |
| P11 | 确认 | `:1915-1925` 逐字一致 | `update` 内 `current != streamUrl` 分支；被 `key` 包住故正常不可达 |
| P12 | 确认 | `:2085-2102`、`WebPageDeadEndPolicy.kt:51`（`=1_000L`）、`WebErrorProbeScript.kt:53-79` 一致 | 无条件 `while(true)` + 1Hz |
| P13 | 确认 | `:2010-2020`、`:2023-2027` 一致 | 2s `withTimeoutOrNull` 的壳页 OkHttp 请求 |
| P14 | 确认（计数小错） | 9 处调用点全部存在：`:447/449/1994/2058/2091/2108/2268`（`elapsedRealtime`）+ `:1820/1824`（`nanoTime`） | 原文引用的 `:2091` 逐字一致；正文写"7 处"与自列的 9 行不符 |
| P15 | 确认 | `VideoResolution.kt:97-100`、`PlayerScreen.kt:531` 逐字一致 | `Regex(...)` 写在函数体内；调用点在组合期 |
| P16 | 确认 | `PipState.kt:20`；`MainActivity.kt:49/250/302` 三条写点 grep 一致 | `val inPip = MutableStateFlow(false)`；`_playerActive/_videoSize/_expandRequests` 均为私有 |
| P17 | 确认 | `ManualPipPolicy.kt:64-65`、`PlayerScreen.kt:1775-1779` 逐字一致 | `fun shouldResetOnMatchSwitch(): Boolean = true`；调用点写成 `if (恒真)` |
| P18 | **证伪**（前提错） | `NbaLiveApp.kt:225-226`、`:375` 一致；但 `MainViewModel.kt:1284` 直接反证 | `onPageDeadEnd` **确实**写了 `_playerState.update { it.markPlaybackSuspect(true) }`（`:1284`，`markPlaybackSuspect` 定义 `:638-639`），其建议的修法**已经在生产里** |

### 1.2 `data.md`

| 编号 | 判定 | 实际行号 | 依据（我实际读到的关键代码） |
|---|---|---|---|
| D1 | 确认 | `MatchRepository.kt:622-642` 逐字一致 | `withTimeoutOrNull` 包住 `jrsDeferred.await() to jrkanDeferred.await()`；超时分支 `matches = emptyList()`、`failedProviders = 全部 active`（Lead 已确认，复读一致） |
| D2 | 确认 | `:823-847` 逐字一致 | `existing.copy(status/timeLabel/startAtMillis/homeLogo/awayLogo/sources/signalCount/jrsDetailId)` —— **无 `league`**；`:840-841 jrkan.forEach → jrs.forEach`；`:847 kept = filter { it.league in League.supported }` |
| D3 | 确认 | `HttpClient.kt:105-109`、`:156-159`、`MatchRepository.kt:343-345`、`:714`、`:741`、`:1080-1081` 全部逐字一致 | `status > 0 -> Unreachable("HTTP $status")`；`get()` 对非 2xx `resume(null)` 故 404 必落此分支；`attempts = if (index == 0) LIST_FETCH_ATTEMPTS else 1` |
| D4 | 确认 | `JrkanParser.kt:301-310` 逐字一致；`:318`、`MatchRepository.kt:1013-1017`、`:1070`、`ChannelAdmission.kt:77-79` | `data-play="…"` 双引号 + `<strong>([^<]+)</strong>`；`admit = raw.filterNot { isSensitive(it.rawLabel) }`，空名 → 不被剔除 |
| D5 | 确认 | `:607-610` 逐字一致；`MainViewModel.kt:671-674`、`:665-667`、`ScoreboardRepository.kt:43` | 列表层无 TTL 缓存（详情页 5 分钟、比分 20 秒都在别处） |
| D6 | 确认（①）/ ②推测 | `Match.kt:105-110`（列表里确无 雷霆/猛龙/奇才）、`:66-71`、`:80-84`；`TeamNameBridge.kt:57/65/67`、`:106-108` | `NBA_TEAM_KEYWORDS` 无这三队；`findGame` 用 `toChinese(g.homeName) == h` 全等 |
| D7 | 确认 | `MainViewModel.kt:350-354` 逐字一致；`ScoreboardRepository.kt:52/55/87-91/43` | `isEmpty()` 时 `return@launch`，`_scores` 只在非空分支赋值；失败源站返回空快照且进 20s 缓存 |
| D8 | 确认 | `ScoreboardRepository.kt:67-69` 逐字一致 | 函数体 `:68-91` 全程未引用 `enabled`（Lead 已确认，复读一致） |
| D9 | 确认（代码事实） | `:732-737` 逐字一致 | `Rejected` 分支只 `listFailedHosts += host` 并停本源自；跨调用冷却确实不存在（报告自标"推测"恰当） |
| D10 | 确认 | `:39-56`、`:798-799`、`:802`、`:213` | `internal fun mergeForTest(…) = merge(jrs, jrkan).matches` |
| D11 | 确认 | `ScoreboardRepository.kt:9`、`:101` | `import com.nbalive.app.ui.TeamNameBridge` + `TeamNameBridge.findGame(...)`（Lead 已确认，复读一致） |
| D12 | 确认（机制） | `:285-295`、`:301-305` 逐字一致 | forceRefresh 跳过 `:280-283` 的早返回与 `:286-295` 的注册，`finally` 仍无条件 `remove(detailId)` |
| D13 | 确认 | `JrkanParser.kt:129-130/306/309`、`JrsParser.kt:69`、`MatchListScreen.kt:415` | `getPlayUrl\("line\d",…\)`（`line10` 必不匹配）；`signalCount > 0` 才画徽标 |
| D14 | 确认 | `EspnScoreboard.kt:49/84/110-113` 逐字一致 | grep `startAtMillis` 在 EspnScoreboard 仅 49/84（`Match.startAtMillis` 是另一个字段） |
| D15 | 部分确认 | `MainViewModel.kt:565-571` 逐字一致；`EspnScoreboard.kt:227-244` | `jsonString` 确无"前一个是 `{`/`,`"边界；但它**要求闭引号 + 冒号**，故"命中更长 key"不成立（见第 2 节） |
| D16 | 确认 | `:540-541` 逐字一致 | 全仓无 `SourceOutcome.enabled` 消费点（仅 `PrefsStore.enabled`/`enabledProviders` 同名不同物） |
| D17 | 确认 | `UpdateCheckPolicy.kt:123-128`、`:56-66` 逐字一致；`MainViewModel.kt:542-547` | `SEGMENT = Regex("\\d+")` 只取数字段 → `-hotfix.1` 判新、`-hotfix` 判相等 |
| D18 | 确认 | `:263-267`（逐字）、`:954-956`、`:968-976`、`:1137` | `MatchRepository` 里 `debugLog` 只有 `:1137`；命中/过期/清理三路无日志 |

### 1.3 `ui-state.md` / `build-test.md`（复核子集）

| 编号 | 判定 | 实际行号 | 依据（关键代码） |
|---|---|---|---|
| U1 | 确认 | `AppEffects.kt:67-72` 逐字一致；`:50-51`、`:155-158`、`:251` 亦一致 | `LaunchedEffect(route, tab){ while(true){ onTick(); delay(45s) } }`；KDoc 的"切后台时会自然停"与代码不符；TTL 20s < 45s 真发请求 |
| U2 | 确认 | `Common.kt:561/571-580/586-587/595` 逐字一致；`AppChrome.kt:473-513` | `staticCompositionLocalOf` 语义 = 值变则 content 整棵失效；`alpha` 是**组合期**读的参数，`graphicsLayer{ this.alpha = alpha }` 读的是闭包 Float |
| U3 | 确认 | `AppEffects.kt:68`；`LiveNowScreen.kt:73-79`；`AppChrome.kt:494/500-510` | `LiveNowScreen` 签名无 `scores`，LIVE 分支不传 `scores` |
| U4 | 确认 | `MatchListScreen.kt:118`、`:133-137`；`MainViewModel.kt:684`；`LiveNowScreen.kt:87` | `isRefreshing = refreshing && matches.isNotEmpty()`；`matches.isEmpty() -> EmptyState()`，`RefreshingBar` 只在该 else 分支 |
| U5 | 确认 | `MainViewModel.kt:325/401/409/544/773` 逐字一致 | `ScoreboardRepository()` 就地 new；`System.nanoTime`/`currentTimeMillis`/`ZonedDateTime.now()` 直用 |
| U6 | 确认 | `MainViewModel.kt:35/41/78/87/95`；`Match.kt:166/190` | 三个 UiState 均无 `@Immutable` 且含 `List`/`Map`，对照数据层已标注 |
| U7 | 部分确认 | `Common.kt:473-479`（Row）、`:490`（`10.5.sp`） | 代码形状与行号一致；`fontScale` 是否真裁切需真机/预览 |
| U8 | 部分确认 | `MainActivity.kt:53-54`、`AppPrefs.kt:39-42` | 两处逐字一致；"首次访问会等 XML 解析"依据 Android 文档语义，具体毫秒未测 |
| U9 | 确认 | `MatchListScreen.kt:525`、`:343-347`；`SearchScreen.kt:248`、`ProfileScreen.kt:251` | 裸 `.clickable(onClick = onClick)` 与已修写法并存（grep 一致） |
| U10 | 确认（分配）/重下载推测 | `Common.kt:149-153` 逐字一致 | `ImageRequest.Builder(...).build()` 直接在组合体内 |
| U11 | 确认 | `AppUiState.kt:48-52`；`AppStructureGuardTest.kt:107-116`、`:316-318`、`:120-127`；生产 `NbaLiveApp.kt:31/67` | 守卫用 `functionBody(source,"fun NbaLiveApp(")` + `Regex("""collectAsStateWithLifecycle\s*\(""")`，生产根函数内只剩 1 处（`:67`）→ 该守卫**不可能**再红 |
| U15 | 确认 | `Common.kt:468-472`；grep `StatusLabel.of` 全仓仅 `Common.kt:471` | LIVE 提前分支使 `StatusLabel` 的 LIVE 文案生产不可达 |
| U16 | 部分确认 | `MainViewModel.kt:770-779` 逐字一致 | 只老化 `_listState`；`_detailState/_playerState` 的写入点未逐行复核 |
| B1 | 确认 | `release.yml:73-82`、`:149-158`；`app/build.gradle.kts:37-38` | tag 只对 `versionName`；badging 输出只写 step summary，无断言；`versionCode = 28` 无判据 |
| B2 | 确认 | `BuildScriptHygieneTest.kt:55-58`（`raw.contains` 4 条，逐字）、`:42-47`；`app/build.gradle.kts:81`、`:174`（注释命中）、`:135/160`（真实实现） | 删掉 `:135/160` 后第 2、4 条断言仍由注释满足 |
| B3 | 部分确认 | `app/build.gradle.kts:237` 逐字一致；debug/release 体积我独立复核一致 | 37.4 MB AAR 未复核（本机 Gradle 缓存路径下未找到该 aar） |
| B4 | 部分确认 | `:54-66`；release APK 实测 292 entry / 单一时间戳 / 无 `.SF`/`.RSA` | 独立复核支持报告已排除的两个成因；RSA OID 与"首个不同字节"未复核，成因仍是推测 |
| B5 | 确认 | `app/build.gradle.kts:103-105` 逐字一致；`META-INF/README.md` 3218 B、`version-control-info.textproto` 120 B 实测存在 | excludes 只挡 AL2.0/LGPL2.1 |
| B6 | 确认 | `release.yml:12-17`、`:85-86`、`:128-129` 逐字一致；`.github/` 只有 `release.yml` | 无 push/PR 触发，无 `assembleDebug` |
| B7 | 部分确认 | `app/build.gradle.kts:106-121`（testOptions 内无 `lint`；全文件 grep `lint` = 0）；`release.yml` 无 lint 步骤 | 结论（lint 不是闸）成立；但报告给的"lint `line=204` ⇒ `controlsTouchSeq`"映射错：当前 `PlayerScreen.kt:204` 是 KDoc，该变量在 `:249` |
| B8 | 确认 | `RepoConfigDisciplineTest.kt:54-58/92-105`、`app/build.gradle.kts:265-267` | 判据只点名 `kotlinx-coroutines-test`；`lockAllConfigurations()` |
| B9 | 确认 | `release.yml:24-32`；`timeout-minutes` 全文件 0 次 | `cancel-in-progress: false` + 无超时 |
| B11 | 部分确认 | `RepoConfigDisciplineTest.kt:92-100`、`AppStructureGuardTest.kt:356-367` 逐字一致；我独立计数：96 个测试文件 / 49 个 `user.dir` / 35 个 `walkTopDown` | 报告的 96/49/35 与我一致；"38 处候选根"未复现（我用 `File(cwd` 计得 26 个文件） |
| B12 | 确认 | `AppStructureGuardTest.kt:45-53`、`GuardSourceScan.kt:135-137` 逐字一致 | `lines <= 180`；表达式体函数直接 `error(...)` |
| B13 | 确认 | `GuardSourceScan.kt:30-31`、`:412-424`、`:436-440` | 原样字符串分支整段填空、无 `${` 处理；普通字符串分支有 `appendStripped(..., stopAtCloseBrace = true)` |

## 2. 证伪 / 必须修正清单（最重要产出）

1. **`player.md` 第 18 条 —— 证伪（关键前提与代码相反）**
   - 审查员写了：`onPageDeadEnd` 不改 `playbackSuspect/playbackDead`，所以死胡同页面在 9.5 秒窗口里仍允许自动进小窗；建议"`onPageDeadEnd` 里顺带置 `playbackSuspect`"。
   - 代码实际：`MainViewModel.kt:1278-1285`，函数第二句就是 `_playerState.update { it.markPlaybackSuspect(true) }`（`:1284`，`markPlaybackSuspect` = `copy(playbackSuspect = suspect)`，`:638-639`）——**建议的修法已经在生产里**。
   - 建议改表述：结论保留但换成真正的原因与窗口：`pageDeadEnd=true`（错误后 **1.5 s**）到 `onPageDeadEnd`（**9.5 s**）之间 `playbackSuspect` 仍为 false，这 **8 秒**才是"按 Home 会进一块坏小窗"的窗口；修法是让恢复条出现时（`:2117`）同步上报 suspect，而不是改 `onPageDeadEnd`。

2. **`player.md` 第 2 条 —— 部分确认，但"建议修法"必须撤回**
   - 审查员写了：把 `hasStream = false` 改成 `hasStream = sawMediaRequest.get()`。
   - 代码实际：`WebErrorProbeScript.kt:11-12` 明写该功能要覆盖的场景是"页面**确实发过媒体请求，只是那条请求 404**"；`PlaybackHealth.isMediaRequest`（`:148-153`）对 `.ts/.m4s/.flv/.mp4/ /hls/ /segment` 均判真。改成 `sawMediaRequest.get()` 后，**这个功能唯一的目标场景会被永久关闭**（`sawMediaRequest` 一旦为 true 就不会再为 false，另见 P4）。
   - 建议改表述：把"高/S 修法"降为"这只是判据参数被写死、`!hasStream` 从未武装"的事实陈述；真修法应是"把 `hasStream` 改成**当前是否真的在出流**（例如最近 N 秒内仍有新的媒体请求 / 页面未进入错误类）"或直接删掉这个已被写死的参数，避免下一个人照抄。

3. **`player.md` 第 3 条 —— 表述必须修正**："轮询只暂停、从不恢复（且 `shouldRequestFocusAgain` 被自己挡住）"里的"没有对应的 resume 分支"不成立：`PlayerScreen.kt:1667-1676` 在 `AUDIOFOCUS_GAIN` 里有 `player.play()`（判据 `shouldResumeOnFocusGain`）。准确表述是"**轮询路径**没有 resume 分支，而 GAIN 监听器需要我们在焦点栈里才收得到事件"。建议把判定由"已证实（代码里只有 pause 分支）"改为"轮询分支已证实；恢复链路是否真断需真机"。

4. **`player.md` 第 8 条 —— 数字必须改**："20 个 `LaunchedEffect`"实为 **15**（grep 全文件命中 15 处，见总表）。结构结论（单函数约 990 行、无对应结构守卫）成立，但引用数字会被反驳者当成整条不可信的抓手。

5. **`data.md` D15 —— 论证必须修正**：`jsonString` 的 needle 是 `"$key"`（**含两侧引号**），因此 `"awayHomeScore"` 这种更长 key **不可能**被 `"homeScore"` 命中；真实残余风险是"文档里更早出现同名嵌套 key"（此时前一个非空白字符不是 `{`/`,` 也照样命中）。当前调用顺序（tag→html_url→body）下也未见可得手的实例。建议把"命中更长的 key"删掉，只保留"缺前边界校验 + 与 `JsonCursor.keyIndex` 重复"。

6. **`build-test.md` 第 7 条 —— 行号/代码对应必须改**：lint 报告里的 `line="204"` 被解释成 `var controlsTouchSeq by remember { mutableStateOf(0) }`，但当前 `PlayerScreen.kt:204` 是 KDoc（`onPipBlocked` 的注释），该变量在 **`:249`**。报告自己也说那份报告早 4 天、不能当现状证据 —— 这句话与"指向 `:204` 的 `mutableStateOf(0)`"自相矛盾。建议改为"旧报告里的 204 行在当前代码中已不对应（现为 KDoc）；`mutableStateOf(0)` 当前在 `:249`"。

7. **`build-test.md` 第 11 条 —— 量化口径需标明**：96/49/35 我独立复核一致；"38 个自带候选根 + `firstOrNull` + `error()`"未复现（我按 `File(cwd` 模块内计数为 26）。建议给出该数字的判据式（例如"含 `user.dir` 且含 `firstOrNull` 且含 `error(`"），否则这类数字不可复核。

8. **`player.md` 第 14 条**：正文"7 处"与自列的 9 个行号不符（7 处 `elapsedRealtime` + 2 处 `nanoTime`）。**第 16 条**：结论写"两处调用"而位置列了 `MainActivity.kt:49/250/302` 三处。**第 5 条**：`pageFinishedAt` 是**可变状态**、每次 `onPageFinished` 都会重写，故"退回原页后按 paps 的 finish 时刻计时"只在**回退页迟迟不 finish** 时成立，平铺成必然结论偏重。三处均为表述级修正。

## 3. 「结论超出证据」清单（该降级的）

| 条目 | 现在写的 | 应改成 |
|---|---|---|
| P2 | 影响段把"任意同源 xgplayer iframe 报错"当机制 | 机制段保留（`hasStream` 写死 + iframe 遍历是代码事实）；**误判概率**仅"推测"，且报告的修法会使功能失效（见第 2 节第 2 条） |
| P4 | "三条感知通道全断" | 三段都是代码事实，但"页面卡住且播放器不画错误面板"这个**前提**未在真机确认；应写"该组合下会静默" |
| P6 | 判定已标"推测"（正确） | 维持；并补一句"若 Compose 实际按反序启动，本条不成立"，避免读者只记住结论 |
| P7 | "整个播放页函数体被反复重组" | 读点落在 `Column` content lambda 内（`:334` 起），失效范围**至多**该 lambda；应写"这块 content lambda（含 `others` 过滤与列表）"而不是"整个 PlayerScreen 函数体" |
| P5② | "慢但健康的线路会被判疑似" | 保留但注明触发条件是**页面在"旧基准 +15 s"之前仍未 finish**；30 s 硬上限只作用于"永不 finish"分支 |
| D15 | "会静默取到错值（命中更长的 key）" | 降级为"缺前边界校验（与 `JsonCursor` 重复）"，具体误取路径需构造样本 |
| U7 | "机制已证实"（裁切） | 拆成"固定 dp + 可缩放 sp 的组合已证实；裁切幅度需预览/真机验证" |
| U10 | 分配已证实 | 已正确标注"是否重新下载 = 推测"，维持 |
| B3 | 37.4 MB / 28 个图标 | debug/release 体积与 `:237` 已复核；AAR 37.4 MB 与 28 个图标类未复核，应标注"数自报告，本轮未独立复算" |
| B4 | "已排除 …（已证实）" | 我复核了 292 entry / 单一时间戳 / 无 `.SF`/`.RSA`；RSA（非 EC）与"逐 entry 首个不同字节"未复核，应标"报告已做、本轮未重做" |

## 4. 「仍无法判定」清单（需真机/真跑）

1. **P1**：`reload()` 后 chromium 是否仍请求 `paps.html?id=<同一个>`、随后 m3u8 的 `expire` 是否变化 —— 建议：`chrome://inspect` + `evaluateJavascript` 打一行 `document.URL` 到 logcat。
2. **P2**：源站页面里是否真存在"会报错且同源的 xgplayer iframe/面板"；建议在 `PlayerScreen.kt:2091` 命中处打 `outerHTML` 采集。
3. **P3**：厂商 `MediaFocusControl` 是否真把我方移出栈、通话结束后是否真收不到 GAIN —— 需要"小窗看球 → 抖音 → 来电挂断"实测。
4. **P5②/P18**：真实误判率与那个 8 秒窗口被用户撞上的概率，都只能靠 `debugLog` 采样统计。
5. **P6**：两个 `LaunchedEffect` 的真实启动顺序（Compose 运行时相关）；静态推断无法定案。
6. **P7/P12/U2/U6/U10/U13**：帧时间、重组次数、分配速率一律需要 `dumpsys gfxinfo framestats` / Layout Inspector recomposition 计数。
7. **P9**：换线延迟中"重建播放器"占比，需真机对比 `ExoPlayerImpl Init` → 首帧。
8. **U8**：SharedPreferences 首读在主线程的**毫秒数**（`am start -W` + StrictMode）。
9. **B1/B4/B6/B7**：流水线从未在真实 runner 上跑过；不可复现成因、完整 lint 现状、icons-extended 在 debug 包里的实际占比，都需真跑构建。
10. **B13**：`src/main` 里是否已有 `"""…${…}…"""` 形态的可执行调用（报告倾向 0，我未穷举）。

## 5. 我复核后仍认为最值得做的 5 条（重排）

1. **`AppEffects.kt:67-72`（U1）—— 后台 45 s 一次真实 ESPN 请求**。它同时满足"用户可感（流量/耗电）+ 与 KDoc 承诺相反 + 修法 ≤S"，且我独立确认 TTL 20 s 吸不掉 45 s 节拍。比 player.md 的 Top1（需真机才能坐实签名问题）更硬。
2. **`AppStructureGuardTest` 的收集数守卫（U11）—— 判据已失效且不可能再红**。这是"守卫给假安全感"的实证（生产根函数内只剩 `:67` 一处），改动 10 行即可恢复约束力。
3. **`BuildScriptHygieneTest.kt:55-58`（B2）—— 注释就能骗过发布事故守卫**。我已逐字确认两处注释命中、两处真实实现另有其行；这是"防线在但已失效"，优先级高于任何观感项。
4. **`data.md` D2（JRKAN 的 `league` 覆盖 JRS）**。静默丢整场、不可逆、修法 ≤S，且 Lead 与我复读一致（`copy(...)` 无 `league`、`:840-841` 顺序、`:847` 过滤三层都在）。
5. **`AppScoresRefreshEffect` 在「直播」页轮询（U3）+ 空列表刷新无反馈（U4）** 二选一放这里：U4 是用户直接可见的"点了没反应"，触发条件（切到 0 场的联赛）常见，成本 S，比 `player.md` 的第 3、4 条更值得先做。

**我认为被高估的条目**：
- `player.md` **第 2 条**：被列为 Top2/高，但（a）误判概率是纯推测，（b）它给出的修法会关掉功能唯一的正经场景。价值应降为"中（判据参数已死，需重新定义）"。
- `player.md` **第 9 条**（换线重建播放器 / ResolvingDataSource）：收益全在真机测量上，成本 L，风险集中在最贵的链路；在只读阶段它只是"结构观察"，不应占"高价值"位置。
- `player.md` **第 11 条**（`setMediaItem` 死代码）：确为死代码，但后果依赖"URI 字符串不往返"这一未观察到的条件，实际是三行洁癖，不构成 Top 之外的独立价值。
- `build-test.md` **第 3 条**（icons-extended）：量级论证漂亮，但 release 侧已被 R8 砍到 28 个图标，改完只动 debug 包与构建时间，属"中"而非可争的高。

## 6. 复核方法学备注

- 所有"原文"比对均以 `read` 输出为准（`read`/`grep` 行号口径一致）；本次未发现"引用了文件里不存在的代码"这一类硬错误，最接近的是 **P18 引用了正确的调用点却得出与同一函数第 1284 行相反的前提**、以及 **B7 把旧 lint 报告的 `line=204` 映射到当前文件的 KDoc 行**。
- 行号漂移最大的三项：P8（`LaunchedEffect` 计数 20 → 15）、P14（7 → 9 个时钟调用点）、B11（38 → 26，口径未标明）。其余引用均在 ±0~1 行内。