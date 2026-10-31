package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源站播放器进入「错误死胡同」时的探测与处置（2026-10-31 用户反馈：
 * 「在这个页面点刷新没有反应」）。
 *
 * ## 现场
 *
 * 网页兜底播放时源站 xgplayer 报错，画一块「网络错误 / 请试试 刷新」。
 * 点那个「刷新」**什么都不发生**，因为 xgplayer 的 `retry()` 只是把
 * **同一个 `config.url`** 重新赋给 `<video>`（见 `WebPageDeadEndPolicy` 的注释），
 * 而那个地址已经失效 —— 再赋一次还是同一个死地址。
 *
 * 所以 App 必须自己给出口：探测到错误态 → 显示我们自己的恢复条 → 自动换下一条线。
 */
class WebPageDeadEndPolicyTest {

    private fun offer(
        hasStream: Boolean = false,
        pageErrorDetected: Boolean = true,
        sinceDetectedMs: Long = WebPageDeadEndPolicy.CONFIRM_MS,
    ) = WebPageDeadEndPolicy.shouldOffer(
        hasStream = hasStream,
        pageErrorDetected = pageErrorDetected,
        sinceDetectedMs = sinceDetectedMs,
    )

    @Test
    fun `确认期到了就该给出口`() {
        assertTrue(offer())
    }

    @Test
    fun `刚探测到错误时先等一下 给播放器自愈的机会`() {
        assertFalse(
            "确认期是留给源站播放器内部重试的，一探测到就压提示会误伤抖动",
            offer(sinceDetectedMs = WebPageDeadEndPolicy.CONFIRM_MS - 1),
        )
    }

    @Test
    fun `已经有流就绝不打扰`() {
        assertFalse("在播的时候压一块提示是 P0-1 批评过的误杀", offer(hasStream = true))
    }

    @Test
    fun `页面没报错就不给`() {
        assertFalse("判据必须是页面自己报错这个事实，不能是我们嗅探不到这种推断", offer(pageErrorDetected = false))
    }

    @Test
    fun `计时不能为负`() {
        assertFalse(offer(sinceDetectedMs = -1))
    }

    @Test
    fun `主按钮不能叫刷新 那个词是坏的`() {
        assertEquals(
            "源站那个「刷新」点不动，我们再写同一个词，用户会以为还是那个坏按钮",
            "重新加载",
            WebPageDeadEndPolicy.reloadLabel(),
        )
    }

    @Test
    fun `次按钮要能换线路`() {
        assertEquals("换线路", WebPageDeadEndPolicy.switchLabel())
    }

    @Test
    fun `说明文案非空 且不空口说'请重试'`() {
        val note = WebPageDeadEndPolicy.note()
        assertTrue("恢复条必须有说明，否则用户不知道这块提示是谁给的", note.isNotBlank())
        assertTrue("说明要指向真正的出路（换线路），而不是让用户原地重试", note.contains("线路"))
    }

    @Test
    fun `自动换线时的提示要能区分于嗅探超时`() {
        val note = WebPageDeadEndPolicy.advanceNote()
        assertTrue(note.isNotBlank())
        assertFalse(
            "与 SniffRecoveryPolicy.advanceNote() 撞词会让用户分不清是哪条路径",
            note == SniffRecoveryPolicy.advanceNote(),
        )
    }

    // ---------- 自动兜底：出口摆着没人点 ----------

    private fun auto(
        hasStream: Boolean = false,
        pageErrorDetected: Boolean = true,
        sinceDetectedMs: Long = WebPageDeadEndPolicy.CONFIRM_MS + WebPageDeadEndPolicy.AUTO_ADVANCE_MS,
    ) = WebPageDeadEndPolicy.shouldAutoAdvance(
        hasStream = hasStream,
        pageErrorDetected = pageErrorDetected,
        sinceDetectedMs = sinceDetectedMs,
    )

    @Test
    fun `出口摆够时间还没人点 就替用户换线`() {
        assertTrue("干等一块点不动的面板是最差的结果", auto())
    }

    @Test
    fun `出口刚出现时不要抢在用户前面换线`() {
        assertFalse(auto(sinceDetectedMs = WebPageDeadEndPolicy.CONFIRM_MS))
    }

    @Test
    fun `自动换线同样不许打扰正在播的画面`() {
        assertFalse(auto(hasStream = true))
    }

    @Test
    fun `页面没报错就不自动换线`() {
        assertFalse(auto(pageErrorDetected = false))
    }

    @Test
    fun `自动兜底必须晚于给出出口`() {
        assertTrue(
            "如果 AUTO_ADVANCE_MS 是 0，用户根本来不及看到那两个按钮",
            WebPageDeadEndPolicy.AUTO_ADVANCE_MS > 0,
        )
    }
}

/** 探测脚本本身：写错了不会抛异常、只会静默永不命中 —— 必须逐条钉住。 */
class WebErrorProbeScriptTest {

    private val script = WebErrorProbeScript.HAS_ERROR

    @Test
    fun `查的是播放器根节点的错误状态类`() {
        assertTrue(
            "xgplayer 的错误态类名变了（或写错了），探测永远不命中：$script",
            script.contains(WebErrorProbeScript.ERROR_CLASS),
        )
    }

    @Test
    fun `同时查错误面板节点`() {
        assertTrue(script.contains(WebErrorProbeScript.ERROR_PANEL))
    }

    @Test
    fun `要遍历 iframe 因为播放器可能在子框架里`() {
        assertTrue("pjs.js 会把播放器塞进 iframe", script.contains("iframe"))
        assertTrue("同源 iframe 的 contentDocument 要读", script.contains("contentDocument"))
    }

    @Test
    fun `跨源 iframe 读不到时不能把宿主拖挂`() {
        assertTrue("必须有 try/catch 兜住跨源访问异常", script.contains("catch"))
    }

    @Test
    fun `返回值必须是常量 1 或 0`() {
        assertTrue("命中返回 '1'", script.contains("'1'"))
        assertTrue("未命中返回 '0'", script.contains("'0'"))
    }

    @Test
    fun `不查错误文案 只查结构`() {
        assertFalse(
            "错误文案来自 i18n，源站换语言/换版本就失效；class 才是结构契约",
            script.contains("网络错误"),
        )
    }

    @Test
    fun `宿主侧解读 字符串常量会被再包一层引号`() {
        assertTrue("evaluateJavascript 回传 \"\\\"1\\\"\"", WebErrorProbeScript.detectsError("\"1\""))
        assertTrue("不带引号也要认", WebErrorProbeScript.detectsError("1"))
        assertFalse(WebErrorProbeScript.detectsError("\"0\""))
        assertFalse("WebView 已销毁时回调是 null", WebErrorProbeScript.detectsError(null))
        assertFalse(WebErrorProbeScript.detectsError(""))
    }
}

/**
 * 接线守卫：探测脚本 / 判据 / 恢复条 / 自动换线必须真的连起来。
 *
 * 本仓库的老问题是"判据写了没人消费"（等于假安全感），所以每一环都要扫源码钉住。
 */
class WebPageDeadEndWiringTest {

    @Test
    fun `WebSurface 必须注入探测脚本并消费判据`() {
        val body = webSurfaceBody()
        assertTrue(
            "WebSurface 没有注入探测脚本：页面报错这件事 App 永远不知道。\n实际片段：\n" + body.take(400),
            body.contains("WebErrorProbeScript.HAS_ERROR"),
        )
        assertTrue(
            "WebSurface 没有调 WebPageDeadEndPolicy.shouldOffer(...)：判据写了没人用",
            body.contains("WebPageDeadEndPolicy.shouldOffer("),
        )
        assertTrue(
            "探测脚本必须通过 evaluateJavascript 注入",
            body.contains("evaluateJavascript("),
        )
    }

    @Test
    fun `WebSurface 必须把死胡同上报给上层`() {
        val body = webSurfaceBody()
        assertTrue(
            "WebSurface 没有 onPageDeadEnd 回调：探测到了也只能干看着",
            body.contains("onPageDeadEnd"),
        )
    }

    @Test
    fun `恢复条必须真的渲染出按钮`() {
        val body = webSurfaceBody()
        assertTrue(
            "没有渲染重新加载按钮（reloadLabel 写了没人用）",
            body.contains("WebPageDeadEndPolicy.reloadLabel()"),
        )
        assertTrue(
            "没有渲染换线路按钮（switchLabel 写了没人用）",
            body.contains("WebPageDeadEndPolicy.switchLabel()"),
        )
    }

    @Test
    fun `PlayerScreen 与 NbaLiveApp 要把回调接到 ViewModel`() {
        val screen = source("PlayerScreen.kt")
        assertTrue(
            "PlayerScreen 的参数表里没有 onPageDeadEnd：WebSurface 的上报出不去",
            screen.contains("onPageDeadEnd"),
        )
        val app = source("NbaLiveApp.kt")
        assertTrue(
            "NbaLiveApp 没有把 onPageDeadEnd 接到 viewModel",
            app.contains("onPageDeadEnd = viewModel::onPageDeadEnd"),
        )
    }

    @Test
    fun `ViewModel 收到上报后要走自动换线`() {
        val vm = source("MainViewModel.kt")
        val body = GuardSourceScan.functionBody(vm, "fun onPageDeadEnd(")
        assertTrue(
            "MainViewModel 没有 onPageDeadEnd：NbaLiveApp 那行会编译不过（这条守卫是双保险）",
            body.isNotEmpty(),
        )
        assertTrue(
            "收到页面报错后没有换线 —— 用户还是只能对着那块死面板",
            body.contains("advanceAfterNoSignal("),
        )
        assertTrue(
            "提示文案必须来自 WebPageDeadEndPolicy，不能在这里另写一份",
            body.contains("WebPageDeadEndPolicy.advanceNote()"),
        )
    }

    @Test
    fun `自检 注释与字符串里的调用不算接线`() {
        val sample = listOf(
            "class Sample {",
            "    fun WebSurface() {",
            "        // WebErrorProbeScript.HAS_ERROR 只在注释里",
            "        val s = \"WebPageDeadEndPolicy.shouldOffer( 只在字符串里\"",
            "    }",
            "}",
        ).joinToString("\n")
        val body = GuardSourceScan.functionBody(sample, "fun WebSurface(")
        assertFalse("判据命中了注释里的字面量", body.contains("WebErrorProbeScript.HAS_ERROR"))
        assertFalse("判据命中了字符串里的字面量", body.contains("WebPageDeadEndPolicy.shouldOffer("))
    }

    // ---------------------------------------------------------------

    private fun webSurfaceBody(): String =
        GuardSourceScan.functionBody(source("PlayerScreen.kt"), "fun WebSurface(")

    private fun source(name: String): String {
        val root = productionRoot()
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("找不到 $name —— 这条守卫会静默全绿，所以直接失败")
        return GuardSourceScan.stripNonCode(file.readText())
    }

    private fun productionRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").absolutePath
        return listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd）—— 守卫会静默全绿")
    }
}
