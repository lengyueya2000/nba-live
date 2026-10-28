package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片内嵌按钮的**无障碍语义**（review/03-ui.md P2-25）与它的**生产接线**
 * （review/audit-guards.md B6）。
 *
 * ## 要解决什么
 *
 * 直播卡片整体 `.clickable(onClick)`，"立即观看"按钮**又** `.clickable(onClick)`
 * 且是同一个回调。TalkBack 会把它读成两个独立可点节点：
 * 用户滑到这里要停两次、听到两遍相同动作，且两次触发的行为完全一样。
 *
 * ## 正确做法（二选一，这里选后者）
 *
 * 1. 内层按钮不设 clickable，只做视觉强调（但那样它看着能点却点不动）；
 * 2. 内层按钮保留可点（视觉上确实是按钮），但把它从无障碍树里**合并**掉：
 *    对父节点 `mergeDescendants`，或对内层设 `clearAndSetSemantics`。
 *
 * 选 2 并配 `onClickLabel`：TalkBack 仍能读到一个节点、一个动作词。
 *
 * ## 为什么原来的测试是"假安全感"（B6）
 *
 * 原来这里断言 `CardSemantics.shouldMergeIntoParent(hasInnerButton = false) == false`
 * —— 那是个**恒等函数**，生产唯一调用传的是字面量 `true`，`false` 分支是生产
 * 永远走不到的路径；`actionLabel(hasInnerButton)` 的形参更是**被实现完全忽略**，
 * 两处调用传 true/false 返回同一串。测的是签名里的参数，不是界面的行为。
 *
 * 现在改成两条真判据：
 *  - 动作词常量本身可用（非空、非"激活"式空话、就是产品定的那一个）；
 *    **它绝不能是空串**：`onClickLabel = ""` 会让 TalkBack 只读"双击以激活"；
 *  - **两个生产调用点都消费它**：源码扫描（剔注释/字符串、排除声明行后数调用点）。
 *    少了这一步，"常量写好了但界面没用它"就没有任何信号。
 *
 * 另外把"直播卡确实合并了子节点语义"也钉在**渲染处的调用**上（原来钉的是恒等函数）。
 */
class CardButtonSemanticsTest {

    @Test
    fun `动作词是给读屏用户的一句话 不能是空串或激活式空话`() {
        // onClickLabel = "" 会让 TalkBack 只读"双击以激活"，等于没给信息
        assertTrue(
            "卡片动作词不能是空白串：读屏用户只会听到一串队名 + “双击以激活”，" +
                "不知道点了会发生什么（review/03-ui.md P2-25）",
            CardSemantics.ACTION_LABEL.isNotBlank(),
        )
        assertTrue(
            "动作词不能只写“激活”这类空话（与 TalkBack 的默认读法重复，等于没说做什么）：" +
                "实际=${CardSemantics.ACTION_LABEL}",
            !CardSemantics.ACTION_LABEL.contains("激活"),
        )
        assertTrue(
            "动作词太短，读屏时分辨不出动作：${CardSemantics.ACTION_LABEL}",
            CardSemantics.ACTION_LABEL.length >= 2,
        )
    }

    @Test
    fun `动作词就是产品定的 播放比赛`() {
        assertEquals(
            "动作词变了要同步设计稿与读屏文案（spec: review/03-ui.md P2-25）",
            "播放比赛",
            CardSemantics.ACTION_LABEL,
        )
    }

    @Test
    fun `两张卡片的动作词都消费 CardSemantics ACTION_LABEL 这一个常量`() {
        // 铁律②：匹配**调用点**而不是字符串出现 —— 先剔注释与字符串字面量。
        // 铁律④：文件找不到直接 error()，绝不静默跳过。
        val counts = CARD_FILES.map { name -> name to callSites(screenFile(name).readText(), LABEL) }
        val missing = counts.filter { (_, n) -> n < 1 }
        assertTrue(
            "以下卡片的 onClickLabel 没消费 CardSemantics.ACTION_LABEL:\n" +
                missing.joinToString("\n") { (name, n) -> "  · $name：$n 处" } +
                "\n读屏动作词必须两处共用同一个常量，不许各写一份字面量 / 各调一个被忽略参数的函数。",
            missing.isEmpty(),
        )
        assertTrue(
            "生产调用点合计只有 ${counts.sumOf { it.second }} 处（要求 ≥2：LiveNowCard + LiveHeroCard）" +
                "—— 少一处就说明有一张卡片的动作词没人消费",
            counts.sumOf { it.second } >= 2,
        )
    }

    @Test
    fun `直播卡确实把内嵌按钮合并进了父节点语义`() {
        // 原来"要不要合并"是 CardSemantics.shouldMergeIntoParent(hasInnerButton=true)
        // 这个恒等函数 + 字面量实参 —— 测试断的是参数，不是界面。现在钉渲染处的调用。
        val code = GuardSourceScan.stripNonCode(screenFile("LiveNowScreen.kt").readText())
        val body = functionBody(code, "LiveNowCard")
            ?: error("找不到 LiveNowCard 的函数体，守卫会静默全绿（cwd=${cwd()}）")
        assertTrue(
            "LiveNowCard 没有 `mergeDescendants = true`：这张卡里有内嵌的“立即观看”按钮，" +
                "不合并时 TalkBack 会读成两个可点节点、用户要停两次（review/03-ui.md P2-25）。" +
                "实际函数体：\n$body",
            MERGE_IN_PLACE.containsMatchIn(body),
        )
    }

    @Test
    fun `判据自检 命中真实调用而放过注释声明与字符串`() {
        // 审计 A9 批评过"自检本身是永真断言"：这里喂给**真正参与扫描的那个函数**，
        // 而不是把判据在自检里复述一遍。
        val sample = listOf(
            "package sample",
            "// 注释里提 CardSemantics.ACTION_LABEL 不算调用",
            "/* 块注释里的 CardSemantics.ACTION_LABEL 同样不算 */",
            "object CardSemantics { const val ACTION_LABEL = \"播放比赛\" }",
            "fun use() {",
            "    val a = CardSemantics.ACTION_LABEL",
            "    val b = \"CardSemantics.ACTION_LABEL 在字符串里不算\"",
            "}",
        ).joinToString("\n")
        assertEquals(
            "判据没数对调用点：注释/字符串里的字面量与声明行都该放过，真实调用那一行必须计入",
            1,
            callSites(sample, LABEL),
        )
    }

    // ---------------------------------------------------------------

    /**
     * [symbol] 在 [rawText] 里的**调用点**行数：先剔注释与字符串字面量，
     * 再排除把该名字**声明**出来的行（声明不算使用，见 AGENT_STATE 教训）。
     */
    private fun callSites(rawText: String, symbol: String): Int {
        val name = symbol.substringAfterLast('.')
        val declaration = Regex("""\b(?:fun|val|var)\s+${Regex.escape(name)}\b""")
        return GuardSourceScan.stripNonCode(rawText).lines()
            .filterNot { declaration.containsMatchIn(it) }
            .count { it.contains(symbol) }
    }

    /** 取 `fun <name>(...) { ... }` 的花括号体（[code] 必须已剔注释/字符串）。 */
    private fun functionBody(code: String, name: String): String? {
        val m = Regex("""\bfun\s+$name\s*\(""").find(code) ?: return null
        // 从 `(` 本身开始扫参数表：从括号之后开始的话，参数表里的 `()` 会把深度算错，
        // 带 lambda 参数的函数（这里正是 `onClick: () -> Unit`）就取不到函数体。
        var i = m.range.last
        var paren = 0
        var open = -1
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '=' -> if (paren == 0) return null
                '{' -> if (paren == 0) {
                    open = i
                    break
                }
            }
            i++
        }
        if (open < 0) return null
        var depth = 0
        i = open
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
        return null
    }

    private fun screenFile(name: String): File =
        screens().firstOrNull { it.name == name }
            ?: error("找不到 $name（cwd=${cwd()}），守卫会静默全绿")

    private fun screens(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui/screens"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui/screens"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 screens 目录，cwd=${cwd()}，候选=${roots.map { it.path }}")
        return root.listFiles { f -> f.isFile && f.extension == "kt" }?.toList() ?: emptyList()
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath

    private companion object {
        /** 生产里两张"整卡可点"的卡片：都必须消费同一个动作词常量。 */
        val CARD_FILES = listOf("LiveNowScreen.kt", "MatchListScreen.kt")

        /** 动作词的唯一来源（声明在 `CardSemantics`）。 */
        const val LABEL = "CardSemantics.ACTION_LABEL"

        /** 内嵌按钮必须被并进来 —— 事实就地写在渲染处，不再绕一层恒等函数。 */
        val MERGE_IN_PLACE = Regex("""\.semantics\(\s*mergeDescendants\s*=\s*true\s*[,)]""")
    }
}