package com.nbalive.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「已经写好但没人调」的守卫（review/00-summary「测试的假安全感」）。
 *
 * ## 为什么需要这个文件
 *
 * 审查发现最危险的形态不是"没写"，而是**写了、测了、但生产代码从不调用**：
 *  - `PlayerControls.shouldShowAspectChip` 有单测、有详细注释，
 *    但 UI 里那个「画面」胶囊仍是无条件渲染 → 网页线路点它毫无变化（01-P2-11）；
 *  - `MatchListUiState.partialError` 有测试断言文案，
 *    但没有任何界面读它 → 用户只看到"场次变少了"（02-P1-2）。
 *
 * 这类缺口单测永远是绿的，只能靠**扫源码看调用点**发现。
 * 本文件把"必须有生产调用点"这件事固化成断言，
 * 新增同类"只声明不消费"的代码时会在这一轮就变红。
 */
class DeadCodeWiringGuardTest {

    @Test
    fun `画面胶囊的显隐判据必须被生产代码调用`() {
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())

        // 定义处不算调用点，所以排除 PlayerControls.kt 自身
        val callers = files.filter { f ->
            f.name != "PlayerControls.kt" && f.readText().contains("shouldShowAspectChip")
        }
        assertTrue(
            "shouldShowAspectChip 写好了却零调用点：网页线路点「画面」三档文字变、" +
                "画面毫无变化，而且选择会被持久化（01-P2-11）。\n" +
                "请在渲染「画面」胶囊处用它做条件。",
            callers.isNotEmpty(),
        )
    }

    @Test
    fun `失败记账的 TTL 必须真的被用上`() {
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())

        // 只断言"SourceFailureBook 被 MainViewModel 持有并使用"，
        // 防止有人把它换回裸 Set 而测试仍然全绿
        val mainVm = files.firstOrNull { it.name == "MainViewModel.kt" }
        assumeTrue("找不到 MainViewModel.kt，跳过", mainVm != null)
        val text = mainVm!!.readText()
        assertTrue(
            "MainViewModel 必须用带 TTL 的 SourceFailureBook 记账，而不是裸 Set —— " +
                "裸 Set 会让一次 15s 误判把健康线路永久拉黑（01-P1-5）",
            text.contains("SourceFailureBook"),
        )
        assertTrue(
            "拉黑判定必须走 isSourceFailed（带过期检查），不能直接判集合成员",
            text.contains("isSourceFailed("),
        )
    }

    @Test
    fun `首帧看门狗阈值不能退回 15 秒`() {
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())
        val playerScreen = files.firstOrNull { it.name == "PlayerScreen.kt" }
        assumeTrue("找不到 PlayerScreen.kt，跳过", playerScreen != null)
        val text = playerScreen!!.readText()

        assertTrue(
            "首帧看门狗必须走具名常量（便于审阅阈值），不能是裸 delay(15_000)：\n" +
                "15 秒对弱网首帧太紧，会误杀健康线路（01-P1-5）",
            text.contains("FIRST_FRAME_WATCHDOG_MS"),
        )
        // 直接查裸 15_000 的 delay 是否还在（注释里出现不算）
        val bareDelay = text.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .any { it.contains("delay(15_000)") }
        assertTrue("不应再出现裸 delay(15_000) 的首帧判定", !bareDelay)
    }

    @Test
    fun `网页播放器必须按 URL 重建 否则切线路会串流`() {
        // 01-P2-4 的第一层修法：切网页线路时若复用同一个 WebView，
        // 旧页面在途的嗅探回调会把旧线路的流当成新线路升级。
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())
        val playerScreen = files.firstOrNull { it.name == "PlayerScreen.kt" }
        assumeTrue("找不到 PlayerScreen.kt，跳过", playerScreen != null)
        val text = playerScreen!!.readText()

        assertTrue(
            "WebSurface 必须包在 key(webFallbackUrl) 里：换网页线路时要重建 WebView，\n" +
                "否则旧页面的在途嗅探会把已判失败的线路重新升级（界面显示 B、实际播 A）。",
            text.contains("key(webFallbackUrl)"),
        )
    }

    @Test
    fun `嗅探升级必须校验页面归属`() {
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())
        val mainVm = files.firstOrNull { it.name == "MainViewModel.kt" }
        assumeTrue("找不到 MainViewModel.kt，跳过", mainVm != null)
        assertTrue(
            "onStreamSniffed 必须校验 Referer 归属当前播放页（01-P2-4 第二层防线）",
            mainVm!!.readText().contains("belongsToPage"),
        )
    }

    @Test
    fun `网页退后台必须注入媒体暂停脚本`() {
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())
        val playerScreen = files.firstOrNull { it.name == "PlayerScreen.kt" }
        assumeTrue("找不到 PlayerScreen.kt，跳过", playerScreen != null)
        val text = playerScreen!!.readText()

        assertTrue(
            "ON_STOP 必须注入 WebMediaPauseScript.PAUSE_ALL：\n" +
                "webView.onPause()/pauseTimers() 停不掉 HTML5 媒体，锁屏后声音还会外放（01-P2-3）",
            text.contains("WebMediaPauseScript.PAUSE_ALL"),
        )
        assertTrue(
            "ON_START 必须恢复被我们暂停过的媒体（不能只暂停不恢复，否则回前台就没声了）",
            text.contains("WebMediaPauseScript.RESUME_PAUSED"),
        )
    }

    @Test
    fun `所有状态胶囊调用点都要传 isPostponed`() {
        // 02-P2-10：Match.isPostponed 算好了却没人渲染，延期场次在界面上
        // 与「未开赛」无法区分。接线后必须**每个**调用点都传，
        // 漏一个就会在那一页继续显示错误的「未开赛」。
        val files = productionFiles()
        assumeTrue("找不到生产源码，跳过源码扫描", files.isNotEmpty())

        val violations = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
                // 只查调用点（`StatusChip(` 后跟参数），跳过定义与 LIVE 常量调用
                if (!code.contains("StatusChip(status =")) return@mapIndexedNotNull null
                // 全量直播场次用固定 LIVE 状态，没有延期语义，允许不传
                if (code.contains("MatchStatus.LIVE")) return@mapIndexedNotNull null
                if (code.contains("isPostponed")) return@mapIndexedNotNull null
                "${file.name}:${index + 1}  $code"
            }
        }
        assertTrue(
            "以下 StatusChip 调用点没有传 isPostponed，那一页的延期场次会显示成「未开赛」：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun productionFiles(): List<File> {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory } ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}