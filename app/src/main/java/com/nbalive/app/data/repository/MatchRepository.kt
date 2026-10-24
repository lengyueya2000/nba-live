package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.data.remote.debugLog
import com.nbalive.app.data.remote.redactUrl
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
     * 可注入 fetch 的实现，供单元测试覆盖"单源瞬时失败要重试"这类逻辑。
     */
    internal suspend fun loadMatches(
        fetch: suspend (String, String?) -> String?,
    ): Result<List<Match>> = loadMatchesInternal { url, _ ->
        val body = fetch(url, null)
        if (body != null) FetchResult.Ok(body) else FetchResult.Unreachable(null)
    }

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
                    Log.w("MatchRepo", "JRS fetch failed", it)
                    emptyList()
                }
                val jrkan = jrkanDeferred.await().getOrElse {
                    Log.w("MatchRepo", "JRKAN fetch failed", it)
                    emptyList()
                }
                if (jrs.isEmpty() && jrkan.isEmpty()) {
                    // 两边都空：大概率是网络/源站故障，而不是"今天真没比赛"
                    throw IOException("两个数据源都没有返回赛事，请检查网络")
                }
                // 先校正再排序：源站列表的"直播中"会滞后，不修就全堆在最前面
                // ⚠️ 校正拉详情页与列表 fetch 走**同一个缓存**：
                // 否则"刷新列表"和"点进播放页"会把同一个详情页拉两遍。
                sort(correctLiveStatuses(merge(jrs, jrkan), fetch))
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
        val toFix = live.filter { it.jrsDetailId != null }
        val skipped = toFix.size - MAX_DETAIL_FETCH
        if (skipped > 0) {
            Log.w("MatchRepo", "详情页校正超预算：$skipped 场跳过（本次最多 $MAX_DETAIL_FETCH 场）")
        }
        val fixes = toFix.take(MAX_DETAIL_FETCH).map { m ->
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
                    status = statusByClock(m.startAtMillis),
                    startAtMillis = m.startAtMillis,
                )
                else -> m
            }
        }
    }

    /** 计划开赛时间 -> 状态：超 3.5 小时=已结束，早于 15 分钟=未开赛，窗口内=进行中。 */
    private fun statusByClock(startAtMillis: Long): MatchStatus {
        val start = java.time.Instant.ofEpochMilli(startAtMillis)
            .atZone(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        return when {
            now.isAfter(start.plusHours(3).plusMinutes(30)) -> MatchStatus.FINISHED
            now.isBefore(start.minusMinutes(15)) -> MatchStatus.UPCOMING
            else -> MatchStatus.LIVE
        }
    }

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
     */
    private suspend fun fetchJrsDetail(
        detailId: String,
        fetch: suspend (String, String?) -> FetchResult,
        forceRefresh: Boolean = false,
    ): String? {
        val url = "https://jrszbt.com/live/$detailId.html"
        // ⚠️ 用**单调时钟**（nanoTime）而不是 currentTimeMillis：
        // 用户把系统时间往前拨，"now - cachedAt" 会变成负数，
        // 负数 < TTL 恒成立 → 缓存永不过期（源站改版了也看不到新内容）。
        // nanoTime 不受系统时间调整影响。
        // （不用 android.os.SystemClock.elapsedRealtime：它在 JVM 单元测试里
        //   返回默认值 0，测试就没法验证 TTL 过期了。）
        val now = System.nanoTime() / 1_000_000L
        if (!forceRefresh) {
            synchronized(detailCache) {
                val hit = detailCache[url]
                if (hit != null) {
                    if (now - hit.first < DETAIL_CACHE_TTL_MS) return hit.second
                    detailCache.remove(url)
                }
            }
        }
        val body = when (val r = fetch(url, JRS_LIST_URL)) {
            is FetchResult.Ok -> r.body
            else -> null
        }
        if (!body.isNullOrBlank()) {
            synchronized(detailCache) {
                detailCache[url] = now to body
            }
        }
        return body
    }

    companion object {
        const val JRS_LIST_URL = "https://jrszbt.com/"
        const val JRKAN_LIST_JS = "https://im-imgs-bucket.oss-accelerate.aliyuncs.com/index.js?t_5"

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
         */
        private const val MAX_DETAIL_FETCH = 8

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
     * 详情页短缓存：URL -> (缓存时刻, 正文)。
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
    private fun sort(matches: List<Match>): List<Match> =
        matches.sortedWith(
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
        url: String,
    ): String? {
        repeat(LIST_FETCH_ATTEMPTS) { attempt ->
            when (val result = fetch(url, null)) {
                is FetchResult.Ok -> return result.body
                is FetchResult.Rejected -> {
                    // 明确拒绝：不重试。这是最容易被忽略的"限流放大器"
                    Log.w("MatchRepo", "源站拒绝请求(HTTP ${result.code})，放弃重试: ${redactUrl(url)}")
                    return null
                }
                is FetchResult.Unreachable -> {
                    if (attempt < LIST_FETCH_ATTEMPTS - 1) {
                        delay(LIST_RETRY_BACKOFF_MS * (attempt + 1))
                    }
                }
            }
        }
        return null
    }

    private suspend fun fetchJrs(fetch: suspend (String, String?) -> FetchResult): Result<List<Match>> = catching {
        val html = fetchListWithRetry(fetch, JRS_LIST_URL) ?: return@catching emptyList()
        jrsParser.parseList(html)
    }

    private suspend fun fetchJrkan(fetch: suspend (String, String?) -> FetchResult): Result<List<Match>> = catching {
        val js = fetchListWithRetry(fetch, JRKAN_LIST_JS) ?: return@catching emptyList()
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
    internal fun mergeForTest(jrs: List<Match>, jrkan: List<Match>): List<Match> = merge(jrs, jrkan)

    private fun merge(jrs: List<Match>, jrkan: List<Match>): List<Match> {
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

        return merged.values
            // 产品只做 NBA + CBA：源站混入的足球/外联赛在这里统一丢弃
            .filter { it.league in League.supported }
            .distinctBy { it.id }
            .toList()
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
            detailCache.remove("https://jrszbt.com/live/$detailId.html")
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
            val html = when (val r = fetch(source.pageUrl, http.refererOf(source.pageUrl))) {
                is FetchResult.Ok -> r.body
                else -> null
            } ?: return@flatMapIndexed emptyList()
            jrkanParser.parseChannels(html, "${match.id}-jr$index")
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
