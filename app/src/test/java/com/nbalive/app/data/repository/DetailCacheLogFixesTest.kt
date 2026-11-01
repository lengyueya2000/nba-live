package com.nbalive.app.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nbalive.app.TestPaths

/**
 * 详情页缓存三条路径的**可观测性**判据（task-6 / fix-data，对应 data.md D18）。
 *
 * ## 为什么必须扫源码而不是断言日志
 *
 * release 包里 `Log`/`debugLog` 是 Android 桩、JVM 单测拿不到真实 logcat，
 * "某次调用打了什么"无法断言（`LoggingDisciplineTest` 的 KDoc 有同款说明）。
 * 但"这条链路有没有留痕""留痕里有没有夹带 URL"是**静态可见**的，
 * 所以这里扫生产源码钉住它。
 *
 * ## 钉住的两条
 *
 *  1. 命中 / TTL 过期 / 显式失效(+整体清空) **四路都有 `debugLog`**：
 *     这是"对源站减负最有效的一次优化"，也是"点了刷新线路却拿到旧正文"
 *     类投诉的唯一嫌疑点 —— 没有日志就只能靠请求数反推。
 *  2. 这些日志**只打 detailId，不打 URL**：详情页 URL 带域名与
 *     （某些形态下）签名 query，写进 logcat 等于白送解析链路细节。
 *
 * ## D10 收口：扫描目标从 `MatchRepository.kt` 改成 `JrsDetailCache.kt`
 *
 * 缓存（含这四条日志）已随 D10 拆到 [JrsDetailCache]，判据跟着实现走 ——
 * 这正是"扫源码式守卫"必须显式维护的一环：目标文件搬了而判据没搬，
 * 它就会以"零命中"静默变绿（本文件的 `扫描锚点必须真的扫到文件` 就是拦这个的）。
 */
class DetailCacheLogFixesTest {

    @Test
    fun `详情页缓存四路都有 debugLog`() {
        val lines = cacheDebugLogLines()
        assertTrue(
            "缓存命中路径必须留痕（实际命中行：${lines.filter { it.contains("命中") }}）",
            lines.any { it.contains("命中") },
        )
        assertTrue(
            "TTL 过期路径必须留痕（实际：${lines.filter { it.contains("过期") }}）",
            lines.any { it.contains("过期") },
        )
        assertTrue(
            "显式失效（刷新线路）路径必须留痕（实际：${lines.filter { it.contains("失效") }}）",
            lines.any { it.contains("失效") },
        )
        assertTrue(
            "整体清空（数据源开关变更）路径必须留痕（实际：${lines.filter { it.contains("清空") }}）",
            lines.any { it.contains("清空") },
        )
    }

    @Test
    fun `缓存日志只打 detailId 不打 URL`() {
        val urlInterpolation = Regex(
            """\$\{?\s*(?:url|href|candidate|candidates|detailUrl|pageUrl|webUrl)\b""",
        )
        val offenders = cacheDebugLogLines().filter { urlInterpolation.containsMatchIn(it) }
        assertTrue(
            "详情页缓存日志里插值了 URL —— 域名与签名 query 会进 logcat，" +
                "而排查只需要 detailId：\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `扫描锚点必须真的扫到文件`() {
        // 非空性锚点：源码根解析不到（或文件搬家）时上面两条会"零违规"地静默全绿
        val text = productionJrsDetailCache()
        assertTrue("扫错文件了", text.contains("class JrsDetailCache"))
        assertTrue(
            "JrsDetailCache 里「详情页缓存」的 debugLog 一条都没扫到 —— 判据会静默全绿",
            cacheDebugLogLines().isNotEmpty(),
        )
    }

    /** 生产源码里所有提到「详情页缓存」的 `debugLog(...)` 行（已剔掉注释行）。 */
    private fun cacheDebugLogLines(): List<String> =
        productionJrsDetailCache().lines()
            .filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
            }
            .filter { it.contains("debugLog(") && it.contains("详情页缓存") }

    private fun productionJrsDetailCache(): String {
        val cwd = TestPaths.workingDirPath()
        val candidates = listOf(
            File("src/main/java/com/nbalive/app/data/repository/JrsDetailCache.kt"),
            File("app/src/main/java/com/nbalive/app/data/repository/JrsDetailCache.kt"),
            File(cwd, "src/main/java/com/nbalive/app/data/repository/JrsDetailCache.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error(
                "找不到 JrsDetailCache.kt（cwd=${File(cwd).absolutePath}）—— " +
                    "锚点缺失时这条守卫会静默全绿，所以直接失败而不是跳过",
            )
    }
}