package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并两个源时的状态取舍。
 *
 * ⚠️ 原来的实现是"后合并的赢"（JRS 后合并，直接用 JRS 的状态），
 * 但 JRS 列表的"直播中"会滞后好几小时，
 * 而 JRKAN 自带 MM-dd HH:mm 可以做时钟校正 —— 合并时不该把更准的状态覆盖掉。
 */
class MergeStatusTest {

    private fun match(
        id: String = "m1",
        home: String = "湖人",
        away: String = "勇士",
        status: MatchStatus = MatchStatus.LIVE,
        day: String = "2026-10-24",
    ) = Match(
        id = id,
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = "$day 10:00",
        startAtMillis = null,
        status = status,
        signalCount = 0,
        sources = emptyList(),
    )

    @Test
    fun `JRS 的滞后 LIVE 不能盖掉 JRKAN 已结束的准确状态`() {
        // JRKAN 先合并（更准：已结束），JRS 后合并（滞后：还标直播中）
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(id = "jrkan-1", status = MatchStatus.FINISHED)),
            jrkan = listOf(match(id = "jrkan-1", status = MatchStatus.FINISHED)),
        )
        // 两种情况都是 FINISHED，结果应为 FINISHED
        assertEquals(MatchStatus.FINISHED, merged.first().status)
    }

    @Test
    fun `已结束的状态不会被直播中覆盖`() {
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(status = MatchStatus.LIVE)),
            jrkan = listOf(match(status = MatchStatus.FINISHED)),
        )
        assertEquals(
            "更可信的 FINISHED 不该被较不可信的 LIVE 覆盖",
            MatchStatus.FINISHED,
            merged.first().status,
        )
    }

    @Test
    fun `直播中不会被未开赛覆盖`() {
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(status = MatchStatus.UPCOMING)),
            jrkan = listOf(match(status = MatchStatus.LIVE)),
        )
        assertEquals(MatchStatus.LIVE, merged.first().status)
    }

    @Test
    fun `合并后 id 必须唯一`() {
        val repo = MatchRepository()
        // ⚠️ 只读审计 review/audit-guards.md A5：原来两个源用的是**同一批 fixture**
        // （同队名 + 同日期 + 同 id），merge 后 size == 1，
        // 于是 `ids.size == ids.distinct().size` 在单元素时**恒真** ——
        // 把 merge 里的 id 唯一化整段删掉也照样绿（审计实测）。
        //
        // 真正会触发唯一化的是「**同 id、不同对阵**」：normalizeKey 不同 ⇒ 两场都必须保留，
        // 但 id 撞车 ⇒ 必须加后缀。只有这种夹具才测得到"合并后 id 不能重复"。
        val jrs = listOf(match(id = "same", home = "A队", away = "B队"))
        val jrkan = listOf(match(id = "same", home = "C队", away = "D队"))
        val inputs = jrs + jrkan
        // 前置（非空性锚点）：夹具里确实有重复 id —— 否则这条又会退化成"测不到东西"
        assertTrue(
            "夹具必须真的含重复 id（这是「合并后 id 唯一」的前提），实际 id=${inputs.map { it.id }}",
            inputs.map { it.id }.size > inputs.map { it.id }.distinct().size,
        )

        val merged = repo.mergeForTest(jrs = jrs, jrkan = jrkan)

        // 把"旧断言"原样跑一遍：单元素时 `size == distinct.size` 是 1 == 1，**恒真** ——
        // 它给不出任何信息（这一步不是守卫，只是把"为什么它永远不会红"钉成可执行的对照）。
        val legacyMerged = repo.mergeForTest(
            jrs = listOf(match(id = "legacy")),
            jrkan = listOf(match(id = "legacy")),
        )
        assertEquals(
            "旧夹具 merge 后只有一场 —— 这就是审计 A5 说的「单元素恒真」，本条测试不能再用它",
            1,
            legacyMerged.size,
        )
        val legacyIds = legacyMerged.map { it.id }
        assertEquals(
            "旧写法在单元素夹具上恒真（1 == 1）—— 审计 A5 的原始形态",
            legacyIds.size,
            legacyIds.distinct().size,
        )

        // ① 两场不同对阵都必须活下来：删掉 id 唯一化后，末尾 `distinctBy { it.id }`
        //    会把其中一场**静默吞掉**（用户看不到那一场）
        assertEquals(
            "同 id、不同对阵的两场必须都保留（id 撞车不能丢比赛）",
            2,
            merged.size,
        )
        val ids = merged.map { it.id }
        // ② 这才是"合并后 id 必须唯一"：size ≥ 2 时才不可能恒真
        assertEquals("合并后 id 不能重复", ids.size, ids.distinct().size)
        assertEquals(
            "被唯一化的那场应当带 # 后缀留下来，而不是整场消失",
            listOf("same", "same#1"),
            ids.sorted(),
        )
    }

    @Test
    fun `合并后队名顺序颠倒也要归到同一场`() {
        val repo = MatchRepository()
        val merged = repo.mergeForTest(
            jrs = listOf(match(id = "a", home = "湖人", away = "勇士")),
            jrkan = listOf(match(id = "b", home = "勇士", away = "湖人")),
        )
        assertEquals("主客队颠倒应该合并成一场", 1, merged.size)
    }
}
