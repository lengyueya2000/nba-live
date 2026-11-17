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
        // ⚠️ v1.2.26 守卫修正：原来这几条是对全文的 `contains(...)` ——
        // 命令被移进 `if: false` 的 step、移进 YAML 块标量的注释行、或只留在
        // artifact 名称里，判据照样全绿（"存在性"被钉住、"实际会执行"没被钉住）。
        // 现在只认**无门控 step 里的 run 脚本**。
        val ungated = steps(ci.readText()).filter { it.condition == null }.mapNotNull { it.run }
        assertTrue(
            "ci.yml 没有可执行的 run step 在跑 debug 单测（B6；文件级 contains 会被 if:false/注释骗过）",
            ungated.any { "testDebugUnitTest" in it },
        )
        assertTrue(
            "ci.yml 没有可执行的 run step 在跑 release 变体单测 —— release 侧的 BuildConfig / 压缩路径改动会漏网（B6）",
            ungated.any { "testReleaseUnitTest" in it },
        )
        assertTrue(
            "ci.yml 没有可执行的 run step 在跑 assembleDebug —— debugImplementation 与 debug 的 manifest/dex 合并完全没被覆盖（B6）",
            ungated.any { "assembleDebug" in it },
        )
        assertTrue(
            "ci.yml 的 `chmod +x gradlew` 不在可执行 step 里：模式位一旦不是 100755，ubuntu 上第一步就 Permission denied",
            ungated.any { "chmod +x gradlew" in it },
        )
        assertTrue(
            "ci.yml 没有 job 级超时上限：挂住的步骤会占满默认 360 分钟上限（B9 的同款理由）。" +
                "必须是真实的 `timeout-minutes: <数字>` 行（注释里提一句不算）",
            Regex("""^[ \t]*timeout-minutes:[ \t]*\d+""", setOf(RegexOption.MULTILINE))
                .containsMatchIn(stripFullLineComments(ci.readText())),
        )
    }

    @Test
    fun `日常 CI 与发版流水线并存 不许把发版那套删掉`() {
        // 反向判据：新增 ci.yml 时最容易犯的错是"把 release.yml 改造成通用工作流"，
        // 于是发版闸（签名凭据、mapping 只作 artifact、产物版本核对）一起没了。
        // ⚠️ 同样只认无门控 step 的 run 脚本（v1.2.26，见上一条测试的理由）。
        val runs = steps(repoFile("release.yml").readText())
            .filter { it.condition == null }
            .mapNotNull { it.run }
        assertTrue("release.yml 没有可执行的 assembleRelease step —— 发版闸没了", runs.any { "assembleRelease" in it })
        assertTrue("release.yml 没有可执行 step 在校验产物 versionCode（H1）", runs.any { "versionCode" in it })
    }

    /**
     * step 解析器自身的自检（守卫纪律：判据本体也要被钉住）。
     *
     * 三种"文件级 contains 会被骗"的伪装必须全部识破，
     * 而 inline run 与块标量 run 必须都能解析到 —— 否则上面的判据是单向偏绿。
     */
    @Test
    fun `step 解析必须识破注释与 if 门控三种伪装`() {
        val disguised = """
            jobs:
              x:
                steps:
                  - name: 伪装一 命令只写在注释里
                    # run: ./gradlew :app:testDebugUnitTest
                  - name: 伪装二 被 if 门控永不执行
                    if: false
                    run: ./gradlew :app:testDebugUnitTest
                  - name: 伪装三 只在 artifact 名称里出现
                    uses: actions/upload-artifact@v4
                    with:
                      name: testDebugUnitTest-dummy
                      path: .
        """.trimIndent()
        val ungated = steps(disguised).filter { it.condition == null }.mapNotNull { it.run }
        assertTrue("伪装命令不许算数（注释 / if:false / uses-with 都不是可执行 run）", ungated.none { "testDebugUnitTest" in it })

        val real = """
            jobs:
              x:
                steps:
                  - name: 真的在跑 inline
                    run: ./gradlew :app:testDebugUnitTest
                  - name: 真的在跑 块标量
                    run: |
                      ./gradlew :app:testReleaseUnitTest
                      echo done
        """.trimIndent()
        val realRuns = steps(real).mapNotNull { it.run }
        assertTrue("inline run 必须解析到", realRuns.any { "testDebugUnitTest" in it })
        assertTrue("块标量 run 必须解析到", realRuns.any { "testReleaseUnitTest" in it })
    }

    // ---------------------------------------------------------------

    /**
     * 一个 step 的解析结果：`condition` 是 `if:` 原文（null = 无门控、总会执行），
     * `run` 是脚本正文（inline 与 `|`/`>` 块标量都展开；没有 run = null）。
     */
    private data class Step(val name: String?, val condition: String?, val run: String?)

    /**
     * 行级 step 解析器（**刻意不是**完整 YAML 解析器 —— 与仓库既有守卫同一取舍）。
     *
     * 只认 GitHub workflow 的实际书写形态：`- ` 起一个 step，键在 step 正文缩进上；
     * `with:` 之类更深的嵌套键**不算** step 键（artifact 名字里出现 gradle 任务名
     * 不能满足"任务真的在跑"，这正是文件级 contains 被骗的第三种伪装）。
     * 整行注释直接跳过（伪装一）。
     */
    private fun steps(text: String): List<Step> {
        val lines = text.lines()
        val out = ArrayList<Step>()
        var i = 0
        var name: String? = null
        var condition: String? = null
        var run: String? = null
        var keyIndent = -1
        fun flush() {
            if (name != null || condition != null || run != null) out += Step(name, condition, run)
            name = null; condition = null; run = null; keyIndent = -1
        }
        fun parseKey(body: String, atIndent: Int) {
            val m = Regex("""^([A-Za-z0-9_-]+):\s*(.*)$""").find(body) ?: return
            val key = m.groupValues[1]
            val value = m.groupValues[2].trim()
            when (key) {
                "name" -> if (name == null) name = value
                "if" -> condition = value
                "run" ->
                    run = if (value.startsWith("|") || value.startsWith(">")) {
                        val sb = StringBuilder()
                        while (i < lines.size) {
                            val next = lines[i]
                            val nt = next.trimStart()
                            val ni = next.length - nt.length
                            if (nt.isEmpty()) { i++; sb.append('\n') }
                            else if (ni > atIndent) { sb.append(nt).append('\n'); i++ }
                            else break
                        }
                        sb.toString()
                    } else {
                        value
                    }
            }
        }
        while (i < lines.size) {
            val raw = lines[i]
            i++
            val t = raw.trimStart()
            if (t.isEmpty() || t.startsWith("#")) continue
            val indent = raw.length - t.length
            if (t.startsWith("- ") || t == "-") {
                flush()
                keyIndent = indent + 2
                parseKey(t.removePrefix("- ").trim(), keyIndent)
            } else if (indent == keyIndent) {
                parseKey(t, keyIndent)
            }
            // 其它缩进：job 级键（更浅）与 with:/uses 嵌套（更深）都不是 step 键
        }
        flush()
        return out
    }

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