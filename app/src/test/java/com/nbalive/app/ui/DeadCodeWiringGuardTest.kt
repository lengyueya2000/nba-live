package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
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
 *
 * ## ⚠️ 本文件曾经自己就是"假安全感"（2026-10-28 审计 A1/A3）
 *
 * 只读审计发现**这个守门文件本身有四条断言永远不会红**：
 *  1. 用 `readText().contains(...)` **没剔注释** —— `key(webFallbackUrl)` 命中的是
 *     `PlayerScreen.kt` 里那句解释它的**注释**；删掉真正的 `key(...)` 照样绿；
 *  2. `SourceFailureBook` 命中的是 **KDoc**；3. `isSourceFailed(` 命中的是**函数声明**；
 *     4. `FIRST_FRAME_WATCHDOG_MS` 命中的是 `const val` **声明**本身。
 * 于是"删掉 WebView 重建"、"删掉 TTL 使用"、"删掉整个首帧看门狗"都不会变红。
 *
 * 另有 **22 处 `assumeTrue`**：路径一变就整片 skipped，而"448 全绿"看不出区别。
 *
 * 现在三条硬规矩（配合 `GuardHygieneTest` 的元守卫）：
 *  - 一切断言前先 [stripComments]（**连字符串字面量一起剔**，只留真实代码）；
 *  - **声明不算使用**：常量/函数要么排除声明行后计数，要么换到别的文件去查调用点；
 *  - 路径找不到 = 直接失败（`error()`），绝不用 `assumeTrue` 静默跳过；
 *  - 末尾有 [扫描必须真的扫到生产源码] 兜住"零违规"的空扫。
 */
class DeadCodeWiringGuardTest {

    @Test
    fun `画面胶囊的显隐判据必须被生产代码调用`() {
        // 定义处不算调用点，所以排除 PlayerControls.kt 自身
        val callers = productionCode()
            .filterKeys { it != "PlayerControls.kt" }
            .filterValues { it.contains("shouldShowAspectChip") }
            .keys
        assertTrue(
            "shouldShowAspectChip 写好了却零调用点：网页线路点「画面」三档文字变、" +
                "画面毫无变化，而且选择会被持久化（01-P2-11）。\n" +
                "请在渲染「画面」胶囊处用它做条件。",
            callers.isNotEmpty(),
        )
    }

    @Test
    fun `失败记账的 TTL 必须真的被用上`() {
        val vm = code("MainViewModel.kt")
        assertTrue(
            "MainViewModel 必须**构造并使用**带 TTL 的 SourceFailureBook，而不是裸 Set —— " +
                "裸 Set 会让一次误判把健康线路永久拉黑（01-P1-5）。\n" +
                "（判据是构造点 `SourceFailureBook()`，不是注释里提一句）",
            vm.contains("SourceFailureBook("),
        )
        // ⚠️ 声明不算使用：必须排除 `private fun isSourceFailed(` 那一行再数调用
        val uses = vm.lines()
            .filterNot { it.contains("fun isSourceFailed(") }
            .count { it.contains("isSourceFailed(") }
        assertTrue(
            "拉黑判定必须走 isSourceFailed（带过期检查），不能直接判集合成员。\n" +
                "这里要求的是**调用点**（排除声明行后的出现次数≥1），实际 $uses 处。",
            uses >= 1,
        )
    }

    @Test
    fun `首帧看门狗阈值不能退回 15 秒`() {
        val code = code("PlayerScreen.kt")
        // ⚠️ 声明不算使用：常量声明本身也会命中这个名字
        val uses = code.lines()
            .filterNot { it.contains("const val FIRST_FRAME_WATCHDOG_MS") }
            .count { it.contains("FIRST_FRAME_WATCHDOG_MS") }
        assertTrue(
            "首帧看门狗阈值必须是具名常量（便于审阅阈值），且**真的被用上**：\n" +
                "15 秒对弱网首帧太紧，会误杀健康线路（01-P1-5）。\n" +
                "排除声明行后实际使用次数 $uses（应为 ≥1）。",
            uses >= 1,
        )
        val bareDelay = code.lines().any { it.contains("delay(15_000)") }
        assertTrue("不应再出现裸 delay(15_000) 的首帧判定", !bareDelay)
    }

    @Test
    fun `网页播放器必须按 URL 重建 否则切线路会串流`() {
        // 01-P2-4 的第一层修法：切网页线路时若复用同一个 WebView，
        // 旧页面在途的嗅探回调会把旧线路的流当成新线路升级。
        // ⚠️ 判据必须查**真实调用**：解释这行代码的注释里也有同样的字面量，
        // contains(rawText) 会被注释蒙混过关（审计 A1 抓到的就是这个）。
        assertTrue(
            "WebSurface 必须包在 key(webFallbackUrl) 里：换网页线路时要重建 WebView，\n" +
                "否则旧页面的在途嗅探会把已判失败的线路重新升级（界面显示 B、实际播 A）。",
            code("PlayerScreen.kt").contains("key(webFallbackUrl)"),
        )
    }

    @Test
    fun `嗅探升级必须校验页面归属`() {
        val vm = code("MainViewModel.kt")
        // ⚠️ 同上：排除声明行，确保是**调用**而不是"这里定义了这个判据"
        val uses = vm.lines()
            .filterNot { it.contains("fun ") && it.contains("belongsToPage") }
            .count { it.contains("belongsToPage(") }
        assertTrue(
            "onStreamSniffed 必须校验 Referer 归属当前播放页（01-P2-4 第二层防线）。\n" +
                "排除声明行后的调用次数 $uses（应为 ≥1）。",
            uses >= 1,
        )
    }

    @Test
    fun `网页退后台必须注入媒体暂停脚本`() {
        val code = code("PlayerScreen.kt")
        assertTrue(
            "ON_STOP 必须注入 WebMediaPauseScript.PAUSE_ALL：\n" +
                "webView.onPause()/pauseTimers() 停不掉 HTML5 媒体，锁屏后声音还会外放（01-P2-3）",
            code.contains("WebMediaPauseScript.PAUSE_ALL"),
        )
        assertTrue(
            "ON_START 必须恢复被我们暂停过的媒体（不能只暂停不恢复，否则回前台就没声了）",
            code.contains("WebMediaPauseScript.RESUME_PAUSED"),
        )
    }

    @Test
    fun `所有状态胶囊调用点都要传 isPostponed`() {
        // 02-P2-10：Match.isPostponed 算好了却没人渲染，延期场次在界面上
        // 与「未开赛」无法区分。接线后必须**每个**调用点都传，
        // 漏一个就会在那一页继续显示错误的「未开赛」。
        val violations = productionCode().flatMap { (name, text) ->
            text.lines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                // 只查调用点（`StatusChip(` 后跟参数），跳过定义
                if (!code.contains("StatusChip(status =")) return@mapIndexedNotNull null
                // 全量直播场次用固定 LIVE 状态，没有延期语义，允许不传
                if (code.contains("MatchStatus.LIVE")) return@mapIndexedNotNull null
                if (code.contains("isPostponed")) return@mapIndexedNotNull null
                "$name:${index + 1}  $code"
            }
        }
        assertTrue(
            "以下 StatusChip 调用点没有传 isPostponed，那一页的延期场次会显示成「未开赛」：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `返回键判据必须走 BackPolicy 且含标签页`() {
        // 03-P2-1：enabled 条件漏掉标签页 -> 在「直播」/「我的」按返回直接退出 App
        val app = code("NbaLiveApp.kt")
        assertTrue(
            "BackHandler 的 enabled 必须走 BackPolicy.shouldIntercept（含标签页判据）",
            app.contains("BackPolicy.shouldIntercept"),
        )
        assertTrue(
            "按返回的动作必须走 BackPolicy.action",
            app.contains("BackPolicy.action"),
        )
        assertTrue(
            "非默认标签按返回要回赛事标签（BackAction.ResetTab）",
            app.contains("BackAction.ResetTab"),
        )
    }

    @Test
    fun `详情页缺 match 不能 return 白屏`() {
        // 03-P2-5：`match = detailState.match ?: return@Box` 会静默白屏
        val app = code("NbaLiveApp.kt")
        assertTrue(
            "详情路由必须用 MissingMatchPolicy 判缺数据并给占位",
            app.contains("MissingMatchPolicy.forDetail"),
        )
        assertTrue(
            "占位必须有返回入口（否则用户对着白屏没法操作）",
            app.contains("MissingMatchPlaceholder"),
        )
    }

    @Test
    fun `手动画中画必须走守卫 且切场要复位小窗尺寸`() {
        val code = code("PlayerScreen.kt")
        assertTrue(
            "手动点画中画必须走 ManualPipPolicy（01-P2-6）：\n" +
                "解析中/全失败时点出来是一块黑窗，而按 Home 那条路早有守卫 —— 两条路径不能只守一条",
            code.contains("ManualPipPolicy.decide"),
        )
        assertTrue(
            "切场必须复位小窗视频尺寸（01-P2-5）：否则新场次首帧到达前，\n" +
                "小窗按上一场的比例显示",
            code.contains("PipAspectReset.shouldResetOnMatchSwitch"),
        )
    }

    @Test
    fun `方向与浮层计时都必须走 SystemBarsPolicy`() {
        val appCode = code("NbaLiveApp.kt")
        assertTrue(
            "全屏方向判定必须走 SystemBarsPolicy.orientationFor（01-P2-7）：\n" +
                "进小窗时 effect 会销毁，onDispose 里无条件复位方向会让小窗跟着乱转",
            appCode.contains("SystemBarsPolicy.orientationFor"),
        )
        // ⚠️ 必须用正则精确匹配 "inPip = inPip"：
        // 用 contains("inPip = inPip") 时 "inPip = false" 不匹配，
        // 但 contains("inPip") 会 —— 传常量等于没传，守卫却照样绿。
        assertTrue(
            "FullscreenSystemEffect 必须接收**真实的** inPip（传常量等于没传，\n" +
                "onDispose 判不出小窗，方向还是会被复位）：",
            Regex("""FullscreenSystemEffect\([^)]*inPip\s*=\s*inPip\b""").containsMatchIn(appCode),
        )

        val playerCode = code("PlayerScreen.kt")
        assertTrue(
            "浮层自动收起必须走 SystemBarsPolicy.shouldAutoHideControls（01-P2-8）：\n" +
                "面板打开时若继续计时，面板关闭后控制栏不再出现",
            playerCode.contains("shouldAutoHideControls"),
        )
        assertTrue(
            "面板关闭后必须回补控制栏（01-P2-8 第二半）",
            playerCode.contains("shouldRestoreControlsOnPanelClose"),
        )
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        // ⚠️ 这条是**非空性锚点**（审计 A3 指出本类原来一条都没有）：
        // 工作目录一变，上面十条会"零违规"地一起变绿，而"全绿"看不出区别。
        val files = productionCode()
        assertTrue("扫描不到生产源码，本文件所有守卫会静默全绿", files.size >= 30)
        for (name in listOf("PlayerScreen.kt", "MainViewModel.kt", "NbaLiveApp.kt", "PlayerControls.kt")) {
            assertTrue("没扫到 $name —— 扫描范围错了，相关守卫都会变成摆设", files.containsKey(name))
        }
    }

    // ---------------------------------------------------------------

    /** 生产源码：文件名 → **已剔除注释**的源码文本。 */
    private fun productionCode(): Map<String, String> =
        productionFiles().associate { it.name to stripComments(it.readText()) }

    private fun code(name: String): String = productionCode()[name]
        ?: error("找不到 $name，守卫会静默全绿（$name 是这条判据的唯一锚点）")

    /**
     * 去掉注释**与字符串字面量**，只留真实代码（行号不变）。
     *
     * ⚠️ 审计「未验证的怀疑 #6」指出本文件旧版只把"整行注释"置空，**行尾 `// …` 原样保留**：
     * 于是"删掉真正的调用、只在同一行留一句提到它的注释"就能让断言继续全绿 ——
     * 又一个"永远不会红的守卫"。现在统一走 [GuardSourceScan.stripNonCode] 的词法级实现。
     */
    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)

    private fun productionFiles(): List<File> {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=${System.getProperty("user.dir")}）")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}