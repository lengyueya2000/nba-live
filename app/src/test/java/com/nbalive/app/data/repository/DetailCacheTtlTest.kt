package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.FetchResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页缓存 TTL 的**起点**（review/02-data.md P2-7）。
 *
 * ## 要解决的回归
 *
 * 缓存用 `detailId -> (缓存时刻, 正文)` 记录年龄。
 * 旧实现把"缓存时刻"取在**取正文之前**：一次 35 秒的慢请求，正文还没回来缓存就已经
 * "老"了 35 秒 —— 5 分钟 TTL 被请求耗时悄悄吃掉，越慢的请求缓存越短，
 * 而所有断言照样绿（改了没人红）。修法：拿到正文之后才取时间戳。
 *
 * ## 判据为什么不再反射读私有缓存（D10 重锚）
 *
 * 拆分前这里用 `getDeclaredField("detailCache")` 读出时间戳，再和 fetch 开始时刻比较。
 * 那条判据钉住的是"某个私有成员还在、还叫这个名字"：D10 把缓存搬进 [JrsDetailCache] 时
 * 它立刻变红（而它真正要守的语义一条都没表达出来），也确实在一次重构里被证明很脆。
 *
 * 现在改成**注入时钟 + 行为断言**：让时钟在"取正文期间"前进 **6 分钟**
 * （模拟一次很慢的请求，但测试本身只跑几毫秒），然后断言第二次 `loadSignals`
 * 仍然命中缓存、**不再回源**。
 *  - 修复后：时间戳在正文到达后取 → 年龄 0 < 5 分钟 TTL → 命中；
 *  - 若赋值被挪回取正文之前：年龄 6 分钟 >= TTL → 回源 → 红。
 *
 * 为什么等价或更强：旧断言读的是"内部时间戳相对 fetch 起点的位置"（实现细节），
 * 新断言读的是它的**可观察后果**（用户最在意的"白打一次源站"），
 * 并且顺带覆盖了"TTL 内命中不回源"这条相邻契约。
 */
class DetailCacheTtlTest {

    private fun detailHtml() = """
        <div class="match-iframe"><iframe src="https://play.example.com/play/kbs/?id=1"></iframe></div>
        <p class="match-info-time">2026-10-23 07:00</p>
    """.trimIndent()

    private fun matchOf(detailId: String) = Match(
        id = "m1",
        league = League.NBA,
        leagueRawName = "NBA",
        homeTeam = "湖人",
        awayTeam = "凯尔特人",
        homeLogo = null,
        awayLogo = null,
        timeLabel = "07:00",
        startAtMillis = null,
        status = MatchStatus.LIVE,
        signalCount = 0,
        sources = emptyList(),
        jrsDetailId = detailId,
    )

    @Test
    fun `慢请求的缓存年龄从拿到正文之后开始算`() = runBlocking {
        // 注入时钟：请求期间前进 6 分钟（模拟一次很慢的请求；真实时间只走几毫秒）
        val nowMs = AtomicLong(0L)
        var fetches = 0
        val repo = MatchRepository(clock = { nowMs.get() })
        val detailId = "9601"
        val fetch: suspend (String, String?) -> FetchResult = { url, _ ->
            if (url.contains("/live/")) {
                fetches++
                nowMs.addAndGet(6 * 60 * 1000L)
                FetchResult.Ok(detailHtml())
            } else {
                FetchResult.Ok("")
            }
        }

        repo.loadSignals(matchOf(detailId), fetch)
        val afterFirst = fetches
        assertTrue("夹具必须真的取过一次正文（实际 $afterFirst 次）", afterFirst >= 1)

        // 行为面判据：慢请求完成后立刻再请求同一场，TTL 内必须命中缓存、不回源。
        // 若缓存时间戳被取在取正文**之前**（旧实现），此刻年龄已是 6 分钟 → 会再回源。
        repo.loadSignals(matchOf(detailId), fetch)
        assertEquals(
            "缓存年龄必须从**拿到正文**之后开始算（review/02-data.md P2-7）：" +
                "否则一次 6 分钟的慢请求会把 5 分钟 TTL 直接吃掉，第二次进场白回源一次。" +
                "实际回源 $fetches 次（期望停在 $afterFirst 次）",
            afterFirst,
            fetches,
        )
    }
}