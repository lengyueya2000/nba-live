package com.nbalive.app.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 清单与主题的版本适配（review/04-security.md P2-9）。
 *
 * ## 要解决什么
 *
 * 1. **`statusBarColor` / `navigationBarColor` 在 API 35 起被废弃**，
 *    系统强制 edge-to-edge 并忽略这两个值。项目 targetSdk 35，
 *    基础 themes.xml 里继续写着它们纯属误导 ——
 *    维护者会以为改了有用，实际在 Android 15 上毫无效果。
 *    正确做法：基础文件保留（API 24~34 仍生效），
 *    `values-v35/` 覆盖版本不写这两个属性。
 *
 * 2. **`enableOnBackInvokedCallback` 未声明**：项目只用 Compose `BackHandler`
 *    （API 33+ 走 OnBackInvokedCallback），没有重写 `onBackPressed`，
 *    所以启用它是安全的，还能拿到预测式返回动画。
 *
 * 这两条都是"配置看起来在、实际不生效/没开"的形态，
 * 只能靠读配置断言。
 */
class AndroidConfigVersionTest {

    @Test
    fun `API35 的主题覆盖不能再写废弃的系统栏颜色`() {
        // ⚠️ 必须先剔除 XML 注释再断言：v35 文件的注释里**特意**写了
        // "statusBarColor / navigationBarColor 在 API 35 起被废弃"来解释原因，
        // 不剔除的话断言会命中注释，守卫永远红（写的时候踩到了）。
        val v35 = stripXmlComments(repoFileOrFail("app/src/main/res/values-v35/themes.xml").readText())
        assertFalse(
            "values-v35 里不能有 statusBarColor：API 35 起系统忽略它，写了只会误导",
            v35.contains("statusBarColor"),
        )
        assertFalse(
            "values-v35 里不能有 navigationBarColor：同上",
            v35.contains("navigationBarColor"),
        )
        assertTrue(
            "v35 覆盖必须保留 windowBackground（那是真正生效的）",
            v35.contains("windowBackground"),
        )
    }

    /** 去掉 XML 注释（`<!-- ... -->`，含跨行）。 */
    private fun stripXmlComments(text: String): String =
        Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL)).replace(text, "")

    @Test
    fun `基础主题必须保留系统栏颜色 否则旧系统视觉回归`() {
        // 反面保护：v35 不写，但 API 24~34 仍需要它 ——
        // 删掉会让旧系统状态栏变成不透明黑条，与页面背景割裂
        //
        // ⚠️ 必须**同样剔除 XML 注释**（审计 A2）：基础 themes.xml 顶部的注释里
        // 就写着这两个属性名，不剔除的话"删掉两个 <item>"照样绿 ——
        // 本类对 values-v35 用了 stripXmlComments，却漏了基础文件这一侧。
        val base = stripXmlComments(repoFileOrFail("app/src/main/res/values/themes.xml").readText())
        assertTrue(
            "基础主题必须保留 statusBarColor（API 24~34 仍然生效）",
            base.contains("statusBarColor"),
        )
        assertTrue(
            "基础主题必须保留 navigationBarColor",
            base.contains("navigationBarColor"),
        )
    }

    @Test
    fun `清单必须启用预测式返回`() {
        val manifest = repoFileOrFail("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "必须声明 android:enableOnBackInvokedCallback=\"true\"：\n" +
                "项目只用 Compose BackHandler（API 33+ 走 OnBackInvokedCallback）、\n" +
                "没有重写 onBackPressed，启用是安全的，还能拿到预测式返回动画。",
            manifest.contains("enableOnBackInvokedCallback=\"true\""),
        )
    }

    @Test
    fun `清单不能重写 onBackPressed 之外的双轨返回`() {
        // 启用 OnBackInvokedCallback 后若又重写 onBackPressed，
        // 两条返回路径会各走一套逻辑，行为分叉
        val activity = repoFileOrFail("app/src/main/java/com/nbalive/app/MainActivity.kt").readText()
        assertFalse(
            "启用预测式返回后不能再重写 onBackPressed（会和 OnBackInvokedCallback 双轨）",
            activity.contains("override fun onBackPressed"),
        )
    }

    private fun repoFileOrFail(name: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, name),
            File(cwd, "../$name"),
            File(cwd, "../../$name"),
            File(name),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（工作目录 ${File(cwd).absolutePath}）")
    }
}