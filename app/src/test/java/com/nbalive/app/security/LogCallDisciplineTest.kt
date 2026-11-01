package com.nbalive.app.security

import com.nbalive.app.TestPaths
import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志纪律：**`ui/` 与 `MainActivity.kt` 里不许直接调 `Log.`**
 * （review/optimization-review.md **U20** 的加固版）。
 *
 * ## 事故形态
 *
 * `LoggingDisciplineTest` 拦的是「把 `Throwable` 直接交给 `Log`」（`Log.w(tag, msg, t)`）——
 * 那会打完整堆栈，而异常 message 里常常内嵌带 `auth_key` 的完整 URL，
 * 等于把 `HttpClient` 的 `redactUrl` 防线整体拆掉（review/02-P1-5、04-P1-1）。
 *
 * 但那条守卫**只认 `, t` 这一种形态**。于是 §U20 点出的那种情况能长期存活：
 * `MainViewModel` 里一处纯字面量的裸 `Log.w` —— 它本身没有泄露风险，
 * 但它示范了"这里可以直接调 Log"的写法：下一个人照着它把带签名的 URL 拼进第二参数，
 * `LoggingDisciplineTest` 依然不会红。
 *
 * ## 判据
 *
 * 纪律改成"**这条链路一律走 `Logs.kt` 的出口**"：
 * `ui/` 目录与 `MainActivity.kt` 里出现任何 `Log.` 调用即违规
 * （`safeLogWarn` / `debugLog` 都在 `data/remote/Logs.kt`，那里是唯一允许出现 `Log.` 的地方）。
 *
 * ⚠️ 写 KDoc 时**不要**在正文里写出通配符 `ui/<星><星>` 或块注释结束符：
 * Kotlin 允许块注释嵌套，正文里的那两个字符会真的开一层嵌套注释、
 * 把后面所有代码吞掉（本轮实测报 "Unclosed comment"）。
 */
class LogCallDisciplineTest {

    @Test
    fun `ui 与 MainActivity 不得直接调用 android 的 Log`() {
        val files = uiAndActivitySources()
        assertTrue(
            "只扫到 ${files.size} 个文件（应 ≥30）—— 扫描范围走偏时下面那条「零违规」" +
                "会静默全绿，所以直接失败而不是跳过",
            files.size >= 30,
        )
        val violations = files.filter { file ->
            BARE_LOG_CALL.containsMatchIn(GuardSourceScan.stripNonCode(file.readText()))
        }
        assertTrue(
            "以下文件直接调用了 `Log.`（review/optimization-review.md U20）：\n" +
                violations.joinToString("\n") { "  ${it.name}" } +
                "\n请改走 `data/remote/Logs.kt` 的出口：有异常对象用 " +
                "`safeLogWarn(tag, msg, t)`（会自动脱敏 message），无异常对象用 " +
                "`safeLogWarn(tag, msg)`（只接受纯字面量文案）；调试日志用 `debugLog(...)`。\n" +
                "理由：`LoggingDisciplineTest` 只拦 `Log.w(tag, msg, t)` 那一种形态，" +
                "裸调用会示范一种「以后可以随便往第二参数拼 URL」的写法。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `判据自检 能抓到裸调用且不误报注释与安全出口`() {
        val sample = listOf(
            "// Log.w(\"t\", \"注释里不算\")",
            "val s = \"文案里的 Log.d 不算\"",
            "Log.w(\"TAG\", \"违规\")",
        ).joinToString("\n")
        assertTrue(
            "扫描器抓不到裸 `Log.` 调用（它已退化成摆设）",
            BARE_LOG_CALL.containsMatchIn(GuardSourceScan.stripNonCode(sample)),
        )
        assertFalse(
            "扫描器误报了注释/字符串里的 `Log.`（剔非代码那一步没生效）",
            BARE_LOG_CALL.containsMatchIn(
                GuardSourceScan.stripNonCode("// Log.w(1)\nval s = \"Log.d(x)\""),
            ),
        )
        // 出口函数名里含 "Log" 但不是 `Log.` 调用，不能误报。
        assertFalse(
            "把 safeLogWarn/debugLog 这类出口也当成了裸调用",
            BARE_LOG_CALL.containsMatchIn(
                GuardSourceScan.stripNonCode("safeLogWarn(\"t\", \"m\")\ndebugLog(\"t\", \"m\")"),
            ),
        )
        // 行首裸调用（没有前置字符）也必须被抓到。
        assertTrue(
            "行首的裸调用漏网（前置字符分支没覆盖行首）",
            BARE_LOG_CALL.containsMatchIn(GuardSourceScan.stripNonCode("Log.e(\"T\", \"m\")")),
        )
    }

    /**
     * 主源码树里位于 `ui/` 目录下的文件，加上 `MainActivity.kt`。
     *
     * 找不到主源码根就 `error()`（守卫静默全绿比误报危险）。
     */
    private fun uiAndActivitySources(): List<File> {
        val cwd = File(TestPaths.workingDirPath())
        val root = listOf(
            File("src/main/java"),
            File(cwd, "src/main/java"),
            File(cwd, "../app/src/main/java"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到主源码根 app/src/main/java（cwd=${cwd.absolutePath}）—— 守卫会静默全绿，所以直接失败")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val path = file.absolutePath.replace('\\', '/')
                path.contains("/ui/") || file.name == "MainActivity.kt"
            }
            .sortedBy { it.path }
            .toList()
    }

    private companion object {
        /**
         * 裸 `Log.` 调用：行首（`^`）或前面不是标识符/点号。
         *
         * ⚠️ 用**普通字符串**而不是原样字符串：正则以 `\.` 结尾时，
         * 原样字符串的结束定界符紧跟反斜杠会让词法器误判（本轮实测报 "Unclosed comment"）。
         */
        val BARE_LOG_CALL = Regex("(^|[^\\w.])Log\\s*\\.")
    }
}