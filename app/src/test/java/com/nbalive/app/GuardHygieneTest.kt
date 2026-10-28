package com.nbalive.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试纪律的**元守卫**：再看守"守卫本身会不会静默变绿"。
 *
 * ## 为什么需要它
 *
 * 只读审计 `review/audit-guards.md`（A3 / A10）发现本仓库有一批**永远不会红的守卫**，
 * 同一个形态反复出现：
 *
 *  - `assumeTrue("找不到 X.kt，跳过", x != null)`：路径一解析不到，整条守卫就
 *    `skipped → 绿`。而"448 测试全绿"的报告里**看不出 skipped 与真的守住有什么区别** ——
 *    这正是本仓库踩过三次的「假安全感」。
 *  - 断言命中**注释**或**声明**（`contains("key(webFallbackUrl)")` 命中的是解释它的注释）。
 *
 * 2026-10-28 的硬化把它们统一改成硬失败。但"以后别再写回去"没有机制保障 ——
 * 这个文件就是那个机制：**它自己不再使用 assume 家族，并要求整个测试源集也不用**。
 *
 * ## 三条铁律（本文件自己也要满足）
 *
 * 1. **断言前先剔注释**：多个测试文件的注释里就写着「不能用 `assumeTrue`」，
 *    直接 `contains` 会命中那些注释而误报。
 * 2. **匹配代码、而不是字符串出现**：连**字符串字面量**也一并剔除（见
 *    [GuardSourceScan.stripNonCode]），否则本文件自己写的正则与失败文案会命中自己。
 * 3. **路径失败 `error()` 硬失败**，绝不用 assume 家族静默跳过；并且有**非空性锚点**
 *    （扫不到 60 个测试文件就红），防止"零违规的空扫"。
 */
class GuardHygieneTest {

    @Test
    fun `测试源码里不许用 assume 家族静默跳过`() {
        val violations = testFiles().flatMap { file ->
            scanForAssumeFamily(file.readText()).map { line -> "${file.name}:$line" }
        }
        assertTrue(
            "以下测试文件在**代码**里用了 assume 家族（assumeTrue/assumeFalse/org.junit.Assume）：\n" +
                "锚点缺失时整条守卫会 skipped→绿，而「全绿」的报告看不出区别 —— " +
                "这正是 review/audit-guards.md A3/A10 记录的假安全感。\n" +
                "请改成 assertTrue/error() 硬失败（并把文案改成真实语义，别再说「跳过」）。\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `测试源码里不许用 Kotlin 原生 assert（-ea 关掉就静默消失）`() {
        // 审计「未验证的怀疑 #2」：`SourceEndpointsTest.候选列表不能为空` 用的是
        // Kotlin 的 `assert(...)`，它只在 JVM `-ea` 打开时才求值。
        // Gradle 的 Test 任务默认开 `-ea`，所以**现在**有效 —— 但这条依赖是隐式的：
        // 一旦构建里出现 `enableAssertions = false`（或换执行器/IDE 配置不同），
        // 那几行会**静默消失**，而"全绿"的报告看不出区别（本仓库踩过三次的形态）。
        // 已把该处改成 JUnit 断言，并用这条元守卫钉住"以后别再写回去"。
        val violations = testFiles().flatMap { file ->
            scanForBareAssert(file.readText()).map { line -> "${file.name}:$line" }
        }
        assertTrue(
            "以下测试文件用了 Kotlin 原生 `assert(...)`：它在 -ea 关闭时**不求值**，" +
                "断言会静默消失而报告照样全绿。请改成 JUnit 的 assertTrue/assertEquals。\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
        // 自检：喂给真正参与扫描的那个函数，证明它抓得到原生 assert、又不会误报
        // JUnit 断言与注释/字符串（否则这条元守卫自己就是摆设）。
        val sample = listOf(
            "class Sample {",
            "    fun t() { assert(x > 0) }",
            "    fun u() { org.junit.Assert.assertTrue(x > 0) }",
            "    val s = \"assert( 只在字符串里\"",
            "}",
        ).joinToString("\n")
        assertTrue(
            "扫描器抓不到 Kotlin 原生 assert（它已退化成摆设）",
            scanForBareAssert(sample).isNotEmpty(),
        )
        assertTrue(
            "扫描器误报了注释/字符串里的 assert（剔非代码那一步没生效）",
            scanForBareAssert("// assert(x)\nval s = \"assert(y)\"\n/* assert(z) */").isEmpty(),
        )
        assertTrue(
            "扫描器把 JUnit 的 assertEquals/assertTrue 也当成了原生 assert（会大面积误报）",
            scanForBareAssert("assertEquals(a, b)\nassertTrue(c)\nassertFalse(d)").isEmpty(),
        )
    }

    @Test
    fun `扫描必须真的扫到测试源码 且锚点文件都在`() {
        // 铁律④：非空性锚点。工作目录一变，"零违规"的空扫会让上面那条断言静默全绿。
        val files = testFiles()
        assertTrue(
            "只扫到 ${files.size} 个测试文件（应 ≥60，cwd=${cwd()}）—— " +
                "扫描范围走偏时上面那条「零违规」断言会静默全绿，所以直接失败而不是跳过",
            files.size >= 60,
        )
        val names = files.map { it.name }.toSet()
        for (anchor in ANCHOR_FILES) {
            assertTrue(
                "没扫到 $anchor —— 扫描范围错了，与它相关的守卫都会变成摆设",
                anchor in names,
            )
        }
        // 反向锚点：本文件自己必须在扫描范围内。
        // 否则"剔字符串"这条规则会被绕过 —— 元守卫只要把自己排除在外，
        // 就能一边写着 assume 家族一边报绿。
        assertTrue(
            "元守卫自己必须也在扫描范围内（否则它可以把自己排除在外）",
            "GuardHygieneTest.kt" in names,
        )
    }

    @Test
    fun `扫描器自检 能抓到违规调用且不误报注释与字符串`() {
        // 防"永远全绿的摆设"（审计 A9 批评过那种 `bad.contains("systemDefault()")` 式自检）：
        // 这里喂给**真正参与扫描的那个函数**的内存样本，而不是把判据复述一遍。
        val violationSample = listOf(
            "package sample",
            "import org.junit.Assume.assumeTrue",
            "class Sample {",
            "    @Test fun t() { assumeTrue(\"找不到 X.kt，跳过\", 1 == 1) }",
            "    @Test fun u() { assumeFalse(2 == 2) }",
            "}",
        ).joinToString("\n")
        assertTrue(
            "扫描器抓不到真实的 assume 调用（它已经退化成摆设）",
            scanForAssumeFamily(violationSample).isNotEmpty(),
        )

        val cleanSample = listOf(
            "package sample",
            "// ⚠️ 这里讨论 assumeTrue：注释里出现不算违规",
            "/* 块注释里的 assumeFalse 也不算 */",
            "/** KDoc 里的 org.junit.Assume 同样不算 */",
            "class Sample {",
            "    val note = \"文案里写 assumeTrue 不是调用\"",
            "    val raw = \"\"\"原样字符串里的 assumeFalse\"\"\"",
            "}",
        ).joinToString("\n")
        assertTrue(
            "扫描器误报了注释/字符串里的字面量 —— 它会命中本仓库大量「不能用 assumeTrue」的说明文字",
            scanForAssumeFamily(cleanSample).isEmpty(),
        )
    }

    // ---------------------------------------------------------------

    /** 违反纪律的行号（1 起）。输入是**已剔除注释与字符串**的代码。 */
    private fun scanForAssumeFamily(text: String): List<Int> =
        GuardSourceScan.stripNonCode(text).lines().mapIndexedNotNull { index, line ->
            if (ASSUME_FAMILY.containsMatchIn(line)) index + 1 else null
        }

    /**
     * Kotlin **原生** `assert(...)` 的行号（1 起）。
     *
     * 正则要求 `assert` 后紧跟 `(`：`assertTrue(` / `assertEquals(` 不会被命中；
     * `(?<![\w.])` 让 `test.assert(` 这类链式调用（kotlin.test 的断言）也不被误报。
     */
    private fun scanForBareAssert(text: String): List<Int> =
        GuardSourceScan.stripNonCode(text).lines().mapIndexedNotNull { index, line ->
            if (BARE_ASSERT.containsMatchIn(line)) index + 1 else null
        }

    private fun testFiles(): List<File> =
        testRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private fun testRoot(): File {
        val candidates = listOf(
            File("src/test/java"),
            File("app/src/test/java"),
            File(System.getProperty("user.dir") ?: ".", "src/test/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到测试源码根 app/src/test/java（cwd=${cwd()}）—— " +
                    "元守卫本身会静默全绿，所以直接失败而不是跳过",
            )
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath

    private companion object {
        /**
         * `assumeTrue(` / `assumeFalse(` 调用，或 `org.junit.Assume` 的 import/链式引用。
         *
         * 注意：它只作用于**已剔除注释与字符串**的文本，所以写在说明文字里的同样字面量不会误报。
         */
        val ASSUME_FAMILY = Regex("""\bassumeTrue\b|\bassumeFalse\b|org\.junit\.Assume\b""")

        /**
         * Kotlin 原生 `assert(` 调用（**不是** JUnit 的 assertTrue/assertEquals…）。
         *
         * 原生 assert 依赖 JVM `-ea`：关掉就静默不求值，等于断言凭空消失。
         */
        val BARE_ASSERT = Regex("""(?<![\w.])assert\s*\(""")

        /** 扫描范围锚点：本轮硬化过的文件都必须被扫到。 */
        val ANCHOR_FILES = listOf(
            "LoggingDisciplineTest.kt",
            "SourceZoneDisciplineTest.kt",
            "SourceEndpointsWiringTest.kt",
            "CleartextPolicyTest.kt",
            "WebViewFileAccessTest.kt",
            "ListNoticePolicyTest.kt",
            "MatchListContrastDisciplineTest.kt",
            "DeadCodeWiringGuardTest.kt",
            "AndroidConfigVersionTest.kt",
        )
    }
}