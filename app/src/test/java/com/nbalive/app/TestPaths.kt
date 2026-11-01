package com.nbalive.app

import java.io.File

/**
 * 测试里**唯一**解析仓库路径的地方（review/optimization-review.md B11）。
 *
 * ## 为什么要有它
 *
 * 改造前 `app/src/test` 里 **54 个文件 / 99 处**各自写了一遍
 * `System.getProperty("user.dir")` + 候选根列表 + `firstOrNull { it.isDirectory }`
 * + `error(...)`。代价不是"多写几行"，而是：
 *  - **换执行器/挪目录时大面积假红**：每个文件按自己的候选根与错误文案行事，
 *    工作目录一变，红的是十几条互不相干的用例，而真正的原因只有一个；
 *  - **假绿风险**：`?: error(...)` 只要有一处被写成 `assumeTrue` 或"返回空列表"，
 *    那条守卫就会静默全绿（本仓库踩过 22 处 `assumeTrue`）。
 *
 * ## 语义契约（铁律③：找不到一律 `error()` 硬失败，绝不用 assume 家族）
 *
 *  - [workingDir] 是**全仓唯一**读 `user.dir` 的地方（`grep user.dir app/src/test`
 *    只应命中本文件）；
 *  - 每个 `xxxRoot()` 的候选顺序**与改造前逐字一致**（先 cwd 相对、再 repo 相对），
 *    额外只是多了一条"从仓库根再试一次"的兜底 —— 它只在原来会 `error()` 的场景生效，
 *    不会改变任何原本能解析出来的结果；
 *  - `productionFile` / `testFile` 按**唯一文件名**定位（0 个或多个命中都 `error()`），
 *    与 `GuardSourceScan.uniqueFile` 同语义；
 *  - 所有错误文案都带上 `cwd` 与候选路径，便于排障。
 *
 * ## 用法
 *
 * ```kotlin
 * val code = TestPaths.productionFile("MatchRepository.kt").readText()
 * val files = TestPaths.productionFiles()          // 全部 .kt
 * val all = TestPaths.productionFilesAll()         // 全部文件（含非 .kt）
 * ```
 */
object TestPaths {

    private const val PRODUCTION_REL = "app/src/main/java/com/nbalive/app"
    private const val UI_REL = "$PRODUCTION_REL/ui"
    private const val SCREENS_REL = "$UI_REL/screens"
    private const val THEME_REL = "$UI_REL/theme"
    private const val TEST_REL = "app/src/test/java/com/nbalive/app"

    /** 进程工作目录（Gradle 单测通常 = 模块目录 `app/`，也可能是仓库根）。 */
    fun workingDir(): File = File(System.getProperty("user.dir") ?: ".").absoluteFile

    /** [workingDir] 的字符串形式（给既有错误文案/候选列表用）。 */
    fun workingDirPath(): String = workingDir().absolutePath

    /** 仓库根：含 `app/src/main/java/com/nbalive/app` 或 `settings.gradle(.kts)` 的那一层。 */
    val repoRoot: File by lazy {
        val cwd = workingDir()
        val candidates = listOfNotNull(cwd, cwd.parentFile)
        candidates.firstOrNull { File(it, PRODUCTION_REL).isDirectory }
            ?: candidates.firstOrNull {
                File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile
            }
            ?: error(
                "找不到仓库根：cwd=${cwd.absolutePath}，候选=${candidates.map { it.absolutePath }}，" +
                    "期望其中一层含 $PRODUCTION_REL 或 settings.gradle(.kts) —— " +
                    "所有路径判据会静默全绿，所以直接失败",
            )
    }

    /** 仓库根下的相对路径。 */
    fun repoFile(relative: String): File = File(repoRoot, relative)

    // ---------------- 目录 ----------------

    /**
     * `src/main/java`（大部分守卫真正要的是它下面的包目录）。
     *
     * 候选顺序 = 改造前各文件的写法：`File("src/main/java")`（cwd 相对，等价于
     * `File(cwd, "src/main/java")`）、`File("app/src/main/java")`，最后是从仓库根再试一次。
     */
    val productionJavaRoot: File by lazy { resolveDir("src/main/java", "app/src/main/java") }

    /** `src/main/java/com/nbalive/app`。 */
    val productionRoot: File by lazy { resolveDir(PRODUCTION_REL.removePrefix("app/"), PRODUCTION_REL) }

    /** `.../ui`。 */
    val uiRoot: File by lazy { resolveDir(UI_REL.removePrefix("app/"), UI_REL) }

    /** `.../ui/screens`。 */
    val screensRoot: File by lazy { resolveDir(SCREENS_REL.removePrefix("app/"), SCREENS_REL) }

    /** `.../ui/theme`。 */
    val themeRoot: File by lazy { resolveDir(THEME_REL.removePrefix("app/"), THEME_REL) }

    /** `src/test/java/com/nbalive/app`。 */
    val testRoot: File by lazy { resolveDir(TEST_REL.removePrefix("app/"), TEST_REL) }

    // ---------------- 文件列表 ----------------

    /** 生产 `*.kt`（原样保留 `extension == "kt"` 与 `walkTopDown` 语义）。 */
    fun productionFiles(): List<File> = ktFiles(productionRoot)

    /** 生产**全部**文件（有些守卫要连非 `.kt` 一起看，例如资源/构建脚本）。 */
    fun productionFilesAll(): List<File> = allFiles(productionRoot)

    /** `ui` 目录下递归的 `*.kt`。 */
    fun uiFiles(): List<File> = ktFiles(uiRoot)

    /**
     * `ui/screens` 目录下的 `*.kt`（**不递归**：改造前用的是 `listFiles`，语义保持）。
     *
     * ⚠️ KDoc 里**不要**写出斜杠加星号的组合（那会被 Kotlin 当成嵌套块注释的开始，
     * 把本文件后半段整段吞掉 —— 本轮实测：`resolveDir`/`ktFiles`/`allFiles`
     * 全部报"未解析"、文件尾报 "Unclosed comment"）。用 `目录 + 通配` 这样的中文说法。
     */
    fun screensFiles(): List<File> =
        screensRoot.listFiles { f: File -> f.isFile && f.extension == "kt" }?.sortedBy { it.name }?.toList()
            ?: error("找不到 screens 目录（${screensRoot.absolutePath}）—— 判据会静默全绿")

    /** `ui/theme` 目录下递归的 `*.kt`。 */
    fun themeFiles(): List<File> = ktFiles(themeRoot)

    /** `src/test` 目录下递归的 `*.kt`。 */
    fun testFiles(): List<File> = ktFiles(testRoot)

    // ---------------- 唯一文件 ----------------

    /** 生产源码里按**唯一文件名**定位（0 个或多个命中都 `error()`）。 */
    fun productionFile(name: String): File = GuardSourceScan.uniqueFile(productionFiles(), name)

    /**
     * 生产源码里按**唯一文件名**定位，但**不限制扩展名**（含资源/构建脚本等）。
     *
     * 语义 = 改造前各文件的 `uniqueFile(root.walkTopDown().filter { it.isFile }.toList(), name)`。
     */
    fun productionFileAny(name: String): File = GuardSourceScan.uniqueFile(productionFilesAll(), name)

    /**
     * 生产源码里按**相对路径**定位（0 个或多个命中都 `error()`）。
     *
     * ⚠️ 调用方传的路径有两种口径（改造前各文件就是这么写的，别改它们的实参）：
     *  - 相对 **`com/nbalive/app` 包根**：`ui/screens/LiveNowScreen.kt`、`theme/Theme.kt`；
     *  - 相对 **`src/main/java`**：`com/nbalive/app/ui/screens/LiveNowScreen.kt`。
     * 这里两种都试，命中即返回；都不命中就 `error()` 并把两条候选路径打出来。
     */
    fun productionFileAt(relative: String): File {
        val candidates = listOf(File(productionRoot, relative), File(productionJavaRoot, relative))
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 $relative：候选=${candidates.map { it.absolutePath }} —— " +
                    "判据会静默全绿，所以直接失败",
            )
    }

    /** 测试源码里按**唯一文件名**定位（0 个或多个命中都 `error()`）。 */
    fun testFile(name: String): File = GuardSourceScan.uniqueFile(testFiles(), name)

    // ---------------- 便捷投影 ----------------

    /** 文件名 → 原文（多处守卫的 `productionCode()` / `uiRaw()` 同一形态）。 */
    fun codeByName(files: List<File>): Map<String, String> =
        files.associate { it.name to it.readText() }

    // ---------------- 内部 ----------------

    /**
     * 依次尝试：`File("相对路径")`（cwd 相对，与改造前逐字一致）、
     * 从 [repoRoot] 出发的同一条相对路径，最后 `error()`。
     *
     * ⚠️ 额外的那条 repo 兜底只在原来会 `error()` 的场景生效 —— 它把
     * 「cwd 恰好不是 `app/` 也不是仓库根」这种环境下的大面积假红，变成一次正常解析。
     */
    private fun resolveDir(relativeToCwd: String, relativeToRepo: String): File {
        val candidates = listOf(
            File(relativeToCwd),
            File(workingDir(), relativeToCwd),
            File(repoRoot, relativeToRepo),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到目录 $relativeToRepo：cwd=${workingDir().absolutePath}，" +
                    "候选=${candidates.map { it.absolutePath }} —— 判据会静默全绿，所以直接失败",
            )
    }

    private fun ktFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun allFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile }.toList()
}