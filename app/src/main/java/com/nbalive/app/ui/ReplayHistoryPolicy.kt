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

    /**
     * 续播状态机：把"是否 seek"与"是否允许进度上报"解耦（2026-11-25 修复）。
     *
     * 三个 bug 都在这一个判据里被堵住：
     *  ① 换线/重连时 effect 以 streamUrl 重启，续播意图会被重复消费 → [consumedMs] 记住
     *     已消费的 initialSeekMs（存在 handle 上，跨 streamUrl 不丢），相等即 [DONE] 不再 seek；
     *  ② 续播点 >= 时长（换短线路）时 shouldResume=false，若用它门控上报，进度永远不写
     *     → 越界直接 [DONE]，从头播但**允许上报**，旧位置会被新进度覆盖；
     *  ③ 时长未就绪（0）时不能 seek；此时 [PENDING]，也不上报（避免把 0 写成历史）。
     *
     * @param initialSeekMs 续播意图（0 = 无续播，从头播）
     * @param durationMs 当前媒体时长（0 = 未就绪）
     * @param consumedMs 已消费过的续播意图值（-1 = 尚未消费任何值）
     */
    fun resumeState(initialSeekMs: Long, durationMs: Long, consumedMs: Long): ResumeState {
        if (durationMs <= 0L) return ResumeState.PENDING // 时长未知：等 READY
        if (initialSeekMs <= 0L) return ResumeState.DONE // 无续播意图：正常上报
        if (consumedMs == initialSeekMs) return ResumeState.DONE // 已消费：换线不倒退
        return if (shouldResume(initialSeekMs, durationMs)) ResumeState.SEEK else ResumeState.DONE
    }

    enum class ResumeState { PENDING, SEEK, DONE }

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
