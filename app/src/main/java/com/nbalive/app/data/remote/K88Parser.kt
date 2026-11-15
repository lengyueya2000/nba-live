package com.nbalive.app.data.remote

import android.util.Log
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isStaticallyResolvablePlayPage
import com.nbalive.app.data.model.wrappedM3u8Url
import org.jsoup.Jsoup

/**
 * 88看球源站（`88kanqiu.cx`）解析器（2026-11-15 第三源接入）。
 *
 * ## 页面结构（2026-11-15 实测抓包，`build-shots/sources88/`）
 *
 * 列表页 `/match/1/live`：
 * ```
 * <li class="live-list-group-time">  2026-11-15          -> 日期分组
 * <li class="group-game-item">
 *   .category-game-time              08:00               -> 开赛时间
 *   .game-type                       NBA / NBA常规赛      -> 联赛
 *   .team-name (两个)                 鹈鹕 / 掘金          -> 主客队
 *   a[href='/live/131401/play']      直播中 / 暂无        -> 状态 + 比赛 id
 * ```
 *
 * 线路接口 `/live/{id}/source`（页面里的混淆 JS 请求它）：
 * ```
 * {"data":"<乱码6 + base64(JSON) + 乱码2>"}
 * JSON = {"shareStatus":0,"links":[{"url","name","shareLock"}],...}
 * ```
 * 实测头尾噪声长度会变（6/2、6/1 都见过），所以解码走
 * [Base64Decode.decodeWithNoise] 的有界搜索，而不是按固定位数切。
 *
 * ## 三类线路，处理策略不同
 *
 *  1. **云直播线路** `play.sportsteam368.com/play/{kbs,mgxl,txf,pao,oo,vxt}/?id=`
 *     —— 与 JRS / JRKAN 是同一批（368 与 356 互为镜像，已验证同后端）。
 *     这里**照常解析**，重复交给合并层的 [LineEquivalence] 去除
 *     （判据集中一处，不在两个解析器里各写一份"谁和谁算重复"）。
 *  2. **裸 m3u8 代理壳** `play.88player.top/m3u8.html?url=<encodeURI(m3u8)>`
 *     —— 这才是本源的增量价值：真实清单地址就写在 query 里，
 *     能静态还原直连（实测 `rtmpcdn.com/live/qqN.m3u8` 返回 200 + `#EXTM3U`）。
 *     判为 [isDirectHls] 由准入函数统一处理，见 [isResolvableSourcePage]。
 *  3. **海外/官源网页** `taifood-blog.asia`、`streamfree.top`、`cctv`、`yangshipin`
 *     —— **整类剔除**。理由：它们是网页播放器，本 App 的 WebView 走
 *     `WebNavigationPolicy` 的注册域白名单承载（白名单外的域会被拦成广告跳转），
 *     而这类站点对盗播 App 的承载价值远低于"多开一批白名单域"的风险。
 *     宁可少几条线路，也不要一个"点了必被拦截"的死按钮。
 *
 * ## 为什么线路要现取，不在列表页解析
 *
 * 列表页 HTML 里**没有**线路（只有一个空的 `#livePlaySource` 容器，
 * 线路由混淆 JS 现拉 `/source` 再填进去）。所以列表阶段每条场次只挂一个
 * "线路接口"入口（[StreamSource.pageUrl] 指向 `/live/{id}/source`），
 * 真正的展开发生在 `MatchRepository.loadSignals` —— 与 JRKAN「列表只给播放页、
 * 线路在播放页内部」完全同构，复用同一套展开与限流纪律。
 */
class K88Parser {

    companion object {
        private const val TAG = "K88Parser"

        /** 源站 origin（候选首位；线路接口与图片相对路径的基准）。 */
        const val SITE_ORIGIN = "https://www.88kanqiu.cx"

        /** 列表页路径模板（候选域名见 `SourceEndpoints.K88_LIST`）。 */
        const val LIST_PATH = "/match/1/live"

        /**
         * 线路接口地址。
         *
         * ⚠️ host 由调用方给（真正取到正文的那个），默认值只是候选首位；
         * 路径口径与 `SourceEndpoints.k88SourceUrls` 必须一致。
         */
        fun sourceUrl(detailId: String, origin: String = SITE_ORIGIN): String =
            "${origin.trimEnd('/')}/live/$detailId/source"

        /** 列表行里的比赛 id（`href='/live/131401/play'`，引号单双都有）。 */
        private val LIVE_ID = Regex("""/live/(\d+)/play""")

        /** 日期分组标题 `2026-11-15`（容忍带星期后缀）。 */
        private val DATE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")

        /**
         * 云直播播放器 host 特征。
         *
         * ⚠️ 与 [JrsParser] 的 `PLAYER_HOST_MARKERS` 同一套语义（sportsteam 家族），
         * 但这里必须**独立**判：88 给的是 `sportsteam368`，而 JRS 那份表还含
         * `jrkan`/`jrs` 两个词，直接复用会把"源站自己的域名"也算成播放线路。
         * ⚠️ 356 也要列：它是 368 的镜像（`SourceEndpoints.PLAY_HOSTS` 同一对），
         * 少写一个就意味着同一批线路里有一半被当成"海外网页"整类丢掉。
         */
        private val CLOUD_PLAYERS = listOf("sportsteam368.com", "sportsteam356.com")

        /** m3u8 代理壳 host。 */
        private const val M3U8_WRAPPER_HOST = "88player.top"
    }

    /**
     * 解析列表页：每场挂一条指向线路接口的入口。
     *
     * 联赛名**不**在这里过滤（与 JRS/JRKAN 一致）：非篮球场次判 [League.OTHER]，
     * 由 `MatchMerger` 的 supported 过滤统一丢弃并计入 `filteredOutCount` ——
     * 那是一条可观测的丢场次路径，静默丢弃才是本仓库反复踩过的坑。
     *
     * @param origin 正文**真正来自哪个 host**（`SourceEndpoints.K88_LIST` 的候选之一）。
     *   ⚠️ 不能写死首位：主域名死了、正文是从镜像拿到的时候，入口地址若仍拼死域名，
     *   展开线路时敲的还是死域名 —— 换域名等于没换（`JrkanParser.parseChannels`
     *   的 `playHost` 是同一条教训）。
     */
    fun parseList(html: String, origin: String = SITE_ORIGIN): List<Match> {
        val out = mutableListOf<Match>()
        val doc = Jsoup.parse(html)
        var dayLabel: String? = null

        doc.select("li.list-group-item").forEach { li ->
            if (li.hasClass("live-list-group-time")) {
                dayLabel = li.text().trim().takeIf { DATE.containsMatchIn(it) }
                return@forEach
            }
            if (!li.hasClass("group-game-item")) return@forEach

            // 比赛链接：源站写成 href='/live/131401/play'（单引号），属性选择器
            // 在这里既不可靠也容易随引号变形失效，直接遍历 a 标签按正则取。
            val anchor = li.select("a").firstOrNull { LIVE_ID.containsMatchIn(it.attr("href")) }
            val detailId = anchor?.let { LIVE_ID.find(it.attr("href"))?.groupValues?.get(1) }
            if (detailId == null) {
                // href 变形（改版/推广位）时旧实现会产出 "k88-" 这种空后缀 id，
                // LazyColumn 的 key 重复直接崩（本仓库崩过两次），所以跳过并留痕。
                Log.w(TAG, "88 列表项缺比赛 id，跳过：${redactText(anchor?.attr("href"))}")
                return@forEach
            }

            val names = li.select(".team-name").map { it.attr("title").ifBlank { it.text() }.trim() }
                .filter { it.isNotEmpty() }
            val home = names.getOrNull(0)
            val away = names.getOrNull(1)
            if (home.isNullOrBlank() || away.isNullOrBlank()) {
                Log.w(TAG, "88 列表项缺队名，跳过：id=$detailId")
                return@forEach
            }

            val leagueRaw = li.selectFirst(".game-type")?.text()?.trim().orEmpty()
            val timeRaw = li.selectFirst(".category-game-time")?.text()?.trim().orEmpty()
            val statusRaw = anchor?.text()?.trim().orEmpty()
            val logos = li.select("img[data-src]").map { it.attr("data-src").trim() }.filter { it.isNotEmpty() }

            val timeLabel = composeTimeLabel(dayLabel, timeRaw)
            val league = League.resolve(leagueRaw, home, away)
            if (leagueRaw.isBlank()) {
                Log.w(TAG, "88 列表项缺联赛名，按队名兜底：id=$detailId 判定=${league.displayName}")
            }

            out += Match(
                id = "k88-$detailId",
                league = league,
                leagueRawName = leagueRaw,
                homeTeam = home,
                awayTeam = away,
                homeLogo = logos.getOrNull(0)?.let { HttpClient.absUrl(it, origin) },
                awayLogo = logos.getOrNull(1)?.let { HttpClient.absUrl(it, origin) },
                timeLabel = timeLabel,
                startAtMillis = parseDateTime(dayLabel, timeRaw),
                // 88 列表页的"直播中"来自同一份缓存，滞后程度与 JRS 相当：
                // "暂无"按未开赛处理，其余交给 MatchStatus.fromSource，
                // 之后仍会被 correctLiveStatuses 用时钟再校正一遍。
                status = if (statusRaw.contains("暂无")) MatchStatus.UPCOMING else MatchStatus.fromSource(statusRaw),
                // 列表页拿不到线路数：-1 = 未解析（与 JRS 列表行同一约定，界面不显示徽标）
                signalCount = -1,
                sources = listOf(
                    StreamSource(
                        id = "k88-$detailId-s0",
                        label = "线路",
                        provider = Provider.K88,
                        pageUrl = sourceUrl(detailId, origin),
                    ),
                ),
            )
        }
        if (out.isEmpty()) {
            // 静默归零是最难查的故障：源站改版后列表直接空，App 只是"今天没比赛"
            Log.w(TAG, "88 列表解析结果为空（源站可能已改版）")
        }
        return out
    }

    /**
     * 解析线路接口正文，取出可用线路。
     *
     * @param body `/live/{id}/source` 的原始响应（`{"data":"<噪声+base64+噪声>"}`）
     * @param match 该场次（线路 id 的前缀来源）
     * @param idPrefix id 前缀。⚠️ 一场可能挂多个线路入口（候选 host / 改版后多路），
     *   编号只在**单次调用内**唯一，跨次会撞；`MatchRepository.loadSignals` 必须像
     *   JRKAN 的 `"$matchId-jr$index"` 那样按索引给不同前缀 —— 播放器把 id 当
     *   LazyColumn 的 key，重复 key 直接崩（本仓库崩过两次）。
     * @param index 该入口在这一场里的序号（拼进 idPrefix，保证同源多次展开不撞号）。
     */
    fun parseSourcePage(body: String, match: Match, index: Int = 0): List<StreamSource> {
        val json = extractSourceJson(body)
        if (json.isNullOrBlank()) {
            Log.w(TAG, "88 线路接口正文解不出 JSON：id=${match.id}")
            return emptyList()
        }
        val links = EspnScoreboard.JsonCursor(json).array("links")
        if (links == null) {
            Log.w(TAG, "88 线路接口缺 links 字段（源站可能已改版）：id=${match.id}")
            return emptyList()
        }

        val raw = mutableListOf<RawChannel>()
        val seen = linkedSetOf<String>()
        for (link in links) {
            val url = link.string("url").orEmpty().trim()
            val name = link.string("name").orEmpty().trim()
            if (url.isEmpty() || !seen.add(url)) continue
            raw += RawChannel(path = url, rawLabel = name)
        }

        // 准入：敏感线路（主播/解说…）整条剔除 + 名字取不到的剔除，
        // 与 JRKAN 播放页共用 ChannelAdmission 那两条规则。
        val admitted = ChannelAdmission.admit(raw).filter { isWorthwhile(it.path) }

        return admitted
            // 可直连的排前面：本源恰好给了本 App 最想要的裸 m3u8，
            // 默认选中第一条就应该落在它上面（与 JrkanParser.parseChannels 同一条排序纪律）
            .sortedByDescending { (path, _) -> isStaticallyResolvablePlayPage(path) }
            .mapIndexed { lineIndex, (path, rawLabel) ->
                StreamSource(
                    // ⚠️ id 带来源维度（与 JRS 同款教训）：播放器把它当 LazyColumn 的 key。
                    // index 让同一场多次展开（多入口）也不撞号。
                    id = "${match.id}-k88-$index-c$lineIndex",
                    label = rawLabel.ifBlank { "线路${lineIndex + 1}" },
                    provider = Provider.K88,
                    pageUrl = path,
                )
            }
    }

    /**
     * 这条线路值不值得进列表。
     *
     * 只留两类：云直播播放器（与两源同源、地址合法）、m3u8 代理壳（本源增量）。
     * 其余（taifood / streamfree / cctv / 各种推广页）一律不要 —— 理由见类 KDoc 第 3 条。
     */
    private fun isWorthwhile(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        if (host.isEmpty() || !url.startsWith("http")) return false
        if (host.contains(M3U8_WRAPPER_HOST)) return hasWrappedM3u8(url)
        return CLOUD_PLAYERS.any { host.contains(it) } && url.contains("/play/")
    }

    /** 代理壳里必须真含一个 `.m3u8` 地址，否则它就是个空壳页面。 */
    private fun hasWrappedM3u8(url: String): Boolean =
        com.nbalive.app.data.model.wrappedM3u8Url(url) != null

    /** `"11-15 08:00"`：日期分组 + 时刻，供展示与 [com.nbalive.app.data.repository.MatchMerger.fallbackDayKey] 抠 `MM-dd`。 */
    private fun composeTimeLabel(dayLabel: String?, timeRaw: String): String {
        if (timeRaw.isBlank()) return dayLabel.orEmpty()
        val md = dayLabel?.let { DATE.find(it) }?.let { "${it.groupValues[2].toInt()}-${it.groupValues[3].toInt()}" }
        return if (md == null) timeRaw else "$md $timeRaw"
    }

    /** 日期分组 + `HH:mm` -> 北京时间戳；拿不到返回 null（不猜）。 */
    private fun parseDateTime(dayLabel: String?, timeRaw: String): Long? {
        val dm = dayLabel?.let { DATE.find(it) } ?: return null
        val hm = Regex("""(\d{1,2}):(\d{2})""").find(timeRaw)
        val hour = hm?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val minute = hm?.groupValues?.get(2)?.toIntOrNull() ?: return null
        // ⚠️ 固定北京时间（与两个既有源同一条纪律）：源站给的就是北京时间，
        // 用设备时区解释会让状态判定与合并键整体偏移。
        return runCatching {
            java.time.LocalDate.of(dm.groupValues[1].toInt(), dm.groupValues[2].toInt(), dm.groupValues[3].toInt())
                .atTime(hour, minute)
                .atZone(MatchStatusClock.SOURCE_ZONE)
                .toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 从响应里取出线路 JSON：`{"data":"<噪声+base64+噪声>"}` -> JSON 文本。
     *
     * 两层容错：
     *  - 外层响应偶有尾巴（实测 404/错误页也回 HTTP 200 带 HTML），用
     *    [JsonCursor] 只取 `data` 字段，不整串 `json.loads`；
     *  - 内层 base64 的头尾噪声长度会变，用有界搜索而不是写死 6/2。
     */
    private fun extractSourceJson(body: String): String? {
        if (body.isBlank()) return null
        val payload = EspnScoreboard.JsonCursor(body).string("data") ?: return null
        return Base64Decode.decodeWithNoise(payload, mustStartWith = '{')
    }
}
