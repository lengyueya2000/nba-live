package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.ui.TeamNameBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 比赛比分仓库（2026-10-31 用户需求「比赛比分」）。
 *
 * ## 为什么比分必须来自外部
 *
 * 已实测确认（2026-10-31 抓源站真实页面）：
 *  - **JRS 列表页**：`<p class="list-info-time">` 只有「直播中」或「20:30」，
 *    全页没有任何比分形态；
 *  - **JRKAN 列表 JS**：`<em class="bf" title="2">` 的 `title` 全是 `2`（源站**类型编号**），
 *    `<li class="lab_bc">` 里是空的 `<span></span>`；
 *  - **播放页**（`/play/mgxl/`、`paps.html`）：`score`/`比分`/`lab_bc` 命中数 **0**。
 *
 * 所以比分只能从第三方取。选 ESPN 的理由与边界见 [EspnScoreboard] 的 KDoc。
 *
 * ## ⚠️ CBA 没有数据
 *
 * ESPN 的 `basketball/cba/scoreboard` 返回 **HTTP 400**（实测）。
 * 所以 [scoresFor] 对 CBA 直接返回空表、**一个请求都不发** ——
 * 明知拿不到还去打一次，只是白白浪费用户流量。
 *
 * ## 失败必须静默
 *
 * 比分是"锦上添花"：拿不到就只是卡片上少一行，**不能**让列表加载失败、
 * 也不能弹任何提示。所以整条链路 `runCatching` + 空表兜底。
 */
class ScoreboardRepository(
    private val fetch: suspend (String, String?) -> FetchResult = { url, referer ->
        HttpClient.shared.fetch(url, referer)
    },
    /** 比分板缓存时长：ESPN 自己约 30 秒更新一次，这里给 20 秒（比它快一点，不落后太多） */
    private val ttlMs: Long = 20_000L,
) {

    /** 一次比分板抓取的结果（供 UI 按场次查）。 */
    data class Snapshot(
        val games: List<EspnScoreboard.GameScore>,
        /** 抓取成功的联赛；失败/无数据的联赛不在这里 */
        val leagues: Set<League>,
    ) {
        fun isEmpty(): Boolean = games.isEmpty()

        companion object {
            val EMPTY = Snapshot(emptyList(), emptySet())
        }
    }

    private var cached: Snapshot? = null
    private var cachedAt: Long = 0L

    /**
     * 拉一次比分板。
     *
     * @param enabled 用户开着的数据源（「我的」页开关）—— 全关掉时也没必要拉比分
     */
    suspend fun load(enabled: Set<Provider> = Provider.entries.toSet()): Snapshot {
        val now = System.nanoTime() / 1_000_000L
        cached?.let { if (now - cachedAt < ttlMs) return it }
        val snap = withContext(Dispatchers.IO) {
            val games = mutableListOf<EspnScoreboard.GameScore>()
            val ok = mutableSetOf<League>()
            for (league in LEAGUES_WITH_SCORES) {
                val url = scoreboardUrl(league) ?: continue
                val body = runCatching {
                    when (val r = fetch(url, null)) {
                        is FetchResult.Ok -> r.body
                        else -> null
                    }
                }.getOrNull() ?: continue
                val parsed = runCatching { EspnScoreboard.parse(body) }.getOrNull() ?: continue
                if (parsed.isNotEmpty()) {
                    games += parsed
                    ok += league
                }
            }
            Snapshot(games, ok)
        }
        cached = snap
        cachedAt = now
        return snap
    }

    /**
     * 这一场的比分（查不到返回 null，界面据此不画比分区块）。
     *
     * ⚠️ 只有 NBA 有数据：CBA 场次永远返回 null，界面必须如实降级。
     */
    fun scoreFor(snapshot: Snapshot, match: Match): EspnScoreboard.GameScore? {
        if (match.league !in snapshot.leagues) return null
        return TeamNameBridge.findGame(snapshot.games, match.homeTeam, match.awayTeam)
    }

    /** 联赛 → ESPN 比分板地址；没有数据的联赛返回 null（不发请求）。 */
    private fun scoreboardUrl(league: League): String? = when (league) {
        League.NBA -> "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"
        // ⚠️ CBA：ESPN 返回 HTTP 400（实测）。不发请求、不显示比分。
        // 哪天 ESPN 支持了，把这里补上即可，其余链路不用动。
        League.CBA -> null
        else -> null
    }

    private companion object {
        /** 有比分数据的联赛（CBA 不在内，理由见 [scoreboardUrl]）。 */
        val LEAGUES_WITH_SCORES = listOf(League.NBA)
    }
}
