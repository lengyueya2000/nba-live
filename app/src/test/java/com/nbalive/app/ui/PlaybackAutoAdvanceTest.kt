package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网页线路长时间没媒体 → 自动换下一条」的判据与接线（线上黑屏修复）。
 *
 * 背景与实测证据见 [PlaybackAutoAdvance] 的 KDoc：
 * 源站首条线路多数是 JRS（只能走 WebView），而它依赖的 `document.write`
 * 跨站脚本会被 chromium 拦截 → 嗅探器 25 秒看不到任何媒体 → 旧行为只提示不换线，
 * 用户对着黑屏干等；同一场的 JRKAN 线路明明能解析出真 m3u8 却永远不会被尝试。
 *
 * 本文件两件事：
 *  1. [PlaybackAutoAdvance.decide] 的**纯判据**（含"已经在直连播就绝不换线"的最强否决）；
 *  2. **接线守卫**：`MainViewModel.onPlaybackSuspect` 必须真的消费这个判据 ——
 *     否则判据写好了没人用，就是审计反复点名的「零消费点」假安全感。
 */
class PlaybackAutoAdvanceTest {

    // ---------- 纯判据 ----------

    @Test
    fun `已经拿到直连流时绝不换线`() {
        // 最重要的一条：原注释担心的三种"正常在播也嗅不到"（MSE/blob、地址不带 .m3u8、
        // 弱网首帧超 25 秒）都归属这一档 —— 只要已经在直连播，就不许动它。
        assertEquals(
            "已经在直连播时换线会踢掉正在看的画面，必须 Wait",
            PlaybackAutoAdvance.Decision.Wait,
            PlaybackAutoAdvance.decide(
                signalCount = 3,
                currentIndex = 0,
                hasDirectStream = true,
                suspect = true,
                alreadyTried = emptySet(),
            ),
        )
    }

    @Test
    fun `一条流都没有且已判疑似时 换到下一条没试过的`() {
        assertEquals(
            PlaybackAutoAdvance.Decision.Advance(1),
            PlaybackAutoAdvance.decide(3, 0, hasDirectStream = false, suspect = true, alreadyTried = setOf(0)),
        )
        // 当前这条也算"试过"，所以从 1 开始就是 2
        assertEquals(
            PlaybackAutoAdvance.Decision.Advance(2),
            PlaybackAutoAdvance.decide(3, 1, hasDirectStream = false, suspect = true, alreadyTried = emptySet()),
        )
    }

    @Test
    fun `每条线路只试一次 全试完就 Exhausted 而不是绕回死循环`() {
        assertEquals(
            "三条都试过（含当前）时必须停下，否则死线路之间会无限来回跳",
            PlaybackAutoAdvance.Decision.Exhausted,
            PlaybackAutoAdvance.decide(3, 2, hasDirectStream = false, suspect = true, alreadyTried = setOf(0, 1, 2)),
        )
    }

    @Test
    fun `环形查找会绕回前面 但不会回到当前这条`() {
        // 余下唯一条没试过的是 0（当前是 2）→ 应该绕回去试它
        assertEquals(
            PlaybackAutoAdvance.Decision.Advance(0),
            PlaybackAutoAdvance.decide(3, 2, hasDirectStream = false, suspect = true, alreadyTried = setOf(2)),
        )
        // 单线路时没有任何"下一条"可换 → 直接 Exhausted（不会 Advance 到自己）
        assertEquals(
            PlaybackAutoAdvance.Decision.Exhausted,
            PlaybackAutoAdvance.decide(1, 0, hasDirectStream = false, suspect = true, alreadyTried = emptySet()),
        )
    }

    @Test
    fun `还没到疑似 或者根本没有线路时什么都不做`() {
        assertEquals(
            "没判疑似就换线，会把还在加载的线路提前踢掉",
            PlaybackAutoAdvance.Decision.Wait,
            PlaybackAutoAdvance.decide(3, 0, hasDirectStream = false, suspect = false, alreadyTried = emptySet()),
        )
        assertEquals(
            PlaybackAutoAdvance.Decision.Wait,
            PlaybackAutoAdvance.decide(0, 0, hasDirectStream = false, suspect = true, alreadyTried = emptySet()),
        )
    }

    // ---------- 接线守卫（去掉修复即红） ----------

    @Test
    fun `疑似上报必须真的消费自动换线判据 而不是只置一个标记`() {
        // 2026-10-30：换线处置被抽成唯一入口 `advanceAfterNoSignal(state, note)`，
        // 让"疑似无信号"和"直连失败退回网页后没恢复"两条路径共用同一套判据。
        // 所以判据守卫要**顺着调用链**检查两个函数体，而不是死盯某一个函数名 ——
        // 只盯 onPlaybackSuspect 会在重构后误报，而放宽成"文件里出现过"又会漏判。
        val suspectBody = GuardSourceScan.functionBody(vmSource(), "fun onPlaybackSuspect(")
        val advanceBody = GuardSourceScan.functionBody(vmSource(), "fun advanceAfterNoSignal(")
        assertTrue(
            "`onPlaybackSuspect` 必须把处置交给 advanceAfterNoSignal(...) ——\n" +
                "只置 `playbackSuspect=true`（修复前的写法）会让用户对着黑屏干等：\n" +
                "实测视频区 0% 非黑像素、无声音、控件正常。\n" +
                "实际函数体：\n$suspectBody",
            suspectBody.contains("advanceAfterNoSignal("),
        )
        assertTrue(
            "`advanceAfterNoSignal` 里必须真的调用 PlaybackAutoAdvance.decide ——\n" +
                "否则判据写好了没人用。实际函数体：\n$advanceBody",
            advanceBody.contains("PlaybackAutoAdvance.decide("),
        )
        assertTrue(
            "判出 Advance 之后必须真的**换线路**（调用 selectSignal(）——\n" +
                "只算不换等于没有修复。",
            advanceBody.contains("selectSignal("),
        )
        assertTrue(
            "全部线路都试完时必须走 Exhausted 分支并给用户一句提示，而不是继续黑屏。\n" +
                "⚠️ 这里断言的是**代码符号**（`Decision.Exhausted` / `withError(`），不是提示文案 ——\n" +
                "`GuardSourceScan.stripNonCode` 会把**字符串字面量**也剔掉，" +
                "拿文案做断言会永远不成立（我第一版就是这么写错的，红了一次）。",
            advanceBody.contains("PlaybackAutoAdvance.Decision.Exhausted") &&
                Regex("""withError\(""").findAll(advanceBody).count() >= 2,
        )
        // 反向断言：不许在直连已就绪时也换线
        assertTrue(
            "疑似分支里必须保留 `streamUrl != null` 的提前返回（已在直连播就绝不换线）",
            suspectBody.contains("state.streamUrl != null") ||
                suspectBody.contains("streamUrl != null"),
        )
    }

    @Test
    fun `判据自检 函数体提取不会把注释当成调用`() {
        // 防"接线守卫自己变成摆设"：喂给**真正参与判定的那个函数**。
        val sample = listOf(
            "class Sample {",
            "    fun onPlaybackSuspect() {",
            "        // 这里提到 PlaybackAutoAdvance.decide( 只是注释，不算接线",
            "        val note = \"PlaybackAutoAdvance.decide( 字符串里也不算\"",
            "    }",
            "}",
        ).joinToString("\n")
        val body = GuardSourceScan.functionBody(sample, "fun onPlaybackSuspect(")
        assertTrue(
            "函数体提取把注释/字符串里的字面量当成了真实调用（守卫会假绿）：$body",
            !body.contains("PlaybackAutoAdvance.decide("),
        )
        val real = listOf(
            "class Sample {",
            "    fun onPlaybackSuspect() {",
            "        when (PlaybackAutoAdvance.decide(1, 0, false, true, emptySet())) { }",
            "    }",
            "}",
        ).joinToString("\n")
        assertTrue(
            "函数体提取漏掉了真实调用（守卫会永远不会绿）",
            GuardSourceScan.functionBody(real, "fun onPlaybackSuspect(").contains("PlaybackAutoAdvance.decide("),
        )
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        val src = vmSource()
        assertTrue("MainViewModel.kt 只有 ${src.lines().size} 行（应 >300）", src.lines().size > 300)
    }

    // ---------- 工具 ----------

    private fun vmSource(): String {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码根（cwd=${System.getProperty("user.dir")}）—— 这条守卫会静默全绿")
        return root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "MainViewModel.kt" }
            ?.readText()
            ?: error("找不到 MainViewModel.kt —— 这条守卫会静默全绿，所以直接失败")
    }
}