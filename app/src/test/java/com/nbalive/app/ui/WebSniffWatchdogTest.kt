package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网页线路嗅探看门狗（线上「永久黑屏」的第二种形态）。
 *
 * 旧实现的第一句是 `if (pageFinishedAt == 0L) return@LaunchedEffect` —— 判据被
 * `onPageFinished` 门控：页面要是被广告/坏 iframe 卡住永不 finish，看门狗永不触发，
 * 用户看到**永久黑屏且一条日志都没有**。这里既测纯判据，也钉住接线。
 */
class WebSniffWatchdogTest {

    // ------------------------------------------------------------------
    // 一、纯判据（三条路径 + 边界）
    // ------------------------------------------------------------------

    /** 页面**永不** finish：必须从"加载开始"起算硬上限，否则这类页面永远不报。 */
    @Test
    fun `永不 finish 的页面到硬上限必须报`() {
        val start = 1_000L
        assertFalse(
            "还没到硬上限就报，正常慢载线路会被误判",
            WebSniffWatchdog.shouldReport(
                loadStartedAt = start,
                pageFinishedAt = 0L,
                nowMs = start + WebSniffWatchdog.HARD_DEADLINE_MS - 1,
                sawMediaRequest = false,
            ),
        )
        assertTrue(
            "永不 finish 也不报 → 永久黑屏且无声（本次修复要防的正是这一条）",
            WebSniffWatchdog.shouldReport(
                loadStartedAt = start,
                pageFinishedAt = 0L,
                nowMs = start + WebSniffWatchdog.HARD_DEADLINE_MS,
                sawMediaRequest = false,
            ),
        )
    }

    /** 页面已 finish：语义与旧实现一致（finish 之后 25 秒）。 */
    @Test
    fun `已 finish 的页面从 finish 时刻算 25 秒`() {
        val start = 1_000L
        val finished = 20_000L
        assertFalse(
            "finish 后不到 25 秒不该报（旧实现就是这个语义，不许变严）",
            WebSniffWatchdog.shouldReport(start, finished, finished + 24_999L, false),
        )
        assertTrue(
            "finish 后满 25 秒必须报",
            WebSniffWatchdog.shouldReport(start, finished, finished + WebSniffWatchdog.AFTER_FINISH_MS, false),
        )
    }

    /** 已经看到媒体请求 = 播放器在工作：两条时限都不该再报。 */
    @Test
    fun `观察到媒体请求后一律不报`() {
        val start = 1_000L
        assertFalse(
            "永不 finish 那条路径在已看到媒体请求时也不该报",
            WebSniffWatchdog.shouldReport(start, 0L, start + 10 * WebSniffWatchdog.HARD_DEADLINE_MS, true),
        )
        assertFalse(
            "已 finish 那条路径同理",
            WebSniffWatchdog.shouldReport(start, 2_000L, start + 10 * WebSniffWatchdog.HARD_DEADLINE_MS, true),
        )
    }

    /** 硬上限必须**大于**正常路径的"导航 + 25 秒"，否则正常线路会被提前判疑似。 */
    @Test
    fun `硬上限必须大于 finish 后时限`() {
        assertTrue(
            "硬上限 ${WebSniffWatchdog.HARD_DEADLINE_MS} 必须大于 ${WebSniffWatchdog.AFTER_FINISH_MS}：" +
                "源站播放页有多跳重定向，导航时间也在这段窗口里",
            WebSniffWatchdog.HARD_DEADLINE_MS > WebSniffWatchdog.AFTER_FINISH_MS,
        )
    }

    /**
     * 反向锚点：硬上限不许被改成"实际等于永不触发"。
     *
     * 这条判据防的是"有人把 HARD_DEADLINE_MS 调到 `Long.MAX_VALUE` 图省事"——
     * 那样"永不 finish"这一类又回到修复前的耳聋状态，而其他用例照样全绿。
     */
    @Test
    fun `硬上限不许退化成永不触发`() {
        assertTrue(
            "硬上限 ${WebSniffWatchdog.HARD_DEADLINE_MS}ms 太长：卡住的页面黑屏时间会肉眼可见地长",
            WebSniffWatchdog.HARD_DEADLINE_MS <= 120_000L,
        )
    }

    /** 采样间隔必须是有限小值，否则轮询 effect 反应过慢。 */
    @Test
    fun `采样间隔合理`() {
        assertTrue(WebSniffWatchdog.POLL_MS in 100L..5_000L)
    }

    // ------------------------------------------------------------------
    // 二、接线守卫（判据必须在 WebSurface 里真的被消费）
    // ------------------------------------------------------------------

    @Test
    fun `WebSurface 必须消费判据且不许自己门控 onPageFinished`() {
        val text = webSurfaceSource()
        val body = GuardSourceScan.functionBody(text, "fun WebSurface(")

        assertTrue(
            "WebSurface 必须调用 WebSniffWatchdog.shouldReport(...)：判据不接线等于零消费点",
            Regex("""WebSniffWatchdog\s*\.\s*shouldReport\s*\(""").containsMatchIn(body),
        )
        assertFalse(
            "WebSurface 里又出现了 `pageFinishedAt == 0L` 门控 —— 0L 分支只许活在判据里，" +
                "在 Compose 里再写一次就等于把「永不 finish」重新变成耳聋",
            Regex("""pageFinishedAt\s*==\s*0L""").containsMatchIn(body),
        )
        assertTrue(
            "suppressHint 只能抑制界面提示、不能抑制判定与上报（P0-1 的判死分级）",
            Regex("""if\s*\(\s*!suppressHint\s*\)""").containsMatchIn(body),
        )
        // 非空性锚点：函数体确实抓到了（不是空串）
        assertTrue("WebSurface 函数体太小（${body.length} 字符），判据可能锚错了地方", body.length > 500)
    }

    @Test
    fun `判据调用必须把四个输入都传齐`() {
        val text = webSurfaceSource()
        val body = GuardSourceScan.functionBody(text, "fun WebSurface(")
        val args = callArgs(body, "WebSniffWatchdog.shouldReport(")
        for (arg in listOf("loadStartedAt", "pageFinishedAt", "nowMs", "sawMediaRequest")) {
            assertTrue(
                "shouldReport 少传了 `$arg`（判据会退化；实际实参：${args.trim()}）",
                Regex("""\b""" + arg + """\s*=""").containsMatchIn(args),
            )
        }
        // 起点必须是**单调时钟**：`elapsedRealtime` 才不受用户改系统时间影响
        assertTrue(
            "loadStartedAt 必须来自单调时钟（SystemClock.elapsedRealtime），不能用墙钟",
            Regex("""elapsedRealtime\s*\(\s*\)""").containsMatchIn(body),
        )
    }

    // ------------------------------------------------------------------
    // 三、工具
    // ------------------------------------------------------------------

    private fun webSurfaceSource(): String {
        val file = productionFile("com/nbalive/app/ui/screens/PlayerScreen.kt")
        val text = file.readText()
        assertTrue("扫错文件了", text.contains("private fun WebSurface("))
        return text
    }

    /**
     * 取 `marker`（形如 `Foo.bar(`）那一对括号内的完整实参文本（按括号配对，嵌套不截断）。
     *
     * ⚠️ 不能用 `\(([^)]*)\)`：实参里就有嵌套调用（`android.os.SystemClock.elapsedRealtime()`），
     * 那样只能拿到半个调用，参数检查会变成一条**永远不会红**的守卫。
     */
    private fun callArgs(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) error("找不到 $marker —— 依赖它的守卫会静默全绿，所以直接失败")
        var depth = 0
        var i = start + marker.length - 1
        val open = i
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        error("$marker 的括号不配对 —— 守卫会静默全绿")
    }

    private fun productionFile(relative: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        return listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File(cwd, "src/main/java/$relative"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${File(cwd).absolutePath}）—— 守卫会静默全绿，所以直接失败")
    }
}