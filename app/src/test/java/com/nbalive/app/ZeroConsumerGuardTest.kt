package com.nbalive.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「零消费点的声明」登记表守卫（review/audit-guards.md 第二节 B1–B11）。
 *
 * ## 拦的是什么
 *
 * 只读审计把「假安全感」分成两半，[GuardHygieneTest] 与 `DeadCodeWiringGuardTest`
 * 管的是第一半（**永远不会红的守卫**：命中注释、声明即满足、assumeTrue 静默跳过）。
 * 这个文件管第二半：**声明写好了、单测也写了、但生产代码从不调用**。
 *
 * 这种缺口最阴的地方是它**看起来有覆盖**：
 *  - `PipState.shouldKeepPip` 有 3 条用例，可 grep 生产零调用 —— 整个
 *    `PipExitOnErrorTest` 守的是一段线上跑不到的代码；
 *  - `PlaybackHealth.showsSuspectHint` 的 KDoc 承诺 SUSPECT 会「显示提示」，
 *    而生产只实现了后半（停自动进小窗），用户看到的是嗅探超时后界面毫无提示；
 *  - `PipAspectReset.ratioFor` 被 3 条用例钉住，而 `MainActivity.pipAspectRatio()`
 *    自己另写了一份同样的「比例 + 16:9 兜底」—— 规则两份实现，被测试的那份没人用。
 *
 * 单测永远发现不了这些（测的正是那个没人调的函数），只能**扫源码看调用点**。
 *
 * ## 与 `DeadCodeWiringGuardTest` 的分工
 *
 * 那个文件是「逐条手写断言」，好处是文案具体；坏处是**新增一条接线时没人记得加**，
 * 而且同一个形态（剔注释 → 排除声明行 → 计数）被抄了很多遍。
 * 这里做的是**登记表 + 一个共用判据**：
 *  - [WIRINGS]：符号 → 必须出现调用的生产文件与最少次数；
 *  - [BODY_WIRINGS]：必须出现在**某个函数体内**的委托（防止"判据两份实现"）；
 *  - [BANNED]：已删除的零消费形参/重载**不许复活**（反向断言）；
 *  - [SINGLE_SOURCE]：同一份字面量/色值在生产里只允许存在一份。
 *
 * ## 三条铁律（与全仓守卫一致）
 *
 * 1. 断言前先 [GuardSourceScan.stripNonCode]（连字符串字面量一起剔，行号不变）；
 * 2. **声明不算使用**：`fun shouldKeepPip(` / `val ACTION_LABEL` 这类行先排除再计数；
 * 3. 扫描范围/文件缺失一律 `error()` 硬失败，绝不用 `assumeTrue` 静默跳过；
 *    另有 [扫描必须真的扫到生产源码] 兜住"零命中"的空扫。
 *
 * ⚠️ 本文件自身也遵守 [GuardHygieneTest] 的元纪律（禁 assume 家族），
 * 并且它的判据函数有**内存样本自检**：喂真实违规写法必须命中、喂注释/字符串必须不命中，
 * 避免这个守卫退化成「永远的绿」（审计 A9 批评过的那种永真自检）。
 */
class ZeroConsumerGuardTest {

    /** 某个符号必须至少在指定生产文件里出现 [minCalls] 次**调用**（排除声明行）。 */
    private data class Wiring(
        val symbol: String,
        val file: String,
        val minCalls: Int,
        val why: String,
    )

    /** 某个调用必须出现在指定文件的**某个函数体内部**（而不是文件里随便一处）。 */
    private data class BodyWiring(
        val file: String,
        val declaration: String,
        val callee: String,
        val why: String,
    )

    /** 已经删掉的零消费形态，不许复活。 */
    private data class Banned(
        val fragment: String,
        val why: String,
    )

    private val WIRINGS = listOf(
        Wiring(
            symbol = "PipAspectReset.ratioFor(",
            file = "MainActivity.kt",
            minCalls = 1,
            why = "B3【高】：小窗比例判据（含非法宽高回退 16:9）在生产里曾有两份实现，" +
                "被 3 条用例钉住的那份没人调 —— 改一处不影响另一处",
        ),
        Wiring(
            symbol = "MissingMatchPolicy.forPlayer(",
            file = "NbaLiveApp.kt",
            minCalls = 1,
            why = "B4【中】：`forPlayer` 写好了却只有测试在调，PLAYER 路由缺 match 时" +
                "直接交给播放页 -> 一块空播放器而不是占位（与详情页不对称）",
        ),
        Wiring(
            symbol = "PlaybackHealth.showsSuspectHint(",
            file = "NbaLiveApp.kt",
            minCalls = 1,
            why = "B2【高】：KDoc 与 review/01-player.md:41 都要求 SUSPECT「用于显示提示」，" +
                "生产零调用 = 嗅探超时后界面毫无提示（`partialError` 同款形态）",
        ),
        Wiring(
            symbol = "CardSemantics.ACTION_LABEL",
            file = "LiveNowScreen.kt",
            minCalls = 1,
            why = "B6【中】：动作词必须有真实生产调用点（原来 `actionLabel(hasInnerButton)` " +
                "的参数被实现忽略，制造「两处已区分」的假象）",
        ),
        Wiring(
            symbol = "CardSemantics.ACTION_LABEL",
            file = "MatchListScreen.kt",
            minCalls = 1,
            why = "B6【中】：hero 卡的动作词必须与直播卡共用同一个常量，不许各写一份字面量",
        ),
        Wiring(
            symbol = "PlaybackHealth.pipDisposition(",
            file = "MainActivity.kt",
            minCalls = 1,
            why = "B1【高】：小窗处置的唯一判据；被删掉的 `PipState.shouldKeepPip` 就是" +
                "因为没人调它，处置链路才在 v1.2.9 之后「从来没生效过」",
        ),
        Wiring(
            symbol = "PipAspectReset.shouldResetOnMatchSwitch(",
            file = "PlayerScreen.kt",
            minCalls = 1,
            why = "01-P2-5：切场必须复位小窗视频尺寸，否则新场次首帧到达前按上一场的比例显示",
        ),
        Wiring(
            symbol = "PipState.shouldAutoEnterPip(",
            file = "ManualPipPolicy.kt",
            minCalls = 1,
            why = "手动点画中画必须与「按 Home 自动进小窗」共用同一条判据（01-P2-6）",
        ),
        Wiring(
            symbol = "PipState.shouldAutoEnterPip(",
            file = "AppEffects.kt",
            minCalls = 1,
            why = "自动进小窗的判据必须真的被消费 —— 03-P2-18 拆分后由 AppEffects 的 " +
                "AppPipEffects 负责（原来长在 NbaLiveApp 根节点里）",
        ),
    )

    private val BODY_WIRINGS = listOf(
        BodyWiring(
            file = "StalledPlayback.kt",
            declaration = "fun onTick(",
            callee = "evaluate(",
            why = "B5【中】：`evaluate` 与 `Tracker.onTick` 曾各写一份阈值判据；" +
                "onTick 必须**委托** evaluate（判据唯一来源），否则改一处不影响另一处",
        ),
        BodyWiring(
            file = "MainActivity.kt",
            declaration = "fun finishPipIfBroken(",
            callee = "PlaybackHealth.pipDisposition(",
            why = "B1【高】：收小窗的处置权限必须由 pipDisposition 决定，" +
                "不许在 Activity 里另判一遍等级",
        ),
        BodyWiring(
            file = "PlayerScreen.kt",
            declaration = "fun PlayerScreen(",
            callee = "ManualPipPolicy.decide",
            why = "01-P2-6：手动点画中画必须走守卫（解析中/全失败时点出来是一块黑窗）",
        ),
    )

    private val BANNED = listOf(
        Banned(
            fragment = "shouldKeepPip",
            why = "B1【高】：生产零调用的死函数（`PipExitOnErrorTest` 整个类守的是它）。" +
                "已删除，不要复活；小窗处置请走 PlaybackHealth.pipDisposition",
        ),
        Banned(
            fragment = "shouldMergeIntoParent",
            why = "B6【中】：恒等函数，唯一生产调用传常量 `true`，测试却断言 `false` 分支 —— " +
                "断的是生产永不走的路径。合并语义直接写在渲染处",
        ),
        Banned(
            fragment = "fun shouldBlock(url: String?, currentHostPageUrl: String?)",
            why = "B10【低】：3 参 `shouldBlock` 已有 `isMainFrame = true` 默认值，" +
                "2 参重载是「只给测试用」的多余入口，已删除",
        ),
    )

    // ---------------------------------------------------------------

    @Test
    fun `登记表里的每个符号都必须在生产里有真实调用点`() {
        val code = productionCode()
        val violations = WIRINGS.mapNotNull { wiring ->
            val text = code[wiring.file]
                ?: error(
                    "找不到生产文件 ${wiring.file}（cwd=${cwd()}）—— " +
                        "这条登记会静默失效，所以直接失败而不是跳过",
                )
            val calls = callSites(text, wiring.symbol)
            if (calls >= wiring.minCalls) null
            else "${wiring.file} 里 ${wiring.symbol} 的调用点只有 $calls 处（要求 ≥${wiring.minCalls}）"
        }
        assertTrue(
            "以下声明在生产里**没有真实调用点**（写了、测了、没人用 = 假安全感）：\n" +
                violations.joinToString("\n") + "\n\n" +
                WIRINGS.joinToString("\n") { w -> "  · ${w.symbol} -> ${w.file}：${w.why}" },
            violations.isEmpty(),
        )
    }

    @Test
    fun `关键判据必须是函数体内委托 而不是文件里随便出现一次`() {
        val code = productionCode()
        val violations = BODY_WIRINGS.mapNotNull { wiring ->
            val text = code[wiring.file]
                ?: error("找不到生产文件 ${wiring.file}（cwd=${cwd()}）")
            val body = functionBody(text, wiring.declaration)
            if (body.contains(wiring.callee)) null
            else "${wiring.file} 的 ${wiring.declaration} 体内没看到 ${wiring.callee} 调用"
        }
        assertTrue(
            "以下委托关系断了（判据出现了第二份实现，或调用点被换成等价写法）：\n" +
                violations.joinToString("\n") + "\n\n" +
                BODY_WIRINGS.joinToString("\n") { w ->
                    "  · ${w.file} ${w.declaration} 必须调 ${w.callee}：${w.why}"
                },
            violations.isEmpty(),
        )
    }

    @Test
    fun `已删除的零消费形态不许复活`() {
        val found = productionCode().flatMap { (name, text) ->
            bannedHits(text).map { "${name}: $it" }
        }
        assertTrue(
            "以下「零消费形态」又回到了生产**代码**里：\n" +
                found.joinToString("\n") + "\n\n" +
                BANNED.joinToString("\n") { b -> "  · ${b.fragment}：${b.why}" },
            found.isEmpty(),
        )
    }

    @Test
    fun `同名色值只许有一份来源`() {
        // B11【低】：红橙三档在 Theme.kt:107、Theme.kt:112、AccentContrast.kt:54-56
        // 各写了一遍。后果：AccentContrastTest 的 3 条 "hero 卡 AA 4.5:1"
        // 遍历的是**平行副本** —— 改 Theme.kt 的界面底色可能掉出 AA，断言照样绿。
        val code = productionCode()
        for (uniqueLiteral in listOf("0xFFFF2E4D", "0xFFFF6B2C")) {
            val hits = filesWithCode(code, uniqueLiteral)
            assertTrue(
                "$uniqueLiteral 在 ${hits.size} 个文件的**代码**里出现：$hits —— " +
                    "渐变色值必须只有一个来源（LiveGradientSource），" +
                    "否则对比度守卫测的可能只是其中一份副本",
                hits.size == 1,
            )
        }
        val derived = callSites(code.values.joinToString("\n"), "LiveGradientSource")
        assertTrue(
            "LiveGradientSource 的引用只有 $derived 处（要求 ≥3：GradLive / GradLiveOnWhite / " +
                "AccentContrast.HeroGradientStops 都要从它派生）—— " +
                "少于 3 就说明又有人把色值抄成了独立一份",
            derived >= 3,
        )
    }

    @Test
    fun `扫描必须真的扫到生产源码`() {
        // 非空性锚点：cwd 一变，上面几条会"零违规"地一起变绿，而「全绿」看不出区别。
        val files = productionCode()
        assertTrue("只扫到 ${files.size} 个生产文件（应 ≥40，cwd=${cwd()}）", files.size >= 40)
        for (anchor in ANCHOR_FILES) {
            assertTrue(
                "没扫到 $anchor —— 本文件的登记项会静默失效",
                files.containsKey(anchor),
            )
        }
    }

    @Test
    fun `判据自检 命中真实调用而放过注释声明与字符串`() {
        // 审计 A9 批评过「自检本身是永真断言」：这里喂给**真正参与扫描的那个函数**，
        // 而不是把判据在自检里复述一遍。
        val sample = listOf(
            "package sample",
            "// 注释里提 PipAspectReset.ratioFor( 不算调用",
            "/* 块注释里的 PipAspectReset.ratioFor( 同样不算 */",
            "class Sample {",
            "    private fun ratioFor(width: Int, height: Int): Float = 16f / 9f",
            "    fun use() {",
            "        val bad = \"PipAspectReset.ratioFor(1, 1) 在字符串里也不算\"",
            "        val r = PipAspectReset.ratioFor(1920, 1080)",
            "    }",
            "}",
        ).joinToString("\n")
        assertTrue(
            "判据抓不到真实调用（它已经退化成摆设）—— " +
                "样本里那一行 `PipAspectReset.ratioFor(1920, 1080)` 必须被数到",
            callSites(sample, "PipAspectReset.ratioFor(") == 1,
        )
        assertTrue(
            "判据把**声明行**也算成了使用：`private fun ratioFor(` 不该被计数（声明不算使用）",
            callSites(sample, "ratioFor(") == 1,
        )
        val commentOnly = listOf(
            "// PipAspectReset.ratioFor( 只在注释里",
            "val s = \"PipAspectReset.ratioFor( 只在字符串里\"",
        ).joinToString("\n")
        assertTrue(
            "判据误报了注释/字符串里的字面量（剔非代码的那一步没生效）",
            callSites(commentOnly, "PipAspectReset.ratioFor(") == 0,
        )

        // 反向断言的自检：注释里**点名**被删掉的符号（解释"为什么删"）不算复活，
        // 只有真的又出现调用才算。这条自检如果缺席，这条守卫就会冤枉正确的注释
        // —— 而且它是被队友评审抓到的真实 bug（见 [bannedHits]）。
        assertTrue(
            "反向断言把解释性注释也当成了复活：`// shouldKeepPip 已删除` 不该命中",
            bannedHits("// shouldKeepPip 已删除，处置走 pipDisposition").isEmpty(),
        )
        assertTrue(
            "反向断言漏掉了真正的复活（真的又出现调用时必须命中）",
            bannedHits("val keep = PipState.shouldKeepPip(true, false)").contains("shouldKeepPip"),
        )
        assertTrue(
            "色值判据把注释里的色值也当成了第二份来源",
            filesWithCode(mapOf("A.kt" to "// 以前这里写过 0xFFFF2E4D"), "0xFFFF2E4D").isEmpty(),
        )
        assertTrue(
            "色值判据漏掉了真正的第二份来源",
            filesWithCode(mapOf("A.kt" to "val x = Color(0xFFFF2E4D)"), "0xFFFF2E4D") == listOf("A.kt"),
        )

        // 函数体提取自检：必须停在本函数结尾，不能一路吃到文件末尾；
        // 而且必须跳过**参数表默认值里的 lambda**（Compose 形参几乎全是 `= {}`，
        // 直接找第一个 `{` 会拿到那个空 lambda，函数体永远是空的 —— 第一次跑这个
        // 文件时 `PlayerScreen` 的判据就是这么假绿的）。
        val bodySample = listOf(
            "class Sample {",
            "    fun wrapped(x: Int, onTick: () -> Unit = {}, note: String = \"{ 字符串里的花括号 }\") {",
            "        if (evaluate(x) != 1) onTick()",
            "    }",
            "    fun other() { /* 故意不放 evaluate */ }",
            "}",
        ).joinToString("\n")
        val body = functionBody(bodySample, "fun wrapped(")
        assertTrue(
            "函数体提取没抓到体内调用（很可能被参数表默认 lambda 骗了）：$body",
            body.contains("evaluate("),
        )
        assertTrue(
            "函数体提取吃过了函数结尾（把别的函数也吞进来了）",
            !body.contains("fun other("),
        )
    }

    // ---------------------------------------------------------------

    /**
     * [rawText] 里命中的"已删除形态"。**必须先剔注释与字符串**：
     *
     * ⚠️ 这条判据最初的写法是裸 `text.contains(fragment)`，于是被
     * `CardSemantics.kt` / `LiveNowScreen.kt` 里**解释"为什么删掉它"的注释**命中 ——
     * 一条会冤枉正确注释的守卫。这正是本仓库铁律①（"断言前必须剔注释"）
     * 要拦的形态，而它发生在一个专门拦"假安全感"的守卫里（队友评审时抓到的）。
     * 现在由 [判据自检 命中真实调用而放过注释声明与字符串] 里的样本钉住。
     */
    private fun bannedHits(rawText: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawText)
        return BANNED.map { it.fragment }.filter { code.contains(it) }
    }

    /** 哪个生产文件的**代码**（已剔注释/字符串）里出现了 [fragment]。 */
    private fun filesWithCode(code: Map<String, String>, fragment: String): List<String> =
        code.filterValues { GuardSourceScan.stripNonCode(it).contains(fragment) }.keys.toList()

    /**
     * [symbol] 在 [rawText] 里的**调用点**数量：
     * 先剔注释与字符串，再排除把该名字**声明**出来的行。
     */
    private fun callSites(rawText: String, symbol: String): Int {
        val name = symbol.substringBefore('(').substringAfterLast('.')
        val declaration = Regex("""\b(?:fun|val|var)\s+${Regex.escape(name)}\b""")
        return GuardSourceScan.stripNonCode(rawText).lines()
            .filterNot { declaration.containsMatchIn(it) }
            .count { it.contains(symbol) }
    }

    /**
     * 从 `declaration`（如 `fun onTick(`）开始、用花括号配对截出的函数体。
     *
     * ⚠️ 必须先跳过**参数表**（括号配对）再找函数体的 `{`：
     * Compose 的形参默认值里全是 lambda（`onPlaybackError: () -> Unit = {}`），
     * 直接找第一个 `{` 会拿到那个空 lambda，于是"函数体"里永远看不到任何调用 ——
     * 一条永远不会红的守卫（本文件第一次跑就是这么红的，见 B2/B4 的接线断言）。
     */
    private fun functionBody(rawText: String, declaration: String): String {
        val code = GuardSourceScan.stripNonCode(rawText)
        val start = code.indexOf(declaration)
        if (start < 0) {
            error("找不到声明 `$declaration`（守卫会静默失效，所以直接失败）")
        }
        val paramOpen = code.indexOf('(', start)
        if (paramOpen < 0) error("`$declaration` 后面找不到参数表 `(`")
        var parens = 0
        var cursor = paramOpen
        while (cursor < code.length) {
            when (code[cursor]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            cursor++
        }
        if (cursor >= code.length) error("`$declaration` 的参数表括号没配对")
        val open = code.indexOf('{', cursor)
        if (open < 0) error("`$declaration` 后面找不到函数体起始 `{`")
        if (code.substring(cursor + 1, open).contains('=')) {
            error("`$declaration` 看起来是表达式体函数（`= ...` 而不是 `{ ... }`），本判据不支持")
        }
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

    private fun productionCode(): Map<String, String> {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=${cwd()}）—— 本文件所有登记会静默全绿")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }
    }

    private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath

    private companion object {
        /** 扫描范围锚点：登记表涉及的每个生产文件都必须被扫到。 */
        val ANCHOR_FILES = listOf(
            "MainActivity.kt",
            "NbaLiveApp.kt",
            "AppEffects.kt",
            "AppChrome.kt",
            "PlayerScreen.kt",
            "StalledPlayback.kt",
            "PipState.kt",
            "PlaybackHealth.kt",
            "ManualPipPolicy.kt",
            "CardSemantics.kt",
            "Theme.kt",
            "AccentContrast.kt",
            "LiveNowScreen.kt",
            "MatchListScreen.kt",
        )
    }
}