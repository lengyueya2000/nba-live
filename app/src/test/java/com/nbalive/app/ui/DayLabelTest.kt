package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表页"今天/明天/昨天"分组标签（review/03-ui.md P2-8 / P2-33）。
 *
 * ## 要解决什么
 *
 * `MatchListScreen` 原来的注释承诺："标签在渲染时现算，App 跨零点后
 * **不用切走页面**标签也会自动变成新的今天"。实现却做不到：
 * `val today = LocalDate.now(...)` 只在**组合**时求值一次，跨零点本身
 * 不会触发任何重组，列表静止时标签会一直停在旧值（今天被继续标成"明天"）。
 * **注释在撒谎** —— 这比缺陷本身更糟，因为它会让后来人以为这里不用管。
 *
 * 另外 `dayLabel`/`groupByDay` 是 `private` 且和 UI 绑在同一个文件里，
 * 于是"按日期分组"这条业务逻辑**无法被单测覆盖**（P2-33 只要求做这一件事）。
 *
 * ## 修法（两件事一起做）
 *
 * 1. 把 `DayGroup` / `dayLabel` / `groupByDay` 移到 `ui/MatchFormat.kt`
 *    的纯函数区（那里本来就是"展示格式化"的收敛点），改 `internal` 使其可测；
 *    时区复用同一份缓存的 `systemZone`，别再每次重组查系统时区。
 * 2. `today` 用 `produceState` + 定时器驱动，跨零点后最多 1 分钟自动修正
 *    （第 6 条守卫钉住它，因为这条行为没法用 JVM 单测直接观测）。
 *
 * ## 第 4 条测试为什么值得单独写
 *
 * 分组 key 用**绝对日期**而不是标签，是因为跨年时两个不同日期可能都显示
 * "1月1日" → 重复 key → LazyVerticalGrid 直接崩（这个仓库崩过两次）。
 * 这条把"为什么不能拿标签当 key"变成可执行断言，而不是只写在注释里。
 */
class DayLabelTest {

    @Test
    fun `今天 明天 昨天 按传入的 today 现算`() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals("今天 · 10月27日", dayLabel(today, today))
        assertEquals("明天 · 10月28日", dayLabel(today.plusDays(1), today))
        assertEquals("昨天 · 10月26日", dayLabel(today.minusDays(1), today))
    }

    @Test
    fun `跨零点后同一个日期从今天变成昨天`() {
        // 这正是 P2-8 的行为：标签是 today 的函数，today 一变标签就变。
        // 缺陷不在这个函数里，而在于旧实现从不让 today 变（见第 6 条守卫）。
        val date = LocalDate.of(2026, 10, 27)
        assertEquals("今天 · 10月27日", dayLabel(date, LocalDate.of(2026, 10, 27)))
        assertEquals("昨天 · 10月27日", dayLabel(date, LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun `其它日期只显示月日 不带前后缀`() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals("11月3日", dayLabel(LocalDate.of(2026, 11, 3), today))
        assertEquals("10月3日", dayLabel(LocalDate.of(2026, 10, 3), today))
    }

    @Test
    fun `跨年时两个不同日期的标签会撞车 所以分组键必须用绝对日期`() {
        val today = LocalDate.of(2026, 6, 1)
        val groups = groupByDay(
            listOf(
                matchOf("a", LocalDate.of(2026, 1, 1)),
                matchOf("b", LocalDate.of(2027, 1, 1)),
            ),
            today,
        )
        assertEquals("两个日期必须分成两组", 2, groups.size)
        assertEquals(
            listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)),
            groups.map { it.date },
        )
        // 两组的**标签**确实一模一样 —— 这就是不能拿标签当 key 的原因
        assertEquals(
            dayLabel(LocalDate.of(2026, 1, 1), today),
            dayLabel(LocalDate.of(2027, 1, 1), today),
        )
    }

    @Test
    fun `无时间戳的场次落在 today 这一组`() {
        val today = LocalDate.of(2026, 10, 27)
        val groups = groupByDay(listOf(matchOf("no-ts", null)), today)
        assertEquals(1, groups.size)
        assertEquals(today, groups.single().date)
    }

    // ---------------------------------------------------------------
    // 源码守卫：P2-8 的"自动跨零点"没法用 JVM 单测观测（需要 Compose 运行时），
    // 所以用它实际依赖的两件事钉住：定时器驱动的 today、以及不再每次重组查时区。
    // ---------------------------------------------------------------

    @Test
    fun `列表页的 today 必须由定时器驱动 而不是组合期快照`() {
        val code = stripComments(uiFile("screens/MatchListScreen.kt").readText())
        assertTrue(
            "MatchListScreen 里找不到 produceState：today 又变回组合期快照了，\n" +
                "跨零点后列表静止时会一直显示旧的'今天'（注释承诺过的行为做不到了）：\n" +
                "review/03-ui.md P2-8",
            code.contains("produceState"),
        )
        assertTrue(
            "today 没有按 DAY_LABEL_TICK_MS 周期性刷新，跨零点不会自动修正（P2-8）",
            code.contains("delay(DAY_LABEL_TICK_MS)"),
        )
    }

    @Test
    fun `列表页不得每次重组都去查系统时区`() {
        val code = stripComments(uiFile("screens/MatchListScreen.kt").readText())
        assertFalse(
            "MatchListScreen 直接调了 systemDefault()：应复用 MatchFormat 里缓存的 systemZone\n" +
                "（ZoneId.systemDefault() 每次调用都要查一次系统设置，这个页面每行都要用）",
            code.contains("systemDefault()"),
        )
        assertTrue(
            "列表页应当用缓存的 systemZone 取当天日期",
            code.contains("LocalDate.now(systemZone)"),
        )
    }

    // ---------------------------------------------------------------

    private fun matchOf(id: String, date: LocalDate?): Match = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "主队",
        awayTeam = "客队",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10-27 20:00",
        startAtMillis = date?.atStartOfDay(systemZone)?.toInstant()?.toEpochMilli(),
        status = MatchStatus.UPCOMING,
        signalCount = 0,
        sources = emptyList(),
    )

    private fun uiFile(relative: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui/$relative"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui/$relative"),
        )
        // 找不到就硬失败：不能用 assumeTrue 静默跳过（那等于守卫不存在）
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative，cwd=$cwd，候选=${candidates.map { it.path }}")
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")
}