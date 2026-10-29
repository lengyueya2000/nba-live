package com.nbalive.app.data.remote

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 *
 * ## ⚠️ 本文件自己的一条"永远全绿"的守卫（2026-10-28 只读审计 A9）
 *
 * `守卫确实能抓到违规写法` 原来是：
 * ```kotlin
 * val bad = "…atZone(java.time.ZoneId.systemDefault())"
 * assertTrue("必须能抓到 systemDefault()", bad.contains("systemDefault()"))
 * ```
 * 断言的是 **Kotlin 标准库的 `String.contains`**，与上面那条守卫的扫描逻辑
 * （`walkTopDown` + 行内匹配 + 白名单）**毫无关系** —— 除非标准库坏掉否则不可能为假，
 * 它给人一种"自检过了"的错觉，却完全没验证扫描范围/白名单是否漏文件。
 *
 * 现在把扫描逻辑提成 [scanZoneViolations]（**生产守卫与自检共用同一个函数**），
 * 自检喂内存样本，并断言"违规样本被抓、白名单/注释/字符串/合规样本都不抓"。
 */
class SourceZoneDisciplineTest {

    @Test
    fun `解析与判定层不得使用设备时区`() {
        val root = sourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // 非空性锚点（审计 A10）：扫不到源码时，"零违规"会静默全绿
        assertTrue(
            "只扫到 ${files.size} 个生产 kt 文件（应 ≥30，root=${root.path}）—— " +
                "时区纪律守卫会静默全绿",
            files.size >= 30,
        )

        val violations = scanZoneViolations(
            entries = files.map { it.name to it.readText() },
            allow = DISPLAY_ONLY,
        )
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
        // ⚠️ 审计 A9：这里必须把样本喂给**真正参与守卫**的 [scanZoneViolations]，
        // 不能另写一份等价逻辑，也不能像旧版那样断言 `String.contains`。
        val entries = listOf(
            // 违规：解析层直接用设备时区
            "JrsParser.kt" to "val z = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())",
            // 白名单文件：只做展示，允许
            "MatchFormat.kt" to "internal val systemZone: ZoneId by lazy { ZoneId.systemDefault() }",
            // 注释里写同样的字面量不是违规（剔注释）
            "SomeDoc.kt" to "// 不要用 ZoneId.systemDefault() 解释源站时间",
            // 字符串字面量里出现不是违规（匹配调用而非字符串出现）
            "SomeLog.kt" to "val note = \"ZoneId.systemDefault() 是错的\"",
            // 合规写法
            "Good.kt" to "val z = java.time.Instant.ofEpochMilli(t).atZone(MatchStatusClock.SOURCE_ZONE)",
        )
        val violations = scanZoneViolations(entries, DISPLAY_ONLY)
        val joined = violations.joinToString("\n")

        assertTrue(
            "真实违规写法（解析层里的 systemDefault() 调用）必须被抓到，且带行号：\n$joined",
            violations.any { it.startsWith("JrsParser.kt:1") },
        )
        assertFalse(
            "白名单文件（只做展示）不该被抓 —— 白名单本身也要被这份样本证明有效：\n$joined",
            violations.any { it.startsWith("MatchFormat.kt") },
        )
        assertFalse(
            "注释里提到 systemDefault() 不算违规（否则本仓库大量「不要用 systemDefault」的说明会误报）：\n$joined",
            violations.any { it.startsWith("SomeDoc.kt") },
        )
        assertFalse(
            "字符串字面量里的同一个词不算违规（判据必须匹配调用而不是字符串出现）：\n$joined",
            violations.any { it.startsWith("SomeLog.kt") },
        )
        assertFalse(
            "合规写法（SOURCE_ZONE）被误报：\n$joined",
            violations.any { it.startsWith("Good.kt") },
        )

        // 对照：旧自检 `bad.contains("systemDefault()")` 断的是标准库，不是扫描函数。
        // 用同一批内存样本演示它**没有判别力** —— 在"注释样本"上它照样判真，
        // 而真扫描函数判合规。两者结论相反 ⇒ 新自检确实测到了扫描逻辑。
        val legacyVerdictOnCommentSample = "// 不要用 ZoneId.systemDefault()".contains("systemDefault()")
        assertTrue(
            "旧写法（String.contains）在注释样本上也判真 —— 这就是它永远全绿、无法发现扫描器退化的证据",
            legacyVerdictOnCommentSample,
        )
        assertFalse(
            "真扫描函数在同一份注释样本上判合规（结论与 contains 相反）",
            violations.any { it.startsWith("SomeDoc.kt") },
        )
    }

    // ---------------------------------------------------------------

    /**
     * 解析/判定层的时区纪律扫描（**生产守卫与自检共用**）。
     *
     * @param entries 文件名 → 源码文本（生产走真实文件，自检走内存样本）
     * @param allow 允许使用设备时区的**展示层**文件
     * @return 违规清单（`file:line  code`），空 = 达标
     *
     * 输入先经 [GuardSourceScan.stripNonCode] 剔注释与字符串字面量，
     * 所以"注释里写着不要用 systemDefault"不会误报，
     * 而"删掉调用、只在同一行留一句注释"也不再命中。
     */
    private fun scanZoneViolations(entries: List<Pair<String, String>>, allow: Set<String>): List<String> =
        entries.filter { (name, _) -> name !in allow }
            .flatMap { (name, text) ->
                GuardSourceScan.stripNonCode(text).lines().mapIndexedNotNull { index, line ->
                    if (BANNED.containsMatchIn(line)) "$name:${index + 1}  ${line.trim()}" else null
                }
            }

    /** 源码根；找不到直接失败（铁律③），绝不静默跳过。 */
    private fun sourceRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到 app/src/main/java（cwd=$cwd，候选=${candidates.map { it.path }}）—— " +
                    "锚点缺失时时区纪律守卫会静默全绿，所以直接失败而不是跳过",
            )
    }

    private companion object {
        /**
         * 允许使用设备时区的文件：只做**展示**（时间戳 -> 人类可读文案）。
         *
         * ⚠️ 不要再把 `MatchStatusClock.kt` 放进来（审计#7 的白名单洞）：
         * 它是**判定层**文件（KDoc 写着"解析与判定固定东八区"），
         * 白名单把它排除后，以后有人在 `byClock`/`aged` 里写回 `systemDefault()`
         * 这条纪律守卫**不会红**。实测该文件当前没有任何 `systemDefault()` 调用，
         * 所以移除白名单是纯收紧、零风险。
         */
        val DISPLAY_ONLY = setOf("MatchFormat.kt", "MatchListScreen.kt")

        /** 设备时区**调用**（不是提到这个名字）。 */
        val BANNED = Regex("""\bsystemDefault\s*\(\s*\)""")
    }
}