package com.nbalive.app.data.remote

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource

/**
 * JRKAN 源站（m.jrkan.com）解析器。
 *
 * 当前赛事列表由 `https://im-imgs-bucket.oss-accelerate.aliyuncs.com/index.js?t_5` 提供，
 * 内容是一串 `document.write('<ul class="item play ...">...</ul>')`，每场一个 ul。
 *
 * 已验证的单场结构：
 *   ul[data-lid="eventId,sportId,leagueId"][data-stype="zqlq"]
 *     li.lab_events > span.name        -> 联赛名
 *     li.lab_time                      -> "10-21 19:35"
 *     li.lab_team_home > strong.name   -> 主队名
 *     li.lab_team_home img.avatar[src] -> 主队队徽
 *     li.lab_team_away > strong.name   -> 客队名
 *     a[data-urlid] 内 getPlayUrl("lineN", "playId") -> 播放线路
 *
 * 播放地址规则（已实测）：
 *   赛事列表里的 getPlayUrl("lineN", playId) 只是同一场直播的三条镜像，
 *   其中只有 http://play.sportsteam356.com 仍然存活（另外两个域名已下线，
 *   会返回"备用网址"提示页）。真正可选的线路在播放页内部的 sub_playlist 里：
 *     /play/steam{playId}.html
 *       -> iframe /play/sm.html?id={innerId}&id2=
 *       -> /play/{innerId}.html
 *       -> iframe //demo.songzenet.com/msss.html?id={encodeURIComponent(m3u8)}
 */
class JrkanParser {

    companion object {
        /** 实测存活的播放主机；失效域名不再使用。 */
        const val PLAY_HOST = "http://play.sportsteam356.com"

        /** 线路名里出现这些词就从界面名称中隐去（主播个人推流，不展示其名义）。 */
        private val EXCLUDED_CHANNEL_KEYWORDS = listOf("主播", "解说", "美女", "聊天")
    }

    fun parseList(js: String): List<Match> {
        val result = mutableListOf<Match>()

        // 拆出每个 <ul class="item play ..."> ... </ul> 块
        val blockRegex = Regex("""(?s)<ul class="item play.*?</ul>""")
        blockRegex.findAll(js).forEach { m ->
            val block = m.value
            val lid = Regex("""data-lid="(\d+),(\d+),(\d+)"""").find(block)?.groupValues ?: return@forEach
            val eventId = lid[1]
            val sportId = lid[2]

            val leagueRaw = Regex("""lab_events"[^>]*><span class="name">([^<]*)""")
                .find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val timeRaw = Regex("""lab_time">([^<]*)""").find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val home = Regex("""lab_team_home"><strong class="name">([^<]*)""")
                .find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val away = Regex("""lab_team_away"><strong class="name">([^<]*)""")
                .find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (home.isBlank() || away.isBlank()) return@forEach

            // 锚定到本方 li 内（跨行匹配），避免主队无图时误拿客队队徽
            val homeLogo = Regex("""lab_team_home[^>]*>.*?<img[^>]*src="([^"]+)""", RegexOption.DOT_MATCHES_ALL)
                .find(block.substringBefore("lab_team_away"))?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val awayLogo = Regex("""lab_team_away[^>]*>.*?<img[^>]*src="([^"]+)""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

            // 列表页只给一个 playId；具体线路在播放页内部，进详情页再展开。
            val playId = Regex("""getPlayUrl\("line\d",\s*"(\d+)"\)""")
                .find(block)?.groupValues?.get(1)

            val sources = if (playId == null) {
                emptyList()
            } else {
                listOf(
                    StreamSource(
                        id = "jrkan-$eventId-s0",
                        label = "信号1",
                        provider = Provider.JRKAN,
                        pageUrl = "$PLAY_HOST/play/steam$playId.html",
                    ),
                )
            }

            result += Match(
                id = "jrkan-$eventId",
                league = League.fromSource(leagueRaw),
                leagueRawName = leagueRaw,
                homeTeam = home,
                awayTeam = away,
                homeLogo = homeLogo,
                awayLogo = awayLogo,
                timeLabel = timeRaw,
                startAtMillis = parseDateTime(timeRaw),
                status = parseStatus(timeRaw),
                signalCount = sources.size,
                sources = sources,
            )
        }
        return result
    }

    /**
     * JRKAN 的状态判定。
     *
     * ⚠️ `li.lab_vs active` / `spin_ant` 是**静态模板类**，实测源站 75/75 场次恒带
     * （含未开赛 3 天后的、昨天已结束的），零信息量 —— 曾拿它判"进行中"，
     * 导致列表每一行都亮红"直播中"。真正的状态只能从时间文字 + 本地时钟推：
     *  - 文字有明确关键词（已结束/直播中）-> 照采
     *  - 文字是 "MM-dd HH:mm" -> 开赛已过 3.5 小时=已结束，距开赛 >15 分钟=未开赛，否则=进行中
     *  （与 JRS 详情页校正同一套窗口）
     */
    private fun parseStatus(timeRaw: String): MatchStatus {
        val explicit = MatchStatus.fromSource(timeRaw)
        if (explicit != MatchStatus.UPCOMING) return explicit

        val startMillis = parseDateTime(timeRaw) ?: return MatchStatus.UPCOMING
        val start = java.time.Instant.ofEpochMilli(startMillis)
            .atZone(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        return when {
            now.isAfter(start.plusHours(3).plusMinutes(30)) -> MatchStatus.FINISHED
            now.isBefore(start.minusMinutes(15)) -> MatchStatus.UPCOMING
            else -> MatchStatus.LIVE
        }
    }

    /**
     * 解析播放页里的线路列表（sub_playlist）。
     *
     * 结构：
     *   <a class="item ..." data-play="/play/sm.html?id=439&id2="><strong>主播解说①</strong></a>
     *
     * 处理策略（按用户要求）：
     *  1. **删除**线路名含"主播/解说"等字样的线路（主播个人推流，界面不呈现）
     *  2. 其余线路保留；能直连 m3u8 的排前面，默认选中即可播
     */
    fun parseChannels(html: String, matchId: String): List<StreamSource> {
        val seen = linkedSetOf<String>()
        val raw = mutableListOf<Pair<String, String>>()  // path to rawLabel

        Regex("""<a[^>]*data-play="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val path = m.groupValues[1].trim()
                if (!path.startsWith("/play/")) return@forEach
                // 源站里有若干空占位（data-play="=&id2="），要跳过
                if (Regex("""[?&]id=(&|$)""").containsMatchIn(path)) return@forEach
                if (!seen.add(path)) return@forEach

                val rawLabel = Regex("""<strong>([^<]+)</strong>""")
                    .find(m.groupValues[2])?.groupValues?.get(1)?.trim().orEmpty()
                // 涉及主播/解说的线路直接过滤掉，不进入列表
                if (EXCLUDED_CHANNEL_KEYWORDS.any { rawLabel.contains(it) }) return@forEach
                raw += path to rawLabel
            }

        // 可直连的排前面，保证默认选中项就是能播的；
        // 编号在排序后重新分配，否则界面会出现"线路2、线路3、线路1"
        return raw.sortedByDescending { (path, _) -> path.contains("/play/sm.html") }
            .mapIndexed { index, (path, rawLabel) ->
                StreamSource(
                    id = "$matchId-c$index",
                    label = displayLabel(rawLabel, index),
                    provider = Provider.JRKAN,
                    pageUrl = PLAY_HOST + path,
                )
            }
    }

    /** 保留源站原名；清洗后为空则退回统一命名。 */
    private fun displayLabel(raw: String, index: Int): String {
        if (raw.isBlank()) return "线路${index + 1}"
        return raw.trim().ifBlank { "线路${index + 1}" }
    }

    /**
     * "10-21 19:35" -> epoch millis。时间串没有年份：在 ±6 个月窗口内取最近的一次。
     *
     * ⚠️ 两个方向都要判：
     * 只看"未来超过 6 个月"是不够的——例如今天 2026-10-22，源站给出 "01-05"，
     * 按今年算得到 2026-01-05（已经过去 9 个多月），真实日期却是 2027-01-05（未来）。
     * 那样会把 1~6 月的赛事归到去年，跨源合并键（用 LocalDate）对不上，
     * 同一场比赛在两个源下就合不起来，信号数也被腰斩。
     */
    private fun parseDateTime(raw: String): Long? {
        val m = Regex("""(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})""").find(raw) ?: return null
        val month = m.groupValues[1].toIntOrNull() ?: return null
        val day = m.groupValues[2].toIntOrNull() ?: return null
        val hour = m.groupValues[3].toIntOrNull() ?: return null
        val minute = m.groupValues[4].toIntOrNull() ?: return null
        val now = java.time.LocalDate.now()
        return runCatching {
            var date = java.time.LocalDate.of(now.year, month, day)
            // 在 ±6 个月窗口内取最近的一次：过去太久 -> 顺延一年，未来太久 -> 倒退一年
            if (date.isBefore(now.minusMonths(6))) date = date.plusYears(1)
            if (date.isAfter(now.plusMonths(6))) date = date.minusYears(1)
            date.atTime(hour, minute)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        }.getOrNull()
    }
}
