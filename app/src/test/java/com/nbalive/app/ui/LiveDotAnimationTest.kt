package com.nbalive.app.ui

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
 */
class LiveDotAnimationTest {

    @Test
    fun `LiveDot 不能自己创建无限动画`() {
        val code = productionCode("Common.kt")
        assertTrue("找不到 Common.kt", code != null)
        val stripped = stripComments(code!!)

        // ⚠️ marker 必须带左括号 "fun LiveDot("：
        // 用 "fun LiveDot" 会同时命中 "fun LiveDotPhase("，
        // 于是这条会把 LiveDotPhase 里的 rememberInfiniteTransition
        // 也算进 LiveDot 的体里，永远红（写的时候踩到了）。
        val body = extractFunctionBody(stripped, "fun LiveDot(")
        assertTrue("找不到 LiveDot 函数体", body != null)
        assertTrue(
            "LiveDot 不能内部自建 rememberInfiniteTransition：\n" +
                "一屏十几场直播就是十几份独立无限动画、永不停止，是列表页的持续耗电来源（03-P2-13）。\n" +
                "应该由共享来源（LiveDotPhase 提供的 CompositionLocal）驱动。",
            !body!!.contains("rememberInfiniteTransition"),
        )
    }

    @Test
    fun `LiveDot 必须接受外部传入的透明度`() {
        // 共享动画的实现方式：由调用方/CompositionLocal 提供 alpha
        val code = productionCode("Common.kt")
        assertTrue("找不到 Common.kt", code != null)
        val body = extractFunctionBody(stripComments(code!!), "fun LiveDot(")
        assertTrue("找不到 LiveDot 函数体", body != null)
        assertTrue(
            "LiveDot 必须接受外部传入的 alpha（共享动画的前提）",
            body!!.contains("alpha"),
        )
    }

    @Test
    fun `共享动画驱动必须存在`() {
        // 动画相位被提到哪里都行，但必须有一个共享来源
        val files = productionFiles()
        assertTrue("找不到生产源码", files.isNotEmpty())
        val hasShared = files.any { f ->
            val t = stripComments(f.readText())
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
        assertTrue("找不到生产源码", files.isNotEmpty())
        val callers = files.filter { f ->
            f.name != "Common.kt" &&
                Regex("""LiveDotPhase\s*\{""").containsMatchIn(stripComments(f.readText()))
        }
        assertTrue(
            "必须有一个列表/页面层用 LiveDotPhase { } 包住内容，否则共享相位没生效",
            callers.isNotEmpty(),
        )
    }

    /**
     * 取出某个函数的函数体（从 marker 到下一个顶层 `fun ` 之间）。
     */
    private fun extractFunctionBody(text: String, marker: String): String? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        val next = text.indexOf("\nfun ", start + marker.length)
        return text.substring(start, if (next < 0) text.length else next)
    }

    private fun productionCode(name: String): String? = productionFiles()
        .firstOrNull { it.name == name }?.readText()

    private fun productionFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory } ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")
}