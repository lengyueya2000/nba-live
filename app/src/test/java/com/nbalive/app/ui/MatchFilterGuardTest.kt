package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE 过滤只能有一处实现（review/audit-guards.md「未验证的怀疑 #5」）。
 *
 * ## 要解决的形态
 *
 * 生产在根组合项里就地写了一份 `matches.filter { it.status == MatchStatus.LIVE }`，
 * `MainViewModelTest.liveIds` 又**自己抄了逐字相同的一份**。两者当时一致，
 * 但生产一旦加条件（例如排除延期场次），那两条用例**不会红** ——
 * 「测试与生产各写一份规则」正是本仓库反复踩的假安全感：
 * 规则有两份时，改一处不影响另一处，而测试仍然全绿。
 *
 * 现在两边都调 `MatchFilters.liveOnly`，本文件钉住三件事：
 *  1. `ui/` 里这个「规范过滤形状」只允许出现在 `MatchFilters.kt`，且**恰好一处**
 *     （多一处 = 文件里又抄了一份）；
 *  2. `liveOnly(` 必须有真实生产调用点（防止生产改回就地 `filter`）；
 *  3. **测试侧**必须复用同一个函数，不许自查一遍。
 *
 * ## 判据的边界（写清楚，避免后来又"以为守住了"）
 *
 * 判据认的是**规范形状**：`filter` / `filterNot` 的 lambda 里**只**做
 * `status` 与 `LIVE` 的比较。多条件的过滤（如 `PlayerScreen` 的
 * 「同时在播但不含当前场」：`it.id != match?.id && it.status == MatchStatus.LIVE`）
 * 是**另一种语义**，不在判据范围内 —— 它由"生产必须调 liveOnly"这条
 * 接线判据兜住：生产一旦把它换成就地多条件过滤，第 2 条会红。
 * 这条边界是**刻意**的：把不同的语义一刀切会逼着后来人为了过测试硬塞共用函数
 * （本仓库在 `DuplicateComponentTest` 里已经踩过这个反面）。
 *
 * 铁律：断言前剔注释与字符串（[GuardSourceScan.stripNonCode]）；匹配**过滤形状**
 * 而不是名字出现；路径失败 `error()` 硬失败，绝不用 `assume` 家族静默跳过；
 * 每条判据都有内存样本自检（含反向样本）。
 */
class MatchFilterGuardTest {

    @Test
    fun `LIVE 过滤只能有一处实现`() {
        val files = productionUiFiles()
        val offenders = files
            .filter { it.name != MATCH_FILTERS_FILE }
            .flatMap { file -> canonicalLiveFilters(file.readText()).map { "${file.name}: $it" } }
        assertTrue(
            "以下位置又出现了「规范 LIVE 过滤」（`filter { … status … LIVE … }`）：\n" +
                offenders.joinToString("\n").prependIndent("  ") +
                "\nLIVE 过滤的唯一实现是 $MATCH_FILTERS_FILE 的 liveOnly：" +
                "生产与测试各写一份时，生产加条件（例如排除延期场次）测试不会红" +
                "（review/audit-guards.md「未验证的怀疑 #5」）。",
            offenders.isEmpty(),
        )

        val definition = GuardSourceScan.uniqueFile(files, MATCH_FILTERS_FILE)
        assertEquals(
            "$MATCH_FILTERS_FILE 里必须**恰好一处**规范 LIVE 过滤（多出来的就是文件内又抄了一份）：",
            1,
            canonicalLiveFilters(definition.readText()).size,
        )
    }

    @Test
    fun `LIVE 过滤判据自检 等价写法命中而注释与多条件不命中`() {
        // 违规样本：等价写法都要能抓到（换参数名、filterNot、交换左右操作数）
        val violations = listOf(
            "val live = matches.filter { it.status == MatchStatus.LIVE }",
            "val live = matches.filter { m -> m.status == MatchStatus.LIVE }",
            "val live = matches.filterNot { it.status != MatchStatus.LIVE }",
            "val live = matches.filter { MatchStatus.LIVE == it.status }",
        )
        val missed = violations.filter { canonicalLiveFilters(sample(it)).isEmpty() }
        assertTrue(
            "以下真实违规写法**没有被抓到**（负向守卫漏判 = 没有守卫）：\n" +
                missed.joinToString("\n").prependIndent("  "),
            missed.isEmpty(),
        )

        // 反向锚点 1：注释里的过滤不算（剔非代码）
        assertTrue(
            "注释里的过滤被误报成违规（没剔注释）：删掉真实现、留一句注释就能骗过判据",
            canonicalLiveFilters(
                sample("// val live = matches.filter { it.status == MatchStatus.LIVE }"),
            ).isEmpty(),
        )
        // 反向锚点 2：字符串字面量里的过滤不算
        assertTrue(
            "字符串字面量里的过滤被误报（剔非代码时必须连字符串一起剔）",
            canonicalLiveFilters(
                sample("val note = \"matches.filter { it.status == MatchStatus.LIVE } 是旧写法\""),
            ).isEmpty(),
        )
        // 反向锚点 3：多条件过滤是**另一种语义**，刻意不在判据范围内
        assertTrue(
            "多条件过滤（同时在播但不含当前场）不该被这条判据误报 —— " +
                "它由「生产必须调 liveOnly」那条接线判据兜住",
            canonicalLiveFilters(
                sample("val others = matches.filter { it.id != match?.id && it.status == MatchStatus.LIVE }"),
            ).isEmpty(),
        )
    }

    @Test
    fun `生产必须真的调用 liveOnly`() {
        val calls = productionUiFiles().sumOf { GuardSourceScan.callSites(it.readText(), "liveOnly") }
        assertTrue(
            "生产里 liveOnly 的调用点有 $calls 处（要求 ≥1）：\n" +
                "要么生产改回了就地 `filter`（又出现第二份实现），" +
                "要么共享函数写好了却没人调（本仓库最忌讳的「假安全感」）。",
            calls >= 1,
        )
    }

    @Test
    fun `测试侧必须复用 liveOnly 而不是自己再写一遍`() {
        val testFile = GuardSourceScan.uniqueFile(testFiles(), MAIN_VM_TEST)
        assertTrue(
            "$MAIN_VM_TEST 里没有调用 liveOnly：它必须断言**生产真正用的那份**过滤结果，" +
                "自己再写一遍 `filter { it.status == MatchStatus.LIVE }` 时，" +
                "生产加条件这些用例不会红（audit「未验证的怀疑 #5」）。",
            GuardSourceScan.callSites(testFile.readText(), "liveOnly") >= 1,
        )
        assertTrue(
            "$MAIN_VM_TEST 里又出现了一份就地 LIVE 过滤：$MAIN_VM_TEST 与生产各写一份规则，" +
                "改一处不影响另一处（audit「未验证的怀疑 #5」）。",
            canonicalLiveFilters(testFile.readText()).isEmpty(),
        )
    }

    @Test
    fun `扫描必须真的扫到 ui 源码与两个锚点文件`() {
        val files = productionUiFiles()
        assertTrue(
            "只扫到 ${files.size} 个 ui 源文件（应 ≥8，cwd=${System.getProperty("user.dir")}）—— " +
                "扫描范围走偏时上面几条会静默全绿",
            files.size >= 8,
        )
        assertTrue("没扫到 $MATCH_FILTERS_FILE（唯一实现）", files.any { it.name == MATCH_FILTERS_FILE })
        assertTrue(
            "没扫到 $MAIN_VM_TEST（测试侧锚点）",
            testFiles().any { it.name == MAIN_VM_TEST },
        )
    }

    // ---------------------------------------------------------------
    // 判据（内存样本与生产源码走**同一个**函数）
    // ---------------------------------------------------------------

    /**
     * 「规范 LIVE 过滤」形状的出现（返回匹配文本，空 = 没有）。
     *
     * 判据：`filter` / `filterNot` 的 lambda 体里**同时**出现 `status` 与 `LIVE`，
     * 且体里**没有**其它条件（`&&` / `||`）—— 多条件的是另一种语义，见类 KDoc 的边界说明。
     * 输入是**原始源码**：内部先剔注释与字符串字面量（铁律①）。
     */
    private fun canonicalLiveFilters(rawText: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawText)
        return LIVE_FILTER_LAMBDA.findAll(code).mapNotNull { match ->
            val body = match.groupValues[1]
            val mentionsStatus = Regex("""\bstatus\b""").containsMatchIn(body)
            val mentionsLive = Regex("""\bLIVE\b""").containsMatchIn(body)
            val multiCondition = body.contains("&&") || body.contains("||")
            if (mentionsStatus && mentionsLive && !multiCondition) match.value.trim() else null
        }.toList()
    }

    /** 把一段实现套进"比赛列表过滤"的壳里，供判据自检使用。 */
    private fun sample(implementation: String): String = """
        fun filterSample(matches: List<Match>, match: Match?) {
            $implementation
        }
    """.trimIndent()

    // ---------------------------------------------------------------
    // 文件定位
    // ---------------------------------------------------------------

    private fun productionUiFiles(): List<File> {
        val root = File(productionRoot(), "ui")
        if (!root.isDirectory) {
            error("找不到 ui 源码目录（${root.path}）—— 本文件所有守卫会静默全绿")
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    private fun productionRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd，候选=${candidates.map { it.path }}）—— 守卫会静默全绿")
    }

    private fun testFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/test/java/com/nbalive/app"),
            File(cwd, "app/src/test/java/com/nbalive/app"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到测试源码目录（cwd=$cwd，候选=${candidates.map { it.path }}）—— 守卫会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    private companion object {
        /** LIVE 过滤的唯一实现所在文件。 */
        const val MATCH_FILTERS_FILE = "MatchFilters.kt"

        /** 测试侧锚点：断言"同时在播"面板数据的那批用例。 */
        const val MAIN_VM_TEST = "MainViewModelTest.kt"

        /** `filter` / `filterNot` 的 lambda 体（简单 lambda，体里没有嵌套花括号）。 */
        val LIVE_FILTER_LAMBDA = Regex("""filter(?:Not)?\s*\{([^{}]*)\}""")
    }
}