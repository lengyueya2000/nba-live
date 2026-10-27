package com.nbalive.app.data.model

import androidx.compose.runtime.Immutable

/** 联赛分类 */
enum class League(val displayName: String) {
    ALL("全部"),
    NBA("NBA"),
    CBA("CBA"),
    OTHER("其他");

    companion object {
        /** 产品范围内可选的主筛选（只做 NBA + CBA，不含"其他"）。 */
        val filters: List<League> = listOf(ALL, NBA, CBA)

        /** 产品支持展示的联赛；源站返回的足球/外联赛一律丢弃。 */
        val supported: Set<League> = setOf(NBA, CBA)

        private val aliasMap = mapOf(
            "NBA" to NBA,
            "CBA" to CBA,
            "美职篮" to NBA,
            "男子篮球" to NBA,
            "中国男子篮球" to CBA,
        )

        // WNBA 不是 NBA：用否定回顾断言避免子串误伤
        private val NBA_RE = Regex("""(?<![A-Za-z])NBA""", RegexOption.IGNORE_CASE)
        private val CBA_RE = Regex("""(?<![A-Za-z])CBA""", RegexOption.IGNORE_CASE)

        fun fromSource(raw: String): League {
            val key = raw.trim()
            aliasMap[key]?.let { return it }
            return if (NBA_RE.containsMatchIn(key)) NBA
            else if (CBA_RE.containsMatchIn(key)) CBA
            else OTHER
        }
    }
}

/** 赛事状态 */
enum class MatchStatus {
    /** 未开赛 */
    UPCOMING,

    /** 直播中 */
    LIVE,

    /** 已结束 */
    FINISHED;

    companion object {
        fun fromSource(raw: String): MatchStatus = when {
            // 顺序很重要，判定必须从"确定"到"不确定"：
            //  1) "直播已结束" 同时含 "直播" 和 "结束" -> 先判结束
            //  2) "直播未开始" 含 "直播" 但没有 "结束" -> 若不先判未开始，
            //     它会被下面的 "直播" 规则误判成直播中（实测源站会提前标）
            raw.contains("已结束") || raw.contains("完场") || raw.contains("结束") -> FINISHED
            raw.contains("未开始") || raw.contains("未开赛") || raw.contains("未赛") -> UPCOMING
            raw.contains("延期") || raw.contains("推迟") || raw.contains("待定") -> UPCOMING
            raw.contains("直播中") || raw.contains("直播") -> LIVE
            // "开赛"表示已经开打，但"即将开赛"是没开始——不能一律当 LIVE，
            // 否则还没开球的场次在列表里就亮红了
            raw.contains("开赛") && !raw.contains("即将") -> LIVE
            else -> UPCOMING
        }
    }
}

/**
 * 源站文案里的"延期 / 推迟 / 待定"。
 *
 * 为什么不在 [MatchStatus] 里加 `POSTPONED`（review/02-data.md P2-10）：
 * 那是一个**跨层**改动——`StatusChip` 的 `when (status)` 是穷尽表达式，
 * 加一个枚举值会让所有 `when` 编译不过，必须同步改 UI 层的配色与文案。
 * 数据层先把"这是延期场次"这个事实暴露出来（[Match.isPostponed]），
 * UI 决定怎么呈现时再消费它，不必等枚举扩容。
 */
private val POSTPONED_MARKERS = listOf("延期", "推迟", "待定", "取消")

/** 该场次是否被源站标注为延期/推迟（仍按未开赛排序，但可单独提示）。 */
val Match.isPostponed: Boolean
    get() = POSTPONED_MARKERS.any { timeLabel.contains(it) }

/** 一场赛事 */
@Immutable
data class Match(
    /** 跨源唯一键，用于去重与故障切换 */
    val id: String,
    val league: League,
    val leagueRawName: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogo: String?,
    val awayLogo: String?,
    /** 展示用时间，如 "07:00" 或 "10-22 07:00" */
    val timeLabel: String,
    /** 排序用时间戳（epoch millis），无法解析时为 null */
    val startAtMillis: Long?,
    val status: MatchStatus,
    /** 可用信号数量 */
    val signalCount: Int,
    /** 该赛事关联的所有播放源 */
    val sources: List<StreamSource>,
    /** JRS 详情页数字 id；合并后仍能取到 JRS 信号（不靠 Match.id 猜源站） */
    val jrsDetailId: String? = null,
)

/** 一个播放源（某条线路） */
@Immutable
data class StreamSource(
    val id: String,
    val label: String,
    /** 所属源站：JRS / JRKAN */
    val provider: Provider,
    /** 播放页地址（还需进一步解析出 m3u8） */
    val pageUrl: String,
)

/** 数据源站点 */
enum class Provider(val displayName: String) {
    JRS("JRS"),
    JRKAN("JRKAN");
}

/**
 * 该线路能否静态解析成 m3u8 直连播放。
 *
 * 实测：只有 `/play/sm.html` 这条链路能还原出 m3u8；
 * JRS 的 mgxl/pao 播放器用 XXTEA 加密，JRKAN 的 wen/pao 线路需要额外令牌，
 * 这些只能交给 WebView 承载源站页面。
 */
val StreamSource.isDirectHls: Boolean
    get() = pageUrl.contains("/play/sm.html")
