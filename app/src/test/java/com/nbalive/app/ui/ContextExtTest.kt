package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主 Activity 取法的唯一来源（review/03-ui.md P2-21）。
 *
 * ## 要解决什么
 *
 * 工程里有两套写法并存：
 *  - `ui/NbaLiveApp.kt` 的 `Context.findActivity()`（递归 `ContextWrapper` 链）；
 *  - `ui/theme/Theme.kt` 的 `(view.context as? Activity)`（直接强转）。
 *
 * 强转那套遇到 `ContextThemeWrapper` 之类的包装会**静默失败**，
 * 调用方又写成 `?: return@SideEffect`，于是症状是"状态栏图标不跟随主题"——
 * 这种缺陷没有编译期、也没有运行期信号。
 *
 * 现在收敛到 `ui/ContextExt.kt` 一份，两处共用。
 *
 * ## 为什么不写纯判据
 *
 * 递归本身需要 `android.content.Context` 实例，本工程的单测是纯 JVM 单测
 * （没有 Robolectric），造不出可信的包装链。所以判据落在**源码结构**上：
 * 只允许一份实现、且不许再出现强转写法 —— 这正是缺陷的形态本身。
 */
class ContextExtTest {

    @Test
    fun `findActivity 只能有一份实现 且在 ContextExt 里`() {
        val impls = uiSources().filter { f ->
            Regex("""fun\s+[A-Za-z0-9_.]*Context\.findActivity\(""").containsMatchIn(stripComments(f.readText()))
        }
        assertTrue(
            "`findActivity` 只应有一份实现（ui/ContextExt.kt），实际出现在：\n" +
                impls.joinToString("\n") { "  ${it.name}" } +
                "\n两份实现意味着'哪个更可靠'没有统一答案，后来人会照抄离自己近的那份。",
            impls.size == 1 && impls.single().name == "ContextExt.kt",
        )
    }

    @Test
    fun `不得再用 as Activity 强转取宿主`() {
        val offenders = uiSources().mapNotNull { f ->
            val code = stripComments(f.readText())
            val hits = code.lines().withIndex()
                .filter { (_, l) -> Regex("""as\?\s*Activity\b""").containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1}  ${l.trim()}" }
            if (hits.isEmpty()) null else hits.joinToString("\n")
        }
        assertTrue(
            "以下位置又用 `as? Activity` 直接强转：遇到 ContextWrapper 包装会静默失败，\n" +
                "表现为'状态栏图标不跟随主题'（review/03-ui.md P2-21）。请用 ContextExt 的 findActivity()：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `主题里的系统栏配色必须走共用的 findActivity`() {
        // 审计「未验证的怀疑 #7」：按文件名取第一个 → 出现同名文件时会静默守错文件。
        // 现在要求唯一命中，否则 GuardSourceScan.uniqueFile 直接 error()。
        val theme = GuardSourceScan.uniqueFile(uiSources(), "Theme.kt")
        val code = stripComments(theme.readText())
        assertTrue(
            "Theme.kt 里没有用 findActivity()：宿主 Activity 的取法又变成就地强转了（P2-21）",
            code.contains(".findActivity()"),
        )
    }

    @Test
    fun `扫描必须真的扫到 ui 源码`() {
        val files = uiSources()
        assertTrue("扫描不到 ui 源码，上面几条会静默全绿", files.size >= 10)
        assertTrue(
            "没扫到 ContextExt.kt（守卫的锚点文件不存在就说明扫描范围错了）",
            files.any { it.name == "ContextExt.kt" },
        )
    }

    private fun uiSources(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui 源码目录，cwd=$cwd，候选=${roots.map { it.path }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)
}