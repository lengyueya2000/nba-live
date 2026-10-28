package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 呼吸红点的动画必须是**共享**的（review/03-ui.md P2-13）。
 *
 * ## 要解决什么
 *
 * `LiveDot` 内部各自 `rememberInfiniteTransition`。列表页一屏可能有十几场
 * 正在直播的比赛，每颗红点一份独立无限动画 —— 十几份动画同时逐帧运行，
 * 且永不停止（只要还有 LIVE 场次）。这是列表页持续耗电的来源之一。
 *
 * ## 正确做法
 *
 * 动画相位由**一个**共享来源驱动（`LiveDotPhase` 提供 CompositionLocal），
 * 所有红点复用同一个 alpha 值。
 *
 * ## 为什么扫源码
 *
 * `rememberInfiniteTransition` 的行为在 JVM 单测里跑不到（需要 Compose 运行时）。
 * 源码扫描是此处唯一能自动化的守门方式。
 *
 * ## ⚠️ 本文件自己的一条"永远不会红的守卫"（2026-10-28 只读审计 A8）
 *
 * `LiveDot 必须接受外部传入的透明度` 原来断言 `body!!.contains("alpha")` ——
 * 而**形参声明** `fun LiveDot(size: Dp = 6.dp, alpha: Float = LocalLiveDotAlpha.current)`
 * 本身就在函数体提取结果里，于是"函数体根本不用它（红点不呼吸）"也永远绿。
 * 生产真正的消费点是 `Common.kt` 的 `.graphicsLayer { this.alpha = alpha }`。
 *
 * 现在：判据要求 alpha **在函数体里被消费**（调用形状），
 * 剔注释与字符串字面量、函数体缺失直接 [error]，并有一条喂内存样本的自检。
 */
class LiveDotAnimationTest {

    @Test
    fun `LiveDot 不能自己创建无限动画`() {
        val code = GuardSourceScan.stripNonCode(code("Common.kt"))
        val body = functionBody(code, "fun LiveDot(")
        // 非空性锚点：表达式体函数会让"函数体"为空，后面的 `!contains` 就恒真了
        assertTrue("LiveDot 的函数体为空（改成表达式体了？）—— 这条守卫会静默全绿", body.isNotBlank())
        assertTrue(
            "LiveDot 不能内部自建 rememberInfiniteTransition：\n" +
                "一屏十几场直播就是十几份独立无限动画、永不停止，是列表页的持续耗电来源（03-P2-13）。\n" +
                "应该由共享来源（LiveDotPhase 提供的 CompositionLocal）驱动。",
            !body.contains("rememberInfiniteTransition"),
        )
    }

    @Test
    fun `LiveDot 必须接受外部传入的透明度 且在实现体里被消费`() {
        // ⚠️ 审计 A8：原来断言 `body!!.contains("alpha")`，被**形参声明**满足 ——
        // 函数体哪怕完全不用它（红点不呼吸）也绿。
        // 现在判据要求 alpha 在函数体里被**消费**（`.alpha = alpha` 调用形状）。
        val violations = alphaConsumptionViolations(code("Common.kt"))
        assertTrue(
            "「LiveDot 接受外部透明度」守卫不合格（review/audit-guards.md A8）：\n" +
                violations.joinToString("\n").prependIndent("  "),
            violations.isEmpty(),
        )
    }

    @Test
    fun `透明度判据自检 声明了却不消费必须报违规`() {
        // 防"永远全绿的摆设"：内存样本喂给**参与生产判定**的那个函数。
        val compliant = """
            @Composable
            fun LiveDot(size: Dp = 6.dp, alpha: Float = LocalLiveDotAlpha.current) {
                Box(
                    Modifier
                        .size(size)
                        .graphicsLayer { this.alpha = alpha }
                )
            }
        """.trimIndent()
        assertTrue(
            "判据误报了合规写法：\n" + alphaConsumptionViolations(compliant).joinToString("\n"),
            alphaConsumptionViolations(compliant).isEmpty(),
        )

        // 违规样本 1：形参声明了，函数体根本不用（旧判据在这一版上照样绿）
        val declaredOnly = """
            @Composable
            fun LiveDot(size: Dp = 6.dp, alpha: Float = 1f) {
                Box(Modifier.size(size))
            }
        """.trimIndent()
        assertTrue(
            "「声明了 alpha 但函数体不消费」必须报违规（这正是审计 A8 的原始形态）",
            alphaConsumptionViolations(declaredOnly).isNotEmpty(),
        )

        // 违规样本 2：只在注释里"消费"
        val commentOnly = """
            @Composable
            fun LiveDot(size: Dp = 6.dp, alpha: Float = LocalLiveDotAlpha.current) {
                // .graphicsLayer { this.alpha = alpha }
                Box(Modifier.size(size))
            }
        """.trimIndent()
        assertTrue(
            "注释里的消费不算消费（剔注释后必须报违规）",
            alphaConsumptionViolations(commentOnly).isNotEmpty(),
        )

        // 违规样本 3：又自建无限动画
        val ownTransition = """
            @Composable
            fun LiveDot(size: Dp = 6.dp, alpha: Float = LocalLiveDotAlpha.current) {
                val t = rememberInfiniteTransition()
                Box(Modifier.size(size).graphicsLayer { this.alpha = alpha })
            }
        """.trimIndent()
        assertTrue(
            "函数体内自建无限动画必须报违规",
            alphaConsumptionViolations(ownTransition).isNotEmpty(),
        )
    }

    @Test
    fun `共享动画驱动必须存在`() {
        // 动画相位被提到哪里都行，但必须有一个共享来源
        val files = productionFiles()
        val hasShared = files.any { f ->
            val t = GuardSourceScan.stripNonCode(f.readText())
            t.contains("LocalLiveDotAlpha") && t.contains("fun LiveDotPhase")
        }
        assertTrue(
            "必须存在共享的动画相位来源（LocalLiveDotAlpha + fun LiveDotPhase）：\n" +
                "否则红点只能各自建无限动画",
            hasShared,
        )
    }

    @Test
    fun `列表页必须用 LiveDotPhase 包住`() {
        // 光有共享机制不用也白搭 —— 这正是审查点名的"写了但没人用"
        val files = productionFiles()
        val callers = files.filter { f ->
            f.name != "Common.kt" &&
                Regex("""LiveDotPhase\s*\{""").containsMatchIn(GuardSourceScan.stripNonCode(f.readText()))
        }
        assertTrue(
            "必须有一个列表/页面层用 LiveDotPhase { } 包住内容，否则共享相位没生效",
            callers.isNotEmpty(),
        )
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        // 非空性锚点：上面几条里有"零违规/any"式断言，扫描范围走偏时会静默全绿
        val files = productionFiles()
        assertTrue(
            "只扫到 ${files.size} 个生产 kt 文件（应 ≥30，cwd=${System.getProperty("user.dir")}）" +
                "—— 上面的守卫会静默全绿",
            files.size >= 30,
        )
        assertTrue(
            "没扫到 Common.kt —— LiveDot 判据的唯一锚点，守卫会静默全绿",
            files.any { it.name == "Common.kt" },
        )
    }

    // ---------------------------------------------------------------
    // 判据
    // ---------------------------------------------------------------

    /**
     * 判据：`LiveDot` 的 `alpha` 必须被**函数体**消费（返回违规说明，空 = 达标）。
     *
     * 输入是原始源码文本；内部先剔注释与字符串字面量（铁律①），
     * 判据匹配调用形状（铁律②）；函数体缺失直接 [error]（铁律③）。
     */
    private fun alphaConsumptionViolations(rawCode: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawCode)
        val (start, open) = functionSpan(code, "fun LiveDot(")
        val signature = code.substring(start, open)
        val body = balanced(code, open)
        val out = mutableListOf<String>()

        // ① 形参存在（共享相位进得来）
        if (!Regex("""\balpha\s*:\s*Float""").containsMatchIn(signature)) {
            out += "① LiveDot 没有 `alpha: Float` 形参（默认读 LocalLiveDotAlpha）"
        }
        // ② 关键：**在函数体里被消费**，而不是只在形参声明里出现
        if (!Regex("""\.alpha\s*=\s*alpha\b""").containsMatchIn(body)) {
            out += "② alpha 形参在函数体里没有被消费（旧判据 body.contains(\"alpha\") " +
                "被形参声明本身满足，红点不呼吸也照样绿）"
        }
        // ③ 走 graphicsLayer 的绘制阶段（P2-13：不触发重组）
        if (!Regex("""graphicsLayer\s*\{""").containsMatchIn(body)) {
            out += "③ 透明度没有走 graphicsLayer 的绘制阶段"
        }
        // ④ 不能自己建无限动画（否则共享相位白做）
        if (body.contains("rememberInfiniteTransition")) {
            out += "④ LiveDot 内部又自建 rememberInfiniteTransition（每颗红点一份永不停止的动画）"
        }
        return out
    }

    /** `marker` 的起点与函数体左花括号下标；找不到/非花括号体直接 [error]。 */
    private fun functionSpan(code: String, marker: String): Pair<Int, Int> {
        val start = code.indexOf(marker)
        if (start < 0) error("源码里找不到 $marker —— 守卫会静默全绿")
        var i = start + marker.length - 1
        var paren = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                // 参数表之外出现 `=` 说明是表达式体函数，没有花括号体
                '=' -> if (paren == 0) {
                    error("$marker 是表达式体函数：判据需要花括号体，否则会静默全绿")
                }
                '{' -> if (paren == 0) return start to i
            }
            i++
        }
        error("$marker 找不到函数体 —— 守卫会静默全绿")
    }

    /** 函数体（不含外层花括号）。 */
    private fun functionBody(code: String, marker: String): String {
        val (_, open) = functionSpan(code, marker)
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

    /** 唯一的 `name` 源码原文；找不到直接 [error]，绝不 return emptyList() 静默全绿。 */
    private fun code(name: String): String {
        val hits = productionFiles().filter { it.name == name }
        if (hits.size != 1) {
            error(
                "$name 在 ${productionFiles().size} 个生产文件里命中 ${hits.size} 次" +
                    "（${hits.map { it.path }}，cwd=${System.getProperty("user.dir")}）—— " +
                    "守卫会静默全绿或守错文件",
            )
        }
        return hits.single().readText()
    }

    private fun productionFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录，cwd=$cwd，候选=${roots.map { it.path }} —— 守卫会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}