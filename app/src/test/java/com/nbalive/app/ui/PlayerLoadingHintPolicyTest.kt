package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在连接源站播放页…」提示的判据与接线（2026-10-30 感知优化）。
 *
 * 网页线路加载期间界面是纯黑（实测 0.1~27 秒），加一句非阻塞提示降低"卡死"观感。
 * 判据必须同时管住"该显示"和"不该显示"——只在加载正常期显示，出流/报错/小窗/超时都要收掉。
 */
class PlayerLoadingHintPolicyTest {

    private fun show(
        hasWebFallback: Boolean = true,
        hasStream: Boolean = false,
        sinceLoadMs: Long = 1_000,
        hasErrorMessage: Boolean = false,
        inPip: Boolean = false,
    ) = PlayerLoadingHintPolicy.shouldShow(
        hasWebFallback = hasWebFallback,
        hasStream = hasStream,
        sinceLoadMs = sinceLoadMs,
        hasErrorMessage = hasErrorMessage,
        inPip = inPip,
    )

    @Test
    fun `网页兜底加载中要显示提示`() {
        assertTrue(show())
    }

    @Test
    fun `已经有流就不显示`() {
        assertFalse(show(hasStream = true))
    }

    @Test
    fun `没有网页兜底不显示`() {
        assertFalse(show(hasWebFallback = false))
    }

    @Test
    fun `已经报错时不显示 避免与错误提示叠加`() {
        assertFalse(show(hasErrorMessage = true))
    }

    @Test
    fun `小窗里不显示 别挡住画面`() {
        assertFalse(show(inPip = true))
    }

    @Test
    fun `超过 20 秒就收掉 不能像永远在连接`() {
        assertTrue("刚好 20 秒前还该显示", show(sinceLoadMs = PlayerLoadingHintPolicy.MAX_HINT_MS - 1))
        assertFalse("到 20 秒必须收掉", show(sinceLoadMs = PlayerLoadingHintPolicy.MAX_HINT_MS))
    }

    @Test
    fun `计时不能为负`() {
        assertFalse(show(sinceLoadMs = -1))
    }

    /**
     * 接线守卫：判据必须真的被 PlayerScreen 调用。
     *
     * 只写一个恒真判据没人消费 = 假安全感（本仓库的老问题）。
     */
    @Test
    fun `PlayerScreen 必须消费提示判据`() {
        val code = productionCode()["PlayerScreen.kt"]
            ?: error("找不到 PlayerScreen.kt —— 这条守卫会静默全绿，所以直接失败")
        val body = GuardSourceScan.functionBody(code, "fun PlayerScreen(")
        assertTrue(
            "PlayerScreen 里没有调用 PlayerLoadingHintPolicy.shouldShow(...)：\n" +
                "提示判据写了没人用。实际函数体片段：\n" +
                body.take(400),
            body.contains("PlayerLoadingHintPolicy.shouldShow("),
        )
    }

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "class Sample {",
            "    fun PlayerScreen() {",
            "        // PlayerLoadingHintPolicy.shouldShow( 只在注释里",
            "        val s = \"PlayerLoadingHintPolicy.shouldShow( 只在字符串里\"",
            "    }",
            "}",
        ).joinToString("\n")
        assertFalse(
            "判据命中了注释/字符串里的字面量（剔非代码那一步没生效）",
            GuardSourceScan.functionBody(sample, "fun PlayerScreen(")
                .contains("PlayerLoadingHintPolicy.shouldShow("),
        )
    }

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
            ?: error("找不到生产源码目录（cwd=${File(System.getProperty("user.dir"))}）—— 守卫会静默全绿")
}