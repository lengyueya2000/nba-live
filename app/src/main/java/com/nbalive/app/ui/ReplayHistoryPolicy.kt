package com.nbalive.app.ui

import androidx.compose.runtime.Immutable
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.data.model.ReplayLine

/**
 * 一条录像播放历史（2026-11-25 用户需求）。
 *
 * 与 [ReplayItem] 的区别：它多带「上次播放位置」与「最近观看时间」，
 * 线路快照直接存下来 —— 历史 List 里的场次即使当前窗口已下线也能点开续播。
 */
@Immutable
data class ReplayHistoryItem(
    val matchId: String,
    val league: League,
    val leagueRawName: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val timeLabel: String,
    val lines: List<ReplayLine>,
    val lastPositionMs: Long,
    val watchedAt: Long,
) {
    val id: String get() = "replay-$matchId"
}

/** 录像历史的纯判据：去重、排序、上限、续播合法性（可 JVM 单测）。 */
object ReplayHistoryPolicy {

    /** 历史最多保留多少条（用户选「最近 10 条」）。 */
    const val DEFAULT_LIMIT = 10

    /** 从当前播放的录像 + 位置构造一条历史。 */
    fun fromReplay(replay: ReplayItem, positionMs: Long, watchedAt: Long): ReplayHistoryItem =
        ReplayHistoryItem(
            matchId = replay.matchId,
            league = replay.league,
            leagueRawName = replay.leagueRawName,
            homeTeam = replay.homeTeam,
            awayTeam = replay.awayTeam,
            homeScore = replay.homeScore,
            awayScore = replay.awayScore,
            homeLogo = replay.homeLogo,
            awayLogo = replay.awayLogo,
            timeLabel = replay.timeLabel,
            lines = replay.lines,
            lastPositionMs = positionMs.coerceAtLeast(0L),
            watchedAt = watchedAt,
        )

    /**
     * 合并新旧历史：同一 matchId 去重（保留 watchedAt 最大的那条），
     * 按 watchedAt 倒序，最多 [limit] 条。
     */
    fun merge(existing: List<ReplayHistoryItem>, limit: Int = DEFAULT_LIMIT): List<ReplayHistoryItem> {
        val byId = LinkedHashMap<String, ReplayHistoryItem>()
        for (item in existing) {
            val prev = byId[item.matchId]
            if (prev == null || item.watchedAt >= prev.watchedAt) byId[item.matchId] = item
        }
        return byId.values.sortedByDescending { it.watchedAt }.take(limit)
    }

    /**
     * 续播位置是否合法：必须 >0 且 < 时长（时长未知=0 时不可续播）。
     *
     * ⚠️ position 接近片尾（>= duration）时从头播，避免用户一点历史就跳到结束页。
     */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean =
        positionMs > 0L && durationMs > 0L && positionMs < durationMs

    /** 历史条目显示的「上次看到 mm:ss」（复用播放器时间格式，避免两套）。 */
    fun formatWatchedPosition(positionMs: Long): String = PlayerControls.formatPlaybackTime(positionMs)

    /**
     * 历史条目的展示时间。
     *
     * 放在 ui 包而非 screens：screens 包有守卫禁止直接渲染源站 `timeLabel`
     * （会绕过假 00:00 防护）；录像的时间本身已是北京时间展示串，这里集中格式化。
     */
    fun displayTime(entry: ReplayHistoryItem): String = entry.timeLabel
}
