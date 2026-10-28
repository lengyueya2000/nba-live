package com.nbalive.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题语义槽位的收敛（review/03-ui.md P2-28 / P2-29）。
 *
 * ## P2-28：`surfaceContainer` 系列必须铺满
 *
 * 两个 colorScheme 原来只覆盖 M3 的经典槽位，`surfaceContainer` /
 * `surfaceContainerHigh` / `surfaceBright` / `surfaceDim` / `inverseSurface`
 * 全走 M3 默认值 —— 于是 `DropdownMenu`（容器色 = `surfaceContainer`）、
 * `ModalBottomSheet`、`DatePicker` 这几个组件用的是库自带的中性紫灰
 *（深色 `#211F26` / 浅色 `#F3EDF7`），挂在本主题的蓝调卡片上"不是一家"。
 *
 * 断言分三层，故意不写成"照着实现再抄一遍"：
 *  1. **落板**：每个槽位必须等于自定义色板里的对应档（会被"删掉赋值"直接打红）
 *  2. **不许退回默认**：`surfaceContainer` 不等于 M3 那两个默认中性色
 *     （反向钉住"没铺满"这个原始缺陷）
 *  3. **不变量**：容器层级单调 + 深色槽位必须仍属蓝调色板 + 反转色对可达
 *     —— 这层在"没铺满"时也是绿的，它守的是以后改色板别再改歪
 *
 * ## P2-29：`SportDark` / `SportLight` 的可见性
 *
 * 注释里 ⚠️ 禁止在 Composable 里直接引用色板，但两个 object 原来都是 public。
 * 收成 `internal` 之后，**本仓库是单模块（settings.gradle.kts 只 include(":app")），
 * `internal` 对 app 内的任何文件仍然可见 —— 编译器并不会因此拦住
 * `ui/screens` 里的一句 `SportDark.Card`**。所以真正能红的约束是
 * [色板对象不得被 theme 之外的生产代码引用]，`internal` 只是为将来拆模块留的边界。
 * 两个测试都保留：只要不是 internal 就红；只要越界引用就红。
 */
class ThemeSlotTest {

    // ---------------- P2-28：深色 ----------------

    @Test
    fun `深色主题必须补齐 surfaceContainer 系列`() {
        val cs = DarkColors
        val pins = listOf(
            "surfaceContainerLowest" to (SportDark.Bg to cs.surfaceContainerLowest),
            "surfaceContainerLow" to (SportDark.Card to cs.surfaceContainerLow),
            "surfaceContainer" to (SportDark.Card2 to cs.surfaceContainer),
            "surfaceContainerHigh" to (SportDark.Card3 to cs.surfaceContainerHigh),
            "surfaceContainerHighest" to (SportDark.Card3 to cs.surfaceContainerHighest),
            "surfaceBright" to (SportDark.Card3 to cs.surfaceBright),
            "surfaceDim" to (SportDark.Bg to cs.surfaceDim),
            "inverseSurface" to (SportDark.Txt to cs.inverseSurface),
            "inverseOnSurface" to (SportDark.Bg to cs.inverseOnSurface),
        )
        for ((slot, pair) in pins) {
            val (expected, actual) = pair
            assertEquals(
                "$slot 没有落在自定义色板里（实际 ${hex(actual)}），" +
                    "DropdownMenu/ModalBottomSheet 这类 M3 组件会回落到库默认的中性紫灰，\n" +
                    "与蓝调卡片「不是一家」（review/03-ui.md P2-28）",
                expected,
                actual,
            )
        }
    }

    @Test
    fun `深色主题的 surfaceContainer 不许退回 M3 默认中性色`() {
        val actual = DarkColors.surfaceContainer
        assertFalse(
            "surfaceContainer 又是 M3 深色默认的 ${hex(Color(0xFF211F26))}（拿掉赋值就会这样）：\n" +
                "DropdownMenu 的容器色就是它，挂在 ${hex(DarkColors.surface)} 的卡片上既不同族也看不出层级",
            actual == Color(0xFF211F26),
        )
        // 本主题的深色面是**蓝调**（蓝远大于红）；M3 默认中性紫灰几乎蓝红相等
        for ((slot, c) in listOf(
            "surfaceContainerLow" to DarkColors.surfaceContainerLow,
            "surfaceContainer" to DarkColors.surfaceContainer,
            "surfaceContainerHigh" to DarkColors.surfaceContainerHigh,
            "surfaceContainerHighest" to DarkColors.surfaceContainerHighest,
            "surfaceBright" to DarkColors.surfaceBright,
        )) {
            assertTrue(
                "$slot = ${hex(c)} 已经不属本主题的蓝调色板（蓝-红只有 ${"%.3f".format(c.blue - c.red)}，" +
                    "自定义档位都 ≥ 0.10）——像是回落到 M3 中性默认了",
                c.blue - c.red >= 0.10f,
            )
        }
    }

    @Test
    fun `深色主题的 surface 层级必须单调变亮`() {
        val levels = listOf(
            "surfaceDim" to DarkColors.surfaceDim,
            "surfaceContainerLow" to DarkColors.surfaceContainerLow,
            "surfaceContainer" to DarkColors.surfaceContainer,
            "surfaceContainerHigh" to DarkColors.surfaceContainerHigh,
            "surfaceContainerHighest" to DarkColors.surfaceContainerHighest,
            "surfaceBright" to DarkColors.surfaceBright,
        )
        for ((a, b) in levels.zipWithNext()) {
            assertTrue(
                "深色主题里 ${a.first}（${hex(a.second)}）比 ${b.first}（${hex(b.second)}）还亮，容器层级反了",
                luminance(a.second) <= luminance(b.second) + 1e-4,
            )
        }
        assertTrue(
            "surfaceContainerLowest（${hex(DarkColors.surfaceContainerLowest)}）在深色主题应该是最暗的一档",
            luminance(DarkColors.surfaceContainerLowest) <= luminance(DarkColors.surfaceContainer) + 1e-4,
        )
    }

    // ---------------- P2-28：浅色 ----------------

    @Test
    fun `浅色主题必须补齐 surfaceContainer 系列`() {
        val cs = LightColors
        val pins = listOf(
            "surfaceContainerLowest" to (SportLight.Card to cs.surfaceContainerLowest),
            "surfaceContainerLow" to (SportLight.Card2 to cs.surfaceContainerLow),
            "surfaceContainer" to (SportLight.Card2 to cs.surfaceContainer),
            "surfaceContainerHigh" to (SportLight.Card3 to cs.surfaceContainerHigh),
            "surfaceContainerHighest" to (SportLight.Card3 to cs.surfaceContainerHighest),
            "surfaceBright" to (SportLight.Card to cs.surfaceBright),
            "surfaceDim" to (SportLight.Bg to cs.surfaceDim),
            "inverseSurface" to (SportLight.Txt to cs.inverseSurface),
            "inverseOnSurface" to (SportLight.Bg to cs.inverseOnSurface),
        )
        for ((slot, pair) in pins) {
            val (expected, actual) = pair
            assertEquals(
                "$slot 没有落在自定义色板里（实际 ${hex(actual)}），浅色主题下菜单会像「没上色的浮层」" +
                    "（M3 浅色默认 surfaceContainer = ${hex(Color(0xFFF3EDF7))}，review/03-ui.md P2-28）",
                expected,
                actual,
            )
        }
    }

    @Test
    fun `浅色主题的 surfaceContainer 不许退回 M3 默认色`() {
        val actual = LightColors.surfaceContainer
        assertFalse(
            "surfaceContainer 又是 M3 浅色默认的 ${hex(Color(0xFFF3EDF7))}（拿掉赋值就会这样）：" +
                "与自定义卡片 ${hex(LightColors.surface)} 差不到 1.2:1，菜单看着像没上色",
            actual == Color(0xFFF3EDF7),
        )
    }

    @Test
    fun `浅色主题的 surface 层级必须单调变暗`() {
        val levels = listOf(
            "surfaceContainerLowest" to LightColors.surfaceContainerLowest,
            "surfaceContainerLow" to LightColors.surfaceContainerLow,
            "surfaceContainer" to LightColors.surfaceContainer,
            "surfaceContainerHigh" to LightColors.surfaceContainerHigh,
            "surfaceContainerHighest" to LightColors.surfaceContainerHighest,
        )
        for ((a, b) in levels.zipWithNext()) {
            assertTrue(
                "浅色主题里 ${a.first}（${hex(a.second)}）比 ${b.first}（${hex(b.second)}）还暗，容器层级反了",
                luminance(a.second) >= luminance(b.second) - 1e-4,
            )
        }
        assertTrue(
            "surfaceDim（${hex(LightColors.surfaceDim)}）在浅色主题应该是最暗的一档",
            luminance(LightColors.surfaceDim) <= luminance(LightColors.surfaceContainerLowest) + 1e-4,
        )
    }

    // ---------------- P2-28：语义不变量 ----------------

    @Test
    fun `菜单容器色必须比卡片高一层 且反转色对白字可读`() {
        // 深色：表面越"高"越亮；浅色：越"高"越暗（M3 tonal surface 规则）
        assertTrue(
            "深色主题的 surfaceContainer（${hex(DarkColors.surfaceContainer)}）不比 surface（${hex(DarkColors.surface)}）亮，" +
                "下拉菜单看不出挂在卡片之上",
            luminance(DarkColors.surfaceContainer) > luminance(DarkColors.surface),
        )
        assertTrue(
            "浅色主题的 surfaceContainer（${hex(LightColors.surfaceContainer)}）不比 surface（${hex(LightColors.surface)}）暗，" +
                "下拉菜单看不出挂在卡片之上",
            luminance(LightColors.surfaceContainer) < luminance(LightColors.surface),
        )
        // inverseOnSurface 压在 inverseSurface 上（Snackbar 文案），必须能读
        for ((name, cs) in listOf("深色" to DarkColors, "浅色" to LightColors)) {
            val ratio = contrast(cs.inverseOnSurface, cs.inverseSurface)
            assertTrue(
                "$name 主题的 inverseOnSurface（${hex(cs.inverseOnSurface)}）压在 " +
                    "inverseSurface（${hex(cs.inverseSurface)}）上只有 ${"%.2f".format(ratio)}:1，Snackbar 文案读不了",
                ratio >= 4.5,
            )
        }
    }

    // ---------------- P2-29 ----------------

    @Test
    fun `色板对象必须收成 internal`() {
        val code = stripComments(themeFile().readText())
        for (name in listOf("SportDark", "SportLight")) {
            assertTrue(
                "$name 必须是 `internal object`（现在 app 内任意文件都能直接 import）：" +
                    "注释里 ⚠️ 禁止 Composable 直接引用色板，编译器要能跟着拦",
                Regex("""\binternal object $name\b""").containsMatchIn(code),
            )
            assertFalse(
                "$name 还是 public 声明 —— 约定就只剩注释了",
                Regex("""(?<!internal )object $name\b""").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `色板对象不得被 theme 之外的生产代码引用`() {
        // 单模块仓库里 internal 拦不住同一模块的兄弟包，所以真正红的是这条
        val offenders = appSourceFiles()
            .filter { it.name != "Theme.kt" }
            .mapNotNull { f ->
                val code = stripComments(f.readText())
                val hits = Regex("""\b(SportDark|SportLight)\.""")
                    .findAll(code)
                    .map { it.value.trimEnd('.') }
                    .distinct()
                    .toList()
                if (hits.isEmpty()) null else "${f.name}: ${hits.joinToString()}"
            }
        assertTrue(
            "以下生产代码直接引用了原始色板（应改按语义槽位取色，如 colorScheme.surfaceVariant / outlineVariant）：\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n浅色主题下写死深色板会压出「深色砖」，这是 Common.kt 的 StatusChip 踩过的坑（review/03-ui.md P2-29）。",
            offenders.isEmpty(),
        )
    }

    // ---------------- 守卫自身 ----------------

    @Test
    fun `扫描必须真的扫到主题源码`() {
        val files = appSourceFiles()
        assertTrue(
            "扫不到生产源码（cwd=${System.getProperty("user.dir")}），守卫会静默全绿",
            files.size >= 10,
        )
        assertTrue("没扫到 ui/theme/Theme.kt", files.any { it.name == "Theme.kt" })
        assertTrue("没扫到 ui/components/", files.any { it.parentFile?.name == "components" })
    }

    // ---------------- 基础设施 ----------------

    private fun themeFile(): File =
        appSourceFiles().firstOrNull { it.name == "Theme.kt" }
            ?: error("找不到 ui/theme/Theme.kt（cwd=${System.getProperty("user.dir")}），守卫会静默全绿")

    private fun appSourceFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录，cwd=$cwd，候选=${roots.map { it.path }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** 剔除注释：注释里常写着同样的字面量，不剔除守卫会永远绿。 */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        while (i < text.length) {
            val c = text[i]
            when {
                inBlock -> {
                    if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                        inBlock = false
                        i += 2
                    } else {
                        if (c == '\n') sb.append('\n')
                        i++
                    }
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    inBlock = true
                    i += 2
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '"' -> {
                    sb.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        sb.append(d)
                        if (d == '\\' && i + 1 < text.length) {
                            sb.append(text[i + 1])
                            i += 2
                            continue
                        }
                        i++
                        if (d == '"') break
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** WCAG 相对亮度（与 ThemeContrastTest 同一套算法，不依赖 Compose 的 luminance）。 */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun hex(c: Color): String =
        "#%02X%02X%02X".format((c.red * 255f).toInt(), (c.green * 255f).toInt(), (c.blue * 255f).toInt())
}