package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * JRS 源站（jrszbt.com）解析器。
 *
 * 列表页结构（已验证）：
 *   .list-title            -> 日期分组标题
 *   a.list-con            -> 单场赛事
 *     .list-team-image     -> 队徽
 *     .list-team-name      -> 队名
 *     .list-info-league    -> 联赛
 *     .list-info-time      -> 时间 / "直播中"
 *
 * 详情页结构（已验证）：
 *   .match-team-image / .match-team-name  -> 队伍
 *   .match-info-league / .match-info-time -> 联赛 / 时间
 *   iframe#pp[src]                        -> 默认播放线路
 *   a.toLive[data-url]                    -> 可切换信号（data-url 为播放页地址）
 *   a.toLive[href]                        -> 外部/备用地址（非播放线路，需过滤）
 */
class JrsParser {

    companion object {
        /** 源站使用的播放器域名特征（已验证：play.sportsteam368.com 等）。 */
        private val PLAYER_HOST_MARKERS = listOf("sportsteam", "jrkan", "jrs")
    }

    fun parseList(html: String): List<Match> {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<Match>()

        doc.select("div.list").forEach { group ->
            val dateLabel = group.selectFirst("p.list-title")?.text()?.trim()
            group.select("a.list-con").forEach { anchor ->
                val home = anchor.selectFirst(".list-team-con:nth-of-type(1) .list-team-name")?.text()?.trim()
                    ?: anchor.select(".list-team-name").firstOrNull()?.text()?.trim()
                val away = anchor.select(".list-team-name").getOrNull(1)?.text()?.trim()
                if (home.isNullOrBlank() || away.isNullOrBlank()) return@forEach

                val leagueRaw = anchor.selectFirst(".list-info-league")?.text()?.trim().orEmpty()
                val timeRaw = anchor.selectFirst(".list-info-time")?.text()?.trim().orEmpty()
                val path = anchor.attr("href")
                // 只接受 /live/{数字}.html；javascript: 等异常 href 会得到空 id，
                // 而空 id 会让 LazyVerticalGrid 的 key 重复并崩溃，必须跳过
                val id = Regex("""/live/(\d+)\.html""").find(path)?.groupValues?.get(1) ?: return@forEach

                val logos = anchor.select(".list-team-image")
                result += Match(
                    id = "jrs-$id",
                    league = League.fromSource(leagueRaw),
                    leagueRawName = leagueRaw,
                    homeTeam = home,
                    awayTeam = away,
                    homeLogo = logos.getOrNull(0)?.attr("src")?.takeIf { it.isNotBlank() },
                    awayLogo = logos.getOrNull(1)?.attr("src")?.takeIf { it.isNotBlank() },
                    timeLabel = timeRaw,
                    startAtMillis = parseDateTime(dateLabel, timeRaw),
                    status = MatchStatus.fromSource(timeRaw),
                    // 列表页拿不到 JRS 的信号数，-1 表示"未解析"，界面不显示徽标
                    signalCount = -1,
                    sources = emptyList(),
                    jrsDetailId = id,
                )
            }
        }
        return result
    }

    /** 解析详情页，取出全部播放信号。 */
    fun parseDetail(html: String, match: Match): List<StreamSource> {
        val doc = Jsoup.parse(html)
        val seen = linkedSetOf<String>()
        val sources = mutableListOf<StreamSource>()

        doc.select("a.toLive").forEach { a ->
            // 源站里：信号1 用 data-url，信号2/3 只用 href 指向播放页，
            // "更多信号 / 备用地址" 是指向外站推广页的链接，必须过滤。
            val candidate = a.attr("data-url").trim().ifBlank { a.attr("href").trim() }
            if (!isPlayableUrl(candidate)) return@forEach
            if (!seen.add(candidate)) return@forEach

            sources += StreamSource(
                id = "${match.id}-s${sources.size}",
                label = a.text().trim().ifBlank { "信号${sources.size + 1}" },
                provider = Provider.JRS,
                pageUrl = candidate,
            )
        }

        // 兜底：没有可用信号时用 iframe 的 src
        if (sources.isEmpty()) {
            val iframeSrc = doc.selectFirst("iframe#pp")?.attr("src")?.trim()
            if (!iframeSrc.isNullOrBlank() && isPlayableUrl(iframeSrc)) {
                sources += StreamSource(
                    id = "${match.id}-s0",
                    label = "信号1",
                    provider = Provider.JRS,
                    pageUrl = iframeSrc,
                )
            }
        }
        return sources
    }

    /**
     * 判断是否源站自身的播放页（而不是推广/备用外链）。
     * 播放页特征：路径含 /play/，且主机是已知播放器域名。
     */
    private fun isPlayableUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        if (!url.contains("/play/")) return false
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return PLAYER_HOST_MARKERS.any { host.contains(it, ignoreCase = true) }
    }

    /** "2026-10-21" + "19:35" -> epoch millis；失败返回 null。 */
    private fun parseDateTime(dateLabel: String?, timeLabel: String): Long? {
        if (dateLabel.isNullOrBlank()) return null
        // 宽松提取日期：容忍 "2026-10-21 星期三"、"2026/10/21" 等变体
        val dm = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""").find(dateLabel) ?: return null
        val date = runCatching {
            java.time.LocalDate.of(dm.groupValues[1].toInt(), dm.groupValues[2].toInt(), dm.groupValues[3].toInt())
        }.getOrNull() ?: return null
        val hm = Regex("""(\d{1,2}):(\d{2})""").find(timeLabel)
        val h = hm?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = hm?.groupValues?.get(2)?.toIntOrNull() ?: 0
        // 直播中行没有 HH:mm：退回当天 00:00，至少分组/排序落在正确的一天
        return runCatching {
            date.atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
