package com.nbalive.app.data.repository

import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.debugLog
import kotlinx.coroutines.CompletableDeferred

/**
 * JRS 详情页正文的**进程内短缓存 + single-flight**（review/optimization-review.md D10 的职责拆分）。
 *
 * ## 它承担什么 / 不承担什么
 *
 * - **承担**：TTL 缓存（key = `detailId`，LRU 条数上限）、"正在飞的请求"合并（single-flight）、
 *   失效（`invalidate`）与清空（`clear`）。时钟可注入，所以 TTL 语义能在 JVM 单测里钉死。
 * - **不承担**：真正去源站取正文的部分（候选域名顺序、403/429 冷却、404 不登记 host 失败）
 *   —— 那是 [MatchRepository] 的 `fetchDetailBody`，通过 [fetchBody] 注入。
 *   分开的理由：缓存与 single-flight 是**纯内存判据**，"打哪个域名/要不要限流"是
 *   **网络与源站协同判据**，两者的红绿条件完全不同；混在一个方法里时，
 *   任何一条都难以单独钉住（这也正是 D10 之前 `MatchRepository` 的问题）。
 *
 * ## 为什么缓存 key 是 `detailId` 而不是 URL
 *
 * 详情页有多个候选域名（[SourceEndpoints.jrsDetailUrls]），按 URL 存会让同一场次
 * 在主域名与镜像域名下各存一份，且"刷新线路"只能清掉其中一个 ——
 * 用户点了刷新却拿到旧正文，还不报错（review/02-data.md P2-6）。
 *
 * ## 为什么 TTL 的起点必须在**拿到正文之后**（review/02-data.md P2-7）
 *
 * 旧实现把 `cachedAt` 取在发请求**之前**：一次 35 秒的慢请求，正文还没回来缓存就已经
 * "老"了 35 秒 —— 5 分钟 TTL 被请求耗时悄悄吃掉，越慢的请求缓存越短，
 * 而所有断言照样绿。现在时间戳在正文到达之后才取。
 *
 * ## 为什么 `forceRefresh` 不许删掉别人在飞的条目（D12）
 *
 * single-flight 靠"注册者完成后移除并完成自己的 Deferred"维持。旧实现在 `finally` 里
 * **无条件** `remove`：`forceRefresh = true` 的调用自己并没有注册，却会删掉**别人**
 * 正在等待的条目 —— 第三个来者以为没有在飞请求、又打一次源站（偶发重复请求）。
 *
 * ## 排障（D18）
 *
 * 命中 / TTL 过期 / 显式失效 / 整体清空四条路径都打 `debugLog`，
 * 且**只打 detailId 不打 URL**（URL 带域名与签名 query）。
 * ⚠️ tag 沿用旧实现的 `"MatchRepo"`：改 tag 会让现场的 logcat 过滤规则失效。
 *
 * @param fetchBody 真正取正文的实现：入参是 `detailId` 与本次调用的 `fetch`，
 *   返回正文（拿不到返回 null）。
 * @param clock 单调时钟（毫秒）。⚠️ 默认 `nanoTime` 而不是墙钟：用户把系统时间往前拨时，
 *   `now - cachedAt` 会变成负数、缓存永不过期（源站改版了也看不到新内容）。
 *   （不用 `android.os.SystemClock.elapsedRealtime`：它在 JVM 单测里返回默认值 0，
 *   测试就没法验证 TTL 过期。）
 * @param ttlMs 缓存有效期；[DEFAULT_TTL_MS] = 5 分钟：足够覆盖
 *   "打开 App → 点直播 → 刷新线路"这一串操作，又短到不会让用户手动刷新时看到过期数据。
 * @param maxEntries 缓存最大条数（LRU 淘汰）。[DEFAULT_MAX_ENTRIES] = 64：
 *   仓库跟着 ViewModel 活整个进程，一场比赛一个详情页，一天下来几十上百个 URL 会一直堆着。
 */
internal class JrsDetailCache(
    private val fetchBody: suspend (String, suspend (String, String?) -> FetchResult) -> String?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /**
     * 正在飞的详情页请求：`detailId -> Deferred`。
     *
     * 缓存只挡"已完成的请求"，挡不住"正在飞的请求"（review/02-data.md P2-8）：
     * 状态校正（4 并发）与 `loadSignals` 同时请求同一 detailId 时，两边都会 miss
     * 并各打一次源站。必须在 `finally` 里移除并完成，任何异常路径都不能把
     * detailId 永久卡在一个已经结束的 Deferred 上。
     */
    private val inFlight = mutableMapOf<String, CompletableDeferred<String?>>()

    /**
     * 详情页短缓存：`detailId -> (缓存时刻, 正文)`。
     *
     * ⚠️ 会被多个协程并发访问（状态校正并发 4 路 + `loadSignals`），
     * 所以用 `synchronized` 保护，不能用裸 `HashMap`。
     * `accessOrder = true` 让它成为 LRU，超过上限淘汰最久未用的那一条。
     */
    private val cache = object : LinkedHashMap<String, Pair<Long, String>>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?,
        ): Boolean = size > maxEntries
    }

    /**
     * 取某场详情页的正文：命中缓存直接返回；有在飞请求就等它；否则自己去取。
     *
     * @param forceRefresh 用户显式刷新：绕过缓存**与** single-flight 自己发一次请求。
     */
    suspend fun load(
        detailId: String,
        forceRefresh: Boolean = false,
        fetch: suspend (String, String?) -> FetchResult,
    ): String? {
        val now = clock()
        if (!forceRefresh) {
            synchronized(cache) {
                val hit = cache[detailId]
                if (hit != null) {
                    if (now - hit.first < ttlMs) {
                        // 命中路径必须可观测，否则现场只能靠请求数反推（D18）。
                        // ⚠️ 只打 detailId：详情页 URL 的域名/query 都不该进日志。
                        debugLog(TAG, "详情页缓存命中: $detailId")
                        return hit.second
                    }
                    // TTL 过期是"点了刷新却没变"类投诉的第一嫌疑点（D18）。
                    debugLog(TAG, "详情页缓存过期: $detailId (age=${now - hit.first}ms >= ${ttlMs}ms)")
                    cache.remove(detailId)
                }
            }
        }

        if (!forceRefresh) {
            val existing = synchronized(inFlight) { inFlight[detailId] }
            if (existing != null) {
                return existing.await()
            }
        }
        val deferred = CompletableDeferred<String?>()
        var registered = false
        if (!forceRefresh) {
            val raced = synchronized(inFlight) {
                // 双重检查：拿锁前可能已有别人放进去了
                inFlight[detailId] ?: run {
                    inFlight[detailId] = deferred
                    null
                }
            }
            if (raced != null) return raced.await()
            registered = true
        }
        var fetched: String? = null
        val body = try {
            // ⚠️ `.also` 保证 fetched 在正常路径上一定拿到真实正文：旧实现在 finally 里
            // 读缓存里的值，而缓存是**这段 try 之后**才写的 —— 等待者永远拿到 null，
            // 于是把一次已经成功的请求当成"详情页没内容"。
            fetchBody(detailId, fetch).also { fetched = it }
        } finally {
            // ⚠️ 只有**注册者**才移除并完成（D12）：`forceRefresh` 路径没有注册自己的
            // deferred，如果它也 remove，就会删掉别人正在等待的条目。
            if (registered) {
                synchronized(inFlight) { inFlight.remove(detailId) }
                deferred.complete(fetched)
            }
        }

        if (!body.isNullOrBlank()) {
            // ⚠️ 时间戳在**拿到正文之后**取（P2-7）：请求耗时不能被算进缓存年龄。
            val cachedAt = clock()
            synchronized(cache) {
                cache[detailId] = cachedAt to body
            }
        }
        return body
    }

    /**
     * 用户显式点「刷新线路」：只让这一场的缓存失效。
     *
     * ⚠️ key 是 `detailId` 而不是 URL：详情页现在有多个候选域名，按写死的某个域名去
     * `remove` 会清不掉上次从镜像域名缓存的那一份 —— 按钮变摆设且不报错（P2-6）。
     * 只清这一场，不动其它场次（其它场次的缓存仍在 TTL 内，继续省请求）。
     */
    fun invalidate(detailId: String) {
        debugLog(TAG, "详情页缓存被显式失效: $detailId")
        synchronized(cache) { cache.remove(detailId) }
    }

    /** 数据源开关变化时清空整表（语义见 [MatchRepository.clearDetailCache] 的 KDoc）。 */
    fun clear() {
        debugLog(TAG, "详情页缓存被整体清空（数据源开关变更）")
        synchronized(cache) { cache.clear() }
    }

    companion object {
        /** 日志 tag：沿用拆分前的 `"MatchRepo"`，现场 logcat 过滤口径不变（D18）。 */
        private const val TAG = "MatchRepo"

        /** 详情页缓存有效期（5 分钟）。 */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /** 详情页缓存的最大条数：防止长时间运行后无限增长。 */
        const val DEFAULT_MAX_ENTRIES = 64
    }
}