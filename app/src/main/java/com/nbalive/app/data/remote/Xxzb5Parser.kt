package com.nbalive.app.data.remote

import android.util.Log
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isStaticallyResolvablePlayPage

/**
 * xxzb5.com 解析器（2026-11-25 替换 88看球）。
 *
 * ## 站点实况（真网探测，非猜测）
 *
 * `xxzb5.com` 首页只做一个跳转：`https://www.xixizb.com?source=xxzb5`（西西直播）。
 * 它是一个 Nuxt 应用，**线路不是 88看球那种 base64**，而是干净 JSON API：
 *
 * ```
 * GET /api/v1/matches/window?from=yyyy-MM-dd&to=yyyy-MM-dd
 *   → {items:[{id, league, sport, home:{name,logoPath}, away:{...},
 *              startsAt:"...Z", status:"live|upcoming|finished"}]}
 *
 * GET /api/v1/matches/{id}/progress
 *   → {available:true, match:{...},
 *      signals:[{name, url}], ...}
 *      url 是裸 m3u8：https://www.rtmpcdn.com/live/qq2.m3u8
 * ```
 *
 * ⚠️ 域名必须用跳转后的 `www.xixizb.com`：`xxzb5.com` 自身**不承载**这些 API
 * （实测 `/api/v1/...` 全 404）。源名仍叫 "xxzb5" 是用户点名的品牌，
 * 后端域名以 [Xxzb5Parser.SITE_ORIGIN] 为准。
 *
 * ## 线路处理
 *
 * - 裸 m3u8 走既有直连链路（[isStaticallyResolvablePlayPage] / `StreamUrlResolver`
 *   的判据唯一来源在 `Match.kt`），不在这里另写正则；
 * - 准入复用 [ChannelAdmission]（主播/解说整条剔除、无名剔除）；
 * - 排序：腾讯超清优先，其余保持源站顺序（与用户此前对 88看球的要求一致）。
 *
 * 列表阶段每条场次挂一个指向 `/progress` 的入口，`MatchRepository.loadSignals`
 * 再展开出线路 —— 与 JRKAN / 88看球同构。
 */
class Xxzb5Parser {

    companion object {
        private const val TAG = "Xxzb5Parser"

        /** 真正承载 API 的 origin（xxzb5.com 跳转后的终点，实测）。 */
        const val SITE_ORIGIN = "https://www.xixizb.com"

        /** 窗口赛程接口：`?from=yyyy-MM-dd&to=yyyy-MM-dd`。 */
        fun listUrl(from: String, to: String, origin: String = SITE_ORIGIN): String =
            "${origin.trimEnd('/')}/api/v1/matches/window?from=$from&to=$to"

        /** 线路接口：`/api/v1/matches/{id}/progress`。 */
        fun signalsUrl(detailId: String, origin: String = SITE_ORIGIN): String =
            "${origin.trimEnd('/')}/api/v1/matches/$detailId/progress"

        /** 从线路接口地址回推比赛 id（改版/外部拼入时用来尝试候选 origin）。 */
        internal val DETAIL_ID = Regex("""/api/v1/matches/(\d+)/progress""")

        /** 腾讯超清优先关键字（用户点名，与 88看球当时同一条要求）。 */
        private const val PREFERRED_LINE_KEYWORD = "腾讯超清"

        /** 取顶层 `available` 的布尔值（容忍 `"available" : true` 的空白）。 */
        private val AVAILABLE_RE = Regex(""""available"\s*:\s*(true|false)""")
    }

    /**
     * 解析窗口赛程。
     *
     * @param origin 正文真正来自的 host（跟随跳转后的 origin）。线路入口按它拼，
     *   不写死首位 —— 主域名换镜像时入口必须跟着换（P1-9 的同一条教训）。
     */
    fun parseList(body: String, origin: String = SITE_ORIGIN): List<Match> {
        if (body.isBlank()) return emptyList()
        val items = EspnScoreboard.JsonCursor(body).array("items") ?: run {
            Log.w(TAG, "窗口接口解析不出 items（源站可能改版）")
            return emptyList()
        }
        val out = mutableListOf<Match>()
        for (item in items) {
            val id = item.number("id") ?: item.string("id")
            if (id.isNullOrBlank()) continue
            val home = item.obj("home")
            val away = item.obj("away")
            val homeName = home?.string("name").orEmpty().trim()
            val awayName = away?.string("name").orEmpty().trim()
            if (homeName.isEmpty() || awayName.isEmpty()) {
                Log.w(TAG, "xxzb5 列表项缺队名，跳过：id=$id")
                continue
            }
            val leagueRaw = item.string("league").orEmpty().trim()
            val sport = item.string("sport").orEmpty().trim()
            // 非篮球（足球等）判 OTHER，由 MatchMerger 统一过滤并计数。
            val league = if (sport == "篮球") {
                League.resolve(leagueRaw, homeName, awayName)
            } else {
                League.OTHER
            }
            val startsAt = item.string("startsAt")
            val startAtMillis = startsAt?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            val timeLabel = startAtMillis?.let { formatBeijing(it) } ?: ""
            val status = apiStatus(item.string("status").orEmpty())

            out += Match(
                id = "xxzb5-$id",
                league = league,
                leagueRawName = leagueRaw,
                homeTeam = homeName,
                awayTeam = awayName,
                homeLogo = home?.string("logoPath")?.takeIf { it.startsWith("http") },
                awayLogo = away?.string("logoPath")?.takeIf { it.startsWith("http") },
                timeLabel = timeLabel,
                startAtMillis = startAtMillis,
                status = status,
                signalCount = -1, // 列表阶段线路还没展开
                sources = listOf(
                    StreamSource(
                        id = "xxzb5-$id-s0",
                        label = "直播信号",
                        provider = Provider.XXZB5,
                        pageUrl = signalsUrl(id, origin),
                    ),
                ),
            )
        }
        if (out.isEmpty()) Log.w(TAG, "xxzb5 列表解析结果为空（源站可能已改版）")
        return out
    }

    /**
     * 解析线路接口的 `signals[]`。
     *
     * `available=false` 时视为无线路（比赛还没备好源，不是故障）。
     */
    fun parseSignals(body: String, match: Match, index: Int = 0): List<StreamSource> {
        if (body.isBlank()) return emptyList()
        val cursor = EspnScoreboard.JsonCursor(body)
        // available 是 JSON 布尔（JsonCursor 没有 boolean()），用带空白容忍的小正则取。
        // 缺省按"有数据"处理：老响应可能没这个字段但仍给 signals。
        val available = AVAILABLE_RE.find(body)?.groupValues?.get(1)?.toBoolean() ?: true
        if (!available) return emptyList()
        val signals = cursor.array("signals") ?: return emptyList()

        val raw = mutableListOf<RawChannel>()
        val seen = linkedSetOf<String>()
        for (signal in signals) {
            val url = signal.string("url").orEmpty().trim()
            val name = signal.string("name").orEmpty().trim()
            if (url.isEmpty() || !seen.add(url)) continue
            raw += RawChannel(path = url, rawLabel = name)
        }
        val admitted = ChannelAdmission.admit(raw).filter {
            it.path.startsWith("http") && isStaticallyResolvablePlayPage(it.path)
        }

        return admitted
            .mapIndexed { order, channel -> order to channel }
            .sortedWith(compareBy(
                { if (it.second.rawLabel.contains(PREFERRED_LINE_KEYWORD)) 0 else 1 },
                { it.first },
            ))
            .mapIndexed { lineIndex, (_, channel) ->
                StreamSource(
                    id = "${match.id}-xxzb5-$index-c$lineIndex",
                    label = channel.rawLabel.ifBlank { "线路${lineIndex + 1}" },
                    provider = Provider.XXZB5,
                    pageUrl = channel.path,
                )
            }
    }

    /** UTC 毫秒 → 北京时间的 `MM-dd HH:mm`（与两个既有源同一口径）。 */
    private fun formatBeijing(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(MatchStatusClock.SOURCE_ZONE)
            .let { "${it.monthValue}-${it.dayOfMonth} ${String.format("%02d:%02d", it.hour, it.minute)}" }
}

/** 把接口英文状态映射到本 App 的三态（与 [MatchStatus.fromSource] 的中文形态互补）。 */
private fun apiStatus(raw: String): MatchStatus = when (raw.trim().lowercase()) {
    "live" -> MatchStatus.LIVE
    "finished", "ft" -> MatchStatus.FINISHED
    else -> MatchStatus.UPCOMING
}
