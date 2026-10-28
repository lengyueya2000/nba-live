package com.nbalive.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表顶部横幅的呈现规则（review/02-data.md P1-2）。
 *
 * ## 要解决什么
 *
 * 单源失败时用户只看到"场次变少了"：`MatchRepository` 早就把
 * `failedProviders` 算出来了（`SourceOutcome`），`MainViewModel` 也把它
 * 落进了 `partialError` —— 但**没有任何 UI 读它**。
 * `LoadOutcomeTest` 断言了那串文案，用户一个像素都看不到。
 * 这是审查点名的典型形态：工具函数/字段有测试，"使用纪律"没有测试。
 *
 * 所以这里测两件事：
 * 1. **规则**：错误与半失败同时存在时显示哪一条（纯判据，可单测）；
 * 2. **接线**：`partialError` 必须有 UI 读取点（源码扫描兜底，
 *    防止它再次退化成"算了但没人用"）。
 */
class ListNoticePolicyTest {

    @Test
    fun `两条都没有时不显示任何横幅`() {
        assertEquals(ListNotice.None, ListNoticePolicy.of(error = null, partialError = null))
    }

    @Test
    fun `半失败时显示部分来源缺失的提示`() {
        val notice = ListNoticePolicy.of(error = null, partialError = "JRS 源暂时不可用，仅显示部分场次")
        assertTrue("半失败必须是可见提示，而不是静默少几场: $notice", notice is ListNotice.Partial)
        assertEquals(
            "JRS 源暂时不可用，仅显示部分场次",
            (notice as ListNotice.Partial).message,
        )
    }

    @Test
    fun `整次刷新失败优先于半失败`() {
        // 刷新失败是"这次请求整个没成功"，可重试；半失败是"某一条源缺了"。
        // 同时存在时显示可重试的那条更有用 —— 两条叠着显示只是噪音。
        val notice = ListNoticePolicy.of(error = "网络异常", partialError = "JRS 源暂时不可用")
        assertTrue("整次失败必须优先展示（它是可重试的那条）: $notice", notice is ListNotice.Failed)
        assertEquals("网络异常", (notice as ListNotice.Failed).message)
    }

    @Test
    fun `横幅文案不与刷新失败前缀混用`() {
        // 「刷新失败：」这个前缀只属于整次失败。
        // 半失败若也套这个前缀，用户会以为整次刷新都失败了，
        // 而实际只是少了一个源 —— 文案必须能区分两种情形。
        val partial = ListNoticePolicy.of(error = null, partialError = "JRS 源暂时不可用")
        assertTrue(
            "半失败文案不能带「刷新失败」前缀: ${(partial as ListNotice.Partial).message}",
            !partial.message.startsWith("刷新失败"),
        )
    }

    // ---------- 接线守卫 ----------

    @Test
    fun `partialError 必须有 UI 读取点`() {
        val root = sourceRoot()
        // ⚠️ 硬失败而不是跳过（审计 A10）：源码根是"partialError 有没有人读"**唯一**的证据来源，
        // 解析不到时这条守卫会"零读取点"地静默全绿（而它正是为了防止"算好了没人用"）。
        assertTrue(
            "找不到 app/src/main/java（cwd=${File(System.getProperty("user.dir") ?: ".").absolutePath}）—— " +
                "锚点缺失时这条接线守卫会静默全绿，所以直接失败而不是跳过",
            root != null,
        )
        val uiFiles = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("${File.separator}ui${File.separator}") }
            .toList()
        assertTrue(
            "ui 源码只扫到 ${uiFiles.size} 个文件 —— 范围走偏时这条守卫会静默全绿，" +
                "所以直接失败而不是跳过",
            uiFiles.size >= 20,
        )

        val readers = uiFiles.filter { f ->
            f.readText().contains("partialError") && !f.name.startsWith("ListNotice")
        }
        assertTrue(
            "partialError 在数据层算好了，却没有任何 ui 文件读取 —— " +
                "用户只会看到「场次变少了」，无法区分「源挂了」和「今天没比赛」。\n" +
                "命中文件：${readers.map { it.name }}",
            readers.isNotEmpty(),
        )
    }

    private fun sourceRoot(): File? {
        val candidates = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }
}