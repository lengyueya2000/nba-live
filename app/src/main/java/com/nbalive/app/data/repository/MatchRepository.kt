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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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
    }

    /** 拉取并合并两个源的全部赛事。一个源抛异常不能带走另一个源的好数据。 */
    suspend fun loadMatches(): Result<List<Match>> = withContext(Dispatchers.IO) {
        runCatching {
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
                merge(jrs, jrkan)
            }
        }
    }

    private suspend fun fetchJrs(): Result<List<Match>> = runCatching {
        val html = http.get(JRS_LIST_URL) ?: return@runCatching emptyList()
        jrsParser.parseList(html)
    }

    private suspend fun fetchJrkan(): Result<List<Match>> = runCatching {
        val js = http.get(JRKAN_LIST_JS) ?: return@runCatching emptyList()
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
                    // JRS 的状态更准（能区分"直播中"）
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
            .sortedWith(
                // 直播中排最前；其余按开赛时间，解析不出时间的排最后
                compareBy<Match> { if (it.status == MatchStatus.LIVE) 0 else 1 }
                    .thenBy { it.startAtMillis ?: Long.MAX_VALUE }
                    .thenBy { it.leagueRawName }
            )
    }

    /** 归一化队名用于跨源匹配（去掉空白差异），并带上开赛日期防止同队重复场次被合并。 */
    private fun normalizeKey(m: Match): String {
        val day = m.startAtMillis?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        } ?: m.timeLabel.substringBefore(' ').ifBlank { "?" }
        return "${day}|${m.homeTeam.replace(Regex("\\s+"), "")}|${m.awayTeam.replace(Regex("\\s+"), "")}"
    }

    /**
     * 拉取某场赛事的全部信号。
     *
     * 按"信号来自哪个源站"分派，而不是靠 Match.id 前缀猜——
     * 合并后的赛事可能同时携带两个源的信号。
     *
     * JRKAN：列表页只给一个播放页，真正的多条线路在播放页内部，需要展开。
     * JRS：信号在详情页的 a.toLive 里。
     */
    suspend fun loadSignals(match: Match): List<StreamSource> = withContext(Dispatchers.IO) {
        val jrsSignals = match.jrsDetailId?.let { id ->
            val detailUrl = "https://jrszbt.com/live/$id.html"
            val html = http.get(detailUrl, JRS_LIST_URL)
            if (html != null) jrsParser.parseDetail(html, match) else emptyList()
        } ?: emptyList()

        val jrkanSources = match.sources.filter { it.provider == Provider.JRKAN }
        val jrkanSignals = jrkanSources.mapNotNull { source ->
            val html = http.get(source.pageUrl) ?: return@mapNotNull null
            jrkanParser.parseChannels(html, match.id)
        }.flatten()
        // 展开失败时退回列表页给的播放页，至少还能用 WebView 打开
        val jrkanFinal = jrkanSignals.ifEmpty { jrkanSources }

        (jrsSignals + jrkanFinal).distinctBy { it.pageUrl }
    }

    /**
     * 把播放页解析为可直连的 m3u8，并**实际拉一次清单做校验**。
     *
     * 只解析出地址不够——地址可能已过期或需要特定 Referer。
     * 这里确认返回体里含 `#EXTM3U` 才认为该线路可用，避免进播放器黑屏。
     */
    suspend fun resolveStream(source: StreamSource): StreamUrlResolver.Resolved? =
        withContext(Dispatchers.IO) {
            if (!source.isDirectHls) return@withContext null
            val resolved = resolver.resolve(source.pageUrl) { url ->
                http.get(url, http.refererOf(url))
            } ?: return@withContext null

            val playlist = http.get(resolved.m3u8Url, resolved.referer)
            if (playlist == null) {
                Log.w("MatchRepo", "m3u8 fetch null: ${resolved.m3u8Url}")
                return@withContext null
            }
            if (!playlist.contains("#EXTM3U")) {
                Log.w("MatchRepo", "m3u8 not a playlist (${playlist.length}): ${playlist.take(80)}")
                return@withContext null
            }
            Log.d("MatchRepo", "m3u8 OK: ${resolved.m3u8Url}")

            resolved
        }

    /** 按联赛过滤。 */
    fun filter(matches: List<Match>, league: League): List<Match> = when (league) {
        League.ALL -> matches
        League.NBA -> matches.filter { it.league == League.NBA }
        League.CBA -> matches.filter { it.league == League.CBA }
        League.OTHER -> matches.filter { it.league == League.OTHER }
    }
}
