package com.nbalive.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tag 触发发版流水线的守卫（review/04-security.md 04-P2-5 的自动化版）。
 *
 * ## 拦的是什么
 *
 * README 的「发版清单」原来是**手工**执行的（v1.2.13 就是这么发的）：
 * 版本号 -> 两变体单测 -> 打包 -> apksigner 验签 -> 归档 APK + mapping -> 打 tag。
 * 手工流程的缺口是「漏一步没人知道」，而漏掉的每一步都对应一类发布事故：
 *  - 单测没跑：把坏版本发出去；
 *  - 签名凭据没写：产出一个装不上的包（或静默产出未签名包）；
 *  - 没验签：APK 存在 != 签名正确；
 *  - mapping 没归档：线上崩溃只剩 `a.b.c`，这个版本永久失去可诊断性；
 *  - **mapping 传上公开 Release**：混淆名 -> 原始名的全量对照表公开，R8 混淆作废。
 *
 * 最后一条是**反向**要求（不许做某事），也正是最容易被「写得好听」骗过的：
 * 在 YAML 注释里写一句 `# mapping 只作 artifact`，文件级 `contains` 照样绿。
 * 所以本文件的判据一律建立在**剔注释后的真实代码**与**步骤块**之上。
 *
 * ## 为什么不用现成的 [GuardSourceScan.stripNonCode]
 *
 * 那是 Kotlin 词法分析器：YAML 里的 `- 'v*'` 会被它当成 Kotlin 字符字面量整段剔掉，
 * `run: curl https://x#y` 里的 `#` 会被它当成行注释。这里必须自己实现
 * **YAML 语义**的剔注释（[stripYamlComments]），并有自检证明
 * 「`#` 在 URL/引号里不算注释」。
 *
 * ## 三条铁律（与全仓守卫一致）
 *
 * 1. 断言前先 [stripYamlComments]（判断的是真实命令，不是注释里的说法）；
 * 2. 反向判据用**步骤块级**判断（[stepBlocks]），不用文件级 `contains`；
 * 3. 路径/结构找不到一律 `error()` 硬失败，绝不静默跳过；
 *    锚点测试（workflow 存在、行数 >30、`secrets.` ≥3 次）兜住「零命中的空扫」。
 *
 * ⚠️ **本文件只做静态核对**：GitHub Actions 无法在本地运行，
 * 所以它证明的是「workflow 文本里有这些真实步骤、且没有把 mapping 混进发布」，
 * 不能证明流水线在真实 runner 上跑通（那要等第一次真推 tag）。
 */
class ReleasePipelineGuardTest {

    // ---------- ① 存在性 / 触发条件 / 非空性锚点 ----------

    @Test
    fun `workflow 必须存在 且由 tag 触发`() {
        val raw = workflowText()
        assertTrue(
            "release.yml 只有 ${raw.lines().size} 行（应 >30）—— " +
                "扫描范围或内容走偏时，本文件所有判据会静默全绿",
            raw.lines().size > 30,
        )
        val code = stripYamlComments(raw)
        val secretRefs = countOccurrences(code, "secrets.")
        assertTrue(
            "workflow 里 secrets. 只出现 $secretRefs 次（应 ≥3）—— " +
                "签名凭据注入被删掉后，发版会拿不到 keystore",
            secretRefs >= 3,
        )
        assertTrue(
            "workflow 必须由 tag 推送触发（on.push.tags 含 v*）",
            Regex("""tags:\s*\n\s*-\s*['"]?v\*['"]?""").containsMatchIn(code),
        )
        assertTrue(
            "必须支持 workflow_dispatch 演练（否则只能靠真推 tag 才能验证流水线）",
            code.contains("workflow_dispatch:"),
        )
    }

    /**
     * ⚠️ 这一类失败**只在真实 runner 上才会暴露**，而且发生在整条流水线的第一步：
     * `gradlew` 在 git 里的模式位若不是 100755，ubuntu 上执行 `./gradlew` 会直接
     * `Permission denied`（exit 126）。本地 Windows 永远看不出来（NTFS 没有这个位），
     * 静态核对也看不出来 —— 第一次推 tag 才炸。所以要求 workflow **自己**先
     * `chmod +x gradlew`：把"仓库模式位"从"靠克隆方保留执行位"变成"流水线自己保证"。
     *
     * （本判据能红：实测把这一行删掉后本用例变红，见提交说明。）
     */
    @Test
    fun `workflow 必须先保证 gradlew 可执行`() {
        val code = stripYamlComments(workflowText())
        assertTrue(
            "workflow 里没有 `chmod +x gradlew`：checkout 一旦丢掉可执行位（或有人下载 zip 取代码），" +
                "`./gradlew` 会在 ubuntu 上 Permission denied，第一次推 tag 就整条失败（04-P2-5）",
            Regex("""chmod\s+\+x\s+gradlew""").containsMatchIn(code),
        )
        assertFalse(
            "把 chmod 只写进 echo/字符串里不算（必须是真执行的命令）——「写得好听」骗过守卫的老形态",
            Regex("""echo\s+["']?chmod\s+\+x\s+gradlew""").containsMatchIn(code),
        )
    }

    // ---------- ② 正向：发版步骤必须齐全，测试不过不许发 ----------

    @Test
    fun `发版步骤必须齐全 且测试不过不许发`() {
        val code = stripYamlComments(workflowText())
        val steps = stepBlocks(code)

        val testStep = stepOrFail(
            steps,
            "testDebugUnitTest",
            "没有任何步骤跑单测：测试不过就不许发（这正是手工清单最容易漏的一步）",
        )
        assertTrue(
            "测试步骤必须同时跑 release 变体（release 的 BuildConfig / 压缩路径会被改坏）",
            testStep.contains("testReleaseUnitTest"),
        )
        assertTrue(
            "测试必须带 --rerun-tasks：否则增量缓存会把新写的用例当「已验证」",
            testStep.contains("--rerun-tasks"),
        )

        val buildStep = stepOrFail(steps, "assembleRelease", "缺少 assembleRelease 步骤：不产 APK 就发不了版")
        assertTrue(
            "同一个打包步骤必须同时产出 AAB（bundleRelease）—— 否则只发 APK 或漏发 AAB",
            buildStep.contains("bundleRelease"),
        )

        val verifyStep = stepOrFail(
            steps,
            "apksigner",
            "缺少 apksigner 验签步骤：产出 app-release.apk 本身不代表它被正确签名",
        )
        assertTrue(
            "apksigner 必须带 --print-certs：证书指纹要能进 job summary 供人工复核",
            verifyStep.contains("--print-certs"),
        )

        assertTrue(
            "workflow 里找不到 mapping.txt —— R8 映射没人归档，线上崩溃堆栈只剩 a.b.c",
            code.contains("mapping.txt"),
        )

        assertTrue(
            "缺少 Release 创建/更新（gh release 或 action-gh-release）",
            code.contains("gh release create") ||
                code.contains("gh release upload") ||
                code.contains("action-gh-release"),
        )

        assertTrue(
            "permissions 必须给 contents: write（否则 gh release 会被 403 拒绝）",
            code.contains("permissions:") && code.contains("contents: write"),
        )

        for (name in SECRET_NAMES) {
            assertTrue(
                "缺少 secret $name 的引用：签名凭据不全时必须明确失败，绝不产出未签名包",
                code.contains("secrets.$name"),
            )
        }
    }

    // ---------- ③ 反向：mapping 只能作 artifact，绝不进 Release assets ----------

    @Test
    fun `mapping 只能作 artifact 绝不许进 Release assets`() {
        val code = stripYamlComments(workflowText())
        val steps = stepBlocks(code)

        val mentioned = steps.filter { it.contains("mapping") }
        assertTrue(
            "没有任何步骤提到 mapping —— 归档步骤被删了，这条反向断言会静默全绿",
            mentioned.isNotEmpty(),
        )

        // 步骤块级判断：提到 mapping 的步骤必须就是 artifact 上传步骤
        val mixed = mentioned.filterNot { it.contains("upload-artifact") }
        assertTrue(
            "以下步骤把 mapping 和发布动作混在一起 —— 公开 Release 暴露混淆映射等于把混淆作废：\n" +
                mixed.joinToString("\n---\n"),
            mixed.isEmpty(),
        )

        val releaseSteps = steps.filter {
            it.contains("gh release") || it.contains("action-gh-release")
        }
        assertTrue(
            "找不到创建/更新 Release 的步骤 —— 下面的反向断言会静默全绿",
            releaseSteps.isNotEmpty(),
        )
        val releaseWithMapping = releaseSteps.filter { it.contains("mapping") }
        assertTrue(
            "Release 步骤里出现了 mapping —— 混淆映射绝不能挂公开 Release：\n" +
                releaseWithMapping.joinToString("\n---\n"),
            releaseWithMapping.isEmpty(),
        )
    }

    // ---------- ④ 判据自检：违规样本必须被抓住，注释不能骗过 ----------

    @Test
    fun `YAML 注释剔除自检 井号在 URL 与引号里不算注释`() {
        assertTrue(
            "把 URL 里的 # 当成了 YAML 注释 —— 剔过头会把真实命令砍掉，守卫从此变摆设",
            stripYamlComments("run: curl -fL https://x#y -o out").contains("https://x#y"),
        )
        assertTrue(
            "把引号字符串里的 # 当成了注释",
            stripYamlComments("name: \"stage # 1\"").contains("# 1"),
        )
        assertFalse(
            "行尾 # 注释没被剔掉",
            stripYamlComments("run: ./gradlew assembleRelease # 临时关掉").contains("临时关掉"),
        )
        assertFalse(
            "整行注释没被剔掉（正是「注释里写一句就能骗过守卫」的那类）",
            stripYamlComments("      # - run: ./gradlew :app:testDebugUnitTest --rerun-tasks")
                .contains("testDebugUnitTest"),
        )
    }

    @Test
    fun `判据自检 违规样本必须被抓住`() {
        // ① 测试步骤被整段注释掉：正向判据必须看不到它
        val commentedTests = listOf(
            "jobs:",
            "  release:",
            "    steps:",
            "      # - run: ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest --rerun-tasks",
            "      - run: ./gradlew :app:assembleRelease :app:bundleRelease",
        ).joinToString("\n")
        assertTrue(
            "测试步骤被注释掉后，判据仍能找到含 testDebugUnitTest 的步骤块 —— 会被注释骗过",
            stepBlocks(stripYamlComments(commentedTests)).none { it.contains("testDebugUnitTest") },
        )

        // ② mapping 混进 release 上传：反向判据必须变红
        val mappingInRelease = listOf(
            "jobs:",
            "  release:",
            "    steps:",
            "      - uses: actions/upload-artifact@v4",
            "        with:",
            "          path: app/build/outputs/mapping/release/mapping.txt",
            "      - uses: softprops/action-gh-release@v2",
            "        with:",
            "          files: |",
            "            app/build/outputs/apk/release/app-release.apk",
            "            app/build/outputs/mapping/release/mapping.txt",
        ).joinToString("\n")
        val badSteps = stepBlocks(stripYamlComments(mappingInRelease))
            .filter { it.contains("mapping") }
            .filterNot { it.contains("upload-artifact") }
        assertTrue(
            "mapping 混进 release assets 没有被反向判据抓住",
            badSteps.isNotEmpty(),
        )

        // ③ 步骤块切分自检：注释掉的步骤不算步骤块，且不能一路吃到下一个 job
        val twoSteps = listOf(
            "    steps:",
            "      - name: a",
            "        run: echo a",
            "        # 注释里写一句 testDebugUnitTest 不算步骤",
            "      - name: b",
            "        run: echo b",
            "otherJob:",
            "  key: v",
        ).joinToString("\n")
        val blocks = stepBlocks(stripYamlComments(twoSteps))
        assertTrue("步骤块切分不对（期望 2 块，实际 ${blocks.size}）：$blocks", blocks.size == 2)
        assertTrue(
            "步骤块切分吃过了 steps 列表（把下一个 job 也吞进来）",
            blocks.none { it.contains("otherJob:") },
        )
        assertTrue(
            "注释里的 testDebugUnitTest 被算进了步骤",
            blocks.none { it.contains("testDebugUnitTest") },
        )
    }

    // ---------------------------------------------------------------

    /**
     * YAML 安全的剔注释。
     *
     * 规则（YAML 语义）：`#` 只有在**行首（前导空白之后）**或**前面是空白**时
     * 才开始注释，且引号内的 `#` 一律不算。所以 `https://x#y` 原样保留。
     */
    private fun stripYamlComments(text: String): String =
        text.lines().joinToString("\n") { stripYamlLineComment(it) }

    private fun stripYamlLineComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace()) ->
                    return line.substring(0, i)
            }
        }
        return line
    }

    /**
     * 把 `steps:` 列表按**列表项缩进**切成步骤块（输入必须是剔注释后的代码）。
     *
     * 每块是一个完整的 step（`- name:` 到下一个同级 `- `，或 `steps` 列表结束）。
     * 反向判据必须在**块**上做：文件级 `contains` 分不清
     * 「mapping 出现在 artifact 步骤里」和「mapping 出现在 release 上传步骤里」。
     */
    private fun stepBlocks(code: String): List<String> {
        val lines = code.lines()
        val stepsIdx = lines.indexOfFirst { it.trim() == "steps:" }
        if (stepsIdx < 0) error("workflow 里找不到 `steps:` —— 判据会静默全绿，所以直接失败")

        val firstIdx = (stepsIdx + 1 until lines.size)
            .firstOrNull { lines[it].trimStart().startsWith("- ") }
        if (firstIdx == null) error("`steps:` 后面没有任何步骤项 —— 判据会静默全绿")

        val stepIndent = lines[firstIdx].takeWhile { it == ' ' }.length
        val blocks = mutableListOf<MutableList<String>>()
        for (i in firstIdx until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                blocks.lastOrNull()?.add(line)
                continue
            }
            val indent = line.takeWhile { it == ' ' }.length
            if (indent < stepIndent) break
            if (indent == stepIndent && line.trimStart().startsWith("- ")) {
                blocks.add(mutableListOf(line))
            } else {
                blocks.lastOrNull()?.add(line)
            }
        }
        if (blocks.isEmpty()) error("`steps:` 没解析出任何步骤块 —— 判据会静默全绿")
        return blocks.map { it.joinToString("\n") }
    }

    private fun stepOrFail(steps: List<String>, needle: String, why: String): String =
        steps.firstOrNull { it.contains(needle) } ?: error(why)

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun workflowText(): String = workflowFile().readText()

    /**
     * `.github/workflows/release.yml`。
     *
     * Gradle 单测的 cwd 是 `app/`，所以先试 `../.github/...`；
     * 也兼容从仓库根跑测试的情况。找不到就 `error()` 硬失败（绝不静默跳过）。
     */
    private fun workflowFile(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File("../.github/workflows/release.yml"),
            File(".github/workflows/release.yml"),
            File(cwd, "../.github/workflows/release.yml"),
            File(cwd, ".github/workflows/release.yml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 .github/workflows/release.yml（cwd=${cwd.absolutePath}）—— " +
                    "本文件所有判据会静默全绿，所以直接失败而不是跳过",
            )
    }

    private companion object {
        /**
         * 与 `app/build.gradle.kts` 的签名 secrets 一一对应
         * （键名以仓库现有 `keystore.properties` 为准：storeFile / storePassword / keyAlias / keyPassword）。
         */
        val SECRET_NAMES = listOf(
            "KEYSTORE_BASE64",
            "KEYSTORE_PASSWORD",
            "KEY_ALIAS",
            "KEY_PASSWORD",
        )
    }
}