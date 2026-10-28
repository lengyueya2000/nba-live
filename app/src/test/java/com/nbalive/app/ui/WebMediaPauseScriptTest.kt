package com.nbalive.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页线路退到后台时的**媒体暂停脚本**（review/01-player.md P2-3）。
 *
 * ## 要解决什么
 *
 * 网页兜底播放时，声音由源站 H5 播放器（`<video>`/`<audio>`）发出。
 * 退到后台（锁屏、切 App，且**没进小窗**）时当前只调了
 * `webView.onPause()` + `pauseTimers()` —— 这两者只暂停页面渲染与定时器，
 * **不会**停掉正在播放的媒体元素，用户锁屏后声音还在外放。
 *
 * 直连路径没这个问题：ExoPlayer 由 `ON_STOP` 显式 `pause()`。
 *
 * ## 为什么要把脚本抽出来
 *
 * 它是一段注入到第三方页面的 JS 字符串，写错了不会抛异常、只会静默无效 ——
 * 恰恰是最难在真机上发现的那类问题。
 *
 * ## ⚠️ 本文件原来"永远不会红"（2026-10-28 只读审计 A12）
 *
 * 旧断言全是 `js.contains("video")` / `"pause()"` / `"if ("` 这类**子串**，
 * 且脚本从不执行：
 *  - 把 `m.pause();` 注释成 `// m.pause();`，`contains("pause()")` 仍为真；
 *  - 把存在性判断删掉，`contains("if (")` 仍为真（`forEach` 里还有别的 if）；
 *  - 子串命中注释也算。
 *
 * 现在判据是**结构**：先剔 JS 注释（保留字符串字面量，因为选择器/标记本身就是要验的内容），
 * 再断言 `querySelectorAll → 遍历 → 逐个 pause()` 这条链、以及各防御点；
 * 并且自带**变异自检**：把脚本按真实错误形态改坏（注释掉 pause、选择器漏 audio、
 * 删掉存在性判断、只处理第一个元素、删掉标记……），每一条都必须被判为违规。
 * 那才是"改错就变红"（本仓库不允许改 `src/main`，所以用等价的内存变异做回退实验）。
 */
class WebMediaPauseScriptTest {

    @Test
    fun `脚本必须暂停所有 video 与 audio 元素`() {
        val js = WebMediaPauseScript.PAUSE_ALL
        // 非空性锚点：脚本被掏空时下面的结构断言会"零问题"地全绿
        assertTrue("PAUSE_ALL 太短（${js.length} 字符），守卫测不到结构", js.length >= 150)

        val violations = pauseScriptViolations(js)
        assertTrue(
            "PAUSE_ALL 结构不合格：\n" + violations.joinToString("\n").prependIndent("  ") +
                "\n\n脚本（$js）",
            violations.isEmpty(),
        )
    }

    @Test
    fun `恢复脚本只恢复被我们暂停过的媒体`() {
        val js = WebMediaPauseScript.RESUME_PAUSED
        assertTrue("RESUME_PAUSED 太短（${js.length} 字符），守卫测不到结构", js.length >= 150)

        val violations = resumeScriptViolations(js)
        assertTrue(
            "RESUME_PAUSED 结构不合格：\n" + violations.joinToString("\n").prependIndent("  ") +
                "\n\n脚本（$js）",
            violations.isEmpty(),
        )
    }

    @Test
    fun `暂停脚本判据自检 改坏任何一处都必须变红`() {
        val base = WebMediaPauseScript.PAUSE_ALL
        val mutations = listOf(
            "把 m.pause(); 注释掉（旧 contains(\"pause()\") 仍为真）" to
                base.replace("m.pause();", "// m.pause();"),
            "选择器漏掉 audio（只暂停 video）" to
                base.replace("'video, audio'", "'video'"),
            "删掉「页面没有媒体」的存在性判断（无媒体页面会抛异常）" to
                base.replace("if (!list || list.length === 0) return;", ";"),
            "只处理第一个元素（把 for 循环改成单元素分支）" to
                base.replace("for (var i = 0; i < list.length; i++) {", "if (list.length > 0) {"),
            "删掉「我们暂停过」的标记（恢复脚本会误播用户手动暂停的视频）" to
                base.replace("m['__nbaPausedByUs'] = true;", ""),
            "去掉 try/catch（第三方页面异常会污染宿主）" to
                base.replace("try {", "{").replace("} catch (e) { }", "}"),
        )
        val missed = mutations.filter { (_, mutated) -> pauseScriptViolations(mutated).isEmpty() }
        assertTrue(
            "以下变异**没有**被判为违规（判据退化成摆设）：\n" +
                missed.joinToString("\n") { "  - ${it.first}" },
            missed.isEmpty(),
        )
    }

    @Test
    fun `恢复脚本判据自检 改坏任何一处都必须变红`() {
        val base = WebMediaPauseScript.RESUME_PAUSED
        val mutations = listOf(
            "删掉「只恢复我们暂停过的」标记检查（会强播用户手动暂停的视频）" to
                base.replace("if (m && m['__nbaPausedByUs']) {", "if (m) {"),
            "把 play() 去掉" to
                base.replace("var p = m.play();", "var p = null;"),
            "删掉 play() 的 catch（自动播放被拒时留下 unhandled rejection）" to
                base.replace("if (p && p.catch) p.catch(function() {});", ""),
            "选择器漏掉 audio" to
                base.replace("'video, audio'", "'video'"),
            "删掉「页面没有媒体」的存在性判断" to
                base.replace("if (!list || list.length === 0) return;", ";"),
        )
        val missed = mutations.filter { (_, mutated) -> resumeScriptViolations(mutated).isEmpty() }
        assertTrue(
            "以下变异**没有**被判为违规（判据退化成摆设）：\n" +
                missed.joinToString("\n") { "  - ${it.first}" },
            missed.isEmpty(),
        )
    }

    // ---------------------------------------------------------------
    // 判据：JS 源文本 → 违规说明（空 = 达标）
    //
    // 用「剔 JS 注释但**保留字符串字面量**」的视图：选择器 'video, audio' 与
    // 标记 '__nbaPausedByUs' 本身就是要验证的内容，剔掉字符串就没法验了；
    // 而注释必须剔掉，否则 `// m.pause();` 会命中（审计 A12 的原始形态）。
    // ---------------------------------------------------------------

    private fun pauseScriptViolations(js: String): List<String> {
        val code = stripJsComments(js)
        val out = mutableListOf<String>()
        requireSelector(code, out)
        if (!Regex("""for\s*\([^)]*\)[\s\S]{0,400}?\.pause\s*\(\s*\)""").containsMatchIn(code)) {
            out += "① 没有「遍历所有元素并逐个 .pause()」：querySelector 只取第一个 / 或只对集合调一次"
        }
        if (!Regex("""!\s*\w+\.paused\b""").containsMatchIn(code)) {
            out += "② 没有 `!m.paused` 判定（会把本来就没在播的元素也标成「我们暂停过」）"
        }
        if (!Regex("""\[\s*'__nbaPausedByUs'\s*\]\s*=\s*true""").containsMatchIn(code)) {
            out += "③ 没有给元素打「我们暂停过」的标记（恢复脚本就分不出用户手动暂停的）"
        }
        requireExistenceGuard(code, out)
        requireTryCatch(code, out)
        return out
    }

    private fun resumeScriptViolations(js: String): List<String> {
        val code = stripJsComments(js)
        val out = mutableListOf<String>()
        requireSelector(code, out)
        val play = Regex("""\.play\s*\(\s*\)""").find(code)
        // 「标记」必须是被**读**进条件里的（`if (m && m['__nbaPausedByUs'])`），
        // 而不是下面那行 `m['__nbaPausedByUs'] = false` 的赋值 —— 否则"删掉判断、只留清标记"
        // 也会被判合规（自检里的变异样本会立刻抓到这一点）。
        val markRead = Regex("""if\s*\([^)]*\[\s*'__nbaPausedByUs'\s*\]""").find(code)
        if (play == null) {
            out += "① 没有 .play() 调用（恢复脚本不恢复）"
        }
        if (markRead == null) {
            out += "② 没有把「我们暂停过」的标记读进条件（会强播用户手动暂停的视频）"
        }
        if (markRead != null && play != null && markRead.range.first > play.range.first) {
            out += "③ 先 play() 再检查标记 —— 判定顺序反了，等于没检查"
        }
        if (!Regex("""\[\s*'__nbaPausedByUs'\s*\]\s*=\s*false""").containsMatchIn(code)) {
            out += "④ 没有把标记清掉（下次退后台时状态残留）"
        }
        if (!Regex("""\.catch\s*\(""").containsMatchIn(code)) {
            out += "⑤ play() 返回的 Promise 没有 catch（自动播放被拒时留 unhandled rejection）"
        }
        requireExistenceGuard(code, out)
        requireTryCatch(code, out)
        return out
    }

    /** 一次拿全 `video` 与 `audio`（直播页常有多路媒体：广告位、备用播放器）。 */
    private fun requireSelector(code: String, out: MutableList<String>) {
        val selector = Regex("""querySelectorAll\s*\(\s*'([^']*)'""").find(code)
        if (selector == null) {
            out += "querySelectorAll('<选择器>') 调用不存在"
            return
        }
        val value = selector.groupValues[1]
        if (!value.contains("video")) out += "选择器漏了 video（实际 '$value'）"
        if (!value.contains("audio")) out += "选择器漏了 audio（实际 '$value'）"
    }

    private fun requireExistenceGuard(code: String, out: MutableList<String>) {
        if (!Regex("""if\s*\([^)]*\)\s*return\b""").containsMatchIn(code)) {
            out += "没有「页面没有媒体元素就直接返回」的存在性判断（无媒体页面会抛异常）"
        }
    }

    private fun requireTryCatch(code: String, out: MutableList<String>) {
        if (!Regex("""try\s*\{""").containsMatchIn(code) || !Regex("""catch\s*\(""").containsMatchIn(code)) {
            out += "没有 try/catch：注入第三方页面，任何异常都不该影响宿主"
        }
    }

    /**
     * 剔 JS 注释（`//…`、`/*…*/`），**保留字符串字面量**，换行保持。
     *
     * 与 Kotlin 侧的 [com.nbalive.app.GuardSourceScan.stripNonCode] 的分工：
     * 那个工具连字符串一起剔（适合"匹配调用而不是字符串出现"），
     * 但这段 JS 里选择器与标记**本身就是字符串**，剔掉就没法验内容了 ——
     * 所以这里只剔注释（注释是唯一会让"坏脚本"假装合规的东西）。
     */
    private fun stripJsComments(js: String): String {
        val out = StringBuilder(js.length)
        var i = 0
        while (i < js.length) {
            val c = js[i]
            when {
                c == '/' && js.getOrNull(i + 1) == '/' -> {
                    while (i < js.length && js[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && js.getOrNull(i + 1) == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < js.length && !(js[i] == '*' && js.getOrNull(i + 1) == '/')) {
                        out.append(if (js[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < js.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                c == '\'' || c == '"' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < js.length) {
                        val d = js[i]
                        if (d == '\\' && i + 1 < js.length) {
                            out.append(d).append(js[i + 1])
                            i += 2
                            continue
                        }
                        out.append(d)
                        i++
                        if (d == quote || d == '\n') break
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}