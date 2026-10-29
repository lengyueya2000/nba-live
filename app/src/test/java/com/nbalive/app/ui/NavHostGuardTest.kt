package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航宿主 / 偏好写入的职责边界守卫（review/03-ui.md P2-4 / P2-9 / P2-16 / P2-17 / P2-31 / P2-35）。
 *
 * ## 为什么是源码守卫
 *
 * 这 6 条都没有"点一下就能看见"的行为 bug，它们是**同一条规则写了两遍**、
 * **写好了却没人用**、**注释与实现相反**这几种形态：
 * 普通单测永远是绿的，只有扫源码看调用点/注释才能发现。
 *
 * ## 这个仓库踩过的坑（本文件一律按这三条写）
 *
 *  1. 断言前**剔除注释**：注释里常写着"为什么这么改"，含同样的字面量；
 *  2. 匹配**调用**而不是字符串出现：`import` 行会让守卫永远绿；
 *  3. 路径解析失败 **error() 硬失败**，绝不 assumeTrue 静默跳过（那等于守卫不存在）。
 */
class NavHostGuardTest {

    // ---------------- P2-4：onNewIntent 是只调 setIntent 的死代码 ----------------

    @Test
    fun `onNewIntent 不能是只调 setIntent 的死代码`() {
        val raw = mainActivity().readText()
        val code = stripComments(raw)
        // 非空性锚点：读不到正文时下面的判据会"零违规"地全绿
        assertTrue("没读到 MainActivity 的正文，守卫会静默全绿", code.contains("class MainActivity"))

        val violation = onNewIntentViolation(raw)
        assertTrue(
            "MainActivity 声明了 onNewIntent，却没有任何读取 intent 的代码" +
                "（Manifest 的 intent-filter 只有 MAIN/LAUNCHER，没有 VIEW/BROWSABLE 深链）：\n" +
                "  ${violation}\n" +
                "只调 setIntent(intent) 是纯死代码，而注释把它写成了深链准备，" +
                "后来者会以为深链已经支持（review/03-ui.md P2-4）。" +
                "要么删掉这个空实现，要么真的消费 intent。",
            violation == null,
        )
    }

    @Test
    fun `onNewIntent 判据自检 换行写法与死代码都要能抓到`() {
        // ⚠️ 审计 A14：旧实现是 `if (!code.contains("override fun onNewIntent")) return`，
        //   a) `override` 与 `fun` 之间换行（Kotlin 合法写法）会**漏判**；
        //   b) 方法不存在时整条测试直接 return —— 当前这条测试是"永远绿"的。
        // 这里把内存样本喂给**参与生产判定**的 [onNewIntentViolation]，
        // 证明"以后有人把死代码加回来"一定会红。
        val dead = """
            class MainActivity : ComponentActivity() {
                override fun onNewIntent(intent: Intent?) {
                    super.onNewIntent(intent)
                    setIntent(intent)
                }
            }
        """.trimIndent()
        assertTrue("只调 setIntent 的死代码必须能抓到", onNewIntentViolation(dead) != null)

        // Kotlin 合法、但旧 `contains("override fun onNewIntent")` 会漏的写法
        val multiline = """
            class MainActivity : ComponentActivity() {
                override
                fun onNewIntent(intent: Intent?) {
                    super.onNewIntent(intent)
                    setIntent(intent)
                }
            }
        """.trimIndent()
        assertTrue(
            "override 与 fun 换行时必须能抓到（旧判据的 contains 在这里漏判）",
            onNewIntentViolation(multiline) != null,
        )

        val withAnnotation = """
            class MainActivity : ComponentActivity() {
                @Suppress("DEPRECATION")
                override fun onNewIntent(intent: Intent?) {
                    super.onNewIntent(intent)
                    setIntent(intent)
                }
            }
        """.trimIndent()
        assertTrue("声明前带注解时也必须能抓到", onNewIntentViolation(withAnnotation) != null)

        // 合规：真的消费 intent（将来加深链时不该被误报）
        val compliant = """
            class MainActivity : ComponentActivity() {
                override fun onNewIntent(intent: Intent?) {
                    super.onNewIntent(intent)
                    setIntent(intent)
                    intent?.data?.let { handleDeepLink(it) }
                }
            }
        """.trimIndent()
        assertTrue(
            "真的消费 intent 的写法被误报了：${onNewIntentViolation(compliant)}",
            onNewIntentViolation(compliant) == null,
        )

        // 反向锚点：注释里提到 onNewIntent 不算"声明了方法"（剔注释）
        val commentOnly = """
            class MainActivity : ComponentActivity() {
                // override fun onNewIntent(intent: Intent?) { setIntent(intent) }
            }
        """.trimIndent()
        assertTrue(
            "注释里的 onNewIntent 不该被当成实现（否则删掉实现、留一句注释照旧）",
            onNewIntentViolation(commentOnly) == null,
        )
    }

    // ---------------- P2-9：三个 remember 的其中两个已经冗余 ----------------

    @Test
    fun `列表派生值必须直接读 state 不能再包一层 remember`() {
        // ⚠️ 跨文件定位：派生值随 03-P2-18 拆分搬到了 ui/AppChrome.kt（原 NbaLiveApp.kt）。
        // 按内容唯一命中，找不到/多个都 error()。
        val code = stripComments(sourceContaining("val visibleMatches = listState.visibleMatches").readText())
        assertTrue(
            "visibleMatches 应直接读 listState.visibleMatches：MainViewModel 里它已经是" +
                " data class 的 val（构造时求值一次），再 remember 不产生任何收益，" +
                "只多持一份引用，还让读者以为这里必须 remember（review/03-ui.md P2-9）",
            code.contains("val visibleMatches = listState.visibleMatches"),
        )
        assertTrue(
            "counts 同上：应直接读 listState.counts（P2-9）",
            code.contains("val leagueCounts = listState.counts"),
        )
        assertFalse(
            "NbaLiveApp 又把 visibleMatches/counts 包回了 remember：\n" +
                "那是 getter 时代的旧问题（已在 MainViewModel 源头修掉），P2-9 明确要求删掉这层",
            REMEMBERED_DERIVED.containsMatchIn(code),
        )
    }

    @Test
    fun `派生值必须在 MainViewModel 里算一次而不是 getter`() {
        // P2-9 的**前提**：删掉这层 remember 之所以安全，是因为源头已经是 val。
        // 若哪天有人把它改回 getter，上面的直接读取就会变成每帧分配 ——
        // 这条守卫把那个前提钉住，避免"修法在源头被回退、守卫还全绿"。
        val code = stripComments(mainViewModel().readText())
        assertTrue(
            "MainViewModel.visibleMatches 必须是 data class 构造时求值的 val（P2-9）",
            Regex("""val visibleMatches: List<Match>\s*=""").containsMatchIn(code),
        )
        assertTrue(
            "MainViewModel.counts 必须是 data class 构造时求值的 val（P2-9）",
            Regex("""val counts: Map<League, Int>\s*=""").containsMatchIn(code),
        )
        assertFalse(
            "visibleMatches/counts 又被写成 getter 了：界面每次重组读它都会新建 List/Map（P2-9）",
            Regex("""val (visibleMatches|counts)[^\n]*get\(\)""").containsMatchIn(code),
        )
    }

    // ---------------- P2-16：null 该怎么解释只能有一份实现 ----------------

    @Test
    fun `null 怎么解释必须两处都走同一个纯函数`() {
        for (file in listOf(mainActivity(), nbaLiveApp())) {
            val code = stripComments(file.readText())
            assertTrue(
                "${file.name} 没有调用 resolveDarkMode(...)：null→深色是**一条业务规则**，" +
                    "不允许在 MainActivity 与 NbaLiveApp 各写一份（review/03-ui.md P2-16）。" +
                    "将来规则一变，漏改一处就会出现「主题是浅色、状态栏图标按深色画」的错配。",
                code.contains("resolveDarkMode("),
            )
            assertFalse(
                "${file.name} 里还留着自己写的 `darkPref ?: true` 兜底：" +
                    "同一业务规则两份实现（review/03-ui.md P2-16），必须改为调用 resolveDarkMode。",
                DARK_PREF_FALLBACK.containsMatchIn(code),
            )
        }
    }

    @Test
    fun `AppPrefs 的注释不得再宣称 null 等于跟随系统`() {
        // 这条故意**不剔注释** —— 它查的就是注释本身（P2-16：注释与行为相反）。
        val raw = appPrefs().readText()
        assertFalse(
            "AppPrefs 又宣称「null = 跟随系统」了：实现（resolveDarkMode）把 null 解释成" +
                "**深色**，注释与行为相反会让后来者照注释改代码（review/03-ui.md P2-16）。",
            LYING_COMMENT.containsMatchIn(raw),
        )
        assertTrue(
            "AppPrefs 必须写明 null 的真实语义（未手动设置过 → 深色）并指向唯一判定处 resolveDarkMode",
            raw.contains("未手动设置过") && raw.contains("resolveDarkMode"),
        )
    }

    // ---------------- P2-17：UI 层不能拿 LocalContext 写 SharedPreferences ----------------

    @Test
    fun `Composable 不得把 Context 传给偏好写入`() {
        // ⚠️ 判据从「ui/ 全目录 contains(AppPrefs.setDarkMode(…)」**收紧**为下面两条。
        //
        // 为什么必须改（不是因为红了才改 —— 旧的名字判据现在既漏又假）：
        // P2-17 修完后 setter **本身不再收 Context**（Context 只由平台层 attach 一次，
        // 见 AppPrefs/PlayerPrefs 的 attach），于是「setter 名字出现」不再等于
        // 「把 Context 传进 UI」。当前 ui/ 里唯一的出现处是 PrefsStore.kt:96/98 的
        // AndroidPrefsStore —— **平台层存储实现**（只有 MainActivity 构造它），
        // 调用时传的是普通 Boolean / AspectMode，没有任何 Context 进入 UI。
        // 旧判据漏的是「签名改成无 Context、调用点却仍传 context」，
        // 假的是「平台层存储实现里的一次普通值调用」。
        // 现在守住真正要守的两件事：
        //  ① 任何生产调用点/签名都不得再出现 Context（那才是 Context 漏进 UI）；
        //  ② UI **组合项**文件不得直接引用偏好单例（读/写都该走 VM 暴露的状态）。
        val withContext = productionFiles().flatMap { file ->
            setterContextViolations(file.name, file.readText())
        }
        assertTrue(
            "以下偏好写入的签名/调用里出现了 Context：\n" +
                withContext.joinToString("\n").prependIndent("  ") +
                "\nContext 只允许在平台层 attach 一次（AppPrefs/PlayerPrefs 的 attach），" +
                "之后 setter 收的必须是普通值（review/03-ui.md P2-17）。",
            withContext.isEmpty(),
        )

        val uiRefs = COMPOSABLE_FILES.flatMap { name ->
            val file = GuardSourceScan.uniqueFile(uiFiles(), name)
            PREF_SINGLETON_REF.findAll(stripComments(file.readText()))
                .map { "$name: ${it.value.trim()}" }
                .toList()
        }
        assertTrue(
            "以下 UI 组合项文件直接引用了偏好单例：\n" +
                uiRefs.joinToString("\n").prependIndent("  ") +
                "\n界面只该读 MainViewModel 暴露的状态（它由注入的 PrefsStore 提供）；" +
                "同向但更宽的判据见 PrefsBoundaryGuardTest（扫 ui/ 全目录）。",
            uiRefs.isEmpty(),
        )

        // 判据自检：违规样本必须命中、注释不许命中（否则这又是一条摆设守卫）。
        assertTrue(
            "「调用点传 Context」的样本没被抓到 —— 旧判据按 setter 名字匹配时这条是漏的",
            setterContextViolations("X.kt", "onDarkModeChange = { AppPrefs.setDarkMode(context, it) }").isNotEmpty(),
        )
        assertTrue(
            "签名带 Context 的样本没被抓到",
            setterContextViolations("X.kt", "fun setDarkMode(context: Context, dark: Boolean) {}").isNotEmpty(),
        )
        assertTrue(
            "注释里的 Context 不该误报",
            setterContextViolations("X.kt", "// AppPrefs.setDarkMode(context, it)").isEmpty(),
        )
    }

    @Test
    fun `偏好写入必须走 VM 写入口 且生产只有一个落盘实现`() {
        // ⚠️ 本判据在 03-P2-17 **写路径收口**时**重锚**，不是删掉：
        // 旧判据要求 `MainActivity` 里出现 `AppPrefs.setDarkMode(` / `PlayerPrefs.setAspect(`、
        // 且 UI 里出现 `onDarkModeChange = onDarkModeChange` 接线 —— 那是"平台层落盘"阶段的形态。
        // 收口后写路径是：UI → `viewModel.setDarkMode(...)` → `PrefsStore`（注入）
        // → `AndroidPrefsStore`（平台层）→ `AppPrefs/PlayerPrefs`。
        // 新判据守住新链路的真实消费点，强度不低于旧的（旧判据对"写入口没人消费"
        // 与"平台层又长出一个落盘点"这两种回归完全无感）：
        //  ① UI 生产代码里必须真的调 VM 写入口（否则开关点了不落盘）；
        //  ② `AppPrefs.setDarkMode(` / `PlayerPrefs.setAspect(` 在生产里**只**允许出现在
        //     PrefsStore.kt 的平台层实现里（MainActivity 不再是落点，也不许再长出第二个）；
        //  ③ `MainActivity` 仍负责构造并注入存储（否则 VM 退回内存实现 → 偏好静默不持久化）。
        val uiCode = uiFiles().joinToString("\n") { stripComments(it.readText()) }
        for (entry in VM_WRITE_ENTRIES) {
            assertTrue(
                "UI 里没有调用 VM 的偏好写入口（应出现形如 `viewModel.setDarkMode(...)` / " +
                    "`viewModel.setAspectMode(...)` 的调用或方法引用）：开关点了不会落盘。" +
                    "写路径必须是 UI → VM 写入口 → PrefsStore → 平台层（review/03-ui.md P2-17 收口）",
                Regex(entry).containsMatchIn(uiCode),
            )
        }
        // 画面模式有两个消费页（我的页 + 播放页）：只保留一处时另一页的胶囊会静默失效。
        val aspectSites = Regex(VM_WRITE_ENTRY_ASPECT).findAll(uiCode).count()
        assertTrue(
            "画面模式的写入口至少要有两个消费点（我的页 + 播放页），实际 $aspectSites 处",
            aspectSites >= 2,
        )

        val storeFile = source(STORE_IMPL_FILE)
        val landing = productionFiles()
            .filter { it.name != storeFile.name }
            .flatMap { storeBackendWriteViolations(it.name, it.readText()) }
        assertTrue(
            "偏好落盘的实现只允许在 ${storeFile.name}（平台层存储实现），这些地方也出现了：\n" +
                landing.joinToString("\n").prependIndent("  ") +
                "\nMainActivity 只该构造/注入存储，不该再直接写偏好（03-P2-17 写路径收口）。",
            landing.isEmpty(),
        )
        assertEquals(
            "${storeFile.name} 必须保留两处落盘实现（AppPrefs / PlayerPrefs 的委托），" +
                "否则新链路断在最后一环",
            2,
            STORE_BACKEND_WRITE.findAll(stripComments(storeFile.readText())).count(),
        )

        val activity = stripComments(mainActivity().readText())
        assertTrue(
            "MainActivity 必须构造并注入 AndroidPrefsStore（否则 VM 会退回 InMemoryPrefsStore，" +
                "偏好静默不持久化）：期望同时出现 `AndroidPrefsStore(` 与 `prefs = prefs`",
            activity.contains("AndroidPrefsStore(") && activity.contains("prefs = prefs"),
        )

        // 判据自检：违规样本必须命中、注释/定义不命中（与生产判定走**同一个**函数）
        assertTrue(
            "「UI 没调 VM 写入口」的样本被误判成合规（守卫会退化成摆设）",
            vmWriteEntryViolations("X.kt", "onDarkModeChange = { dark -> println(dark) }").isEmpty(),
        )
        assertTrue(
            "真的调了 VM 写入口的样本必须命中",
            vmWriteEntryViolations("X.kt", "onDarkModeChange = { dark -> viewModel.setDarkMode(dark) }").isNotEmpty(),
        )
        assertTrue(
            "注释里的 VM 写入口不算消费点",
            vmWriteEntryViolations("X.kt", "// viewModel.setDarkMode(true)").isEmpty(),
        )
        assertTrue(
            "平台层之外的落盘实现必须被抓到",
            storeBackendWriteViolations("MainActivity.kt", "AppPrefs.setDarkMode(it)").isNotEmpty(),
        )
        assertTrue(
            "存储实现文件自身不算「平台层之外的落点」",
            storeBackendWriteViolations(
                STORE_IMPL_FILE,
                "override fun setDarkMode(dark: Boolean) = AppPrefs.setDarkMode(dark)",
            ).isEmpty(),
        )
    }

    // ---------------- P2-31：AppPrefs 的 loaded 短路与 PlayerPrefs 约定相反 ----------------

    @Test
    fun `AppPrefs 不得再用 loaded 短路 init`() {
        val code = stripComments(appPrefs().readText())
        assertFalse(
            "AppPrefs.init 又用 `loaded` 短路了（review/03-ui.md P2-31）：与 PlayerPrefs 的" +
                "明确约定相反 —— object 单例在任何早于 MainActivity.onCreate 的读取里会拿到" +
                "初值 null（消费方解释成深色），与用户设置不符。" +
                "SharedPreferences 本身已在内存里，每次读开销可忽略。",
            Regex("""\bloaded\b""").containsMatchIn(code),
        )
        assertTrue(
            "init 必须真的从 SharedPreferences 还原偏好（走 darkModeFromStorage）",
            code.contains("darkModeFromStorage("),
        )
    }

    // ---------------- P2-35：无「更多」时的占位与左侧不等宽 ----------------

    @Test
    fun `无更多时的占位必须用 SubPageBarSideSlot`() {
        // ⚠️ 跨文件定位：二级页顶栏随 03-P2-18 拆分搬到了 ui/AppChrome.kt。
        val code = stripComments(sourceContaining("fun SubPageTopBar(").readText())
        val body = functionBody(code, "fun SubPageTopBar(")

        assertTrue(
            "无「更多」时的占位必须用 SubPageBarSideSlot，不能写裸 dp：" +
                "左侧返回键的**布局**尺寸被 minimumInteractiveComponentSize() 撑到 48dp，" +
                "右侧占位只有 40dp 时，两侧 weight 分到不等宽的剩余空间，" +
                "标题中心相对屏幕中心左偏 4dp（review/03-ui.md P2-35）",
            Regex("""Spacer\(\s*Modifier\.size\(\s*SubPageBarSideSlot\s*\)\s*\)""").containsMatchIn(body),
        )
        assertFalse(
            "SubPageTopBar 里又出现了裸 dp 的 Spacer 占位：与左侧 48dp 不等宽，标题会左偏（P2-35）",
            Regex("""Spacer\(\s*Modifier\.size\(\s*\d+(\.\d+)?\.dp\s*\)\s*\)""").containsMatchIn(body),
        )
    }

    @Test
    fun `顶栏侧位常量必须是 48dp`() {
        // ⚠️ 跨文件定位：常量随顶栏一起搬到 ui/AppChrome.kt。
        val code = stripComments(sourceContaining("val SubPageBarSideSlot").readText())
        val slot = Regex("""SubPageBarSideSlot\s*=\s*(\d+)\.dp""").find(code)
            ?: error("找不到 SubPageBarSideSlot 常量定义 —— 守卫会静默全绿（P2-35）")
        assertEquals(
            "顶栏侧位必须等于 minimumInteractiveComponentSize 的最小触摸目标 48dp：" +
                "左侧按钮的布局尺寸就是被它撑到 48dp 的，占位写 40dp 标题就会左偏 4dp（P2-35）",
            48,
            slot.groupValues[1].toInt(),
        )
    }

    // ---------------- 扫描自检 ----------------

    @Test
    fun `扫描必须真的扫到目标源码`() {
        val root = productionRoot()
        assertTrue("扫描不到生产源码（cwd=${System.getProperty("user.dir")}），守卫会静默全绿", root.walkTopDown().count { it.isFile && it.extension == "kt" } >= 10)
        assertTrue("扫描不到 MainActivity.kt", source("MainActivity.kt").isFile)
        assertTrue("扫描不到 NbaLiveApp.kt", source("NbaLiveApp.kt").isFile)
        assertTrue("扫描不到 AppPrefs.kt", source("AppPrefs.kt").isFile)
    }

    // ---------------- 工具 ----------------

    private fun productionRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd，候选=${candidates.map { it.path }}）—— 守卫会静默全绿")
    }

    private fun source(fileName: String): File = GuardSourceScan.uniqueFile(
        productionRoot().walkTopDown().filter { it.isFile }.toList(),
        fileName,
    )

    /**
     * 按**代码内容**唯一定位生产文件（跨文件判据用）。
     *
     * 03-P2-18 把外壳/副作用从 `NbaLiveApp.kt` 拆到 `AppChrome.kt` / `AppEffects.kt` 等新文件后，
     * 写死文件名的锚点会失效；按内容定位跟着代码走，抗重构。
     * 0 个或多个命中都 `error()`（[GuardSourceScan.uniqueFileContaining]），绝不静默。
     */
    private fun sourceContaining(marker: String): File =
        GuardSourceScan.uniqueFileContaining(
            productionRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList(),
            marker,
        )

    /** `ui/` 目录下全部 kt：用于"必须扫全目录"的负向判据。 */
    private fun uiFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app/ui"),
            File(cwd, "app/src/main/java/com/nbalive/app/ui"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 ui 源码目录（cwd=$cwd）—— 跨文件判据会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** `com/nbalive/app` 下全部生产 kt（含 `MainActivity.kt` 这类平台层文件）。 */
    private fun productionFiles(): List<File> =
        productionRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** UI 里 VM 写入口的消费点（方法调用或方法引用都算；空 = 没人消费）。 */
    private fun vmWriteEntryViolations(fileName: String, rawText: String): List<String> =
        VM_WRITE_ENTRY.findAll(stripComments(rawText)).map { "$fileName: ${it.value.trim()}" }.toList()

    /**
     * 偏好落盘的**实现**调用（`AppPrefs.setDarkMode(` / `PlayerPrefs.setAspect(`）。
     *
     * 允许的只有平台层存储实现（[STORE_IMPL_FILE]）—— 别处出现就是在 MainActivity
     * 或 UI 层又长出一个落盘点（空 = 达标）。
     */
    private fun storeBackendWriteViolations(fileName: String, rawText: String): List<String> {
        if (fileName == STORE_IMPL_FILE) return emptyList()
        return STORE_BACKEND_WRITE.findAll(stripComments(rawText)).map { "$fileName: ${it.value.trim()}" }.toList()
    }

    /**
     * 偏好 setter 的签名/调用里出现 `Context` 的违规说明（空 = 达标）。
     *
     * ⚠️ **内部自己剔非代码**：生产路径与自检路径必须走同一个函数，
     * 否则"注释里的 Context 不算命中"这条自检会变成"测试自己先剔了、判据其实没剔"
     * （本仓库踩过：判据在测试里被复述而不是被验证）。
     * 与 `PrefsBoundaryGuardTest` 同向：那里扫的是"UI 文件直接读偏好单例"，
     * 这里锚在"任何生产调用点都不许把 Context 传进偏好写入"。
     */
    private fun setterContextViolations(fileName: String, rawText: String): List<String> =
        SETTER_WITH_CONTEXT.findAll(stripComments(rawText)).map { "$fileName: ${it.value.trim()}" }.toList()

    private fun mainActivity() = source("MainActivity.kt")
    private fun nbaLiveApp() = source("NbaLiveApp.kt")
    private fun appPrefs() = source("AppPrefs.kt")
    private fun mainViewModel() = source("MainViewModel.kt")

    /**
     * `onNewIntent` 死代码判据：返回违规说明；`null` = 达标（方法不存在，或存在且真的读了 intent）。
     *
     * ⚠️ 审计 A14：旧实现是 `if (!code.contains("override fun onNewIntent")) return` ——
     *  a) `override` 与 `fun` 之间换行（Kotlin 合法写法）会**漏判**；
     *  b) 方法不存在时整条测试直接 return（当前是"永远绿"的）。
     * 这里用允许换行的正则识别**声明**，再对**函数体**做结构判断。
     */
    private fun onNewIntentViolation(rawCode: String): String? {
        val code = stripComments(rawCode)
        val declaration = Regex("""\boverride\s+fun\s+onNewIntent\s*\(""").find(code)
            ?: return null // 已删除 = 达标
        val open = code.indexOf('{', declaration.range.last)
        if (open < 0) {
            return "onNewIntent 声明之后找不到函数体 —— 判据没法确认它是否读了 intent"
        }
        val body = balanced(code, open)
        if (INTENT_READ.containsMatchIn(body)) return null
        return "函数体只做了：${body.trim().replace(Regex("""\s+"""), " ")}"
    }

    /**
     * 提取以 [marker] 起始的函数花括号体。
     *
     * ⚠️ marker 必须带左括号（`fun SubPageTopBar(`），否则会命中同前缀函数
     * （这个仓库踩过：`fun LiveDot` 命中了 `fun LiveDotPhase(`）。
     */
    private fun functionBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        require(start >= 0) { "源码里找不到 $marker —— 守卫会静默全绿" }
        var i = start + marker.length - 1
        require(i < code.length && code[i] == '(') { "marker 必须以左括号结尾：$marker" }
        var paren = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                // 参数表之外出现 `=` 说明是表达式体函数，没有花括号体
                '=' -> if (paren == 0) return ""
                '{' -> if (paren == 0) return balanced(code, i)
            }
            i++
        }
        error("$marker 找不到函数体 —— 守卫会静默全绿")
    }

    private fun balanced(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        error("花括号不配对 —— 守卫会静默全绿")
    }

    /**
     * 去掉行注释与块注释**以及字符串字面量**（注释里写同样的字面量会让守卫永远全绿）。
     *
     * 走共享的词法实现 [GuardSourceScan.stripNonCode]：早期的"整行注释才置空"版本
     * 会漏掉行尾 `// …`，于是"删掉调用、在同一行留一句提到它的注释"照样绿。
     */
    private fun stripComments(text: String): String = GuardSourceScan.stripNonCode(text)

    private companion object {
        /** 真的读了 activity 的 intent（不是只 setIntent）。 */
        val INTENT_READ = Regex("""getIntent\(\)|intent\.[A-Za-z]|intent\?\.\w""")

        /** 「把派生值重新包一层 remember」。 */
        val REMEMBERED_DERIVED =
            Regex("""remember\(\s*listState\.(matches|selectedLeague)[^)]*\)\s*\{\s*listState\.(visibleMatches|counts)\s*\}""")

        /** 两处各写一遍的 null 兜底。 */
        val DARK_PREF_FALLBACK = Regex("""darkPref\s*\?:\s*(true|false)""")

        /** AppPrefs 注释里那句与实现相反的谎话。 */
        val LYING_COMMENT = Regex("""null\s*=\s*跟随系统""")

        /**
         * 偏好 setter 的签名或调用里出现 `Context`。
         *
         * ⚠️ 用子串 `[Cc]ontext` 而不是 `\bContext\b`：`applicationContext` 里
         * "Context" 前面是字母 `n`，词边界不成立 —— 带词边界的写法会漏掉
         * 「调用点传 applicationContext」这个最常见形态（同款判据在
         * PrefsBoundaryGuardTest 的自检里已经抓到过这个漏判）。
         */
        val SETTER_WITH_CONTEXT =
            Regex("""\b(?:setDarkMode|setAspect)\s*\([^)]*[Cc]ontext[^)]*\)""")

        /** 直接引用偏好单例（`AppPrefs.darkMode` / `PlayerPrefs.aspect`…）。 */
        val PREF_SINGLETON_REF = Regex("""\b(?:AppPrefs|PlayerPrefs)\s*\.\s*\w""")

        /**
         * 组合 UI 的文件：不得直接引用偏好单例。
         *
         * `AppPrefs.kt` / `PlayerPrefs.kt` / `PrefsStore.kt` 是**偏好存储自己**，
         * 不在其列（它们就是"偏好住的地方"）；`MainActivity.kt` 是平台层（也不在 ui/ 下）。
         */
        val COMPOSABLE_FILES = setOf(
            "NbaLiveApp.kt",
            "AppChrome.kt",
            "AppUiState.kt",
            "SearchOverlay.kt",
            "AppEffects.kt",
            "AppBackHandling.kt",
        )

        /**
         * UI 里 VM 的偏好写入口（调用与方法引用都算）。
         *
         * 03-P2-17 写路径收口后，UI 只能通过它写偏好 ——
         * 点开关却没人调这个入口，就等于"开关点了不落盘"且没有编译期信号。
         */
        val VM_WRITE_ENTRY = Regex(
            """\bviewModel\s*(?:\.\s*(?:setDarkMode|setAspectMode)\s*\(|::\s*(?:setDarkMode|setAspectMode)\b)""",
        )

        /** 逐条断言的写入口（失败时能指出缺哪个） */
        val VM_WRITE_ENTRIES = listOf(
            """\bviewModel\s*(?:\.\s*setDarkMode\s*\(|::\s*setDarkMode\b)""",
            """\bviewModel\s*(?:\.\s*setAspectMode\s*\(|::\s*setAspectMode\b)""",
        )

        /** 画面模式的 VM 写入口（用于"两个消费页"计数） */
        const val VM_WRITE_ENTRY_ASPECT =
            """\bviewModel\s*(?:\.\s*setAspectMode\s*\(|::\s*setAspectMode\b)"""

        /** 平台层存储的落盘实现（`AppPrefs.setDarkMode(` / `PlayerPrefs.setAspect(`） */
        val STORE_BACKEND_WRITE =
            Regex("""\b(?:AppPrefs\s*\.\s*setDarkMode|PlayerPrefs\s*\.\s*setAspect)\s*\(""")

        /** 唯一允许出现落盘实现的文件（平台层存储实现） */
        const val STORE_IMPL_FILE = "PrefsStore.kt"
    }
}