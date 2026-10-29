package com.nbalive.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队徽圆底在浅色主题下的**可辨性**（review/03-ui.md P2-30 的收尾；
 * `review/coverage-ledger.md` 指名的最后一条"用户可见"未落地项）。
 *
 * ## 缺陷（实测值）
 *
 * `TeamLogo` 的圆底原来是硬编码 `Color.White`（`ui/components/Common.kt`）：
 *  - **深色**主题：白盘压 `SportDark.Card = #161C33` → **16.83:1**，视觉正常；
 *  - **浅色**主题：白盘压 `SportLight.Card = #FFFFFF` → **1.00:1** ——
 *    审查原话「边缘完全消失」：圆形队徽与卡片糊成一片。
 *
 * ## 门槛为什么取 1.5:1（取值与理由）
 *
 *  - 圆盘上**不承载文字**（队徽是图像内容，队名在旁边的文本节点里），
 *    所以 WCAG 的文字门槛（AA 4.5:1）在这里不适用；
 *  - WCAG 1.4.11 对"理解内容所必需的视觉边界"给的是 3:1，但圆盘只是队徽图片的**衬底**、
 *    不是控件边界；3:1 会把浅色主题的盘面压成很重的灰、盖过队徽本身，所以本仓库不强求；
 *  - 取 **1.5:1** 作为"能看出圆形"的下限：浅色主题的盘面实测 **1.82:1**（压第二档卡片
 *    `SportLight.Card2 = #F4F6FC` 也有 1.69:1），一眼可辨，同时保留"浅盘衬队徽"的观感；
 *  - 深色主题**另加**一条"不许退化"的下限 **≥10:1**：原来就是"白圆底 + 深卡片"，
 *    换成暗盘虽然也能过 1.5:1，但那是视觉退化（白盘实测 16.83:1）。
 *
 * ## 判据为什么分两层
 *
 * 1. **纯判据**：直接从 `SportDark` / `SportLight` 的**真实 token** 算 WCAG 对比度
 *    （用现成的 [AccentContrast.contrast]，不另抄一份色值、也不另写一份 WCAG 算术）；
 * 2. **调用点判据**：`TeamLogo` 的 `background(...)` 必须消费主题 token、且不得再出现硬编码白。
 *    ⚠️ 只有第 1 层是不够的：**它只看 token 的值** —— 组件里写死 `Color.White` 时
 *    第 1 层照样全绿（token 本身没问题）。这正是本仓库审计反复点名的「假安全感」，
 *    所以两层都要（也是任务里"回退即红"能成立的那一层）。
 *
 * 三条铁律：断言前 [GuardSourceScan.stripNonCode]（连同字符串字面量一起剔，行号不变）；
 * 匹配**调用**而不是声明或字面量出现；源码根/函数体取不到一律 `error()`，绝不静默跳过。
 * 判据本身有内存样本自检（喂真函数，不把判据复述一遍）。
 */
class LogoPlateContrastTest {

    // ---------------- 调用点判据：组件必须消费 token ----------------

    @Test
    fun `TeamLogo 的圆底必须消费主题 token 而不是硬编码白`() {
        val body = GuardSourceScan.functionBody(commonFile().readText(), "fun TeamLogo(")
        val violations = plateViolations(body)
        assertTrue(
            "TeamLogo 的圆底没有走主题层 token（review/03-ui.md P2-30）：\n" +
                violations.joinToString("\n") { "  · $it" } + "\n" +
                "白圆底压在浅色卡片 SportLight.Card=#FFFFFF 上只有 1.00:1，审查原话「边缘完全消失」。\n" +
                "修法：`.background(logoPlate())` —— token 定义在 Theme.kt，按深浅主题各给一档，\n" +
                "组件只消费、不写死色值。实际函数体：\n$body",
            violations.isEmpty(),
        )
    }

    @Test
    fun `判据自检 命中硬编码白而放过 token 且注释与声明不算消费`() {
        // 审计 A9 批评过"自检本身是永真断言"：这里喂给**真正参与判定的那个函数**。
        val hardcoded = sampleTeamLogo("Color.White")
        assertTrue(
            "判据抓不到硬编码白（已经退化成摆设）：${plateViolations(hardcoded)}",
            plateViolations(hardcoded).isNotEmpty(),
        )
        val whiteLiteral = sampleTeamLogo("Color(0xFFFFFFFF)")
        assertTrue(
            "判据抓不到 `Color(0xFFFFFFFF)` 这种等价写法（审计 A1：正则只匹配当前写法）：" +
                "${plateViolations(whiteLiteral)}",
            plateViolations(whiteLiteral).isNotEmpty(),
        )
        val tokenized = sampleTeamLogo("logoPlate()")
        assertTrue(
            "判据误报了正确的 token 写法：${plateViolations(tokenized)}",
            plateViolations(tokenized).isEmpty(),
        )
        // 注释里的 logoPlate() 不算"消费"（铁律①）：样本故意**不预先**剔注释，验判据自己会剔
        val commentOnly = sampleTeamLogo("Color.White").replace(
            "background(Color.White)",
            "background(Color.White) // 以后要换成 logoPlate()",
        )
        assertTrue(
            "注释里的 logoPlate() 被当成了消费 —— 铁律①（先剔注释/字符串）没生效：" +
                "${plateViolations(commentOnly)}",
            plateViolations(commentOnly).isNotEmpty(),
        )
        // 声明也不算消费：只有 `fun logoPlate(` 的定义、圆底却是别的写法
        val declarationOnly = listOf(
            "private fun logoPlate(): Color = MaterialTheme.colorScheme.surface",
            "fun TeamLogo(url: String?) {",
            "    SubcomposeAsyncImage(modifier = Modifier.background(MaterialTheme.colorScheme.surface))",
            "}",
        ).joinToString("\n")
        assertTrue(
            "判据把 `fun logoPlate(` 的**声明**当成了消费（声明不算使用，AGENT_STATE 教训）：" +
                "${plateViolations(declarationOnly)}",
            plateViolations(declarationOnly).isNotEmpty(),
        )
        // 直接引用原始色板也不算消费主题层 token（组件层不许 import 色板，P2-29）
        val paletteDirect = sampleTeamLogo("SportDark.LogoPlate")
        assertTrue(
            "判据没抓到「组件直接引用色板」这种写法（应改走 logoPlate()）：" +
                "${plateViolations(paletteDirect)}",
            plateViolations(paletteDirect).isNotEmpty(),
        )
    }

    @Test
    fun `门槛不是摆设 原来的硬编码白必须被判不合格`() {
        // 反向锚点：把"原缺陷的取值"钉进判据 —— 门槛若被改松，这条会先红。
        val ratio = AccentContrast.contrast(Color.White, SportLight.Card)
        assertTrue(
            "硬编码白压浅色卡片竟然过了门槛（实测 ${"%.2f".format(ratio)}:1）—— " +
                "门槛写歪了，这条守卫拦不住 03-P2-30 的原缺陷（原缺陷应为 1.00:1）",
            ratio < EDGE_FLOOR,
        )
    }

    // ---------------- 纯判据：真实 token 的 WCAG 对比度 ----------------

    @Test
    fun `队徽圆底 token 在浅色主题下必须与卡片可辨`() {
        // 直接从真实 token 算（不另抄色值，也不另写一份 WCAG 算术：用 AccentContrast.contrast）
        val onCard = AccentContrast.contrast(SportLight.LogoPlate, SportLight.Card)
        assertTrue(
            "浅色主题的队徽圆底（SportLight.LogoPlate）压在卡片（SportLight.Card）上只有 " +
                "${"%.2f".format(onCard)}:1 —— 低于「能看出圆形」的下限 ${EDGE_FLOOR}:1，" +
                "就是 03-P2-30 的原缺陷（白盘压白卡 1.00:1，边缘完全消失）。门槛取值与理由见类 KDoc。",
            onCard >= EDGE_FLOOR,
        )
        // 列表里也会落在第二档卡片上（surfaceContainerLow = Card2），一起守
        val onCard2 = AccentContrast.contrast(SportLight.LogoPlate, SportLight.Card2)
        assertTrue(
            "浅色主题的队徽圆底压在第二档卡片（SportLight.Card2）上只有 " +
                "${"%.2f".format(onCard2)}:1 —— 低于 ${EDGE_FLOOR}:1",
            onCard2 >= EDGE_FLOOR,
        )
    }

    @Test
    fun `队徽圆底 token 在深色主题下必须可辨 且不许把白盘换成暗盘`() {
        val ratio = AccentContrast.contrast(SportDark.LogoPlate, SportDark.Card)
        assertTrue(
            "深色主题的队徽圆底压在卡片上只有 ${"%.2f".format(ratio)}:1 —— 低于 ${EDGE_FLOOR}:1",
            ratio >= EDGE_FLOOR,
        )
        // 深色主题的**不许退化**线：原来是"白圆底 + 深卡片"，换成暗盘也能过 1.5:1，
        // 但那是视觉退化（review 只要求浅色那半边修好，深色那半边不许动坏）。
        assertTrue(
            "深色主题的队徽圆底被压暗到了 ${"%.2f".format(ratio)}:1（要求 ≥${DARK_NO_REGRESSION}:1）：" +
                "原来这里是白圆底 + 深卡片，改成暗盘虽然也能「看出圆形」，" +
                "但那是深色主题的视觉退化",
            ratio >= DARK_NO_REGRESSION,
        )
    }

    @Test
    fun `圆底 token 必须随主题返回不同的一档`() {
        assertEquals(
            "logoPlateFor(darkTheme = true) 必须给出深色档（这是深浅的**唯一判据**）",
            SportDark.LogoPlate,
            logoPlateFor(darkTheme = true),
        )
        assertEquals(
            "logoPlateFor(darkTheme = false) 必须给出浅色档",
            SportLight.LogoPlate,
            logoPlateFor(darkTheme = false),
        )
        assertTrue(
            "深浅两档是同一个颜色 —— 等于没随主题走：浅色主题会重新掉回 1.00:1 的白盘压白卡",
            logoPlateFor(darkTheme = true) != logoPlateFor(darkTheme = false),
        )
    }

    @Test
    fun `NbaLiveTheme 必须把圆底 token 按当前主题提供出去`() {
        val themeText = themeFile().readText()
        val body = GuardSourceScan.functionBody(themeText, "fun NbaLiveTheme(")
        val provider = GuardSourceScan.callBlocks(body, "CompositionLocalProvider")
            .firstOrNull { it.contains("LocalLogoPlate") }
        assertTrue(
            "NbaLiveTheme 没有把队徽圆底 token 提供出去（组件拿到的会是默认深色档）：\n$body",
            provider != null,
        )
        assertTrue(
            "提供的必须是 `logoPlateFor(darkTheme)` —— 深浅只在那一处判，不许在提供处再写一遍 if：\n$provider",
            WIRING.containsMatchIn(provider!!),
        )
        assertTrue(
            "LocalLogoPlate 必须定义在主题层 Theme.kt（色值不许散在组件里）：" +
                "找不到 `val LocalLogoPlate =`",
            Regex("""\bval\s+LocalLogoPlate\s*=""").containsMatchIn(GuardSourceScan.stripNonCode(themeText)),
        )
    }

    // ---------------- 基础设施 ----------------

    /**
     * [code]（已剔或未剔非代码都可，内部会再剔一次）里 TeamLogo 圆底的违规项。
     *
     * 三条判据：
     *  - `background(...)` 的**实参块**里必须出现 `logoPlate(`（匹配调用，不看裸标识符）；
     *  - 不得出现硬编码白（`Color.White` / `Color(0xFFFFFFFF)` 这类等价写法）；
     *  - 也不得直接引用原始色板（`SportDark.LogoPlate`）—— 组件层只消费主题层提供的 token。
     */
    private fun plateViolations(code: String): List<String> {
        val stripped = GuardSourceScan.stripNonCode(code)
        val out = mutableListOf<String>()
        val plateCalls = GuardSourceScan.callBlocks(stripped, "background")
            .filter { it.contains("logoPlate(") }
        if (plateCalls.isEmpty()) {
            out += "圆底 background(...) 没有消费 logoPlate()（组件里写死了色值？）"
        }
        val hard = HARDCODED_WHITE.findAll(stripped).map { it.value }.toList()
        if (hard.isNotEmpty()) {
            out += "出现硬编码白：${hard.joinToString()}"
        }
        val palette = RAW_PALETTE.findAll(stripped).map { it.value }.toList()
        if (palette.isNotEmpty()) {
            out += "直接引用了原始色板（应走主题层 token）：${palette.joinToString()}"
        }
        return out
    }

    /** 内存样本：一个最小 `TeamLogo` 函数体，圆底那行由 [background] 决定。 */
    private fun sampleTeamLogo(background: String): String =
        listOf(
            "fun TeamLogo(url: String?, teamName: String, size: Dp, modifier: Modifier) {",
            "    SubcomposeAsyncImage(",
            "        modifier = modifier.size(size).clip(CircleShape).background($background),",
            "    )",
            "}",
        ).joinToString("\n")

    private fun commonFile(): File = GuardSourceScan.uniqueFile(productionFiles(), "Common.kt")

    private fun themeFile(): File = GuardSourceScan.uniqueFile(productionFiles(), "Theme.kt")

    private fun productionFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd）—— 本文件所有登记会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        /** 「能看出圆形」的下限；取值与理由见类 KDoc。 */
        const val EDGE_FLOOR = 1.5

        /**
         * 深色主题的**不许退化**线：原来是白圆盘（16.83:1），不许被压成暗盘。
         * 10 这个数取得比 1.5 保守得多，是"别把深色那半边改坏"的意思，不是可达性门槛。
         */
        const val DARK_NO_REGRESSION = 10.0

        /** 硬编码白：`Color.White` 与 `Color(0xFFFFFFFF)` 这类等价写法都要抓（审计 A1/A7）。 */
        val HARDCODED_WHITE = Regex("""\bColor\s*\.\s*White\b|\bColor\s*\(\s*0x[fF]{8}\s*\)""")

        /** 组件层直接引用原始色板（P2-29 的越界形态）。 */
        val RAW_PALETTE = Regex("""\b(?:SportDark|SportLight)\s*\.\s*\w+""")

        /** 圆底 token 的提供式：`LocalLogoPlate provides logoPlateFor(darkTheme)`（允许具名实参）。 */
        val WIRING = Regex("""LocalLogoPlate\s+provides\s+logoPlateFor\s*\(\s*darkTheme\b""")
    }
}