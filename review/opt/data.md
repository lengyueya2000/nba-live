# 数据层新增优化点（只读审查 · rev-data / task-2）

**范围**：`app/src/main/java/com/nbalive/app/data/` 全部 13 文件（`MatchRepository.kt` 实为 **1180** 行）+ 跨文件引用点 `ui/TeamNameBridge.kt`、`ui/UpdateCheckPolicy.kt`、`ui/SniffedPlaylistCache.kt`、`ui/MainViewModel.kt` 消费数据层的段。
**方法**：`read`/`grep`/`glob` 逐文件读完（行号以 `read` 为准）；用 `.forensics/` 真实样本交叉核对解析假设：`jrs_list.html` 11,136 B、`jrkan_index.js` **271,323 B**、`detail_36412{7,8,9}.html` 各 4,525 B。
**边界**：未跑 Gradle/adb；**未改**任何生产代码/测试/构建脚本；唯一写入物 = 本文件。
**与台账的关系**：已先读 `review/coverage-ledger.md`（95 条：87 已落地 / 8 有据取舍）。下列 **18 条都不在 95 条清单里**；其中 D1/D2/D3/D5 是对**已落地修复的余量**（02-P2-3 超时 / 02-P1-3 联赛兜底 / 02-P1-9 域名候选 / 列表加载）的补充，不是翻案，正文都注明了"该修复的表态 vs 实现"。
**一次性外部探测（外部数据，仅旁证）**：`http://play.sportsteam368.com/play/steam824920.html` → **HTTP 404**（"此场结束啦"），用于支撑 D3：源站对**已结束**场次的播放页确实返回 404。

## Top 5 高价值

| # | 一句话结论 | 位置 | 等级 | 成本 | 判定 |
|---|---|---|---|---|---|
| D1 | 60s 整体 deadline 一旦触发，另一个源**已拿到的场次被整体丢弃**，还被报成"两个源都超时" | `MatchRepository.kt:630-642` | 高 | M | 已证实 |
| D2 | 合并时 `league` 保留 JRKAN 的值：JRKAN 判 `OTHER` 会把同场 JRS 记录一起带进 `OTHER` → **整场静默丢弃** | `MatchRepository.kt:823-847` | 高 | S | 已证实 |
| D3 | 任何非 2xx（含 404）都算"host 不可达" → 健康域名降到候选队尾，且**新首位要跑满 3 次重试** | `HttpClient.kt:105-109`+`MatchRepository.kt:343-345` | 中 | S | 已证实（链末端推测） |
| D4 | 播放页解析仍手写正则，**只认双引号 `data-play="`、只认裸 `<strong>`**：上游一改写法线路整表清零；`<strong>` 内嵌标签则主播线路**绕过过滤** | `JrkanParser.kt:301-310` | 中 | S | 已证实（不对称）/触发推测 |
| D5 | 列表层完全没有 TTL/缓存：每次刷新重下 **271 KB** 的 `jrkan_index.js` | `MatchRepository.kt:607-610`+`MainViewModel.kt:671-674` | 中 | M | 已证实 |

---

### D1 整体 deadline 会丢掉已成功的另一边数据
**位置** `data/repository/MatchRepository.kt:622-642` ｜ **价值/成本** 高/M ｜ **判定** 已证实
**原文**
```kotlin
                jrsDeferred.await() to jrkanDeferred.await()
            }
        }
        if (finished == null) {
```
**结论/影响**：`withTimeoutOrNull` 包住的是"两源都完成"这一个整体结果，超时即 `null` → `matches = emptyList()`（`:638-642`），源站慢/死的那一侧把**已经返回的另一侧场次**一起拖走。`:46-54` 的注释把 deadline 的动机写成"用户宁可看到部分场次 + 可重试"，`LoadOutcome.partialError()`（`:571-577`）也专为"还有部分数据"设计，但实现拿不到部分数据。具体场景：JRS 域名死时首候选要跑满 **3 次重试 × `callTimeout=35s`（`HttpClient.kt:156-159`）= 105s**，必然超过 60s → **JRKAN 2 秒就返回的十几场被丢弃**，界面得到"JRS、JRKAN 源暂时不可用（网络超时）"+ 空列表；即 deadline 存在的理由（域名死）恰好是它丢数据的场景，文案还把正常源报成超时。
**价值·理由**：高（用户可见故障 + 已有数据被丢 + 文案与事实相反）。修法：把超时下移到每个源（各 `async` + 各 `withTimeoutOrNull`，或用 `getCompleted()` 取已完成值），并把"超时源/正常源"分开组装 `failedProviders/reasons`。
**验证**：注入 fetch，令 JRS 永不返回、JRKAN 立即返回 3 场，断言 `matches.size == 3` —— 当前得 0。

### D2 合并方向让 JRKAN 的联赛分类"污染"掉有效的 JRS 场次
**位置** `data/repository/MatchRepository.kt:823-847` ｜ **价值/成本** 高/S ｜ **判定** 已证实
**原文**
```kotlin
                existing.copy(
                    // 状态取"更可信"的那个，而不是"后合并的那个"：
                    // JRS 的列表会滞后（已结束还标直播中），
                    // JRKAN 自带 MM-dd HH:mm 可以做时钟校正。
                    // 原来直接用 m.status（JRS 后合并）会把 JRKAN 更准的状态覆盖掉。
                    status = betterStatus(existing.status, m.status),
```
**结论/影响**：`copy(...)` 的具名字段只有 status/timeLabel/startAtMillis/logo/sources/signalCount/jrsDetailId（`:828-836`），**不含 `league`**，于是沿用先合并的 JRKAN；随后 `val kept = all.filter { it.league in League.supported }`（`:847`）把整场丢掉。`mergeOne` 顺序恒为 `jrkan.forEach` → `jrs.forEach`（`:840-841`），所以 JRS 侧明明判出 NBA/CBA，也会被 JRKAN 的 `OTHER` 带走。`League.resolve`（`Match.kt:80-84`）只在**空串**时用队名兜底，JRKAN 联赛文案一旦变成非空但认不出的值（"篮球""夏季联赛"…）即触发：该场消失，用户只看到"场次少了"，`filteredOutCount` 也没有界面落点 → 静默且不可逆。
**价值·理由**：高（静默丢场次；成本 S，符合"收益明显且成本 ≤ S"）。修法：加 `betterLeague(a,b)`（`supported` 优先，其次非 `OTHER`）放进 `copy(...)`。
**验证**：构造 jrkan leagueRaw="篮球" + jrs leagueRaw="NBA" 的同键场次，断言 `mergeForTest(...)` 保留该场 —— 当前为空。

### D3 404 被当成"host 挂了"，反压方向相反
**位置** `data/remote/HttpClient.kt:105-109` + `data/repository/MatchRepository.kt:343-345` ｜ **价值/成本** 中/S ｜ **判定** 已证实（链末端推测）
**原文**
```kotlin
        return when {
            body != null -> FetchResult.Ok(body)
            // 403/429 是源站明确拒绝：再打只会加剧限流
            status == 403 || status == 429 -> FetchResult.Rejected(status)
            status > 0 -> FetchResult.Unreachable("HTTP $status")
```
**结论/影响**：`Unreachable` 的定义是"网络层失败，值得重试"（`FetchResult.kt:24-25`），但所有非 403/429 的非 2xx 都落进去；`is FetchResult.Unreachable -> { synchronized(listFailedHosts) { listFailedHosts += hostOf(url) } }`（`:343-345`，同款也在 `:741`、`:1080-1081`）把"某个页面 404"登记成"这个 host 上一轮失败"。连锁：① `SourceEndpoints.attemptOrder`（`:119-127`）把主域名挪到队尾 → **之后整个会话的列表/详情/播放都先试镜像**；② `runSource` 对首位候选给 3 次重试、对镜像只给 1 次（`:710-714`），降级后先跑死镜像 3×35s=105s > 60s deadline → **整表为空**（与 D1 叠加，这次连"另一源成功"都救不回）；③ 触发不需要域名故障——探测显示已结束场次的播放页就返回 **HTTP 404**。
**价值·理由**：中（前两条症状是"无谓重试 + 主域名被错误降级"，成本半小时：把 404/410 单独归类，既不重试也不登记 host 失败）；③ 需镜像真的不通才成立。
**验证**：注入 fetch 令某详情页返回 `Unreachable("HTTP 404")`，断言 `attemptOrder(JRS_LIST, failedHosts)` 首位仍是 `jrszbt.com` —— 当前会变成镜像。

### D4 播放页解析没跟上列表页的加固，过滤规则还可被 DOM 变化绕过
**位置** `data/remote/JrkanParser.kt:301-310` ｜ **价值/成本** 中/S ｜ **判定** 已证实（不对称与绕过链）/触发条件推测
**原文**
```kotlin
        Regex("""<a[^>]*data-play="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val path = m.groupValues[1].trim()
```
**结论/影响**：线路名走 `Regex("""<strong>([^<]+)</strong>""")`（`:309-310`）。① 列表页解析已为"单引号/属性顺序/`span` 替 `strong`"写过变体用例（台账 02-P1-7、02-P1-8），**播放页这两条没有**：源站改成单引号 → `path` 匹配归零 → 所有 JRKAN 场次 0 条线路；更糟的是 `pageFetched=true`（`:1066-1090`）使 `loadSignals` **不再退回入口页做 WebView 兜底**（`:1013-1017`），用户看到"该赛事暂无可用线路"而不是"线路坏了"。② 线路名若是 `<strong><span>主播解说①</span></strong>` 这类嵌标签形态，`rawLabel` 退化为空串，`ChannelAdmission.admit` 的**唯一规则**（按名字过滤，`:77-79`）完全失效；而 `sortedByDescending { isDirectPath(path) }`（`:318`）把可直连的 `sm.html` 排到第 0 条 —— 2026-10-29 用户"过滤掉涉及主播的线路"的要求会被默认播回来。
**价值·理由**：中（触发是上游改版；后果是"产品要求被静默绕过"或"整源线路归零"）。修法廉价：放宽引号；名字用 Jsoup 片段解析（或 `<strong>([\s\S]*?)</strong>` 后 strip 标签），且**取不到名字时必须视为敏感并整条剔除**。
**验证**：抓一份真实存活的 `steam<id>.html`（本次探测的 10 月样本 playId 已 404），核对 `data-play` 引号形态与线路名标签内是否只有纯文本。

### D5 列表层没有 TTL / single-flight，下拉刷新就是全量重下
**位置** `data/repository/MatchRepository.kt:607-610` + `ui/MainViewModel.kt:671-674` ｜ **价值/成本** 中/M ｜ **判定** 已证实
**原文**
```kotlin
    internal suspend fun loadMatchesWithOutcome(
        enabled: Set<Provider> = Provider.entries.toSet(),
        fetch: suspend (String, String?) -> FetchResult,
    ): LoadOutcome = withContext(Dispatchers.IO) {
```
**结论/影响**：`ScoreboardRepository` 有 20s TTL、`fetchJrsDetail` 有 5 分钟缓存，**列表本身一次缓存都没有**：`fun refresh()` 每次 `refreshJob?.cancel()` 后重走两源全量请求（`MainViewModel.kt:671-674`），冷启动 `init { refresh() }` 也走它。实测样本量级 = JRKAN 索引 **271,323 B** + JRS 列表 11,136 B ≈ **282 KB/次**；用户"下拉刷新三次"即 800+ KB 与三倍源站压力，而这正是触发 403/429 的路径（`:44-54` 自述源站严格限流）。此外 `loadMatchesOutcome` 无 single-flight，`cancel()` 只换目标，已发出的请求要等 OkHttp cancel 才停。
**价值·理由**：中（收益明确：流量与源站压力；成本 M：要定 TTL 时长、"用户主动刷新是否绕过"、与 `refreshing` 语义配合）。
**验证**：真机抓包或给 `http.fetch` 加计数，连续两次下拉刷新观察请求数 —— 当前是 2×（另加重试）。

### D6 队名表有两份且互相不一致（缺 3 支 NBA 队 / 缺中文别名）
**位置** `data/model/Match.kt:105-110` + `ui/TeamNameBridge.kt:36-68` ｜ **价值/成本** 中/S ｜ **判定** ①已证实 ②推测
**原文**
```kotlin
        private val NBA_TEAM_KEYWORDS = listOf(
            "湖人", "勇士", "凯尔特人", "雄鹿", "热火", "太阳", "掘金", "快船",
            "鹈鹕", "爵士", "开拓者", "火箭", "马刺", "灰熊", "森林狼", "独行侠",
            "小牛", "76人", "步行者", "活塞", "魔术", "黄蜂", "老鹰", "骑士",
            "公牛", "尼克斯", "篮网", "国王",
        )
```
**结论/影响**：① `League.inferFromTeams`（`:66-71`）只在"恰好认出一个联赛"时返回；**雷霆/猛龙/奇才不在这张表里**，所以"雷霆 vs 猛龙""奇才 vs 雷霆"这类**双方都认不出**的对阵，在联赛文案为空时仍落 `OTHER` → 被 D2 的过滤器整场丢掉。对照 `TeamNameBridge.kt:57/65/67` 明确有这三队：桥能认、兜底不能认。② 反向缺口：`NICK_TO_CN` 每个昵称只给一个中文名，而源站对 Mavericks 可能写"小牛"（本仓自己在 `NBA_TEAM_KEYWORDS` 承认两种写法）；`findGame` 用 `toChinese(g.homeName) == h` 全等比较（`TeamNameBridge.kt:106-108`），源站写"小牛"时 ESPN 永远匹配不上 → 卡片静默不画比分，用户分不清"没数据"还是"对不上"。
**价值·理由**：中（两份表合并成一份"昵称 → {中文别名集合, 联赛}"，并补 3 队；成本 S）。
**验证**：`grep` 源站含 NBA 的真实列表队名（`.forensics/*` 只有足球与 CBA，需抓一次）。

### D7 比分失败时旧比分不过期，"冻结的比分"一直挂在卡片上
**位置** `ui/MainViewModel.kt:350-354`（消费者）+ `repository/ScoreboardRepository.kt:75-91` ｜ **价值/成本** 中/S ｜ **判定** 已证实
**原文**
```kotlin
                val snapshot = scoreboard.load(prefs.enabledProviders.value)
                if (snapshot.isEmpty()) {
                    debugLog("Scores", "ESPN 比分板为空（源站列表里没有对得上的场次，或网络失败）")
                    return@launch
                }
```
**结论/影响**：`snapshot.isEmpty()` 时直接 `return`，**不清 `_scores`**；而 `ScoreboardRepository` 失败时返回的正是"空快照"并写进 20s TTL 缓存（`:75-91`）。于是 ESPN 连续失败期间，界面保留最后一次成功的比分且无任何时效标记：用户看到的是几分钟前甚至更久的比分在"陪着比赛"，比赛结束后也不归零。比分是锦上添花，但"看起来在跳的旧数字"比"不显示"更容易误导。
**价值·理由**：中（给快照加时间戳，超 N 分钟或连续 M 次失败就剔除/置灰；改动只在 VM + 一个常量，需权衡"断流时闪一下"）。
**验证**：把 `ScoreboardRepository` 的 fetch 注入为恒失败，断言 `_scores` 在超时上限后不再含旧值 —— 当前永不剔除。

### D8 `ScoreboardRepository.load(enabled)` 的参数从未被使用
**位置** `data/repository/ScoreboardRepository.kt:67-69` ｜ **价值/成本** 中/S ｜ **判定** 已证实
**原文**
```kotlin
    suspend fun load(enabled: Set<Provider> = Provider.entries.toSet()): Snapshot {
        val now = System.nanoTime() / 1_000_000L
        cached?.let { if (now - cachedAt < ttlMs) return it }
```
**结论/影响**：签名收 `enabled`，KDoc 写明"全关掉时也没必要拉比分"，但函数体（`:68-91`）**一次都没读它**，也不会因 `enabled` 变化而失效缓存 —— 声明与实现相反。本仓对"声明了就要有消费点"是有纪律的（`SourceEndpointsWiringTest`，见台账 02-P1-9），这里 `prefs.enabledProviders.value`（`MainViewModel.kt:350`）纯装饰：两源都关的过渡态仍会打 ESPN；将来给 LiveNow 页也挂比分时，这个参数会让人误以为开关联动已做。
**价值·理由**：中（要么真消费（空集返回 `Snapshot.EMPTY` 且不进缓存），要么删参数改 KDoc；两行，避免下一个人照着 KDoc 假设）。
**验证**：函数体内 `grep enabled` 零命中。

### D9 403/429 只做到"这一次不重试"，没有冷却窗口
**位置** `data/repository/MatchRepository.kt:732-737`（定义见 `remote/FetchResult.kt:8-14`）｜ **价值/成本** 中/M ｜ **判定** 推测
**原文**
```kotlin
                is Fetched.Rejected -> {
                    // ⚠️ 403/429 是限流信号：**立即停整个源，不试镜像**。
                    // 镜像与主域名是同一站点家族，大概率共享限流窗口，
                    // 继续打只会把窗口越拖越长（review 的"限流放大器"）。
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    return SourceOutcome.Rejected(fetched.code)
```
**结论/影响**：注释的推理在**单次调用内**成立，跨调用不成立：`attemptOrder` 有意不永久拉黑（台账 02-P1-9 已论证），于是限流窗口内每次下拉刷新、切标签、进详情都再撞一次；用户遇到 403 的直觉动作恰是"一直刷新"，正好是最坏用法 → 窗口被拖长、长时间无数据。
**价值·理由**：中（加带 TTL 的"源冷却"，如 429 后 60s 内不发起该源请求，UI 照旧显示"源暂时不可用"；难点在取值要实测，且要与"不永久拉黑"的既定取舍区分开）。
**验证**：真机连续 5 次下拉刷新抓包统计 403/429 次数与间隔，或看 `Log.w("MatchRepo", "源站拒绝请求(HTTP 403)…")` 的频率。

### D10 1180 行的 `MatchRepository` 职责过宽，测试入口混在生产 API
**位置** `data/repository/MatchRepository.kt:39-56`（类体）+ `:798-799` ｜ **价值/成本** 中/L ｜ **判定** 已证实
**原文**
```kotlin
    internal fun mergeForTest(jrs: List<Match>, jrkan: List<Match>): List<Match> =
        merge(jrs, jrkan).matches
```
**结论/影响**：一个类同时承担：列表抓取/重试、两源合并去重、状态时钟校正 + 详情页预算、详情页 TTL 缓存与 single-flight、JRKAN 播放页展开、m3u8 候选校验；纯函数只抽出了文件尾部的 `selectDetailBudget`。合并规则（D2 的 bug 就在这里）、状态窗口、缓存身份（detailId vs URL）这些**判据类**逻辑与 IO/协程样板混在一个 1180 行文件里，每次改动都要在其中定位；`mergeForTest`（`:798`）、`correctLiveStatusesForTest`（`:213`）、`Merged`（`:802`）把测试关注点写进生产类，重构时容易被当成 API 保留。任务问的"912(=1180) 行仓库的职责边界"就在这。
**价值·理由**：中（拆 `MatchMerger`（纯函数）/`JrsDetailCache`/`JrkanExpansion`/`LoadOrchestrator`，`MatchRepository` 留入口与装配；收益明确但无用户可见故障，改动面大，必须与 99 个 suite 一起验证）。
**验证**：结构可核（行数、字段、内部 API 清单）。

### D11 数据层反向依赖 UI 包
**位置** `data/repository/ScoreboardRepository.kt:9`、`:101` ｜ **价值/成本** 中/S ｜ **判定** 已证实
**原文**
```kotlin
import com.nbalive.app.ui.TeamNameBridge
        return TeamNameBridge.findGame(snapshot.games, match.homeTeam, match.awayTeam)
```
**结论/影响**：`TeamNameBridge` 无任何 Compose/Android 依赖（只 import `EspnScoreboard`），本来就属于数据层；放在 `ui/` 让"哪个包是底层"的约定失效：将来做 data 层 JVM-only 测试或按包分层的守卫时，这条 import 会让数据层被 UI 包重构牵动（本仓已有同款跨包纪律测试，如 `ThemeSlotTest` 的思路）。
**价值·理由**：中（纯文件移动 + 2 处 import，无行为变化；若有意保留应在 KDoc 写明理由，现在没写）。成本 S。
**验证**：import 与调用点可核。

### D12 `forceRefresh` 路径会移除别人的在飞条目
**位置** `data/repository/MatchRepository.kt:301-305`（注册分支 `:285-295`）｜ **价值/成本** 低/S ｜ **判定** 推测
**原文**
```kotlin
            synchronized(inFlightDetails) { inFlightDetails.remove(detailId) }
            deferred.complete(
                synchronized(detailCache) { detailCache[detailId]?.second },
            )
        }
```
**结论/影响**：`forceRefresh=true` 时不注册自己的 deferred，却仍在 `finally` 里 `remove(detailId)` —— 可能删掉**另一个协程**正在等待的条目，让第三个来者重新发请求。等待者各自持有 `Deferred` 引用，不会被卡死；实际损失是**重复请求**（三路并发 + 用户点"刷新线路"同时发生的窄窗口）。
**价值·理由**：低（影响是偶发重复请求；作为"single-flight 真的覆盖所有入口"的收口值得补：把 `remove` 放进 `if (!forceRefresh)`）。
**验证**：三协程同时调 `fetchJrsDetail(id)`，其中一路 `forceRefresh=true`，统计 `fetch` 调用次数（预期 2，当前可能 3）。

### D13 热路径逐项新建 `Regex`，且 playId 正则只认单数字
**位置** `data/remote/JrkanParser.kt:129-130`、`:306`、`:309`（同类见 `JrsParser.kt:69`）｜ **价值/成本** 低/S ｜ **判定** 已证实
**原文**
```kotlin
            val playId = Regex("""getPlayUrl\("line\d",\s*"(\d+)"\)""")
                .find(block)?.groupValues?.get(1)
```
**结论/影响**：量级可估：`.forensics/jrkan_index.js` 每次刷新 119 个 `<ul>` 块 → 至少 119 次 `Pattern` 编译，另加每线路 2 次（`:306`、`:309`）；单次几十微秒量级，一次刷新几毫秒 —— **无测量支撑，只算整洁性**，但与本仓先例一致（`WHITESPACE`（`:413-419`）正是为"每次调用新建 Regex"提成常量）。`line\d` 是实际缺陷：源站线路从 `line1…line3` 扩到 `line10+` 时 `playId == null` → `sources` 空 → 卡片 `signalCount=0`（界面按 `>0` 隐藏徽标，`MatchListScreen.kt:415`）→ 该场次**既不报错也没有线路**，进播放页只有空态。
**价值·理由**：低（提到 `companion object` + `\d` 放宽为 `\d+`，顺手补一条"line10 也能解析"的用例；成本 S）。
**验证**：`grep -o 'getPlayUrl("line[0-9]*"'` 源站索引。

### D14 `GameScore.startAtMillis` 是死字段，还每场分配一个 `Calendar`
**位置** `data/remote/EspnScoreboard.kt:49`、`:84`、`:110-113` ｜ **价值/成本** 低/S ｜ **判定** 已证实
**原文**
```kotlin
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
```
**结论/影响**：`GameScore.startAtMillis` 全仓**无消费点**（`TeamNameBridge` 只用 `state/period/clock/homeScore/awayScore`，VM 只把它塞进 map）：10 场比分板 → 10 次 `TimeZone.getTimeZone` + `Calendar` 分配，收益为零。更值得警惕的是它给读代码的人一个"我们有时刻可比对"的错觉 —— 将来真要做"比分与场次对齐"，缺的恰恰是用 `startAtMillis` 做**时间窗校验**（现在只有队名匹配）。
**价值·理由**：低（删字段与解析，或反过来真的用它做匹配兜底；`NewFeaturesTest.kt:151-154` 有 `parseIso8601` 用例需同步）。
**验证**：`grep startAtMillis` 在生产代码里只剩定义与赋值。

### D15 两份手写 JSON 取值器，`jsonString` 缺 key 边界校验
**位置** `ui/MainViewModel.kt:565-571`（对照 `remote/EspnScoreboard.kt:227-243`）｜ **价值/成本** 低/S ｜ **判定** 已证实（重复与缺校验）/误取推测
**原文**
```kotlin
    private fun jsonString(json: String, key: String): String? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(needle, from)
            if (at < 0) return null
            var i = at + needle.length
```
**结论/影响**：`JsonCursor.keyIndex` 的注释写明为什么必须要求前一个非空白字符是 `{`/`,`（否则 `"homeScore"` 会命中 `"awayHomeScore"` 后缀）；`jsonString` 没有这道防线，取 GitHub release 字段时"命中更长的 key，或在 release 说明正文里命中同名字符串"会静默取到错值。当前调用顺序（tag→html_url→body）恰好避开正文，属"靠字段顺序活着"。
**价值·理由**：低（把 `jsonString` 换成 `EspnScoreboard.JsonCursor`（提到 `internal` 即可复用）或至少补边界校验，顺带消掉 30 行重复转义；成本 S）。
**验证**：构造 `{"other_body":"x","body":"y"}` 类样本断言取到 `y` —— 当前取决于顺序。

### D16 `SourceOutcome.enabled` 是无消费点的死属性
**位置** `data/repository/MatchRepository.kt:540-541` ｜ **价值/成本** 低/S ｜ **判定** 已证实
**原文**
```kotlin
        /** 这个源是否产生了场次（[Disabled] 与失败都是 false）。 */
        val enabled: Boolean get() = this !is Disabled
```
**结论/影响**：`grep` 显示 `SourceOutcome` 的生产消费点只有 `failed`（`:647`），`enabled` 无人使用；语义还有歧义（"开着的源"与"产生了场次"被写在同一句里），后续有人按名字用它做开关判断会拿到"Disabled 之外的失败也算 enabled=true"的结果。
**价值·理由**：低（删掉，或改名 `producedMatches` 并写明只描述结果；一行改动）。
**验证**：`grep '\.enabled'` 在 data/ 下零命中（`PrefsStore.enabled` 是另一回事）。

### D17 版本号里非数字后缀的语义是错的
**位置** `ui/UpdateCheckPolicy.kt:56-66`、`:123-128` ｜ **价值·成本** 低/S ｜ **判定** 已证实
**原文**
```kotlin
    private fun parse(version: String?): List<Int>? {
        val text = version?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = SEGMENT.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        return parts.ifEmpty { null }
    }
```
**结论/影响**：只取数字段，于是 `1.2.24-hotfix.1` 被判成比 `1.2.24` 新（多出第 4 段 1 → 提示升级到同名版本，用户点了没变化），而 `1.2.24-hotfix` 被判成**相等**（有新版却不提示）。走 `releases/latest`（GitHub 已排除 prerelease，`MainViewModel.kt:542-547`）时 "-beta" 不构成真实风险，真正的缝是"正式 tag 带后缀"，两种都误判。判据是纯函数，改起来最便宜。
**价值·理由**：低（规则明确化：`-`/`+` 之后一律算"低于同数字版本"，并补两条边界用例；成本 S。本仓 tag 形如 `v1.2.24`，故定低）。
**验证**：`isRemoteNewer("1.2.24-hotfix", "1.2.24")` 与 `isRemoteNewer("1.2.24-rc.1", "1.2.24")` 两条断言。

### D18 详情页缓存的命中/失效在日志里完全不可观测
**位置** `data/repository/MatchRepository.kt:263-267`（另 `:954-956`、`:968-976`）｜ **价值·成本** 低/S ｜ **判定** 已证实
**原文**
```kotlin
        if (!forceRefresh) {
            synchronized(detailCache) {
                val hit = detailCache[detailId]
                if (hit != null) {
                    if (now - hit.first < DETAIL_CACHE_TTL_MS) return hit.second
```
**结论/影响**：命中缓存、TTL 过期、被"刷新线路"清掉三条路径都不打任何日志（连 `debugLog` 都没有），现场只能靠源站请求数反推。这条缓存是"对源站减负最有效的一次优化"（`:223-235` 自述），也是"点了刷新线路却拿到旧正文"这类投诉的唯一嫌疑点；没有命中/失效日志时只能猜 5 分钟 TTL。加一行 `debugLog("MatchRepo", "detail cache hit: $detailId")`（只打 detailId，天然无签名）即可让这条链路可观测。
**价值·理由**：低（3 行 debug 日志 + 一条"刷新线路必须走到 fetch"的用例；成本 S。`:340/771/1076` 的 `Log.w` 只在失败分支）。
**验证**：当前命中/失效路径零日志调用。

---

## 我读到但认为不值得改的

- **`debugLog` 打出完整签名地址**（`MatchRepository.kt:1137` 的 `m3u8 OK: $candidate`、`StreamUrlResolver.kt:64/68`）：只存在于 `BuildConfig.DEBUG`，`Logs.kt:6-14` 已把泄露口径限定在 release；这正是现场排查解析链路最需要的东西，改成脱敏会让"地址到底长什么样"消失。紧邻的 `Log.w` 已全部走 `redactUrl`（台账 02-P2-9 / 04-P1-1），边界清楚。
- **`ChannelAdmission.EXCLUDED_KEYWORDS` 只有 4 个词**（"主播/解说/美女/聊天"）：2026-10-29 用户给的产品规则，不是技术债；扩词表要用户定（技术防线已在 D4 里只提"取不到名字必须视为敏感"）。
- **JRS 详情页只解析出 1 条信号**：实测 `.forensics/detail_364127.html:50-51` 的 `信号1`(`data-url`) 与 `信号2`(`href`) 是**同一个地址**，`distinctBy` 去掉的是源站的重复项，不是我们丢线路。
- **`ScoreboardRepository` 的 `cached/cachedAt` 没有 `@Volatile`**：当前唯一入口是 VM 主线程的 `refreshScores`（还带 `_scoresJobActive` 挡重叠），没有实测到的并发路径。
- **`MatchStatus.fromSource` 的 `contains` 顺序判定**（`Match.kt:133-146`）：已被真实文案覆盖（`.forensics/jrs_list*.html` 里的 `直播中`/`00:30`），结构化重写收益不明。
- **`stableFallbackId` 用 `hashCode()`**（`JrkanParser.kt:192-195`）：碰撞只让 `usedIds` 加 `#n` 后缀（`MatchRepository.kt:813-817`），不会丢比赛；换 SHA-256 是给热路径加成本。
- **`SniffedPlaylistCache` 用墙钟**（`:29-32`）：`expire` 是绝对时间戳，改单调钟会把 2026 年当成"几亿毫秒后"；KDoc 已写死这条取舍。
- **`HttpClient.USER_AGENT` 写死 Chrome/120**（`:129-131`）：源站就是按这个 UA 放行的，动它是风险不是收益。
- **`JrkanParser.PLAY_HOST` 与 `SourceEndpoints.PLAY_HOSTS[0]`**：已是"同源引用"（台账 02-P1-9 的落地方式），不是两份重复常量。

## 需要运行/真机才能定的

1. **D4 触发条件**：需一份**真实存活**的 `steam<id>.html`（本次探测的 10 月样本 playId 已 404），确认 `data-play` 引号形态与线路名标签内是否只有纯文本；此前"单引号/`span` 替 `strong`"的结论只能从列表页推。
2. **D5 收益量级**：真机抓包计时，JRS 列表 11 KB + JRKAN 索引 271,323 B 的下载占冷启动/下拉刷新比例，以定 TTL（30s 还是 5 分钟、是否只对非用户主动刷新生效）。
3. **D9 取值**：真实 403/429 的频率与窗口长度，才能给冷却时长定值（连"是否真会被连续顶撞"都未证实）。
4. **D3 链末端**：`www.jrszb444.com` / `www.jrszb222.com` / `play.sportsteam368.com` 的当前存活与延迟，决定"单次 404 → 主域名降级 → 整表为空"是否可达（`.forensics/jrs_list.html` 里页面自身引用 444 的静态资源，倾向"444 活着"，但那是历史样本）。
5. **D7**：ESPN 连续失败时真机是否出现"冻结比分"（断网/限速实验，观察 45s 刷新期间的行为）。
6. **D6②**：源站 NBA 场次实际队名写法（"小牛"还是"独行侠"；是否用"雷霆/猛龙/奇才"），决定 30 队映射是否改成"别名集合"。
7. **`MatchStatusClock` 的 3.5 小时窗口**：CBA 双加时/长暂停的实际结束时刻分布，决定是否按联赛区分"直播中 → 已结束"窗口（现在全联赛一刀切）。
8. **`EspnScoreboard.parseIso8601` 的非 `Z` 形态**：ESPN 若给 `2026-10-31T02:00-05:00`，当前实现会把偏移当 UTC（只做 `substring`），差 5 小时；需抓一次真实响应确认它恒为 `Z`。