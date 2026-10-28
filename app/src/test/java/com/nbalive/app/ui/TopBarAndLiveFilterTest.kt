package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏按钮的唯一来源（review/03-ui.md P2-22）与 LIVE 过滤的唯一来源（P2-10）。
 *
 * ## P2-22：三份"方形图标按钮"
 *
 * `NbaLiveApp.TopIconButton`（40dp 视觉 + 最小触摸目标）、`SubPageTopBar` 里的
 * 返回键 / 「更多」（同样 40dp）、`SearchScreen` 的返回键（直接 48dp）——
 * 同一个"顶栏按钮"在三个地方长了三个样子，视觉走查一眼能看出来
 * （搜索页返回键比列表页顶栏按钮大一圈）。
 *
 * 现在统一到 `components/Common.kt` 的 `SquareIconButton`（视觉 40dp、触摸 48dp）。
 *
 * ## P2-10：LIVE 过滤两处各写一份
 *
 * `NbaLiveApp` 为播放页的"同时在播"面板算了一份 `liveMatches`，
 * `LiveNowScreen` 又拿完整列表自己过滤一份 —— 同一语义两处实现，
 * 两次 O(n) 扫描 + 两份 List 分配，还会各自漂移。
 * 现在由调用方把算好的列表传进去。
 *
 * ## 为什么是源码守卫
 *
 * "少写一个共享调用"不会产生任何编译期/运行期信号：代码照样跑，
 * 只是又一个页面的按钮尺寸和别人不一样；过滤多算一遍也只是白花 CPU。
 * 这正是本项目反复踩的形态（函数有测试、使用纪律没有测试）。
 */
class TopBarAndLiveFilterTest {

    @Test
    fun `方形图标按钮只能有一份实现 且在 components 里`() {
        val impls = uiSources().filter { f ->
            Regex("""fun SquareIconButton\(""").containsMatchIn(stripComments(f.readText()))
        }
        assertTrue(
            "SquareIconButton 只应有一份实现（ui/components/），实际出现在：\n" +
                impls.joinToString("\n") { "  ${it.name}" },
            impls.size == 1 && impls.single().parentFile?.name == "components",
        )
    }

    @Test
    fun `不得再各写一份方形按钮`() {
        // "方尺寸 + 按钮圆角" 就是这几份副本的形状：40dp 那份（顶栏/二级页）和
        // 48dp 那份（搜索页返回键）都算。components/Common.kt 里那份用的是
        // SquareIconButtonVisualSize 常量，且本测试排除了 Common.kt。
        val shape = Regex(
            """\.size\((40|48)\.dp\)[\s\S]{0,160}?RoundedCornerShape\(Radius\.btn\)""" +
                """|RoundedCornerShape\(Radius\.btn\)[\s\S]{0,160}?\.size\((40|48)\.dp\)""",
        )
        val offenders = uiSources()
            .filter { it.name != "Common.kt" }
            .filter { shape.containsMatchIn(stripComments(it.readText())) }
            .map { it.name }
        assertTrue(
            "以下文件里又出现了一份「方尺寸 + 按钮圆角」的私有按钮实现：\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n请改用 components 的 SquareIconButton（review/03-ui.md P2-22）；" +
                "如果它确实不是顶栏按钮，请换用语义不同的尺寸来源并在注释里说明。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `共享方形按钮必须真的接到顶栏与搜索页上`() {
        // 防"抽出来了没人用"（审查反复点名的形态）。
        // ⚠️ 跨文件：顶栏外壳随 03-P2-18 拆分搬到 ui/AppChrome.kt（原 NbaLiveApp.kt），
        // 所以不再写死文件名，而是看"哪些文件真的调用了 SquareIconButton"。
        val codeByName = uiSources().associate { it.name to stripComments(it.readText()) }
        val callers = codeByName.filterKeys { it != "Common.kt" }
            .filterValues { it.contains("SquareIconButton(") }
            .keys
        assertTrue(
            "只有 ${callers.size} 个文件用了 SquareIconButton（应 ≥2：顶栏外壳 + 搜索页）：$callers\n" +
                "顶栏按钮又写回私有实现了（P2-22）",
            callers.size >= 2,
        )
        assertTrue(
            "SearchScreen 的返回键没有用 SquareIconButton（P2-22）",
            callers.contains("SearchScreen.kt"),
        )
        assertFalse(
            "`fun TopIconButton` 私有实现在改名之后又回来了（P2-22）：" +
                codeByName.filterValues { it.contains("fun TopIconButton") }.keys.toString(),
            codeByName.values.any { it.contains("fun TopIconButton") },
        )
    }

    @Test
    fun `直播页不得自己再过滤一遍 LIVE`() {
        // ⚠️ 只读审计 review/audit-guards.md A7：原来这里只有一条**负向**正则
        //   `filter\s*\{\s*it\.status\s*==\s*[A-Za-z0-9_.]*MatchStatus\.LIVE`
        // 它只匹配"lambda 参数恰好叫 it、右边以 MatchStatus.LIVE 结尾"这一种写法，
        // 等价但漏判的形态全放过去了（`m ->`、`import LIVE` 简写、
        // `filterNot { it.status != LIVE }`、`filter { it.status.name == "LIVE" }`）。
        // 负向守卫漏判**不会变红** —— 等价于"没有守卫"。
        // 现在判据放宽到结构形状，并配套自检（见下一条测试）证明它真的会红。
        val violations = liveRefilterViolations(stripped("LiveNowScreen.kt"))
        assertTrue(
            "LiveNowScreen 里又出现了自己筛选 LIVE 的代码（review/03-ui.md P2-10）：\n" +
                violations.joinToString("\n").prependIndent("  ") +
                "\n\nNbaLiveApp 已经算好一份 liveMatches 传进来了：同一语义两处实现会各自漂移，" +
                "列表几百场时还白做一次 O(n) 扫描 + 一份 List 分配。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `直播页 LIVE 过滤判据自检 各种等价写法都要能抓到`() {
        // 防"负向守卫漏判 = 没有守卫"：把真实违规形态喂给**参与生产判定**的那个函数。
        val violations = listOf(
            "val live = matches.filter { it.status == MatchStatus.LIVE }",
            "val live = matches.filter { m -> m.status == MatchStatus.LIVE }",
            "val live = matches.filter { it.status == LIVE }",
            "val live = matches.filterNot { it.status != MatchStatus.LIVE }",
            "val live = matches.filter { it.status.name == \"LIVE\" }",
            "val live = matches.filter { LIVE == it.status }",
            "val live = matches.filter { MatchStatus.LIVE == it.status }",
        )
        val missed = violations.filter { liveRefilterViolations(sampleScreen(it)).isEmpty() }
        assertTrue(
            "以下真实违规写法**没有被抓到**（负向守卫漏判 = 没有守卫）：\n" +
                missed.joinToString("\n").prependIndent("  "),
            missed.isEmpty(),
        )

        // 反向锚点 1：合规写法不能被误报
        val ok = sampleScreen("val live = liveMatches")
        assertTrue(
            "合规写法（直接渲染调用方传进来的列表）被误报了：\n" +
                liveRefilterViolations(ok).joinToString("\n"),
            liveRefilterViolations(ok).isEmpty(),
        )

        // 反向锚点 2：注释里的过滤不算违规（判别器若用 raw contains/正则不剔注释，
        // 这里会被误报成违规 —— 那正是审计 A6 记录的"命中注释"形态）
        val commented = sampleScreen("// val live = matches.filter { it.status == MatchStatus.LIVE }")
        assertTrue(
            "注释里的过滤被误报成违规了（没剔注释）：\n" +
                liveRefilterViolations(commented).joinToString("\n"),
            liveRefilterViolations(commented).isEmpty(),
        )
    }

    @Test
    fun `NbaLiveApp 必须把算好的 liveMatches 传给直播页`() {
        // ⚠️ 跨文件：直播页的调用点随 03-P2-18 拆分搬到 ui/AppChrome.kt（原 NbaLiveApp.kt）。
        // 用"非声明地出现 `LiveNowScreen(`"唯一定位调用文件（排除 screens/LiveNowScreen.kt 的声明）；
        // 0 个/多个都 error()，绝不静默取第一个。
        val callers = uiSources().filter { f ->
            val c = stripComments(f.readText())
            c.contains("LiveNowScreen(") && !Regex("""fun\s+LiveNowScreen\s*\(""").containsMatchIn(c)
        }
        if (callers.isEmpty()) error("整个 ui/ 里找不到 LiveNowScreen(...) 调用点，守卫会静默全绿")
        if (callers.size > 1) {
            error("LiveNowScreen(...) 出现在 ${callers.size} 个文件：${callers.map { it.name }} —— 锚点不唯一")
        }
        val live = stripComments(callers.single().readText())
        // ⚠️ 必须只看 LiveNowScreen 那个调用点：`liveMatches = liveMatches` 在
        // 播放页（PlayerScreen）的"同时在播"面板那里也合法存在 ——
        // 早先写成"全文件 contains(...)"时，把直播页改回传完整列表**抓不到**。
        val call = Regex("""LiveNowScreen\(([\s\S]*?)\n\s*\)""").find(live)
            ?: error("找不到 LiveNowScreen(...) 调用点，守卫会静默全绿")
        assertTrue(
            "LiveNowScreen 的调用点没有传 liveMatches = liveMatches（P2-10 的接线断了）：\n" +
                "直播页收到的又是完整列表，于是只能自己再过滤一遍。实际调用：\n${call.value}",
            call.groupValues[1].contains("liveMatches = liveMatches"),
        )
    }

    @Test
    fun `扫描必须真的扫到源码`() {
        val files = uiSources()
        assertTrue("扫描不到 ui 源码，上面几条会静默全绿", files.size >= 10)
        for (name in listOf("NbaLiveApp.kt", "LiveNowScreen.kt", "SearchScreen.kt", "Common.kt")) {
            assertTrue("没扫到 $name", files.any { it.name == name })
        }
    }

    // ---------------------------------------------------------------

    /**
     * 判据：`LiveNowScreen` 不得自己筛选 LIVE（返回违规说明，空 = 达标）。
     *
     * 输入是**原始源码文本**；内部先 [GuardSourceScan.stripNonCode] 剔注释与字符串字面量，
     * 判据锚在 `fun LiveNowScreen(` 的**函数体**里（别处的 filter 不算）。
     */
    private fun liveRefilterViolations(rawScreen: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawScreen)
        // ⚠️ 函数体缺失或不是花括号体时，[functionBody] 内部直接 error()（铁律③）。
        // 注意：**全注释的函数体**剔注释后只剩空白 —— 那是"没有违规"，不是"找不到锚点"。
        // （写这段时踩到过：用 isBlank() 当"没找到"的信号，会把合规样本误报成违规。）
        val body = functionBody(code, "fun LiveNowScreen(")
        val out = mutableListOf<String>()
        // 形状 1：filter / filterNot 的 lambda 里出现 status
        // （`it` / 命名参数 `m ->` / `import LIVE` 简写全覆盖；不需要 "LIVE" 字面量）
        Regex("""filter(?:Not)?\s*\{[^}]*\bstatus\b""").findAll(body).forEach {
            out += "filter 里又拿 status 做筛选：${it.value.trim()}"
        }
        // 形状 2：不经过 filter 的 status 与 LIVE 重复比较（含交换左右操作数）
        Regex("""\bstatus\s*==\s*(?:MatchStatus\.)?LIVE\b|\b(?:MatchStatus\.)?LIVE\s*==\s*\w+\.status\b""")
            .findAll(body).forEach {
                out += "函数体里又出现 status 与 LIVE 的比较：${it.value.trim()}"
            }
        return out
    }

    /** 把一段实现套进 LiveNowScreen 的壳里，供判据自检使用。 */
    private fun sampleScreen(implementation: String): String = """
        @Composable
        fun LiveNowScreen(liveMatches: List<Match>, loading: Boolean) {
            $implementation
        }
    """.trimIndent()

    private fun functionSpan(code: String, marker: String): Pair<Int, Int>? {
        val start = code.indexOf(marker)
        if (start < 0) return null
        var i = start + marker.length - 1
        var paren = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                // 参数表之外出现 `=` 说明是表达式体函数，没有花括号体
                '=' -> if (paren == 0) return null
                '{' -> if (paren == 0) return start to i
            }
            i++
        }
        return null
    }

    /** marker 的花括号函数体；找不到/非花括号体直接 [error]（铁律③，绝不静默全绿）。 */
    private fun functionBody(code: String, marker: String): String {
        val open = functionSpan(code, marker)?.second
            ?: error("源码里找不到 $marker 的花括号函数体 —— 守卫会静默全绿")
        return balanced(code, open)
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
        error("花括号不配对 —— 守卫会静默全绿")
    }

    private fun stripped(name: String): String {
        // 审计「未验证的怀疑 #7」：按文件名取第一个 → 同名文件时静默守错。现在唯一命中否则 error()。
        val f = GuardSourceScan.uniqueFile(uiSources(), name)
        return stripComments(f.readText())
    }

    private fun uiSources(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui 源码目录，cwd=$cwd，候选=${roots.map { it.path }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * 剔注释（连字符串字面量一起剔），走共享的词法实现 [GuardSourceScan.stripNonCode]。
     *
     * ⚠️ 早期这里是"整行注释才置空"的宽松版：行尾 `// …` 原样保留 ——
     * 于是"删掉真正的调用、在同一行留一句提到它的注释"就能让断言继续全绿
     * （审计「未验证的怀疑 #6」）。
     */
    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)
}