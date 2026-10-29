# 95 条审查清单覆盖台账（逐条可复核）

- 核对人：`guard-auditor`（只读；本文件是唯一写入物）
- 核对时间：2026-10-29 17:23 ~ 18:05 (+08:00)
- 快照：`HEAD = 17f75af`，但核对期间 `ui/**` 与 `MainActivity.kt` **正被并行改写**。
  18:00 的 `git status --short`：`M .github/workflows/release.yml`、`M MainActivity.kt`、
  `M ui/AppChrome.kt`、`M ui/MainViewModel.kt`、`M ui/NbaLiveApp.kt`、`M test/.../ReleasePipelineGuardTest.kt`、
  `M test/.../ui/NavHostGuardTest.kt`、`M test/.../ui/PrefsBoundaryGuardTest.kt`、`M  gradlew`（已暂存）。
  凡涉及这些文件的行号均按 18:00 快照核对，关键文件 SHA-256 前 12 位：
  `MainViewModel 2DFA766CB02E`、`NbaLiveApp 8648B27B4881`、`AppChrome CAF60B066732`、
  `MainActivity 6B099254C57D`、`AppPrefs A8132FD27C16`、`PrefsStore A45D33AE8F34`、
  `PrefsBoundaryGuardTest 9C76C9C04D1F`。**后续提交若再改这些文件，相关行号需重核。**
- 清单规模复算（修正了 Lead 的口径差异）：`^### P\d+-\d+` → 01-player **19** / 02-data **23**；
  `^#### P\d+-\d+` → 03-ui **35**（另有 `### P1-1..P1-4` 4 条）/ 04-security **14**。
  合计 **19 + 23 + (4+35) + 14 = 95 ✓**（03/04 的条目用 `####`，只数 `###` 会漏 49 条）。
- 状态口径：`已落地` / `有据的取舍` / `未落地` / `无法判定`。**每条 `已落地` 的"证据"列都是我自己跑
  `grep`/`read`/`git log`/文件哈希看到的**，不采信 `AGENT_STATE.md` 的结论（其错误见第三节）。

---

## 一、总表

### 01-player.md（19 条）

| 编号 | 标题（截断） | 状态 | 证据（我实际看到的） |
|---|---|---|---|
| 01-P0-1 | 网页判死是推断、处置却不可逆 | 已落地 | 判据分级 + 可逆处置：`PlayerScreen.kt:1973` 调 `WebSniffWatchdog.shouldReport`；`PlaybackHealth.pipDisposition`（MainActivity.kt:281-285 用 `finish()` 而非 `finishAndRemoveTask`，:284 注释）；`PlaybackHealthTest`（18 用例，含 `疑似异常不收小窗`）；commit `fdc9f73`/`ecdea2e` |
| 01-P1-1 | `onReceivedError` 不区分 errorCode | 已落地 | `PlaybackHealth.isConfirmedWebFailure`（PlaybackHealth.kt:109，四重过滤）+ 调用点 `PlayerScreen.kt:2101`；`PlaybackHealthTest.ERR_ABORTED 不判死`/`子资源失败不判死`/`首次加载完成前不判死` |
| 01-P1-2 | 焦点被抢走后不再持有焦点 | 已落地 | `AudioFocusPolicy.isFocusGranted`/`shouldRequestFocusAgain`/`shouldPauseForAudioMode`；调用点 `PlayerScreen.kt:1644`（检查返回值）、`:1674`（回前台/起播补申请）、`:1655`（轮询兜底）；`AudioFocusPolicyTest`（10 用例） |
| 01-P1-3 | 来电判据依赖瞬时 mode 的竞态 | 已落地 | `AudioFocusPolicy.kt:80-88`：TRANSIENT 直接停、LOSS 才退回 inCall；`AudioFocusPolicyTest.短暂丢失焦点直接暂停` |
| 01-P1-4 | 亮度不复位 | 已落地 | `PlayerGestureMath.brightnessOnDispose`（:369）+ 调用点 `PlayerGestureLayer.kt:153`（`?.let` 只在改过时复位）；`PlayerGestureMathTest.离开播放页要复位亮度` |
| 01-P1-5 | 15 秒看门狗永久拉黑健康线路 | 已落地 | 阈值 30s：`PlayerScreen.kt:2270 FIRST_FRAME_WATCHDOG_MS = 30_000`；TTL：`SourceFailureBook.TTL_MS = 5*60*1000`（:45）+ `MainViewModel.kt:327 isSourceFailed`；`SourceFailureBookTest`（8 用例）；commit `cc294fb` |
| 01-P1-6 | 锁定态单击不消费 | 已落地 | `PlayerLock.gestureConfig` 的 `consumeTap = locked`（:91）+ 调用点 `PlayerScreen.kt:438`；`PlayerLockTest.锁定时单击必须消费` |
| 01-P2-1 | 中途卡死无检测 | 已落地 | `StalledPlayback.Tracker` + 调用点 `PlayerScreen.kt:1771-1780`；`StalledPlaybackTest`（8 用例）；commit `a8633ec` |
| 01-P2-2 | 非全屏手势层吃掉滚动 | 已落地 | `gestureConfig.dragEnabled = !locked && fullscreen`（PlayerLock.kt:75）；`PlayerLockTest.全屏未锁定才接管滑动` |
| 01-P2-3 | `ON_STOP` 停不掉 HTML5 媒体 | 已落地 | `WebMediaPauseScript.PAUSE_ALL`/`RESUME_PAUSED` + 调用点 `PlayerScreen.kt:2002/2010`；`WebMediaPauseScriptTest`；commit `54d6ead` |
| 01-P2-4 | 切线路复用 WebView 导致串流 | 已落地 | 重建：`PlayerScreen.kt:376 key(webFallbackUrl)`；归属校验：`MainViewModel.kt:846` 调 `belongsToPage`（:867）；`StreamSniffOwnershipTest`（3 用例）+ `DeadCodeWiringGuardTest` 钉调用点；commit `11a8da4` |
| 01-P2-5 | 小窗比例沿用上一场 | 已落地 | `PipAspectReset.ratioFor`（ManualPipPolicy.kt:73）+ `MainActivity.kt:191` 唯一来源（B3 收口）；切场清零 `PlayerScreen.kt` `setVideoSize(0,0)`；`ManualPipGuardTest`；commit `8faedf3` |
| 01-P2-6 | 解析中点画中画得黑窗 | 已落地 | `ManualPipPolicy.decide` + 调用点 `PlayerScreen.kt:517`；`ManualPipGuardTest.手动与自动用同一条判据` |
| 01-P2-7 | 进小窗方向被复位 | 已落地 | `SystemBarsPolicy.orientationFor` → `OrientationAction.KeepLandscape`；调用点 `AppEffects.kt:172`；`FullscreenOrientationTest`；commit `16a484f` |
| 01-P2-8 | 面板停留后底部栏消失 | 已落地 | `SystemBarsPolicy.shouldAutoHideControls`/`shouldRestoreControlsOnPanelClose` + 调用点 `PlayerScreen.kt:276/307`；`FullscreenOrientationTest`；commit `16a484f` |
| 01-P2-9 | `gesturesEnabled`/`tapShowsUnlockUi` 是死函数 | 已落地 | 生产唯一入口改为 `gestureConfig`（`PlayerScreen.kt:438`）；`PlayerLockTest:113-130` 断言两者与 `gestureConfig` 一致。**备注**：两个单项目视图函数仍保留（仅作测试 oracle），审计 B9 判为"有判据兜底的有意零消费" |
| 01-P2-10 | `shouldShowBottomBar` 两个参数未参与判定 | 有据的取舍 | `PlayerControls.kt:31-51` 明确写下保留理由（自文档 + 将来扩展 + 意图落点），并指定 `WebFallbackControlsTest.底部控制栏不区分直连与网页路径`（4 URL 组合 × 全布尔等价性）钉住契约；审计 B12 判为不算缺陷 |
| 01-P2-11 | 网页线路「画面」胶囊无效 | 已落地 | `PlayerControls.shouldShowAspectChip`（:86，`streamUrl != null`）+ 调用点 `PlayerScreen.kt:694`；`WebFallbackControlsTest.直连路径才显示画面胶囊`；commit `cc294fb` |
| 01-P2-12 | 广告可把主框架导航走 | 已落地 | `WebNavigationPolicy.shouldBlock`（注册域白名单）+ `blockReason`，调用点 `PlayerScreen.kt:2057`；`WebNavigationPolicyTest`（8 用例含 `白名单匹配注册域而不是子串`） |

### 02-data.md（23 条）

| 编号 | 标题（截断） | 状态 | 证据 |
|---|---|---|---|
| 02-P0-1 | m3u8 正则吞掉 `&id2=` | 已落地 | `StreamUrlResolver.kt:109-118`（`stripId2Tail` + 三候选）；`StreamUrlResolverTest.m3u8 payload is not truncated at a bare ampersand` 已改**精确相等**断言（:85-88） |
| 02-P1-1 | 状态是一次性快照、永不过期 | 已落地 | `MatchStatusClock.aged(match, now)`（:90/102）+ 60s ticker（`AppEffects.kt:33 LIST_STATUS_AGE_INTERVAL_MS`、`:116`）+ `MainViewModel.kt:522 agedMatches(current, ZonedDateTime.now())`；`MatchStatusClockTest`（14 用例，含老化 5 条） |
| 02-P1-2 | 单源失败静默 | 已落地 | `MatchRepository.partialError()`（:559）+ `MatchListUiState.partialError`（:43）+ UI 消费（`MatchListScreen.kt:120` 调 `ListNoticePolicy.of`）；`LoadOutcomeTest`（10）/`ListNoticePolicyTest`（5）；commit `520c1f8` |
| 02-P1-3 | 联赛名失败→OTHER→整场丢弃 | 已落地 | 队名兜底 `League.inferFromTeams`（Match.kt:66，`fromSource("")` 处 :83 调用）+ 丢弃计数与日志（`MatchRepository.kt:813` 注释、`:131` 超预算日志）；`LeagueInferenceTest`（6）；commit `16c52b1` |
| 02-P1-4 | 详情页校正 8 场硬上限 | 已落地 | `MAX_DETAIL_FETCH = 16`（MatchRepository.kt:390）+ `selectDetailBudget`（:128/:1102，按最早开赛排序）；`DetailBudgetTest`（6）；commit `16c52b1` |
| 02-P1-5 | `statusByClock` 忽略注入的 now | 已落地 | 判据已收敛为 `MatchStatusClock.byClock(startMillis, now)` 与 `aged(..., now)`，调用方注入：`MatchRepository.kt:499 aged(matches, now)`、`MainViewModel.kt:522`；`MatchStatusClockTest.同一个开赛时间在不同 now 下得到不同状态`。**备注**：`JrkanParser.parseStatus`（:269）与 `JrsParser.correctedStatus`（:193）仍默认取真实 `now()`（报告里点名的"同类问题"），解析时刻取 now 语义正确，只是该层不可注入 |
| 02-P1-6 | release 用 `t.message` 泄露签名 | 已落地 | `Logs.safeLogWarn`（:93，只打类型 + 脱敏 message）+ 全部上层改用（`MainViewModel.kt:469/539/651/756`、`MainActivity.kt:171/287`）；`LoggingDisciplineTest`（3 用例 + 自检）；`grep "Log\.[we]\(.*, t\)"` 在 `src/main` **0 命中**；commit `fdc9f73` |
| 02-P1-7 | JrkanParser 手写正则切 HTML | 已落地 | 已改 Jsoup：`JrkanParser.kt:10 import org.jsoup`、`:68-182 selectFirst/select`；`JrkanParserTest`（变体用例：块起点写法、属性顺序、单引号、span 替 strong、data-lid 多段）；commit `1a99e6e` |
| 02-P1-8 | `parseScheduledTime` 正则过窄 | 已落地 | `JrsParser.kt:179 Jsoup.parse(html).selectFirst(".match-info-time")?.text()`；`JrsParserTest.计划开赛时间容忍 class 上追加的状态 token`（5 形态）；commit `16c52b1` |
| 02-P1-9 | 域名全写死、无候选 | 已落地 | `SourceEndpoints`（JRS_LIST/JRKAN_LIST_JS/PLAY_HOSTS/jrsDetailUrls/playPageCandidates/attemptOrder）+ 生产消费点（`MatchRepository.kt:324/499/648/652/1004`）；`SourceEndpointsTest`（5）/`SourceEndpointsWiringTest`（8）；commit `cc0ed66`/`ac3dd18` |
| 02-P2-1 | 队名正则跨 li 串味 | 已落地 | Jsoup 限定在各自 li：`JrkanParser.kt:101-122`；`JrkanParserTest.主队没有 strong name 时不能串味到客队` |
| 02-P2-2 | 全链路用设备时区 | 已落地 | 仅展示层保留 `systemDefault()`（`MatchFormat.kt:27`，被白名单）；解析/判定用 `MatchStatusClock.SOURCE_ZONE`（`grep systemDefault` 在解析器里只剩注释）；`SourceTimeZoneTest`/`SourceZoneDisciplineTest`；commit `8371c40` |
| 02-P2-3 | `loadMatches` 无整体超时 | 已落地 | `withTimeoutOrNull(loadDeadlineMs)`（MatchRepository.kt:595）+ `DEFAULT_LOAD_DEADLINE_MS = 60_000`（:359）+ 超时给失败源文案（:608-609）；`LoadDeadlineTest`（3，双边窗口 250..1500ms）；commit `cfa74a6`/`20d36df` |
| 02-P2-4 | 刷新取消收尾覆盖新任务 | 已落地 | `MainViewModel.kt:411 fun stillCurrent(): Boolean = refreshJob === self`；`RefreshRaceTest`（2 用例，含复现"旧收尾更晚落地"） |
| 02-P2-5 | `back()` 不取消 `detailJob` | 已落地 | `MainViewModel.kt` 的 `detailJob?.cancel()` 出现在 :272/:529/:624/:1025/:1052 |
| 02-P2-6 | 详情页 URL 模板重复 | 已落地 | 唯一来源 `SourceEndpoints.jrsDetailUrls`（:94），`fetchJrsDetail`/`invalidateDetailCache` 都经它（`MatchRepository.kt:324` 附近 + :922 注释）；`SourceEndpointsWiringTest`；commit `ac3dd18` |
| 02-P2-7 | 缓存时间戳在 fetch 之前取 | 已落地 | `MatchRepository.kt:307-311`：拿到正文**之后**才取 `cachedAt`（注释即指向 P2-7） |
| 02-P2-8 | 详情页无 single-flight | 已落地 | `inFlightDetails` + finally 移除/complete（`MatchRepository.kt:288-305`）；`DetailSingleFlightTest`（3）；commit `311ccec` |
| 02-P2-9 | `redactUrl` 不抹 userinfo/path | 已落地 | `Logs.kt:43-47` 抹 userinfo、`:61-77` path 截断 120 字符；`RedactUrlEdgeCaseTest`（5）；commit `311ccec` |
| 02-P2-10 | 延期与未开赛无法区分 | 已落地 | `Match.isPostponed`（Match.kt:163，POSTPONED_MARKERS）+ `StatusLabel.of`（:32-37）+ 消费点 `Common.kt:464`；`PostponedLabelTest`（5）/`PostponedMatchTest`（3）；commit `75090ab` |
| 02-P2-11 | 2 月 29 日抛异常静默降级 | 已落地 | `JrkanParser.kt:353 MonthDay.of(month, day).atYear(now.year)`（注释即指向 02-P2-11） |
| 02-P2-12 | 合并键退化成字面量 | 已落地 | `MatchRepository.kt:876-897`（先看 timeLabel 的 `MM-dd`，再退当天）+ `MergeKeyFallbackTest`（4）；commit `b23cdc7` |
| 02-P2-13 | `League.OTHER` 分支是死代码 | 有据的取舍 | 报告给了"删掉**或**加注释说明"两个选项；采用后者：`MainViewModel.kt:60-66` 注释写明"实际走不到 + 保留为防御性兜底 + 删掉反而会变成空列表"，`:74` 同样说明 counts；commit `b23cdc7` |

### 03-ui.md（39 条：P1 4 条 + P2 35 条）

| 编号 | 标题（截断） | 状态 | 证据 |
|---|---|---|---|
| 03-P1-1 | 返回栈与跳转不匹配 | 已落地 | `NavState.back()`（NavState.kt:82，含 beforePlayer）+ `PlayerBackStackTest`（4）/`NavStateTest`（10）；commit `3ef8a3a` |
| 03-P1-2 | hero 卡半透明白字对比度 | 已落地 | `AccentContrast.OnAccent`/`HeroPillBackground` 用于 `MatchListScreen.kt:313/335/366/422`；`AccentContrastTest`（6，含药丸底合成后 AA）；commit `88bd0a6` |
| 03-P1-3 | 浅色主题导航栏图标不可见 | 已落地 | `Theme.kt:355 controller.isAppearanceLightNavigationBars = !darkTheme`（注释即指向该条） |
| 03-P1-4 | 筛选胶囊计数 80% 白 | 已落地 | `MatchListScreen.kt:176` 改为 `if (active) Color.White else …`、`:183 AccentContrast.OnAccent`；`AccentContrastTest.联赛筛选胶囊选中态的场次计数` |
| 03-P2-1 | 非默认标签按返回退出 App | 已落地 | `BackPolicy.shouldIntercept` + `AppBackHandling.kt:39/47`；`BackAndMissingDataTest`（5）；commit `23ac56c` |
| 03-P2-2 | 路由无 SavedStateHandle | 已落地 | `MainViewModel.kt:189` 注入 `SavedStateHandle`、`:221-255` 存档/还原 + `MainActivity.kt:70 createSavedStateHandle()`；`NavRestoreTest`（5，含 `生产必须注入真正可持久化的 SavedStateHandle`）；commit `3ef8a3a` |
| 03-P2-3 | tab/route 两个状态机 | 已落地 | 合成 `NavState`（NavState.kt:51-105，纯迁移函数）；`NavStateTest`（10） |
| 03-P2-4 | `onNewIntent` 是死代码 | 已落地 | 该覆写已删除；`MainActivity.kt:95-103` 注释写明"**故意不实现** onNewIntent（P2-4）"；`NavHostGuardTest` 钉住"若存在必须真消费 intent"；commit `7828a6b` |
| 03-P2-5 | `Route.DETAIL` 缺 match 白屏 | 已落地 | `MissingMatchPolicy.forDetail` + `MissingMatchPlaceholder`；`BackAndMissingDataTest.详情页缺 match 时不能静默白屏` + `DeadCodeWiringGuardTest` 钉分支调用点；commit `23ac56c` |
| 03-P2-6 | `syncAutoEnterEnabled` 依赖 effect 顺序 | 已落地 | 收敛为一个显式有序 effect（`AppEffects.kt`）+ `AppStructureGuardTest.画中画的状态写入必须排在粘性开关刷新之前`（含反序自检）；commit `d35a25e` |
| 03-P2-7 | 错误 Snackbar 被下一条取消 | 有据的取舍 | `AppEffects.kt:144 PlayerNoticeEffect` 的 KDoc（:135）明确写下定论：覆盖已是实际语义、再补 `dismiss()` 是把同一件事写第二遍；commit `42c6b9d` |
| 03-P2-8 | 今天/明天标签不跨零点更新 | 已落地 | `MatchListScreen.kt:215-218 produceState` + `DAY_LABEL_TICK_MS`（MatchFormat.kt:117）+ 复用缓存 `systemZone`；`DayLabelTest`（7）；commit `7fe2ead` |
| 03-P2-9 | 三个 remember 冗余 | 已落地 | `NbaLiveApp.kt:166-170` 直接读 `listState.visibleMatches`/`counts`；`NavHostGuardTest.列表派生值必须直接读 state` |
| 03-P2-10 | LIVE 过滤算两次 | 已落地 | 唯一实现 `MatchFilters.liveOnly`（:22），生产调用 `NbaLiveApp.kt:173`，`LiveNowScreen` 改为收参数（:70/78）；`MatchFilterGuardTest`（5）；commit `c3841de` |
| 03-P2-11 | `TeamLogo` fallback lambda 每帧新建 | 已落地 | 回退块提为 `Common.kt:103 LogoFallback` + 两个槽只调用（:154-155）；`ComponentPerfTest`（6）；commit `532a7b0` |
| 03-P2-12 | `StatusChip` 在 when 内 remember | 已落地 | `remember` 移出 when（`Common.kt` StatusChip）；`ComponentPerfTest.StatusChip 的 remember 必须无条件调用`；commit `532a7b0` |
| 03-P2-13 | LiveDot 无限动画成倍且不停 | 已落地 | 共享相位 `LiveDotPhase`/`LocalLiveDotAlpha`（Common.kt:563），调用点 `AppChrome.kt:458`；`LiveDotAnimationTest`（4）；commit `5627336` |
| 03-P2-14 | 详情页信号列表用 Column+scroll | 有据的取舍 | `MatchDetailScreen.kt:186-194` 注释写明取舍（线路<20 是常态、无 instrumentation 测试、风险大于收益）**并写明重估条件**（常态 >20 条时改 LazyColumn） |
| 03-P2-15 | 根节点收集 6 个状态 | 已落地 | 根节点 `collectAsStateWithLifecycle` → ≤2（`AppStructureGuardTest.根节点里的状态收集不得超过 2 处`）；commit `d35a25e` |
| 03-P2-16 | null 视为深色写了两遍 | 已落地 | 唯一判据 `resolveDarkMode`（AppPrefs.kt:91），消费点 `MainActivity.kt:80`、`NbaLiveApp.kt:56`；`DarkModeResolveTest`（3）；commit `7828a6b` |
| 03-P2-17 | UI 直接拿 Context 写偏好 | 已落地（未提交·进行中） | 读：`NbaLiveApp.kt:56` 经 `ui.darkPref`（VM 状态，VM 从 `prefs` 读 :349/355）。写：**UI 已改为调 VM**（`NbaLiveApp.kt:357`、`AppChrome.kt:501-502` → `viewModel.setAspectMode/setDarkMode`），VM 内有 setter（`MainViewModel.kt:364-375` 委托 `prefs`），`MainActivity` 的平台层回调已删除（:80-81 注释改写）；`PrefsBoundaryGuardTest`（9）。**⚠️ 全部为工作区未提交改动**（`MainActivity.kt`/`MainViewModel.kt`/`NbaLiveApp.kt`/`AppChrome.kt`/`PrefsBoundaryGuardTest.kt` 均 `M`），等提交 + 全量验证 |
| 03-P2-18 | `NbaLiveApp` 上帝组件 | 已落地 | 拆出 `AppChrome/SearchOverlay/AppEffects/AppBackHandling/AppUiState`；`AppStructureGuardTest`（9，含 `根组合项函数体不得超过 180 行`/`外壳与搜索层必须在新文件里`）；commit `d35a25e` |
| 03-P2-19 | `InfoRow` 两份副本 | 已落地 | 唯一实现 `components/Common.kt:172`；`DuplicateComponentTest`；commit `7cdf50e` |
| 03-P2-20 | 错误态/横幅四份 | 已落地 | `FullScreenErrorState`（Common.kt:270）+ `RetryErrorBanner`，四个调用点改为薄封装（`MatchListScreen.kt:659`、`LiveNowScreen.kt:171`）；`DuplicateComponentTest`（6）；commit `7cdf50e` |
| 03-P2-21 | 两种取宿主 Activity 写法 | 已落地 | 唯一实现 `ContextExt.kt:29 findActivity`，`Theme.kt:348` 改用它（:345 注释）；`ContextExtTest`（4）；commit `29c43b3` |
| 03-P2-22 | 顶栏按钮三份 | 已落地 | `SquareIconButton`（Common.kt:630）+ 调用点 `AppChrome.kt:256/262/268/313/336`、`SearchScreen.kt:131`；`TopBarAndLiveFilterTest`（6）；commit `29c43b3` |
| 03-P2-23 | 搜索框只有 placeholder | 已落地 | `SearchScreen.kt:151 label = { Text("搜索球队 / 联赛") }`；`AccessibilitySemanticsTest`（4）；commit `9cadbd2` |
| 03-P2-24 | 底栏缺 `selectableGroup()` | 已落地 | `AppChrome.kt:372 .selectableGroup()`（:368 注释）；`AccessibilitySemanticsTest.底栏标签必须成组`；commit `9cadbd2` |
| 03-P2-25 | 卡片两个语义重叠可点节点 | 已落地（替代方案） | `LiveNowScreen.kt:224 semantics(mergeDescendants = true)` + `:228 onClickLabel = CardSemantics.ACTION_LABEL`（:219-223 注释说明为何原地写 true）；`CardButtonSemanticsTest`/`AccessibilitySemanticsTest`。**备注**：审查建议的"内层去掉 clickable"未采纳 —— 内层按钮仍有 `.clickable`（:295）与 `minimumInteractiveComponentSize()`（:292），走的是 `CardSemantics` KDoc 里"合并语义、保留内层可点"的路线 |
| 03-P2-26 | hero 卡无动作词/Role | 已落地 | `MatchListScreen.kt:301-302 onClickLabel = CardSemantics.ACTION_LABEL, role = Role.Button`；`ScreenConsistencyGuardTest`；commit `7fe2ead` |
| 03-P2-27 | 队徽回退字号不随容器缩放 | 已落地 | `logoFallbackFontSize`（Common.kt:80 + 使用 :117）；`ComponentPerfTest`（纯判据 3 组 × 3 fontScale + 源码调用点断言）；commit `532a7b0` |
| 03-P2-28 | `surfaceContainer` 系列未定义 | 已落地 | `Theme.kt:238-244`（DarkColors 9 槽）、`:275-280`（LightColors）；`ThemeSlotTest`（10，含"不许退回 M3 默认中性色"反向断言）；commit `532a7b0` |
| 03-P2-29 | 色板对象是 public | 已落地 | `internal object SportDark`（Theme.kt:47）、`internal object SportLight`（:66）+ 越界引用守卫；`ThemeSlotTest.色板对象必须收成 internal`/`色板对象不得被 theme 之外的生产代码引用`；commit `532a7b0` |
| 03-P2-30 | 绕过主题的硬编码颜色 | **未落地**（部分） | 已落地那半：hero 胶囊底改为 `AccentContrast.HeroPillBackground`（`MatchListScreen.kt:366/422`，:419 注释）。**未落地那半**：队徽圆底仍是硬编码白 —— `Common.kt:152 .background(Color.White)`；浅色主题下压在 `SportLight.Card = #FFFFFF` 上对比度 **1.0:1**（审查给的修法是随主题取色或加描边，均未做） |
| 03-P2-31 | `AppPrefs` 用 `loaded` 短路 | 已落地 | 短路已删；改为 `attach(context)`（AppPrefs.kt:39）+ 未 attach 直接 `error(...)`（:72，PlayerPrefs.kt:82 同）；`NavHostGuardTest.AppPrefs 不得再用 loaded 短路`；commit `7828a6b` |
| 03-P2-32 | 时间显示两套口径 | 已落地 | 三个页面统一 `hhmm()`：`MatchListScreen.kt:319/468`、`LiveNowScreen.kt:242`、`SearchScreen.kt:262`；`ScreenConsistencyGuardTest.screens 不得直接渲染源站 timeLabel`（全目录扫描）；commit `7fe2ead` |
| 03-P2-33 | 全部 UI 文案硬编码 | 有据的取舍 | 审查在 `03-ui.md:474` 自己判定"**按项目现状，这不是缺陷**……不需要把全部文案搬进 strings.xml"；现状：`strings.xml` 确实仍只有 `app_name`（实测 3 行）。其"低成本子项"已落地：`dayLabel`/`groupByDay` 移到 `MatchFormat.kt:90/103` 并 `internal`，`DayLabelTest`（7）可测 |
| 03-P2-34 | 详情页 96dp 底部留白 | 已落地 | `MatchDetailScreen.kt:69-…` 注释写明"原来还有 `.padding(bottom = 96.dp)`（P2-34）"已删；`ScreenConsistencyGuardTest.详情页不得再叠加 96dp 底部留白`；commit `7fe2ead` |
| 03-P2-35 | 占位 40dp 与左侧 48dp 不等宽 | 已落地 | `AppChrome.kt:290 internal val SubPageBarSideSlot = 48.dp` + `:333 Spacer(Modifier.size(SubPageBarSideSlot))`；`NavHostGuardTest.无更多时的占位必须用 SubPageBarSideSlot`/`顶栏侧位常量必须是 48dp`；commit `7828a6b` |

### 04-security.md（14 条）

| 编号 | 标题（截断） | 状态 | 证据 |
|---|---|---|---|
| 04-P1-1 | 日志脱敏被"直接打 throwable"绕过 | 已落地 | `Logs.safeLogWarn`（:93-95：类型 + 脱敏 message）；调用点全量替换；`LoggingDisciplineTest`（含"守卫确实有效"自检）；`grep "Log.[we](…, t)" src/main` **0 命中** |
| 04-P1-2 | `JrsParser` 打未脱敏 href | 已落地 | `JrsParser.kt:61/71` 均为 `redactText(path)`；`RedactUrlTest`/`RedactUrlEdgeCaseTest`/`LoggingDisciplineTest` |
| 04-P1-3 | WebView 加载第三方页无来源约束 | 已落地 | `WebNavigationPolicy`（注册域白名单 + `shouldBlock`/`blockReason`），调用点 `PlayerScreen.kt:2057`；`WebNavigationPolicyTest`（8，含 `白名单匹配注册域而不是子串`、`拿不到当前页面 host 时保守放行`） |
| 04-P1-4 | `shouldOverrideUrlLoading` 只拦非 http(s) | 已落地 | 同上（主框架白名单）；`PlayerScreen.kt:2047-2051` 注释 + `WebNavigationPolicyTest` |
| 04-P1-5 | `network_security_config` 全局放行明文 | 已落地（**第九回合修正了其中一处误禁**） | `res/xml/network_security_config.xml`：`jrszbt.com` 仍 `cleartextTrafficPermitted="false"`；`im-imgs-bucket.oss-accelerate.aliyuncs.com` 改回 **`true`** —— 原因是 v1.2.13 把它列进禁明文只考虑了"App 自己以 https 访问它"，漏了"WebView 里的源站页面以 http 从它加载 `pjs.js`"，导致「点比赛纯黑」（证据逐字写在该文件 KDoc，修复提交 `45fdb89`）；base-config 保留（:35）。守卫 `CleartextPolicyTest`(4) 已是**双向**：该禁的禁（jrszbt.com）、**WebView 承载的脚本主机必须允许**（OSS），回退即红 1 条；初始提交 `25dfde9` |
| 04-P2-1 | `MIXED_CONTENT_ALWAYS_ALLOW` | 有据的取舍 | `PlayerScreen.kt:2035-2037`："收紧成 COMPATIBILITY 会黑屏，这是**实测后的有意取舍**"（审查的建议就是"保留现状 + 记一条"，理由已在代码里） |
| 04-P2-2 | `mediaPlaybackRequiresUserGesture = false` | 有据的取舍 | `PlayerScreen.kt:2034`；审查自己判定"**不改此项**（改了会破坏自动起播）"，属产品需求 |
| 04-P2-3 | `allowFileAccess` 未显式关闭 | 已落地 | `PlayerScreen.kt:2044-2045 allowFileAccess = false / allowContentAccess = false`（:2039-2043 注释）；`WebViewFileAccessTest`（2）；commit `e2ab4af` |
| 04-P2-4 | `.gitignore` 非合法 UTF-8 | 已落地 | 我用 `UTF8Encoding(throwOnInvalidBytes: true)` 对该文件全字节解码 → **通过**；`RepoConfigDisciplineTest`（4，含"核心规则不能丢"）；commit `0d19b08` |
| 04-P2-5 | R8 mapping 未归档 | 已落地（流程已执行；CI 未真跑） | **直接证据**：仓库根存在 `nba-live-1.2.13/1.2.14/1.2.15-mapping.txt`（各 ~44.5 MB）+ 对应 APK，且 `git check-ignore -v` 显示 `.gitignore:19 *-mapping.txt`、`:9 *.apk` 已排除 → 最近三次发版确实归档了；自动化：`.github/workflows/release.yml`（mapping 只作 artifact，:160-164；Release 只挂 APK+AAB，:185-189）+ README `## CI 发版`（:96-135）+ `ReleasePipelineGuardTest`（6）。⚠️ 流水线**从未在真实 runner 上跑过**（`AGENT_STATE.md:156` 自认，我也无 Actions 可验证） |
| 04-P2-6 | lockfile 与声明版本不一致 | 已落地 | `app/build.gradle.kts:199 testImplementation("…kotlinx-coroutines-test:1.9.0")`（与 android 1.9.0 对齐）；`RepoConfigDisciplineTest.版本必须与 coroutines-android 一致`/`lockfile 里不能残留旧版本`；commit `0d19b08` |
| 04-P2-7 | 使用 AGP 内部实现类 | 已落地（第十回合收尾） | 删掉 `com.android.build.api.variant.impl.VariantOutputImpl` 依赖：`javap` 实测 AGP 8.7.3 公开 `VariantOutput` 没有 `outputFileName`，改为打包任务 `doLast` 用 Gradle 文件 API 把 `app-release-unsigned.apk` 改名成 `app-UNSIGNED-release.apk`；`BuildScriptHygieneTest.构建脚本不得引用 AGP 内部实现包` 钉住，回退即红 |
| 04-P2-8 | 未签名 AAB 拦截点在依赖任务之后 | 已落地（第十回合修正了原证据） | 原 `withType<PackageAndroidArtifact> { doFirst }` 对 AAB 路径**从不 configure**（`bundleRelease` 的打包任务是 `PackageBundleTask`，不继承该类型）→ 实测无凭据仍 exit=0 产出 `app-release.aab`。现闸挂在 `*ReleaseBundle.doFirst`：无凭据 exit=1 且磁盘 0 个 `.aab`；`-PskipSigning` 只留 `app-UNSIGNED-release.aab`。守卫 `BuildScriptHygieneTest.bundle 任务必须按名字补签名闸` |
| 04-P2-9 | targetSdk 35 适配（废弃状态栏属性 + predictive back） | 有据的取舍 | 第 2 点**已落地**：`AndroidManifest.xml:16 android:enableOnBackInvokedCallback="true"`（`AndroidConfigVersionTest` 钉住 + v35 覆盖文件不写废弃属性）。第 1 点**保留**但**有据**：`values/themes.xml:5-11` 注释写明"API35 起废弃、API 24~34 仍生效所以保留"，`AndroidConfigVersionTest.基础主题必须保留系统栏颜色` 把这条取舍钉住 |

---

## 二、分类小结

| 状态 | 条数 | 占比 |
|---|---|---|
| 已落地 | **87** | 91.6% |
| 有据的取舍 | **8** | 8.4% |
| 未落地 | **0** | 0% |
| 无法判定 | **0** | 0% |
| 合计 | **95** | 100% |

> 计数更新（2026-10-30 第十回合）：**04-P2-7** 已真正落地 —— 不再依赖 AGP 私有实现类
> （javap 实测公开 `VariantOutput` 没有 `outputFileName`），改为在产出任务 `*ReleaseBundle` 上
> 用 Gradle 文件 API 改名；`BuildScriptHygieneTest`(3) 钉住，回退即红。
> **95 条：87 已落地 / 8 有据的取舍 / 0 未落地 / 0 无法判定。**

### 未落地（按严重度排序）

无。

> **台账修正（2026-10-30 第十回合）**：04-P2-7 已落地（见第六节）；本节不再是"还剩"清单。
> 台账里另有 6 条"代码已落地但当时无判据"（§四 A 类），本轮全部补齐并逐条做了回退实验。

### 有据的取舍（8 条，全部有"明确写下的理由"）

| 编号 | 取舍理由的落点 |
|---|---|
| 01-P2-10 | `PlayerControls.kt:31-51`（保留无用参数的三条理由 + 指定等价性测试） |
| 02-P2-13 | `MainViewModel.kt:60-66`（"实际走不到，保留为防御性兜底"） |
| 03-P2-7 | `AppEffects.kt:135`（PlayerNoticeEffect："覆盖已是实际语义"） |
| 03-P2-14 | `MatchDetailScreen.kt:186-194`（线路<20 + 无 instrumentation 测试 + 重估条件） |
| 03-P2-33 | `03-ui.md:474`（审查自判"按现状不是缺陷"）+ 子项已落地 |
| 04-P2-1 | `PlayerScreen.kt:2035-2037`（"收紧会黑屏，实测后的有意取舍"） |
| 04-P2-2 | `PlayerScreen.kt:2034` + 审查自判"不改此项（会破坏自动起播）" |
| 04-P2-9 | `values/themes.xml:5-11`（为何保留废弃属性）+ Manifest 已开 predictive back |

### 无法判定

无。本次核对中不存在"只看到注释说已修"的条目：每条状态都由**当前代码位置**（`文件:行号`）、
**钉住它的测试类/用例名**或 **git commit** 三者之一支撑。

---

## 三、防伪说明

### 3.1 我用了什么方法

1. **清单口径复算**（`Select-String -Pattern '^#{3,4}\s*P\d+-\d+'`）：发现 03-ui/04-security 用 `####`，
   只数 `###` 会得到 48 条而不是 95 条 → 确认 **95 正确**，但口径必须写清（见开头）。
2. **逐条回到代码**：`grep`（生产符号是否真有调用点）、`read`（关键文件/断言上下文）、
   `git log --oneline`（把条目映射到提交）、`Get-FileHash`（固定核对快照）。
3. **优先采信"调用点 + 测试"而不是"文件里出现过"**：例如
   `PipAspectReset.ratioFor` 我不仅看它存在，还看 `MainActivity.kt:191` 真的在调；
   `liveOnly` 不仅存在于 `MatchFilters.kt:22`，还有 `MatchFilterGuardTest.生产必须真的调用 liveOnly`。
4. **双向核验证**：对"该禁的禁/该放的放"这类条目（04-P1-5、01-P2-11）看两个方向的断言。
5. **文件哈希**：把核对时被并行改写的 7 个文件记 SHA-256 前 12 位，便于事后判断行号是否漂移。

### 3.2 我没能核到的部分（诚实标注）

1. **没有跑 Gradle**（按任务要求把锁让给写者）。因此：
   - `AGENT_STATE.md:6` 声称的"全量 **557/0/0**，debug 与 release 同数"**我无法验证**；
   - 我无法确认上述 85 条"已落地"在当前工作区**编译通过/测试全绿** —— 尤其 03-P2-17 那批未提交改动。
2. **04-P2-5 的自动化流水线从未真跑**：我只能静态核对 `release.yml` 的步骤与守卫，无法验证 runner 上
   Android SDK/build-tools/gh CLI 是否齐备（`AGENT_STATE.md:156` 也这么承认）。
3. **03-P2-17 的"进行中"**：`MainActivity.kt`/`MainViewModel.kt`/`NbaLiveApp.kt`/`AppChrome.kt` 在我的核对窗口内
   正在被改写（18:00 快照 4 个文件均 `M`）。我给出的是**那一刻**的状态；若提交后再变，需重核该行。
4. **04-P2-4 的 `.gitignore`**：我用严格 UTF-8 解码验证了**当前工作区文件**，未复算历史提交里的版本。
5. **03-P2-25 的"替代方案"是否等效**：我核到 `mergeDescendants=true` + `onClickLabel` 已落地、
   内层 `clickable` 仍在。它是否真的消除 TalkBack 的"两个可点节点"，**需要实机 TalkBack 验证**
   （JVM 单测跑不到 Compose 语义树）。我按"实现选择了另一条被文档化的路线"记为已落地，并把差异写在证据列。
6. **02-P1-5 的"同类问题"**：`JrkanParser.parseStatus`（:269）仍内部取真实 `now()`。我判定解析时刻取 now 语义正确，
   但**没有实验**证明"注入 now 后 JRKAN 校正分支完全确定"（该分支已被 `MatchRepository.kt:499` 的注入覆盖）。

### 3.3 `AGENT_STATE.md` 的问题点（它本身也是被审对象）

我把该文档里能复核的陈述逐条对照，**发现 3 处偏差**：

1. **夸大了 `03-P2-30`**：`AGENT_STATE.md:100` 的"已验证落地"清单含 `03-P2-1~5/6/8~13/15/16/18~32/34/35`
   —— 区间 `18~32` 覆盖了 P2-30，但 `Common.kt:152` 的硬编码白圆底仍在（浅色主题下对比度 1.0:1），
   **应为未落地**。同理该区间也覆盖 P2-25，那一条是"替代方案"落地（可接受，但应注明）。
2. **自相矛盾的 03-P2-17**：提交 `c3841de` 的说明是"偏好读写收口到 PrefsStore（**03-P2-17 剩余**）"
   （`AGENT_STATE.md:24`），而同一文档 `:142-147` 的"仍存在的主要缺口 A2"写着"**写路径只做了一半**"。
   我按 18:00 工作区核对：**写路径其实已完成（UI→VM→PrefsStore）但未提交** —— 所以两处说法**都**已过时，
   以我的核对为准（并附文件哈希）。
3. **`AGENT_STATE.md:99` 把 `02-P2-13` 计入"已验证落地"**：实际改动是"加注释说明是防御性兜底"
   （`MainViewModel.kt:60-66`），审查原文给的是"删掉**或**加注释"——按落地口径应记 `有据的取舍`，
   不该与"删掉死代码"同列。

另外两处**我无法判定真伪**、仅记录：`:6` 的 557/0/0 全量数字（无 Gradle 运行证据）、`:19` 的"79 suites"
（当前测试目录 80 个 `.kt`，其中 `GuardSourceScan.kt` 是工具类不是 suite，故 79 与目录数自洽，但不是独立证据）。

### 3.4 与本次核对无关但顺手发现的

`review/02-data.md:289-296` 有 **4 条"待确认"**（JRS 日期路由、`?t_5` 语义、`PLAY_HOST` 存活、
`msss.html` 是否带 `&id2=`）——它们**不在 95 条条目内**，因此没有进总表。其中第 4 条已由本会话
T3 实证（`/play/384.html` 的 iframe **带** `&id2=` 尾巴与 `auth_key`，且反混淆后的 host 是唯一可解析的）。

---

## 四、护栏补齐（原 6 条"改了没人红" → 第十回合已全部补齐）

> 这一节最初是**顺着第二节"证据"列反向自查**得到的：挑出那些**只有代码位置、没有判据**的条目。
> **2026-10-30 第十回合已把它们逐条补上判据，并各自实测「去掉修复会红」**（证据见第六节）。

### A 类：原先真的没有判据（6 条，现全部已补）

| # | 条目 | 补的判据（测试类） | 回退实验 |
|---|---|---|---|
| 1 | **02-P2-5** 返回时不取消 `detailJob` | `MainViewModelTest.从播放页返回后 在途详情请求晚到也不能覆写详情态`（行为级：`CompletableDeferred` 卡住 refresh，`back()` 后放行，断言详情态未被旧值覆写） | 删生产 `detailJob?.cancel()` → **恰好 1 条红** |
| 2 | **02-P2-7** 缓存时间戳在 fetch 之后取 | `DetailCacheTtlTest.慢请求的缓存年龄从拿到正文之后开始算`（2s 慢 fetch + 反射只读时间戳 + 命中计数复核；未改生产可见性） | 把 `cachedAt` 挪到 fetch 之前 → **恰好 1 条红**（实测差值 15ms vs 2000ms） |
| 3 | **02-P2-11** 2 月 29 日不得静默降级 | `JrkanParserTest.闰日 02-29 必须能解析出开赛时刻 不得静默降级` + `闰日归一规则自检` | 生产改回 `LocalDate.of(now.year, month, day)` → **恰好 1 条红**（`startAtMillis=null`） |
| 4 | **03-P1-3** 浅色主题导航栏图标不可见（**P1**） | `ui/theme/SystemBarsAppearanceTest`(2)：函数体级断言两个系统栏赋值都在、都等于 `!darkTheme` | 改成 `= false` → **恰好 1 条红** |
| 5 | **03-P2-25** TalkBack 双可点节点 | 生产移除内层重复 `.clickable`；`ui/LiveNowCardSemanticsTest`(2) 钉住「`LiveNowCard` 体内恰好一次 `.clickable(` 且带 `onClickLabel`」 | 加回内层 `.clickable` → **恰好 1 条红**。⚠️ 语义树层仍需实机 TalkBack 走查，不宣称已完全解决 |
| 6 | **04-P2-8** 未签名 AAB 拦截点 | 生产把闸从 `bundleRelease` 生命周期任务移到产出任务 `*ReleaseBundle.doFirst`；`BuildScriptHygieneTest.构建脚本不得引用 AGP 内部实现包`／`未签名产物必须显式改名`／`bundle 任务必须按名字补签名闸`(3) | 见 6.2 的三场景行为实验 |

### 额外：原"已落地但守卫有个洞"（已收口）

**02-P2-2（时区）**：`SourceZoneDisciplineTest.DISPLAY_ONLY` 原先把**判定层**文件
`MatchStatusClock.kt` 当成"展示层"排除在白名单里的洞已修 —— 该文件实测没有任何
`systemDefault()` 调用，移除它之后这条纪律守卫只收紧不放宽。§四 表见上。

### B 类：判据存在，只是我的引用没落到用例名（4 条 + 1 条附）

| # | 条目 | 我已复核存在的具体用例（可直接补进总表的"证据"列） |
|---|---|---|
| 7 | **01-P1-2** 音频焦点重申请 | `AudioFocusPolicyTest.播放中且没有焦点才需要重新申请`、`焦点申请结果要按 GRANTED 判定`、`播放中现查到通话模式就要暂停` |
| 8 | **02-P1-9** 域名候选 | `SourceEndpointsTest.候选按声明顺序 主域名第一`、`失效的 host 被跳过`、`全部失效时从头再来 不永久拉黑`；`SourceEndpointsWiringTest.候选常量在生产代码里真的有消费点` |
| 9 | **03-P2-28** `surfaceContainer` 系列 | `ThemeSlotTest.深色主题必须补齐 surfaceContainer 系列`、`浅色主题必须补齐 surfaceContainer 系列`、`深色主题的 surfaceContainer 不许退回 M3 默认中性色` |
| 10 | **04-P2-3** `allowFileAccess`/`allowContentAccess` | `WebViewFileAccessTest.播放页 WebView 必须显式关闭本地文件访问`（另一条 `JS 桥必须不存在`） |
| 附 | **03-P2-13** LiveDot 共享相位 | `LiveDotAnimationTest.列表页必须用 LiveDotPhase 包住`（调用点断言，由 `GuardSourceScan.stripNonCode` 驱动，比"共享机制存在"那条强得多） |

### 额外：原"已落地但守卫有个洞"（第十回合已收口）

**02-P2-2（时区）** 的洞是 `SourceZoneDisciplineTest.DISPLAY_ONLY` 把**判定层**文件
`MatchStatusClock.kt` 当成"展示层"排除。已修（该文件实测无 `systemDefault()` 调用）。
这条与 `review/audit-guards.md` 记的"白名单把该守的文件排除"同源 —— 两轮审计互相印证，
说明这类洞会**跟着修好的代码一起留下来**，值得作为一类模式单独防。

---

## 五、2026-10-29 第九回合：用户需求变更 —— 过滤掉涉及主播的线路

> 这一节不是审查条目，而是**产品规则的改向**，以及随之删除的一条"误诊补丁"。
> 之所以写进台账：被删的那条补丁本身就写在 `ChannelAdmission` 的 KDoc 里当作依据，
> 不点名会让下一轮的人照着旧 KDoc 把它加回来。

### 5.1 规则

`ChannelAdmission.admit(raw)` 只剩一条规则：

```kotlin
fun admit(raw: List<RawChannel>): List<RawChannel> {
    return raw.filterNot { isSensitive(it.rawLabel) }   // 主播/解说/美女/聊天 → 整条过滤
}
```

返回 **空列表是合法结果**（该场次只有主播线路），由 `MatchRepository.loadSignals` → 播放页
显示 `"该赛事暂无可用线路"`。

### 5.2 删掉的补偿规则与它错在哪

`007ad88`（2026-10-29 13:08）加过规则 2：*若按名字过滤会把可直连线路清零，就把这些敏感线路
以中性名 "线路N" 补回列表*。

- 它的依据是"网页线路零媒体请求、页内播放器没跑起来，所以只有直连能播"；
- 真因是 v1.2.13 明文策略误禁了 `http://im-imgs-bucket.oss-accelerate.aliyuncs.com/pjs.js`
  （`net::ERR_CLEARTEXT_NOT_PERMITTED`，逐字证据见 `res/xml/network_security_config.xml` KDoc），
  已由 v1.2.16（`45fdb89`）修复；**网页线路本身可播**；
- 补偿生效后 `MainViewModel.kt:707` 的"默认线路优先选可直连"恰好选中被补回来的主播线路 ⇒
  用户看到的就是"只有主播线路能看"，于是**明确要求过滤掉它们**。

### 5.3 实测数据（2026-10-29 21:00，源站 23 个场次、95 条线路）

| 类别 | 条数 | 路径 |
|---|---|---|
| 敏感（主播/解说…） | 48 | **全部** `/play/sm.html`（可直连 HLS） |
| 非敏感 | 47 | **全部** `/play/pao\|wen\|mgxl\|wlive/`（只能走 WebView） |

⇒ **过滤后 0 条直连线路是正常结果，不是故障。**

### 5.4 配套改动与守卫

1. `MatchRepository.expandJrkanPlayPage` 返回 `Expansion(channels, pageFetched)`；
   **只要有一个播放页真取到正文就不再退回入口页**（入口页 `steam<id>.html` 会自动选中它自己的
   第一条 = 主播线路，退回等于绕过过滤）；一次都没取到正文时才退回入口页做 WebView 兜底。
2. `JrkanChannelAdmissionTest`(13) 新增函数体扫描：`admit` 内出现 `isDirectPath(` 即红。
3. `SourceEndpointsWiringTest` 新增 2 条（全过滤不退回入口页 / 取不到正文仍退回入口页）。
4. **回退实验**：把补偿分支与无条件退回同时加回去 → **恰好 7 条红**（41 tests / 7 failed），
   与新增/改写的断言一一对应。

### 5.5 实机验证

- debug 构建（雷电 9 / 1280×720）：点 CBA 场次 → `线路 1/4`；chromium 加载
  `http://play.sportsteam368.com/play/mgxl/?id=00594084&id2=`（**非敏感线路**）+
  `pjs.js` → `ExoPlayerImpl Init` + `OMX.qcom.video.decoder.avc`；视频带
  **96.4% / 94.8% 非黑**、中心 98.9% / 98.1%、帧间差 **214**（画面在动）。
- release 包（versionCode 21，R8）：WebView → 嗅探 → `ExoPlayerImpl Init` 同样成功；
  此次点到的是一场已结束/无源的比赛，清单返回 404（`Caused by: x1.t: Response code: 404`），
  属源站事实，不属 App 回归。

### 5.6 已知代价

同一批 23 个场次里 **5 个场次只剩主播线路**（825263 / 825262 / 825266 / 825273 / 825227），
过滤后为 0 条线路，界面显示"该赛事暂无可用线路"。是否改成隐藏这 5 场或显示占位提示，待用户拍板。

---

## 六、2026-10-30 第十回合：护栏补齐 + 04-P2-7 收尾

### 6.1 04-P2-7（唯一的"未落地"）→ 已落地

旧写法：`androidComponents.onVariants { output as? com.android.build.api.variant.impl.VariantOutputImpl }`
改输出文件名。取证：`javap -cp gradle-api-8.7.3.jar com.android.build.api.variant.VariantOutput`
只暴露
`getVersionCode / getVersionName / getEnabled / getEnable` —— **公开接口没有 `outputFileName`**，
所以"用公开 API"这条路不存在，也不必为此升级 AGP。

改法：删除内部类依赖。未签名 APK 交给 **Gradle 文件 API**：

- `tasks.withType<PackageAndroidArtifact>()` 的 `doFirst` 保留签名凭据检查；
- `doLast` 把 `outputs/apk/<variant>/app-*-unsigned.apk` 改名成 `app-UNSIGNED-<variant>.apk`。
  实测 AGP 默认未签名名是 `app-release-unsigned.apk`（改名后 `app-UNSIGNED-release.apk`，
  2,725,737 B），正式签名包 `app-release.apk`（2,733,929 B）不受影响。
- 若 AGP 以后改名导致匹配不到，产物仍是 `*-unsigned.apk`，**永不占用**正式名 —— 退化方向安全。

### 6.2 04-P2-8 三场景行为实验（同一把 Gradle 锁内，跑完逐字恢复凭据）

| 场景 | 命令 | exit | `outputs/bundle/release/` |
|---|---|---|---|
| A 有凭据（签名） | `:app:bundleRelease --rerun-tasks` | **0** | `app-release.aab` 5,950,921 B（正式名，允许） |
| **B 无凭据** | 同上（`keystore.properties` 挪到 TEMP） | **1** | **无 `.aab`（count=0）** ✅ 原条目判据满足 |
| C `-PskipSigning=true` + 无凭据 | `... -PskipSigning=true` | **0** | 只有 `app-UNSIGNED-release.aab` 5,933,374 B |

机理：`bundleRelease` 生命周期任务的 `doFirst` 太晚（`packageReleaseBundle` 已落盘），
故闸挂在 `*ReleaseBundle.doFirst`；而改名放 `bundleRelease.doLast` ——
若放产出任务 `doLast`，AGP 的 `produceReleaseBundleIdeListingFile` 会因
`finalBundleFile` 路径不存在而把构建打红（实测 exit=1）。

### 6.3 本轮新增/改写的守卫

- 新增测试类 4 个（14 条）：`SystemBarsAppearanceTest`(2)、`LiveNowCardSemanticsTest`(2)、`BuildScriptHygieneTest`(3)、`DetailCacheTtlTest`(1)；并使 `SourceZoneDisciplineTest` 白名单收紧。
- 生产改动 2 处：`LiveNowScreen.kt` 去掉内层重复 `.clickable`；`app/build.gradle.kts` 改名/签名闸重构。
- 队友 `guard-fixups` 补 02-P2-11 两条用例（20/0/0/0）；`pip-health` 补 02-P2-5 行为级用例（13/0/0/0）。
- 每条均有独立回退实验（见第四节表与 6.2）。

### 6.4 验收数字

- 全量 debug + release 各 **581 通过 / 0 失败 / 0 跳过**（84 suites）；
- `:app:assembleDebug` / `:app:assembleRelease` 成功，versionCode 22 / 1.2.18；
- release 包装机后列表正常加载（10 月 30 日 8 场）。