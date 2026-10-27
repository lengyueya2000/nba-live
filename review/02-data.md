# 数据层代码审查（仓库 / 解析器 / 网络 / ViewModel）

审查范围：`app/src/main/java/com/nbalive/app/` 下的 `data/repository/MatchRepository.kt`、`data/remote/{JrsParser,JrkanParser,StreamUrlResolver,HttpClient,Logs,FetchResult}.kt`、`data/model/Match.kt`、`ui/MainViewModel.kt`。

审查方式：纯只读，未改动任何源码。除源码外，还用仓库内 `.forensics/` 的真实抓包做了复算与验证：

- `.forensics/jrs_list.html`（11KB，14 场，3 个日期分组）
- `.forensics/detail_364127.html`（JRS 详情页，含 `match-info-time` 与 `a.toLive`）
- `.forensics/jrkan_index.js`（263KB，119 个 `<ul class="item play">` 场次块）

并把解析器的正则**逐条抽出来在真实数据 / 构造变体上实跑**，下文凡标注"实测"的结论都来自这种复算，不是推测。

**结论先行**：数据层整体工程素养明显高于一般水平——有协程取消的正确处理、重试与限流的区分、URL 脱敏、并发与总量双重预算，注释里还记录了多次真实回归的来龙去脉。但仍有 **1 个 P0、9 个 P1**，其中多数集中在"解析器正则容错方向选错"和"状态永不老化"这两类结构性问题上，且与用户反馈的两个痛点（场次不全、比赛结束了还显示直播中）直接相关。

严重度分布：**P0 = 1，P1 = 9，P2 = 13**，另有 4 条待确认。

---

## P0（阻塞）

### P0-1 m3u8 正则贪婪匹配吃掉 `&id2=`，直连 HLS 候选地址被污染，整条 `/play/sm.html` 链路失效

- **文件**：`app/src/main/java/com/nbalive/app/data/remote/StreamUrlResolver.kt:105`
- **问题**：正则
  ```kotlin
  Regex("""msss\.html\?id=([^"'\s<]+)(?=&id2=|["'\s<]|$)""")
  ```
  捕获组 `[^"'\s<]+` **没有排除 `&`**，且用的是贪婪量词。`&` 不在排除集里，所以 `&id2=` 会被整体吞进捕获组；随后那个 `(?=&id2=|...)` 前瞻**永远不可能命中**（`&` 已被消费），是一个死分支。
  这与作者自己在 `:98-104` 写下的意图正好相反——注释明确说"不能按 `&` 截断；只在遇到 `&id2=`、引号、空白或标签边界时停"。
- **实测证据**（把该正则与 `restoreObfuscatedHost` 的算法逐行复刻后实跑）：
  - 输入 `<iframe src="//demo.songzenet.com/msss.html?id=<encoded>%2F%2Fhdl2.gnimixuiq.com%2Flive%2Fa.m3u8&id2=">`
  - 捕获组得到 `%2F%2Fhdl2.gnimixuiq.com%2Flive%2Fa.m3u8&id2=`（不是干净 payload）
  - URL 解码后 `//hdl2.gnimixuiq.com/live/a.m3u8&id2=`
  - 反混淆后最终候选 = **`http://hdl2.qiuximing.com/live/a.m3u8&id2=`**
  - 对照：同一 payload 不带 `&id2=` 时得到正确的 `http://hdl2.qiuximing.com/live/a.m3u8`
  - 带 query 时：`http://hdl2.qiuximing.com/live/a.m3u8?auth_key=abc&t=123&id2=`
- **触发条件**：源站播放页的 msss iframe 带 `&id2=` 尾巴。这个条件正是该正则**专门为之设计**的场景（前瞻里写了 `&id2=`），且 `JrkanParser.kt:30`、`StreamUrlResolver.kt:13-14` 的注释都记录了源站 `sm.html?id={innerId}&id2=` 这种带 `&id2=` 的 URL 形态是常态。
- **影响面**：`msss.html?id=` 是**唯一**能静态还原成 m3u8 的主链路（`Match.kt:118` 的 `isDirectHls` 只认 `/play/sm.html`）。三个候选（反混淆版、原文版、原文 raw 版）**全部**被同一个捕获结果污染：反混淆版和原文版都带 `&id2=`（坏地址），第三个 `normalizeScheme(raw)` 因为 raw 是 URL-encoded 而不以 `http` 开头、返回 null。于是 `MatchRepository.resolveStream` 逐个探测全部拿不到 `#EXTM3U` → 判死 → 所有 JRKAN 线路降级为 WebView 承载。这正是 README「已知取舍」里最想避免的退化。
- **为什么 9 条 `StreamUrlResolverTest` 全绿却没发现**：测试用例构造的 html **没有一条带 `&id2=` 的 msss 链接**（第 41、60、75、88、101、109 行的样本），所以正则的贪婪从没被触发。更关键的是 `:72-83` 那条名为「m3u8 payload is not truncated at a bare ampersand」的用例**本意就是测这个**，它构造了带 `&id2=` 的 html，但断言用的是
  ```kotlin
  assertTrue(resolved.m3u8Candidates.any { it.contains("auth_key=abc&t=123") })
  ```
  ——`contains` 只要求前缀存在，而带 bug 的捕获结果 `...auth_key=abc&t=123&id2=` **恰好满足**。一条本该拦住这个 bug 的测试，因为断言太松而放行了。这是典型的"测试写了但没测住"。
- **建议修法**：
  ```kotlin
  // 把 & 排除出字符类；&id2= 的剥离交给后续处理
  Regex("""msss\.html\?id=([^"'\s<&]+)""")
  ```
  payload 里的裸 `&` 是 m3u8 自身的 query 分隔符吗？不是——源站给的是 `encodeURIComponent` 后的 payload，`&` 会被编码成 `%26`，所以排除裸 `&` 是安全的（`URLDecoder` 解码后会还原出真正的 `&`）。
  同时把 `:72-83` 的断言从 `contains` 改成**精确相等**：
  ```kotlin
  assertEquals("http://hdl2.qiuximing.com/live/a.m3u8?auth_key=abc&t=123", resolved!!.m3u8Candidates.first())
  ```
  并补一条"候选里不含 `id2`"的断言。

---

## P1（重要）

### P1-1 比赛状态是"一次性快照"，进程内永不过期——这是"比赛早早结束了还显示直播中"的结构性根因

- **文件**：`data/remote/JrkanParser.kt:138-151`（`parseStatus`）、`data/repository/MatchRepository.kt:240-249`（`statusByClock`）、`ui/MainViewModel.kt:213-259`（`refresh`）
- **问题**：状态只在两个时刻计算一次——① 解析列表时（`JrkanParser.parseStatus`，用 `ZonedDateTime.now()` 算 3.5 小时窗口）；② 列表加载完成后校正一次（`correctLiveStatuses`）。算完就写进 `Match.status`，之后**没有任何机制让它随时间老化**。
  我 grep 了 `ui/` 与 `data/` 全目录：没有状态轮询、没有 `Ticker`、没有"每分钟重算状态"的逻辑。`PlayerScreen.kt` 里的 `delay(15_000)` / `delay(25_000)` 都是播放器超时，与列表状态无关；`PlayerGestureLayer.kt` 的 `delay` 是浮层自动收起。
- **触发条件**：用户早上打开 App（列表里有若干"直播中"），把 App 切后台或就这么放一下午，再切回来。列表**不会重新拉取**（README 明确「无自动轮询」，这个取舍本身是对的），于是上午 10:00 开赛、13:30 就该结束的比赛，到晚上仍显示"直播中"，点进去是网络错误。
  `correctLiveStatuses` 只在 `loadMatches` 内部被调用，用户不手动点刷新就永远不会再执行。
- **为什么"修过 `correctLiveStatuses`"还会复现**：那次修的是**校正算错了**（`LiveStatusCorrectionTest` 的 6 条用例全部在钉计算规则：3.5 小时窗口、跨零点、拿不到详情页时按整天兜底）。而"算对了但**不会算第二次**"这一层没有任何测试，也没有任何代码。
- **建议修法**：**不要**引入网络轮询（会打爆源站，违背请求礼仪）。纯本地重算即可：
  1. 把 `statusByClock` 提成接收 `now` 参数的纯函数（顺带修掉 P1-4），放在 `MatchStatus` 旁边；
  2. `MainViewModel` 加一个轻量协程：只在**列表页可见**时，每 60s 对 `_listState.matches` 做一次纯内存的 `statusByClock` 重算，只有状态真的变了才 `update`（避免无谓重组）；
  3. 用 `Lifecycle.repeatOnLifecycle(STARTED)` 包住，切后台自动停。
  全程零网络、零 IO，119 场重算在微秒级，不违反「无自动轮询」的请求礼仪。

### P1-2 单源失败对用户完全静默：一个源挂了，用户只看到"场次变少了"

- **文件**：`data/repository/MatchRepository.kt:81-92`
- **问题**：
  ```kotlin
  val jrs = jrsDeferred.await().getOrElse { Log.w("MatchRepo", "JRS fetch failed", it); emptyList() }
  val jrkan = jrkanDeferred.await().getOrElse { Log.w(...); emptyList() }
  if (jrs.isEmpty() && jrkan.isEmpty()) throw IOException("两个数据源都没有返回赛事，请检查网络")
  ```
  任一源失败只写一条 `Log.w`，然后当成"这个源今天没比赛"。**只有两个源同时为空**才报错。部分失败完全静默。
- **触发条件**：JRS 被 Cloudflare 拦（403）或超时、JRKAN 正常 → 用户看到只剩 JRKAN 的场次，界面无任何提示；反之亦然。
  这与用户反馈的"明天有很多场次但只显示部分"症状**高度吻合**，而且是代码侧可修的（源站本身缺场次是另一回事，两件事会叠加，用户无法区分）。
- **建议修法**：`loadMatchesInternal` 返回结构里带上失败源信息，例如
  ```kotlin
  data class LoadOutcome(val matches: List<Match>, val failedProviders: List<Provider>)
  ```
  `MatchListUiState` 加 `partialError: String?`，UI 用已有的 `ErrorBanner`（`MatchListScreen.kt:125` 已实现该组件）显示"JRS 源暂时不可用，仅显示部分场次"。
  这是把"静默缺数据"变成"用户知道缺数据"的最低成本改动，也让线上问题可被用户准确反馈。

### P1-3 联赛名解析失败会导致整场被静默丢弃（不是显示为"其他"，是直接消失）

- **文件**：`data/remote/JrkanParser.kt:69-70`、`:112`；`data/model/Match.kt:31-37`；`data/repository/MatchRepository.kt:487`
- **问题**：这是一条**完全没有日志的丢场次路径**：
  1. `JrkanParser.kt:69-70` 用正则取联赛名，失败时 `.orEmpty()` → `leagueRaw = ""`；
  2. `:112` `league = League.fromSource("")` → `aliasMap` 无命中、`NBA_RE`/`CBA_RE` 都不匹配 → 返回 `League.OTHER`；
  3. `MatchRepository.kt:487` `merge` 里 `.filter { it.league in League.supported }`（`supported = {NBA, CBA}`）→ **整场被丢弃**。
  对比队名缺失（`:79-83`）有 `Log.w` 留痕，联赛名缺失这条路径**连日志都没有**。
- **触发条件**：`lab_events` 区块的结构变化导致 `span class="name"` 取不到（例如上游把联赛名换成 `<span class="league">`，或整个 `li.lab_events` 被移除/改名）。此时 119 场**全部**变成 OTHER → 全部被过滤 → JRKAN 源贡献零场次，而 App 表现只是"场次少了"，日志里一行都看不到。
- **实测依据**：我按 `JrkanParser.kt:69` 的正则在真实数据上复算，119/119 都能取到联赛名（`NBA`/`CBA`/`英超`/`德甲`…），说明**当前是好的**；但正则写法 `lab_events\b[^>]*>.*?<span class="name">([^<]*)` 依赖 `span class="name"` 这个精确字面量，容错方向和 `:72-74` 注释里承认过的历史事故（`lab_team_home"><strong class="name">` 写死导致整表归零）是同一类。
- **建议修法**：
  1. `leagueRaw` 为空时**不要**静默归为 OTHER，而是 `Log.w` 留痕并考虑"保留为 OTHER 但不丢弃"（或按队名白名单兜底识别 NBA/CBA 球队）；
  2. 在 `merge` 的过滤处加一条统计日志：`Log.w("MatchRepo", "过滤掉 N 场非 NBA/CBA")`，让"被过滤"这件事在日志里可见；
  3. 根本修法见 P1-7（改用 Jsoup）。

### P1-4 详情页校正有 8 场硬上限，超出的场次当天内仍留在"直播中"

- **文件**：`data/repository/MatchRepository.kt:149-153`（`MAX_DETAIL_FETCH = 8`，常量在 `:322`）
- **问题**：`toFix.take(MAX_DETAIL_FETCH)` 只校正前 8 场 JRS 直播场次，其余走 `else` 分支的 `isPastDayEnd` 兜底，而 `isPastDayEnd`（`:214-220`）只在**整个自然日过完**后才判 `FINISHED`。
- **触发条件**：CBA 一个比赛日 19:35 同时开球 6~8 场、再加 NBA 上午场，JRS 侧 `status == LIVE` 的场次很容易超过 8。第 9 场起在**当天 23:59 之前**一直显示"直播中"——19:35 开球、22:00 早就结束的比赛，到午夜前都还亮红。这正是 v1.2.8 用户截图那个 bug 的**未覆盖余量**，修了主路径但没覆盖超预算的那部分。
- **建议修法**：
  1. `toFix` 不要按列表顺序截断，改为按"最可能已结束"排序后取前 8（例如按 `startAtMillis` 升序，最早开赛的最先校正）；
  2. 预算从 8 提到 12~16（详情页约 5KB，在 4 并发门限下对源站压力差异可接受）；
  3. 对被跳过的场次给一个可见降级，而不是干等整天结束。

### P1-5 `statusByClock` 忽略注入的 `now`，`correctLiveStatuses(now=...)` 的"可注入时钟"承诺是假的

- **文件**：`data/repository/MatchRepository.kt:240-249`（`statusByClock` 内部 `:243` 重新取 `ZonedDateTime.now()`），对比 `:131-134` 的 `now` 参数
- **问题**：`correctLiveStatuses` 有一个 `now: ZonedDateTime` 参数，注释（`:124-130`）明确说是"让单元测试把跨零点的分支固定下来"。但 JRKAN 分支（`:174-177`）调用的 `statusByClock` **内部重新取了真实时钟**，完全无视传入的 `now`。只有 `isPastDayEnd`（`:192`）真正用了 `now`。
- **触发条件**：任何想用 `correctLiveStatusesForTest(now = 固定时刻)` 验证 **JRKAN 场次**状态校正的测试都会随真实时间漂移——同一份测试上午跑和晚上跑结果不同。
  当前 `LiveStatusCorrectionTest` 恰好只测了 JRS 分支（走 `jrsParser.correctedStatus`，那个确实接收 `now`），所以问题被完美掩盖。这是"测试通过"与"代码正确"脱钩的又一个例子。
- **建议修法**：`statusByClock(startAtMillis, now)` 加参数，`:175` 改为 `statusByClock(m.startAtMillis, now)`。
  同类问题：`JrkanParser.parseStatus`（`:145`）也内部取 `ZonedDateTime.now()`，建议一并参数化。

### P1-6 release 包会通过 `t.message` 把带签名的播放地址写进 logcat，与 `HttpClient` 的脱敏策略自相矛盾

- **文件**：`ui/MainViewModel.kt:492`（另有 `:249`、`:288`、`:387`）；`data/repository/MatchRepository.kt:82`、`:86`
- **问题**：`HttpClient.kt:63-67` 有一段非常明确的注释——**不打 `e.message`**，理由是"OkHttp 部分异常消息内嵌完整 URL（如 `unexpected end of stream on https://...?auth_key=...`），照样泄露播放凭证。只打异常类型"。但 `MainViewModel` 和 `MatchRepository` 里用的是
  ```kotlin
  Log.w("MainViewModel", "resolveStream failed: ${t.message}", t)   // :492
  ```
  这些是 `Log.w`（release 常开，不像 `debugLog` 有 `BuildConfig.DEBUG` 门禁），而且第三个参数传入 `Throwable` 会把**完整堆栈 + message** 一起打印。
- **触发条件**：`resolveStream` 抛 `IllegalArgumentException`——`HttpClient.get` 的 `Request.Builder().url(url)`（`:53`）在遇到畸形 URL 时会抛，message 里就带那个 URL；而该 URL 来自 m3u8 候选，query 里可能有 `auth_key`。异常一路传播到 `MainViewModel.kt:487-495` 被原样打印。用户设备上 `adb logcat` 无需任何权限即可读取。
- **为什么 `RedactUrlTest` 没兜住**：它只测 `redactUrl()` 这个纯函数（5 条用例：query/fragment/无 query/非法地址/http），**完全不覆盖"调用点是否真的用了 redactUrl"**。工具函数有测试、使用纪律没有测试，是这类泄漏最常见的形态。
- **建议修法**：
  1. 四处 `Log.w(..., t.message, t)` 改为只打类型，与 `HttpClient` 对齐：
     ```kotlin
     Log.w(tag, "resolveStream failed (${t.javaClass.simpleName})", t)
     ```
     （`Throwable` 堆栈本身不含 URL，安全）
  2. 或加一个 `Throwable.safeMessage()` 扩展，内部先过 `redactUrl`；
  3. 补一条**静态检查**测试：读源码断言 `Log.w` 里不出现 `${t.message}`，把纪律固化成测试。

### P1-7 `JrkanParser` 用正则逐块切 HTML，块起点与 `data-lid` 写死，上游一变就整表归零且静默

- **文件**：`data/remote/JrkanParser.kt:61`（块起点）、`:66`（`data-lid` 严格三段）、`:69-78`（各字段正则）
- **问题**：整个 JRKAN 解析是**手写正则跑在 JS 文本上**，不是 Jsoup。三个脆弱点：
  1. 块起点 `Regex("""<ul class="item play""")`。我核对真实数据：当前恰好是 `<ul class="item play d-touch active "` 与 `... active hot` 两种形态，都能命中。但只要上游变成 `class="item play"` 之外的形式（`item  play` 双空格、属性顺序变成 `data-lid` 在 `class` 前、换成单引号 `class='item play'`），`findAll` 返回空 → **`parseList` 静默返回空列表**。
  2. `data-lid="(\d+),(\d+),(\d+)"` 严格要求三段纯数字。真实数据 119/119 符合。上游加第四段（`eventId,sportId,leagueId,matchId`）→ 每块都在 `:66` 处 `return@forEach` → 整表归零。
  3. 对比：`JrsParser` 用 Jsoup + CSS 选择器，同样的改版冲击小得多。
- **为什么现有测试没发现**：`JrkanParserTest` 11 条用例全部基于**同一份手写的、结构固定的 `indexJs` 样本**（`:14-28`），只覆盖了"上游给 li 多加属性"这一种变体（`:37-65`）。没有一条测"块起点写法变了会怎样"——而这恰恰是最容易整表归零的地方。
- **建议修法**：`index.js` 的本质是 `document.write('<ul ...>...</ul>')`，把 `document.write('` 与 `')` 剥掉后就是**合法 HTML 片段**，完全可以交给已依赖的 Jsoup（1.18.1）：
  ```kotlin
  val html = js.replace("document.write('", "").replace("');", "")
  Jsoup.parse(html).select("ul.item.play").forEach { ul -> ... }
  ```
  字段用 `selectFirst("li.lab_team_home strong.name")?.text()` 取，块边界、属性顺序、空白全部交给 HTML 解析器，鲁棒性与 `JrsParser` 对齐。
  另加一条可观测性：`parseList` 结束时若结果为空但原文含 `data-lid`，打一条 `Log.w`，把静默归零变成可诊断。

### P1-8 `JrsParser.parseScheduledTime` 的正则要求 class 值紧跟引号，上游加一个 class 就静默返回 null

- **文件**：`data/remote/JrsParser.kt:157-158`
- **问题**：
  ```kotlin
  Regex("""match-info-time"[^>]*>\s*(\d{1,2}:\d{2})""")
  ```
  `time"` 要求 class 属性值**以 `match-info-time` 结尾且紧跟引号**。实测四种形态：
  | 输入 | 结果 |
  |---|---|
  | `<p class="match-info-time">19:35</p>` | `19:35` ✅ |
  | `<p class="match-info-time live">19:35</p>` | **null** ❌ |
  | `<p class="foo match-info-time">19:35</p>` | `19:35` ✅ |
  | `<p class="match-info-time">直播中</p>` | null（预期） |
- **触发条件**：源站在这个 `p` 上追加状态类（`live` / `picked` / `active`）——**这类站点极常见的改版动作**。一旦发生，`correctedStatus` 全部拿到 `null`，`correctLiveStatuses` 退回 `isPastDayEnd` 兜底 → 当天内**所有**已结束场次继续显示"直播中"。即 P1-4 的 bug 会从"超出 8 场"扩大到"全部场次"。
- **当前是好的**：真实抓包 `.forensics/detail_364127.html:38` 恰好是 `<p class="match-info-time">19:35</p>`，测试样本也都是无附加 class 的形式。所以这是**容错方向选错**，不是现存故障——但代价是零成本可修的。
- **建议修法**：既然已经用 Jsoup，直接 `doc.selectFirst(".match-info-time")?.text()` 再从中抠 `\d{1,2}:\d{2}`。或至少把正则改成 `match-info-time\b[^>]*>\s*(\d{1,2}:\d{2})`（用 `\b` 而非 `"`）。补一条"class 带额外 token 时仍能解析"的单测。

### P1-9 JRS 与 JRKAN 的源站域名全部写死，源站换域名后无任何告警

- **文件**：`data/repository/MatchRepository.kt:300`（`JRS_LIST_URL = "https://jrszbt.com/"`）、`:270`、`:554`；`data/remote/JrsParser.kt:41`（`SITE_ORIGIN`）；`data/remote/JrkanParser.kt:38`（`PLAY_HOST = "http://play.sportsteam356.com"`）、`:105`
- **问题**：这类源站（免费盗播站）换域名是常态。代码里域名全是单值常量，**没有候选列表、没有兜底域名**。我 grep 了整个 `main/`，不存在任何 mirror / fallback host 机制。
- **实测佐证**：`.forensics/` 抓包里的页面自身引用的是 `www.jrszb444.com`（资源域名）、`www.jrszb222.com`（列表区块），而 App 写死的是 `jrszbt.com`——**同一个站点的多个域名并存**，说明轮换是真实存在的现象，不是假想。
- **触发条件**：`jrszbt.com` 失效。此时 `fetchListWithRetry` 重试 3 次后返回 null → `fetchJrs` 返回空 → 静默降级为只剩 JRKAN（叠加 P1-2，用户完全不知情）。若 JRKAN 域名也换了（`PLAY_HOST` 已换过两次，见 `:27-29` 注释），则**两个源同时挂** → 才报"两个数据源都没有返回赛事"。
- **补充**：`JrkanParser` 的播放域名问题更棘手——真实 `index.js` 里播放地址是 `getPlayUrl("lineN", playId)` 这个 **JS 函数**在浏览器端算出来的，静态文件里只有 119 处调用、**没有函数定义体**（我已确认全文无 `function getPlayUrl`）。所以真实播放域名只能由源站 JS 决定，App 无从得知，只能硬编码。
- **建议修法**：
  1. 列表域名做成候选列表，`fetchListWithRetry` 失败时按顺序试下一个；
  2. `PLAY_HOST` 同样做成候选列表，`loadSignals` 展开失败时试下一个（`MatchRepository.kt:583-591` 现有的 `flatMapIndexed` + `ifEmpty` 结构正好可扩展）；
  3. 把"域名失效"做成可观测事件（`Log.w` 已是常开，但至少要能把 `Rejected`/`Unreachable` 计数上报）。

---

## P2（建议）

### P2-1 队名正则跨字段串味：主队 li 缺 `strong.name` 时，主客队会被解析成同一支队

- **文件**：`data/remote/JrkanParser.kt:75-78`
- **问题**：`lab_team_home\b[^>]*>.*?<strong class="name">([^<]*)` 用了 `DOT_MATCHES_ALL`，`.*?` 不限制在**本 li 内**。若主队 li 里没有 `strong.name`（例如改成 `<em class="tbd">待定</em>`），正则会一路扫到**客队 li**，把客队的名字捕获成主队。
- **实测**：
  ```
  输入: <li class="lab_team_home"><em class="tbd">待定</em></li>
        <li class="lab_team_away"><strong class="name">篮网</strong></li>
  结果: home=[篮网]  away=[篮网]   → home==away
  ```
  而 `:79-83` 的守卫只判 `isBlank()`，两值都非空 → **照常通过**，产出一张"篮网 vs 篮网"的卡片。
- **对比**：队徽的正则（`:86-87`）**已经**用 `block.substringBefore("lab_team_away")` 把自己限制在本 li 内，说明作者意识到这个问题——但队名这三处没做同样处理。
- **触发条件**：上游对某场比赛的主队名用非 `strong.name` 标签（TBD 场次、或改版）。
- **建议修法**：与队徽一致，先把 block 按 `lab_team_away` 切开再匹配主队；或直接改用 Jsoup（P1-7）后 `li.lab_team_home strong.name` 天然受限。另加一条守卫：`home == away` 时视为解析失败并 `Log.w`。

### P2-2 全链路用 `ZoneId.systemDefault()` 解释源站时间，设备时区非东八区时状态判定整体偏移

- **文件**：`JrsParser.kt:180,186,232`、`JrkanParser.kt:144,223`、`MatchRepository.kt:216,218,242,524`
- **问题**：源站时间（`"10-25 07:30"`、`"19:35"`）是**北京时间**，代码一律用设备本地时区解释。
- **触发条件**：设备时区设为 UTC-5 → 源站 `07:30` 被解释成北京时间 20:30 → `statusByClock` 的 3.5 小时窗口判定错位，列表"直播中"与排序全部错乱。
- **建议修法**：产品定位中国用户，解析侧固定 `ZoneId.of("Asia/Shanghai")`，展示侧继续用 `systemDefault()`。另 `MatchFormat.kt:26` 用 `by lazy` 缓存 `systemDefault()`，用户改时区后不重启不生效（注释已承认），可接受但值得记一笔。

### P2-3 `loadMatches` 无整体超时，最坏情况用户盯着转圈约 3 分钟

- **文件**：`MatchRepository.kt:76-99`（`withContext(Dispatchers.IO)` + `supervisorScope`），配合 `HttpClient.kt:153-162`（`callTimeout = 35s`）
- **问题**：单源 3 次尝试 × 35s = 105s（两源并发，所以整体约 105s）；之后 `correctLiveStatuses` 最多 8 场 / 4 并发 = 2 批 × 35s = 70s。总最坏约 **175s**，全程无 UI 超时、无整体 deadline。
- **建议修法**：给 `loadMatchesInternal` 套 `withTimeoutOrNull(60_000)`，超时返回"部分结果 + 可重试提示"，而不是无限等。

### P2-4 `refresh()` 的取消收尾会覆盖新任务的 `refreshing=true`（状态竞态）

- **文件**：`ui/MainViewModel.kt:216-246`
- **问题**：`refreshJob?.cancel()` 后旧协程的 `catch (ce: CancellationException)`（`:240-246`）会写回 `loading=false, refreshing=false`。若新协程已经先写了 `refreshing=true`，旧协程的收尾会把它抹掉 → 刷新指示器闪一下就消失，但请求还在跑。
- **建议修法**：收尾前判断自己是否仍是当前 job（`if (refreshJob === coroutineContext[Job])`），或不在取消分支里写状态、交给新任务统一设置。

### P2-5 `back()` 从播放页返回时不取消 `detailJob`

- **文件**：`ui/MainViewModel.kt:645-669`
- **问题**：`Route.PLAYER` 分支取消了 `playJob`/`loadJob`，但**没取消** `detailJob`（`refreshSignals` 起的那个）。若用户点"刷新线路"后立刻进播放页再返回，`detailJob` 可能在返回后写 `_detailState`。
- **影响**：小（写的是 `_detailState`，此时 route 是 DETAIL，值基本正确），故 P2。
- **建议修法**：该分支补 `detailJob?.cancel()`。

### P2-6 详情页 URL 模板重复两遍，改一处会静默失配

- **文件**：`MatchRepository.kt:270`（`"https://jrszbt.com/live/$detailId.html"`）与 `:554`（同一字符串第二遍）
- **问题**：将来若改成其他路由形态，漏改 `invalidateDetailCache` 会让"刷新线路"按钮变成摆设，且**不报错**——正是 `MatchRepositoryRetryTest` 里「用户点刷新线路时绕过缓存 真的回源」那条想防的回归。
- **建议修法**：抽 `private fun detailUrl(id: String) = "https://jrszbt.com/live/$id.html"`，两处共用。

### P2-7 `fetchJrsDetail` 的缓存时间戳在 fetch 之前取，长耗时请求会缩短实际 TTL

- **文件**：`MatchRepository.kt:277`（取 `now`）与 `:292-294`（存 `now to body`）
- **问题**：`now` 在发起请求**之前**计算，请求耗时（最长 35s）被算进缓存年龄。轻微缩短 TTL，无正确性影响。
- **建议修法**：改在拿到 body 之后取时间戳。

### P2-8 `fetchJrsDetail` 无 single-flight，并发同 URL 会重复请求

- **文件**：`MatchRepository.kt:265-297`
- **问题**：缓存只挡"已完成的请求"，挡不住"正在飞的请求"。`correctLiveStatuses`（4 并发）与 `loadSignals` 若同时请求同一 `detailId`，两边都会 miss 并各打一次源站。
- **触发条件**：用户在列表刷新还没结束时点进某场播放页。
- **建议修法**：用 `ConcurrentHashMap<String, Deferred<String?>>` 做 in-flight 去重（`async` 后 `await`）。

### P2-9 `redactUrl` 对"无 query 但凭证在 path/userinfo"的地址不做脱敏

- **文件**：`data/remote/Logs.kt:28-42`
- **问题**：`:37` 在既无 `?` 又无 `#` 时直接 `return url`。注释（`:39-40`）承认 path 里可能带签名但选择保留。另外 `https://user:pass@host/` 这类 userinfo 完全没处理。`RedactUrlTest` 5 条用例也没覆盖这两种。
- **建议修法**：至少把 authority 里的 userinfo 抹掉（`user:***@`）；path 侧在日志里做长度截断（保留前 40 字符）。

### P2-10 `MatchStatus.fromSource` 把"延期/推迟/待定"归为 `UPCOMING`，界面上与"未开赛"无法区分

- **文件**：`data/model/Match.kt:60`
- **问题**：延期的比赛显示成"未开赛"，用户按时间找过去发现没有直播。枚举里没有 `POSTPONED`。
- **建议修法**：加 `POSTPONED`（`fromSource` 里先于"未开赛"判定），列表用不同颜色/文案。属产品决策。

### P2-11 `JrkanParser.parseDateTime` 在 2 月 29 日会抛异常并静默降级

- **文件**：`JrkanParser.kt:210-226`
- **问题**：`LocalDate.of(now.year, 2, 29)` 在平年抛 `DateTimeException`，被 `runCatching` 吞掉返回 `null` → `startAtMillis = null` → `parseStatus` 退回 `UPCOMING`，且跨源合并键退化成 `timeLabel`（`MatchRepository.kt:525`），可能合并不上。
- **建议修法**：用 `MonthDay.of(month, day).atYear(year)` 并捕获后回退到月末，或 `LocalDate.of(year, month, 1).withDayOfMonth(min(day, lengthOfMonth()))`。

### P2-12 `merge` 的 `normalizeKey` 在 `startAtMillis == null` 时退化成 `timeLabel` 前缀，跨源去重失效

- **文件**：`MatchRepository.kt:522-528`
- **问题**：`m.startAtMillis ?: m.timeLabel.substringBefore(' ').ifBlank { "?" }`。JRS 行 `timeLabel = "直播中"` 时得到 key `"直播中|A|B"`，JRKAN 得到 `"10-25|A|B"` → 同一场不合并 → 两张卡片、各只有半边信号。
- **触发条件**：JRS 列表项缺 `p.list-title`（`dateLabel` 缺失 → `parseDateTime` 返回 null）。真实抓包有 title，当前不触发。
- **建议修法**：`parseDateTime` 拿不到日期时，用列表分组的 `dateLabel` 兜底（`parseList` 已拿到该值，只是没参与 key）；再不行用"当天"兜底而非字面量。

### P2-13 `selectLeague` 的 `League.OTHER` 分支是死代码

- **文件**：`ui/MainViewModel.kt:49`、`:57`
- **问题**：`merge` 在 `MatchRepository.kt:487` 已 `filter { it.league in League.supported }` 丢掉 OTHER，所以 `visibleMatches` 的 OTHER 分支和 `counts[OTHER]` 恒为空/0。`League.filters`（`Match.kt:14`）也不含 OTHER，UI 不会暴露，但代码留着会误导维护者。
- **建议修法**：删掉，或加注释说明"合并阶段已过滤，此处仅防御"。

---

## 待确认（需线上/实机验证，本次无法确定）

1. **JRS 列表页是否有按日期查询的独立路由**。`.forensics/jrs_list.html` 只有 3 个日期分组，且**第 3 个 `2026-10-26` 分组是空的**（第 221-223 行 `<div class="list"><p class="list-title">2026-10-26</p></div>`），页面底部有 `/e/extend/DoTimeRepage/` 这个脚本（名字暗示"日期翻页"）。`JrsParser.parseList` 只解析首页 HTML，若源站有 `?date=` 之类路由，当前实现永远拿不到更远的场次。**这是"场次不全"最值得优先验证的代码侧线索**。
2. **`JRKAN_LIST_JS` 的 `?t_5` 是否代表分片**。`MatchRepository.kt:301` 写死 `index.js?t_5`。我检查了抓包文件：**内部没有任何 `t_N` 引用、没有分页参数**，内容覆盖 10-24（25 场）与 10-25（94 场）两天共 119 场，看起来是完整两天。所以"只拉了 1/N"这个假设**暂无证据**，但 `t_5` 这个参数名的含义仍无法从静态文件确认。
3. **`PLAY_HOST = play.sportsteam356.com` 当前是否仍存活**。真实 `index.js` 里无可静态求值的域名（`getPlayUrl` 是 JS 函数），需实机抓一次播放页确认。
4. **`msss.html` iframe 当前是否带 `&id2=`**。这决定 P0-1 是"正在发生的故障"还是"潜伏的故障"。代码注释与正则前瞻都指向"会带"，但抓包里没有 msss 页面的实际 HTML。**建议实机抓一次 `/play/sm.html` 的响应确认**——这直接决定 P0-1 的紧急程度。

**关于"场次不全"的诚实结论**：两个源站首页各只覆盖约 2 天（JRS：10-24/25/26，其中 26 空；JRKAN：10-24/25）。App 没有任何日期维度，就是"源站首页给什么就显示什么"。这与用户自己的判断（"源头是数据源本身缺场次"）一致。**代码侧确实没有找到"只取第一条/只取某个区块"导致的漏场次**——解析器对每个块都遍历（JRKAN 119 块全部解析成功，实测 119/119 拿到队名/联赛/时间/playId）。但存在两条**静默丢场次**的路径（P1-3 联赛名失败→OTHER→被过滤；P1-2 单源失败→静默降级），它们会在源站改版或限流时叠加放大"场次不全"的观感。

---

## 做得好的地方（后续维护请勿回退）

- `MatchRepository.kt:46-53` 的 `catching` 显式重抛 `CancellationException`，避开 `runCatching` 吞取消的经典坑。
- `FetchResult` 区分 `Rejected`/`Unreachable`，并配 `MatchRepositoryRetryTest` + `HttpClientFetchTest` **双层**覆盖。尤其 `HttpClientFetchTest` 用裸 Socket 起真实 HTTP 服务器测状态码映射（注释解释了为何不能用 `com.sun.net.httpserver`），思路正确且必要。
- 详情页 TTL 缓存用 `System.nanoTime()` 而非 `currentTimeMillis()`（`MatchRepository.kt:277`），规避用户改系统时间导致缓存永不过期。
- `MatchRepository.kt:599-601` 最后一道 `mapIndexed { copy(id = ...) }` 兜底保证信号 id 唯一，配合 `SignalIdUniquenessTest`，把"LazyColumn 重复 key 闪退"挡在门外。
- `MatchRepository.kt:141-153` 对详情页请求做**并发 + 总量**双重预算，注释准确指出"并发封顶只限同时几个，不限一共几个"，这个认知很到位。
- `JrkanParser.parseChannels` 过滤主播/解说线路是产品要求，且 `MatchRepository.kt:591` 有 `ifEmpty` 兜底（全被过滤时退回列表页播放页），不会把线路清空。
- `MainViewModel` 的 `playJob`/`detailJob`/`loadJob`/`refreshJob` 四路取消，以及 `if (!isActive) return@launch` + `_route.value != Route.PLAYER` 双重守卫（`:400-401`、`:499`、`:527`），对"挂起返回后取消仍会跑完同步代码"这个 Kotlin 协程陷阱处理得比大多数代码都细致。
