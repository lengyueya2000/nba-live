package com.nbalive.app.data.model

import androidx.compose.runtime.Immutable

/**
 * 一场已完赛赛事的录像（2026-11-25 新增，数据源 xxzb5 → xixizb.com）。
 *
 * 与直播 [Match] 分开：录像数据来自 `/api/v1/replays?matchId=`，
 * 带真实比分与多条清晰度线路（标清 / 中文高清 / 英文高清）。
 * 它不参与直播链路的源开关、去重与状态校正。
 */
@Immutable
data class ReplayItem(
    /** 源站比赛 id（与直播 Match 的 xxzb5-<id> 对应，但这里是纯数字串） */
    val matchId: String,
    val league: League,
    val leagueRawName: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int?,
    val awayScore: Int?,
    /** 队徽 URL（xxzb5 window 接口的 home/away.logoPath）；null 时 UI 回退队名首字 */
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    /** 展示用时间（北京时间 MM-dd HH:mm） */
    val timeLabel: String,
    val startAtMillis: Long?,
    /** 录像线路（按清晰度 sort 排序，可换线） */
    val lines: List<ReplayLine>,
) {
    /** 稳定唯一键：按 matchId，跨源只有 xxzb5 提供录像。 */
    val id: String get() = "replay-$matchId"
}

/** 一条录像线路（一个清晰度）。 */
@Immutable
data class ReplayLine(
    val id: String,
    /** 源站给的清晰度名，如「标清回放」「中文高清回放」。 */
    val label: String,
    /** 裸 m3u8 VOD 直连地址。 */
    val url: String,
)
