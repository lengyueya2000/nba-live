package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动点「画中画」按钮的守卫（review/01-player.md P2-6）。
 *
 * ## 要解决什么
 *
 * 按钮的 onClick 直接调 `onEnterPip()`，`enterPip()` 只判系统版本与硬件特性，
 * **不判当前有没有流可播**。于是：
 *  - 线路还在解析时点画中画 → 得到一块转圈/黑的小窗
 *  - 所有线路都失败（界面显示"暂无信号"）时点 → 得到一块坏掉的小窗
 *
 * 「按 Home 自动进小窗」那条路早就有 `shouldAutoEnterPip` 守卫
 * （`PipAutoEnterTest` 覆盖了），**手动按钮这条路没有** —— 同一个问题两条路径、
 * 只有一条被守住，是典型的"改了一半"。
 *
 * ## 规则
 *
 * 手动进小窗与自动进小窗用**同一条**判据（[PipState.shouldAutoEnterPip]），
 * 但给用户的反馈不同：
 *  - 自动（按 Home）：静默不弹，用户本来就没打算小窗；
 *  - 手动（点按钮）：必须给一句提示，否则用户以为按钮坏了。
 */
class ManualPipGuardTest {

    @Test
    fun `没有流时点画中画要给提示 而不是弹黑窗`() {
        val decision = ManualPipPolicy.decide(isPlayerRoute = true, hasStream = false)
        assertTrue(
            "没有流可播时点画中画必须拦住（否则得到一块黑/转圈小窗）",
            decision is ManualPipDecision.Blocked,
        )
        assertTrue(
            "拦住时必须给用户提示，否则他会以为按钮坏了",
            (decision as ManualPipDecision.Blocked).message.isNotBlank(),
        )
    }

    @Test
    fun `正在解析时点画中画同样要拦住`() {
        // 解析中 = 还没有流；界面上是转圈，进小窗就是转圈小窗
        assertTrue(
            ManualPipPolicy.decide(isPlayerRoute = true, hasStream = false) is ManualPipDecision.Blocked,
        )
    }

    @Test
    fun `有流可播时正常进小窗`() {
        assertEquals(
            ManualPipDecision.Allow,
            ManualPipPolicy.decide(isPlayerRoute = true, hasStream = true),
        )
    }

    @Test
    fun `不在播放页时也不该进小窗`() {
        // 防御：按钮只在播放页可见，但状态机不该依赖 UI 的可见性
        assertTrue(
            ManualPipPolicy.decide(isPlayerRoute = false, hasStream = true) is ManualPipDecision.Blocked,
        )
    }

    @Test
    fun `手动与自动用同一条判据 避免两条路径漂移`() {
        // 关键约束：两条路径的"能不能进小窗"必须完全一致。
        // 不一致会出现"按 Home 不进、点按钮进"这种自相矛盾的行为。
        for (playerRoute in listOf(true, false)) {
            for (hasStream in listOf(true, false)) {
                val auto = PipState.shouldAutoEnterPip(playerRoute, hasStream)
                val manual = ManualPipPolicy.decide(playerRoute, hasStream) == ManualPipDecision.Allow
                assertEquals(
                    "手动与自动的判定必须一致（isPlayerRoute=$playerRoute, hasStream=$hasStream）",
                    auto,
                    manual,
                )
            }
        }
    }

    // ---------- P2-5 小窗比例不沿用上一场 ----------

    @Test
    fun `切场后小窗比例要复位 不能沿用上一场的宽高`() {
        // 回归：setVideoSize 只在 onVideoSizeChanged 里写，切场时没人清零 ——
        // 新场次的首帧到达之前，小窗按**上一场**的比例显示。
        assertTrue(
            "切场时必须把视频尺寸复位（否则小窗沿用上一场比例）",
            PipAspectReset.shouldResetOnMatchSwitch(),
        )
    }

    @Test
    fun `复位后回退到 16比9`() {
        val ratio = PipAspectReset.ratioFor(0, 0)
        assertEquals("没有真实宽高时用 16:9 兜底", 16f / 9f, ratio, 0.001f)
    }

    @Test
    fun `有真实宽高时用真实比例`() {
        val ratio = PipAspectReset.ratioFor(1920, 1080)
        assertEquals(1920f / 1080f, ratio, 0.001f)
    }

    @Test
    fun `异常宽高不让比例变成 NaN 或负数`() {
        // 负数/0 都来自"还没拿到"或"播放器给了脏数据"
        for ((w, h) in listOf(-1 to 1080, 1920 to 0, 0 to 0, -5 to -5)) {
            val ratio = PipAspectReset.ratioFor(w, h)
            assertFalse("宽高 ($w, $h) 不能算出非法比例 $ratio", ratio.isNaN() || ratio <= 0f)
        }
    }

    // ---------- B3：小窗比例判据只有一个来源（review/audit-guards.md B3） ----------

    @Test
    fun `小窗比例判据在生产里只有一个来源`() {
        // 事实：`PipAspectReset.ratioFor` 被上面 3 条用例钉住，生产却零调用；
        // 真正生效的是 MainActivity.pipAspectRatio() 里另写的一份
        // "真实比例 + 非法宽高回退 16:9"。规则两份实现 -> 改一处不影响另一处。
        val body = functionBody(mainActivityCode(), "fun pipAspectRatio(")
        assertTrue(
            "MainActivity.pipAspectRatio 的函数体里没有 PipAspectReset.ratioFor(...) 调用：" +
                "「视频比例」这条规则的唯一来源必须是 PipAspectReset.ratioFor，" +
                "这里只能做系统上下限夹取与约分（review/audit-guards.md B3）。\n" +
                "实际函数体：\n$body",
            body.contains("PipAspectReset.ratioFor("),
        )
    }

    @Test
    fun `比例判据自检 注释与字符串里的调用不算接线 且不吃过函数结尾`() {
        val sample = listOf(
            "class Sample {",
            "    // 注释里提 PipAspectReset.ratioFor( 不算调用",
            "    private fun pipAspectRatio(): Int {",
            "        val s = \"PipAspectReset.ratioFor(1, 1) 字符串里也不算\"",
            "        val r = PipAspectReset.ratioFor(1920, 1080)",
            "        return r.toInt()",
            "    }",
            "    fun other() = 0",
            "}",
        ).joinToString("\n")
        val body = functionBody(sample, "fun pipAspectRatio(")
        assertTrue(
            "自检样本里的真实调用没抓到（判据已退化成摆设）：$body",
            body.contains("PipAspectReset.ratioFor("),
        )
        assertTrue(
            "函数体提取吃过了函数结尾（把别的函数也吞进来）：$body",
            !body.contains("fun other("),
        )

        val commentOnly = listOf(
            "class Sample {",
            "    private fun pipAspectRatio(): Int {",
            "        // PipAspectReset.ratioFor(1, 1) 只在注释里",
            "        val s = \"PipAspectReset.ratioFor(1, 1) 只在字符串里\"",
            "        return 16",
            "    }",
            "}",
        ).joinToString("\n")
        assertFalse(
            "判据命中了注释/字符串里的字面量（剔非代码那一步没生效）",
            functionBody(commentOnly, "fun pipAspectRatio(").contains("PipAspectReset.ratioFor("),
        )
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        val files = productionCode()
        assertTrue("只扫到 ${files.size} 个生产文件（应 ≥30，cwd=${cwd()}）", files.size >= 30)
        assertTrue(
            "没扫到 MainActivity.kt —— 上面的唯一来源守卫会静默全绿",
            files.containsKey("MainActivity.kt"),
        )
        assertTrue(
            "没扫到 ManualPipPolicy.kt（ratioFor 的声明处）",
            files.containsKey("ManualPipPolicy.kt"),
        )
    }

    // ---------------------------------------------------------------

    private fun mainActivityCode(): String = code("MainActivity.kt")

    private fun code(name: String): String = productionCode()[name]
        ?: error("找不到 $name（cwd=${cwd()}）—— 相关守卫会静默全绿，所以直接失败")

    /** 生产源码：文件名 → 已剔除注释与字符串的代码（行号不变）。 */
    private fun productionCode(): Map<String, String> =
        productionRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to GuardSourceScan.stripNonCode(it.readText()) }

    private fun productionRoot(): File =
        listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=${cwd()}）—— 守卫会静默全绿")

    /**
     * 从 `declaration`（如 `fun pipAspectRatio(`）开始、按花括号配对截出的函数体。
     *
     * ⚠️ 先 [GuardSourceScan.stripNonCode]：删掉真调用、只在同一行留一句注释，
     * 断言也必须变红（审计 A1 的教训）。找不到声明直接 `error()` 硬失败。
     */
    private fun functionBody(rawText: String, declaration: String): String {
        val code = GuardSourceScan.stripNonCode(rawText)
        val start = code.indexOf(declaration)
        if (start < 0) error("找不到声明 `$declaration`（守卫会静默失效，所以直接失败）")
        val open = code.indexOf('{', start)
        if (open < 0) error("`$declaration` 后面找不到函数体起始 `{`")
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        error("`$declaration` 的函数体花括号没有配对")
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath
}