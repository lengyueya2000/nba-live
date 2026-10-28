package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍语义的两条缺失（review/03-ui.md P2-23 / P2-24）。
 *
 * ## P2-23 搜索框没有可读的用途说明
 *
 * `TextField` 只给了 `placeholder`，`leadingIcon` 的 contentDescription 是 null。
 * placeholder **不是**无障碍标签：输入框一有内容它就消失，
 * TalkBack 读到的是一个"空文本框"，用户不知道这里能搜什么。
 * 正确做法是加 `label` 或用 `semantics { contentDescription = ... }`。
 *
 * ## P2-24 底栏标签未成组
 *
 * 三个标签（赛事/直播/我的）各自是一个可点节点，TalkBack 逐个读，
 * 用户不知道"这是一组互斥的导航选项、当前选中哪个"。
 * 加 `selectableGroup()` 后读屏会说明"标签页 1/3、已选中"。
 *
 * 这两条都是"能看见但读屏读不出来"的形态 —— 只能靠读源码断言。
 *
 * ## ⚠️ 本文件自己的两条"永远不会红"（2026-10-28 只读审计 A6 / A16）
 *
 *  - **A6** `呼吸红点不能只靠颜色传达信息` 原来断言 `contains("LiveDot")`：
 *    而 `Common.kt` 里 `fun LiveDot(` 的**声明**就满足它 ——
 *    于是"删掉旁边的「直播中」文字、只留红点"永远不会红。
 *    同类还有 `标签必须声明选中状态`（`selected = ` 的全文件析取，
 *    `SportBottomBar` 改坏也照样绿）。
 *  - **A16** 文件定位是 `walkTopDown().firstOrNull { it.name == name }`：
 *    出现同名文件时会**静默取遍历序第一个**（守错文件）。
 *
 * 现在一律：`GuardSourceScan.stripNonCode` 剔注释与字符串字面量、
 * 判据落在**调用点/函数体**上、路径不唯一或找不到直接 [error]，
 * 且每条判据都有一个喂内存样本的自检（能证明它在违规写法下会红）。
 */
class AccessibilitySemanticsTest {

    @Test
    fun `搜索框必须有可读的用途说明`() {
        val code = GuardSourceScan.stripNonCode(source("ui/screens/SearchScreen.kt"))

        // placeholder 不是标签：有输入内容时它就消失，读屏读不到用途。
        // ⚠️ 判据锚在 `TextField(...)` **这个调用点**上：审计 A6 的同款形态是
        // "全文件 contains 一个别处也有的符号"（原来只要文件里任意位置出现 `label = {` 就算过）。
        assertTrue(
            "搜索框的 TextField(...) 必须带 label = { ... }：\n" +
                "placeholder 在输入内容后消失，TalkBack 会读成「空文本框」，\n" +
                "用户不知道这里能搜什么（03-P2-23）",
            Regex("""TextField\s*\([\s\S]{0,800}?\blabel\s*=\s*\{""").containsMatchIn(code),
        )
    }

    @Test
    fun `底栏标签必须成组`() {
        // ⚠️ 跨文件定位：底栏外壳已从 NbaLiveApp.kt 拆到 ui/AppChrome.kt（03-P2-18 拆分），
        // 写死文件名的锚点会随重构失效。按**内容**唯一命中，找不到/多个都 error()。
        val code = GuardSourceScan.stripNonCode(sourceContaining("selectableGroup()"))

        // ⚠️ 必须匹配**调用** `.selectableGroup()`，不能只 contains("selectableGroup")：
        // import 行也含这个字符串，删掉调用、留下 import 时守卫会照样绿
        //（验证守卫时踩到了这个坑）。
        assertTrue(
            "底栏三个标签必须用 selectableGroup() 包住：\n" +
                "否则 TalkBack 逐个读成独立按钮，用户不知道这是互斥导航、也不知道选中了哪个（03-P2-24）",
            Regex("""\.selectableGroup\(\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `标签必须声明选中状态`() {
        // ⚠️ 同样跨文件定位：`fun SportBottomBar(` 现在在 ui/AppChrome.kt。
        val code = GuardSourceScan.stripNonCode(sourceContaining("fun SportBottomBar("))

        // 只成组还不够：要告诉读屏"当前选中的是哪个"。
        // ⚠️ 审计 A6：原来这里是 `contains("selectable(") || contains("selected = ")` 的
        // **全文件析取** —— `selected = ` 在分页指示等处也出现，改坏底栏照样绿。
        // 现在收进底栏 composable 的**函数体**里，并要求两者是同一次调用。
        val body = functionBody(code, "fun SportBottomBar(")
        assertTrue(
            "SportBottomBar 里必须出现 `.selectable(selected = <当前选中>, ...)`：\n" +
                "否则读屏用户不知道自己在哪个标签（03-P2-24）",
            Regex("""\.selectable\(\s*selected\s*=\s*\w+""").containsMatchIn(body),
        )
    }

    // ---------------- 只读审计 A6：红点不能只靠颜色 ----------------

    @Test
    fun `呼吸红点不能只靠颜色传达信息`() {
        // 反面保护：LiveDot 是纯装饰，旁边必须有"直播中"文字。
        // ⚠️ 原来断言 `stripComments(common).contains("LiveDot")`，
        // 而 `Common.kt:588` 就是 `fun LiveDot(...)` 的**声明** ——
        // 只要函数名还在就永远绿（审计 A6）。
        // 现在判据落在 StatusChip 的**函数体 + 调用点**上（见 [liveDotAloneViolations]）。
        val violations = liveDotAloneViolations(source("ui/components/Common.kt"))
        assertTrue(
            "「红点不能只靠颜色传达信息」守卫不合格（review/audit-guards.md A6）：\n" +
                violations.joinToString("\n").prependIndent("  ") +
                "\n\n删掉「直播中」文字、只留红点时，色盲/读屏用户无法分辨状态。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `红点判据自检 只剩红点时必须报违规`() {
        // 防"永远全绿的摆设"：内存样本喂给**真正参与生产判定**的那个函数。
        val compliant = """
            @Composable
            fun StatusChip(status: MatchStatus, liveLabel: String = "直播中") {
                val label = if (status == MatchStatus.LIVE) {
                    liveLabel
                } else {
                    StatusLabel.of(status, false)
                }
                Row {
                    if (status == MatchStatus.LIVE) {
                        LiveDot()
                    }
                    Text(text = label, color = fg)
                }
            }
        """.trimIndent()
        assertTrue(
            "判据误报了合规写法：\n" + liveDotAloneViolations(compliant).joinToString("\n"),
            liveDotAloneViolations(compliant).isEmpty(),
        )

        // 违规样本 1：只留红点，删掉「直播中」文字
        val dotOnly = """
            @Composable
            fun StatusChip(status: MatchStatus) {
                Row {
                    if (status == MatchStatus.LIVE) {
                        LiveDot()
                    }
                }
            }
        """.trimIndent()
        assertTrue(
            "删掉「直播中」文字、只留红点时判据必须报违规",
            liveDotAloneViolations(dotOnly).isNotEmpty(),
        )

        // 违规样本 2：文字还在，但红点变成无条件渲染（不再是状态指示）
        val unconditionalDot = """
            @Composable
            fun StatusChip(status: MatchStatus, liveLabel: String = "直播中") {
                val label = if (status == MatchStatus.LIVE) { liveLabel } else { "未开赛" }
                Row {
                    LiveDot()
                    Text(text = label, color = fg)
                }
            }
        """.trimIndent()
        assertTrue(
            "红点脱离 LIVE 分支（无条件渲染）时判据必须报违规",
            liveDotAloneViolations(unconditionalDot).isNotEmpty(),
        )

        // 反向锚点：注释里的 LiveDot / Text 不算命中（剔注释）
        val commentOnly = """
            // if (status == MatchStatus.LIVE) { LiveDot() }
            // Text(text = label)
            @Composable
            fun StatusChip(status: MatchStatus) {
                Row { }
            }
        """.trimIndent()
        assertTrue(
            "注释里的画面不算命中（否则删掉实现、留一句说明就照样绿）",
            liveDotAloneViolations(commentOnly).isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------
    // 判据
    // ---------------------------------------------------------------

    /**
     * 判据：红点必须与状态文字同处 [StatusChip] 的渲染体。
     *
     * 输入是**原始源码文本**；内部先剔注释与字符串字面量（铁律①），
     * 判据全部匹配**调用形状**（铁律②）。返回违规说明（空 = 达标）。
     *
     * 注意：`"直播中"` 这个字面量在剔字符串后不可见，所以判据走
     * 「LIVE 分支用 `liveLabel` 参数 ⇒ 同一个 `label` 被 `Text(text = label)` 渲染」
     * 这条**结构性链**证明"文字还在"，而不是靠字面量。
     */
    private fun liveDotAloneViolations(rawCode: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawCode)
        val out = mutableListOf<String>()
        // ⚠️ 函数体缺失或不是花括号体时，[functionBody] 内部直接 error()（铁律③）。
        // 注意：**全注释的函数体**剔注释后只剩空白 —— 那是"没有合规内容"，不是"找不到锚点"。
        val body = functionBody(code, "fun StatusChip(")
        // ① 红点必须是**调用**，不能只是名字出现在注释/声明里
        if (!Regex("""\bLiveDot\s*\(\s*\)""").containsMatchIn(body)) {
            out += "① StatusChip 里没有 LiveDot() 调用（红点已不在状态标签的渲染体里）"
        }
        // ② 红点必须挂在 LIVE 分支上（脱离状态就成了纯装饰）
        if (!Regex("""if\s*\(\s*status\s*==\s*MatchStatus\.LIVE\s*\)\s*\{\s*LiveDot\s*\(\s*\)""")
                .containsMatchIn(body)
        ) {
            out += "② LiveDot() 没有挂在 `if (status == MatchStatus.LIVE)` 分支里"
        }
        // ③ LIVE 的文案必须走 liveLabel 参数（文字与红点同时存在）
        if (!Regex("""liveLabel\s*:\s*String""").containsMatchIn(code)) {
            out += "③ StatusChip 没有 `liveLabel: String` 形参 —— 红点旁边没有文案来源"
        }
        if (!Regex("""if\s*\(\s*status\s*==\s*MatchStatus\.LIVE\s*\)\s*\{\s*liveLabel\b""")
                .containsMatchIn(body)
        ) {
            out += "④ LIVE 时没有使用 liveLabel（红点的语义无处传达）"
        }
        // ⑤ 同一个渲染体里必须真的把 label 画出来
        if (!Regex("""Text\(\s*text\s*=\s*label\b""").containsMatchIn(body)) {
            out += "⑤ 同一个渲染体里没有 `Text(text = label, ...)` —— 只剩红点在传达状态"
        }
        return out
    }

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

    // ---------------------------------------------------------------
    // 文件定位（审计 A16）
    // ---------------------------------------------------------------

    /**
     * 按**相对路径**取唯一一份生产源码。
     *
     * ⚠️ 审计 A16：原来用 `walkTopDown().firstOrNull { it.name == name }` ——
     * 出现同名文件（例如 `ui/` 与 `ui/screens/` 各一份）时会静默取遍历序第一个，
     * "找到哪个"不确定。现在要求**唯一命中**且路径与预期一致，否则 [error]。
     */
    private fun source(relative: String): String {
        val root = productionRoot()
        val name = relative.substringAfterLast('/')
        val hits = root.walkTopDown().filter { it.isFile && it.name == name }.toList()
        if (hits.isEmpty()) {
            error(
                "找不到 $relative（root=${root.path}，cwd=${System.getProperty("user.dir")}）" +
                    "—— 守卫会静默全绿，所以直接失败而不是跳过",
            )
        }
        val hit = hits.singleOrNull()
            ?: error(
                "$name 有 ${hits.size} 份：${hits.map { it.path }} —— " +
                    "按名字取第一个是不确定的路径（审计 A16），守卫可能守错文件",
            )
        val normalized = hit.path.replace('\\', '/')
        if (!normalized.endsWith("/com/nbalive/app/$relative")) {
            error("$name 唯一命中在 $normalized，不是 .../com/nbalive/app/$relative —— 守卫会守错文件")
        }
        return hit.readText()
    }

    /**
     * 按**代码内容**唯一定位生产文件并返回原文。
     *
     * 用途：符号被拆分/搬家到新文件后（例如 `NbaLiveApp.kt` → `AppChrome.kt`），
     * 写死相对路径的锚点会失效；按内容定位跟着代码走。0 个或多个命中都 `error()`
     * （见 [GuardSourceScan.uniqueFileContaining]），绝不静默。
     */
    private fun sourceContaining(marker: String): String {
        val root = productionRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        return GuardSourceScan.uniqueFileContaining(files, marker).readText()
    }

    private fun productionRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录，cwd=$cwd，候选=${roots.map { it.path }} —— 守卫会静默全绿")
    }
}