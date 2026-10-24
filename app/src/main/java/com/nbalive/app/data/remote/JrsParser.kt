package com.nbalive.app.data.remote

import android.util.Log
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

        /** 队徽相对路径的补全基准（源站目前给的是绝对 URL，这里只是兜底） */
        private const val SITE_ORIGIN = "https://jrszbt.com"

        private const val TAG = "JrsParser"
    }

    fun parseList(html: String): List<Match> {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<Match>()

        doc.select("div.list").forEach { group ->
            val dateLabel = group.selectFirst("p.list-title")?.text()?.trim()
            group.select("a.list-con").forEach { anchor ->
                val path = anchor.attr("href")
                val home = anchor.selectFirst(".list-team-con:nth-of-type(1) .list-team-name")?.text()?.trim()
                    ?: anchor.select(".list-team-name").firstOrNull()?.text()?.trim()
                val away = anchor.select(".list-team-name").getOrNull(1)?.text()?.trim()
                if (home.isNullOrBlank() || away.isNullOrBlank()) {
                    // 上游改版时这里会静默吞掉整场比赛，必须留痕
                    Log.w(TAG, "JRS 列表项缺队名，跳过：$path")
                    return@forEach
                }

                val leagueRaw = anchor.selectFirst(".list-info-league")?.text()?.trim().orEmpty()
                val timeRaw = anchor.selectFirst(".list-info-time")?.text()?.trim().orEmpty()
                // 只接受 /live/{数字}.html；javascript: 等异常 href 会得到空 id，
                // 而空 id 会让 LazyVerticalGrid 的 key 重复并崩溃，必须跳过
                val id = Regex("""/live/(\d+)\.html""").find(path)?.groupValues?.get(1)
                if (id == null) {
                    Log.w(TAG, "JRS 列表项 href 不合预期，跳过：$path")
                    return@forEach
                }

                val logos = anchor.select(".list-team-image")
                result += Match(
                    id = "jrs-$id",
                    league = League.fromSource(leagueRaw),
                    leagueRawName = leagueRaw,
                    homeTeam = home,
                    awayTeam = away,
                    homeLogo = logos.getOrNull(0)?.attr("src")?.takeIf { it.isNotBlank() }?.let { absUrl(it) },
                    awayLogo = logos.getOrNull(1)?.attr("src")?.takeIf { it.isNotBlank() }?.let { absUrl(it) },
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

    /** 队徽地址补全：相对路径按源站 origin 补齐 */
    private fun absUrl(raw: String): String? = HttpClient.absUrl(raw, SITE_ORIGIN)

    /**
     * 详情页里的计划开赛时间（如 "07:00"）。
     * JRS 列表页的"直播中"会因缓存严重滞后（实测结束 3 小时仍标直播中），
     * 详情页的时间是校正真实状态的依据。
     */
    fun parseScheduledTime(html: String): String? =
        Regex("""match-info-time"[^>]*>\s*(\d{1,2}:\d{2})""").find(html)?.groupValues?.get(1)

    /**
     * 用计划开赛时间校正"直播中"：
     * - 开赛已过 3.5 小时（一场篮球赛的合理上限）=> 已结束
     * - 距开赛还有 15 分钟以上 => 源站提前误标，实际未开赛
     * 同时回填真实开赛时间戳（列表页"直播中"的行没有 HH:mm，startAtMillis 只是 00:00 兜底）。
     * 无法判断时返回 null（不猜测、不误杀）。
     */
    fun correctedStatus(match: Match, scheduledTime: String?): StatusCorrection? =
        correctedStatus(match, scheduledTime, java.time.ZonedDateTime.now())

    /**
     * 可注入"当前时间"的重载，供单元测试把跨零点等时间相关的分支固定下来。
     */
    internal fun correctedStatus(
        match: Match,
        scheduledTime: String?,
        now: java.time.ZonedDateTime,
    ): StatusCorrection? {
        if (match.status != MatchStatus.LIVE || scheduledTime == null) return null
        val day = match.startAtMillis?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        } ?: return null
        val hm = Regex("""(\d{1,2}):(\d{2})""").find(scheduledTime) ?: return null
        val hour = hm.groupValues[1].toIntOrNull() ?: return null
        val minute = hm.groupValues[2].toIntOrNull() ?: return null
        val base = runCatching {
            day.atTime(hour, minute).atZone(java.time.ZoneId.systemDefault())
        }.getOrNull() ?: return null

        fun statusAt(start: java.time.ZonedDateTime): MatchStatus = when {
            now.isAfter(start.plusHours(3).plusMinutes(30)) -> MatchStatus.FINISHED
            now.isBefore(start.minusMinutes(15)) -> MatchStatus.UPCOMING
            else -> MatchStatus.LIVE
        }

        // 跨零点的场次：源站可能把 00:30 的比赛归到"前一天"的分组，
        // 按分组日期直接算会凭空"结束 24 小时前"，整场状态判错。
        //
        // 但要区分得靠"源站说直播中"这个前提：只有当**按次日算是直播中**时才改用次日。
        // 否则"今天凌晨 01:28 开赛、已经过去 20 小时"这种正常情况会被误改成
        // "明天凌晨开赛"，把一场早就结束的比赛判成未开赛。
        var start = base
        if (hour < 6) {
            val nextDay = base.plusDays(1)
            if (statusAt(base) == MatchStatus.FINISHED && statusAt(nextDay) == MatchStatus.LIVE) {
                start = nextDay
            }
        }

        val startMillis = start.toInstant().toEpochMilli()
        return StatusCorrection(statusAt(start), startMillis)
    }

    /** 详情页校正结果：真实状态 + 真实开赛时间 */
    data class StatusCorrection(
        val status: MatchStatus,
        val startAtMillis: Long,
    )

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
