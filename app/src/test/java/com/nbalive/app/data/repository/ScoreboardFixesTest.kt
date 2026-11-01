package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.data.remote.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 比分仓库本轮的判据（task-6 / fix-data）。
 *
 *  - **data.md D7**：抓取失败**不许覆盖**上一次成功快照，`Snapshot` 带 `fetchedAtMs`
 *    （VM 侧据此判陈旧并剔除，见 fix-ui-state）；
 *  - **data.md D8**：`load(enabled)` 必须真的消费 `enabled`（空集直接 EMPTY，不发请求、不写缓存）；
 *  - **data.md D6 后一半**：ESPN 的 `Dallas Mavericks` 与源站「小牛」能配上（中文别名集合）。
 */
class ScoreboardFixesTest {

    private val lock = Any()

    private val sample = """
        {"events":[{"competitions":[
          {"competitors":[
            {"homeAway":"home","score":"112","team":{"displayName":"Golden State Warriors","name":"Warriors"}},
            {"homeAway":"away","score":"95","team":{"displayName":"Los Angeles Lakers","name":"Lakers"}}
          ],
          "status":{"displayClock":"12:00","period":4,"type":{"state":"post","shortDetail":"Final"}}}]}]}
    """.trimIndent()

    private val mavericksSample = """
        {"events":[{"competitions":[
          {"competitors":[
            {"homeAway":"home","score":"99","team":{"displayName":"Dallas Mavericks","name":"Mavericks"}},
            {"homeAway":"away","score":"88","team":{"displayName":"Golden State Warriors","name":"Warriors"}}
          ],
          "status":{"displayClock":"12:00","period":4,"type":{"state":"post","shortDetail":"Final"}}}]}]}
    """.trimIndent()

    /**
     * 夹具自检：样本必须是**合法 JSON 且能被解析出 1 场**。
     *
     * ⚠️ 加这条的来由（2026-11-01 实测）：第一版样本漏了"事件对象 + events 数组"的收尾
     * （写成 `}}}]}`，少一个 `]}`），`ConvertFrom-Json` 与 `EspnScoreboard.parse`
     * 都判它非法 → `load()` 拿到空快照 → 本类三条用例一起红，
     * 而失败信息只会说"第一次必须成功"，完全看不出是**夹具**坏了。
     *
     * 让夹具自己先以明确的文案失败，比让被测逻辑替它背锅强 ——
     * 这正是本仓库"非空性锚点"那条纪律的用法。
     */
    @Test
    fun `夹具自检 样本必须是可解析的合法 JSON`() {
        val parsed = EspnScoreboard.parse(sample)
            ?: error("sample 不是合法 JSON —— 夹具自己先失败，别让被测逻辑背锅：\n$sample")
        assertEquals("sample 必须恰好解析出 1 场", 1, parsed.size)
        assertEquals("夹具自检：主队分数必须真的读出来", 112, parsed.single().homeScore)

        val mavericks = EspnScoreboard.parse(mavericksSample)
            ?: error("mavericksSample 不是合法 JSON：\n$mavericksSample")
        assertEquals("mavericksSample 必须恰好解析出 1 场", 1, mavericks.size)
        assertEquals("Dallas Mavericks", mavericks.single().homeName)
    }

    @Test
    fun `连续失败时保留上一次成功的比分 且 fetchedAtMs 不变`() = runBlocking {
        var failing = false
        var calls = 0
        // ttlMs = 0：每次调用都真的走一遍抓取，判据才能看到"失败"这一轮
        val repo = ScoreboardRepository(
            fetch = { _, _ ->
                synchronized(lock) { calls++ }
                if (failing) FetchResult.Unreachable("offline") else FetchResult.Ok(sample)
            },
            ttlMs = 0L,
        )

        val first = repo.load()
        assertFalse("第一次必须成功", first.isEmpty())
        val createdAt = first.fetchedAtMs
        assertTrue("成功快照必须带抓取时刻（供 UI 判陈旧）", createdAt > 0L)

        failing = true
        val second = repo.load()
        val third = repo.load()

        assertTrue("两次失败之后不许把上一次成功清掉（实际 calls=$calls）", third.games.isNotEmpty())
        assertEquals(
            "fetchedAtMs 必须停在**上一次成功**的时刻（UI 靠它剔除陈旧比分，" +
                "若被失败的当前时刻覆盖，陈旧比分就永远看起来是新的）",
            createdAt,
            third.fetchedAtMs,
        )
        assertNotNull("分数仍必须查得到", repo.scoreFor(third, match("勇士", "湖人")))
        assertEquals(112, repo.scoreFor(third, match("勇士", "湖人"))!!.homeScore)
    }

    @Test
    fun `首次就失败时返回空快照 而不是抛异常`() = runBlocking {
        val repo = ScoreboardRepository(
            fetch = { _, _ -> FetchResult.Unreachable("offline") },
            ttlMs = 0L,
        )
        assertTrue("没成功过就不该有陈旧值", repo.load().isEmpty())
    }

    @Test
    fun `enabled 为空集时一个请求都不发 也不写缓存`() = runBlocking {
        var calls = 0
        val repo = ScoreboardRepository(
            fetch = { _, _ ->
                synchronized(lock) { calls++ }
                FetchResult.Ok(sample)
            },
        )

        val empty = repo.load(enabled = emptySet())
        assertTrue("空集必须直接给 EMPTY", empty.isEmpty())
        assertEquals("空集时一次 ESPN 请求都不许发（D8）", 0, calls)
        assertEquals("EMPTY 不带抓取时刻", 0L, empty.fetchedAtMs)

        // 不写缓存：紧接着全开时必须真的去抓（否则 TTL 内会拿到"空比分板"）
        val full = repo.load()
        assertTrue("空集不许污染缓存：全开时必须真的发请求（实际 $calls 次）", calls >= 1)
        assertFalse("全开时必须拿到真实比分", full.isEmpty())
    }

    @Test
    fun `源站写小牛也能配上 ESPN 的 Mavericks`() = runBlocking {
        val repo = ScoreboardRepository(
            fetch = { url, _ ->
                if (url.contains("basketball/nba")) FetchResult.Ok(mavericksSample)
                else FetchResult.Unreachable("no")
            },
        )
        val snap = repo.load()

        assertNotNull(
            "源站写「小牛」时必须能配上（旧实现每个昵称只映射一个中文名，永远匹配不上，" +
                "卡片静默不画比分）",
            repo.scoreFor(snap, match("小牛", "勇士")),
        )
        assertNotNull("规范写法「独行侠」同样要能配上（同一别名集合）", repo.scoreFor(snap, match("独行侠", "勇士")))
        assertNull("主客方向仍必须一致", repo.scoreFor(snap, match("勇士", "小牛")))
    }

    private fun match(home: String, away: String) = Match(
        id = "m1",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = "10:00",
        startAtMillis = null,
        status = com.nbalive.app.data.model.MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = null,
    )
}

/**
 * `EspnScoreboard.JsonCursor` 的边界校验（**data.md D15 半条**）。
 *
 * 复核把 D15 的表述修正为："`jsonString` 的 needle 含两侧引号，
 * 故『命中更长的 key』不成立；真实残余风险只剩『文档里更早出现同名嵌套 key』"。
 * 本文件把这两点半钉成可执行证据：
 *  - 后缀键**不可能**被误命中（前边界校验在起作用）；
 *  - 更早出现的同名嵌套 key 会被先取到 —— 这是**已知残余风险**，
 *    不是 bug 修复项（文档顺序决定，当前所有调用点的字段都排在文档靠后/唯一位置）。
 *
 * ⚠️ `JsonCursor` 是 `internal`，与生产同模块的单测可直接引用；
 * 生产侧的第二个消费点由 fix-ui-state 的 `ReleaseRepository`（`data/remote`）落地
 * （VM 里那份手写 `jsonString` 已随之删除），本文件只钉边界语义。
 */
class EspnJsonCursorTest {

    @Test
    fun `后缀键不可能被误命中`() {
        val json = """{"other_body":"x"}"""
        assertNull(
            "「other_body」里不含两侧带引号的 body 键，必须取不到（而不是取到 x）",
            EspnScoreboard.JsonCursor(json).string("body"),
        )
        assertEquals("y", EspnScoreboard.JsonCursor("""{"body":"y"}""").string("body"))
    }

    @Test
    fun `更长的 key 不会被短的 key 命中`() {
        // 复核修正过的论证：needle 含两侧引号 ⇒ `"homeScore"` 打不到 `"awayHomeScore"`
        val json = """{"homeScoreX":"999","awayScoreX":"888","score":"1"}"""
        val cursor = EspnScoreboard.JsonCursor(json)
        assertNull(cursor.string("homeScore"))
        assertNull(cursor.string("awayScore"))
        assertEquals("1", cursor.string("score"))
    }

    @Test
    fun `更早出现的同名嵌套 key 会被先取到 已知残余风险`() {
        // 特征测试（写实际行为，不写"漂亮的说法"）：前一个非空白字符是 `{`，
        // 于是嵌套里的 body 先被取到。当前调用点的字段不会这样嵌套出现。
        val json = """{"note":{"body":"x"},"body":"y"}"""
        assertEquals("x", EspnScoreboard.JsonCursor(json).string("body"))
    }

    @Test
    fun `数字字段与字符串字段各走各的路`() {
        val json = """{"period":4,"displayClock":"12:00","name":"Lakers"}"""
        val cursor = EspnScoreboard.JsonCursor(json)
        assertEquals(4, cursor.int("period"))
        assertEquals("12:00", cursor.string("displayClock"))
        assertEquals("Lakers", cursor.string("name"))
    }
}