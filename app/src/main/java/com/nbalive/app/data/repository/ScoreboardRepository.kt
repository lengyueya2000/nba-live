package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.TeamNameBridge
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.data.remote.debugLog
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
        /**
         * 这份比分**成功抓到**的时刻（墙钟 epoch millis）；[EMPTY] 与"从未成功"为 0。
         *
         * ⚠️ 用墙钟而不是 `nanoTime`：它的消费方是界面（判断"比分是不是几分钟前的"），
         * 而 TTL 用的是单调钟（见 [load] 里的 `cachedAt`）——
         * 两者的用途不同，混用会让"用户调系统时间"与"比分陈旧"互相干扰。
         *
         * 加这个字段是为了让 UI 能剔除**陈旧比分**（review/optimization-review.md D7）：
         * 失败不再覆盖 last-good 之后，卡片上挂着的可能是几分钟前的比分，
         * 没有时间戳就只能靠猜。
         */
        val fetchedAtMs: Long = 0L,
    ) {
        fun isEmpty(): Boolean = games.isEmpty()

        companion object {
            val EMPTY = Snapshot(emptyList(), emptySet(), fetchedAtMs = 0L)
        }
    }

    private var cached: Snapshot? = null
    private var cachedAt: Long = 0L

    /**
     * 拉一次比分板。
     *
     * @param enabled 用户开着的数据源（「我的」页开关）—— 全关掉时也没必要拉比分
     * @param nowWallMs 墙上钟注入点（测试用它把"陈旧"变成确定性判据；
     *   生产默认 `System.currentTimeMillis()`，与 [Snapshot.fetchedAtMs] 同一口径）
     */
    suspend fun load(enabled: Set<Provider> = Provider.entries.toSet(), nowWallMs: () -> Long = { System.currentTimeMillis() }): Snapshot {
        // ⚠️ D8：`enabled` 必须真的被消费（review/optimization-review.md D8）。
        // 旧实现只在签名里收下它、函数体一次都没读，KDoc 却写"全关掉时也没必要拉比分"——
        // 声明与实现相反，两源全关的过渡态仍会打 ESPN。
        // 空集直接返回 EMPTY：**不发请求、不写缓存**（缓存写进去会把"没开源的正常状态"
        // 冒充成"ESPN 抓到的空比分板"，之后 TTL 内的真实调用会拿到它）。
        if (enabled.isEmpty()) {
            debugLog("Scores", "所有数据源都关掉了，跳过 ESPN 比分板")
            return Snapshot.EMPTY
        }
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
            Snapshot(games, ok, fetchedAtMs = nowWallMs())
        }
        // ⚠️ D7：失败（空快照）**不许覆盖 last-good**（review/optimization-review.md D7）。
        // 旧实现把空快照写进 20 秒 TTL 缓存，于是 ESPN 不通期间卡片一直挂着
        // 上一次成功、且是**几分钟前**的比分，界面上没有任何时效标记。
        // 现在的纪律：失败时原样返回上一次成功的那份（连 `fetchedAtMs` 都不动），
        // 由 UI 用 [Snapshot.fetchedAtMs] 决定要不要把它标成陈旧/剔除。
        val last = cached
        if (snap.isEmpty() && last != null) {
            debugLog("Scores", "ESPN 比分板为空，保留上一次成功快照（fetchedAtMs 不变）")
            return last
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

    /**
     * 快照是否已经**陈旧**（消费 [Snapshot.fetchedAtMs] 的唯一生产判据）。
     *
     * ⚠️ v1.2.26：这个函数把 D7 的承诺接完 —— `fetchedAtMs` 与"UI 靠它剔除
     * 陈旧比分"的注释在 review D7 就写下了，但生产里没有任何消费点
     * （VM 只取 `Map<String, GameScore>`，时间戳被丢弃），ESPN 持续失败时
     * 旧比分被当实时比分无限挂着。判据在此，消费点在
     * [com.nbalive.app.ui.MainViewModel.refreshScores]，回归见 `ScoreboardStalenessTest`。
     *
     * 空快照（EMPTY / 从未成功，`fetchedAtMs = 0`）**不算陈旧**——
     * 它本来就没有内容可标旧，由调用方按 isEmpty 处理。
     */
    fun isStale(snapshot: Snapshot, nowWallMs: Long): Boolean =
        !snapshot.isEmpty() && nowWallMs - snapshot.fetchedAtMs > STALE_AFTER_MS

    /** 联赛 → ESPN 比分板地址；没有数据的联赛返回 null（不发请求）。 */
    private fun scoreboardUrl(league: League): String? = when (league) {
        League.NBA -> "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"
        // ⚠️ CBA：ESPN 返回 HTTP 400（实测）。不发请求、不显示比分。
        // 哪天 ESPN 支持了，把这里补上即可，其余链路不用动。
        League.CBA -> null
        else -> null
    }

    companion object {
        /** 有比分数据的联赛（CBA 不在内，理由见 [scoreboardUrl]）。 */
        val LEAGUES_WITH_SCORES = listOf(League.NBA)

        /** 一份 last-good 快照"还能当实时比分显示"的墙上钟上限（见 [isStale]）。 */
        const val STALE_AFTER_MS: Long = 120_000L
    }
}
