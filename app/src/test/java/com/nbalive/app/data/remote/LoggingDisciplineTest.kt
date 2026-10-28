package com.nbalive.app.data.remote

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏的**调用点**守卫（review/00-summary「测试的假安全感」第 4 条）。
 *
 * [RedactUrlTest] 只证明 `redactUrl()` 这个工具函数本身正确，
 * 但防线是否生效取决于**每个调用点有没有用它**。
 * 之前那 6 处违规（把 throwable 直接交给 `Log.w`、把源站原始 href 拼进日志）
 * 正是因为这个缺口才长期存在：函数有测试，纪律没有。
 *
 * 这个测试不测逻辑，扫源码把两条纪律钉死：
 * 1. 不许把 `Throwable` 透传给 `Log.w/e` —— 那样会打印完整堆栈，
 *    而每一帧的异常 message 都是原文，`HttpClient` 的消息内嵌带 `auth_key` 的完整 URL；
 * 2. 不许把源站 URL / href 变量**未经脱敏**插值进日志字符串。
 *
 * ⚠️ 为什么用源码扫描而不是运行时断言：
 * release 包里 `Log` 是 Android stub，JVM 单测拿不到真实 logcat，
 * 断言"某次调用打了什么"在这里做不到。扫源码是此处唯一能自动化、
 * 且在引入违规的那一轮就变红的守门方式。它替代不了真机抽查，
 * 但能挡住绝大多数无心之失。
 */
class LoggingDisciplineTest {

    /** 定位 `app/src/main/java/com/nbalive/app`；单测工作目录是模块目录。 */
    private fun sourceRoot(): File? {
        val candidates = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    private fun ktFiles(): List<File> =
        sourceRoot()?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList() ?: emptyList()

    /** 取出代码行（跳过注释），避免把 doc 里"为什么不能这么写"的反例判成违规。 */
    private fun codeLines(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            ) {
                null
            } else {
                index + 1 to trimmed
            }
        }

    @Test
    fun `没有把 Throwable 透传给 Log 的调用点`() {
        val files = ktFiles()
        // ⚠️ 硬失败而不是跳过（审计 A10）：源码根/扫描结果为空时下面这条"零违规"判断
        // 会静默全绿 —— 脱敏纪律会整个消失，而报告里看不出来，所以直接失败。
        assertTrue(
            "扫描不到 app/src/main/java 的 Kotlin 源码（实际 ${files.size} 个文件，cwd=" +
                "${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时脱敏纪律守卫会静默全绿，所以直接失败而不是跳过",
            files.size >= 20,
        )
        val violations = files.flatMap { file ->
            codeLines(file).filter { (_, text) -> LOG_WITH_THROWABLE.containsMatchIn(text) }
                .map { (line, text) -> "${file.name}:$line  $text" }
        }
        assertTrue(
            "把 Throwable 交给 Log 会打印堆栈，帧里的 message 可能内嵌带签名的源站 URL。\n" +
                "请改用 safeLogWarn(tag, message, throwable)，它只打类型 + 脱敏后的 message。\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `日志里的 URL 与 href 都经过脱敏`() {
        val files = ktFiles()
        // ⚠️ 硬失败而不是跳过（审计 A10）：见上一条用例的说明。
        assertTrue(
            "扫描不到 app/src/main/java 的 Kotlin 源码（实际 ${files.size} 个文件，cwd=" +
                "${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时脱敏纪律守卫会静默全绿，所以直接失败而不是跳过",
            files.size >= 20,
        )
        val violations = files.flatMap { file ->
            codeLines(file)
                .filter { (_, text) -> text.startsWith("Log.") }
                .mapNotNull { (line, text) ->
                    val hit = RAW_URL_INTERP.find(text) ?: return@mapNotNull null
                    "${file.name}:$line  $text  命中 ${hit.value}"
                }
        }
        assertTrue(
            "日志直接插值 URL/href 变量会把 auth_key 之类的签名写进 logcat。\n" +
                "请套 redactUrl(整段 URL) 或 redactText(混合文本)。\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `守卫确实有效能抓到违规写法不误报合规写法`() {
        // 这个测试防止上面两个源码扫描退化成"永远全绿的摆设"：
        // 用内存样本确认两条规则各自真的能抓到那一类违规。
        val badPassthrough = "Log.w(\"MainViewModel\", \"refresh failed: x\", t)"
        val badInterp = "Log.w(TAG, \"JRS 列表项缺队名，跳过：\$path\")"
        val goodPassthrough = "safeLogWarn(\"MainViewModel\", \"refresh failed\", t)"
        val goodInterp = "Log.w(TAG, \"JRS 列表项缺队名，跳过：\${redactText(path)}\")"

        assertTrue("必须能抓到透传 throwable", LOG_WITH_THROWABLE.containsMatchIn(badPassthrough))
        assertTrue("必须能抓到未脱敏 href", RAW_URL_INTERP.containsMatchIn(badInterp))
        assertTrue("不能误报 safeLogWarn", !LOG_WITH_THROWABLE.containsMatchIn(goodPassthrough))
        assertTrue("不能误报已脱敏的写法", !RAW_URL_INTERP.containsMatchIn(goodInterp))
    }

    private companion object {
        /** 形如 `Log.w(tag, "...", it)` 的三参数透传（最后一个实参是 throwable）。 */
        val LOG_WITH_THROWABLE = Regex("""Log\.[we]\([^()]*,\s*"[^"]*"\s*,\s*(?:it|t|e|err|cause)\s*\)""")

        /** 日志字符串里直接插值 URL/href 类变量，且没有经过 redact*。 */
        val RAW_URL_INTERP = Regex(
            """\$\{?\s*(?:path|url|href|candidate|candidates|streamUrl|pageUrl|webUrl|resolvedUrl|m3u8Url|link|finalUrl)\b"""
        )
    }
}
