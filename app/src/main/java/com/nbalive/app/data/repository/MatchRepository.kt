package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.remote.HttpClient
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
    companion object {
        const val JRS_LIST_URL = "https://jrszbt.com/"
        const val JRKAN_LIST_JS = "https://im-imgs-bucket.oss-accelerate.aliyuncs.com/index.js?t_5"

        /** 状态校正时并发拉详情页的上限：防止一次刷新把源站打爆。 */
        private const val MAX_DETAIL_CONCURRENCY = 4
    }

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
    suspend fun loadMatches(): Result<List<Match>> = withContext(Dispatchers.IO) {
        catching {
            supervisorScope {
                val jrsDeferred = async { fetchJrs() }
                val jrkanDeferred = async { fetchJrkan() }
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
                sort(correctLiveStatuses(merge(jrs, jrkan)))
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
    internal suspend fun correctLiveStatuses(matches: List<Match>): List<Match> = coroutineScope {
        val live = matches.filter { it.status == MatchStatus.LIVE }
        if (live.isEmpty()) return@coroutineScope matches

        // JRS：详情页校正。并发数要封顶——直播中的场次可能十几场，
        // 一次全开就是把源站当 DDoS 源打；同时单个详情失败不能连累其它场次。
        val gate = Semaphore(MAX_DETAIL_CONCURRENCY)
        val fixes = live.filter { it.jrsDetailId != null }.map { m ->
            async {
                gate.withPermit {
                    catching {
                        val html = http.get("https://jrszbt.com/live/${m.jrsDetailId}.html", JRS_LIST_URL)
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

    private suspend fun fetchJrs(): Result<List<Match>> = catching {
        val html = http.get(JRS_LIST_URL) ?: return@catching emptyList()
        jrsParser.parseList(html)
    }

    private suspend fun fetchJrkan(): Result<List<Match>> = catching {
        val js = http.get(JRKAN_LIST_JS) ?: return@catching emptyList()
        jrkanParser.parseList(js)
    }

    /**
     * 合并两个源：按 "日期|主队|客队" 归一化 key 归并，信号列表合并去重。
     *
     * 日期必须参与 key：同一对球队背靠背/系列赛会重复出现，
     * 只按队名归并会把真实存在的第二场直接吞掉。
     */
    private fun merge(jrs: List<Match>, jrkan: List<Match>): List<Match> {
        val merged = LinkedHashMap<String, Match>()

        fun mergeOne(m: Match) {
            val key = normalizeKey(m)
            // id 兜底唯一化：LazyVerticalGrid 的 key 重复会直接崩溃，
            // 宁可 id 加后缀，也绝不丢比赛
            val unique = if (merged.values.any { it.id == m.id }) "${m.id}#${merged.size}" else m.id
            val existing = merged[key]
            merged[key] = if (existing == null) {
                m.copy(id = unique)
            } else {
                val sources = (existing.sources + m.sources).distinctBy { it.pageUrl }
                existing.copy(
                    // JRS 的状态更准（能区分"直播中"）；
                    // 即便此处保留了滞后的 LIVE，correctLiveStatuses 也会用详情页/时钟校正
                    status = if (m.status != MatchStatus.UPCOMING) m.status else existing.status,
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

    /** 归一化队名用于跨源匹配（去掉空白差异），并带上开赛日期防止同队重复场次被合并。 */
    private fun normalizeKey(m: Match): String {
        val day = m.startAtMillis?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        } ?: m.timeLabel.substringBefore(' ').ifBlank { "?" }
        return "${day}|${m.homeTeam.replace(Regex("\\s+"), "")}|${m.awayTeam.replace(Regex("\\s+"), "")}"
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
        loadSignals(match) { url, referer -> http.get(url, referer) }

    /**
     * 可注入 fetch 的实现，供单元测试覆盖"多个播放页展开后 id 是否唯一"这类逻辑。
     */
    internal suspend fun loadSignals(
        match: Match,
        fetch: suspend (String, String?) -> String?,
    ): Detail = withContext(Dispatchers.IO) {
        var correction: JrsParser.StatusCorrection? = null

        val jrsSignals = match.jrsDetailId?.let { id ->
            val detailUrl = "https://jrszbt.com/live/$id.html"
            val html = fetch(detailUrl, JRS_LIST_URL)
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
            val html = fetch(source.pageUrl, http.refererOf(source.pageUrl))
                ?: return@flatMapIndexed emptyList()
            jrkanParser.parseChannels(html, "${match.id}-jr$index")
        }
        // 展开失败时退回列表页给的播放页，至少还能用 WebView 打开
        val jrkanFinal = jrkanSignals.ifEmpty { jrkanSources }

        Detail((jrsSignals + jrkanFinal).distinctBy { it.pageUrl }, correction)
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

            for (candidate in resolved.m3u8Candidates) {
                val playlist = http.get(candidate, resolved.referer, fastFail = true)
                if (playlist != null && playlist.contains("#EXTM3U")) {
                    Log.d("MatchRepo", "m3u8 OK: $candidate")
                    return@withContext resolved.copy(m3u8Url = candidate)
                }
                Log.w("MatchRepo", "m3u8 candidate rejected: $candidate")
            }
            Log.w("MatchRepo", "all m3u8 candidates failed: ${resolved.m3u8Candidates}")
            null
        }
}
