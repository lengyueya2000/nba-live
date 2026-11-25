package com.nbalive.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 录像日期筛选的可选范围（2026-11-25 新增）。
 *
 * 源站回放实测：30 天前 10 场、60 天前 3 场、90 天前 0 场 ——
 * 用户选「最近 30 天」，取今天 + 往前 29 天共 30 个日期，超范围选了也是空页。
 * 纯函数，供 DatePicker 的 `SelectableDates` 复用并可单测。
 */
object ReplayDatePolicy {
    const val MAX_DAYS = 30L

    fun isSelectable(date: LocalDate, today: LocalDate): Boolean =
        !date.isBefore(today.minusDays(MAX_DAYS - 1L)) && !date.isAfter(today)

    /** 顶部日期按钮的短标签。 */
    fun label(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    /**
     * 业务日期 → Material3 `DatePickerState` 的 `selectedDateMillis`。
     *
     * ⚠️ DatePicker 的 millis 契约是 **UTC 当天开始**（`2026-11-25T00:00Z`），
     * 不是北京时区当天开始（那会是前一天 16:00Z）。模拟器实测：
     * 传后者会让日历把「11月25日」显示成「11月24日」，差一天。
     */
    fun toPickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** 日历返回的 millis → 业务日期（与 [toPickerMillis] 严格互逆）。 */
    fun fromPickerMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
