package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「退出小窗 = 回到全屏」的接线守卫（2026-10-30 现场反馈）。
 *
 * 纯判据 [PipExitPolicyTest] 已经钉住"该不该回全屏"，但判据被写出来
 * **没人调用**就是假安全感（review/audit-guards.md B 组的老毛病）。这里钉三件事：
 *
 *  1. `MainActivity` 退出小窗的回调里真的调了判据，并在**退出分支**里发请求
 *     （放在 `if (isInPictureInPictureMode) return` 之前 = 一进小窗就发，仍然是假修复）；
 *  2. `AppEffects.PipExitFullscreenEffect` 真的消费 `PipState.expandRequests`，
 *     且用的是 lifecycle-aware 的 `collectAsStateWithLifecycle`
 *     （用 `collectAsState` 也行，但小窗期间收集不该停 —— 退了以后一定会收到最新值，
 *     计数保证不丢事件；见 PipState 的 KDoc）；
 *  3. 根组件 `NbaLiveApp` 真的调用了这个 effect —— 否则请求写进 StateFlow 没人消费。
 */
class PipExitWiringTest {

    @Test
    fun `退出小窗的回调里要调判据 并在退出分支发请求`() {
        val body = functionBody(code("MainActivity.kt"), "fun onPictureInPictureModeChanged(")
        assertTrue(
            "onPictureInPictureModeChanged 里没有调 PipExitPolicy.shouldExpandToFullscreen(...) —— " +
                "「要不要回全屏」的判据就没人用了。实际函数体：\n$body",
            body.contains("PipExitPolicy.shouldExpandToFullscreen("),
        )
        assertTrue(
            "调了判据却没发请求（PipState.requestExpand()）—— 判据等于摆设。实际函数体：\n$body",
            body.contains("PipState.requestExpand()"),
        )
        val exitGuard = body.indexOf("if (isInPictureInPictureMode) return")
        assertTrue("找不到「进小窗时直接 return」这条前置分支", exitGuard >= 0)
        assertTrue(
            "发请求的位置在「进小窗直接 return」之前 —— 一进小窗就请求全屏，是另一个 bug。\n" +
                "实际函数体：\n$body",
            body.indexOf("PipState.requestExpand()") > exitGuard,
        )
    }

    @Test
    fun `effect 要真的消费请求并执行回到全屏`() {
        val body = functionBody(code("AppEffects.kt"), "fun PipExitFullscreenEffect(")
        assertTrue(
            "没有消费 PipState.expandRequests —— 请求写进去没人读（假接线）。实际函数体：\n$body",
            body.contains("PipState.expandRequests"),
        )
        assertTrue(
            "没有用 collectAsStateWithLifecycle 收集：小窗期间 Activity 是 PAUSED，" +
                "普通 collectAsState 的时序可能与生命周期不同，事件可能被吞。实际函数体：\n$body",
            body.contains("collectAsStateWithLifecycle("),
        )
        assertTrue(
            "收集到请求后没有调用 onExpand（界面不会真的切全屏）。实际函数体：\n$body",
            body.contains("currentOnExpand()") || body.contains("onExpand()"),
        )
    }

    @Test
    fun `根组件要挂载这个 effect`() {
        val body = functionBody(code("NbaLiveApp.kt"), "fun NbaLiveApp(")
        assertTrue(
            "NbaLiveApp 里没有调用 PipExitFullscreenEffect(...)：请求永远没人消费。",
            body.contains("PipExitFullscreenEffect {") || body.contains("PipExitFullscreenEffect("),
        )
    }

    @Test
    fun `计数请求真的能自增`() {
        // 消费端靠"比大小"判断有没有新请求；写入端不自增的话等于没发
        val before = PipState.expandRequests.value
        PipState.requestExpand()
        assertEquals(
            "requestExpand() 没有让计数 +1（消费端的 requests > handled 永远不成立）",
            before + 1,
            PipState.expandRequests.value,
        )
    }

    // ---------- 自检：注释/字符串里的字面量不能算接线 ----------

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {",
            "    // PipExitPolicy.shouldExpandToFullscreen(true, false) 只在注释里",
            "    val s = \"PipState.requestExpand() 只在字符串里\"",
            "    if (isInPictureInPictureMode) return",
            "}",
        ).joinToString("\n")
        assertFalse(
            "判据命中了注释/字符串里的字面量（剔非代码那一步没生效）",
            functionBody(sample, "fun onPictureInPictureModeChanged(")
                .contains("PipState.requestExpand()"),
        )
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        val files = productionCode()
        assertTrue("只扫到 ${files.size} 个生产文件（应 ≥30，cwd=${cwd()}）", files.size >= 30)
        for (name in listOf("MainActivity.kt", "AppEffects.kt", "NbaLiveApp.kt", "PipState.kt")) {
            assertTrue(
                "没扫到 $name —— 上面的守卫会静默全绿",
                files.containsKey(name),
            )
        }
    }

    // ---------------------------------------------------------------

    private fun code(name: String): String = productionCode()[name]
        ?: error("找不到 $name（cwd=${cwd()}）—— 相关守卫会静默全绿，所以直接失败")

    /** 生产源码：文件名 → 已剔除注释与字符串的代码（行号不变）。 */
    private fun productionCode(): Map<String, String> =
        productionRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to GuardSourceScan.stripNonCode(it.readText()) }

    private fun productionRoot(): File =
        listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=${cwd()}）—— 守卫会静默全绿")

    /** 从声明处按花括号配对截出的函数体（先剔注释与字符串）。 */
    private fun functionBody(rawText: String, declaration: String): String {
        val code = GuardSourceScan.stripNonCode(rawText)
        val start = code.indexOf(declaration)
        if (start < 0) error("找不到声明 `$declaration`（守卫会静默失效，所以直接失败）")
        val open = code.indexOf('{', start)
        if (open < 0) error("`$declaration` 后面找不到函数体起始 `{`")
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
        error("`$declaration` 的函数体花括号没有配对")
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath
}