package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「直连失败退回网页后」的恢复判据 + 接线守卫（2026-10-30「解析播放太慢」）。
 *
 * 纯判据 [SniffRecoveryPolicyTest] 钉住"什么时候该判死换线"；
 * [SniffRecoveryWiringTest] 钉住"判据真的被生产调用，且计时真的从失败点开始"，
 * 否则判据写出来没人用，就是本仓库踩过的假安全感。
 */
class SniffRecoveryPolicyTest {

    @Test
    fun `宽限期没到不换线`() {
        assertFalse(
            SniffRecoveryPolicy.shouldAdvance(
                elapsedSinceRevertMs = SniffRecoveryPolicy.GRACE_MS - 1,
                hasStream = false,
                stillOnFailedPage = true,
                resolving = false,
            ),
        )
    }

    @Test
    fun `宽限期到点且仍无流 就该换线`() {
        assertTrue(
            SniffRecoveryPolicy.shouldAdvance(
                elapsedSinceRevertMs = SniffRecoveryPolicy.GRACE_MS,
                hasStream = false,
                stillOnFailedPage = true,
                resolving = false,
            ),
        )
    }

    @Test
    fun `已经恢复出流就不换线`() {
        assertFalse(
            SniffRecoveryPolicy.shouldAdvance(
                elapsedSinceRevertMs = SniffRecoveryPolicy.GRACE_MS * 2,
                hasStream = true,
                stillOnFailedPage = true,
                resolving = false,
            ),
        )
    }

    @Test
    fun `用户已经切走这条线就不换线`() {
        assertFalse(
            SniffRecoveryPolicy.shouldAdvance(
                elapsedSinceRevertMs = SniffRecoveryPolicy.GRACE_MS * 2,
                hasStream = false,
                stillOnFailedPage = false,
                resolving = false,
            ),
        )
    }

    @Test
    fun `正在解析时不叠加换线`() {
        assertFalse(
            SniffRecoveryPolicy.shouldAdvance(
                elapsedSinceRevertMs = SniffRecoveryPolicy.GRACE_MS * 2,
                hasStream = false,
                stillOnFailedPage = true,
                resolving = true,
            ),
        )
    }

    @Test
    fun `宽限期是 8 秒`() {
        // 取值依据见 KDoc：正常页面 2~3 秒内出流，8 秒留了 2~3 倍余量；
        // 一旦调大（回到"永久等待"的方向）必须有人重新审视这个决定。
        assertEquals(8_000L, SniffRecoveryPolicy.GRACE_MS)
    }

    @Test
    fun `换线提示要和嗅探超时区分开`() {
        val note = SniffRecoveryPolicy.advanceNote()
        assertTrue("提示不能为空（用户要知道发生了什么）", note.isNotBlank())
        assertTrue(
            "提示必须说明是「直连失败」而不是笼统的「无信号」（当前=$note）",
            note.contains("直连失败"),
        )
    }
}

/**
 * 「恢复计时」的接线守卫。
 *
 * 判据再正确，`onPlaybackFailed` 里不启动它、或计时到点不复查状态，都等于没修。
 */
class SniffRecoveryWiringTest {

    @Test
    fun `直连失败退回网页时必须启动恢复计时`() {
        val body = functionBody(code("MainViewModel.kt"), "fun onPlaybackFailed(")
        assertTrue(
            "onPlaybackFailed 的嗅探失败分支没有调 startSniffRecovery(...)：退回网页后就永久卡住了。\n" +
                "实际函数体：\n$body",
            body.contains("startSniffRecovery("),
        )
    }

    @Test
    fun `计时到点必须用判据复查状态而不是无条件换线`() {
        val body = functionBody(code("MainViewModel.kt"), "fun startSniffRecovery(")
        assertTrue(
            "计时到点没有调 SniffRecoveryPolicy.shouldAdvance(...)：会变成「到点必换线」，\n" +
                "页面已经恢复出流时也会被踢掉。实际函数体：\n$body",
            body.contains("SniffRecoveryPolicy.shouldAdvance("),
        )
        assertTrue(
            "计时到点没有读最新状态（_playerState.value）：判据输入可能还是旧的。\n" +
                "实际函数体：\n$body",
            body.contains("_playerState.value"),
        )
        assertTrue(
            "到点换线没有复用 advanceAfterNoSignal(...)：两条路径的换线规则会漂移。\n" +
                "实际函数体：\n$body",
            body.contains("advanceAfterNoSignal("),
        )
        assertTrue(
            "换线原因没有走 SniffRecoveryPolicy.advanceNote()：文案会与嗅探超时混淆。\n" +
                "实际函数体：\n$body",
            body.contains("SniffRecoveryPolicy.advanceNote("),
        )
    }

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "class Sample {",
            "    fun onPlaybackFailed() {",
            "        // startSniffRecovery(pageUrl) 只在注释里",
            "        val s = \"startSniffRecovery(pageUrl) 只在字符串里\"",
            "    }",
            "}",
        ).joinToString("\n")
        assertFalse(
            "判据命中了注释/字符串里的字面量（剔非代码那一步没生效）",
            functionBody(sample, "fun onPlaybackFailed(").contains("startSniffRecovery("),
        )
    }

    // ---------------------------------------------------------------

    private fun code(name: String): String = productionCode()[name]
        ?: error("找不到 $name（cwd=${cwd()}）—— 相关守卫会静默全绿，所以直接失败")

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