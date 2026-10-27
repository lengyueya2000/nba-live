package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.data.remote.debugLog
import com.nbalive.app.data.remote.redactUrl
import com.nbalive.app.data.remote.safeLogWarn
import com.nbalive.app.data.remote.JrsParser
import com.nbalive.app.data.remote.JrkanParser
import com.nbalive.app.data.remote.StreamUrlResolver
import android.util.Log
import kotlinx.coroutines.CancellationException
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
 * 主源 JRS（jrszbt.com），备源 JRKAN（m.jrkan.com）。
 * 同一场比赛在两个源都可能出现，按"队名对"归一化后合并信号列表。
 */
class MatchRepository(
    private val http: HttpClient = HttpClient.shared,
    private val jrsParser: JrsParser = JrsParser(),
    private val jrkanParser: JrkanParser = JrkanParser(),
    private val resolver: StreamUrlResolver = StreamUrlResolver(),
    /**
     * 列表加载的整体 deadline（review/02-data.md P2-3）。
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

    /** 拉取并合并两个源的全部赛事。一个源抛异常不能带走另一个源的好数据。 */
    suspend fun loadMatches(): Result<List<Match>> =
        loadMatchesInternal { url, referer -> http.fetch(url, referer) }

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
     * 可注入 fetch 的实现，供单元测试覆盖"单源瞬时失败要重试"这类逻辑。
     *
     * ⚠️ 参数类型是 [FetchResult] 而不是 `String?`：
     * 两个版本在 Kotlin 侧是合法重载（可空性不同），但擦除后 JVM 签名相同，
     * 且对 lambda 会产生重载歧义 —— 只保留信息更全的这个，
     * 老测试里返回 `String?` 的 lambda 已同步迁移。
     */
    internal suspend fun loadMatches(
        fetch: suspend (String, String?) -> FetchResult,
    ): Result<List<Match>> = loadMatchesInternal(fetch)

    /**
     * 真正干活的实现：区分"源站拒绝"与"网络不通"，据此决定要不要重试。
     *
     * @param fetch 返回 [FetchResult]：403/429 明确拒绝 → 不重试；网络故障 → 重试
     */
    internal suspend fun loadMatchesInternal(
        fetch: suspend (String, String?) -> FetchResult,
    ): Result<List<Match>> = withContext(Dispatchers.IO) {
        catching {
            supervisorScope {
                val jrsDeferred = async { fetchJrs(fetch) }
                val jrkanDeferred = async { fetchJrkan(fetch) }
                val jrs = jrsDeferred.await().getOrElse {
                    // ⚠️ 不把 throwable 交给 Log：OkHttp 异常消息内嵌完整带 auth_key 的 URL
                    safeLogWarn("MatchRepo", "JRS fetch failed", it)
                    emptyList()
                }
                val jrkan = jrkanDeferred.await().getOrElse {
                    safeLogWarn("MatchRepo", "JRKAN fetch failed", it)
                    emptyList()
                }
                if (jrs.isEmpty() && jrkan.isEmpty()) {
                    // 两边都空：大概率是网络/源站故障，而不是"今天真没比赛"
                    throw IOException("两个数据源都没有返回赛事，请检查网络")
                }
                // 先校正再排序：源站列表的"直播中"会滞后，不修就全堆在最前面
                // ⚠️ 校正拉详情页与列表 fetch 走**同一个缓存**：
                // 否则"刷新列表"和"点进播放页"会把同一个详情页拉两遍。
                sort(correctLiveStatuses(merge(jrs, jrkan).matches, fetch))
            }
        }
    }

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
                        val html = fetchJrsDetail(m.jrsDetailId!!, fetch)
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
     * 拉取 JRS 详情页，带一个**短 TTL 的进程内缓存**。
     *
     * 同一个详情页在一条用户操作链里会被请求 2~3 次：
     *  1) 列表状态校正（每场一个）
     *  2) 进入播放页 loadSignals（同一场又来一次）
     *  3) 详情页点"刷新线路"（再来一次）
     *
     * 详情页内容是"计划开赛时间"，一场比赛在几小时内不会变，
     * 用 TTL 缓存直接消掉重复请求 —— 这是对源站最有效的一次减负。
     *
     * TTL 取 5 分钟：足够覆盖"打开 App → 点直播 → 刷新线路"这一串操作，
     * 又短到不会让用户手动刷新时看到过期数据（状态校正本身会重新拉）。
     *
     * ## 域名候选（review/02-data.md P1-9 / P2-6）
     *
     * 详情页挂在**同一个站点的不同域名**下，所以走 [SourceEndpoints.jrsDetailUrls]
     * 的候选列表：主域名不可达时改试镜像。
     * 旧实现把 `https://jrszbt.com/live/$id.html` 写死在这里**和**
     * `invalidateDetailCache` 两处 —— 主域名一死，列表靠镜像活了下来，
     * 但每一场的详情页仍去敲死域名，状态校正与线路展开全部静默失效；
     * 而且两处副本改一处漏一处时，"刷新线路"会清 A 的缓存、读 B 的缓存，
     * 按钮变摆设且不报错。
     *
     * ⚠️ 缓存 key 用 **detailId**（而不是 URL）：
     * 命中哪个域名都不影响同一场次的缓存身份，
     * 用户清缓存时也不用知道上次是从哪个域名拿到的。
     */
    private suspend fun fetchJrsDetail(
        detailId: String,
        fetch: suspend (String, String?) -> FetchResult,
        forceRefresh: Boolean = false,
    ): String? {
        // ⚠️ 用**单调时钟**（nanoTime）而不是 currentTimeMillis：
        // 用户把系统时间往前拨，"now - cachedAt" 会变成负数，
        // 负数 < TTL 恒成立 → 缓存永不过期（源站改版了也看不到新内容）。
        // nanoTime 不受系统时间调整影响。
        // （不用 android.os.SystemClock.elapsedRealtime：它在 JVM 单元测试里
        //   返回默认值 0，测试就没法验证 TTL 过期了。）
        val now = System.nanoTime() / 1_000_000L
        if (!forceRefresh) {
            synchronized(detailCache) {
                val hit = detailCache[detailId]
                if (hit != null) {
                    if (now - hit.first < DETAIL_CACHE_TTL_MS) return hit.second
                    detailCache.remove(detailId)
                }
            }
        }

        val candidates = SourceEndpoints.attemptOrder(
            SourceEndpoints.jrsDetailUrls(detailId),
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
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
                    Log.w("MatchRepo", "详情页被拒绝(HTTP ${r.code})，放弃重试: ${redactUrl(url)}")
                    return null
                }
                is FetchResult.Unreachable -> {
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                }
            }
            if (body != null) break
        }

        if (!body.isNullOrBlank()) {
            synchronized(detailCache) {
                detailCache[detailId] = now to body
            }
        }
        return body
    }

    companion object {
        /**
         * 列表加载的整体 deadline（review/02-data.md P2-3）。
         *
         * 60 秒：正常两源并发首屏 1~3 秒，慢网也在 10 秒级；
         * 超过 60 秒基本可以确定源站/网络挂了，再等只是让用户干瞪眼。
         */
        private const val DEFAULT_LOAD_DEADLINE_MS = 60_000L

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

        /** 重试退避基数：第 n 次失败后等 n * LIST_RETRY_BACKOFF_MS。 */
        private const val LIST_RETRY_BACKOFF_MS = 400L

        /** 详情页缓存有效期：覆盖"打开 App → 点直播 → 刷新线路"这一串操作。 */
        private const val DETAIL_CACHE_TTL_MS = 5 * 60 * 1000L

        /** 详情页缓存的最大条数：防止长时间运行后无限增长。 */
        private const val DETAIL_CACHE_MAX_ENTRIES = 64

        /**
         * 队名归一化用的空白正则。
         *
         * ⚠️ 必须提成常量：每次调用 `Regex("\\s+")` 都会新建一个对象，
         * 而 normalizeKey 每场比赛要调两次 —— 几十场还好，上百场就是无谓的分配。
         */
        private val WHITESPACE = Regex("\\s+")
    }

    /**
     * 详情页短缓存：**detailId** -> (缓存时刻, 正文)。
     *
     * ⚠️ key 用 detailId 而不是 URL：详情页有多个候选域名
     * （[SourceEndpoints.jrsDetailUrls]），按 URL 存会让同一场次
     * 在主域名与镜像域名下各存一份，且"刷新线路"只能清掉其中一个 ——
     * 用户点了刷新却拿到旧正文，还不报错（review/02-data.md P2-6）。
     *
     * ⚠️ 会被多个协程并发访问（状态校正并发 4 路 + loadSignals），
     * 所以用 synchronized 保护，不能用普通 LinkedHashMap。
     *
     * ⚠️ 必须有**条数上限**：MatchRepository 跟着 ViewModel 活整个进程，
     * 而一场比赛一个详情页 —— 一天下来几十上百个 URL 会一直堆着。
     * 超过上限就淘汰最旧的（LinkedHashMap 的 accessOrder=true 就是 LRU）。
     */
    private val detailCache = object : LinkedHashMap<String, Pair<Long, String>>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?,
        ): Boolean = size > DETAIL_CACHE_MAX_ENTRIES
    }

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
     * 列表页单源拉取，带线性退避重试。
     *
     * 回归背景：实测源站 20/20 稳定，但此前 fetch 返回 null（非 2xx / 超时 / DNS 抖动）
     * 会直接 return emptyList() 且不重试 —— 手机网络一次瞬时抖动就把整个源静默吞掉，
     * 用户看到的正是"有时只剩 JRS、有时只剩 JRKAN"。
     *
     * ⚠️ 但**被源站拒绝（403/429）时不能重试**：
     * 那是限流信号，再打 3 次只会让限流窗口越拖越长，退避 400/800ms 也太短。
     * 详见 [FetchResult]。
     */
    private suspend fun fetchListWithRetry(
        fetch: suspend (String, String?) -> FetchResult,
        urls: List<String>,
    ): String? {
        // 一个域名失效按顺序试下一个（review/02-data.md P1-9）；
        // 失败 host 记进 listFailedHosts，下一轮直接从镜像域名开始。
        val candidates = SourceEndpoints.attemptOrder(
            urls,
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
        var lastReason: String? = null
        for ((index, url) in candidates.withIndex()) {
            // 首个候选跑满重试（瞬时抖动靠它兜），镜像只试一次：
            // 镜像存在的意义是"这个域名死了"（DNS/主机故障），
            // 那种情况下在死域名上重试三次纯属浪费几秒超时
            val attempts = if (index == 0) LIST_FETCH_ATTEMPTS else 1
            var rejected = false
            var gotBody: String? = null
            for (attempt in 0 until attempts) {
                when (val result = fetch(url, null)) {
                    is FetchResult.Ok -> {
                        synchronized(listFailedHosts) { listFailedHosts -= hostOf(url) }
                        gotBody = result.body
                        break // 成功立刻收工，绝不再多打
                    }
                    is FetchResult.Rejected -> {
                        // ⚠️ 403/429 是限流信号：**立即停整个源，不试镜像**。
                        // 镜像与主域名是同一站点家族，大概率共享限流窗口，
                        // 继续打只会让窗口越拖越长（review 的"限流放大器"）。
                        Log.w("MatchRepo", "源站拒绝请求(HTTP ${result.code})，放弃重试: ${redactUrl(url)}")
                        synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                        rejected = true
                        break
                    }
                    is FetchResult.Unreachable -> {
                        lastReason = result.reason
                        if (attempt < attempts - 1) {
                            delay(LIST_RETRY_BACKOFF_MS * (attempt + 1))
                        }
                    }
                }
            }
            if (rejected) return null
            if (gotBody != null) return gotBody
            synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
        }
        return null
    }

    private suspend fun fetchJrs(fetch: suspend (String, String?) -> FetchResult): Result<List<Match>> = catching {
        val html = fetchListWithRetry(fetch, SourceEndpoints.JRS_LIST) ?: return@catching emptyList()
        jrsParser.parseList(html)
    }

    /**
     * 单个源的抓取结果，**带失败原因**。
     *
     * 原来只有 [fetchJrs] 这种 `Result<List<Match>>`：
     * 网络不通、被限流、HTTP 200 但解析出零场，三种情况都塌缩成
     * "空列表"或"一个异常"，调用方无法向用户解释"为什么场次变少了"。
     * 这里把三者分开，[loadMatchesWithOutcome] 才能报出 failedProviders。
     */
    internal sealed class SourceOutcome {
        /** 正常拿到场次 */
        data class Ok(val matches: List<Match>) : SourceOutcome()

        /** 源站明确拒绝（403/429）。不重试，但必须让用户知道这个源没了。 */
        data class Rejected(val code: Int) : SourceOutcome()

        /** 网络不通（DNS/超时/连接失败），重试后仍失败 */
        data class Unreachable(val reason: String?) : SourceOutcome()

        /**
         * HTTP 200 但解析出零场。
         *
         * ⚠️ 这**也算失败**：源站改版让解析器整表归零时，HTTP 层是完全正常的。
         * 若按"HTTP 成功"处理就成了"这个源今天没比赛"，又是一条静默丢场次的路。
         */
        data object ParsedEmpty : SourceOutcome()

        val failed: Boolean get() = this !is Ok
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

    /** 生产入口：带失败信息的加载。 */
    suspend fun loadMatchesOutcome(): LoadOutcome =
        loadMatchesWithOutcome { url, referer -> http.fetch(url, referer) }

    /**
     * 带失败信息的列表加载。
     *
     * 与 [loadMatchesInternal] 的区别：**不因部分失败而抛异常**，
     * 而是把失败源如实报出来。两源全挂时也不抛，
     * 由调用方决定怎么呈现（[loadMatches] 为了兼容旧签名仍会抛）。
     */
    internal suspend fun loadMatchesWithOutcome(
        fetch: suspend (String, String?) -> FetchResult,
    ): LoadOutcome = withContext(Dispatchers.IO) {
        // ⚠️ 整体 deadline（review/02-data.md P2-3）：
        // 单源 3 次重试 × 35s callTimeout 已约 105s，再叠加状态校正的
        // 4 批 × 35s，最坏接近 3 分钟无出口。用户宁可看到
        // "部分场次 + 可重试"，也不要盯着转圈。
        //
        // 超时后**不抛异常**：如实报成"两个源都没在时限内返回"，
        // 由调用方按 partialError 呈现 —— 抛异常会让 UI 只剩一个错误页，
        // 连已经拿到的部分数据也丢掉。
        val finished = withTimeoutOrNull(loadDeadlineMs) {
            supervisorScope {
                val jrsDeferred = async { fetchJrsOutcome(fetch) }
                val jrkanDeferred = async { fetchJrkanOutcome(fetch) }
                jrsDeferred.await() to jrkanDeferred.await()
            }
        }
        if (finished == null) {
            Log.w("MatchRepo", "列表加载超过 ${loadDeadlineMs}ms 未完成，按超时返回")
            return@withContext LoadOutcome(
                matches = emptyList(),
                failedProviders = listOf(Provider.JRS, Provider.JRKAN),
                reasons = mapOf(
                    Provider.JRS to "网络超时（超过 ${loadDeadlineMs / 1000} 秒未响应）",
                    Provider.JRKAN to "网络超时（超过 ${loadDeadlineMs / 1000} 秒未响应）",
                ),
            )
        }
        val (jrs, jrkan) = finished

        val failures = listOf(Provider.JRS to jrs, Provider.JRKAN to jrkan)
            .filter { it.second.failed }
        val reasons = failures.associate { (provider, outcome) ->
            provider to when (outcome) {
                is SourceOutcome.Rejected -> "源站拒绝请求（HTTP ${outcome.code}）"
                is SourceOutcome.Unreachable ->
                    "网络连接失败${outcome.reason?.let { "（$it）" } ?: ""}"
                SourceOutcome.ParsedEmpty -> "未解析到任何场次（源站可能已改版）"
                is SourceOutcome.Ok -> ""
            }
        }

        val merged = merge(jrs.orEmpty(), jrkan.orEmpty())
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

    /** 单源成功时给场次，失败时给空表。 */
    private fun SourceOutcome.orEmpty(): List<Match> =
        (this as? SourceOutcome.Ok)?.matches ?: emptyList()

    private suspend fun fetchJrsOutcome(
        fetch: suspend (String, String?) -> FetchResult,
    ): SourceOutcome = runSource(SourceEndpoints.JRS_LIST, fetch) { jrsParser.parseList(it) }

    private suspend fun fetchJrkanOutcome(
        fetch: suspend (String, String?) -> FetchResult,
    ): SourceOutcome = runSource(SourceEndpoints.JRKAN_LIST_JS, fetch) { jrkanParser.parseList(it) }

    /**
     * 抓一个源并归类到 [SourceOutcome]。
     *
     * ⚠️ 解析异常也要归为失败而不是抛出：
     * 单个源的解析器回归不能带走另一个源的好数据（这条与原有约定一致）。
     *
     * @param urls 候选端点（[SourceEndpoints]）：一个失效按顺序试下一个，
     *   全部失效才判这个源失败 —— 源站换域名是常态（review/02-data.md P1-9）。
     */
    private suspend fun runSource(
        urls: List<String>,
        fetch: suspend (String, String?) -> FetchResult,
        parse: (String) -> List<Match>,
    ): SourceOutcome = catching {
        // 跨调用记住哪些 host 上一轮失败了：下一轮直接镜像域名先试，省一轮超时。
        // ⚠️ 成功必须清掉失败记录（恢复），失败只记到"下一轮"——不是永久拉黑：
        // 限流窗口（403/429）是会过去的，永久黑名单只是把暂时故障变成长期故障，
        // 那是 review/01-P1-5「15 秒看门狗永久拉黑健康线路」的同款错误。
        val candidates = SourceEndpoints.attemptOrder(
            urls,
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
        var last: Fetched.Unreachable? = null
        var rejected: Fetched.Rejected? = null
        for ((index, url) in candidates.withIndex()) {
            // 首个候选跑满重试（瞬时抖动靠它兜），镜像只试一次：
            // 镜像存在的意义是"这个域名死了"（DNS/主机故障），
            // 那种情况下在死域名上重试三次纯属浪费几秒超时
            val attempts = if (index == 0) LIST_FETCH_ATTEMPTS else 1
            when (val fetched = fetchListDetailed(fetch, url, attempts)) {
                is Fetched.Body -> {
                    synchronized(listFailedHosts) { listFailedHosts -= hostOf(url) }
                    val parsed = try {
                        parse(fetched.body)
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
                    return SourceOutcome.Rejected(fetched.code)
                }
                is Fetched.Unreachable -> {
                    last = fetched
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                }
            }
        }
        SourceOutcome.Unreachable(last?.reason)
    }.getOrElse {
        Log.w("MatchRepo", "fetch failed (${it.javaClass.simpleName})")
        SourceOutcome.Unreachable(it.javaClass.simpleName)
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()

    /** [fetchListWithRetry] 的带原因版本：把"为什么没拿到正文"分清楚。 */
    private sealed class Fetched {
        data class Body(val body: String) : Fetched()
        data class Rejected(val code: Int) : Fetched()
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


    private suspend fun fetchJrkan(fetch: suspend (String, String?) -> FetchResult): Result<List<Match>> = catching {
        val js = fetchListWithRetry(fetch, SourceEndpoints.JRKAN_LIST_JS) ?: return@catching emptyList()
        jrkanParser.parseList(js)
    }

    /**
     * 合并两个源：按 "日期|主队|客队" 归一化 key 归并，信号列表合并去重。
     *
     * 日期必须参与 key：同一对球队背靠背/系列赛会重复出现，
     * 只按队名归并会把真实存在的第二场直接吞掉。
     */
    /**
     * 合并逻辑的测试入口。
     *
     * merge 本身是 private（只在 loadMatches 内部用），
     * 但"两个源怎么合并"是最容易出静默错误的地方（id 撞车、状态被覆盖），
     * 必须能直接测。
     */
    internal fun mergeForTest(jrs: List<Match>, jrkan: List<Match>): List<Match> =
        merge(jrs, jrkan).matches

    /** [merge] 的结果：合并后的场次 + 因非 NBA/CBA 被丢弃的数量。 */
    internal data class Merged(val matches: List<Match>, val filteredOutCount: Int)

    private fun merge(jrs: List<Match>, jrkan: List<Match>): Merged {
        val merged = LinkedHashMap<String, Match>()
        // 已用过的 id：避免每次都 O(n) 扫 merged.values（几十场还行，上百场会退化）
        val usedIds = HashSet<String>()

        fun mergeOne(m: Match) {
            val key = normalizeKey(m)
            // id 兜底唯一化：LazyVerticalGrid 的 key 重复会直接崩溃，
            // 宁可 id 加后缀，也绝不丢比赛
            var unique = m.id
            var suffix = merged.size
            while (!usedIds.add(unique)) {
                unique = "${m.id}#${suffix++}"
            }
            val existing = merged[key]
            merged[key] = if (existing == null) {
                m.copy(id = unique)
            } else {
                val sources = (existing.sources + m.sources).distinctBy { it.pageUrl }
                existing.copy(
                    // 状态取"更可信"的那个，而不是"后合并的那个"：
                    // JRS 的列表会滞后（已结束还标直播中），
                    // JRKAN 自带 MM-dd HH:mm 可以做时钟校正。
                    // 原来直接用 m.status（JRS 后合并）会把 JRKAN 更准的状态覆盖掉。
                    status = betterStatus(existing.status, m.status),
                    timeLabel = existing.timeLabel.ifBlank { m.timeLabel },
                    startAtMillis = existing.startAtMillis ?: m.startAtMillis,
                    homeLogo = existing.homeLogo ?: m.homeLogo,
                    awayLogo = existing.awayLogo ?: m.awayLogo,
                    sources = sources,
                    signalCount = sources.size,
                    jrsDetailId = existing.jrsDetailId ?: m.jrsDetailId,
                )
            }
        }

        jrkan.forEach(::mergeOne)
        jrs.forEach(::mergeOne)

        val all = merged.values.toList()
        // 产品只做 NBA + CBA：源站混入的足球/外联赛在这里统一丢弃。
        // ⚠️ 丢弃数量要能被观测（P1-新）：联赛名解析失败会落到 League.OTHER，
        // 整场静默消失 —— 至少让 filteredOutCount 能把"丢了几场"暴露出来。
        val kept = all.filter { it.league in League.supported }
            .distinctBy { it.id }
        return Merged(kept, all.size - kept.size)
    }

    /**
     * 合并两个来源的状态：取更可信的那个。
     *
     * 可信度排序：FINISHED > LIVE > UPCOMING。
     *
     * 为什么要这样而不是"后合并的赢"：
     * - JRS 列表的"直播中"会滞后好几小时（已结束的场次还标着直播中）
     * - JRKAN 自带 "MM-dd HH:mm"，可以拿本地时钟双向校正
     * JRS 后合并，直接用 m.status 会把 JRKAN 更准的状态覆盖成滞后值。
     *
     * 后续 correctLiveStatuses 还会用详情页/时钟再校正一遍，这里只是别把
     * 已经更准的状态改差。
     */
    private fun betterStatus(a: MatchStatus, b: MatchStatus): MatchStatus =
        if (statusRank(b) > statusRank(a)) b else a

    private fun statusRank(s: MatchStatus): Int = when (s) {
        MatchStatus.FINISHED -> 3
        MatchStatus.LIVE -> 2
        MatchStatus.UPCOMING -> 1
    }

    /**
     * 归一化队名用于跨源匹配（去掉空白差异），并带上开赛日期防止同队重复场次被合并。
     *
     * ⚠️ 主客队必须**排序后**拼接：实测 JRS 列表与 JRKAN 的主客顺序相反
     * （JRS "骑士 vs 76人" / JRKAN "76人 vs 骑士"），按固定 "home|away" 排列
     * 会让两源的 key 永远对不上——同一场被拆成两张卡，点进去各只有半边信号。
     * 排序后顺序无关，仅日期+队名对决定归属（背靠背两场靠日期区分，仍然安全）。
     */
    private fun normalizeKey(m: Match): String {
        val day = m.startAtMillis?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        } ?: m.timeLabel.substringBefore(' ').ifBlank { "?" }
        val (t1, t2) = listOf(m.homeTeam, m.awayTeam).map { it.replace(WHITESPACE, "") }.sorted()
        return "$day|$t1|$t2"
    }

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
        synchronized(detailCache) {
            // key 是 detailId 而不是 URL：详情页现在有多个候选域名
            // （[SourceEndpoints.jrsDetailUrls]），按写死的某个域名去 remove
            // 会清不掉上次从镜像域名缓存的那一份 —— 按钮变摆设且不报错
            // （review/02-data.md P2-6）。
            detailCache.remove(detailId)
        }
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
            // ⚠️ 必须走缓存版 fetchJrsDetail：
            // 列表状态校正刚拉过同一个详情页，不复用就是白打源站一次。
            val html = fetchJrsDetail(id, fetch)
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
        val jrkanSignals = jrkanSources.flatMapIndexed { index, source ->
            expandJrkanPlayPage(source, "${match.id}-jr$index", fetch)
        }
        // 展开失败时退回列表页给的播放页，至少还能用 WebView 打开
        val jrkanFinal = jrkanSignals.ifEmpty { jrkanSources }

        // ⚠️ 最后一道防线：保证信号 id 全局唯一。
        // 播放器把 id 当 LazyColumn 的 key，重复 key 直接 IllegalStateException 闪退
        // （这个仓库已经崩过两次）。
        // 上游的 id 生成（JrkanParser / JrsParser）已经各自带来源维度了，
        // 但那是"约定"不是"保证"——这里再兜一次底，
        // 以后任何一处改了 id 规则都不会把 App 弄崩。
        val deduped = (jrsSignals + jrkanFinal)
            .distinctBy { it.pageUrl }
            .mapIndexed { index, s -> s.copy(id = "${s.id}#$index") }

        Detail(deduped, correction)
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
    ): List<StreamSource> {
        val candidates = SourceEndpoints.playPageCandidates(
            source.pageUrl,
            synchronized(listFailedHosts) { listFailedHosts.toSet() },
        )
        for (url in candidates) {
            val html = when (val r = fetch(url, http.refererOf(url))) {
                is FetchResult.Ok -> r.body
                is FetchResult.Rejected -> {
                    // 403/429 是限流信号：立即停整个源，不试镜像。
                    // 镜像与主域名同属一个站点家族，大概率共享限流窗口。
                    Log.w("MatchRepo", "播放页被拒绝(HTTP ${r.code})，放弃重试: ${redactUrl(url)}")
                    synchronized(listFailedHosts) { listFailedHosts += hostOf(url) }
                    return emptyList()
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
            if (channels.isNotEmpty()) return channels
            // 拿得到正文但一条线路都解析不出来：这个页面本身可能已经改版，
            // 换下一个候选域名试（镜像有时还留着一份旧结构）。
        }
        return emptyList()
    }

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
