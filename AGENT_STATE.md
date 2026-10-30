# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）
- 构建/测试：`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`（全量 **590 通过 / 0 失败 / 0 跳过**，
  debug 与 release 两个变体同数，86 suites）。**单测 JVM 时区已钉死为 UTC**（见 `app/build.gradle.kts` 的
  `testOptions`，审计「怀疑 #1」的落地），所以验收口径是"**UTC 下**全绿"，比东八区更严格。
- 验收还要跑 `.\gradlew.bat :app:assembleDebug`
- 测试结果 XML 在 `app/build/test-results/testDebugUnitTest/`（**多人并行时会被覆盖，要在 Gradle 锁内拷走再计数**；XML 带 BOM，用 `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 读）

## 总任务
按 95 条审查清单（review/00~04）逐条落地修复。
纪律：先 TDD 写纯判据 RED 测试 → 生产代码 → 编译 → 全量测试 → 提交。
**最核心的风险形态是「假安全感」**：写了/测了但生产零调用、以及永远不会变红的守卫。

## 最近几个回合的提交
### 2026-10-30 第十三回合（用户「都做」）：播放启动 4 项优化 + 防守卫
全量 debug+release 各 **634/0/0**（93 suites）+ `assembleDebug`/`assembleRelease` 成功。新增守卫 **34 条**（含队友 2 条）。已发版 **v1.2.21**（tag `v1.2.21` → `0224ff8`，APK `nba-live-1.2.21.apk` 2,750,309 B，sha256 `9b6196e7…`）。
| 提交 | 内容 |
|---|---|
| `0224ff8` | **A. 直连失败退回网页后不再永久卡住**：实测嗅探到的 m3u8 404 → 退回网页 → 同页禁止再升级 + 页面发过媒体请求（25s 看门狗不触发）⇒ 界面停在源站「网络错误请试试刷新」上**挂了 5 分钟没动静**。新增 `SniffRecoveryPolicy`（8 秒宽限期，取值为正常出流 2~3s 的 2~3 倍）＋ `MainViewModel.startSniffRecovery` / `advanceAfterNoSignal`（与"嗅探超时"共用换线判据）；`onStreamSniffed`（页面又发流）、`play`、`back`、`playMatch` 都会取消计时。**B. 缓存上限 30→60 分钟**（实测源站 `expire` 还差约 4 小时，覆盖"看一半退出、过半小时再回来看"）。**F. 加载提示浮层**：网页兜底加载期间（黑屏 0.1~27s）叠一句非阻塞「正在连接源站播放页…」，`PlayerLoadingHintPolicy`（20 秒上限、出流/报错/小窗都不显示）。**D. 跳过播放页壳直接加载玩家页**：壳页含 `playerConfig.playerUrlBase64`（base64 的 `https://demo.songzenet.com/paps.html?id=`）与 `encodedStr`，新增 `PlayerPageResolver`（手写 base64 解码，minSdk24 不用 `java.util.Base64`）算出 paps 地址；WebSurface 加载前用 OkHttp 拉壳页（2s 超时、失败/解析不出就静默用原页），直接加载 paps 后 6 秒内无媒体请求再退回原页。**实机证据（debug）**：WebView 直接加载 `https://demo.songzenet.com/paps.html?id=…`，随后仍嗅探到 m3u8 → `ExoPlayerImpl Init`（说明跳过壳页没有破坏嗅探）。**G（队友 guard-fixups）**：`WebSniffWatchdog` 25s→15s、45s→30s，回退实验恰好 2 条边界用例变红。**C（WebView 复用）本轮未做**：P2-4 的 Referer 归属校验实测是 fail-open（媒体请求常无 Referer），删掉"按 URL 重建 WebView"这道保护会让旧页在途流可能被当成新线路升级，需单独一轮带 instrumentation 验证。**回退实验**：注释 `startSniffRecovery(pageUrl)` → 1 红；`resolvePlayerUrl` 改成 `null` → 1 红；移除提示判据调用 → 1 红（三条同批执行，恰好 3 红）。 |

### 2026-10-30 第十二回合（现场反馈）：**解析播放太慢** —— 复用已嗅探成功的 m3u8
全量 debug+release 各 **600/0/0**（87 suites）+ `assembleDebug`/`assembleRelease` 成功。新增守卫 **10 条**。已发版 **v1.2.20**（tag `v1.2.20` → `4889a3a`，APK `nba-live-1.2.20.apk` 2,750,309 B，sha256 `210a4407…`）。
| 提交 | 内容 |
|---|---|
| `4889a3a` | **现场**：「现在解析播放时间太久了」。**定位（雷电 9 / Android 14 / 1280×720；该模拟器时钟比宿主快 20.5 秒，早期把 tap→日志的间隔算大了 20 秒，已校正）**：过滤掉主播线路后（v1.2.17），剩余 JRKAN 线路全部是 `/play/pao\|wen\|mgxl\|wlive/` ⇒ 只能走 WebView。实测 tap→ExoPlayer Init：`loadSignals` 冷 861ms / 缓存 27~46ms；WebView 页面加载 **0.1~27 秒**（页面用 `document.write` 注入跨站 `t.js`，是 parser-blocking，源站页面快慢波动极大）；嗅探到 m3u8 后再 Init。**关键事实**：嗅探到的 m3u8 带 `expire` 签名（实测 `expire=1793358219`，距当时约 4 小时），**同一场再点开时却把整条 WebView 链路重走一遍**。**修法**：新增 `SniffedPlaylistCache`（pageUrl → m3u8+referer；有效期 `min(expire, now+10min)`、上限 30min、解析不到 expire 用 10min；**墙钟**为基准，签名是绝对时间戳）；`play()` 命中缓存直接 ExoPlayer 起播（`sniffedStream=true`，失败仍能退回该播放页），不再进 WebView；`onPlaybackFailed` 里**立刻 invalidate**（否则下次拿 404 旧地址重试）。**回退实验**：`if (false && !chosen.isDirectHls)` 关闭缓存分支 → **1 红**；注释掉 invalidate → **1 红**。**诚实边界**：只优化"再次打开同一场"（返回列表再进 / 画中画回 / 切回刚看过的比赛）；**首次打开仍要加载 WebView 页面**，那 0.1~27s 的波动没消除。真机侧：release 1.2.20 装机列表正常、无 `FATAL EXCEPTION`；**"缓存命中"这一步的真机脚本化验证不稳定（当前多条线路 404 + 模拟器返回键/导航脚本不可靠），未声称真机实测命中** —— 命中语义由 10 条单测钉住（`SniffedPlaylistCacheTest` 8 + `MainViewModelTest` 2）。 |

### 2026-10-30 第十一回合（现场 bug）：小窗里点「全屏」回来是竖屏
全量 debug+release 各 **590/0/0**（86 suites）+ `assembleDebug`/`assembleRelease` 成功。新增守卫 **2 类 9 条**。已发版 **v1.2.19**（tag `v1.2.19` → `6e26e0b`，APK `nba-live-1.2.19.apk` 2,733,929 B，sha256 `488aee92…`）。
| 提交 | 内容 |
|---|---|
| `6e26e0b` | **现场报告**：竖屏播放页 → 点「画中画」→ 小窗里点系统的「全屏」→ **回到 720×1280 竖屏播放页**（信号列表还在），不是全屏。**复现**（雷电 9 / Android 14 / 1280×720，debug 包）：`dumpsys window displays` 退出小窗前 `cur=720x1280`，点系统「全屏」后仍是 `720x1280`，`uiautomator` 里「信号切换」列表仍在。**根因**：`fullscreen` 是 `rememberSaveable`，记录的是**进小窗前那一刻**的状态 —— 从竖屏页进的小窗它是 false，退出小窗时**没有任何一步**把它改回 true（从全屏进的小窗碰巧是 true 所以曾"看起来正常"）。**修法**：判据抽成 `PipExitPolicy.shouldExpandToFullscreen(onPlayer, finishing)`（有单测）；`MainActivity.onPictureInPictureModeChanged` 的**退出分支**（`if (isInPictureInPictureMode) return` 之后）调判据并 `PipState.requestExpand()`；`PipState.expandRequests` 用**计数**而不是 Boolean（小窗期间 Activity 是 PAUSED，`collectAsStateWithLifecycle` 停止收集，Boolean 的"写 true→消费→复位 false"可能全发生在不可见时段、事件被吞）；`AppEffects.PipExitFullscreenEffect` 用 lifecycle-aware 收集并在新请求时 `fullscreen = true`；`NbaLiveApp` 挂载该 effect。**回退实验**：判据改成恒 `false` → 1 红；注释掉 `requestExpand()` 与 `NbaLiveApp` 的 effect 调用 → **2 红**（各一条）；把 `currentOnExpand()` 改成 `Unit` → 1 红。**实机验证**（debug 包）：同样的"竖屏页→画中画→系统全屏"步骤，退出后按 250ms 采样 `720x1280 → 1280x720`（第一帧仍为旧尺寸）后**稳定 1280×720**，界面只剩视频 + 「退出全屏/画中画」按钮，无 `FATAL EXCEPTION`。 |

### 2026-10-30 第十回合（goal round 9）：**95 条清单全部收口** —— 04-P2-7 落地 + 6 条缺护栏补齐
1 个提交（`7648f66`）+ 发版 **v1.2.18**。全量 debug+release 各 **581/0/0**（84 suites）+ `assembleDebug`/`assembleRelease` 成功。
| 提交 | 内容 |
|---|---|
| `7648f66` | **04-P2-7（唯一的未落地）→ 落地**：`javap` 实测 AGP 8.7.3 公开 `VariantOutput` 只有 versionCode/versionName/enabled/enable —— **没有 outputFileName**，所以删掉对 `com.android.build.api.variant.impl.VariantOutputImpl` 的依赖，改为 Gradle 文件 API 在打包任务 `doLast` 改名（未签名 APK 实测默认名 `app-release-unsigned.apk` → `app-UNSIGNED-release.apk` 2,725,737 B；签名包 `app-release.apk` 2,733,929 B 不受影响）。**04-P2-8 三场景实验**：签名 bundleRelease exit=0 产正式名 AAB；无凭据 exit=1 且 `.aab` count=0（闸挂在产出任务 `*ReleaseBundle.doFirst`，挂生命后期太晚）；`-PskipSigning` exit=0 只留 `app-UNSIGNED-release.aab`（改名若放产出 doLast 会被 AGP `produceReleaseBundleIdeListingFile` 打红，实测）。**03-P2-25** 生产去掉内层重复 `.clickable` + 新守卫；**03-P1-3（P1）** 新增 `SystemBarsAppearanceTest`(2) 函数体级守卫；`SourceZoneDisciplineTest` 白名单洞收口。队友：`guard-fixups` 补 02-P2-11 闰日 2 条（20/0/0/0）、`pip-health` 补 02-P2-5 行为级 1 条（13/0/0/0）；新增 04-P2-7/8 守卫 `BuildScriptHygieneTest`(3)。**95 条结果：87 已落地 / 8 有据取舍 / 0 未落地 / 0 无法判定** |

### 2026-10-29 第九回合（用户需求变更）：**过滤掉涉及主播的线路** + 删掉 007ad88 的误诊补偿
1 个提交 + 发版 **v1.2.17**。全量 debug+release 各 **570/0/0**（80 suites）+ `assembleDebug`/`assembleRelease` 成功。
| 提交 | 内容 |
|---|---|
| `dfd13ac` | **产品规则改向**：`ChannelAdmission.admit` 只剩一条规则 —— 名字命中 `主播/解说/美女/聊天` 的线路**整条过滤**；删除 `007ad88` 加的"可直连线路被清零就以中性名补回"补偿分支。**为什么原来那条是错的**：它的依据是"网页线路一条媒体请求都不发、播放器没跑起来"，而真因是 v1.2.13 明文误禁 `pjs.js`（v1.2.16 / `45fdb89` 修掉）；补偿生效后 `MainViewModel.kt:707` 的"默认线路优先选可直连"恰好选中被补回的主播线路 ⇒ 用户看到"只有主播线路能看"。**21:00 实测 23 场次 95 条线路：48 条敏感线路全部是 `/play/sm.html`（可直连），47 条非敏感线路全部是 `/play/pao\|wen\|mgxl\|wlive/`（只能走 WebView）** ⇒ 过滤后 0 条直连是正常结果。配套改 `MatchRepository`：`expandJrkanPlayPage` 返回 `Expansion(channels, pageFetched)`，**只要有一个播放页真取到正文就不再退回入口页**（入口页会自动选中它自己的第一条 = 主播线路，等于绕过过滤）；一次都没取到正文时仍退回入口页兜底。守卫：`JrkanChannelAdmissionTest`(13，含函数体扫描"`admit` 内不许出现 `isDirectPath`")、`JrkanParserTest`(3 条改写)、`SourceEndpointsWiringTest` 新增 2 条。**回退实验：把补偿分支与无条件退回都加回去 → 恰好 7 条红**（逐条：只剩主播线路返回空/唯一的可直连线/整页全主播/真实 sub_playlist/函数体扫描/主播线路整条过滤/全过滤不退回入口页）。**实机（雷电 9 / 1280x720 / debug 构建）**：点 CBA 场次后 `线路 1/4`，chromium 加载 `play.sportsteam368.com/play/mgxl/?id=00594084`（非敏感线路）+ `pjs.js` → `ExoPlayerImpl Init` + `OMX.qcom.video.decoder.avc`；视频带 **96.4% / 94.8% 非黑**、中心 98.9%/98.1%、帧间差 **214**（在动）。release 包（versionCode 21）：WebView→嗅探→ExoPlayer 同样初始化成功，此次点到的是一场**已结束/无源**的比赛，清单 404（`Caused by: x1.t: Response code: 404`），属源站事实不属 App 回归 |

**已知代价（用户 2026-10-30 07:39 已拍板：保持现状）**：同一批 23 个场次里有 **5 个场次只剩主播线路**
（825263/825262/825266/825273/825227），过滤后变为 0 条线路，界面走 `MatchRepository.loadSignals`
→ `"该赛事暂无可用线路"`。**决定：不隐藏场次、不加第二套占位文案，就用现有空态文案表达** ——
"本场确实只有主播线路"是源站事实，隐藏会让用户误以为源站没有这场比赛。**后续不要再当未完成项反复提。**

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

### D. 已明确不做的需求（用户拍板，不要再提案）
- **2026-10-30 11:54 用户决定：画质增强不做。** 起因是"线路都是 720p，能不能增强画质"。
  评估结论（已实测，不是猜测）：ExoPlayer 1.9.2 确有 `setVideoEffects`，`media3-effect:1.9.2`
  也可用（AAR 322 KB，含 `Contrast`/`LanczosResample`/`SeparableConvolution`），**但没有现成
  锐化/超分效果**；就算做，也只能提升观感锐度，不能恢复 720p 没有的细节，且只对直连 HLS 生效
  （网页线路完全无效）、有 GPU 掉帧/发热与 HLS 切档黑帧风险、APK +0.2~0.3 MB。
  **不要再提"AI 超分/1080p 上采样"方案。**

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