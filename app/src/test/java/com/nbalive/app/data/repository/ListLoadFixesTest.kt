package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.FetchResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据层本轮修复的**行为判据**（task-6 / fix-data）。
 *
 * 对应 review 条目：
 *  - **H3 / data.md D1**：超时下移到每个源，另一个源已拿到的场次必须保留；
 *  - **H4 / data.md D2**：合并时联赛取优，JRKAN 的 OTHER 不许把同场 JRS 的 NBA 吞掉；
 *  - **data.md D3**：404/410 不登记 host 失败（主域名仍排候选首位）；
 *  - **data.md D5**：列表层 30 秒 TTL + single-flight，用户主动刷新必须绕过；
 *  - **data.md D9**：403 之后的 60 秒源冷却——窗口内不发请求、窗口过后恢复；
 *  - **optimization-review.md D12**：`forceRefresh` 不许删掉别人在飞的 single-flight 条目。
 *
 * ⚠️ 这些用例**不复用**既有测试文件（铁律：不修改既有测试），全部自建夹具。
 */
class ListLoadFixesTest {

    private val lock = Any()

    /**
     * 一轮全源加载发出的列表请求数（single-flight 判据的期望值）。
     *
     * ⚠️ 写成常量而不是散落的 `2`：第三源接入时这个数字变成 3，
     * 而"每个源各一次"这条判据的**含义**没变 —— 只有夹具的算术变了。
     * 三源都开时若漏了 88 的请求，断言会直接红而不是悄悄少测一个源。
     */
    private companion object {
        const val LIST_FANOUT = 3
    }

    // ---------------- H3：超时按源，已成功的另一源必须保留 ----------------

    @Test
    fun `一个源超时不许丢掉另一个源已经返回的场次`() = runBlocking {
        // 注入的 deadline 取 300ms：JRS 永不返回、JRKAN 立即返回 3 场。
        // 旧实现（整体 withTimeoutOrNull + matches = emptyList()）下这里会是 0 场，
        // 而且两个源都被报成"网络超时"。
        val repo = MatchRepository(loadDeadlineMs = 300L)
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszb")) {
                // 模拟"连上了但再也不回数据"（旧实现里这正是整体超时的成因）
                delay(60_000)
                FetchResult.Ok("")
            } else {
                FetchResult.Ok(listBody(url))
            }
        }

        assertEquals(
            "另一个源已经拿到的 3 场必须保留（旧实现会整体清空）：" +
                "matches=${outcome.matches.map { it.homeTeam }}",
            3,
            outcome.matches.size,
        )
        assertEquals(
            "只有真的超时的那个源才该被报成失败（旧实现会把两个源都报成超时）",
            listOf(Provider.JRS),
            outcome.failedProviders,
        )
        val reason = outcome.failureReason(Provider.JRS)
        assertNotNull("超时源必须给出可读原因", reason)
        assertTrue("原因应说明是超时：$reason", reason!!.contains("超时"))
        assertTrue(
            "超时提示必须能面向用户显示（partialError 会拼上它）",
            outcome.partialError()?.contains("超时") == true,
        )
    }

    @Test
    fun `全源超时时仍然在 deadline 内返回并都记为失败`() = runBlocking {
        // 既有 LoadDeadlineTest 的同款语义在"按源超时"的实现下必须保持。
        // ⚠️ 第三源接入后期望三个源：夹具对**所有** URL 都挂起，
        // 这就是"全源超时"的场景，少报一个源 = 界面上那个源看起来还在正常解析。
        val started = System.nanoTime()
        val repo = MatchRepository(loadDeadlineMs = 250L)
        val outcome = repo.loadMatchesWithOutcome { _, _ ->
            delay(60_000)
            FetchResult.Ok("")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("所有源都没回来时列表必须为空", outcome.matches.isEmpty())
        assertEquals(
            "所有源都超时必须都被报出来",
            listOf(Provider.JRS, Provider.JRKAN, Provider.K88),
            outcome.failedProviders.sortedBy { it.ordinal },
        )
        assertTrue(
            "必须真的等满注入的 deadline（实测 ${elapsedMs}ms）——" +
                "过短说明 deadline 被忽略，过长说明退化成几秒硬编码",
            elapsedMs in 200L..1_500L,
        )
    }

    // ---------------- H4 / D2：合并时的联赛取优 ----------------

    @Test
    fun `JRKAN 判 OTHER 时同场 JRS 的 NBA 必须留下`() = runBlocking {
        val day = dayAnchor()
        val jrkan = match(
            id = "jrkan-777",
            league = League.OTHER,
            leagueRawName = "篮球",
            home = "湖人",
            away = "凯尔特人",
            startAtMillis = day,
        )
        val jrs = match(
            id = "jrs-555",
            league = League.NBA,
            leagueRawName = "NBA",
            home = "湖人",
            away = "凯尔特人",
            startAtMillis = day,
        )

        // mergeForTest(jrs, jrkan)：内部恒为 jrkan 先合并、jrs 后合并
        val merged = MatchRepository().mergeForTest(listOf(jrs), listOf(jrkan))

        assertEquals(
            "同一场比赛必须只留一张卡（旧实现因为 league 被污染而整场丢弃，实际 ${merged.size} 场）",
            1,
            merged.size,
        )
        assertEquals("联赛必须取二者中更优的那个", League.NBA, merged.single().league)
        assertEquals(
            "league 与其原始文案必须成对取自同一个源，否则会自相矛盾",
            "NBA",
            merged.single().leagueRawName,
        )
    }

    @Test
    fun `两个源都判 OTHER 时保持先合并的那个（行为不变）`() = runBlocking {
        val day = dayAnchor()
        val jrkan = match(
            id = "jrkan-1", league = League.OTHER, leagueRawName = "篮球",
            home = "某队", away = "另队", startAtMillis = day,
        )
        val jrs = match(
            id = "jrs-1", league = League.OTHER, leagueRawName = "夏季联赛",
            home = "某队", away = "另队", startAtMillis = day,
        )
        val merged = MatchRepository().mergeForTest(listOf(jrs), listOf(jrkan))
        assertEquals(0, merged.size) // 两个 OTHER 仍被 supported 过滤（产品规则不变）
    }

    // ---------------- D3：404 不登记 host 失败 ----------------

    @Test
    fun `详情页 404 不登记 host 失败 主域名仍排候选首位`() = runBlocking {
        val repo = MatchRepository()
        val tried = mutableListOf<String>()
        repo.loadSignals(matchWithDetail("555")) { url, _ ->
            tried += url
            if (url.contains("/live/")) FetchResult.NotFound(404) else FetchResult.Ok("")
        }

        assertTrue("夹具必须真的请求过详情页", tried.any { it.contains("/live/") })
        assertEquals(
            "404 是「页面没了」而不是「域名挂了」，不该被登记成 host 失败",
            emptySet<String>(),
            failedHostsOf(repo),
        )
        val first = SourceEndpoints.attemptOrder(SourceEndpoints.JRS_LIST, failedHostsOf(repo)).first()
        assertTrue(
            "单场详情页 404 之后，主域名必须仍排在候选首位（实际 $first）——" +
                "旧实现把它降到队尾，之后整个会话的列表/详情/播放都先敲镜像",
            first.contains("jrszbt.com"),
        )
    }

    @Test
    fun `列表页 404 报成页面不存在 而不是网络连接失败`() = runBlocking {
        val repo = MatchRepository()
        val outcome = repo.loadMatchesWithOutcome { url, _ ->
            if (url.contains("jrszb")) FetchResult.NotFound(404) else FetchResult.Ok(listBody(url))
        }

        assertEquals("JRS 必须被如实记为失败源", listOf(Provider.JRS), outcome.failedProviders)
        val reason = outcome.failureReason(Provider.JRS)
        assertTrue("原因里应含 404 而不是「网络连接失败」：$reason", reason!!.contains("404"))
        assertEquals(
            "404 不许登记 host 失败（否则主域名会被降级）",
            emptySet<String>(),
            failedHostsOf(repo),
        )
        assertTrue("JRKAN 的场次照常返回", outcome.matches.any { it.id.startsWith("jrkan-") })
    }

    @Test
    fun `播放页 404 不登记 host 失败`() = runBlocking {
        val repo = MatchRepository()
        val jrkanMatch = match(
            id = "jrkan-1", league = League.NBA, leagueRawName = "NBA",
            home = "湖人", away = "凯尔特人", startAtMillis = dayAnchor(),
            sources = listOf(
                StreamSource(
                    id = "jrkan-1-s0",
                    label = "信号1",
                    provider = Provider.JRKAN,
                    pageUrl = "${SourceEndpoints.PLAY_HOSTS.first()}/play/steam824761.html",
                ),
            ),
        )
        repo.loadSignals(jrkanMatch) { url, _ ->
            if (url.contains("/play/")) FetchResult.NotFound(404) else FetchResult.Ok("")
        }

        assertEquals(
            "播放页 404 是「这场结束了」，不该让主域名被降级",
            emptySet<String>(),
            failedHostsOf(repo),
        )
        val order = SourceEndpoints.playPageCandidates(
            "${SourceEndpoints.PLAY_HOSTS.first()}/play/steam824761.html",
            failedHostsOf(repo),
        )
        assertTrue(
            "列表页给的原始播放页必须仍排在候选首位（实际 ${order.first()}）",
            order.first().contains(SourceEndpoints.PLAY_HOSTS.first().substringAfter("://")),
        )
    }

    // ---------------- D5：列表短缓存 + single-flight + forceRefresh ----------------

    @Test
    fun `TTL 内第二次调用不发请求 TTL 过后重发 forceRefresh 必发`() = runBlocking {
        val nowMs = AtomicLong(0L)
        val repo = MatchRepository(listCacheTtlMs = 30_000L, clock = { nowMs.get() })
        val calls = AtomicInteger(0)
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            calls.incrementAndGet()
            if (isListUrl(url) || url.contains("/live/")) {
                FetchResult.Ok(listBody(url))
            } else {
                FetchResult.Ok("")
            }
        }

        repo.loadMatchesWithOutcome(fetch = fetch)
        val afterFirst = calls.get()
        assertTrue("首次必须真的发请求（实际 $afterFirst 次）", afterFirst >= 2)

        repo.loadMatchesWithOutcome(fetch = fetch)
        assertEquals(
            "30 秒 TTL 内的第二次调用**一个请求都不该发**（review D5：单次刷新要下 282 KB）",
            afterFirst,
            calls.get(),
        )

        nowMs.addAndGet(30_000L)
        repo.loadMatchesWithOutcome(fetch = fetch)
        assertTrue("TTL 过后必须重新发请求", calls.get() > afterFirst)

        val beforeForce = calls.get()
        repo.loadMatchesWithOutcome(forceRefresh = true, fetch = fetch)
        assertTrue(
            "用户主动刷新（forceRefresh=true）必须绕过缓存真的回源，" +
                "否则「点了刷新却拿到 30 秒前的列表」",
            calls.get() > beforeForce,
        )
    }

    @Test
    fun `同一源组合的并发调用只打一次源站`() = runBlocking {
        val repo = MatchRepository()
        val listCalls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (isListUrl(url)) {
                if (listCalls.incrementAndGet() >= LIST_FANOUT) started.complete(Unit)
                release.await()
            }
            FetchResult.Ok(listBody(url))
        }

        val a = async(Dispatchers.IO) { repo.loadMatchesWithOutcome(fetch = fetch) }
        started.await()
        // B 与 A 并发、且源组合相同：必须复用 A 的在飞结果
        val b = async(Dispatchers.IO) { repo.loadMatchesWithOutcome(fetch = fetch) }
        delay(200)
        release.complete(Unit)
        val ra = a.await()
        val rb = b.await()

        assertEquals(
            "每个源的列表请求各只该发一次（实际 $listCalls 次，期望 $LIST_FANOUT）——" +
                "缓存挡不住「正在飞的请求」，single-flight 才挡得住",
            LIST_FANOUT,
            listCalls.get(),
        )
        assertTrue("A 必须有数据", ra.matches.isNotEmpty())
        assertTrue("B 必须拿到同一批数据", rb.matches.isNotEmpty())
        assertEquals("A/B 拿到的是同一份结果", ra.matches.map { it.id }, rb.matches.map { it.id })
    }

    // ---------------- D12：forceRefresh 不许删别人的 in-flight 条目 ----------------

    @Test
    fun `force 刷新不许删掉别人在飞的 single-flight 条目`() = runBlocking {
        // 缓存关掉（TTL=0），让判据只看 single-flight 那一层
        val repo = MatchRepository(listCacheTtlMs = 0L)
        val bStarted = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        val bListCalls = AtomicInteger(0)

        // B：非 force，列表请求阻塞在 bRelease 上
        val slowFetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (isListUrl(url)) {
                if (bListCalls.incrementAndGet() >= LIST_FANOUT) bStarted.complete(Unit)
                bRelease.await()
            }
            FetchResult.Ok(listBody(url))
        }
        val b = async(Dispatchers.IO) { repo.loadMatchesWithOutcome(fetch = slowFetch) }
        bStarted.await()

        // A：force 刷新（用户点"刷新"），立即失败 —— 旧实现会在 finally 里
        // 无条件 remove，把 B 的 in-flight 条目一起删掉
        repo.loadMatchesWithOutcome(forceRefresh = true, fetch = { _, _ -> FetchResult.NotFound(404) })

        // C：非 force，在 B 完成之前进来 —— 必须仍看得到 B 在飞的条目
        val cListCalls = AtomicInteger(0)
        val c = async(Dispatchers.IO) {
            repo.loadMatchesWithOutcome { url, _ ->
                if (isListUrl(url)) cListCalls.incrementAndGet()
                if (url.contains("jrszb")) FetchResult.Ok(jrsListHtml()) else FetchResult.Ok(jrkanThreeMatches())
            }
        }
        delay(300)
        bRelease.complete(Unit)
        val rb = b.await()
        val rc = c.await()

        assertEquals(
            "C 必须复用 B 的在飞请求（实际自己发了 ${cListCalls.get()} 次）——" +
                "旧实现在 A 的 finally 里把 B 的条目删了，第三个来者以为没人请求、又打一次源站",
            0,
            cListCalls.get(),
        )
        // ⚠️ 期望值 = 夹具的算术，不是"JRKAN 那 3 场"：
        // B 的三个源都成功 —— JRS 列表给 1 场（湖人/凯尔特人）+ JRKAN 给 3 场
        // + 88看球给**同样那 3 个对阵**（见 k88ThreeMatches 的注释）= 合并后仍是 **4** 场。
        // 第一版写成 3（只算了 JRKAN），首轮实测红：`expected:<3> but was:<4>`。
        // 同时用 `rc` 与 `rb` 的 id 列表相等兜住"C 拿到的是同一批结果"。
        assertEquals(
            "B 的结果必须完好（1 场 JRS + 3 场对阵，88 与 JRKAN 同对阵须并卡；实际 ${rb.matches.map { it.id }}）",
            4,
            rb.matches.size,
        )
        assertTrue(
            "夹具必须真的同时包含两个源的场次，否则这条判据测不到 single-flight 的复用",
            rb.matches.any { it.id.startsWith("jrs-") } && rb.matches.any { it.id.startsWith("jrkan-") },
        )
        assertEquals("C 必须拿到 B 的同一批结果", rb.matches.map { it.id }, rc.matches.map { it.id })
    }

    // ---------------- D9：403 之后的源冷却 ----------------

    @Test
    fun `403 冷却窗口内不再发请求 窗口过后恢复`() = runBlocking {
        // 可注入时钟（判据要求）：窗口内/窗口外由测试推动，不必真等 60 秒。
        // 列表缓存关掉，避免缓存把"该不该发请求"这件事遮住。
        val nowMs = AtomicLong(1_000L)
        val repo = MatchRepository(listCacheTtlMs = 0L, clock = { nowMs.get() })
        val jrsListCalls = AtomicInteger(0)
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (isListUrl(url) && url.contains("jrszb")) {
                jrsListCalls.incrementAndGet()
                FetchResult.Rejected(403)
            } else if (isListUrl(url)) {
                FetchResult.Ok(listBody(url))
            } else {
                FetchResult.Ok("")
            }
        }

        val first = repo.loadMatchesWithOutcome(fetch = fetch)
        assertEquals("JRS 被 403 必须如实报出", listOf(Provider.JRS), first.failedProviders)
        val afterFirst = jrsListCalls.get()
        assertTrue("第一次必须真的撞过一次 403（实际 $afterFirst 次）", afterFirst >= 1)

        // 冷却窗口内：一次 JRS 请求都不该发
        repo.loadMatchesWithOutcome(fetch = fetch)
        assertEquals(
            "60 秒冷却窗口内不该再撞限流（实际 ${jrsListCalls.get()} 次）——" +
                "旧实现跨调用不设防，用户「一直刷新」正好把窗口越拖越长",
            afterFirst,
            jrsListCalls.get(),
        )

        // 窗口过：恢复请求（证明这不是"永久拉黑"）
        nowMs.addAndGet(60_001L)
        repo.loadMatchesWithOutcome(fetch = fetch)
        assertTrue(
            "冷却窗口过后必须恢复请求（否则就把暂时故障变成长期故障了）",
            jrsListCalls.get() > afterFirst,
        )
    }

    @Test
    fun `详情页 403 之后冷却窗口内也不发请求`() = runBlocking {
        val nowMs = AtomicLong(1_000L)
        val repo = MatchRepository(clock = { nowMs.get() })
        val detailCalls = AtomicInteger(0)
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                detailCalls.incrementAndGet()
                FetchResult.Rejected(429)
            } else {
                FetchResult.Ok("")
            }
        }

        repo.loadSignals(matchWithDetail("777"), fetch)
        val afterFirst = detailCalls.get()
        assertEquals("第一次应当真的撞过一次限流", 1, afterFirst)

        repo.loadSignals(matchWithDetail("777"), fetch)
        assertEquals(
            "冷却窗口内第二次进同一场不该再打源站（实际 ${detailCalls.get()} 次）",
            afterFirst,
            detailCalls.get(),
        )

        nowMs.addAndGet(60_001L)
        repo.loadSignals(matchWithDetail("777"), fetch)
        assertTrue("窗口过后必须恢复请求", detailCalls.get() > afterFirst)
    }

    // ---------------- 夹具 ----------------

    private fun dayAnchor(): Long =
        java.time.LocalDate.of(2026, 10, 23)
            .atTime(19, 35)
            .atZone(com.nbalive.app.data.model.MatchStatusClock.SOURCE_ZONE)
            .toInstant()
            .toEpochMilli()

    private fun isListUrl(url: String): Boolean =
        (url.contains("jrszb") && !url.contains("/live/")) ||
            url.contains("oss-accelerate") ||
            // 88看球（第三源）：**只认列表路径**（`/match/1/live`）。
            // 它的线路接口是 `/live/{id}/source`，若一并算进"列表 URL"，
            // single-flight 那两条判据的计数就会把详情级请求混进来，
            // 断言的"每个源各一次"不再对应它要测的东西。
            url.contains("/match/")

    /** 三个源的列表正文按 URL 路由（避免每个用例各写一份三分支）。 */
    private fun listBody(url: String): String = when {
        url.contains("88kanqiu") -> k88ThreeMatches()
        url.contains("jrszb") -> jrsListHtml()
        else -> jrkanThreeMatches()
    }

    private fun jrsListHtml(): String = """
        <div class="list"><p class="list-title">2026-10-23</p>
        <a href="/live/555.html" class="list-con">
          <div class="list-team-con"><p class="list-team-name">湖人</p></div>
          <div class="list-team-con"><p class="list-team-name">凯尔特人</p></div>
          <p class="list-info-league">NBA</p><p class="list-info-time">直播中</p>
        </a></div>
    """.trimIndent()

    /** 三场 JRKAN 场次（互不相同的对阵，联赛名明确写 NBA）。 */
    private fun jrkanThreeMatches(): String {
        val pairs = listOf("勇士" to "火箭", "太阳" to "掘金", "热火" to "雄鹿")
        return pairs.mapIndexed { i, (home, away) -> """
            document.write('<ul class="item play active" data-lid="${900 + i},2,${900 + i}">');
            document.write('  <li class="lab_events"><span class="name">NBA</span></li>');
            document.write('  <li class="lab_time">10-23 10:0$i</li>');
            document.write('  <li class="lab_team_home"><strong class="name">$home</strong></li>');
            document.write('  <li class="lab_team_away"><strong class="name">$away</strong></li>');
            document.write('</ul>');
        """.trimIndent() }.joinToString("\n")
    }

    /**
     * 三场 88看球场次（第三源，2026-11-15）。
     *
     * ⚠️ 对阵与 [jrkanThreeMatches] **逐场相同**（日期同锚 2026-10-23）：
     * 合并键是"日期|队名对(排序)"，同对阵必然并成同一张卡 ——
     * 于是"三源都返回"时列表仍是 3 场，本文件里所有 `size == 3` 的断言
     * 测的还是它本来要测的东西（按源超时不丢数据、single-flight 复用），
     * 而不是被迫跟着源数量改数字。
     * 跨源**线路去重**的判据不在这里测（那些场次没有 sources），
     * 在 K88SourceIntegrationTest / LineEquivalenceTest。
     */
    private fun k88ThreeMatches(): String {
        val pairs = listOf("勇士" to "火箭", "太阳" to "掘金", "热火" to "雄鹿")
        return """
            <ul class="list-group">
              <li class="list-group-item live-list-group-time">2026-10-23</li>
        """.trimIndent() + "\n" + pairs.mapIndexed { i, (home, away) -> """
              <li class="list-group-item group-game-item">
                <div class="row">
                  <div class="col-md-1 category-game-time">10:0$i</div>
                  <div class="col-md-3"><div><span class="game-type">NBA</span></div></div>
                  <div class="col-md-4"><span class="team-name" title="$home">$home</span></div>
                  <div class="col-md-1">VS</div>
                  <div class="col-md-3"><span class="team-name" title="$away">$away</span></div>
                  <div><a href='/live/${700 + i}/play'>直播中</a></div>
                </div>
              </li>
        """.trimIndent() }.joinToString("\n") + "\n</ul>"
    }

    private fun match(
        id: String,
        league: League,
        leagueRawName: String,
        home: String,
        away: String,
        startAtMillis: Long?,
        sources: List<StreamSource> = emptyList(),
        jrsDetailId: String? = null,
        status: MatchStatus = MatchStatus.LIVE,
    ) = Match(
        id = id,
        league = league,
        leagueRawName = leagueRawName,
        homeTeam = home,
        awayTeam = away,
        homeLogo = null,
        awayLogo = null,
        timeLabel = "19:35",
        startAtMillis = startAtMillis,
        status = status,
        signalCount = sources.size,
        sources = sources,
        jrsDetailId = jrsDetailId,
    )

    private fun matchWithDetail(detailId: String) = match(
        id = "jrs-$detailId",
        league = League.NBA,
        leagueRawName = "NBA",
        home = "湖人",
        away = "凯尔特人",
        startAtMillis = dayAnchor(),
        jrsDetailId = detailId,
    )

    /**
     * 读私有的 `listFailedHosts`（"上一轮失败过的 host"表）。
     *
     * ⚠️ 为什么必须反射：这条判据的**结论**就是"表里不该多出东西"，
     * 而它没有任何行为出口（`attemptOrder` 的顺序变化是唯一现象，
     * 但那只在"表非空"时才可见）。
     *
     * ⚠️ 这是本仓**最后一处**针对详情页缓存/合并类的反射式判据的邻居：
     * `DetailCacheTtlTest` 与 `MatchRepositoryRetryTest` 对 `detailCache` 的反射
     * 已在 D10（`JrsDetailCache` 拆分）时改成公开 API 的行为断言；
     * `MatchRepositoryTest` 对 `sort` 的反射也改成了 `agedMatches` 的行为断言。
     * 本条（`listFailedHosts`）保留反射，是因为失败记账表目前没有可注入的观察口
     * —— 若要继续收口，应把"host 失败记忆 + 冷却"也抽成可单独构造的单元
     * （review 的 D10 完整拆分项里列的就是这一步）。
     */
    private fun failedHostsOf(repo: MatchRepository): Set<String> {
        val field = MatchRepository::class.java.getDeclaredField("listFailedHosts")
        field.isAccessible = true
        val raw = field.get(repo)
        @Suppress("UNCHECKED_CAST")
        return (raw as Set<String>).toSet()
    }
}