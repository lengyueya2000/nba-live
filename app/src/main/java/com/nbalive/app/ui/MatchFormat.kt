package com.nbalive.app.ui

import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import java.time.Instant
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
    val t = startAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    if (t != null) {
        if (status == MatchStatus.LIVE && t.hour == 0 && t.minute == 0) return timeLabel
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }
    return timeLabel.substringAfterLast(' ').ifBlank { timeLabel }
}

/** 展示用日期 "MM-dd"。 */
fun Match.mmdd(): String {
    val t = startAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    if (t != null) return String.format(Locale.ROOT, "%02d-%02d", t.monthValue, t.dayOfMonth)
    return timeLabel.substringBefore(' ').ifBlank { "" }
}

/** 详情页用完整时间 "MM-dd HH:mm"；时间未知时退回源站文案。 */
fun Match.displayTime(): String {
    val t = startAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
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
