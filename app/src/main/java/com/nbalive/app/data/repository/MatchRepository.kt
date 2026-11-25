package com.nbalive.app.data.repository

import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.data.remote.debugLog
import com.nbalive.app.data.remote.redactUrl
import com.nbalive.app.data.remote.safeLogWarn
import com.nbalive.app.data.remote.JrsParser
import com.nbalive.app.data.remote.JrkanParser
import com.nbalive.app.data.remote.Xxzb5Parser
import com.nbalive.app.data.remote.LineEquivalence
import com.nbalive.app.data.remote.StreamUrlResolver
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * 赛事仓库：多源聚合 + 去重 + 故障切换。
 *
 * 主源 JRS（jrszbt.com），备源 JRKAN（m.jrkan.com），
 * 第三源 xxzb5（xxzb5.com → xixizb.com，2026-11-25 替换 88看球）。
 * 同一场比赛在各源都可能出现，按"队名对 + 日期"归一化后合并；
 * **线路**按 [com.nbalive.app.data.remote.LineEquivalence] 的等价键去重 ——
 * xxzb5 的裸 m3u8 与 JRS/JRKAN 的云直播线路（356/368 互为镜像）里，
 * 同一份清单会以不同拼法出现，只按整串 URL 判重会让同一路以两三个名字进换线列表。
 */
class MatchRepository(
    private val http: HttpClient = HttpClient.shared,
    private val jrsParser: JrsParser = JrsParser(),
    private val jrkanParser: JrkanParser = JrkanParser(),
    private val xxzb5Parser: Xxzb5Parser = Xxzb5Parser(),
    private val resolver: StreamUrlResolver = StreamUrlResolver(),
    /**
     * **每个源**各自的 deadline（review/02-data.md P2-3，修正见
     * review/optimization-review.md H3 / opt/data.md D1）。
     *
     * ⚠️ 语义已从"整体 deadline"下移到"每源各一份"：
     * 旧实现用 `withTimeoutOrNull` 包住 **`jrsDeferred.await() to jrkanDeferred.await()`**，
     * 超时即 `matches = emptyList()` —— 一个源慢/死会把**另一个源已经拿到**的场次
     * 一起丢掉，还被报成"两个源都超时"。而 60 秒这个值的动因（域名死时
     * 首候选要跑满 3 次重试 = 105 秒）恰好就是它丢数据的场景：
     * 界面得到"JRS、JRKAN 源暂时不可用（网络超时）"+ 空列表，
     * 尽管 JRKAN 2 秒就返回了十几场。
     *
     * 单源 3 次尝试 × `callTimeout = 35s`，两源并发约 105s；
     * 之后状态校正最多 16 场 / 4 并发 = 4 批 × 35s = 140s。
     * 最坏情况用户盯着转圈接近 **3 分钟**，期间没有任何出口。
     *
     * 60 秒是"用户还愿意等"与"覆盖正常慢网"的折中：
     * 正常两源并发首屏在 1~3 秒，慢网也在 10 秒级；
     * 超过 60 秒基本可以确定是源站/网络挂了，再等只是让用户干瞪眼。
     */
    private val loadDeadlineMs: Long = DEFAULT_LOAD_DEADLINE_MS,
    /**
     * 列表级短缓存的 TTL（review/optimization-review.md D5）。
     *
     * 取 **30 秒**的依据（Lead 复核口径）：
     *  - 源站列表本身约 **2 分钟**粒度更新（同一批场次两分钟内内容不变），
     *    30 秒远短于真实变化节奏，命中缓存不会让用户看到"过期场次"；
     *  - 单次全量刷新要下 JRKAN 索引 **271,323 B** + JRS 列表 11 KB ≈ 282 KB，
     *    而"切标签/进详情再回来"这类返回式刷新在 30 秒内很常见 ——
     *    命中一次就省 282 KB 与一倍源站压力（源站对频繁请求回 403/429）；
     *  - 30 秒也短到用户**主动下拉刷新**时即使忘了绕过，也最多看到 30 秒前的数据；
     *    真正的用户主动刷新仍必须走 `forceRefresh = true` 直接绕过（见
     *    [loadMatchesOutcome] 的 `forceRefresh` 形参）。
     */
    private val listCacheTtlMs: Long = DEFAULT_LIST_CACHE_TTL_MS,
    /**
     * 403/429 之后的"源冷却"时长（review/optimization-review.md D9）。
     *
     * 取 **60 秒**：源站的限流窗口通常是分钟级，60 秒足够让窗口过去，
     * 又短到用户不会觉得"这个源永久坏了"。
     * ⚠️ 与"永久拉黑"的区别见 [startCooldown] 的 KDoc。
     */
    private val sourceCooldownMs: Long = DEFAULT_SOURCE_COOLDOWN_MS,
    /**
     * 可注入的**单调**时钟（毫秒），用于缓存年龄与源冷却。
     *
     * 为什么必须能注入（D9 的判据要求）："冷却窗口内不发请求、窗口过后恢复"
     * 这条判据如果只能靠真等 60 秒，就没法在单测里钉死；
     * 注入之后测试可以把时钟推到窗口之外。
     *
     * ⚠️ 默认是 `nanoTime` 而不是墙钟：用户把系统时间往前拨时，
     * `now - cachedAt` 会变成负数、缓存永不过期（详情页缓存原本就吃这个亏）。
     */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /**
     * runCatching 会把 [CancellationException] 也吞掉，
     * 协程被取消后还会继续跑完剩下的逻辑。
     * 这里显式区分：取消照常抛出，其余异常才包成 Result。
     */
    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /**
     * 上一轮失败过的源站 host（小写）。
     *
     * 跨调用记住它们，下一轮直接从镜像域名先试，省掉一轮超时；
     * 成功即清除（恢复），**不是永久拉黑**（见 [runSource] 注释）。
     *
     * 同步容器：两个源的抓取在不同协程里并发。
     */
    private val listFailedHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * **源的限流冷却**（review/optimization-review.md D9）：host（小写）→ (冷却截止时刻, 状态码)。
     *
     * ## 与 [listFailedHosts]「不永久拉黑」的区别（这一条最容易被误读）
     *
     * `listFailedHosts` 只影响**候选顺序**（把失败 host 挪到队尾，全部失效时从头再来），
     * 它从不阻止请求；本表则是在 TTL 内**真的不发请求**。
     * 但两者都不是永久拉黑：冷却窗口（默认 60 秒）一过，条目自然失效、请求照发。
     * 永久黑名单会把"暂时故障"变成"长期故障"，那是 review/01-P1-5
     * 「15 秒看门狗永久拉黑健康线路」的同款错误 —— 这里绝不做。
     *
     * 旧实现只在**单次调用内**不重试（403/429 立即停），跨调用不设防：
     * 限流窗口内用户每次下拉刷新、切标签、进详情都会再撞一次，
     * 而用户遇到 403 的直觉动作恰好是"一直刷新"，正是最坏用法。
     *
     * 时刻用构造参数 [clock]（默认单调钟）：可注入，单测才能把时钟推到窗口外验证恢复。
     */
    private val sourceCooldowns = mutableMapOf<String, Pair<Long, Int>>()

    /**
     * 列表级**短 TTL 缓存**：key = "开着的源组合" → (缓存时刻, 结果)。
     *
     * review/optimization-review.md D5：列表层原先一次缓存都没有，
     * 每次刷新重下 JRKAN 索引 271,323 B + JRS 列表 11 KB ≈ 282 KB，
     * 而详情页有 5 分钟缓存、比分有 20 秒 TTL。
     *
     * ⚠️ 同步容器：两个源的抓取在不同协程里并发（`loadMatchesWithOutcome` 也是并发入口）。
     * ⚠️ 有**条数上限**：key 只有"源组合"几种，但上限存在是为了防御将来 key 变形。
     */
    private val listCache = object : LinkedHashMap<String, Pair<Long, LoadOutcome>>(
        /* initialCapacity = */ 4,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, LoadOutcome>>?,
        ): Boolean = size > LIST_CACHE_MAX_ENTRIES
    }

    /**
     * 正在飞的列表加载：key → Deferred（D5 的 single-flight 那一半）。
     *
     * ⚠️ 缓存只挡"已完成的请求"，挡不住"正在飞的"：冷启动 + 用户立刻下拉刷新、
     * 或切标签再切回来，都会让同一份列表被请求两次。
     * ⚠️ 与 [JrsDetailCache] 里同款纪律（D12）：`forceRefresh` 路径**不许**删别人的条目。
     */
    private val inFlightLists = mutableMapOf<String, CompletableDeferred<LoadOutcome>>()

    /**
     * 列表级状态校正：源站列表对已结束的场次仍标"直播中"（实测滞后 3 小时+）。
     *
     * - JRS 场次：列表行没有真实开赛时间（"直播中"顶掉了时间），
     *   并发拉一次详情页（约 5KB/次）用计划开赛时间校正并回填。
     * - 无详情页可查的场次（JRKAN 等）：列表自带 "MM-dd HH:mm"，startAtMillis 可信，
     *   本地时钟双向校正——超时判已结束，未到点判未开赛（源站会提前把未开赛标成"直播中"）。
     */
    internal suspend fun correctLiveStatuses(matches: List<Match>): List<Match> =
        correctLiveStatuses(matches) { url, referer -> http.fetch(url, referer) }

    /**
     * 带 fetch 注入口的实现：让单元测试能整条链路不触网。
     */
    internal suspend fun correctLiveStatuses(
        matches: List<Match>,
        fetch: suspend (String, String?) -> FetchResult,
    ): List<Match> = correctLiveStatuses(
        matches = matches,
        fetch = fetch,
        now = java.time.ZonedDateTime.now(),
    )

    /**
     * 可注入"当前时间"的实现：让单元测试把跨零点的分支固定下来。
     *
     * 详情页拉不到时间时的兜底校正（[isPastDayEnd]）完全依赖时钟，
     * 不注入就只能在"日期真的过完"之后才跑，测试会随一天中的时刻
     * 随机通过/失败。
     */
    internal suspend fun correctLiveStatuses(
        matches: List<Match>,
        fetch: suspend (String, String?) -> FetchResult,
        now: java.time.ZonedDateTime,
    ): List<Match> = supervisorScope {
        val live = matches.filter { it.status == MatchStatus.LIVE }
        if (live.isEmpty()) return@supervisorScope matches

        // JRS：详情页校正。并发数要封顶——直播中的场次可能十几场，
        // 一次全开就是把源站当 DDoS 源打；同时单个详情失败不能连累其它场次。
        val gate = Semaphore(MAX_DETAIL_CONCURRENCY)
        // ⚠️ 并发封顶不够，还要封**总量**：
        // 并发 4 意味着"同时 4 个"，但队列里排着 15 个时总量还是 15 次请求，
        // 只是分批打出去 —— 对源站的压力没变，还多撑了几秒。
        // 超出预算的场次保留源站状态（多半是"直播中"），不做时钟校正：
        // JRS 的"直播中"行没有真实开赛时间（startAtMillis 是分组日期的 00:00 占位），
        // 套时钟校正会把正在直播的比赛全判成"已结束"，比标错严重得多。
        val toFix = selectDetailBudget(live, MAX_DETAIL_FETCH)
        val skipped = live.count { it.jrsDetailId != null } - toFix.size
        if (skipped > 0) {
            Log.w("MatchRepo", "详情页校正超预算：$skipped 场跳过（本次最多 $MAX_DETAIL_FETCH 场）")
        }
        val fixes = toFix.map { m ->
            async {
                gate.withPermit {
                    catching {
                        val html = detailCache.load(m.jrsDetailId!!, fetch = fetch)
                        val c = html?.let { jrsParser.correctedStatus(m, jrsParser.parseScheduledTime(it)) }
                        m.id to c
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().toMap()

        matches.map { m ->
            if (m.status != MatchStatus.LIVE) return@map m
            val c = fixes[m.id]
            when {
                c != null ->
                    m.copy(status = c.status, startAtMillis = c.startAtMillis)
                // 没有详情页可查且计划开赛时间可信（JRKAN 的 "MM-dd HH:mm"）：
                // 本地时钟双向校正 —— 源站会把未开赛的场次提前标成"直播中"
                // （只判"超时->已结束"会让未来场次一直亮红到开赛后 3.5 小时）
                m.jrsDetailId == null && m.startAtMillis != null -> m.copy(
                    // ⚠️ 走 MatchStatusClock 而不是本类的私有 statusByClock：
                    // 旧实现内部自己取 ZonedDateTime.now()，把调用方注入的
                    // now 完全无视掉 —— correctLiveStatuses(now=...) 的
                    // "可注入时钟"对 JRKAN 分支是假的，同一份测试上午跑和
                    // 晚上跑结果不同（P1-4）。
                    // 另外它用 ZoneId.systemDefault() 解释源站的北京时间，
                    // 设备时区设成 UTC-5 时状态整体偏移 13 小时（P2-1）。
                    status = MatchStatusClock.byClock(m.startAtMillis, now),
                    startAtMillis = m.startAtMillis,
                )
                // JRS 场次但详情页没给出可用的 HH:mm（源站 404 / 超时 / 改版）：
                // ⚠️ 这里原来直接 `else -> m`，把场次**原样留在直播中**。
                // 实测回归（v1.2.8，用户截图 2026-10-24 23:01）：
                // 19:35 开球的三场 CBA 早已结束，列表里仍标着「直播中」，
                // 点进去是「网络错误」—— 源站的"直播中"是会滞后好几个小时的，
                // 拿不到详情页时间不等于该继续信它。
                //
                // 但 JRS"直播中"行的 startAtMillis 只是分组日期的 **00:00 占位**
                // （真实 HH:mm 被"直播中"文案顶掉了），直接套时钟校正
                // 会把凌晨场次判成"结束 24 小时前"，正在直播的也会被误杀。
                // 所以只做**保守的下界判断**：分组日期 + 1 天 = 该场最晚可能的结束时刻。
                // 连这个都过了，比赛绝无可能还在进行 —— 判已结束。
                else -> {
                    val floor = m.startAtMillis
                    if (floor != null && isPastDayEnd(floor, now)) {
                        m.copy(status = MatchStatus.FINISHED)
                    } else {
                        m
                    }
                }
            }
        }
    }

    /**
     * 该时间戳所在的日期是否**整体已经过去**。
     *
     * 用于 JRS"直播中"行的兜底校正：这类行的 startAtMillis 只是分组日期的
     * 00:00 占位，拿不到详情页里的真实 HH:mm 时，只能用"这一天结束了"
     * 作为下界判断 —— 一天都过完了，当天任何时刻开球的比赛都不可能还在打。
     *
     * ⚠️ 这里刻意用「当天 24:00」而不是「+3.5 小时」：
     * 00:00 是占位值不是真实开球时刻，往上加 3.5 小时只会得到 03:30，
     * 把当天晚上才开球的比赛在中午就判成已结束。按整天判断虽然粗，
     * 但方向是安全的（宁可多标一会儿直播中，也不误杀正在打的比赛）。
     */
    private fun isPastDayEnd(startAtMillis: Long, now: java.time.ZonedDateTime): Boolean =
        // 委托给 MatchStatusClock：那边固定用北京时间（SOURCE_ZONE）解释时间戳，
        // 本类旧实现用 ZoneId.systemDefault()，设备时区一偏判定就整体错位（P2-1）。
        MatchStatusClock.isPastDayEnd(startAtMillis, now)

    /**
     * 测试专用入口：固定 HTML 与当前时间，跑一遍 [correctLiveStatuses]。
     *
     * 详情页的 TTL 缓存是进程级的，直接复用会让测试之间互相污染
     * （同一 URL 的第二次调用拿到缓存里的旧正文）。
     * 这里走一个全新的仓库实例，缓存天然是空的。
     */
    internal suspend fun correctLiveStatusesForTest(
        matches: List<Match>,
        htmlFor: (String) -> String?,
        now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(),
    ): List<Match> = correctLiveStatuses(
        matches = matches,
        fetch = { url, _ -> FetchResult.Ok(htmlFor(url) ?: "") },
        now = now,
    )

    /**
     * 真正去源站取详情页正文的部分（候选域名顺序 + 403/429 冷却 + 404 不登记 host 失败）。
     *
     * ⚠️ 它现在是 [JrsDetailCache] 注入的"取正文"实现（`fetchBody` 形参）：
     * 缓存 / single-flight / TTL 起点那三条判据在 [JrsDetailCache] 里，
     * 域名候选与限流协同在这里 —— 拆开之后两者各有一条**能单独变红**的判据（D10）。
     *
     * ## 域名候选（review/02-data.md P1-9 / P2-6）
     *
     * 详情页挂在**同一个站点的不同域名**下，所以走 [SourceEndpoints.jrsDetailUrls]
     * 的候选列表：主域名不可达时改试镜像。
     * 旧实现把 `https://jrszbt.com/live/$id.html` 写死在这里**和**
     * `invalidate`（缓存失效）两处 —— 主域名一死，列表靠镜像活了下来，
     * 但每一场的详情页仍去敲死域名，状态校正与线路展开全部静默失效；
     * 而且两处副本改一处漏一处时，"刷新线路"会清 A 的缓存、读 B 的缓存，
     * 按钮变摆设且不报错。
     */
    private suspend fun fetchDetailBody(
        detailId: String,
        fetch: suspend (String, String?) -> FetchResult,
    ): String? {
        val candidates = SourceEndpoints.attemptOrder(
            SourceEndpoints.jrsDetailUrls(detailId),
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
        // D9：冷却窗口内一个请求都不发（与列表路径同一条纪律）
        coolingCodeFor(candidates)?.let { code ->
            debugLog("MatchRepo", "详情页处于限流冷却中(HTTP $code)，本轮不发请求: $detailId")
            return null
        }
        var body: String? = null
        for (url in candidates) {
            when (val r = fetch(url, http.refererOf(url))) {
                is FetchResult.Ok -> {
                    synchronized(listFailedHosts) { listFailedHosts -= hostOf(url) }
                    body = r.body
                }
                is FetchResult.Rejected -> {
                    // ⚠️ 403/429 是限流信号：**立即停整个源，不试镜像**。
                    // 镜像与主域名同属一个站点家族，大概率共享限流窗口，
                    // 继续打只会把窗口越拖越长（review 的"限流放大器"）。
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    // D9：记一次冷却，让后续调用也不再撞限流窗口
                    startCooldown(url, r.code)
                    Log.w("MatchRepo", "详情页被拒绝(HTTP ${r.code})，放弃重试: ${redactUrl(url)}")
                    return null
                }
                is FetchResult.NotFound -> {
                    // ⚠️ D3：404/410 **不重试、不登记 host 失败**。
                    // 已结束场次的详情页就是 404（实测）：登记了会让主域名被降到候选队尾，
                    // 之后整个会话的列表/详情/播放都先敲镜像。
                    // 换下一个候选（镜像上可能还留着这份页面）。
                    debugLog("MatchRepo", "详情页不存在(HTTP ${r.code})，不重试: $detailId")
                }
                is FetchResult.Unreachable -> {
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                }
            }
            if (body != null) break
        }
        return body
    }

    companion object {
        /**
         * 列表加载的**每源** deadline（review/02-data.md P2-3；D1 修正为按源）。
         *
         * 60 秒：正常两源并发首屏 1~3 秒，慢网也在 10 秒级；
         * 超过 60 秒基本可以确定源站/网络挂了，再等只是让用户干瞪眼。
         * ⚠️ 它是"每个源各自跑满 60 秒"，不是"两源合计 60 秒"——见构造参数 KDoc。
         */
        private const val DEFAULT_LOAD_DEADLINE_MS = 60_000L

        /**
         * 列表级短缓存 TTL：30 秒（review/optimization-review.md D5）。
         *
         * 依据写在构造参数的 KDoc 里（源站约 2 分钟粒度 + 单次 282 KB + 下拉必须绕过）。
         */
        private const val DEFAULT_LIST_CACHE_TTL_MS = 30_000L

        /** 列表级缓存的条数上限：只有"开着的源组合"几种 key，留几个就够。 */
        private const val LIST_CACHE_MAX_ENTRIES = 4

        /**
         * 403/429 之后的源冷却时长：60 秒（review/optimization-review.md D9）。
         *
         * 与"永久拉黑"的区别见 [startCooldown] 的 KDoc。
         */
        private const val DEFAULT_SOURCE_COOLDOWN_MS = 60_000L

        // ⚠️ 端点不再写死在本类：单值常量扛不住源站换域名（review/02-data.md P1-9）。
        // JRS/JRKAN/播放域名全部收进 SourceEndpoints，一个失效按顺序试下一个。

        /**
         * 进程级共享实例。
         *
         * ⚠️ 必须共享：详情页 TTL 缓存是这个类的实例字段，
         * 每次 new 一个就等于每次从零开始缓存 —— 用户在页面之间往返、
         * ViewModel 重建时缓存就丢了，同一个详情页会重复拉源站。
         */
        val shared: MatchRepository by lazy { MatchRepository() }

        /** 状态校正时并发拉详情页的上限：防止一次刷新把源站打爆。 */
        private const val MAX_DETAIL_CONCURRENCY = 4

        /** 录像详情并发上限（每场一次 `/replays?matchId=`）：同样防止打爆源站。 */
        private const val MAX_REPLAY_CONCURRENCY = 4

        /**
         * 状态校正时**总**拉详情页的上限。
         *
         * 并发封顶只限"同时几个"，不限"一共几个"：
         * 直播中的场次有十几场时，即使并发 4，总量仍是十几次请求，
         * 只是分批打出去 —— 对源站的压力没变，还把界面多撑了几秒。
         *
         * ⚠️ 从 8 提到 16（review/02-data.md P1-4）：
         * CBA 一个比赛日 19:35 同时开球 6~8 场，JRS 侧标 `LIVE` 的很容易超过 8，
         * 第 9 场起就只能等"整日过完"的兜底 —— 19:35 开球、22:00 早就结束的比赛
         * 到午夜前都还亮红「直播中」（v1.2.8 用户截图那个 bug 的未覆盖余量）。
         * 详情页约 5KB，在 4 并发门限下把预算提到 16 对源站压力差异可接受，
         * 却能让"一个比赛日"基本总是落在预算内。
         */
        private const val MAX_DETAIL_FETCH = 16

        /**
         * m3u8 候选校验预算：最多试几个候选。
         *
         * 每个候选 = 1 次 HTTP 请求，且坏域名会吃满 fastFail 的超时（8s 连接 + 12s 读）。
         * 反混淆版 + 原文版两个足够覆盖真实情况，
         * 全量试完只是让用户多等几个超时。
         */
        private const val MAX_M3U8_CANDIDATES = 2

        /** 列表页单源拉取的最大尝试次数（含首次）。 */
        private const val LIST_FETCH_ATTEMPTS = 3

        /** xxzb5 线路接口地址里的比赛 id（用于回推候选 URL）。 */
        private val XXZB5_DETAIL_ID = Xxzb5Parser.DETAIL_ID

        /** 重试退避基数：第 n 次失败后等 n * LIST_RETRY_BACKOFF_MS。 */
        private const val LIST_RETRY_BACKOFF_MS = 400L

        // 详情页缓存的 TTL 与条数上限已随缓存搬到 [JrsDetailCache]（D10）：
        // 那两个常量只服务缓存本身，留在这里会让"缓存在本类"的错觉重现。

        /**
         * 队名归一化与合并键的正则已随合并逻辑搬到 [MatchMerger]（D10）：
         * 它们只服务 normalizeKey / fallbackDayKey，留在这里会让"两份实现"的假象重现。
         */
    }

    /**
     * 详情页正文的**短缓存 + single-flight**（D10 拆出的 [JrsDetailCache]）。
     *
     * ⚠️ 职责边界：TTL 缓存、single-flight（含 D12 那条"forceRefresh 不许删别人的在飞条目"）、
     * `invalidate`/`clear` 都在 [JrsDetailCache] 里；候选域名顺序与 403/429 冷却、404
     * 不登记 host 失败仍由本类的 [fetchDetailBody] 负责（以 lambda 注入）。
     * 前者是**纯内存判据**、后者是**网络与源站协同判据** —— 分开才能各自被单测钉住。
     *
     * ⚠️ 时钟走构造参数 [clock]（单调毫秒）：TTL 语义必须可注入，
     * 否则"慢请求的缓存起点"只能靠真等几分钟才看得出来。
     */
    private val detailCache = JrsDetailCache(
        clock = clock,
        fetchBody = { detailId, fetch -> fetchDetailBody(detailId, fetch) },
    )

    /** 直播中优先，其余按开赛时间。 */
    private fun sort(matches: List<Match>): List<Match> =        matches.sortedWith(
            compareBy<Match> { if (it.status == MatchStatus.LIVE) 0 else 1 }
                // 直播中组内按开赛时间**倒序**：最晚开赛（刚开始不久、最新鲜）的排最上；
                // 其余组内保持升序：最近要开的在前。组内方向不同，不能直接 thenBy。
                .then { a, b ->
                    val ta = a.startAtMillis ?: Long.MAX_VALUE
                    val tb = b.startAtMillis ?: Long.MAX_VALUE
                    if (a.status == MatchStatus.LIVE) tb.compareTo(ta) else ta.compareTo(tb)
                }
                .thenBy { it.leagueRawName }
        )

    /**
     * 让列表状态随时间老化并重新排序（P1-1）。
     *
     * 状态原来是一次性快照：只在解析列表和 loadMatches 结束时各算一次，
     * 之后没有任何机制让它老化。用户早上打开 App、放一下午再切回来，
     * 列表不会重新拉取（这是刻意的取舍，自动轮询会打爆源站），
     * 于是 13:30 就该结束的比赛到晚上仍显示「直播中」。
     *
     * 修法不是加网络轮询，而是**纯内存重算**：
     *  - 零请求、零 IO，一百多场也只是微秒级
     *  - 由 ViewModel 在列表可见时每分钟调一次，切后台自动停
     *
     * 重排是必要的：一场比赛从「直播中」退到「已结束」后，
     * 它还占着列表顶部的黄金位置，新开赛的场次被压在下面。
     *
     * @return 状态或顺序有变化时返回新列表，否则返回**原列表引用**
     *   （StateFlow 靠 equals 去重，返回新实例会白白触发全列表重组）
     */
    fun agedMatches(matches: List<Match>, now: java.time.ZonedDateTime): List<Match> {
        if (matches.isEmpty()) return matches
        val aged = MatchStatusClock.aged(matches, now)
        return if (aged === matches) matches else sort(aged)
    }

    /**
     * 单个源的抓取结果，**带失败原因**。
     *
     * 原来只有 `Result<List<Match>>` 一种形态：
     * 网络不通、被限流、HTTP 200 但解析出零场，三种情况都塌缩成
     * "空列表"或"一个异常"，调用方无法向用户解释"为什么场次变少了"。
     * 这里把三者分开，[loadMatchesWithOutcome] 才能报出 failedProviders。
     */
    internal sealed class SourceOutcome {
        /** 正常拿到场次 */
        data class Ok(val matches: List<Match>) : SourceOutcome()

        /** 源站明确拒绝（403/429）。不重试，但必须让用户知道这个源没了。 */
        data class Rejected(val code: Int) : SourceOutcome()

        /**
         * 资源不存在（404/410，review/optimization-review.md D3）。
         *
         * ⚠️ 与 [Unreachable] 严格区分：它**不重试、不登记 host 失败**。
         * 源站对**已结束场次**的播放页就返回 404（实测），把它当成"域名挂了"
         * 会让主域名被挪到候选队尾，之后整个会话都先敲镜像。
         */
        data class NotFound(val code: Int) : SourceOutcome()

        /**
         * 该源在 deadline 内没有返回（review/optimization-review.md H3 / opt/data.md D1）。
         *
         * ⚠️ 它是**按源**判定的：一个源超时只影响它自己，
         * 另一个源已经拿到的场次必须照常返回。
         */
        data class TimedOut(val deadlineMs: Long) : SourceOutcome()

        /** 网络不通（DNS/超时/连接失败），重试后仍失败 */
        data class Unreachable(val reason: String?) : SourceOutcome()

        /**
         * HTTP 200 但解析出零场。
         *
         * ⚠️ 这**也算失败**：源站改版让解析器整表归零时，HTTP 层是完全正常的。
         * 若按"HTTP 成功"处理就成了"这个源今天没比赛"，又是一条静默丢场次的路。
         */
        data object ParsedEmpty : SourceOutcome()

        /**
         * 用户把这个源关了（「我的」页开关，2026-10-31）。
         *
         * ⚠️ 与失败严格区分：它不是故障，**不许**出现在 `failedProviders` 里，
         * 否则用户会看到"JRS 源暂时不可用"——而那是他自己关的。
         */
        data object Disabled : SourceOutcome()

        /** 只有"真的出错"才算失败；[Disabled] 不算。 */
        val failed: Boolean get() = this !is Ok && this !is Disabled
    }

    /**
     * 一次列表加载的完整结果：场次 + 哪些源失败了 + 被过滤掉几场。
     *
     * 为什么要新增这个类型（review/02-data.md P1-2）：
     * 原 `loadMatches` 返回 `Result<List<Match>>`，部分失败被完全吞掉 ——
     * 任一源挂掉只写一条 `Log.w` 然后当成"这个源今天没比赛"，
     * 只有两个源同时为空才报错。用户看到的就是"场次莫名变少"，
     * 且无法区分"源站真没比赛"和"某个源挂了"。
     *
     * @param filteredOutCount 因非 NBA/CBA 被丢弃的场次数。
     *   联赛名解析失败会让场次静默消失（P1-新），至少让它可被观测。
     */
    data class LoadOutcome(
        val matches: List<Match>,
        val failedProviders: List<Provider> = emptyList(),
        val filteredOutCount: Int = 0,
        private val reasons: Map<Provider, String> = emptyMap(),
    ) {
        /** 该源的失败原因（可读文案，UI 直接显示）。成功时返回 null。 */
        fun failureReason(provider: Provider): String? = reasons[provider]

        /**
         * 面向用户的"部分数据不可用"提示；两源都正常时返回 null。
         *
         * 只在**还有数据**时提示。两源全挂时该走整页错误态，
         * 而不是"仅显示部分场次"这种轻描淡写的横幅。
         */
        fun partialError(): String? {
            if (failedProviders.isEmpty() || matches.isNotEmpty().not()) return null
            val names = failedProviders.joinToString("、") { it.displayName }
            val why = failedProviders.mapNotNull { reasons[it] }.firstOrNull()
            return if (why == null) "$names 源暂时不可用，仅显示部分场次"
            else "$names 源暂时不可用（$why），仅显示部分场次"
        }

        companion object {
            /** 只有场次、没有失败的便捷构造（测试与"全部成功"分支用）。 */
            fun of(matches: List<Match>): LoadOutcome = LoadOutcome(matches = matches)
        }
    }

    /**
     * 生产入口：带失败信息的加载。
     *
     * @param enabled 用户开着的数据源（「我的」页开关）。默认全开，
     *   这样既有调用点与测试一行都不用改。
     * @param forceRefresh 用户**主动**刷新（下拉刷新 / 重试按钮）时传 `true`，
     *   直接绕过 30 秒的列表短缓存（review/optimization-review.md D5）。
     *   ⚠️ 这是**对外暴露的形参**：调用方（下拉刷新链路）必须真的传它，
     *   否则用户点了刷新却可能拿到 30 秒前的列表 —— 那正是这次修复要避免的
     *   "缓存把用户显式操作吞掉"。`false`（默认）时命中缓存的调用连一次 HTTP 都不发。
     */
    suspend fun loadMatchesOutcome(
        enabled: Set<Provider> = Provider.entries.toSet(),
        forceRefresh: Boolean = false,
    ): LoadOutcome =
        loadMatchesWithOutcome(enabled = enabled, forceRefresh = forceRefresh) { url, referer ->
            http.fetch(url, referer)
        }

    /**
     * 加载已完赛录像（2026-11-25 新增，数据源 xxzb5 → xixizb.com）。
     *
     * 先用窗口赛程挑出「篮球 + 已完赛 + 有 replayId」的场次（保留真实比分），
     * 再按 matchId 并发拉 `/api/v1/replays?matchId=` 的清晰度线路。
     *
     * ⚠️ 单场详情失败（超时/404）只让那场没有线路，**不影响其它场次** ——
     * 卡片仍保留比分，点进去提示暂无线路，而不是整页失败。
     * 并发封顶 [MAX_REPLAY_CONCURRENCY]，避免对源站一次打几十个请求。
     */
    suspend fun loadReplays(
        fetch: suspend (String, String?) -> FetchResult = { url, referer -> http.fetch(url, referer) },
    ): List<ReplayItem> = withContext(Dispatchers.IO) {
        val today = java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE)
        val origin = SourceEndpoints.XXZB5_LIST.firstOrNull() ?: Xxzb5Parser.SITE_ORIGIN
        val windowUrl = Xxzb5Parser.listUrl(
            from = today.minusDays(1).toString(),
            to = today.plusDays(1).toString(),
            origin = origin,
        )
        val windowBody = when (val r = fetch(windowUrl, null)) {
            is FetchResult.Ok -> r.body
            else -> return@withContext emptyList()
        }
        val candidates = xxzb5Parser.parseReplayCandidates(windowBody)
        if (candidates.isEmpty()) return@withContext emptyList()

        val gate = Semaphore(MAX_REPLAY_CONCURRENCY)
        val withLines = candidates.map { candidate ->
            async {
                gate.withPermit {
                    val detailUrl = Xxzb5Parser.replayListUrl(candidate.matchId, origin)
                    val lines = when (val r = fetch(detailUrl, null)) {
                        is FetchResult.Ok -> xxzb5Parser.parseReplays(r.body)
                        else -> emptyList()
                    }
                    candidate.copy(lines = lines)
                }
            }
        }.awaitAll()
        withLines
    }

    /**
     * 带失败信息的列表加载。
     *
     * 与 [loadMatchesInternal] 的区别：**不因部分失败而抛异常**，
     * 而是把失败源如实报出来。
     *
     * ## 两层"不发请求"（review/optimization-review.md D5）
     *
     *  - **短 TTL 缓存**（默认 30 秒，见构造参数 KDoc）：命中即返回，零请求；
     *  - **single-flight**：同一"开着的源组合"的并发调用合并成一次，
     *    后来者等第一个结果（旧实现只挡"已完成的请求"，挡不住"正在飞的"）。
     *
     * 用户主动刷新必须传 `forceRefresh = true`：它同时绕过上面两层。
     *
     * @param enabled 用户开着的数据源（「我的」页开关，2026-10-31 需求）。
     *   **关掉的源根本不发请求** —— 用户说的是"不解析"，不是"解析完再过滤"。
     *   空集合按全开处理（防御：宁可多解析一个源，也不要给用户一个永远空列表）。
     * @param forceRefresh 绕过缓存与 single-flight（用户显式刷新）
     */
    internal suspend fun loadMatchesWithOutcome(
        enabled: Set<Provider> = Provider.entries.toSet(),
        forceRefresh: Boolean = false,
        fetch: suspend (String, String?) -> FetchResult,
    ): LoadOutcome = withContext(Dispatchers.IO) {
        // ⚠️ 防御性归一：空集/全关不是"什么都不解析"，而是"用户配置读坏了"。
        // 静默返回空列表会让用户以为源站今天没比赛，比多打一个源糟得多。
        val active = enabled.ifEmpty { Provider.entries.toSet() }
        // 缓存身份 = "开着的源组合"（与顺序无关）：关掉一个源是另一份数据，
        // 不能命中"两源都开"时缓存的结果。
        val cacheKey = active.sortedBy { it.ordinal }.joinToString(",")

        // ---- D5① 短 TTL 缓存：TTL 内第二次调用不发任何请求 ----
        if (!forceRefresh) {
            synchronized(listCache) {
                val hit = listCache[cacheKey]
                if (hit != null && clock() - hit.first < listCacheTtlMs) {
                    debugLog("MatchRepo", "列表缓存命中($cacheKey)，本轮不发源站请求")
                    return@withContext hit.second
                }
            }
        }
        // ---- D5② single-flight：同一 key 的并发调用合并成一次 ----
        if (!forceRefresh) {
            val existing = synchronized(inFlightLists) { inFlightLists[cacheKey] }
            if (existing != null) return@withContext existing.await()
        }
        val deferred = CompletableDeferred<LoadOutcome>()
        var registered = false
        if (!forceRefresh) {
            val raced = synchronized(inFlightLists) {
                // 双重检查：拿锁前可能已有别人放进去了
                inFlightLists[cacheKey] ?: run {
                    inFlightLists[cacheKey] = deferred
                    null
                }
            }
            if (raced != null) return@withContext raced.await()
            registered = true
        }
        var produced: LoadOutcome? = null
        try {
            val result = loadMatchesUncached(active, fetch)
            produced = result
            result
        } finally {
            produced?.let { result ->
                // ⚠️ 写缓存放在 finally 里：异常路径也不留一个"永远空"的 key。
                // forceRefresh 也写（绕过的是"读"，不是"写"）—— 否则用户刷新完
                // 下一次普通调用还会拿到刷新前的旧缓存。
                synchronized(listCache) { listCache[cacheKey] = clock() to result }
            }
            if (registered) {
                // ⚠️ 只有**自己注册过**才移除（review/optimization-review.md D12 同款纪律）：
                // 无条件 remove 会删掉别人正在等待的 in-flight 条目，
                // 后来者于是重新发一次请求（偶发重复请求）。
                synchronized(inFlightLists) { inFlightLists.remove(cacheKey) }
                val result = produced
                // 等待者必须拿到**本轮**的结果，而不是缓存里可能已存在的旧值
                if (result != null) deferred.complete(result)
                else deferred.completeExceptionally(IllegalStateException("列表加载失败"))
            }
        }
    }

    /**
     * 不带缓存的实际加载：**每个源各自**带 deadline（review/optimization-review.md H3）。
     *
     * 旧实现把 `withTimeoutOrNull` 包在"两源都完成"这个**整体结果**上，
     * 超时即 `matches = emptyList()`：一个源慢/死会把另一个源已经拿到的场次
     * 一起丢掉，还被报成"两个源都超时"（JRS 首候选要跑满 3×35s=105s，必然触发）。
     * 现在两个 `async` 各自超时：超时的那个源自己变成 [SourceOutcome.TimedOut]，
     * 另一个源的场次照常合并、照常返回。
     */
    private suspend fun loadMatchesUncached(
        active: Set<Provider>,
        fetch: suspend (String, String?) -> FetchResult,
    ): LoadOutcome = supervisorScope {
        val jrsDeferred = async {
            if (Provider.JRS in active) {
                sourceWithDeadline { fetchJrsOutcome(fetch) }
            } else {
                SourceOutcome.Disabled
            }
        }
        val jrkanDeferred = async {
            if (Provider.JRKAN in active) {
                sourceWithDeadline { fetchJrkanOutcome(fetch) }
            } else {
                SourceOutcome.Disabled
            }
        }
        val xxzb5Deferred = async {
            if (Provider.XXZB5 in active) {
                sourceWithDeadline { fetchXxzb5Outcome(fetch) }
            } else {
                SourceOutcome.Disabled
            }
        }
        val jrs = jrsDeferred.await()
        val jrkan = jrkanDeferred.await()
        val xxzb5 = xxzb5Deferred.await()

        val failures = listOf(Provider.JRS to jrs, Provider.JRKAN to jrkan, Provider.XXZB5 to xxzb5)
            .filter { it.second.failed }
        val reasons = failures.associate { (provider, outcome) ->
            provider to when (outcome) {
                is SourceOutcome.Rejected -> "源站拒绝请求（HTTP ${outcome.code}）"
                is SourceOutcome.NotFound -> "源站页面不存在（HTTP ${outcome.code}）"
                is SourceOutcome.TimedOut ->
                    "网络超时（超过 ${outcome.deadlineMs / 1000} 秒未响应）"
                is SourceOutcome.Unreachable ->
                    "网络连接失败${outcome.reason?.let { "（$it）" } ?: ""}"
                SourceOutcome.ParsedEmpty -> "未解析到任何场次（源站可能已改版）"
                SourceOutcome.Disabled -> ""
                is SourceOutcome.Ok -> ""
            }
        }
        failures.filter { it.second is SourceOutcome.TimedOut }.forEach {
            // ⚠️ 只报**超时的那一个源**（review/optimization-review.md H3）：
            // 旧实现在整体超时时把两个源都报成"网络超时"，
            // 而另一个源其实早就返回了数据 —— 文案与事实相反。
            Log.w("MatchRepo", "${it.first.displayName} 列表在 ${loadDeadlineMs}ms 内未返回，按超时处理")
        }

        val merged = merge(jrs.orEmpty(), jrkan.orEmpty(), xxzb5.orEmpty())
        // 校正与排序只在有数据时有意义；空列表直接返回，省掉一整轮详情页请求
        val corrected = if (merged.matches.isEmpty()) {
            merged.matches
        } else {
            sort(correctLiveStatuses(merged.matches, fetch))
        }
        LoadOutcome(
            matches = corrected,
            failedProviders = failures.map { it.first },
            filteredOutCount = merged.filteredOutCount,
            reasons = reasons,
        )
    }

    /**
     * 单个源套上**自己的** deadline（review/optimization-review.md H3）。
     *
     * 用 `withTimeoutOrNull` 而不是 `withTimeout`：超时必须变成
     * [SourceOutcome.TimedOut] 这种**可上报的结果**，不许抛出去 ——
     * 抛出去会让 `supervisorScope` 里另一个源的结果也没人接。
     *
     * ⚠️ 取消只作用于本协程内的请求（`HttpClient.get` 已挂 `invokeOnCancellation`
     * 同步 cancel 底层 Call），不会波及另一个源。
     */
    private suspend fun sourceWithDeadline(
        body: suspend () -> SourceOutcome,
    ): SourceOutcome =
        withTimeoutOrNull(loadDeadlineMs) { body() } ?: SourceOutcome.TimedOut(loadDeadlineMs)

    /** 单源成功时给场次，失败时给空表。 */
    private fun SourceOutcome.orEmpty(): List<Match> =
        (this as? SourceOutcome.Ok)?.matches ?: emptyList()

    private suspend fun fetchJrsOutcome(
        fetch: suspend (String, String?) -> FetchResult,
    ): SourceOutcome = runSource(SourceEndpoints.JRS_LIST, fetch) { body, _ -> jrsParser.parseList(body) }

    private suspend fun fetchJrkanOutcome(
        fetch: suspend (String, String?) -> FetchResult,
    ): SourceOutcome = runSource(SourceEndpoints.JRKAN_LIST_JS, fetch) { body, _ -> jrkanParser.parseList(body) }

    /**
     * xxzb5（西西直播）窗口赛程列表。
     *
     * 接口按日期窗口取：`/api/v1/matches/window?from=...&to=...`。
     * 用北京时间当天前后各推一天（3 天窗口），覆盖跨零点场次 —— 实测窗口接口
     * 一次返回两三天共约 235 场，本地按联赛/时间排序后展示，足够首屏。
     *
     * ⚠️ 解析时把**真正取到正文的那个 host** 交给解析器，按它拼线路入口
     * （`/api/v1/matches/{id}/progress`），避免换域名只换一半（P1-9 同一条纪律）。
     */
    private suspend fun fetchXxzb5Outcome(
        fetch: suspend (String, String?) -> FetchResult,
    ): SourceOutcome {
        val today = java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE)
        val urls = SourceEndpoints.XXZB5_LIST.map { origin ->
            Xxzb5Parser.listUrl(
                from = today.minusDays(1).toString(),
                to = today.plusDays(1).toString(),
                origin = origin,
            )
        }
        return runSource(urls, fetch) { body, url ->
            xxzb5Parser.parseList(body, SourceEndpoints.originOf(url))
        }
    }

    /**
     * 抓一个源并归类到 [SourceOutcome]。
     *
     * ⚠️ 解析异常也要归为失败而不是抛出：
     * 单个源的解析器回归不能带走另一个源的好数据（这条与原有约定一致）。
     *
     * @param urls 候选端点（[SourceEndpoints]）：一个失效按顺序试下一个，
     *   全部失效才判这个源失败 —— 源站换域名是常态（review/02-data.md P1-9）。
     * @param parse (正文, 取到正文的那个 URL)。第二个参数是**必须的**而不是方便用的：
     *   解析器要靠它拼后续请求的地址，写成单参版本就会诱导调用方回到写死域名。
     */
    private suspend fun runSource(
        urls: List<String>,
        fetch: suspend (String, String?) -> FetchResult,
        parse: (String, String) -> List<Match>,
    ): SourceOutcome = catching {
        // 跨调用记住哪些 host 上一轮失败了：下一轮直接镜像域名先试，省一轮超时。
        // ⚠️ 成功必须清掉失败记录（恢复），失败只记到"下一轮"——不是永久拉黑：
        // 限流窗口（403/429）是会过去的，永久黑名单只是把暂时故障变成长期故障，
        // 那是 review/01-P1-5「15 秒看门狗永久拉黑健康线路」的同款错误。
        val candidates = SourceEndpoints.attemptOrder(
            urls,
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
        // ---- D9：403/429 之后的**源冷却** ----
        // 跨调用记住"这个 host 正在限流窗口里"，窗口内**一个请求都不发**
        // （旧实现只在单次调用内不重试，用户连点刷新就会反复撞限流）。
        // 窗口一过条目自然失效，不是永久拉黑 —— 见 [sourceCooldowns] 的 KDoc。
        coolingCodeFor(candidates)?.let { code ->
            debugLog("MatchRepo", "源处于限流冷却中(HTTP $code)，本轮不发任何请求")
            return SourceOutcome.Rejected(code)
        }
        var last: Fetched.Unreachable? = null
        var notFound: Fetched.NotFound? = null
        for ((index, url) in candidates.withIndex()) {
            // 首个候选跑满重试（瞬时抖动靠它兜），镜像只试一次：
            // 镜像存在的意义是"这个域名死了"（DNS/主机故障），
            // 那种情况下在死域名上重试三次纯属浪费几秒超时
            val attempts = if (index == 0) LIST_FETCH_ATTEMPTS else 1
            when (val fetched = fetchListDetailed(fetch, url, attempts)) {
                is Fetched.Body -> {
                    synchronized(listFailedHosts) { listFailedHosts -= hostOf(url) }
                    val parsed = try {
                        parse(fetched.body, url)
                    } catch (t: Throwable) {
                        // 只打异常类型：解析器抛的 message 可能内嵌源站 URL
                        Log.w("MatchRepo", "解析异常 (${t.javaClass.simpleName})")
                        emptyList()
                    }
                    if (parsed.isEmpty()) {
                        // HTTP 200 但解析出零场：源站改版（见 SourceOutcome.ParsedEmpty）。
                        // 换下一个候选域名没意义 —— 同一份内容，解析器同样归零
                        return SourceOutcome.ParsedEmpty
                    }
                    return SourceOutcome.Ok(parsed)
                }
                is Fetched.Rejected -> {
                    // ⚠️ 403/429 是限流信号：**立即停整个源，不试镜像**。
                    // 镜像与主域名是同一站点家族，大概率共享限流窗口，
                    // 继续打只会让窗口越拖越长（review 的"限流放大器"）。
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    // D9：记一次冷却，让**后续调用**也不再撞限流窗口
                    startCooldown(url, fetched.code)
                    return SourceOutcome.Rejected(fetched.code)
                }
                is Fetched.NotFound -> {
                    // ⚠️ D3：404/410 **不重试、不登记 host 失败**。
                    // 登记了会让 attemptOrder 把主域名挪到队尾，
                    // 之后整个会话的列表/详情/播放都先敲镜像（镜像首轮还要跑满 3 次重试）。
                    // 换下一个候选是安全的：镜像上这份内容可能还在。
                    notFound = fetched
                }
                is Fetched.Unreachable -> {
                    last = fetched
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                }
            }
        }
        // 所有候选都是"页面不存在"：如实报成 NotFound（而不是"网络连接失败"）
        notFound?.let { if (last == null) return SourceOutcome.NotFound(it.code) }
        SourceOutcome.Unreachable(last?.reason)
    }.getOrElse {
        Log.w("MatchRepo", "fetch failed (${it.javaClass.simpleName})")
        SourceOutcome.Unreachable(it.javaClass.simpleName)
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()

    /**
     * 这批候选里是否有 host 正处于限流冷却（D9）；有就返回当时的状态码。
     *
     * ⚠️ 任一候选命中就判整个源冷却，不去试"没冷却的镜像"：
     * 镜像与主域名同属一个站点家族、大概率共享限流窗口 ——
     * 这正是 [runSource] 里 403/429 分支"不试镜像"的同一条纪律。
     */
    private fun coolingCodeFor(urls: List<String>): Int? {
        val now = clock()
        return synchronized(sourceCooldowns) {
            var code: Int? = null
            for (url in urls) {
                val host = hostOf(url)
                val entry = sourceCooldowns[host]
                if (entry != null) {
                    if (now < entry.first) {
                        code = entry.second
                    } else {
                        // 窗口已过：条目自然失效（**不是**永久拉黑）
                        sourceCooldowns.remove(host)
                    }
                }
            }
            code
        }
    }

    /**
     * 记一次"源冷却"（review/optimization-review.md D9）。
     *
     * ⚠️ 与 [listFailedHosts]「不永久拉黑」的区别：这里的条目带 **TTL**，
     * 到点自动失效、请求照发。永久黑名单会把暂时故障变成长期故障
     * （review/01-P1-5 的同款错误），所以冷却时长刻意只给 60 秒。
     */
    private fun startCooldown(url: String, code: Int) {
        val host = hostOf(url)
        if (host.isEmpty()) return
        val until = clock() + sourceCooldownMs
        synchronized(sourceCooldowns) { sourceCooldowns[host] = until to code }
    }

    /** [runSource] 的底层：把"为什么没拿到正文"分清楚。 */
    private sealed class Fetched {
        data class Body(val body: String) : Fetched()
        data class Rejected(val code: Int) : Fetched()

        /** 404/410：不重试，也**不登记** host 失败（D3）。 */
        data class NotFound(val code: Int) : Fetched()
        data class Unreachable(val reason: String?) : Fetched()
    }

    private suspend fun fetchListDetailed(
        fetch: suspend (String, String?) -> FetchResult,
        url: String,
        attempts: Int = LIST_FETCH_ATTEMPTS,
    ): Fetched {
        var lastReason: String? = null
        for (attempt in 0 until attempts) {
            when (val result = fetch(url, null)) {
                is FetchResult.Ok -> return Fetched.Body(result.body)
                is FetchResult.Rejected -> {
                    Log.w("MatchRepo", "源站拒绝请求(HTTP ${result.code})，放弃重试: ${redactUrl(url)}")
                    return Fetched.Rejected(result.code)
                }
                is FetchResult.NotFound -> {
                    // ⚠️ D3：404/410 是"这个页面没了"，不是"域名挂了"。
                    // 不重试（再打还是 404），也**不**登记 host 失败。
                    debugLog("MatchRepo", "列表项不存在(HTTP ${result.code})，不重试: ${redactUrl(url)}")
                    return Fetched.NotFound(result.code)
                }
                is FetchResult.Unreachable -> {
                    lastReason = result.reason
                    if (attempt < attempts - 1) {
                        delay(LIST_RETRY_BACKOFF_MS * (attempt + 1))
                    }
                }
            }
        }
        return Fetched.Unreachable(lastReason)
    }

    /**
     * 合并逻辑的测试入口。
     *
     * merge 本身是 internal（只在 loadMatches 内部用），
     * 但"两个源怎么合并"是最容易出静默错误的地方（id 撞车、状态被覆盖），
     * 必须能直接测。
     *
     * ⚠️ 签名保持不变（review/optimization-review.md D10）：既有测试在用。
     * 实现已抽到 [MatchMerger]（纯函数，可单独测、不再和 IO/协程样板混在一个类里）。
     *
     * @param xxzb5 第三源场次；缺省空，让旧的 2 参调用点与测试一行都不用改。
     */
    internal fun mergeForTest(
        jrs: List<Match>,
        jrkan: List<Match>,
        xxzb5: List<Match> = emptyList(),
    ): List<Match> = MatchMerger.merge(jrs, jrkan, xxzb5).matches

    /**
     * 合并两个源（委托给 [MatchMerger]）。
     *
     * 原先这段纯逻辑（归一化 key / 状态可信度 / 联赛取优 / 去重）长在本类里，
     * 和抓取/重试/缓存/协程样板混在同一个千行文件中 —— 改一处要在一堆 IO 里定位。
     * 抽出去之后 [MatchMerger] 可以脱离仓库单独测（D10）。
     *
     * ## 合并顺序：jrkan → jrs → xxzb5（末位**故意**）
     *
     * `mergeOne` 是"先到先得 + 后来者只补缺"，而三个源里只有 **JRKAN 自带
     * `MM-dd HH:mm`** 且列表更新最及时；JRS 的 `timeLabel` 常被"直播中"顶掉；
     * xxzb5 的 `timeLabel` 由 `startsAt`（UTC）按北京时间算出，
     * 形状与 JRKAN 一致，但时间解析失败时会退化。
     * 所以 xxzb5 必须排在最后：它的 timeLabel / startAtMillis 只用来**补缺**
     * （`existing.x ?: m.x`），绝不覆盖已有源的值 —— 顺序写反会让跨零点场次
     * 的合并键漂移，同一场拆成两张卡（review/02-data.md P2-12 的同款事故）。
     */
    private fun merge(jrs: List<Match>, jrkan: List<Match>, xxzb5: List<Match>): MatchMerger.Merged =
        MatchMerger.merge(jrs, jrkan, xxzb5)

    /**
     * 拉取某场赛事的全部信号，并用详情页时间校正"直播中"（源站列表缓存会滞后）。
     *
     * 按"信号来自哪个源站"分派，而不是靠 Match.id 前缀猜——
     * 合并后的赛事可能同时携带两个源的信号。
     *
     * JRKAN：列表页只给一个播放页，真正的多条线路在播放页内部，需要展开。
     * JRS：信号在详情页的 a.toLive 里。
     */
    suspend fun loadSignals(match: Match): Detail =
        loadSignals(match) { url, referer -> http.fetch(url, referer) }

    /**
     * 数据源开关变化时清掉详情页缓存（2026-10-31「我的」页开关）。
     *
     * 为什么要清：`loadSignals` 会按开关过滤信号，而详情页 HTML 有 5 分钟 TTL 缓存。
     * 不清的话"关掉 JRS → 立刻重进同一场"会命中缓存里的旧正文 —— 虽然过滤逻辑仍会
     * 生效（过滤发生在解析结果上），但**JRS 的详情页请求本身**已经在缓存命中时被跳过了，
     * 这部分是省下的；真正需要清的是"用户重新打开 JRS 后立刻要能看到它的线路"，
     * 那也只需缓存未过期即可。所以这里清缓存是**为了语义清晰**：
     * 开关是数据来源的变更，不该受上一次配置的缓存影响。
     */
    fun clearDetailCache() {
        detailCache.clear()
    }

    /**
     * 用户显式点「刷新线路」时，让该场详情页的 TTL 缓存失效。
     *
     * ⚠️ 为什么不加 forceRefresh 参数：
     * 那样要改 MainViewModel 注入的 loadSignals lambda 签名，
     * 而那是个测试用的注入口，改签名会连带改一堆测试。
     * 清缓存是纯内存操作、不触网，注入测试下也安全。
     *
     * 只清这一场，不动其它场次（其它场次的缓存仍在 TTL 内，继续省请求）。
     */
    fun invalidateDetailCache(detailId: String) {
        detailCache.invalidate(detailId)
    }

    /**
     * 可注入 fetch 的实现，供单元测试覆盖"多个播放页展开后 id 是否唯一"这类逻辑。
     */
    internal suspend fun loadSignals(
        match: Match,
        fetch: suspend (String, String?) -> FetchResult,
    ): Detail = withContext(Dispatchers.IO) {
        var correction: JrsParser.StatusCorrection? = null

        val jrsSignals = match.jrsDetailId?.let { id ->
            // ⚠️ 必须走缓存版 detailCache.load：
            // 列表状态校正刚拉过同一个详情页，不复用就是白打源站一次。
            val html = detailCache.load(id, fetch = fetch)
            if (html != null) {
                correction = jrsParser.correctedStatus(match, jrsParser.parseScheduledTime(html))
                jrsParser.parseDetail(html, match)
            } else {
                emptyList()
            }
        } ?: emptyList()

        val jrkanSources = match.sources.filter { it.provider == Provider.JRKAN }
        // 每个播放页用独立的 id 前缀：parseChannels 内部的编号从 0 开始，
        // 两个页面共用前缀会产出重复 id —— 播放器把 id 当 LazyColumn 的 key，
        // 重复 key 会直接抛 IllegalArgumentException 崩掉（这个仓库已经崩过两次）。
        val jrkanExpansions = jrkanSources.mapIndexed { index, source ->
            expandJrkanPlayPage(source, "${match.id}-jr$index", fetch)
        }
        val jrkanSignals = jrkanExpansions.flatMap { it.channels }
        // 展开失败时退回列表页给的播放页，至少还能用 WebView 打开。
        //
        // ⚠️ 但只要**有一个播放页真的取到了正文**，就不退回：那说明源站把线路给了我们，
        // 过滤后为空就是"这一场只剩主播线路"（用户要求过滤掉）。
        // 而入口页（`/play/steam<id>.html`）会自动选中它自己的第一条 —— 正是主播线路，
        // 退回入口页等于把刚过滤掉的内容又播回来（见 `ChannelAdmission` 的 KDoc）。
        val jrkanFinal = if (jrkanSignals.isEmpty() && jrkanExpansions.none { it.pageFetched }) {
            jrkanSources
        } else {
            jrkanSignals
        }

        // xxzb5：列表页只给一个线路接口入口（/progress），线路要现取（结构与 JRKAN 同构）。
        // ⚠️ 每个入口给不同的 id 前缀序号（与 jrkanExpansions 同一纪律）：
        // parseSignals 的编号只在单次调用内唯一，两个入口都成功时
        // 共用前缀就会产出重复 id，播放器当 LazyColumn key 用直接崩。
        val xxzb5Sources = match.sources.filter { it.provider == Provider.XXZB5 }
        val xxzb5Signals = xxzb5Sources.mapIndexed { index, source ->
            expandXxzb5SourceEndpoint(source, match, index, fetch)
        }.flatten()

        // ⚠️ 最后一道防线：保证信号 id 全局唯一。
        // 播放器把 id 当 LazyColumn 的 key，重复 key 直接 IllegalStateException 闪退
        // （这个仓库已经崩过两次）。
        // 上游的 id 生成（JrkanParser / JrsParser）已经各自带来源维度了，
        // 但那是"约定"不是"保证"——这里再兜一次底，
        // 以后任何一处改了 id 规则都不会把 App 弄崩。
        //
        // 线路去重同样按**等价键**（`LineEquivalence`）而不是整串 URL：
        // xxzb5 的裸 m3u8 与 JRS/JRKAN 的云直播线路里，同一份清单会以不同拼法出现，
        // 不在这里收敛的话，播放页会列出几条名字不同、点开是同一个上游的线路。
        val deduped = LineEquivalence.distinctByLine(jrsSignals + jrkanFinal + xxzb5Signals)
            .mapIndexed { index, s -> s.copy(id = "${s.id}#$index") }

        Detail(deduped, correction)
    }

    /**
     * 展开 xxzb5 的线路接口（`/api/v1/matches/{id}/progress`）。
     *
     * ## 为什么要走候选 host
     *
     * 与 [expandJrkanPlayPage] 同一条纪律（P1-9）：线路接口挂在**取到列表正文的那个
     * host** 下（`Xxzb5Parser.parseList` 的 origin 参数），主域名死了而列表是从镜像
     * 拿到的时候，这里必须改试镜像 —— 否则换域名只换了一半。
     *
     * 拿到正文但 0 条线路（`available=false` / 全被准入过滤）是事实不是故障，
     * 换镜像也解不出同样的东西，直接返回空。
     */
    private suspend fun expandXxzb5SourceEndpoint(
        source: StreamSource,
        match: Match,
        index: Int,
        fetch: suspend (String, String?) -> FetchResult,
    ): List<StreamSource> {
        val detailId = XXZB5_DETAIL_ID.find(source.pageUrl)?.groupValues?.get(1)
        val candidates = if (detailId != null) {
            SourceEndpoints.attemptOrder(
                SourceEndpoints.xxzb5SignalsUrls(detailId),
                synchronized(listFailedHosts) { listFailedHosts.toSet() },
            )
        } else {
            // 地址形态不认识（改版/外部拼进来的）：原样试一次，不猜 id
            listOf(source.pageUrl)
        }
        coolingCodeFor(candidates)?.let { code ->
            debugLog("MatchRepo", "xxzb5 线路接口处于限流冷却中(HTTP $code)，本轮不发请求")
            return emptyList()
        }
        for (url in candidates) {
            val body = when (val r = fetch(url, http.refererOf(url))) {
                is FetchResult.Ok -> r.body
                is FetchResult.Rejected -> {
                    Log.w("MatchRepo", "xxzb5 线路接口被拒绝(HTTP ${r.code})，放弃重试: ${redactUrl(url)}")
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    startCooldown(url, r.code)
                    return emptyList()
                }
                is FetchResult.NotFound -> {
                    debugLog("MatchRepo", "xxzb5 线路接口不存在(HTTP ${r.code})，不重试")
                    continue
                }
                is FetchResult.Unreachable -> {
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    continue
                }
            }
            synchronized(listFailedHosts) { listFailedHosts -= hostOf(url) }
            val channels = xxzb5Parser.parseSignals(body, match, index)
            if (channels.isNotEmpty()) return channels
            debugLog("MatchRepo", "xxzb5 线路接口无可用线路：${redactUrl(url)}")
            return emptyList()
        }
        return emptyList()
    }

    /**
     * 展开一个 JRKAN 播放页，拿到里面的线路列表。
     *
     * ## 为什么要试多个 host（review/02-data.md P1-9）
     *
     * 播放域名写死在 `JrkanParser.PLAY_HOST`（历史已换过两次）。源站一换域名，
     * 所有 JRKAN 线路会**静默失效**，然后由下面的 `ifEmpty` 兜回列表页播放页
     * （还能开 WebView），用户看到的是"线路变少了"，完全不知道发生了什么。
     *
     * 所以这里按 [SourceEndpoints.playPageCandidates] 的顺序试候选主机：
     * 列表页给的原始地址在前，候选主机随后。
     *
     * ## 请求预算
     *
     * ⚠️ 每个候选**只试一次**（不像列表页首候选跑满 3 次重试）：
     * 换域名针对的是"这个域名死了"（DNS/主机故障），
     * 那种情况下在死域名上重试三次纯属浪费几秒超时；
     * 而瞬时抖动由列表页那一层负责兜（那边的重试预算更值）。
     * 上界 = 候选数量（见 `SourceEndpointsWiringTest`），不会随重试次数放大。
     *
     * @param source 列表页给出的播放页（host 可能是历史域名）
     * @param matchId 线路 id 前缀（同一场的多个播放页必须各自唯一）
     */
    private suspend fun expandJrkanPlayPage(
        source: StreamSource,
        matchId: String,
        fetch: suspend (String, String?) -> FetchResult,
    ): Expansion {
        val candidates = SourceEndpoints.playPageCandidates(
            source.pageUrl,
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
        // D9：冷却窗口内一个请求都不发（与列表/详情路径同一条纪律）
        coolingCodeFor(candidates)?.let { code ->
            debugLog("MatchRepo", "播放页处于限流冷却中(HTTP $code)，本轮不发请求")
            return Expansion(emptyList(), pageFetched = false)
        }
        // 只要**有一页**拿到过正文就记下来：此时"0 条线路"是事实（全是主播线路），
        // 而不是"没连上"。两种情况对调用方的含义完全不同，见 [Expansion]。
        var pageFetched = false
        for (url in candidates) {
            val html = when (val r = fetch(url, http.refererOf(url))) {
                is FetchResult.Ok -> {
                    pageFetched = true
                    r.body
                }
                is FetchResult.Rejected -> {
                    // 403/429 是限流信号：立即停整个源，不试镜像。
                    // 镜像与主域名同属一个站点家族，大概率共享限流窗口。
                    Log.w("MatchRepo", "播放页被拒绝(HTTP ${r.code})，放弃重试: ${redactUrl(url)}")
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    // D9：记一次冷却，让后续调用不再撞限流窗口
                    startCooldown(url, r.code)
                    return Expansion(emptyList(), pageFetched)
                }
                is FetchResult.NotFound -> {
                    // ⚠️ D3：404/410 **不重试、不登记 host 失败**。
                    // 已结束场次的播放页实测就是 404：登记了会让主域名被降到候选队尾，
                    // 之后整个会话的列表/详情/播放都先敲镜像。
                    // 换下一个候选（镜像上可能还留着一份）。
                    debugLog("MatchRepo", "播放页不存在(HTTP ${r.code})，不重试: ${redactUrl(url)}")
                    continue
                }
                is FetchResult.Unreachable -> {
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    continue
                }
            }
            synchronized(listFailedHosts) { listFailedHosts -= hostOf(url) }
            // ⚠️ 用**取到正文那个 host** 拼线路地址，而不是固定用 PLAY_HOST：
            // 否则主域名死了、线路是从镜像展开出来的，地址却仍指向死域名，
            // 播放器点开就是错误页 —— 换了域名等于没换。
            val channels = jrkanParser.parseChannels(html, matchId, SourceEndpoints.originOf(url))
            if (channels.isNotEmpty()) return Expansion(channels, pageFetched = true)
            // 拿得到正文但一条线路都解析不出来：可能改版，也可能过滤后确实为空
            // （全是主播线路）。换下一个候选域名试（镜像有时还留着一份旧结构）。
        }
        return Expansion(emptyList(), pageFetched)
    }

    /**
     * 一个播放页的展开结果。
     *
     * @param channels 过滤后进入界面的线路（**可能为空**）
     * @param pageFetched 是否真的拿到过正文。拿到过 → 我们知道源站给了哪些线路，
     *   `channels` 为空就是"这一场只剩主播线路"这个事实；没拿到 → 我们不知道里面
     *   有什么，调用方可以退回列表页给的入口页用 WebView 兜底。
     */
    private data class Expansion(val channels: List<StreamSource>, val pageFetched: Boolean)

    /** loadSignals 的结果：信号列表 + 可选校正（源站列表"直播中"滞后时用） */
    data class Detail(
        val signals: List<StreamSource>,
        val correction: JrsParser.StatusCorrection? = null,
    )

    /**
     * 把播放页解析为可直连的 m3u8，并**实际拉一次清单做校验**。
     *
     * 只解析出地址不够——地址可能已过期或需要特定 Referer。
     * 这里确认返回体里含 `#EXTM3U` 才认为该线路可用，避免进播放器黑屏。
     *
     * 解析可能给出多个候选（反混淆版 / 原文版）：逐个校验，
     * 第一个能拉出清单的即采用。源站哪天取消域名混淆，原文版会自动顶上，
     * 不用等"反混淆彻底坏掉"才整条线路判死。
     */
    suspend fun resolveStream(source: StreamSource): StreamUrlResolver.Resolved? =
        withContext(Dispatchers.IO) {
            if (!source.isDirectHls) return@withContext null
            val resolved = resolver.resolve(source.pageUrl) { url ->
                http.get(url, http.refererOf(url))
            } ?: return@withContext null

            // ⚠️ 候选校验有预算：反混淆版 + 原文版两个足够，
            // 全量试完 = 每个坏域名一次超时等待（fastFail 也要 8s+12s），
            // 乘上故障切换的 3 条线路就是几十秒静默。
            val budget = resolved.m3u8Candidates.take(MAX_M3U8_CANDIDATES)
            for (candidate in budget) {
                val playlist = http.get(candidate, resolved.referer, fastFail = true)
                if (playlist != null && playlist.contains("#EXTM3U")) {
                    debugLog("MatchRepo", "m3u8 OK: $candidate")
                    return@withContext resolved.copy(m3u8Url = candidate)
                }
                // ⚠️ 脱敏：m3u8 的签名在 query 里，直接打出来等于公开播放凭证
                Log.w("MatchRepo", "m3u8 candidate rejected: ${redactUrl(candidate)}")
            }
            Log.w(
                "MatchRepo",
                "all m3u8 candidates failed: " +
                    budget.joinToString { redactUrl(it) },
            )
            null
        }
}

/**
 * 详情页校正的**预算选取**：在预算内优先校正**最早开赛**的场次
 * （review/02-data.md P1-4）。
 *
 * ## 为什么不能按列表顺序取前 N 场
 *
 * 旧实现是 `toFix.take(MAX_DETAIL_FETCH)` —— 纯按列表顺序。
 * 列表顺序是源站给的，**和"哪场最可能已经结束"毫无关系**，
 * 于是最该被纠正的场次可能落在预算之外，
 * 只能等"整个自然日过完"才被兜底判结束：
 *
 * > 19:35 开球、22:00 早就结束的比赛，到午夜前都还亮红「直播中」。
 *
 * ## 排序键
 *
 * - 主键 `startAtMillis` 升序：JRS「直播中」行的 `startAtMillis` 是分组日期的
 *   00:00 占位，所以**跨天**能分出先后 —— 昨天/前天的场次一定是过期最久的，
 *   最该先校正。同一天内分不出（都是同一个 00:00），但至少不会把
 *   "肯定过期的前几天"排在预算之外。
 * - 次键 `id` 升序：同一天内必须有稳定的次级键，
 *   否则同一份数据两次运行会拉不同的详情页，测试也会时绿时红。
 */
internal fun selectDetailBudget(matches: List<Match>, budget: Int): List<Match> {
    if (budget <= 0) return emptyList()
    return matches
        .filter { it.jrsDetailId != null }
        .sortedWith(compareBy({ it.startAtMillis ?: Long.MAX_VALUE }, { it.id }))
        .take(budget)
}
