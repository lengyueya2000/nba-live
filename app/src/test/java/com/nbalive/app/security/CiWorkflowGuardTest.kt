package com.nbalive.app.security

import com.nbalive.app.TestPaths
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日常 CI 守卫 —— review/optimization-review.md **B6**。
 *
 * ## 事故形态
 *
 * 此前仓库**只有**一个工作流 `release.yml`，且它是 `v*` tag 触发的：
 * 714 条用例在两次发版之间一次都不跑；等发版当天变红时，tag 已经推出去、
 * `release: vX.Y.Z` 已经在写，修复路径变成"删 tag 重推"。
 * 而 release 流水线**从不**跑 `assembleDebug`：`debugImplementation("…ui-tooling")`
 * 这类只在 debug 存在的依赖、debug 的多 dex 合并坏了，也照样全绿。
 *
 * ## 判据口径
 *
 * 1. 断言前先剔掉**整行注释**（否则"我们在注释里说过要跑 assembleDebug"就能骗过判据）；
 * 2. 反向判据只看 `on:` 块的具体触发器，而不是文件里出现过 `pull_request`；
 * 3. 找不到文件一律 `error()` 硬失败（`review/audit-guards.md` A3/A10 的形态）。
 */
class CiWorkflowGuardTest {

    @Test
    fun `日常 CI 必须存在 且由 push 与 PR 触发`() {
        val code = stripFullLineComments(ci.readText())
        val onBlock = code.lines()
            .dropWhile { it.trim() != "on:" }
            .takeWhile { it.isBlank() || it.startsWith(" ") || it.trim() == "on:" }
            .joinToString("\n")
        assertTrue(
            "ci.yml 的 `on:` 块里找不到 `push:` —— 日常改动不会触发 CI，714 条用例又只剩发版日才跑（B6）",
            onBlock.contains("push"),
        )
        assertTrue(
            "ci.yml 的 `on:` 块里找不到 `pull_request` —— 合并前的检查缺一道（B6）",
            onBlock.contains("pull_request"),
        )
        // 负控：注释里提到触发器不算数（否则"在注释里写 push:"就能骗过上面两条）。
        assertFalse(
            "剔整行注释那一步没生效 —— 注释里的 `push:` 会被当成真触发器",
            stripFullLineComments("#  on:\n#    push:").contains("push"),
        )
    }

    @Test
    fun `日常 CI 必须跑两个变体的单测与 debug 打包`() {
        val code = stripFullLineComments(ci.readText())
        assertTrue("ci.yml 没跑 debug 单测", code.contains("testDebugUnitTest"))
        assertTrue(
            "ci.yml 没跑 release 变体单测 —— release 侧的 BuildConfig / 压缩路径改动会漏网（B6）",
            code.contains("testReleaseUnitTest"),
        )
        assertTrue(
            "ci.yml 没跑 assembleDebug —— debugImplementation 与 debug 的 manifest/dex 合并完全没被覆盖（B6）",
            code.contains("assembleDebug"),
        )
        assertTrue(
            "ci.yml 没有超时上限：挂住的步骤会占满默认 360 分钟上限（B9 的同款理由）",
            code.contains("timeout-minutes"),
        )
        assertTrue(
            "ci.yml 没有 chmod +x gradlew：模式位一旦不是 100755，ubuntu 上第一步就 Permission denied",
            code.contains("chmod +x gradlew"),
        )
    }

    @Test
    fun `日常 CI 与发版流水线并存 不许把发版那套删掉`() {
        // 反向判据：新增 ci.yml 时最容易犯的错是"把 release.yml 改造成通用工作流"，
        // 于是发版闸（签名凭据、mapping 只作 artifact、产物版本核对）一起没了。
        val release = repoFile("release.yml").readText()
        assertTrue("release.yml 不见了 —— 发版闸没了", release.contains("assembleRelease"))
        assertTrue("release.yml 丢了对产物版本号的断言（H1）", release.contains("versionCode"))
    }

    // ---------------------------------------------------------------

    /**
     * 剔掉**整行**注释（`#` 起首的行；YAML 里行尾 `#` 需要引号感知，
     * 本守卫只依赖"整行注释不能冒充命令"这一条，故不处理行尾形态）。
     */
    private fun stripFullLineComments(text: String): String =
        text.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("#")) "" else line
        }

    private val ci: File get() = repoFile("ci.yml")

    /**
     * `.github/workflows/<name>`。
     *
     * Gradle 单测 cwd 是 `app/`，所以先试 `../.github/...`；也兼容从仓库根跑。
     * 找不到就 `error()` 硬失败（守卫静默全绿比误报危险得多）。
     */
    private fun repoFile(name: String): File {
        val cwd = File(TestPaths.workingDirPath())
        val candidates = listOf(
            File("../.github/workflows/$name"),
            File(".github/workflows/$name"),
            File(cwd, "../.github/workflows/$name"),
            File(cwd, ".github/workflows/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 .github/workflows/$name（cwd=${cwd.absolutePath}）—— " +
                    "本文件所有判据会静默全绿，所以直接失败而不是跳过",
            )
    }
}