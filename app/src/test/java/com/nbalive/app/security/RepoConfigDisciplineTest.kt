package com.nbalive.app.security

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库配置文件的两条纪律（review/04-security.md P2-4 / P2-6）。
 *
 * ## P2-4 `.gitignore` 必须是严格 UTF-8
 *
 * 它原来被写成 GBK（严格 UTF-8 解码在字节 594 处失败）。
 * `.gitattributes` 声明了 `* text=auto`，工具链假设文本文件是 UTF-8 ——
 * 于是：
 *  - `git diff` / `git log -p` 在该文件上输出乱码，改错规则时看不出来
 *  - 任何按 UTF-8 读取的脚本（CI、编辑器插件）直接抛异常
 *  - 更隐蔽的是：注释乱码之后，没人愿意维护它，规则会逐渐腐化
 *
 * ## P2-6 `kotlinx-coroutines-test` 版本必须与运行库一致
 *
 * 声明是 1.8.1，而 `kotlinx-coroutines-android` 是 1.9.0，
 * lockfile 里 `debugUnitTestRuntimeClasspath` 锁的是 **1.9.0** ——
 * 也就是说测试实际跑在 1.9.0 上，但 `testImplementationDependenciesMetadata`
 * 配置还锁着 1.8.1。声明与锁定不一致：升级时容易漏改，
 * 也让人误以为测试跑在 1.8.1 上（排查 coroutines 行为差异时会被误导）。
 */
class RepoConfigDisciplineTest {

    @Test
    fun `gitignore 必须是严格 UTF-8`() {
        val file = repoFileOrFail(".gitignore")
        val bytes = file.readBytes()
        val strict = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        val decoded = runCatching { strict.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        assertTrue(
            ".gitignore 不是严格 UTF-8（${decoded.exceptionOrNull()?.message}）。\n" +
                ".gitattributes 声明了 * text=auto，工具链假设文本是 UTF-8 ——\n" +
                "GBK 会让 git diff 输出乱码、按 UTF-8 读的脚本直接抛异常。",
            decoded.isSuccess,
        )
    }

    @Test
    fun `gitignore 的核心规则不能丢`() {
        // 重写编码时最容易顺手丢规则：APK/签名凭据绝不能入库
        val text = repoFileOrFail(".gitignore").readText()
        for (must in listOf("*.apk", "*.aab", "keystore.properties", "*.jks", "local.properties")) {
            assertTrue("重写 .gitignore 时丢了关键规则：$must", text.contains(must))
        }
    }

    @Test
    fun `coroutines-test 版本必须与 coroutines-android 一致`() {
        val text = repoFileOrFail("app/build.gradle.kts").readText()

        val android = versionOf(text, "kotlinx-coroutines-android")
        val test = versionOf(text, "kotlinx-coroutines-test")
        assertTrue("必须能解析出两个版本号（android=$android, test=$test）", android != null && test != null)
        assertEquals(
            "kotlinx-coroutines-test 必须与 kotlinx-coroutines-android 同版本：\n" +
                "版本不一致时 lockfile 里实际生效的是运行库版本，测试声明形同虚设，\n" +
                "排查 coroutines 行为差异时会被误导",
            android,
            test,
        )
    }

    @Test
    fun `lockfile 里不能残留旧版本`() {
        // 声明改了但 lockfile 没重新生成时，构建会用锁定版本 —— 声明等于没改
        val text = repoFileOrFail("app/gradle.lockfile").readText()
        val stale = Regex("""kotlinx-coroutines-test:1\.8\.\d""").findAll(text).toList()
        assertTrue(
            "lockfile 里仍残留旧版 kotlinx-coroutines-test：\n" +
                stale.joinToString("\n") { it.value } +
                "\n改完声明必须跑 `gradle dependencies --write-locks` 重新生成。",
            stale.isEmpty(),
        )
    }

    /**
     * review/optimization-review.md **B8**：依赖声明与 lockfile 的一致性判据
     * 此前只**点名**了一个 artifact（`kotlinx-coroutines-test`），
     * 换成 `okio`/`jsoup`/`media3` 重演同款漂移（声明 1.8.1、锁 1.9.0）不会被发现。
     *
     * 这里改成**通用比对**：`app/build.gradle.kts` 里每一个带显式版本号的依赖声明
     * （任意配置：`implementation/platform/debugImplementation/coreLibraryDesugaring/testImplementation`…），
     * 都必须在 `app/gradle.lockfile` 里以**同一版本**出现。
     *
     * ⚠️ 不含版本号的声明（`implementation("androidx.compose.ui:ui")` 这类由 BOM 管的）
     * 跳过 —— 它们的版本由 BOM 决定，判据在 BOM 那一行上。
     * 一个都不匹配时 `error()`（判据退化成"零违规的空扫"比漂移更难发现）。
     */
    @Test
    fun `每一条带版本号的依赖声明都必须按同版本进 lockfile`() {
        val buildScript = GuardSourceScan.stripComments(repoFileOrFail("app/build.gradle.kts").readText())
        val lockfile = repoFileOrFail("app/gradle.lockfile").readText()

        // 形如 implementation("group:artifact:1.2.3")
        val declared = Regex("""\w+\s*\(\s*"([\w.\-]+):([\w.\-]+):([\w.\-]+)"\s*\)""")
            .findAll(buildScript)
            .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()
        assertTrue(
            "一条带版本号的依赖声明都没解析出来（判据退化成空扫）：" +
                "build.gradle.kts 的形状变了，请更新这条正则 —— 空扫全绿是最危险的形态",
            declared.size >= 8,
        )

        val missing = declared.filterNot { (g, a, v) -> lockfile.contains("$g:$a:$v") }
        assertTrue(
            "以下依赖声明在 lockfile 里找不到**同版本**条目（声明与锁定不一致，构建实际用的是锁定版本）：\n" +
                missing.joinToString("\n") { "  ${it.first}:${it.second}:${it.third}" } +
                "\n改完声明必须跑 `gradle dependencies --write-locks` 重新生成 lockfile。",
            missing.isEmpty(),
        )
    }

    private fun versionOf(text: String, artifact: String): String? =
        Regex("""org\.jetbrains\.kotlinx:$artifact:([\d.]+)""").find(text)?.groupValues?.get(1)

    /**
     * 找仓库根目录下的文件；**找不到直接让测试失败**，不用 assumeTrue。
     *
     * ⚠️ 这里刻意不用 assumeTrue：assumeTrue 会把"路径没解析对"当成
     * "环境不支持，跳过"，测试静默通过 —— 守卫变成摆设。
     * 写这个测试时第一次就踩到了（单测工作目录是 app/，File(name) 找不到根目录文件）。
     */
    private fun repoFileOrFail(name: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, name),
            File(cwd, "../$name"),
            File(cwd, "../../$name"),
            File(name),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 $name（工作目录 ${File(cwd).absolutePath}）。\n" +
                    "候选路径：${candidates.joinToString { it.absolutePath }}",
            )
    }
}