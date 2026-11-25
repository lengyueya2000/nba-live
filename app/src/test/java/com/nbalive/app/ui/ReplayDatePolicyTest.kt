package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 录像日期筛选的可选范围（2026-11-25 新增）。
 *
 * 实测源站回放数据：30 天前 10 场、60 天前 3 场、90 天前 0 场。
 * 用户选「最近 30 天」——今天 + 往前 29 天共 30 个可选日期，
 * 超范围与未来日期必须不可选，否则选了也是空页。
 */
class ReplayDatePolicyTest {

    private val today = LocalDate.of(2026, 11, 25)

    @Test
    fun `今天与29天前可选`() {
        assertTrue(ReplayDatePolicy.isSelectable(today, today))
        assertTrue(ReplayDatePolicy.isSelectable(today.minusDays(29), today))
    }

    @Test
    fun `30天前不可选`() {
        assertFalse(ReplayDatePolicy.isSelectable(today.minusDays(30), today))
    }

    @Test
    fun `未来日期不可选`() {
        assertFalse(ReplayDatePolicy.isSelectable(today.plusDays(1), today))
    }

    @Test
    fun `日期标签格式`() {
        assertEquals("11月25日", ReplayDatePolicy.label(today))
        assertEquals("1月1日", ReplayDatePolicy.label(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `日历时间戳按UTC当天开始往返不差一天`() {
        // Material3 DatePicker 的 millis 契约是"UTC 当天开始"；
        // 若传北京时区当天开始（前一天 16:00Z），日历会把 25 号显示成 24 号（模拟器实测）。
        val millis = ReplayDatePolicy.toPickerMillis(today)
        assertEquals("时间戳必须是 UTC 整日边界", 0L, millis % 86_400_000L)
        assertEquals("往返必须还是同一天", today, ReplayDatePolicy.fromPickerMillis(millis))
    }
}
