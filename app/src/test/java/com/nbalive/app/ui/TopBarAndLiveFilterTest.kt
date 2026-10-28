package com.nbalive.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏按钮的唯一来源（review/03-ui.md P2-22）与 LIVE 过滤的唯一来源（P2-10）。
 *
 * ## P2-22：三份"方形图标按钮"
 *
 * `NbaLiveApp.TopIconButton`（40dp 视觉 + 最小触摸目标）、`SubPageTopBar` 里的
 * 返回键 / 「更多」（同样 40dp）、`SearchScreen` 的返回键（直接 48dp）——
 * 同一个"顶栏按钮"在三个地方长了三个样子，视觉走查一眼能看出来
 * （搜索页返回键比列表页顶栏按钮大一圈）。
 *
 * 现在统一到 `components/Common.kt` 的 `SquareIconButton`（视觉 40dp、触摸 48dp）。
 *
 * ## P2-10：LIVE 过滤两处各写一份
 *
 * `NbaLiveApp` 为播放页的"同时在播"面板算了一份 `liveMatches`，
 * `LiveNowScreen` 又拿完整列表自己过滤一份 —— 同一语义两处实现，
 * 两次 O(n) 扫描 + 两份 List 分配，还会各自漂移。
 * 现在由调用方把算好的列表传进去。
 *
 * ## 为什么是源码守卫
 *
 * "少写一个共享调用"不会产生任何编译期/运行期信号：代码照样跑，
 * 只是又一个页面的按钮尺寸和别人不一样；过滤多算一遍也只是白花 CPU。
 * 这正是本项目反复踩的形态（函数有测试、使用纪律没有测试）。
 */
class TopBarAndLiveFilterTest {

    @Test
    fun `方形图标按钮只能有一份实现 且在 components 里`() {
        val impls = uiSources().filter { f ->
            Regex("""fun SquareIconButton\(""").containsMatchIn(stripComments(f.readText()))
        }
        assertTrue(
            "SquareIconButton 只应有一份实现（ui/components/），实际出现在：\n" +
                impls.joinToString("\n") { "  ${it.name}" },
            impls.size == 1 && impls.single().parentFile?.name == "components",
        )
    }

    @Test
    fun `不得再各写一份方形按钮`() {
        // "方尺寸 + 按钮圆角" 就是这几份副本的形状：40dp 那份（顶栏/二级页）和
        // 48dp 那份（搜索页返回键）都算。components/Common.kt 里那份用的是
        // SquareIconButtonVisualSize 常量，且本测试排除了 Common.kt。
        val shape = Regex(
            """\.size\((40|48)\.dp\)[\s\S]{0,160}?RoundedCornerShape\(Radius\.btn\)""" +
                """|RoundedCornerShape\(Radius\.btn\)[\s\S]{0,160}?\.size\((40|48)\.dp\)""",
        )
        val offenders = uiSources()
            .filter { it.name != "Common.kt" }
            .filter { shape.containsMatchIn(stripComments(it.readText())) }
            .map { it.name }
        assertTrue(
            "以下文件里又出现了一份「方尺寸 + 按钮圆角」的私有按钮实现：\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n请改用 components 的 SquareIconButton（review/03-ui.md P2-22）；" +
                "如果它确实不是顶栏按钮，请换用语义不同的尺寸来源并在注释里说明。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `共享方形按钮必须真的接到顶栏与搜索页上`() {
        // 防"抽出来了没人用"（审查反复点名的形态）
        val live = stripped("NbaLiveApp.kt")
        val search = stripped("SearchScreen.kt")
        assertTrue(
            "NbaLiveApp 里没有用 SquareIconButton：顶栏按钮又写回私有实现了（P2-22）",
            live.contains("SquareIconButton("),
        )
        assertTrue(
            "SearchScreen 的返回键没有用 SquareIconButton（P2-22）",
            search.contains("SquareIconButton("),
        )
        assertTrue(
            "NbaLiveApp 里的 TopIconButton 私有实现在改名之后又回来了（P2-22）",
            !live.contains("fun TopIconButton"),
        )
    }

    @Test
    fun `直播页不得自己再过滤一遍 LIVE`() {
        val code = stripped("LiveNowScreen.kt")
        assertTrue(
            "LiveNowScreen 里又出现了自己过滤 LIVE 的代码：NbaLiveApp 已经算过一份，\n" +
                "同一语义两处实现会各自漂移，且白做一次 O(n) 扫描（review/03-ui.md P2-10）",
            !Regex("""filter\s*\{\s*it\.status\s*==\s*[A-Za-z0-9_.]*MatchStatus\.LIVE""").containsMatchIn(code),
        )
    }

    @Test
    fun `NbaLiveApp 必须把算好的 liveMatches 传给直播页`() {
        val live = stripped("NbaLiveApp.kt")
        // ⚠️ 必须只看 LiveNowScreen 那个调用点：`liveMatches = liveMatches` 在
        // 播放页（PlayerScreen）的"同时在播"面板那里也合法存在 ——
        // 早先写成"全文件 contains(...)"时，把直播页改回传完整列表**抓不到**。
        val call = Regex("""LiveNowScreen\(([\s\S]*?)\n\s*\)""").find(live)
            ?: error("找不到 LiveNowScreen(...) 调用点，守卫会静默全绿")
        assertTrue(
            "LiveNowScreen 的调用点没有传 liveMatches = liveMatches（P2-10 的接线断了）：\n" +
                "直播页收到的又是完整列表，于是只能自己再过滤一遍。实际调用：\n${call.value}",
            call.groupValues[1].contains("liveMatches = liveMatches"),
        )
    }

    @Test
    fun `扫描必须真的扫到源码`() {
        val files = uiSources()
        assertTrue("扫描不到 ui 源码，上面几条会静默全绿", files.size >= 10)
        for (name in listOf("NbaLiveApp.kt", "LiveNowScreen.kt", "SearchScreen.kt", "Common.kt")) {
            assertTrue("没扫到 $name", files.any { it.name == name })
        }
    }

    // ---------------------------------------------------------------

    private fun stripped(name: String): String {
        val f = uiSources().firstOrNull { it.name == name }
            ?: error("找不到 $name，守卫会静默全绿")
        return stripComments(f.readText())
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

    private fun stripComments(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")
}