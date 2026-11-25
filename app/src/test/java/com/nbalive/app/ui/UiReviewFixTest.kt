package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import com.nbalive.app.TestPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮 UI 审查 6 条修复（P2×3 / P3×3）的回归守卫。
 *
 * ## 为什么全部是源码守卫
 *
 * 这 6 条的共同点和 ScreenConsistencyGuardTest / UiFixWiringTest 一样：
 * 改坏之后**编译器、纯 JVM 单测都不会有信号**（Compose 渲染在本工程的
 * `testDebugUnitTest` 里跑不到，项目也没有 instrumentation 测试）。
 * 能留下证据的只有调用点/分支结构，所以判据统一落在**剔注释后的调用形状**上，
 * 文件定位走 [TestPaths.productionFile]（唯一命中，找不到直接 error）。
 *
 * 六条各自钉住的是「修复前那段代码会给出的相反答案」，不是永真断言：
 *  - P2-1：旧信号的 else 分支里根本没有 error/onRetry；
 *  - P2-2：失败门槛带着 `items.isEmpty()`，正是旧数据泄漏的条件；
 *  - P2-3/P3-4：裸 `.clickable { }` 没有括号实参，callArgs 抓不到；
 *  - P3-5：选择器用的是 clickable 而不是 selectable/selectableGroup；
 *  - P3-6：`EmptyState(...)` 实参里只有 refreshing，没有 partialError。
 */
class UiReviewFixTest {

    @Test
    fun `P2_1 有旧信号时刷新失败必须显示内联错误与重试`() {
        val body = functionBody("MatchDetailScreen.kt", "MatchDetailScreen")
        val (emptyBranch, emptyEnd) = blockAfter("else if (signals.isEmpty())", body)
        assertTrue(
            "空信号分支仍要在 error 时给重试入口（SignalErrorState）",
            emptyBranch.contains("SignalErrorState("),
        )
        val (elseBranch, _) = blockAfter("else", body.substring(emptyEnd + 1))
        assertTrue(
            "有旧信号时失败被吞：signals 非空的 else 分支必须同时出现 error 判据、错误横幅与 onRetry。\n" +
                "实际 else 分支：$elseBranch",
            Regex("""error\s*!=\s*null""").containsMatchIn(elseBranch) &&
                elseBranch.contains("onRetry") &&
                (elseBranch.contains("RetryErrorBanner(") || elseBranch.contains("SignalErrorState(")),
        )

        // 自检：把旧写法喂给同一个判据，必须被判违规（否则守卫是摆设）
        val old = "else { Column { signals.forEachIndexed { i, s -> SignalRow(i, s) } } }"
        val (oldElse, _) = blockAfter("else", old)
        assertFalse(
            "判据退化：有旧信号却什么都不显示的旧写法也被判合规",
            Regex("""error\s*!=\s*null""").containsMatchIn(oldElse),
        )
    }

    @Test
    fun `P2_2 录像换日失败不得继续展示旧日期场次与场次数`() {
        val body = functionBody("ReplayScreen.kt", "ReplayScreen")
        assertFalse(
            "失败态仍以 items.isEmpty() 为门槛：换日失败但还留着旧 items 时会落到旧列表分支，" +
                "顶部日期却已变成新日期，用户会点进不属于该日期的录像",
            Regex("""state\.error\s*!=\s*null\s*&&\s*items\.isEmpty\(\)""").containsMatchIn(body),
        )
        assertTrue(
            "error 分支必须无条件渲染错误态（不再渲染旧卡片）",
            Regex("""state\.error\s*!=\s*null\s*->\s*FullScreenErrorState\(""").containsMatchIn(body),
        )
        assertTrue(
            "顶部「N 场」在失败时不能继续报旧数据：when 里需要独立的 error 分支取代旧场次数",
            Regex("""state\.error\s*!=\s*null\s*->""").findAll(body).count() >= 2,
        )
    }

    @Test
    fun `P2_3 清空历史必须是 48dp 带动作词且二次确认的按钮`() {
        val body = functionBody("ProfileScreen.kt", "ProfileScreen", keepStrings = true)
        val clearClicks = callArgs(body, ".clickable").filter { it.contains("showClearHistoryConfirm") }
        assertTrue("清空历史入口必须存在（点击先弹确认）", clearClicks.isNotEmpty())
        assertTrue(
            "清空历史必须给读屏动作词「清空全部录像历史」",
            clearClicks.any { it.contains("onClickLabel = \"清空全部录像历史\"") },
        )
        assertTrue("清空历史必须声明 role = Role.Button", clearClicks.any { it.contains("role = Role.Button") })
        assertTrue(
            "点击清空历史不能直接清库：必须先弹确认对话框",
            clearClicks.none { it.contains("onClearReplayHistory(") },
        )

        // 锚在 Text 调用本身，而不是「清空历史」这四个字（注释里也会提到它）
        assertTrue(
            "找不到清空历史入口文案的 Text 调用",
            body.contains("text = \"清空历史\""),
        )
        assertTrue(
            "清空历史触摸目标必须不小于 48dp（ProfileScreen 里只有这一处 min=48dp）",
            Regex("""heightIn\(\s*min\s*=\s*48\.dp\s*\)""").containsMatchIn(body),
        )

        assertTrue("清空历史必须有二次确认对话框", body.contains("AlertDialog("))
        val (confirm, _) = blockAfter("confirmButton =", body)
        assertTrue("真正清空只能发生在确认按钮里", confirm.contains("onClearReplayHistory()"))
    }

    @Test
    fun `P3_4 录像历史条目必须有续播动作词与 role`() {
        val body = functionBody("ProfileScreen.kt", "ProfileScreen", keepStrings = true)
        val entryClicks = callArgs(body, ".clickable").filter { it.contains("onReplayHistoryClick(entry)") }
        assertTrue(
            "历史条目仍是无括号实参的裸 clickable（U9 守卫扫不到、读屏只说「双击激活」）",
            entryClicks.isNotEmpty(),
        )
        assertTrue(
            "历史条目的动作词必须说明点下去是续播",
            entryClicks.any { Regex("onClickLabel\\s*=\\s*\"续播").containsMatchIn(it) },
        )
        assertTrue("历史条目必须声明 role = Role.Button", entryClicks.any { it.contains("role = Role.Button") })
    }

    @Test
    fun `P3_5 画面模式选择器必须暴露选中态与组内项数`() {
        val body = functionBody("Common.kt", "AspectModeSelector")
        assertTrue(
            "三档选择器必须成组（TalkBack 才能读出「共 3 项」）",
            Regex("""\.selectableGroup\s*\(""").containsMatchIn(body),
        )
        assertTrue(
            "每一档必须用 selectable(selected = …) 暴露选中态",
            Regex("""\.selectable\s*\(\s*selected\s*=\s*\w+""").containsMatchIn(body),
        )
        assertFalse(
            "不该再用裸 clickable 代替 selectable（读不出「已选中」）",
            Regex("""\.clickable\s*\(""").containsMatchIn(body),
        )
        val withStrings = functionBody("Common.kt", "AspectModeSelector", keepStrings = true)
        assertTrue(
            "每一档都要给读屏用户一个动作词（selectable 在 foundation 1.7.x 没有 onClickLabel 形参，" +
                "等价写法是 semantics { onClick(label = …) }）",
            Regex("""(?:onClickLabel\s*=\s*\"|onClick\s*\(\s*label\s*=\s*\")""").containsMatchIn(withStrings),
        )
    }

    @Test
    fun `P3_6 联赛筛到 0 场时空态也要消费 partialError`() {
        val body = functionBody("MatchListScreen.kt", "MatchListScreen")
        val emptyCalls = callArgs(body, "EmptyState")
        assertTrue("找不到 EmptyState(...) 调用，判据锚点失效", emptyCalls.isNotEmpty())
        assertTrue(
            "空态分支没有把 partialError（或 ListNoticePolicy 判定结果）传进去：" +
                "少了一个数据源却只说「今日暂无赛事」，用户分不清是源挂了还是真没比赛",
            emptyCalls.any { it.contains("partialError") || it.contains("notice") },
        )
        val emptyFn = functionBody("MatchListScreen.kt", "EmptyState")
        assertTrue(
            "EmptyState 必须真的渲染部分源缺失提示（不能只是把参数收下不用）",
            emptyFn.contains("PartialBanner(") || emptyFn.contains("ListNotice.Partial"),
        )
    }

    // ---------------------------------------------------------------
    // 判据工具（只在本测试文件里，不进生产代码）
    // ---------------------------------------------------------------

    private fun source(file: String): String = TestPaths.productionFile(file).readText()

    /**
     * 取 `fun <name>(...) { ... }` 的花括号体。
     *
     * 先用 [GuardSourceScan.stripNonCode] 定位（它剔注释/字符串但保留长度与换行），
     * [keepStrings] 时再把同一区间的源码取自 [GuardSourceScan.stripComments] —— 两种
     * 剔除都只把字符替换成空格、不改长度，所以下标可以直接复用。
     */
    private fun functionBody(file: String, name: String, keepStrings: Boolean = false): String {
        val raw = source(file)
        val stripped = GuardSourceScan.stripNonCode(raw)
        val marker = "fun $name("
        val start = stripped.indexOf(marker)
        require(start >= 0) { "$file 找不到 $marker —— 判据会静默失效" }
        var i = start + marker.length - 1
        var paren = 0
        while (i < stripped.length) {
            when (stripped[i]) {
                '(' -> paren++
                ')' -> {
                    paren--
                    if (paren == 0) break
                }
            }
            i++
        }
        val open = stripped.indexOf('{', i)
        require(open >= 0) { "$file 找不到 $name 的花括号体（可能是表达式体函数）" }
        var depth = 0
        var j = open
        while (j < stripped.length) {
            when (stripped[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            j++
        }
        require(j < stripped.length) { "$file 花括号不配对 —— 判据会静默失效" }
        val text = if (keepStrings) GuardSourceScan.stripComments(raw) else stripped
        return text.substring(open + 1, j)
    }

    /** [marker] 之后的第一个花括号体（含内容，不含两端花括号），返回它与右括号下标。 */
    private fun blockAfter(marker: String, text: String): Pair<String, Int> {
        val start = text.indexOf(marker)
        require(start >= 0) { "找不到锚点 $marker —— 判据会静默失效" }
        val open = text.indexOf('{', start + marker.length)
        require(open >= 0) { "$marker 后面找不到花括号体" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i) to i
                }
            }
            i++
        }
        error("$marker 花括号不配对 —— 判据会静默失效")
    }

    /** [marker] 的每次调用，返回完整实参文本（按括号配对截取，跨行调用也能取全）。 */
    private fun callArgs(code: String, marker: String): List<String> {
        val out = mutableListOf<String>()
        var idx = code.indexOf(marker)
        while (idx >= 0) {
            var i = idx + marker.length
            while (i < code.length && code[i].isWhitespace()) i++
            if (i < code.length && code[i] == '(') {
                var depth = 0
                var j = i
                while (j < code.length) {
                    when (code[j]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    j++
                }
                out += code.substring(i, minOf(j + 1, code.length))
            }
            idx = code.indexOf(marker, idx + 1)
        }
        return out
    }
}
