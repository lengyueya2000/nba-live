package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.repository.ScoreboardRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」判据（2026-10-31 用户需求）。
 *
 * 版本比较的坑全在边界上：字符串比较会把 `1.2.9` 判成比 `1.2.10` 新。
 */
class UpdateCheckPolicyTest {

    @Test
    fun `按数字段比较 不是按字符串`() {
        assertTrue("1.2.10 比 1.2.9 新（字符串比较会判反）", UpdateCheckPolicy.isRemoteNewer("1.2.10", "1.2.9"))
        assertFalse("1.2.9 不比 1.2.10 新", UpdateCheckPolicy.isRemoteNewer("1.2.9", "1.2.10"))
        assertTrue("1.2.21 比 1.2.3 新", UpdateCheckPolicy.isRemoteNewer("1.2.21", "1.2.3"))
    }

    @Test
    fun `相同版本不算有更新`() {
        assertFalse(UpdateCheckPolicy.isRemoteNewer("1.2.23", "1.2.23"))
        assertFalse("段数不同但数值相同也算相同", UpdateCheckPolicy.isRemoteNewer("1.2", "1.2.0"))
        assertFalse(UpdateCheckPolicy.isRemoteNewer("1.2.0", "1.2"))
    }

    @Test
    fun `能处理 v 前缀与后缀`() {
        assertTrue("GitHub tag 常带 v", UpdateCheckPolicy.isRemoteNewer("v1.2.24", "1.2.23"))
        assertTrue("预发布后缀要能剥掉", UpdateCheckPolicy.isRemoteNewer("1.2.24-beta.1", "1.2.23"))
        assertEquals("normalize 要去掉 v 前缀", "1.2.24", UpdateCheckPolicy.normalize("v1.2.24"))
        // ⚠️ 后缀里的数字也会被当成段（`beta.1` → 1），这是**有意**的取舍：
        // 它只会让"预发布版看起来比正式版新"，而 GitHub release 的 tag 都是正式版，
        // 真正会出现的形态是 `v1.2.24`。为这个边界加"预发布降级"逻辑不值当，
        // 所以断言写实际行为，而不是写一个漂亮但不成立的说法。
        assertEquals("1.2.24.1", UpdateCheckPolicy.normalize("1.2.24-beta.1"))
    }

    @Test
    fun `拿不到远端版本时静默 不提示`() {
        assertFalse("null = 网络失败/限流，不能当成有更新", UpdateCheckPolicy.isRemoteNewer(null, "1.2.23"))
        assertFalse(UpdateCheckPolicy.isRemoteNewer("", "1.2.23"))
        assertFalse("本地版本读不出来也不提示（宁可不打扰）", UpdateCheckPolicy.isRemoteNewer("1.2.24", null))
    }

    @Test
    fun `decide 只在真的更新时给 Available`() {
        val newer = UpdateCheckPolicy.decide("v1.2.24", "1.2.23", "https://x/y", "notes")
        assertTrue(newer is UpdateCheckPolicy.Decision.Available)
        assertEquals("1.2.24", (newer as UpdateCheckPolicy.Decision.Available).latest)
        assertEquals("https://x/y", newer.downloadUrl)

        assertEquals(
            UpdateCheckPolicy.Decision.UpToDate,
            UpdateCheckPolicy.decide("v1.2.23", "1.2.23", "https://x/y", ""),
        )
        assertEquals(
            "网络失败必须静默",
            UpdateCheckPolicy.Decision.UpToDate,
            UpdateCheckPolicy.decide(null, "1.2.23", "https://x/y", ""),
        )
    }

    @Test
    fun `状态文案`() {
        val available = UpdateCheckPolicy.decide("v1.2.24", "1.2.23", "u", "")
        assertTrue(UpdateCheckPolicy.statusText(available).contains("1.2.24"))
        assertEquals("已是最新", UpdateCheckPolicy.statusText(UpdateCheckPolicy.Decision.UpToDate))
    }

    @Test
    fun `兜底下载地址`() {
        assertEquals(
            "https://github.com/lengyueya2000/nba-live/releases/tag/v1.2.24",
            UpdateCheckPolicy.fallbackReleaseUrl("lengyueya2000", "nba-live", "v1.2.24"),
        )
    }
}

/**
 * ESPN 比分板解析（2026-10-31 用户需求「比赛比分」）。
 *
 * 用**真实响应裁剪出的最小样本**，不用手编的理想 JSON ——
 * 手编的样本会漏掉真实数据里的形态（比如未开赛时 `score` 字段根本不存在）。
 */
class EspnScoreboardTest {

    /** 真实响应里摘出的两场：一场已结束、一场未开赛（无 score 字段）。 */
    private val sample = """
        {"events":[
          {"id":"401909285","date":"2026-10-31T02:00Z","name":"Los Angeles Lakers at Golden State Warriors",
           "competitions":[{
             "competitors":[
               {"homeAway":"home","score":"112","team":{"displayName":"Golden State Warriors","name":"Warriors"}},
               {"homeAway":"away","score":"95","team":{"displayName":"Los Angeles Lakers","name":"Lakers"}}
             ],
             "status":{"clock":720.0,"displayClock":"12:00","period":4,
                       "type":{"state":"post","description":"Final","shortDetail":"Final"}}
           }]},
          {"id":"401909300","date":"2026-11-01T02:00Z","name":"Phoenix Suns at Denver Nuggets",
           "competitions":[{
             "competitors":[
               {"homeAway":"home","team":{"displayName":"Denver Nuggets","name":"Nuggets"}},
               {"homeAway":"away","team":{"displayName":"Phoenix Suns","name":"Suns"}}
             ],
             "status":{"clock":0.0,"displayClock":"0:00","period":0,
                       "type":{"state":"pre","description":"Scheduled","shortDetail":"11/1 - 10:00 PM EDT"}}
           }]}
        ]}
    """.trimIndent()

    @Test
    fun `解析出两场并区分主客`() {
        val games = EspnScoreboard.parse(sample)
        assertNotNull("真实形态的样本必须能解析", games)
        assertEquals(2, games!!.size)
        val g = games[0]
        assertEquals("Golden State Warriors", g.homeName)
        assertEquals("Los Angeles Lakers", g.awayName)
        assertEquals(112, g.homeScore)
        assertEquals(95, g.awayScore)
        assertEquals("post", g.state)
        assertEquals(4, g.period)
    }

    @Test
    fun `未开赛的场次没有比分 不能变成 0`() {
        val g = EspnScoreboard.parse(sample)!![1]
        assertEquals("pre", g.state)
        assertNull("ESPN 未开赛时不给 score 字段，必须保持 null", g.homeScore)
        assertNull(g.awayScore)
    }

    @Test
    fun `ISO 时间解析`() {
        assertEquals(
            "2026-10-31T02:00Z 必须是 UTC 的 02:00",
            java.time.Instant.parse("2026-10-31T02:00:00Z").toEpochMilli(),
            EspnScoreboard.parseIso8601("2026-10-31T02:00Z"),
        )
        assertNull(EspnScoreboard.parseIso8601("not-a-date"))
        assertNull(EspnScoreboard.parseIso8601(null))
    }

    @Test
    fun `畸形或空响应给 null 而不是抛异常`() {
        assertNull(EspnScoreboard.parse(null))
        assertNull(EspnScoreboard.parse(""))
        assertNull("没有 events 字段", EspnScoreboard.parse("""{"foo":1}"""))
        assertNull("不是 JSON", EspnScoreboard.parse("<html>403</html>"))
        assertEquals("空 events 是合法结果", emptyList<Any>(), EspnScoreboard.parse("""{"events":[]}"""))
    }

    @Test
    fun `键名不能被更长的键误命中`() {
        // "score" 若用裸 indexOf 会命中 "awayScore"/"homeScore" 之类的后缀键
        val tricky = """
            {"events":[{"date":"2026-10-31T02:00Z","competitions":[{
              "homeScoreX":"999","awayScoreX":"888",
              "competitors":[
                {"homeAway":"home","score":"1","team":{"displayName":"A","name":"A"}},
                {"homeAway":"away","score":"2","team":{"displayName":"B","name":"B"}}
              ],
              "status":{"period":1,"type":{"state":"in"}}
            }]}]}
        """.trimIndent()
        val g = EspnScoreboard.parse(tricky)!!.single()
        assertEquals("必须取到真正的 score 而不是 homeScoreX", 1, g.homeScore)
        assertEquals(2, g.awayScore)
    }
}

/**
 * 中文队名映射（2026-10-31 用户需求「比赛比分」）。
 *
 * 源站是中文名、ESPN 是英文名，两边没有共同 id，只能按名字对。
 */
class TeamNameBridgeTest {

    @Test
    fun `NBA 全 30 队都能认出来`() {
        val pairs = mapOf(
            "Golden State Warriors" to "勇士",
            "Los Angeles Lakers" to "湖人",
            "LA Clippers" to "快船",
            "Los Angeles Clippers" to "快船",
            "Philadelphia 76ers" to "76人",
            "Minnesota Timberwolves" to "森林狼",
            "Portland Trail Blazers" to "开拓者",
            "Oklahoma City Thunder" to "雷霆",
            "New Orleans Pelicans" to "鹈鹕",
            "San Antonio Spurs" to "马刺",
        )
        for ((espn, cn) in pairs) {
            assertEquals("$espn 应映射成 $cn", cn, TeamNameBridge.toChinese(espn))
        }
    }

    @Test
    fun `直接给昵称也能认`() {
        assertEquals("湖人", TeamNameBridge.toChinese("Lakers"))
        assertEquals("勇士", TeamNameBridge.toChinese("warriors"))
    }

    @Test
    fun `认不出来返回 null 不许瞎猜`() {
        assertNull(TeamNameBridge.toChinese("Guangdong Southern Tigers"))
        assertNull(TeamNameBridge.toChinese(""))
        assertNull(TeamNameBridge.toChinese(null))
    }

    @Test
    fun `找比赛时主客方向必须一致`() {
        val games = listOf(
            EspnScoreboard.GameScore("Golden State Warriors", "Los Angeles Lakers", 112, 95, "post", "Final", 4, 0L),
        )
        assertNotNull("主客都对得上", TeamNameBridge.findGame(games, "勇士", "湖人"))
        assertNull(
            "方向反了不能匹配（比分会被画到对面去）",
            TeamNameBridge.findGame(games, "湖人", "勇士"),
        )
        assertNull(TeamNameBridge.findGame(games, "勇士", "太阳"))
    }

    @Test
    fun `状态文案`() {
        assertEquals(
            "第3节 05:21",
            TeamNameBridge.statusLabel(EspnScoreboard.GameScore("A", "B", 1, 2, "in", "05:21", 3, 0L)),
        )
        assertEquals("已结束", TeamNameBridge.statusLabel(EspnScoreboard.GameScore("A", "B", 1, 2, "post", "Final", 4, 0L)))
        assertNull(
            "未开赛不显示状态文案（卡片自己有开赛时间）",
            TeamNameBridge.statusLabel(EspnScoreboard.GameScore("A", "B", null, null, "pre", "", 0, 0L)),
        )
        assertEquals(
            "加时要能区分",
            "加时1 02:00",
            TeamNameBridge.statusLabel(EspnScoreboard.GameScore("A", "B", 1, 2, "in", "02:00", 5, 0L)),
        )
    }

    @Test
    fun `未开赛不显示比分`() {
        assertNull(
            "未开赛给 0-0 会让用户以为比赛已经开打",
            TeamNameBridge.displayableScore(EspnScoreboard.GameScore("A", "B", 0, 0, "pre", "", 0, 0L)),
        )
        assertEquals(
            112 to 95,
            TeamNameBridge.displayableScore(
                EspnScoreboard.GameScore("A", "B", 112, 95, "post", "Final", 4, 0L),
            ),
        )
        assertNull(
            "字段缺失也不显示",
            TeamNameBridge.displayableScore(EspnScoreboard.GameScore("A", "B", null, 95, "in", "", 1, 0L)),
        )
    }
}

/**
 * 比分仓库的**行为**：CBA 不发请求、失败静默、缓存生效。
 *
 * 这些是"用户会不会为此付出代价"的地方：
 *  - CBA 明知 400 还去打 → 白耗流量；
 *  - 拿不到就抛异常 → 会把整个列表拖下水（比分只是锦上添花）。
 */
class ScoreboardRepositoryTest {

    private val sample = """
        {"events":[{"date":"2026-10-31T02:00Z","competitions":[{
          "competitors":[
            {"homeAway":"home","score":"112","team":{"displayName":"Golden State Warriors","name":"Warriors"}},
            {"homeAway":"away","score":"95","team":{"displayName":"Los Angeles Lakers","name":"Lakers"}}
          ],
          "status":{"displayClock":"12:00","period":4,"type":{"state":"post","shortDetail":"Final"}}
        }]}]}
    """.trimIndent()

    @Test
    fun `成功时能按场次查到比分`() = runBlocking {
        val repo = ScoreboardRepository(fetch = { url, _ ->
            if (url.contains("basketball/nba")) FetchResult.Ok(sample) else FetchResult.Unreachable("no")
        })
        val snap = repo.load()
        // ⚠️ 主客顺序必须与 ESPN 的 home/away 一致（样本里 home=Warriors、away=Lakers）：
        // 写反了就该查不到 —— 那是 TeamNameBridge.findGame 的有意行为，
        // 比"把比分画到对面球队身上"好得多。
        val game = repo.scoreFor(snap, match("勇士", "湖人", League.NBA))
        assertNotNull("NBA 场次必须能查到比分", game)
        assertEquals(112, game!!.homeScore)
        assertNull(
            "主客写反时不许匹配（否则比分会被画到对面）",
            repo.scoreFor(snap, match("湖人", "勇士", League.NBA)),
        )
    }

    @Test
    fun `CBA 场次一个请求都不发`() = runBlocking {
        val seen = mutableListOf<String>()
        val repo = ScoreboardRepository(fetch = { url, _ ->
            synchronized(seen) { seen += url }
            FetchResult.Ok(sample)
        })
        repo.load()
        assertTrue(
            "实测 ESPN 的 CBA 端点返回 HTTP 400：明知拿不到还去打只是白耗用户流量。实际请求：$seen",
            seen.none { it.contains("cba") },
        )
    }

    @Test
    fun `网络失败时静默给空表 不抛异常`() = runBlocking {
        val repo = ScoreboardRepository(fetch = { _, _ -> FetchResult.Unreachable("offline") })
        val snap = repo.load()
        assertTrue("失败必须是空表而不是异常（比分拿不到不该拖垮列表）", snap.isEmpty())
        assertNull(repo.scoreFor(snap, match("湖人", "勇士", League.NBA)))
    }

    @Test
    fun `返回垃圾内容也不抛`() = runBlocking {
        val repo = ScoreboardRepository(fetch = { _, _ -> FetchResult.Ok("<html>403 Forbidden</html>") })
        assertTrue(repo.load().isEmpty())
    }

    @Test
    fun `短时间内重复调用走缓存 不重复打 ESPN`() = runBlocking {
        var calls = 0
        val repo = ScoreboardRepository(fetch = { _, _ ->
            calls++
            FetchResult.Ok(sample)
        })
        repo.load()
        repo.load()
        repo.load()
        assertEquals("20 秒 TTL 内只该打一次（45 秒轮询节奏下这是唯一一次）", 1, calls)
    }

    private fun match(home: String, away: String, league: League) = Match(
        id = "m1",
        league = league,
        leagueRawName = league.name,
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
 * 三项新功能的**接线**守卫（下拉刷新 / 检查更新 / 比分）。
 *
 * 本仓库最忌「判据写了没人消费」= 假安全感，所以每一环都扫源码钉住。
 */
class NewFeaturesWiringTest {

    @Test
    fun `列表页必须真的挂上下拉刷新`() {
        val body = GuardSourceScan.functionBody(source("MatchListScreen.kt"), "fun MatchListScreen(")
        assertTrue(
            "MatchListScreen 没有 PullToRefreshBox：下拉刷新没接上\n实际片段：\n" + body.take(400),
            body.contains("PullToRefreshBox("),
        )
        assertTrue(
            "下拉刷新必须接到 onRetry（否则拉一下什么都不发生）",
            body.contains("onRetry"),
        )
    }

    @Test
    fun `直播页也要能下拉刷新`() {
        val body = GuardSourceScan.functionBody(source("LiveNowScreen.kt"), "fun LiveNowScreen(")
        assertTrue(
            "LiveNowScreen 没有 PullToRefreshBox",
            body.contains("PullToRefreshBox("),
        )
    }

    @Test
    fun `我的页必须有检查更新入口并消费判据`() {
        val body = GuardSourceScan.functionBody(source("ProfileScreen.kt"), "fun ProfileScreen(")
        assertTrue("ProfileScreen 没有消费 UpdateCheckPolicy", body.contains("UpdateCheckPolicy."))
        assertTrue("ProfileScreen 没有检查更新入口（onCheckUpdate）", body.contains("onCheckUpdate"))
    }

    @Test
    fun `ViewModel 必须有真的检查更新实现`() {
        val vm = source("MainViewModel.kt")
        assertTrue(
            "MainViewModel 没有 checkForUpdate()：界面上那个入口点了会没反应",
            vm.contains("fun checkForUpdate("),
        )
        // ⚠️ 不能断言源码里有 "api.github.com" 字样：GuardSourceScan.stripNonCode 会把
        // **字符串字面量一起剔掉**（本仓库的铁律①），那种断言永远不成立 ——
        // 它抓的不是行为，只是恰好留在代码里的字面量。改为断言**代码标识**：
        // 仓库坐标常量 + releases/latest 路径片段（后者在拼接表达式里，是代码）。
        assertTrue(
            "检查更新没有引用仓库坐标常量（REPO_OWNER / REPO_NAME）：" +
                "写死字符串时改一处漏一处会静默失效",
            vm.contains("REPO_OWNER") && vm.contains("REPO_NAME"),
        )
        assertTrue(
            "检查更新必须消费 UpdateCheckPolicy.decide",
            vm.contains("UpdateCheckPolicy.decide("),
        )
        assertTrue(
            "检查更新必须真的发请求（HttpClient.shared.fetch）",
            vm.contains("HttpClient.shared.fetch("),
        )
    }

    @Test
    fun `比分必须接到卡片上`() {
        val card = source("MatchListScreen.kt")
        assertTrue(
            "列表卡片没有渲染比分（TeamNameBridge 写了没人用）",
            card.contains("TeamNameBridge."),
        )
        val repo = source("ScoreboardRepository.kt")
        assertTrue("比分仓库没有解析器调用", repo.contains("EspnScoreboard.parse("))
        assertTrue(
            "比分仓库没有一个'按联赛选地址'的分派（CBA 没有数据要如实跳过）",
            repo.contains("scoreboardUrl(") && repo.contains("League.NBA"),
        )
        assertTrue(
            "CBA 必须显式走 null 分支（不发请求）：否则每次刷新都白打一个注定 400 的请求",
            repo.contains("League.CBA -> null"),
        )
    }

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "class Sample {",
            "    fun MatchListScreen() {",
            "        // PullToRefreshBox( 只在注释里",
            "        val s = \"onCheckUpdate 只在字符串里\"",
            "    }",
            "}",
        ).joinToString("\n")
        val body = GuardSourceScan.functionBody(sample, "fun MatchListScreen(")
        assertFalse("判据命中了注释", body.contains("PullToRefreshBox("))
        assertFalse("判据命中了字符串", body.contains("onCheckUpdate"))
    }

    private fun source(name: String): String {
        val root = productionRoot()
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("找不到 $name —— 这条守卫会静默全绿，所以直接失败")
        return GuardSourceScan.stripNonCode(file.readText())
    }

    private fun productionRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").absolutePath
        return listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd）—— 守卫会静默全绿")
    }
}
