package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 直播卡片无障碍语义守卫 —— review/03-ui.md **P2-25**「同一张卡上两个语义重叠的可点节点」。
 *
 * ## 缺陷与修法
 *
 * 旧实现：外层 `Column`（整卡）有 `.clickable(…)`，卡片内部的「立即观看」按钮**又**有一个
 * `.clickable(onClick = 同一个 onClick)`。TalkBack 会把它读成两个独立可点节点：
 * 用户滑到这张卡要停两次、听到两遍相同动作。
 *
 * 修法：移除内层那个重复的 `.clickable`（点击由整卡承担，视觉上按钮仍在），
 * 并保留整卡的 `onClickLabel = CardSemantics.ACTION_LABEL` 让动作有名字。
 *
 * ⚠️ 语义树层（TalkBack 实际读几个节点）在 JVM 单测里量不到 —— 本工程没有
 * instrumentation 测试，所以这里只能钉住**结构事实**：`LiveNowCard` 体内恰好一次
 * `.clickable(`。**这不足以宣称 P2-25 已完全解决，仍需真机 TalkBack 走查。**
 */
class LiveNowCardSemanticsTest {

    private val screenText: String = productionFile("ui/screens/LiveNowScreen.kt").readText()

    @Test
    fun `LiveNowCard 体内只能有一个可点节点`() {
        // 非空性锚点：扫到的确实是直播卡片实现文件
        assertTrue("扫错文件了（找不到 LiveNowCard）：${screenText.take(80)}", screenText.contains("fun LiveNowCard("))

        val body = GuardSourceScan.functionBody(screenText, "fun LiveNowCard(")
        assertEquals(
            "LiveNowCard 里出现多个 .clickable( —— 整卡与内层按钮的语义重叠缺陷（P2-25）回来了",
            1,
            Regex("""\.clickable\s*\(""").findAll(body).count(),
        )
        assertTrue(
            "剩下的那一个必须是整卡级别的 onClickLabel = CardSemantics.ACTION_LABEL（读屏需要动作名）",
            Regex("""\.clickable\s*\(\s*onClickLabel\s*=\s*CardSemantics\s*\.\s*ACTION_LABEL""").containsMatchIn(body),
        )
        // 非空性锚点：函数体真的截到了（这里用**代码**形状，字符串字面量已被剔掉）
        assertTrue("函数体像是空的：${body.take(120)}", body.contains("minimumInteractiveComponentSize()"))
    }

    @Test
    fun `语义合并仍在整卡上`() {
        val body = GuardSourceScan.functionBody(screenText, "fun LiveNowCard(")
        assertTrue(
            "整卡必须 mergeDescendants = true，否则内层文本/图标不会合并成一个节点",
            Regex("""\.semantics\s*\(\s*mergeDescendants\s*=\s*true\s*\)""").containsMatchIn(body),
        )
    }

    private fun productionFile(relative: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        return listOf(
            File("src/main/java/com/nbalive/app/$relative"),
            File("app/src/main/java/com/nbalive/app/$relative"),
            File(cwd, "src/main/java/com/nbalive/app/$relative"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${File(cwd).absolutePath}）—— 守卫会静默全绿，所以直接失败")
    }
}