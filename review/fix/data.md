# 数据层修复报告（fix-data / task-6）

- **范围**：`app/src/main/java/com/nbalive/app/data/**` 全部 13 文件 + 新增 `MatchMerger.kt` / `data/model/TeamNameBridge.kt` + `ui/TeamNameBridge.kt`（迁包）+ `ui/UpdateCheckPolicy.kt`
- **任务**：`review/optimization-review.md` §3/§4.1/§5.1/§6 → `review/opt/data.md` D1~D18（+ H3/H4 别名、U14 追加条）
- **未做**：未运行 Gradle / adb / 构建 / 安装（铁律 3）。所有判据都是 JVM 纯函数单测或行为单测；无法单测的写了"回退即红"实验步骤
- **新增测试文件**（只新建，未改任何既有测试文件；例外见下）：
  - `app/src/test/java/com/nbalive/app/data/repository/ListLoadFixesTest.kt`（H3/H4/D3/D5/D9/D12）
  - `.../data/repository/ScoreboardFixesTest.kt`（D7/D8/D6-②/D15）
  - `.../data/repository/MatchMergerTest.kt`（D10）
  - `.../data/repository/DetailCacheLogFixesTest.kt`（D18）
  - `.../data/remote/ChannelParseFixesTest.kt`（D4/D13）
  - `.../data/model/TeamAliasAndVersionFixesTest.kt`（D6-①/D17/D11 别名）
- **经 Lead 明确批准后改动的既有测试**（只改这两条相关的部分）：
  - `app/src/test/java/com/nbalive/app/data/remote/HttpClientFetchTest.kt`：404 由 `Unreachable` 改断言 `FetchResult.NotFound`，并补 410
  - `app/src/test/java/com/nbalive/app/ui/NewFeaturesTest.kt`：删 `EspnScoreboardTest.ISO 时间解析`（D14）、8 处 `GameScore(...)` 去掉末尾 `, 0L`（D14）、4 处 `decide(...)` 去掉 `"notes"` 实参（U14）

## 逐条结果

| 编号 | 状态 | 改动文件:行 | 判据测试名 | 回退即红实验 |
|---|---|---|---|---|
| **H3 / D1** 超时丢数据 | 已修复 | `MatchRepository.kt:766`（`loadMatchesWithOutcome` 加缓存层）、`:840-859`（`loadMatchesUncached`：两源各自 `async`）、`:908-911`（`sourceWithDeadline`）、`:662`（`SourceOutcome.TimedOut`）、`:863-881`（按源组装 `failedProviders/reasons`，只把超时的那一个源报成超时） | `ListLoadFixesTest.一个源超时不许丢掉另一个源已经返回的场次`、`两个源都超时时仍然在 deadline 内返回并都记为失败` | 把两处 `sourceWithDeadline { … }` 换回 `withTimeoutOrNull(loadDeadlineMs) { jrsDeferred.await() to jrkanDeferred.await() }` + `matches = emptyList()`：第一条断言立刻从 3 变 0 |
| **H4 / D2** league 污染 | 已修复 | `MatchMerger.kt:63`（`val league = betterLeague(...)`）、`:73-74`（`league` 与 `leagueRawName` 成对取用）、`:107-114`（`betterLeague`：supported > 非 OTHER > 平手保持先合并） | `ListLoadFixesTest.JRKAN 判 OTHER 时同场 JRS 的 NBA 必须留下`、`MatchMergerTest.betterLeague 优先级 supported 高于非 OTHER 高于其它` | 删掉 `copy(...)` 里的 `league = league`（恢复旧实现）：第一条断言从 1 场变 0 场（整场被 supported 过滤） |
| **D3** 404/410 被归 Unreachable | 已修复 | `FetchResult.kt:41`（新增 `NotFound`）、`HttpClient.kt:112`（404/410 → NotFound）、`MatchRepository.kt:1059`（`Fetched.NotFound`）、`:1076-1081`（列表：不重试）、`:988-994`（列表：不登记 host 失败）、`:458-463`（详情页）、`:1276-1282`（播放页） | `ListLoadFixesTest.详情页 404 不登记 host 失败 主域名仍排候选首位`、`列表页 404 报成页面不存在 而不是网络连接失败`、`播放页 404 不登记 host 失败`；HttpClient 层：`HttpClientFetchTest.404 判为 NotFound 既不重试也不登记 host 失败`、`410 同样判为 NotFound` | 把三处 `is FetchResult.NotFound ->` 分支并回 `Unreachable`：`failedHostsOf(repo)` 立刻非空 → 三条断言同时红（候选首位变成镜像域名） |
| **D4** 播放页解析加固 | 已修复 | `JrkanParser.kt:75-78`（`DATA_PLAY` 引号放宽单/双）、`:85-89`（`CHANNEL_LABEL` 允许内嵌标签、`TAGS`、`WHITESPACE_COLLAPSE`）、`:345-355`（改用常量 + `extractLabel`）、`:385-397`（剥标签取名）、`ChannelAdmission.kt:94-96`（**名字取不到 → 整条剔除**） | `ChannelParseFixesTest.单引号 data-play 也能解析出线路`、`线路名内嵌标签时剥标签后再判敏感`、`名字完全取不到的线路必须整条剔除`、`名字正常且可直连的线路仍然保留`、`准入判据对空名字也整条剔除 但 isSensitive 语义不变` | 把 `DATA_PLAY` 换回 `<a[^>]*data-play="([^"]+)"`／把 `admit` 换回 `filterNot { isSensitive(...) }`／把 `CHANNEL_LABEL` 换回 `<strong>([^<]+)</strong>`：对应三条断言分别红（0→1 或 1→0） |
| **D5** 列表零缓存 | 已修复（**VM 接线待 fix-ui-state**） | `MatchRepository.kt:77`/`:489`（TTL 30s 常量）、`:151-166`（`listCache`）、`:168`（`inFlightLists`）、`:780-787`（TTL 命中）、`:789-805`（single-flight）、`:815-826`（写缓存 + 完成等待者）、`:739-745`（生产入口暴露 `forceRefresh` 形参） | `ListLoadFixesTest.TTL 内第二次调用不发请求 TTL 过后重发 forceRefresh 必发`、`同一源组合的并发调用只打一次源站` | 把 `if (hit != null && clock() - hit.first < listCacheTtlMs)` 恒假（或删掉缓存层）：第一条的"第二次不发请求"立刻红（请求数翻倍） |
| **D6** 队名表合并 | 已修复 | `Match.kt:117`（NBA 关键字补 雷霆/猛龙/奇才）、`data/model/TeamNameBridge.kt:36-91`（昵称 → **中文别名集合**，mavericks→{独行侠,小牛}）、`:95-116`（`toChineseAliases`）、`:118-133`（`toChinese` 返回规范名，行为不变）、`:143-160`（`findGame` 用别名集合，**主客方向校验保留**） | `TeamKeywordFixesTest.雷霆 猛龙 奇才 的对阵也要能判出 NBA`、`NBA 全 30 队都能被兜底表认出`、`ESPN 达拉斯与源站小牛能配上 且主客方向仍校验`、`ScoreboardFixesTest.源站写小牛也能配上 ESPN 的 Mavericks` | 删掉 `"雷霆","猛龙","奇才"`：第一条红；把 `findGame` 改回 `toChinese(...) == h`：`小牛` 断言红 |
| **D7** 陈旧比分 | 已修复（VM 侧剔除由 fix-ui-state 消费） | `ScoreboardRepository.kt:63`（`Snapshot.fetchedAtMs`）、`:109`（成功时记墙钟）、`:111-123`（**失败不覆盖 last-good**，连 `fetchedAtMs` 都不动） | `ScoreboardFixesTest.连续失败时保留上一次成功的比分 且 fetchedAtMs 不变`、`首次就失败时返回空快照 而不是抛异常` | 删掉 `if (snap.isEmpty() && last != null) return last`：第一条从"两次失败后仍有分数"变空表 + `fetchedAtMs` 被覆盖 |
| **D8** `enabled` 从未被消费 | 已修复 | `ScoreboardRepository.kt:86-89`（空集直接 `Snapshot.EMPTY`，不发请求、不写缓存） | `ScoreboardFixesTest.enabled 为空集时一个请求都不发 也不写缓存` | 删掉 `if (enabled.isEmpty())` 分支：`calls` 从 0 变 1，且缓存被污染（第二条"全开时必须真发请求"也跟着红） |
| **D9** 403/429 无冷却 | 已修复 | `MatchRepository.kt:85`/`:499`（冷却 60s）、`:96`（可注入时钟）、`:139`（`sourceCooldowns`）、`:1019-1037`（`coolingCodeFor`：任一候选冷却即整源冷却，过期条目自清）、`:1046-1051`（`startCooldown`）、`:950-954`（列表）、`:437-440`（详情）、`:1253-1257`（播放页）、`:985`/`:454`/`:1273`（三处 Rejected 登记） | `ListLoadFixesTest.403 冷却窗口内不再发请求 窗口过后恢复`、`详情页 403 之后冷却窗口内也不发请求` | 删掉 `coolingCodeFor(candidates)?.let { … }` 三处：两条断言立刻红（窗口内第二次调用会再撞一次 403） |
| **D10** 拆 `MatchRepository` | **部分**（按最低风险方案只抽 `MatchMerger`） | 新增 `MatchMerger.kt`（`merge`/`betterLeague`/`betterStatus`/`statusRank`/`normalizeKey`/`fallbackDayKey` + 两个正则常量）；`MatchRepository.kt:1111-1112`（`merge` 委托）、`:1104-1108`（`mergeForTest` 签名不变）、`:555-558`（正则常量迁走） | `MatchMergerTest.` 全部 6 条（含 `仓库的测试入口与合并器同源 不是又抄了一份`） | 把 `MatchMerger.merge` 的规则改回"后合并的赢"/去掉 `betterLeague`：对应断言红；`mergeForTest` 改成自己重写一份合并逻辑则"同源"那条红 |
| **D11** 数据层反向 import UI | 已修复（保留 ui 转发件） | 新增 `data/model/TeamNameBridge.kt`（实现体迁入，无 Compose 依赖）；`ui/TeamNameBridge.kt:26-56` 变为**纯转发 object**；`ScoreboardRepository.kt:9` 改为 `import com.nbalive.app.data.model.TeamNameBridge` | `TeamKeywordFixesTest` 全部（数据层新位置）；转发件由既有 `NewFeaturesTest.TeamNameBridgeTest` 间接钉住 | `git grep "import com.nbalive.app.ui.TeamNameBridge" app/src/main` 应为空；把 `ScoreboardRepository` 的 import 改回 `ui.` 即回退 |
| **D12** force 删别人的 in-flight | 已修复 | `MatchRepository.kt:382`/`:409-412`（详情页：`if (registered)` 才 remove）、`:793-826`（列表层同款 `registered` 纪律）；顺带修掉等待者永远拿到 `null` 的隐患（`:401` 的 `.also { fetched = it }`） | `ListLoadFixesTest.force 刷新不许删掉别人在飞的 single-flight 条目` | 把列表层的 `if (registered) { … remove … }` 改成无条件 `remove`：该测试立刻红（C 自己发 2 次请求）。详情页路径的**回退即红实验**（无生产入口触达 `forceRefresh=true`）：临时把 `loadSignals` 里 `fetchJrsDetail(id, fetch)` 改成 `fetchJrsDetail(id, fetch, forceRefresh = true)`，三协程并发（A 阻塞在 fetch → 一路 force 先完成 → C 进场）应观察到 fetch 由 2 次变 3 次 |
| **D13** 热路径 Regex / `line\d` | 已修复 | `JrkanParser.kt:60`（`PLAY_ID` + `\d+`）、`:63`（`DATE_TIME`）、`:75-92`（`DATA_PLAY`/`CHANNEL_LABEL`/`TAGS`/`EMPTY_ID`）、`:365`（改用 `PLAY_ID`）；`JrsParser.kt:46-55`（`LIVE_ID`/`HHMM`/`HHMM_LOOSE`/`DATE`） | `ChannelParseFixesTest.line10 也能解析出 playId`、`line1 的老形态不受影响` | 把 `PLAY_ID` 的 `\d+` 改回 `\d`：第一条红（sources 由 1 变 0） |
| **D14** 死字段 | 已修复 | `EspnScoreboard.kt:36-48`（删 `GameScore.startAtMillis`）、`:72-82`（删 `event.string("date")` 计算）、删除 `parseIso8601`（唯一消费者就是该字段） | `NewFeaturesTest.EspnScoreboardTest`（解析行为不变）+ `ScoreboardFixesTest`（新夹具已按 7 字段构造） | 源码里 `grep -c startAtMillis app/src/main/java/.../EspnScoreboard.kt` = 0；把字段加回来编译即仍可通过，故判据是"该文件不再出现 + 每场少一次 `Calendar`/`TimeZone` 分配"（实测 grep 为 0） |
| **D15** JsonCursor | **半条已满足**（原本就是 `internal`；本次补 KDoc + 判据；VM 侧迁移由 fix-ui-state 的 `ReleaseRepository` 落地，非本任务） | `EspnScoreboard.kt:93-100`（KDoc 写明"唯一取值器 + 谁在消费"） | `EspnJsonCursorTest.后缀键不可能被误命中`、`更长的 key 不会被短的 key 命中`、`更早出现的同名嵌套 key 会被先取到 已知残余风险`、`数字字段与字符串字段各走各的路` | 把 `keyIndex` 的前边界校验（`src[p] == '{' \|\| src[p] == ','`）删掉：`后缀键不可能被误命中` 那条在 `{"note":{"body":"x"},"body":"y"}` 类样本上立刻红 |
| **D16** 零消费属性 | 已修复 | `MatchRepository.kt:683-684`（删 `SourceOutcome.enabled`） | 全仓 `grep "\.enabled"` 在 `data/` 下仅剩 `PrefsStore.enabled`（另一回事）；`ListLoadFixesTest` 对 `failedProviders` 的断言间接钉住 `failed` 语义 | 把 `val enabled` 加回来即回退（无行为输出来源，故判据是"声明不存在" + 既有 `failed` 语义测试） |
| **D17** 版本比较语义 | 已修复 | `UpdateCheckPolicy.kt:65-94`（两段式比较：数字核心 + 后缀降级）、`:159-197`（`Parsed`/`split`/`parseInts`/`suffixDigits`/`compareSegments`）；`parse` 保留为 `normalize` 专用（`:148-157`） | `VersionCompareFixesTest.后缀版本低于同数字的正式版`、`两边都带后缀时后缀里的数字仍可比较`、`数字段优先的既有语义完全不变`、`解析失败仍然静默不提示` | 把 `isRemoteNewer` 换回"只比数字段"：`1.2.24-hotfix` vs `1.2.24` 由 false 变 false（相等）→ 第二条断言红；`1.2.24` vs `1.2.24-hotfix` 由 true 变 false → 红 |
| **D18** 详情页缓存零日志 | 已修复 | `MatchRepository.kt:359`（命中）、`:363`（TTL 过期）、`:1157`（显式失效）、`:1140`（整体清空）——四处都只打 `detailId` | `DetailCacheLogFixesTest.详情页缓存三路都有 debugLog`、`缓存日志只打 detailId 不打 URL`、`扫描锚点必须真的扫到文件` | 删掉任一路 `debugLog`：对应断言红；把 `$detailId` 换成 `$url`/`redactUrl(url)`：第二条红 |
| **U14**（Lead 追加）`Available.notes` 零消费 | 已修复 | `UpdateCheckPolicy.kt:32-38`（删字段）、`:99-116`（删 `decide` 的 `notes` 形参 + KDoc 说明） | 既有 `NewFeaturesTest.UpdateCheckPolicyTest`（4 处调用点已同步去掉实参） | 全仓 `grep notes app/src` 只剩解释"为什么删"的 KDoc 一行；VM 侧已由 fix-ui-state 同步删除（`MainViewModel.kt` 现无 `notes`） |
| **H3/H4 之外：D3 的 `HttpClient` 映射** | 已修复 | `HttpClient.kt:112` | `HttpClientFetchTest.404 判为 NotFound…` / `410 同样判为 NotFound` | 删掉 `status == 404 \|\| status == 410` 分支：两条断言红（回落到 `Unreachable`） |

## 需要 Lead 转交 / 收尾的事项

1. **~~【必须接线】D5 的 `forceRefresh`~~ ✅ 已由 fix-ui-state 落地（2026-11-01 Lead 确认：冷启动 `false`、用户刷新 `true`）**：
   - 原缺口：`MainViewModel.kt` 的 `loadOutcome` lambda 只收 `enabled`，`refresh()` 无法区分"冷启动"与"用户下拉刷新"。
   - 现状：lambda 已扩成 `(Set<Provider>, Boolean)`，D5 语义完整（TTL 命中零请求 + 用户刷新绕过）。
2. **D11 的 ui 转发件**（收口进度见 [`test-reconcile.md`](test-reconcile.md)）：`ui/TeamNameBridge.kt` 是纯转发 object，只为 `ui/screens/MatchListScreen.kt:66` 的 import 而留；`NewFeaturesTest` 已改为显式引用 `data.model.TeamNameBridge`（别名 `DataTeamNameBridge`），所以**只剩生产侧一处 import** —— 它改完后该文件即可整体删除。**不要在我这轮里删**（会打断生产编译）。
3. **既有测试同步**：本轮我只改了 Lead 批准的两处（`HttpClientFetchTest` 的 404/410、`NewFeaturesTest` 的 D14+U14 三个点）。**其余既有测试我判断不需要改**（逐条核对过 `MergeStatusTest`/`MatchRepositoryTest`/`LoadOutcomeTest`/`LoadDeadlineTest`/`SourceToggleWiringTest`/`SourceEndpointsWiringTest`/`JrkanChannelAdmissionTest`/`JrkanParserTest`/`DetailSingleFlightTest`/`DetailCacheTtlTest`/`MatchRepositoryRetryTest`/`NewFeaturesWiringTest` 的断言与夹具）。若 Lead 的构建里仍有红，请把错误贴给我。

## 未完成与原因

1. **D10 未抽 `JrsDetailCache` / `LoadOrchestrator`（只落地 `MatchMerger`）**：
   - 既有测试用**反射**钉住了 `MatchRepository` 的私有成员 —— `detailCache` 字段（`DetailCacheTtlTest.kt:62`、`MatchRepositoryRetryTest.kt:276`）与 `sort` 方法（`MatchRepositoryTest.kt:98`）。抽走它们会直接让这些测试红（且我没有既有测试文件的修改权）。
   - 此外 `review/optimization-review.md` §7 明确把 D10 的完整拆分列为"L 成本、建议独立一轮、先有 CI 兜底再动"。本轮按清单里的降级方案只抽纯函数合并器，风险与收益比最合理。
2. **D12 的详情页路径没有直接判据**：`fetchJrsDetail(forceRefresh = true)` 这条形参目前**在生产里没有调用者**（`loadSignals` 与 `correctListStatuses` 都走 `forceRefresh=false`；用户"刷新线路"走 `invalidateDetailCache` + 普通调用），所以无法从任何生产入口构造出"三协程 + 一路 force"。判据钉在同构的列表层实现上（有真实红绿），详情页路径给出了"临时把 `loadSignals` 改成 `forceRefresh=true`"的回退即红实验步骤（见上表 D12 行）。
3. **D15 只剩"提为 internal"这一半且原实现已满足**：`JsonCursor` 在本轮之前就是 `internal class`（`read` 原文为证），本次只是把"为什么必须 internal、谁在消费"写进 KDoc 并补边界判据；"让 VM 复用"这半条由 fix-ui-state 的 `ReleaseRepository` 落地，不由本任务完成。
4. **D14 的"没有生产消费者"只能靠源码判据**：字段与函数都已删除，`grep startAtMillis`/`grep parseIso8601` 在 `EspnScoreboard.kt` 为 0；"少一次 `Calendar`/`TimeZone` 分配"属分配量收益，无 JVM 侧可断言的出口（本仓库对"无测量支撑的微优化"一律封顶"低"，故不做性能断言）。
5. **未做真机/抓包验证**（按铁律 3）：D4 的真实 `data-play` 引号形态、D9 的真实 403/429 频率与窗口长度、D5 的 282 KB 量级、D6-② 的源站 NBA 队名写法、D7 的 ESPN 连续失败表现，均仍需 `.forensics/` 或真机复核（`review/opt/data.md` 的"需要运行/真机才能定的"1~7 条）。

## 测试调试（2026-11-01，Lead 首轮 `:app:testDebugUnitTest`：833 用例 / 5 失败，其中 4 条是我新建的）

| 失败用例 | 判定 | 根因（已核实） | 改了什么 |
|---|---|---|---|
| `ListLoadFixesTest > force 刷新不许删掉别人在飞的 single-flight 条目`<br>`expected:<3> but was:<4>` | **测试错**（期望值算错） | 我把期望值当成"JRKAN 那 3 场"了；夹具里 **B 的两个源都成功**：JRS 列表给 1 场（湖人/凯尔特人）+ JRKAN 给 3 场 = **4** 场。生产行为正确（`rc` 与 `rb` 拿到同一批 4 场、`cListCalls == 0` 说明 single-flight 复用成功）。 | 断言改成 4，并写明算式与"1 场 JRS + 3 场 JRKAN"；**另加非空性锚点**断言 `rb` 同时含 `jrs-` 与 `jrkan-` 场次（否则这条判据可能在只剩一个源的夹具上假绿）。 |
| `ScoreboardFixesTest > 连续失败时保留上一次成功的比分 且 fetchedAtMs 不变`<br>`第一次必须成功` | **测试错**（夹具非法 JSON） | 见下一行的共同根因。生产侧无问题：`load()` 之所以返回空快照，是因为 `EspnScoreboard.parse(sample)` 对**畸形 JSON** 返回 null。 | 见下。 |
| `ScoreboardFixesTest > enabled 为空集时一个请求都不发 也不写缓存`<br>`全开时必须拿到真实比分` | **测试错**（夹具非法 JSON） | 同上。 | 见下。 |
| `ScoreboardFixesTest > 源站写小牛也能配上 ESPN 的 Mavericks` | **测试错**（夹具非法 JSON） | 同一条根因：快照为空 → `snapshot.leagues` 为空 → `scoreFor` 直接返回 null。**不是** `findGame` 只比规范名 —— `data.model.TeamNameBridge.findGame` 确实按**别名集合**比较（`toChineseAliases` 返回 `{独行侠, 小牛}`）。 | 见下。 |

**共同根因（三条 Scoreboard 用例）**：我在 task-6 首轮把两个**本来合法**的 JSON 夹具"清理"坏了 —— 把结尾从 `}}}]}]}` 删成了 `}}}]}`，少了"事件对象 + events 数组"两个收尾（正确的收尾序列是：`}` type → `}` status → `}` competition 对象 → `]` competitions 数组 → `}` event 对象 → `]` events 数组 → `}` 根对象）。
当时的判断依据是**裸字符计数**（`{` 41 / `}` 40），但那个计数把字符串里的花括号也算进去了，真正的失衡来自本文件其它字符串 —— 我从一个错误的度量推出了一个错误的结论。

**硬证据（不跑 Gradle 也能独立复现）**：用 .NET 的 JSON 解析器验证过：
- 旧文本 → `FAIL 传入的数组无效，应为","`（events 数组被提前闭合）；
- 修好后 → `OK events=1 competitions=1 competitors=1 homeScore=112 homeName=Golden State Warriors`；`mavericksSample` → `OK events=1 ... homeName=Dallas Mavericks`。
（两段文本是直接从 `ScoreboardFixesTest.kt` 里抽出来喂给解析器的，不是手抄。）

**修法**：把两个样本的收尾恢复成 `}}}]}]}`；并新增用例
`ScoreboardFixesTest.夹具自检 样本必须是可解析的合法 JSON`（1 场 + `homeScore`/`homeName` 逐字段核对）——
让**夹具自己**先以明确文案失败，而不是让三条业务用例分别报出"第一次必须成功"这种看不出根因的信息。

**教训（写进报告的用意）**：本仓库反复强调"非空性锚点"，我这次漏在了**夹具**上；另外"用裸文本计数推断结构平衡"正是 review §10 点名的行号/计数陷阱的同类错误，下次这类改动要么用真实解析器验证，要么不动。