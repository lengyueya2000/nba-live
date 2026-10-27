package com.nbalive.app.data.remote

import android.util.Log
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import org.jsoup.Jsoup

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

        /** 队徽相对路径的补全基准（源站目前给的是绝对 URL，这里只是兜底） */
        private const val SITE_ORIGIN = "https://m.jrkan.com"

        private const val TAG = "JrkanParser"

        /** 线路名里出现这些词就从界面名称中隐去（主播个人推流，不展示其名义）。 */
        private val EXCLUDED_CHANNEL_KEYWORDS = listOf("主播", "解说", "美女", "聊天")
    }

    /** 队徽地址补全：相对路径按源站 origin 补齐 */
    private fun absUrl(raw: String): String? = HttpClient.absUrl(raw, SITE_ORIGIN)

    fun parseList(js: String): List<Match> {
        val result = mutableListOf<Match>()

        // 拆出每个 <ul class="item play ..."> ... </ul> 块
        //
        // ⚠️ 不能用 `(?s)<ul class="item play.*?</ul>`：
        // 惰性量词 + DOT_MATCHES_ALL 会在**整份 index.js**（18 万字符）上回溯，
        // 每来一个 <ul 都要扫到下一个 </ul>，源站页面一大就是指数级开销。
        // 改成"先定位起点，再从那里找第一个结束标记"，是线性的。
        // ⚠️ 为什么不用 `document.write('...')` 的边界来切块：
        // 源站的内容里有 `href="' + getPlayUrl("line1", "824761") + '"`，
        // 字符串里**又含单引号**，按引号找结束位置会把一块拦腰截断。
        // 所以这里根本不做 JS 反转义 —— 直接在原文里按 `<ul>...</ul>` 切块，
        // 再把每块交给 Jsoup 解析。
        //
        // 块边界也不再依赖 `class="item play"` 这个字面量：
        // 旧实现写死 `<ul class="item play`，上游只要改成单引号、
        // 属性顺序颠倒、或 class 里多个空格，findAll 就返回空 ——
        // **整表归零且静默**（review/02-data.md P1-7）。
        for (block in splitUlBlocks(js)) {
            val ul = Jsoup.parse(block).selectFirst("ul") ?: continue
            // 不是赛事块（页面导航之类的 ul）：要求块里至少有一行 lab_* 结构
            if (!ul.select("li").any { li -> li.classNames().any { it.startsWith("lab_") } }) {
                continue
            }

            val lid = ul.attr("data-lid")
            val eventId = lid.split(",").firstOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            if (eventId == null) {
                // data-lid 缺失/变形：旧实现直接跳过（整场静默消失）。
                // 现在至少留痕，并且用队名+时间兜底出一个稳定的 id。
                Log.w(TAG, "JRKAN 场次缺 data-lid，按队名与时间兜底 id")
            }

            val eventsLi = ul.selectFirst("li.lab_events")
            val leagueRaw = eventsLi?.selectFirst(".name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: eventsLi?.selectFirst(".league")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: eventsLi?.text()?.trim().orEmpty()
            val timeRaw = ul.selectFirst("li.lab_time")?.text()?.trim().orEmpty()

            // ⚠️ 队名必须**限定在各自的 li 内**（review/02-data.md P2-1）：
            // 旧实现用 DOT_MATCHES_ALL 跨 li 匹配，主队 li 里没有 strong.name 时
            // （比如改成 `<em class="tbd">待定</em>`）会一路扫到客队 li，
            // 把客队名当成主队名 —— 产出一张「篮网 vs 篮网」的卡片。
            // CSS 选择器天然限定在元素自己的子树内。
            val homeLi = ul.selectFirst("li.lab_team_home")
            val awayLi = ul.selectFirst("li.lab_team_away")
            val home = teamName(homeLi)
            val away = teamName(awayLi)
            if (home.isBlank() || away.isBlank()) {
                // 上游改版时这里会静默吞掉整场比赛，必须留痕
                Log.w(TAG, "JRKAN 场次缺队名，跳过：eventId=${eventId ?: "-"}")
                continue
            }
            val stableId = eventId ?: stableFallbackId(home, away, timeRaw)
            if (leagueRaw.isBlank()) {
                // 联赛名取不到本身也必须留痕：它会让整场落 OTHER 后被 merge 丢掉
                Log.w(
                    TAG,
                    "JRKAN 场次缺联赛名，按队名兜底：eventId=$stableId 判定=" +
                        League.resolve(leagueRaw, home, away).displayName,
                )
            }

            val homeLogo = homeLi?.selectFirst("img")?.attr("src")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { absUrl(it) }
            val awayLogo = awayLi?.selectFirst("img")?.attr("src")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { absUrl(it) }

            // 列表页只给一个 playId；具体线路在播放页内部，进详情页再展开。
            // ⚠️ 必须从**原始块文本**里取：Jsoup 解析时
            // `href="' + getPlayUrl("line1", "824761") + '"` 这个坏掉的属性
            // 会被拆散，重新序列化后正则再也匹配不到。
            val playId = Regex("""getPlayUrl\("line\d",\s*"(\d+)"\)""")
                .find(block)?.groupValues?.get(1)

            val sources = if (playId == null) {
                emptyList()
            } else {
                listOf(
                    StreamSource(
                        id = "jrkan-$stableId-s0",
                        label = "信号1",
                        provider = Provider.JRKAN,
                        pageUrl = "$PLAY_HOST/play/steam$playId.html",
                    ),
                )
            }

            result += Match(
                id = "jrkan-$stableId",
                // 联赛名取不到时用队名兜底（review/02-data.md P1-3）：
                // 旧实现直接落 OTHER，而 merge 会把 OTHER 整场丢掉 ——
                // 那是一条连日志都没有的丢场次路径：
                // 源站把 `<span class="name">` 换成 `<span class="league">`，
                // 整个 JRKAN 源的场次会全部消失。
                league = League.resolve(leagueRaw, home, away),
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
        if (result.isEmpty()) {
            // 静默归零是最难查的一类故障：源站改版后列表直接空了，
            // 而 App 的表现只是"今天没比赛"。至少留一条痕。
            Log.w(TAG, "JRKAN 列表解析结果为空（源站可能已改版）")
        }
        return result
    }

    /**
     * 队名：优先 `.name`，其次 `strong`，最后退到本 li 的文本。
     *
     * 退到本 li 文本是为了覆盖「待定」这类没有 strong 的形态 ——
     * 拿不到就整场丢掉太激进，而**绝不会**跑到别的 li 去拿。
     */
    private fun teamName(li: org.jsoup.nodes.Element?): String {
        if (li == null) return ""
        return li.selectFirst(".name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: li.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: li.text()?.trim().orEmpty()
    }

    /**
     * `data-lid` 缺失时的稳定兜底 id。
     *
     * 用「主队 + 客队 + 时间」拼，源站同一场的内容不变时 id 就不变 ——
     * 跨源合并键与信号 id 依赖它是稳定的。
     */
    private fun stableFallbackId(home: String, away: String, timeRaw: String): String {
        val hash = "$home|$away|$timeRaw".hashCode().toUInt().toString(16)
        return "auto-$hash"
    }

    /**
     * 在原文里按 `<ul>...</ul>` 切块（支持嵌套，按深度配对）。
     *
     * ⚠️ 必须是**线性**扫描：源站的 index.js 有 18 万字符，
     * 早先试过 `(?s)<ul class="item play.*?</ul>`，惰性量词 + DOT_MATCHES_ALL
     * 会在整份文件上回溯，源站页面一大就是指数级开销。
     */
    private fun splitUlBlocks(js: String): List<String> {
        val out = ArrayList<String>()
        var searchFrom = 0
        while (true) {
            val start = findTag(js, "ul", searchFrom)
            if (start < 0) break
            val end = findBlockEnd(js, start)
            if (end < 0) break
            out += js.substring(start, end)
            searchFrom = end
        }
        return out
    }

    /** 找下一个 `<tag` 起始位置（后面必须跟空白、`>` 或 `/`）。 */
    private fun findTag(html: String, tag: String, from: Int): Int {
        val needle = "<$tag"
        var i = from
        while (true) {
            val idx = html.indexOf(needle, i)
            if (idx < 0) return -1
            val next = html.getOrNull(idx + needle.length)
            if (next == null || next == '>' || next == '/' || next.isWhitespace()) return idx
            i = idx + 1
        }
    }

    /** 从 `<ul>` 起点找到配对的 `</ul>` 结束位置（含）。 */
    private fun findBlockEnd(html: String, start: Int): Int {
        var depth = 0
        var pos = start
        while (true) {
            val open = findTag(html, "ul", pos)
            val close = html.indexOf("</ul>", pos)
            if (close < 0) return -1
            if (open in 0 until close) {
                depth++
                pos = open + 4
                continue
            }
            depth--
            if (depth == 0) return close + "</ul>".length
            pos = close + "</ul>".length
        }
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
        // ⚠️ 判定委托给 MatchStatusClock（review/02-data.md P2-2）：
        // 旧实现用 ZoneId.systemDefault() 解释源站的北京时间，
        // 设备时区设成 UTC-5 时状态整体偏移 13 小时 —— 正在打的比赛会被判成未开赛。
        // 而且它内部自己取 now()，与 correctLiveStatuses(now=...) 的注入时钟脱钩。
        val now = java.time.ZonedDateTime.now(MatchStatusClock.SOURCE_ZONE)
        return MatchStatusClock.byClock(startMillis, now)
    }

    /**
     * 解析播放页里的线路列表（sub_playlist）。
     *
     * 结构：
     *   <a class="item ..." data-play="/play/sm.html?id=439&id2="><strong>主播解说①</strong></a>
     *
     * @param playHost 拼线路地址用的播放主机（形如 `http://play.sportsteam356.com`）。
     *   ⚠️ 不能固定用 [PLAY_HOST]：正文可能是从**候选里的另一个域名**取到的
     *   （见 `MatchRepository.expandJrkanPlayPage` / `SourceEndpoints.PLAY_HOSTS`），
     *   拼回写死域名的话线路地址指向已经下线的 host，点开就是错误页 ——
     *   换了域名却等于没换。默认值保持 [PLAY_HOST] 只为兼容原调用形态。
     *
     * 处理策略（按用户要求）：
     *  1. **删除**线路名含"主播/解说"等字样的线路（主播个人推流，界面不呈现）
     *  2. 其余线路保留；能直连 m3u8 的排前面，默认选中即可播
     */
    fun parseChannels(
        html: String,
        matchId: String,
        playHost: String = PLAY_HOST,
    ): List<StreamSource> {
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
                    pageUrl = playHost.trimEnd('/') + path,
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
        // ⚠️ "今天"与合成时区都用北京时间（review/02-data.md P2-2）：
        // 源站给的是北京时间，用设备时区解释会让设备在 UTC-5 时整体偏移 13 小时，
        // 跨源合并键（按 LocalDate）也对不上，同一场变成两张卡。
        val now = java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE)
        return runCatching {
            // ⚠️ 用 MonthDay.atYear 而不是 LocalDate.of(year, 2, 29)：
            // 平年 2 月 29 日会抛 DateTimeException 被 runCatching 吞成 null
            // -> startAtMillis 静默为 null -> 状态退回 UPCOMING 且合并不上（02-P2-11）。
            java.time.MonthDay.of(month, day)
                .atYear(now.year)
                .let { date ->
                    // 在 ±6 个月窗口内取最近的一次：过去太久 -> 顺延一年，未来太久 -> 倒退一年
                    when {
                        date.isBefore(now.minusMonths(6)) -> date.plusYears(1)
                        date.isAfter(now.plusMonths(6)) -> date.minusYears(1)
                        else -> date
                    }
                }
                .atTime(hour, minute)
                .atZone(MatchStatusClock.SOURCE_ZONE)
                .toInstant().toEpochMilli()
        }.getOrNull()
    }
}
