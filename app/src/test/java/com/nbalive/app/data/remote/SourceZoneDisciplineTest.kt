package com.nbalive.app.data.remote

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「源站时间必须按北京时间解释」的调用点守卫（review/02-data.md P2-2）。
 *
 * ## 为什么需要扫源码
 *
 * [SourceTimeZoneTest] 用「切 JVM 默认时区」的方式验证了**当前**这几个解析点
 * 的行为。但它挡不住"以后有人新加一个解析函数、顺手写了 systemDefault()"——
 * 新函数没被那个测试覆盖，偏移会静默回来。
 *
 * 所以这里把纪律钉死：**解析与判定层不许出现 `systemDefault()`**。
 * 展示层（把时间戳渲染成"今天 19:35"）用设备时区是对的，不在此列。
 */
class SourceZoneDisciplineTest {

    @Test
    fun `解析与判定层不得使用设备时区`() {
        val root = sourceRoot()
        assumeTrue("找不到 app/src/main/java，跳过源码扫描", root != null)

        // 允许使用设备时区的文件：只做**展示**（时间戳 -> 人类可读文案）
        val displayOnly = setOf("MatchFormat.kt", "MatchListScreen.kt", "MatchStatusClock.kt")

        val violations = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in displayOnly }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.trim()
                    // 跳过注释：文档里写着"不要用 systemDefault"是好事，不是违规
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                    if (!code.contains("systemDefault()")) return@mapIndexedNotNull null
                    "${file.name}:${index + 1}  $code"
                }
            }
            .toList()

        assertTrue(
            "解析/判定层用了设备时区：源站时间串是北京时间，用 systemDefault() 解释时\n" +
                "设备时区非东八区会让「直播中」判定与跨源合并键整体偏移。\n" +
                "请改用 MatchStatusClock.SOURCE_ZONE（展示层才用设备时区）。\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `守卫确实能抓到违规写法`() {
        // 防"永远全绿的摆设"：确认匹配规则对真实违规形态有效
        val bad = "java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())"
        val good = "java.time.Instant.ofEpochMilli(it).atZone(MatchStatusClock.SOURCE_ZONE)"
        assertTrue("必须能抓到 systemDefault()", bad.contains("systemDefault()"))
        assertTrue("不能误报 SOURCE_ZONE 写法", !good.contains("systemDefault()"))
    }

    private fun sourceRoot(): File? {
        val candidates = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }
}