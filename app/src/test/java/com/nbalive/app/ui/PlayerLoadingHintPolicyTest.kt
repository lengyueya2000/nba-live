package com.nbalive.app.ui

import com.nbalive.app.TestPaths
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
     * 接线守卫（**P7 完整版重锚**，2026-11-01）。
     *
     * 判据只写不消费 = 假安全感（本仓库老问题），所以判据落在**结构**上：
     *  ① `fun PlayerScreen(` 体内必须真的**调用** `PlayerLoadingHint(...)`（渲染入口）；
     *  ② 判据 `shouldShow(...)` 必须落在 `PlayerLoadingHint` 的**函数体**里 ——
     *     这也正是"哪一块会被 250ms ticker 失效"的边界（P7 的意义就在这）；
     *  ③ `fun PlayerScreen(` 体内**不许**再有 ticker（`delay(250)` / `sinceLoadMs`）；
     *  ④ 显示时机的关键输入（`sinceLoadMs` + `MAX_HINT_MS` 上限）必须在那个函数体里。
     *
     * ⚠️ 不许退化成 `contains("shouldShow")`：那样把调用删掉、只在同文件别处
     * 留一句注释/字符串就照样绿（本仓库反复踩过这个坑）。
     */
    @Test
    fun `PlayerScreen 只许调用加载提示组件 判据必须落在它的函数体里`() {
        val code = productionCode()["PlayerScreen.kt"]
            ?: error("找不到 PlayerScreen.kt —— 这条守卫会静默全绿，所以直接失败")

        // ① 渲染入口：`fun PlayerScreen(` 体内必须真的调用 PlayerLoadingHint(...)
        val screenBody = GuardSourceScan.functionBody(code, "fun PlayerScreen(")
        assertTrue(
            "`fun PlayerScreen(` 里没有 `PlayerLoadingHint(...)` 调用：\n" +
                "加载提示的渲染入口断了（提示整块消失）。实际函数体片段：\n" +
                screenBody.take(400),
            GuardSourceScan.callSites(screenBody, "PlayerLoadingHint") >= 1,
        )
        // ② P7：判据不许留在 `fun PlayerScreen(` 体内
        //    （否则 ticker 的失效面又回到整块 content lambda）
        assertFalse(
            "`fun PlayerScreen(` 体内还有 `PlayerLoadingHintPolicy.shouldShow(...)`：" +
                "判据没搬进 PlayerLoadingHint，250ms ticker 会连带失效整块 content lambda",
            screenBody.contains("PlayerLoadingHintPolicy.shouldShow("),
        )
        assertFalse(
            "`fun PlayerScreen(` 体内还有 250ms ticker（`delay(250)`）—— ticker 没搬出去",
            screenBody.contains("delay(250)"),
        )
        // ③ 判据必须在 PlayerLoadingHint 的函数体里被**调用**（不是"文件里出现过"）
        val hintBody = GuardSourceScan.functionBody(code, "fun BoxScope.PlayerLoadingHint(")
        assertTrue(
            "PlayerLoadingHint 体内没有 `PlayerLoadingHintPolicy.shouldShow(...)`：\n" +
                "提示判据在那个组件里没人消费。实际函数体片段：\n" + hintBody.take(400),
            hintBody.contains("PlayerLoadingHintPolicy.shouldShow("),
        )
        // ④ 显示时机必须仍在计时上：20 秒上限 + sinceLoadMs 输入
        assertTrue(
            "PlayerLoadingHint 体内没把 `sinceLoadMs` 喂给判据：计时语义会失效",
            hintBody.contains("sinceLoadMs = sinceLoadMs"),
        )
        assertTrue(
            "PlayerLoadingHint 体内没有 `MAX_HINT_MS`：20 秒上限没了",
            hintBody.contains("PlayerLoadingHintPolicy.MAX_HINT_MS"),
        )
        assertTrue(
            "ticker 必须在 PlayerLoadingHint 体内（它是失效范围的边界）",
            hintBody.contains("delay(250)"),
        )
    }


    private fun productionCode(): Map<String, String> =
        productionRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to GuardSourceScan.stripNonCode(it.readText()) }

    private fun productionRoot(): File = TestPaths.productionRoot
}