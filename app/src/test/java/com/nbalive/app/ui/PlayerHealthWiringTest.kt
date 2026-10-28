package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 的**接线**守卫：`PlaybackHealth.showsSuspectHint` 必须真的画到界面上。
 *
 * ## 为什么需要它（review/audit-guards.md B2）
 *
 * `PlaybackHealth.kt` 的 KDoc 与 `review/01-player.md:41` 都写明：25 秒没嗅到流
 * 只算**推断**出来的「疑似异常」，要**用于显示提示**（外加停掉按 Home 自动进小窗）。
 * 生产只实现了后半（`NbaLiveApp` 的 `blocksAutoEnterPip`），前半**零消费者** ——
 * 用户看到的现象就是"嗅探超时后界面毫无提示"，与 `partialError` 同款形态：
 * **算好了、测了、没人用**。
 *
 * 纯判据（`SUSPECT == true` / `OK`、`DEAD == false`）已由
 * `PlaybackHealthTest.只有疑似状态才显示疑似提示` 覆盖，这里**只**管接线，不重复纯判据。
 *
 * ## 三条铁律（与全仓守卫一致）
 *
 * 1. 断言前先 [GuardSourceScan.stripNonCode]（连字符串字面量一起剔，行号不变）；
 * 2. **声明不算使用**：`fun showsSuspectHint(` 这类声明行先排除再计数；
 * 3. 文件找不到 / 分支锚不上 -> `error()` 硬失败，绝不用 `assumeTrue` 静默跳过；
 *    另有非空性锚点兜住"零命中的空扫"。
 */
class PlayerHealthWiringTest {

    @Test
    fun `根组合项必须把疑似等级交给播放页 而不是算完就丢`() {
        val app = code("NbaLiveApp.kt")
        val calls = app.lines()
            .filterNot { it.contains("fun showsSuspectHint(") }
            .count { it.contains("PlaybackHealth.showsSuspectHint(") }
        assertTrue(
            "NbaLiveApp 必须真的**调用** PlaybackHealth.showsSuspectHint（审计 B2）：\n" +
                "算好的疑似等级没有任何 UI 消费者，嗅探超时后界面毫无提示。\n" +
                "排除声明行后实际调用 $calls 处（应为 ≥1）。",
            calls >= 1,
        )
        assertTrue(
            "疑似等级必须**作为实参**传给 PlayerScreen（形如 `suspectHint = PlaybackHealth.showsSuspectHint(...)`）：\n" +
                "只算出来不往下传，等于没接进界面。",
            Regex("""suspectHint\s*=\s*PlaybackHealth\.showsSuspectHint\(""").containsMatchIn(app),
        )
        // 反向断言：判据只许有一个来源，UI 里不许另写 `playbackLevel == …SUSPECT`。
        assertTrue(
            "UI 里不许再写一份 `playbackLevel == PlaybackHealth.Level.SUSPECT` 的重复判据 —— " +
                "两份判据时改一处不影响另一处（PlaybackHealth 是唯一来源）。",
            !Regex("""playbackLevel\s*==\s*PlaybackHealth\.Level\.SUSPECT""").containsMatchIn(app),
        )
    }

    @Test
    fun `播放页必须既接收疑似提示 又有它的渲染分支`() {
        val player = code("PlayerScreen.kt")
        assertTrue(
            "PlayerScreen 必须新增 `suspectHint: Boolean` 形参（可选参数区，默认 false）：\n" +
                "推断出来的疑似无信号只用于提示，绝不收窗/关任务（P0-1）。",
            Regex("""suspectHint\s*:\s*Boolean""").containsMatchIn(player),
        )
        // 只出现形参不算接线：必须有**渲染分支**，且条件含 `errorMessage == null`
        // （有确认错误时错误卡优先，不叠加）。
        val branch = branchBody(player, Regex("""if\s*\(\s*suspectHint\s*&&\s*errorMessage\s*==\s*null\s*\)"""))
        assertTrue(
            "播放页里没有 `if (suspectHint && errorMessage == null) { … }` 的渲染分支：\n" +
                "只有形参、没有渲染 = 又一条「声明即满足」的假安全感。\n" +
                "条件必须带上 `errorMessage == null`（确认错误优先，不叠加两层提示）。",
            branch != null,
        )
        assertTrue(
            "疑似提示卡必须真的画出内容（分支体内要有 Text( 调用），不能是个空块：\n" +
                "实际分支体：\n$branch",
            branch != null && branch.contains("Text("),
        )
    }

    @Test
    fun `扫描必须真的扫到这两个文件`() {
        // 非空性锚点：cwd 一变，上面两条会"零命中"地一起变绿，而「全绿」看不出区别。
        for (name in listOf("NbaLiveApp.kt", "PlayerScreen.kt")) {
            val lines = source(name).lines().size
            assertTrue(
                "$name 只有 $lines 行（应 >100）—— 扫描范围走偏时上面几条会静默全绿",
                lines > 100,
            )
        }
    }

    // ---------------------------------------------------------------

    /**
     * 从**已剔注释与字符串**的 [code] 里找出 [marker] 命中的那个 `if (…) { … }` 块体。
     *
     * 返回 null = 没有这个分支（调用方的断言会给出具体失败原因）。
     */
    private fun branchBody(code: String, marker: Regex): String? {
        val hit = marker.find(code) ?: return null
        val open = code.indexOf('{', hit.range.last + 1)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** 生产源码原文（保留注释与字符串，用于行数锚点）。 */
    private fun source(name: String): String {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码根（cwd=${cwd()}）—— 本文件所有守卫会静默全绿")
        return GuardSourceScan.uniqueFile(
            root.walkTopDown().filter { it.isFile }.toList(),
            name,
        ).readText()
    }

    /** 生产源码：已剔除注释与字符串字面量（行号不变）。 */
    private fun code(name: String): String = GuardSourceScan.stripNonCode(source(name))

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath
}