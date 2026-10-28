package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复 UI 组件的收敛（review/03-ui.md P2-19 / P2-20）。
 *
 * ## 要解决什么
 *
 * 同一个小组件在多个文件里各写一份，改样式时只改到一半 ——
 * 这个仓库已经因此踩过坑（`AspectModeSelector` 的两份副本，
 * 改配色时浅色主题下的描边漏改，见 Common.kt 里的注释）。
 *
 * 审查点名的两处：
 *  - **P2-19**：`InfoRow` 在 MatchDetailScreen 与 ProfileScreen 各有一份，
 *    逐字相同（只差一个 overflow 参数）
 *  - **P2-20**：错误态组件从 4 份变成 5 份
 *    （MatchListScreen 的 ErrorBanner/ErrorState、LiveNowScreen 的
 *    LiveErrorBanner/LiveErrorState、MatchDetailScreen 的 SignalErrorState）
 *
 * ## 这条守卫守什么（三个不同的失败形态，不能只查字符串）
 *
 * 1. **同名小组件出现第二份实现** → `InfoRow 只能有一份实现`
 * 2. **同一结构被整段复制**（改一处漏一处） → `跨文件不得有逐字相同的私有实现`
 * 3. **全屏错误态骨架在 screen 里重新长出来** →
 *    `全屏错误态的骨架不得在 screen 里再抄一份`
 *
 * ## 为什么不用「私有 Error/Banner 函数体里出现 Surface( 就算违规」
 *
 * 试过这个判据，它是**假的**：`PartialBanner`（部分源不可用横幅）和
 * `SignalErrorState`（详情页内联失败态）都是**故意**不复用共享组件的，
 * 两者都有注释说明差异（不可点/不同配色；内联不套 fillMaxSize）。
 * 一刀切会把它们判违规，逼着后来人"为了过测试"把布局硬塞进共享组件 ——
 * 那才是真正的回归。所以判据收到**具体结构**上：
 *   - 全屏骨架 = 函数体里同时出现 `fillMaxSize()` 与
 *     `contentAlignment = Alignment.Center`（这正是原来复制了 3 份的形状）
 *   - 逐字复制 = 把函数体去掉空白后完全相同，且长到不可能是巧合（≥120 字符）
 */
class DuplicateComponentTest {

    @Test
    fun `InfoRow 只能有一份实现`() {
        val impls = productionFiles().filter { f ->
            Regex("""(private )?fun InfoRow\(""").containsMatchIn(stripComments(f.readText()))
        }
        assertTrue(
            "InfoRow 只应有一份实现（放在 ui/components 里），实际出现在：\n" +
                impls.joinToString("\n") { "  ${it.name}" } +
                "\n两份副本改样式时只会改到一半（这个仓库已经因此踩过坑）。",
            impls.size == 1,
        )
    }

    @Test
    fun `InfoRow 必须在 components 包里 而不是某个 screen 内`() {
        val impl = productionFiles().firstOrNull { f ->
            Regex("""(private )?fun InfoRow\(""").containsMatchIn(stripComments(f.readText()))
        } ?: error("找不到 InfoRow 实现，守卫会静默全绿")
        assertTrue(
            "InfoRow 是跨页面复用的小组件，应放在 ui/components 下；" +
                "现在在 ${impl.parentFile?.name}/ 里，别的页面用不到只能再抄一份",
            impl.parentFile?.name == "components",
        )
    }

    @Test
    fun `全屏错误态的骨架不得在 screen 里再抄一份`() {
        // 原来 MatchListScreen.ErrorState / LiveNowScreen.LiveErrorState 里
        // 各有一段 Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)
        // 的骨架。收敛后这两个都只是调用 FullScreenErrorState 的薄封装。
        val offenders = productionFiles().mapNotNull { f ->
            val code = stripComments(f.readText())
            val bad = privateFunctionBodies(code) { it.matches(ERROR_FAMILY) }
                .filter { (_, body) ->
                    body.contains("fillMaxSize()") &&
                        body.contains("contentAlignment = Alignment.Center")
                }
                .map { it.first }
            if (bad.isEmpty()) null else "${f.name}: ${bad.joinToString()}"
        }
        assertTrue(
            "以下 screen 里又长出了**整屏错误态骨架**（fillMaxSize + 居中），\n" +
                "应改为调用 ui/components 的 FullScreenErrorState（review/03-ui.md P2-20）；\n" +
                "如果确实需要不同布局（如内联/嵌在滚动容器里），请另起名字并注释原因：\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `跨文件不得有逐字相同的私有实现`() {
        // 把函数体去掉空白后完全相同 → 就是复制粘贴。阈值 120 字符是为了
        // 放过「xxx 已提到共享组件」这类一行薄封装（它们本来就该长一样）。
        val seen = mutableMapOf<String, MutableList<String>>()
        for (f in productionFiles()) {
            val code = stripComments(f.readText())
            for ((name, body) in privateFunctionBodies(code)) {
                val key = body.filterNot { it.isWhitespace() }
                if (key.length < 120) continue
                seen.getOrPut(key) { mutableListOf() } += "${f.name}#$name"
            }
        }
        val dupes = seen.values.filter { names ->
            names.map { it.substringBefore('#') }.distinct().size > 1
        }
        assertTrue(
            "以下私有实现**逐字相同**地存在于两个文件里（改一处必然漏一处，\n" +
                "review/03-ui.md P2-19/P2-20 就是这个形态）：\n" +
                dupes.joinToString("\n") { "  " + it.joinToString("  ==  ") },
            dupes.isEmpty(),
        )
    }

    @Test
    fun `共享错误组件必须真的被调用`() {
        // 防"抽出来了但没人用"——审查反复点名的形态
        val files = productionFiles()
        for (name in listOf("FullScreenErrorState(", "RetryErrorBanner(")) {
            val callers = files.filter { it.name != "Common.kt" }
                .filter { stripComments(it.readText()).contains(name) }
            assertTrue(
                "共享组件 $name 抽出来了却没有页面在用（真·假安全感）：" +
                    "调用点只有 ${callers.map { it.name }}",
                callers.isNotEmpty(),
            )
        }
    }

    @Test
    fun `扫描必须真的扫到源码`() {
        // 没有这条，路径解析一旦出错，上面几条会"零违规"地静默全绿。
        val files = productionFiles()
        assertTrue("扫描不到 ui 源码（cwd=${System.getProperty("user.dir")}），守卫会静默全绿", files.size >= 10)
        assertTrue("没扫到 screens/ 下的文件", files.any { it.parentFile?.name == "screens" })
        assertTrue("没扫到 components/ 下的文件", files.any { it.parentFile?.name == "components" })
    }

    private fun productionFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui"),
        )
        // 找不到就硬失败：不能用 assumeTrue 静默跳过（那等于守卫不存在）
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui 源码目录，cwd=$cwd，候选=${roots.map { it.path }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** 逐个提取私有函数的花括号体；表达式体函数（`= ...`）跳过。 */
    private fun privateFunctionBodies(
        code: String,
        nameFilter: (String) -> Boolean = { true },
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in PRIVATE_FUN.findAll(code)) {
            val name = m.groupValues[1]
            if (!nameFilter(name)) continue
            // 注意从 `(` 本身开始扫（m.range.last 就是这个括号）：
            // 若从括号之后开始，参数表里的 `()` 会让 depth 变成 -1，
            // 于是所有带 lambda 参数（`onRetry: () -> Unit`）的函数
            // 都取不到函数体 —— 守卫会静默变瞎（这个坑真踩过）
            val body = bodyStartAfter(code, m.range.last) ?: continue
            out += name to body
        }
        return out
    }

    private fun bodyStartAfter(code: String, from: Int): String? {
        var paren = 0
        var i = from
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                // 参数表之外出现 `=` 说明是表达式体函数（没有花括号体）
                '=' -> if (paren == 0) return null
                '{' -> if (paren == 0) return balanced(code, i)
            }
            i++
        }
        return null
    }

    private fun balanced(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        return code.substring(open + 1)
    }

    /**
     * 剔注释**与字符串字面量**，走共享的词法实现 [GuardSourceScan.stripNonCode]。
     *
     * ⚠️ 旧版本地实现只剔"整行注释"，行尾 `// …` 原样保留 → 删掉真组件、
     * 只在同一行留一句提到它的注释就能骗过去重判据（审计「未验证的怀疑 #6」）。
     */
    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)

    private companion object {
        val PRIVATE_FUN = Regex("""private fun (\w+)\s*\(""")
        val ERROR_FAMILY = Regex("""\w*(ErrorState|ErrorBanner)""")
    }
}