package com.nbalive.app.ui

import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 赛事的展示格式化。
 *
 * 列表页、详情页、播放器都要显示"联赛名 / 开赛时间 / 日期"，
 * 之前这三处各写一份扩展函数（`hhmm` / `mmdd` / `displayTime`），
 * 而且**行为已经分叉**——详情页多带一段日期，列表页不带。
 * 这里收敛成一份，两个页面共用。
 */

/**
 * 当前时区。
 *
 * ⚠️ 缓存起来：`ZoneId.systemDefault()` 每次调用都会查一次系统设置，
 * 而列表页每一行都要调它两次（hhmm + mmdd），上百行就是几百次系统查询。
 * 系统时区在进程生命周期内基本不变，缓存是安全的；
 * 用户改时区需要重启 App 才生效 —— 对赛事时间显示来说可以接受。
 */
internal val systemZone: ZoneId by lazy { ZoneId.systemDefault() }

/** 联赛展示名：优先用源站原名（如"美职篮"），为空时退回枚举名。 */
fun Match.leagueLabel(): String = leagueRawName.ifBlank { league.displayName }

/**
 * 展示用开赛时刻 "HH:mm"。
 *
 * LIVE 行：校正后 startAtMillis 是详情页回填的真实开赛时间，直接显示；
 * 未校正的 JRS"直播中"行 startAtMillis 只是 parseDateTime 的当天 00:00 兜底，
 * 这种假 00:00 不能上屏（真 00:00 开赛的篮球赛不存在），退回源站文案。
 *
 * ⚠️ 一律用 [Locale.ROOT] 格式化：不指定 Locale 时 `%02d` 会跟随系统默认语言，
 * 阿拉伯语/波斯语等区域会输出本地数字（٠١:٣٠），和旁边的时间文案对不上。
 */
fun Match.hhmm(): String {
    val t = startAtMillis?.let { Instant.ofEpochMilli(it).atZone(systemZone) }
    if (t != null) {
        if (status == MatchStatus.LIVE && t.hour == 0 && t.minute == 0) return timeLabel
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }
    return timeLabel.substringAfterLast(' ').ifBlank { timeLabel }
}

/** 展示用日期 "MM-dd"。 */
fun Match.mmdd(): String {
    val t = startAtMillis?.let { Instant.ofEpochMilli(it).atZone(systemZone) }
    if (t != null) return String.format(Locale.ROOT, "%02d-%02d", t.monthValue, t.dayOfMonth)
    return timeLabel.substringBefore(' ').ifBlank { "" }
}

/** 详情页用完整时间 "MM-dd HH:mm"；时间未知时退回源站文案。 */
fun Match.displayTime(): String {
    val t = startAtMillis?.let { Instant.ofEpochMilli(it).atZone(systemZone) }
    if (t != null && !(status == MatchStatus.LIVE && t.hour == 0 && t.minute == 0)) {
        return String.format(
            Locale.ROOT,
            "%02d-%02d %02d:%02d",
            t.monthValue,
            t.dayOfMonth,
            t.hour,
            t.minute,
        )
    }
    return timeLabel
}

/**
 * 列表页的日期分组（review/03-ui.md P2-8 / P2-33）。
 *
 * ⚠️ 存的是**绝对日期**，不是标签字符串。
 * 标签（"今天/明天/1月1日"）拿来做 `LazyVerticalGrid` 的 key 会**重复 key 崩溃**：
 * 跨年时 2026-01-01 与 2027-01-01 都显示 "1月1日"（这个仓库崩过两次）。
 * 存绝对日期则跨零点、跨年 key 都稳定。
 */
internal data class DayGroup(val date: LocalDate, val matches: List<Match>)

/**
 * 日期分组标签：把 `today` 当参数传进来，而不是内部读时钟。
 *
 * 这样"跨零点后同一个日期从'今天'变成'昨天'"这条业务规则可以被单测钉住
 * （原来是 `private` 且和 UI 绑在一个文件里，测不到 —— review/03-ui.md P2-33）。
 */
internal fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天 · ${date.monthValue}月${date.dayOfMonth}日"
    today.plusDays(1) -> "明天 · ${date.monthValue}月${date.dayOfMonth}日"
    today.minusDays(1) -> "昨天 · ${date.monthValue}月${date.dayOfMonth}日"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}

/**
 * 按绝对日期分组；无时间戳的场次归到 `today` 这组。
 *
 * 时区用缓存的 [systemZone]，与 [hhmm]/[mmdd]/[displayTime] 同一口径 ——
 * 不要让分组和显示落在两个不同的时区上。
 */
internal fun groupByDay(matches: List<Match>, today: LocalDate): List<DayGroup> =
    matches
        .groupBy { m ->
            m.startAtMillis?.let { Instant.ofEpochMilli(it).atZone(systemZone).toLocalDate() } ?: today
        }
        .toSortedMap()
        .map { (date, list) -> DayGroup(date, list) }

/**
 * 列表页日期标签的刷新节奏（review/03-ui.md P2-8）。
 *
 * 1 分钟一跳：跨零点后最多 1 分钟内自动修正，代价是列表可见时每分钟一次
 * 极轻量的比较（`LocalDate` 相等时 `produceState` 不会触发重组）。
 */
internal const val DAY_LABEL_TICK_MS = 60_000L
