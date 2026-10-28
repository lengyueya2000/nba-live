package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
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
        // ⚠️ 只读审计 review/audit-guards.md A15：原来这里是**文件级谓词**
        // （`code.contains("produceState")` / `contains("delay(DAY_LABEL_TICK_MS)")`），
        // `MatchListScreen.kt` 里任意位置满足即可 —— 不绑定「今天/明天」那个 today 状态：
        // 把 produceState 挪到别的 composable、或删掉 ticker 只留一处 delay，都可能照样绿。
        // 现在收进 **MatchGrid** 的函数体里判（today 实际就算在这里）。
        val violations = todayTickerViolations(uiFile("screens/MatchListScreen.kt").readText())
        assertTrue(
            "列表页的 today 必须由定时器驱动（review/03-ui.md P2-8）：\n" +
                violations.joinToString("\n").prependIndent("  ") +
                "\n\n跨零点后列表静止时标签会一直停在旧值（今天被继续标成「明天」）。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `today 定时器判据自检 组合期快照与挪走 ticker 都要能抓到`() {
        val compliant = """
            @Composable
            private fun MatchGrid(matches: List<Match>, onMatchClick: (Match) -> Unit) {
                val today by produceState(initialValue = LocalDate.now(systemZone)) {
                    while (true) {
                        delay(DAY_LABEL_TICK_MS)
                        value = LocalDate.now(systemZone)
                    }
                }
                val groups = remember(matches, today) { groupByDay(matches, today) }
            }
        """.trimIndent()
        assertTrue(
            "判据误报了合规写法：\n" + todayTickerViolations(compliant).joinToString("\n"),
            todayTickerViolations(compliant).isEmpty(),
        )

        // 违规样本 1：回到组合期快照（P2-8 的原始缺陷形态）
        val snapshot = """
            @Composable
            private fun MatchGrid(matches: List<Match>) {
                val today = LocalDate.now(systemZone)
                val groups = groupByDay(matches, today)
            }
        """.trimIndent()
        assertTrue(
            "组合期快照必须报违规（这正是 P2-8 要修的原始缺陷）",
            todayTickerViolations(snapshot).isNotEmpty(),
        )

        // 违规样本 2：文件里有 delay，但 today 依旧是快照（旧的文件级谓词会放过）
        val staleWithDelay = """
            @Composable
            private fun MatchGrid(matches: List<Match>) {
                val today = LocalDate.now(systemZone)
                LaunchedEffect(Unit) { delay(DAY_LABEL_TICK_MS) }
            }
        """.trimIndent()
        assertTrue(
            "文件里有 delay、但 today 不是 produceState 驱动时必须报违规（文件级谓词在这里漏判）",
            todayTickerViolations(staleWithDelay).isNotEmpty(),
        )

        // 违规样本 3：produceState 挪到别的 composable 里（"文件里有"但 today 没用上）
        val movedAway = """
            @Composable
            private fun OtherThing() {
                val today by produceState(initialValue = LocalDate.now(systemZone)) {
                    while (true) {
                        delay(DAY_LABEL_TICK_MS)
                        value = LocalDate.now(systemZone)
                    }
                }
            }

            @Composable
            private fun MatchGrid(matches: List<Match>) {
                val today = LocalDate.now(systemZone)
            }
        """.trimIndent()
        assertTrue(
            "ticker 被挪到别的函数、MatchGrid 里退回快照时必须报违规（函数体判据的价值就在这里）",
            todayTickerViolations(movedAway).isNotEmpty(),
        )
    }

    @Test
    fun `列表页不得每次重组都去查系统时区`() {
        val code = GuardSourceScan.stripNonCode(uiFile("screens/MatchListScreen.kt").readText())
        val body = functionBody(code, "fun MatchGrid(")
        assertFalse(
            "MatchListScreen 直接调了 systemDefault()：应复用 MatchFormat 里缓存的 systemZone\n" +
                "（ZoneId.systemDefault() 每次调用都要查一次系统设置，这个页面每行都要用）",
            code.contains("systemDefault()"),
        )
        // ⚠️ 收紧到 today 所在的函数体（审计 A15）：原来整个文件任意位置出现 `LocalDate.now(systemZone)` 就算过
        assertTrue(
            "MatchGrid 应当用缓存的 systemZone 取当天日期（`LocalDate.now(systemZone)`）",
            Regex("""LocalDate\.now\(\s*systemZone\s*\)""").containsMatchIn(body),
        )
    }

    // ---------------------------------------------------------------

    /**
     * 判据：`MatchGrid` 的 today 必须由**定时器**驱动（返回违规说明，空 = 达标）。
     *
     * 输入是原始源码文本；内部先剔注释与字符串字面量（铁律①），
     * 判据锚在 `fun MatchGrid(` 的**函数体**（审计 A15：文件级谓词会放过"挪到别处"）。
     */
    private fun todayTickerViolations(rawCode: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawCode)
        // ⚠️ 函数体缺失或不是花括号体时，[functionBody] 内部直接 error()（铁律③）。
        // 注意：**全注释的函数体**剔注释后只剩空白 —— 那是"没有合规内容"，不是"找不到锚点"。
        val body = functionBody(code, "fun MatchGrid(")
        val out = mutableListOf<String>()
        if (!Regex("""\bval\s+today\s+by\s+produceState\s*\(""").containsMatchIn(body)) {
            out += "① today 不是 `by produceState(...)` 驱动的：组合期快照跨零点不会更新"
        }
        if (!Regex("""delay\(\s*DAY_LABEL_TICK_MS\s*\)""").containsMatchIn(body)) {
            out += "② 函数体里没有 `delay(DAY_LABEL_TICK_MS)` 周期刷新（跨零点不会自动修正）"
        }
        if (!Regex("""value\s*=\s*LocalDate\.now\(\s*systemZone\s*\)""").containsMatchIn(body)) {
            out += "③ ticker 里没有重算 `value = LocalDate.now(systemZone)`"
        }
        if (!Regex("""LocalDate\.now\(\s*systemZone\s*\)""").containsMatchIn(body)) {
            out += "④ 没有复用缓存的 systemZone（应走 LocalDate.now(systemZone)）"
        }
        return out
    }

    private fun functionSpan(code: String, marker: String): Pair<Int, Int>? {
        val start = code.indexOf(marker)
        if (start < 0) return null
        var i = start + marker.length - 1
        var paren = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                // 参数表之外出现 `=` 说明是表达式体函数，没有花括号体
                '=' -> if (paren == 0) return null
                '{' -> if (paren == 0) return start to i
            }
            i++
        }
        return null
    }

    /** marker 的花括号函数体；找不到/非花括号体直接 [error]（铁律③，绝不静默全绿）。 */
    private fun functionBody(code: String, marker: String): String {
        val open = functionSpan(code, marker)?.second
            ?: error("源码里找不到 $marker 的花括号函数体 —— 守卫会静默全绿")
        return balanced(code, open)
    }

    private fun balanced(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        error("花括号不配对 —— 守卫会静默全绿")
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
}