package com.nbalive.app.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nbalive.app.GuardSourceScan
import com.nbalive.app.ui.components.logoFallbackFontSize
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui/components` 的组件性能与回退字号（review/03-ui.md P2-11 / P2-12 / P2-27）。
 *
 * ## 为什么是源码守卫
 *
 * 这三条的对象都是**组合期行为**（lambda 实例是否每次重组新建、`remember`
 * 的调用点是否随分支漂移、`.sp` 是否被 fontScale 放大），JVM 单测跑不到
 * 组合过程。能跑纯判据的只有 P2-27 的字号换算，所以：
 *
 *  - P2-27：**两层**。① 纯函数 [logoFallbackFontSize] 的换算在
 *    fontScale = 1 / 1.3 / 2 下都必须落在容器内（这一层是真的会算错的）；
 *    ② 源码守卫钉住 `LogoFallback` **调用**了它、而不是又写回裸 `.sp`。
 *  - P2-11 / P2-12：只能扫源码。判据都盯**调用/声明形状**，
 *    不盯字符串出现（`import` 行或注释里的同名 token 会让守卫永远绿）。
 *
 * ## 守卫的三条自律（这个仓库踩过的坑）
 *
 * 1. 断言前一律 [stripComments]（注释里写着同样的字面量，尤其 P2-11/P2-12
 *    的"为什么这么改"注释）
 * 2. 匹配调用（`loading = { LogoFallback(`、`logoFallbackFontSize(`），
 *    不匹配裸标识符
 * 3. 源码根或函数体取不到就 `error()` 硬失败，绝不用 `assumeTrue` 静默跳过
 */
class ComponentPerfTest {

    // ---------------- P2-11：回退 lambda 不能每次重组都新建 ----------------

    @Test
    fun `TeamLogo 不得在组合期新建回退 lambda`() {
        val body = functionBody(requireCommon(), "fun TeamLogo(")
            ?: error("找不到 TeamLogo 的函数体（签名变了？），守卫会静默失效")

        assertFalse(
            "TeamLogo 里又出现了组合期新建的局部 lambda：\n" +
                "  body 片段 = ${body.take(160).replace('\n', ' ')}\n" +
                "`SubcomposeAsyncImage` 的 loading/error 槽每次重组都收到新实例，\n" +
                "子组合要重新执行（review/03-ui.md P2-11）。\n" +
                "回退块必须提到函数外的 Composable，两个槽只做调用。",
            LOCAL_LAMBDA.containsMatchIn(body),
        )
        // 匹配**调用**：光有 LogoFallback 的定义不够，两个槽必须真的调它
        for (slot in listOf("loading", "error")) {
            assertTrue(
                "TeamLogo 的 $slot 槽没有调用共享的 LogoFallback(...)：\n" +
                    "  body = ${body.take(200).replace('\n', ' ')}",
                Regex("""$slot\s*=\s*\{\s*LogoFallback\(""").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `回退块必须只有一份私有实现`() {
        val files = productionFiles()
        val decls = files.filter { Regex("""fun LogoFallback\(""").containsMatchIn(stripComments(it.readText())) }
        assertEquals(
            "回退块必须且只能有一份实现（多一份副本就会「改一处漏一处」）：\n" +
                decls.joinToString("\n") { "  ${it.name}" },
            1,
            decls.size,
        )
        val common = files.first { it.name == "Common.kt" }
        assertTrue(
            "回退块是组件内部实现细节，必须是 private fun，不能长成对外 API",
            Regex("""private fun LogoFallback\(""").containsMatchIn(stripComments(common.readText())),
        )
    }

    // ---------------- P2-12：remember 不能在 when 分支里 ----------------

    @Test
    fun `StatusChip 的 remember 必须无条件调用 不能藏在 when 分支里`() {
        val body = functionBody(requireCommon(), "fun StatusChip(")
            ?: error("找不到 StatusChip 的函数体（签名变了？），守卫会静默失效")

        val blocks = whenBlocks(body)
        assertTrue(
            "StatusChip 里找不到 `when (status)` 块，守卫会静默失效：\n" +
                "  body = ${body.take(200).replace('\n', ' ')}",
            blocks.isNotEmpty(),
        )
        for (b in blocks) {
            assertFalse(
                "StatusChip 的 `when` 分支里又出现了 remember(...)：\n" +
                    "  when 块 = ${b.take(220).replace('\n', ' ')}\n" +
                    "remember 出现在条件分支里意味着 status 变化时 slot 结构会变；\n" +
                    "现在缓存的是纯派生值（Brush）还只是重算，但把更贵/带状态的东西\n" +
                    "放进来就会变成真缺陷（review/03-ui.md P2-12）。\n" +
                    "三档 Brush 必须在 when 之外**无条件**各算一次。",
                b.contains("remember("),
            )
        }
        // 反向约束：修法不能是"把 remember 删掉"——那会丢掉 P2-12 想保留的缓存
        val cached = Regex("""remember\(""").findAll(body).count()
        assertTrue(
            "StatusChip 的 Brush 缓存被删掉了（现在只剩 $cached 处 remember）：\n" +
                "漏出 when 之外是**搬位置**，不是取消缓存 —— 列表里每行一个 chip，\n" +
                "Brush 每帧新建会让背景节点持续失效。",
            cached >= 2,
        )
    }

    // ---------------- P2-27：回退首字的字号要锁在容器内 ----------------

    @Test
    fun `回退首字字号必须锁在容器内 不随 fontScale 放大`() {
        // 圆形底色是 size（dp，不随字体缩放），字号若是裸 .sp 就会乘 fontScale
        for (size in listOf(24.dp, 40.dp, 56.dp)) {
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                val density = Density(1f, fontScale)
                val actualPx = with(density) { logoFallbackFontSize(size, density).toPx() }
                val containerPx = with(density) { (size / 2.4f).toPx() }
                assertEquals(
                    "size=$size fontScale=$fontScale：首字实际占 ${actualPx}px，圆里只放得下 ${containerPx}px —— " +
                        "系统字体调大后首字会溢出圆形被裁掉（review/03-ui.md P2-27，" +
                        "AndroidManifest 的 configChanges 显式包含 fontScale）",
                    containerPx,
                    actualPx,
                    0.05f,
                )
            }
        }
    }

    @Test
    fun `回退字号必须走锁定的换算函数 而不是裸 sp`() {
        val code = requireCommon()
        val body = functionBody(code, "fun LogoFallback(")
            ?: error("找不到 LogoFallback 的函数体，守卫会静默失效")

        assertTrue(
            "LogoFallback 没有调用锁定的换算函数（裸 .sp 会乘 fontScale）：\n" +
                "  body = ${body.take(240).replace('\n', ' ')}",
            body.contains("logoFallbackFontSize("),
        )
        assertFalse(
            "LogoFallback 里又出现了裸 .sp 字号：\n" +
                "  body = ${body.take(240).replace('\n', ' ')}",
            body.contains(".sp"),
        )

        // 换算函数必须①先算出容器内的 px、②再用库自己的逆换算转回 sp；
        // 自己除 fontScale 再乘 `sp` 的写法在 fontScale≠1 时反推不精确
        //（实测 fontScale=1.3、40dp：算术路线 16.91px vs 容器 16.67px —— 上面那条纯判据抓到过）
        val helper = functionBody(code, "fun logoFallbackFontSize(")
            ?: error("找不到 logoFallbackFontSize 的函数体，守卫会静默失效")
        assertTrue(
            "logoFallbackFontSize 没有「先算容器 px（toPx）再逆换算（toSp）」：\n" +
                "  body = ${helper.take(220).replace('\n', ' ')}",
            helper.contains("toPx(") && helper.contains("toSp("),
        )
    }

    // ---------------- 守卫自身 ----------------

    @Test
    fun `扫描必须真的扫到 ui 源码`() {
        val files = productionFiles()
        assertTrue(
            "扫不到 ui 源码（cwd=${System.getProperty("user.dir")}），守卫会静默全绿",
            files.size >= 10,
        )
        assertTrue("没扫到 components/", files.any { it.parentFile?.name == "components" })
        assertTrue("没扫到 Common.kt", files.any { it.name == "Common.kt" })
    }

    // ---------------- 基础设施 ----------------

    private fun requireCommon(): String {
        // 审计「未验证的怀疑 #7」：按文件名取第一个 → 同名文件时静默守错。现在唯一命中否则 error()。
        val f = GuardSourceScan.uniqueFile(productionFiles(), "Common.kt")
        return stripComments(f.readText())
    }

    private fun productionFiles(): List<File> {
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
     * 取出函数体：从 marker（**必须带左括号**，否则 `fun LiveDot` 会命中
     * `fun LiveDotPhase(`）的括号开始数，直到括号深度归零后遇到的第一个 `{`，
     * 再取平衡花括号块。表达式体函数返回 null。
     */
    private fun functionBody(code: String, marker: String): String? {
        val start = code.indexOf(marker)
        if (start < 0) return null
        var paren = 0
        var i = start + marker.length - 1 // marker 以 '(' 结尾
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '=' -> if (paren == 0) return null
                '{' -> if (paren == 0) return balanced(code, i)
            }
            i++
        }
        return null
    }

    /** 取出所有 `when (...) { ... }` 的花括号体（含嵌套 when）。 */
    private fun whenBlocks(code: String): List<String> {
        val out = mutableListOf<String>()
        var idx = 0
        while (true) {
            val m = Regex("""\bwhen\s*\(""").find(code, idx) ?: break
            var paren = 0
            var i = m.range.last
            var close = -1
            while (i < code.length) {
                when (code[i]) {
                    '(' -> paren++
                    ')' -> {
                        paren--
                        if (paren == 0) {
                            close = i
                            break
                        }
                    }
                }
                i++
            }
            if (close < 0) break
            var j = close + 1
            while (j < code.length && code[j].isWhitespace()) j++
            if (j >= code.length || code[j] != '{') {
                idx = close + 1
                continue
            }
            out += balanced(code, j)
            idx = j + 1
        }
        return out
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
     * ⚠️ 旧版本地实现（只剔注释、保留字符串）是审计「未验证的怀疑 #6」点名的第二套实现之一；
     * 现在全仓只有 [GuardSourceScan.stripNonCode] 一套，避免两份实现语义漂移。
     */
    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)

    private companion object {
        /** 组合期新建的局部 `val x = { ... }`（带不带类型都算） */
        val LOCAL_LAMBDA = Regex("""\bval\s+\w+[^\n]*=\s*\{""")
    }
}