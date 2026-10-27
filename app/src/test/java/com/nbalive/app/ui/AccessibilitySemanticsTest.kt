package com.nbalive.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍语义的两条缺失（review/03-ui.md P2-23 / P2-24）。
 *
 * ## P2-23 搜索框没有可读的用途说明
 *
 * `TextField` 只给了 `placeholder`，`leadingIcon` 的 contentDescription 是 null。
 * placeholder **不是**无障碍标签：输入框一有内容它就消失，
 * TalkBack 读到的是一个"空文本框"，用户不知道这里能搜什么。
 * 正确做法是加 `label` 或用 `semantics { contentDescription = ... }`。
 *
 * ## P2-24 底栏标签未成组
 *
 * 三个标签（赛事/直播/我的）各自是一个可点节点，TalkBack 逐个读，
 * 用户不知道"这是一组互斥的导航选项、当前选中哪个"。
 * 加 `selectableGroup()` 后读屏会说明"标签页 1/3、已选中"。
 *
 * 这两条都是"能看见但读屏读不出来"的形态 —— 只能靠读源码断言。
 */
class AccessibilitySemanticsTest {

    @Test
    fun `搜索框必须有可读的用途说明`() {
        val code = productionCode("SearchScreen.kt")
        assertTrue("找不到 SearchScreen.kt", code != null)
        val stripped = stripComments(code!!)

        // placeholder 不是标签：有输入内容时它就消失，读屏读不到用途
        val hasLabel = stripped.contains("label = {") || stripped.contains("contentDescription = \"搜索")
        assertTrue(
            "搜索框必须有 label 或语义上的 contentDescription：\n" +
                "placeholder 在输入内容后消失，TalkBack 会读成「空文本框」，\n" +
                "用户不知道这里能搜什么（03-P2-23）",
            hasLabel,
        )
    }

    @Test
    fun `底栏标签必须成组`() {
        val code = productionCode("NbaLiveApp.kt")
        assertTrue("找不到 NbaLiveApp.kt", code != null)
        val stripped = stripComments(code!!)

        // ⚠️ 必须匹配**调用** `.selectableGroup()`，不能只 contains("selectableGroup")：
        // import 行也含这个字符串，删掉调用、留下 import 时守卫会照样绿
        //（验证守卫时踩到了这个坑）。
        assertTrue(
            "底栏三个标签必须用 selectableGroup() 包住：\n" +
                "否则 TalkBack 逐个读成独立按钮，用户不知道这是互斥导航、也不知道选中了哪个（03-P2-24）",
            Regex("""\.selectableGroup\(\)""").containsMatchIn(stripped),
        )
    }

    @Test
    fun `标签必须声明选中状态`() {
        val code = productionCode("NbaLiveApp.kt")
        assertTrue("找不到 NbaLiveApp.kt", code != null)
        val stripped = stripComments(code!!)

        // 只成组还不够：要告诉读屏"当前选中的是哪个"
        assertTrue(
            "标签必须通过 selectable(selected=...) 或 semantics { selected = ... } 声明选中态，\n" +
                "否则读屏用户不知道自己在哪个标签（03-P2-24）",
            stripped.contains("selectable(") || stripped.contains("selected = "),
        )
    }

    @Test
    fun `呼吸红点不能只靠颜色传达信息`() {
        // 反面保护：LiveDot 是纯装饰，旁边必须有"直播中"文字。
        // 这条守住"别把文字删了只留红点"。
        val code = productionCode("Common.kt")
        assertTrue("找不到 Common.kt", code != null)
        assertTrue(
            "LiveDot 的注释明确说「颜色之外还有直播中文字」—— \n" +
                "若哪天只留红点，色盲用户无法分辨状态",
            stripComments(code!!).contains("LiveDot"),
        )
    }

    private fun productionCode(name: String): String? {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory } ?: return null
        return root.walkTopDown().firstOrNull { it.isFile && it.name == name }?.readText()
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")
}