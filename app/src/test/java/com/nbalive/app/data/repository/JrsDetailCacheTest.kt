package com.nbalive.app.data.repository

import com.nbalive.app.data.remote.FetchResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JrsDetailCache] 的**独立**单测（task: D10 完整拆分）。
 *
 * ## 为什么这些判据不该继续挂在 `MatchRepository` 上
 *
 * 拆分前它们只能靠**反射**读 `MatchRepository.detailCache` 里的私有字段/条数
 * （`DetailCacheTtlTest` / `MatchRepositoryRetryTest`）—— 那种判据钉住的是
 * "某个私有成员还在、还叫这个名字"，任何一次重构（D10 这次就是）都会把它打红，
 * 而它真正想守的"TTL 起点 / 条数上限"却测不到。现在缓存是一个可单独构造的单元：
 * 时钟、TTL、条数上限都能注入，判据直接断言**行为**（取了几次正文）。
 *
 * 覆盖：
 *  - TTL 命中 / 到点过期；
 *  - **缓存时间戳在正文到达之后才取**（P2-7：慢请求不许吃掉 TTL）；
 *  - single-flight：并发同一场只取一次；
 *  - `forceRefresh` 绕过缓存，**且不许删掉别人在飞的条目**（D12）；
 *  - `invalidate` 只失效一场 / `clear` 清空整表；
 *  - LRU 条数上限（最旧淘汰、最新保留）；
 *  - 拿不到正文（null / 空串）不写缓存。
 */
class JrsDetailCacheTest {

    private val lock = Any()

    /** 测试用的 fetch：缓存类不关心它，`fetchBody` 夹具会忽略它。 */
    private val noopFetch: suspend (String, String?) -> FetchResult = { _, _ -> FetchResult.Ok("") }

    @Test
    fun `TTL 内第二次 load 命中缓存 到点后重新取正文`() = runBlocking {
        val nowMs = AtomicLong(0L)
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                "body-$id"
            },
            clock = { nowMs.get() },
            ttlMs = 1_000L,
        )

        assertEquals("body-555", cache.load("555", fetch = noopFetch))
        assertEquals("第一次必须真的取正文", 1, calls.get())

        assertEquals("body-555", cache.load("555", fetch = noopFetch))
        assertEquals("TTL 内必须命中缓存（实际取了 ${calls.get()} 次）", 1, calls.get())

        nowMs.addAndGet(1_000L) // 恰好到 TTL：`>=` 即过期
        cache.load("555", fetch = noopFetch)
        assertEquals("TTL 到点必须重新取正文", 2, calls.get())
    }

    @Test
    fun `缓存时间戳在正文到达之后才取 慢请求不许吃掉 TTL`() = runBlocking {
        // 关键判据（review/02-data.md P2-7）：注入时钟在**取正文期间**前进 6 分钟
        // （模拟一次很慢的请求，但不用真等 6 分钟）。
        //  - 修复后：时间戳在正文之后取 → 年龄 0 < 5 分钟 → 第二次 load 命中（0 次新请求）；
        //  - 旧实现（时间戳取在请求之前）：年龄 6 分钟 >= 5 分钟 → 第二次 load 会回源（红）。
        val nowMs = AtomicLong(0L)
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                nowMs.addAndGet(6 * 60 * 1000L)
                "body-$id"
            },
            clock = { nowMs.get() }, // TTL 用生产默认值 5 分钟
        )

        cache.load("555", fetch = noopFetch)
        val afterFirst = calls.get()
        cache.load("555", fetch = noopFetch)
        assertEquals(
            "缓存年龄必须从**拿到正文**开始算：一次 6 分钟的慢请求不该把 5 分钟的 TTL 吃掉" +
                "（旧实现让第二次 load 白回源一次）",
            afterFirst,
            calls.get(),
        )
    }

    @Test
    fun `并发 load 同一场只取一次正文`() = runBlocking {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                if (synchronized(lock) { calls.incrementAndGet() } >= 1) started.complete(Unit)
                release.await()
                "body-$id"
            },
        )

        val a = async(Dispatchers.IO) { cache.load("555", fetch = noopFetch) }
        started.await()
        // B 与 A 并发、且是同一场：必须复用 A 在飞的请求
        val b = async(Dispatchers.IO) { cache.load("555", fetch = noopFetch) }
        delay(100)
        release.complete(Unit)

        assertEquals("body-555", a.await())
        assertEquals("body-555", b.await())
        assertEquals("并发同一场必须合并成一次取正文（实际 ${calls.get()} 次）", 1, calls.get())
    }

    @Test
    fun `forceRefresh 绕过缓存自己取一次`() = runBlocking {
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                "body-$id"
            },
        )

        cache.load("555", fetch = noopFetch)
        val afterFirst = calls.get()
        cache.load("555", fetch = noopFetch)
        assertEquals("未 force 时必须命中缓存", afterFirst, calls.get())

        cache.load("555", forceRefresh = true, fetch = noopFetch)
        assertEquals("forceRefresh（用户显式刷新）必须绕过缓存真的取一次", afterFirst + 1, calls.get())
    }

    @Test
    fun `forceRefresh 不许删掉别人在飞的条目`() = runBlocking {
        // D12：拆分前 forceRefresh 路径不注册自己的 deferred，却在 finally 里**无条件**
        // remove —— 于是把别人（正在飞的那一路）的条目一起删了，第三个来者重新取一次正文。
        val slowCalls = AtomicInteger(0)
        val bStarted = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        val forceMode = AtomicBoolean(false)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                if (forceMode.get()) {
                    // force 那一路立刻失败：不写缓存、不占用 deferred
                    null
                } else {
                    slowCalls.incrementAndGet()
                    bStarted.complete(Unit)
                    bRelease.await()
                    "body-$id"
                }
            },
        )

        // B：非 force，卡在取正文里
        val b = async(Dispatchers.IO) { cache.load("555", fetch = noopFetch) }
        bStarted.await()
        forceMode.set(true)
        assertNull(
            "force 那一路取不到正文（夹具如此）",
            cache.load("555", forceRefresh = true, fetch = noopFetch),
        )

        // C：非 force，在 B 完成之前进来 —— 必须复用 B 的在飞条目
        val c = async(Dispatchers.IO) { cache.load("555", fetch = noopFetch) }
        delay(300)
        bRelease.complete(Unit)

        assertEquals("B 必须拿到自己的正文", "body-555", b.await())
        assertEquals(
            "C 必须拿到 B 的结果，而不是因为条目被 force 删掉而重新取一次（拿到 null）",
            "body-555",
            c.await(),
        )
        assertEquals(
            "非 force 的取正文只该发生一次（实际 ${slowCalls.get()} 次）",
            1,
            slowCalls.get(),
        )
    }

    @Test
    fun `invalidate 只失效这一场 clear 清掉整表`() = runBlocking {
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                "body-$id"
            },
        )

        cache.load("555", fetch = noopFetch)
        cache.load("777", fetch = noopFetch)
        val afterBoth = calls.get()

        cache.invalidate("555")
        cache.load("555", fetch = noopFetch)
        assertEquals("invalidate 必须让这一场失效（真的回源）", afterBoth + 1, calls.get())
        cache.load("777", fetch = noopFetch)
        assertEquals("invalidate 不该动其它场次的缓存", afterBoth + 1, calls.get())

        cache.clear()
        cache.load("777", fetch = noopFetch)
        assertEquals("clear 必须清掉整表", afterBoth + 2, calls.get())
    }

    @Test
    fun `有条数上限 最旧的被淘汰 最新的仍命中`() = runBlocking {
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                "body-$id"
            },
            maxEntries = 2,
        )

        cache.load("a", fetch = noopFetch)
        cache.load("b", fetch = noopFetch)
        cache.load("c", fetch = noopFetch) // 上限 2 → 最旧的 a 被淘汰
        val afterFill = calls.get()
        assertEquals("夹具必须真的取了 3 个不同的详情页", 3, afterFill)

        cache.load("c", fetch = noopFetch)
        assertEquals("刚放进来的 c 必须仍在缓存里（命中）", afterFill, calls.get())
        cache.load("a", fetch = noopFetch)
        assertEquals("最旧的 a 必须已被淘汰（真的回源）", afterFill + 1, calls.get())
    }

    @Test
    fun `拿不到正文不写缓存 下次会重新尝试`() = runBlocking {
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { _, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                null
            },
        )
        assertNull(cache.load("555", fetch = noopFetch))
        assertNull(cache.load("555", fetch = noopFetch))
        assertEquals(
            "拿不到正文不许写缓存 —— 否则一次失败会被固化成 TTL 内的「这个详情页没有内容」",
            2,
            calls.get(),
        )
    }

    @Test
    fun `空正文同样不写缓存`() = runBlocking {
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { _, _ ->
                synchronized(lock) { calls.incrementAndGet() }
                ""
            },
        )
        cache.load("555", fetch = noopFetch)
        cache.load("555", fetch = noopFetch)
        assertEquals("空正文与 null 一样不许进缓存", 2, calls.get())
    }

    @Test
    fun `取正文抛异常不会把 detailId 永久卡住`() = runBlocking {
        // 回归：任何异常路径都必须把 in-flight 条目清掉，
        // 否则这个场次的详情页会永远等待一个已经结束的 Deferred。
        val calls = AtomicInteger(0)
        val cache = JrsDetailCache(
            fetchBody = { id, _ ->
                val n = synchronized(lock) { calls.incrementAndGet() }
                if (n == 1) throw IllegalStateException("boom") else "body-$id"
            },
        )
        runCatching { cache.load("555", fetch = noopFetch) }
        assertEquals("第二次必须能重新取（不能被 in-flight 卡死）", "body-555", cache.load("555", fetch = noopFetch))
        assertTrue("两次都要真的走到取正文", calls.get() >= 2)
    }
}