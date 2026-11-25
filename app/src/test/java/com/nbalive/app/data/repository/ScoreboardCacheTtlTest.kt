package com.nbalive.app.data.repository

import com.nbalive.app.data.remote.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 比分板 TTL 的起点（review/02-data.md P2）。
 *
 * 旧实现在进入 `withContext(IO)` **之前**采样 `now`，却在抓取完成**之后**把这个
 * 采样值写进 `cachedAt`。一次 400ms 的慢请求在 150ms TTL 下缓存一落地就已经过期，
 * 紧接着的下一次 load 又打一次 ESPN —— 越慢的请求缓存越短，而所有断言照样绿。
 *
 * 本测试注入一个"比 TTL 还慢"的抓取，验证时间戳必须从**正文到手后**起算。
 */
class ScoreboardCacheTtlTest {

    private val sample = """
        {"events":[{"competitions":[
          {"competitors":[
            {"homeAway":"home","score":"112","team":{"displayName":"Golden State Warriors","name":"Warriors"}},
            {"homeAway":"away","score":"95","team":{"displayName":"Los Angeles Lakers","name":"Lakers"}}
          ],
          "status":{"displayClock":"12:00","period":4,"type":{"state":"post","shortDetail":"Final"}}}]}]}
    """.trimIndent()

    private val lock = Any()

    @Test
    fun `抓取耗时超过 TTL 时第二次 load 仍必须命中缓存`() = runBlocking {
        var calls = 0
        val repo = ScoreboardRepository(
            fetch = { _, _ ->
                synchronized(lock) { calls++ }
                // 故意让一次抓取比 TTL 还慢：旧实现把这段耗时算进缓存年龄，
                // 第一次 load 返回时缓存已经"过期"。
                Thread.sleep(400L)
                FetchResult.Ok(sample)
            },
            ttlMs = 150L,
        )

        val first = repo.load()
        assertFalse("夹具前提：首次抓取必须拿到比分", first.isEmpty())
        assertEquals("首次 load 只该发一次请求", 1, synchronized(lock) { calls })

        val second = repo.load()
        assertEquals(
            "TTL 起点必须在拿到正文之后：慢抓取不该把刚写进去的缓存立刻判过期，" +
                "第二次 load 必须命中缓存而不是再打一次 ESPN（实际 calls=$calls）",
            1,
            synchronized(lock) { calls },
        )
        assertEquals("命中缓存时返回的必须是同一份快照", first, second)
    }
}
