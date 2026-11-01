package com.nbalive.app.security

import com.nbalive.app.TestPaths
import com.nbalive.app.GuardSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 构建脚本卫生守卫 —— review/04-security.md **P2-7**「使用 AGP 内部实现类」。
 *
 * ## 事故形态
 *
 * 为了让"未签名 APK"不占用正式包的名字（`app-release.apk`），旧脚本把
 * `com.android.build.api.variant.impl.VariantOutputImpl` 强转出来改 `outputFileName`。
 * 那是 **AGP 私有实现**：AGP 升级换掉实现类时，这段要么静默不生效、要么当场崩，
 * 而它守护的恰好是"别把装不上的包发出去"这条发布红线。
 *
 * 实测证据（AGP 8.7.3，`javap -cp gradle-api-8.7.3.jar com.android.build.api.variant.VariantOutput`）：
 * ```
 * public interface com.android.build.api.variant.VariantOutput extends VariantOutputConfiguration {
 *   public abstract org.gradle.api.provider.Property<java.lang.Integer> getVersionCode();
 *   public abstract org.gradle.api.provider.Property<java.lang.String>  getVersionName();
 *   public abstract org.gradle.api.provider.Property<java.lang.Boolean> getEnabled();
 *   public abstract org.gradle.api.provider.Property<java.lang.Boolean> getEnable();
 * }
 * ```
 * 公开接口**没有 outputFileName**，所以正确做法是别碰 AGP 输出名的私有 API：
 * 在打包任务之后用 Gradle 自己的文件 API 把 `app-release-unsigned.apk` 改名。
 */
class BuildScriptHygieneTest {

    private val script: File = moduleBuildScript()

    @Test
    fun `构建脚本不得引用 AGP 内部实现包`() {
        val raw = script.readText()
        // 非空性锚点：扫到的确实是 App 模块的构建脚本
        assertTrue("扫错文件了（找不到 android 块）：${raw.take(80)}", raw.contains("android {"))
        assertTrue("扫错文件了（找不到依赖锁定）：${raw.take(200)}", raw.contains("dependencyLocking"))

        val code = GuardSourceScan.stripNonCode(raw)
        assertFalse(
            "构建脚本又引用了 AGP 私有实现包 `com.android.build.api.variant.impl`（P2-7）—— " +
                "那是 AGP 私有 API，升级时会静默失效或当场崩溃",
            code.contains("com.android.build.api.variant.impl"),
        )
    }

    @Test
    fun `未签名产物必须显式改名 且绝不占用正式包名`() {
        val raw = script.readText()
        // ⚠️ 断言必须跑在**只剔注释**的文本上（review/optimization-review.md H2）：
        // 这里的判据里含字符串字面量（`endsWith("-unsigned.apk")`），
        // 用 `stripNonCode` 会把字面量一起剔掉导致断言恒假；而用 `raw` 又会被
        // **注释**满足 —— `app/build.gradle.kts:81`/`:174` 的注释里就逐字写着
        // `app-UNSIGNED-` 与 `PackageAndroidArtifact`，于是把真改名逻辑
        // （`:135`/`:160`）删掉，守卫照样绿。`stripComments` 正好取中间：
        // 剔注释、留字符串字面量。
        val code = GuardSourceScan.stripComments(raw)

        // 断言真实执行的改名动作（打包任务的 doLast 里），不是注释里的承诺：
        assertTrue("改名判据丢了：必须只处理 `-unsigned.apk` 产物", code.contains("""endsWith("-unsigned.apk")"""))
        assertTrue("改写目标丢了：未签名包必须变成 app-UNSIGNED-<variant>.apk", code.contains("app-UNSIGNED-"))
        assertTrue("改名必须调用文件 API 真的落盘", code.contains(".renameTo(target)"))
        assertTrue("改名必须挂在打包任务上", code.contains("PackageAndroidArtifact"))
    }

    /**
     * 负控（review/optimization-review.md H2）：**注释里的字面量不能再满足判据**。
     *
     * 样本逐字取自 `app/build.gradle.kts` 的两条注释行（`:81` 与 `:174`），
     * 它们此前正好满足上面那份断言里的第 2、4 条。这条测试证明"只剔注释"这一步
     * 真的生效：如果谁把上面的断言改回 `raw.contains(...)`，他就会重新落入
     * "删掉实现、守卫仍绿"的形态 —— 而这里钉住的正是那个前提。
     */
    @Test
    fun `注释里的字面量不得满足改名判据`() {
        val commentOnlySample = listOf(
            "    //    改名后是 `app-UNSIGNED-release.apk`；",
            "// **不继承** `com.android.build.gradle.tasks.PackageAndroidArtifact` —— 所以上面那道 `doFirst`",
        ).joinToString("\n")
        val stripped = GuardSourceScan.stripComments(commentOnlySample)
        for (needle in listOf("app-UNSIGNED-", "PackageAndroidArtifact")) {
            assertFalse(
                "`${needle}` 在**只含注释**的样本里仍然命中 —— 剔注释那一步没生效，" +
                    "于是「删掉真实现、守卫照样绿」的形态回来了（H2）",
                stripped.contains(needle),
            )
        }
        // 反向自检：字符串字面量必须**保留**，否则判据会因剔得太狠而恒假。
        assertTrue(
            "`stripComments` 把字符串字面量也剔掉了 —— 判据会恒假（比假绿更难发现）",
            GuardSourceScan.stripComments("""it.name.endsWith("-unsigned.apk")""").contains("""endsWith("-unsigned.apk")"""),
        )
        // 行注释形态（`//` 之后同行还有内容）与块注释形态都要覆盖。
        assertFalse(
            "同行尾注释里的字面量仍被当成代码",
            GuardSourceScan.stripComments("""val x = 1 // app-UNSIGNED-""").contains("app-UNSIGNED-"),
        )
        assertFalse(
            "块注释里的字面量仍被当成代码",
            GuardSourceScan.stripComments("/* app-UNSIGNED- */").contains("app-UNSIGNED-"),
        )
    }

    /**
     * 04-P2-8：AAB 路径必须也有闸。
     *
     * 实测机理：`bundleRelease` 的打包任务是 `PackageBundleTask`，**不继承**
     * `PackageAndroidArtifact` —— 只按类型拦 APK 的话，AAB 路径静默放行
     * （实测挪走 keystore.properties 后仍 exit=0 并产出 `app-release.aab`）。
     * 所以脚本必须按名字给 bundle 任务补闸，且未签名 AAB 要改名。
     */
    @Test
    fun `bundle 任务必须按名字补签名闸 而不是只按 APK 任务类型拦`() {
        val raw = script.readText()
        val code = GuardSourceScan.stripNonCode(raw)
        // ⚠️ 与上面同理：含字符串字面量的判据走"只剔注释"（H2）。
        val uncommented = GuardSourceScan.stripComments(raw)

        assertTrue(
            "bundle 任务没有闸：脚本里找不到 `endsWith(\"ReleaseBundle\")` —— " +
                "只 withType<PackageAndroidArtifact>() 拦不住 AAB 路径（P2-8）",
            uncommented.contains("""endsWith("ReleaseBundle")"""),
        )
        assertTrue(
            "闸必须挂在 tasks.matching 上（按名字匹配，不依赖 AGP 内部类型）",
            code.contains("tasks.matching"),
        )
        assertTrue(
            "未签名 AAB 必须改名成 app-UNSIGNED-release.aab，不能留在发布路径上",
            uncommented.contains("app-UNSIGNED-release.aab"),
        )
    }

    private fun moduleBuildScript(): File {
        val cwd = TestPaths.workingDirPath()
        return listOf(
            File("build.gradle.kts"),
            File(cwd, "build.gradle.kts"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 App 模块 build.gradle.kts（cwd=${File(cwd).absolutePath}）—— 守卫会静默全绿，所以直接失败")
    }
}