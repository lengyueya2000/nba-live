package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 详情页校正的**预算选取**（review/02-data.md P1-4）。
 *
 * ## 要解决什么
 *
 * JRS 详情页校正原本是 `toFix.take(MAX_DETAIL_FETCH)` —— 按列表顺序取前 N 场。
 * 超出的场次走"整日过完才判结束"的兜底，于是：
 *
 * > CBA 一个比赛日 19:35 同时开球 6~8 场，JRS 侧标 `LIVE` 的很容易超过 8。
 * > 第 9 场起在**当天 23:59 之前**一直显示"直播中" ——
 * > 19:35 开球、22:00 早就结束的比赛，到午夜前都还亮红。
 *
 * 这正是 v1.2.8 用户截图那个 bug 的**未覆盖余量**：主路径修了，超预算的部分没覆盖。
 *
 * ## 修法
 *
 * 1. **排序后再取**：优先校正**最早开赛**的场次 —— 那些是最可能已经结束、
 *    最需要被纠正的。JRS"直播中"行的 `startAtMillis` 是分组日期的 00:00 占位，
 *    所以跨天能分出先后；同一天内分不出，但至少不会把"肯定过期的昨天场次"
 *    排在预算之外。
 * 2. **提高预算**：详情页约 5KB，在 4 并发门限下把 8 提到 16 对源站压力差异可接受，
 *    却能让"一场比赛日"基本总是落在预算内。
 */
class DetailBudgetTest {

    private fun live(
        id: String,
        startAtMillis: Long? = null,
        jrsDetailId: String? = "d-$id",
    ) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "主$id",
        awayTeam = "客$id",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "直播中",
        startAtMillis = startAtMillis,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = jrsDetailId,
    )

    @Test
    fun `预算优先给最早开赛的场次`() {
        val matches = listOf(
            live("day3", startAtMillis = 3_000L),
            live("day1", startAtMillis = 1_000L),
            live("day2", startAtMillis = 2_000L),
        )
        val picked = selectDetailBudget(matches, budget = 2)
        assertEquals(
            "预算内必须是最早开赛的两场",
            listOf("day1", "day2"),
            picked.map { it.id },
        )
    }

    @Test
    fun `没有开赛时间的场次排在有时间的之后`() {
        val matches = listOf(
            live("unknown", startAtMillis = null),
            live("day1", startAtMillis = 1_000L),
        )
        val picked = selectDetailBudget(matches, budget = 1)
        assertEquals(listOf("day1"), picked.map { it.id })
    }

    @Test
    fun `没有详情页 id 的场次不占预算`() {
        val matches = listOf(
            live("noDetail", startAtMillis = 1_000L, jrsDetailId = null),
            live("withDetail", startAtMillis = 2_000L),
        )
        val picked = selectDetailBudget(matches, budget = 1)
        assertEquals(listOf("withDetail"), picked.map { it.id })
    }

    @Test
    fun `选取结果是确定的 不受输入顺序影响`() {
        // 同一天内的场次 startAtMillis 相同（都是 00:00 占位），
        // 排序必须有稳定的次级键，否则同一份数据两次运行会拉不同的详情页。
        val a = live("a", startAtMillis = 1_000L)
        val b = live("b", startAtMillis = 1_000L)
        val c = live("c", startAtMillis = 1_000L)
        val first = selectDetailBudget(listOf(a, b, c), budget = 2).map { it.id }
        val second = selectDetailBudget(listOf(c, a, b), budget = 2).map { it.id }
        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first)
    }

    @Test
    fun `预算为 0 时不选任何场次`() {
        assertEquals(emptyList<String>(), selectDetailBudget(listOf(live("x")), budget = 0).map { it.id })
    }

    @Test
    fun `场次少于预算时全部选中`() {
        val matches = listOf(live("a", 1_000L), live("b", 2_000L))
        assertEquals(2, selectDetailBudget(matches, budget = 16).size)
    }
}
