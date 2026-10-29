package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏好边界守卫（review/03-ui.md P2-17 的剩余部分）。
 *
 * ## 守的是什么
 *
 * 「UI 层碰 Context / 直接读偏好单例」不会产生任何编译期或运行期信号：
 * 代码照样能跑、界面照样能显示，只是 Android 依赖又横穿了一层，
 * 纯 Compose 预览与 JVM 单测再也碰不到这条路径。所以判据落在**源码结构**上：
 *
 *  1. UI 目录里不许再直接读 `AppPrefs.` / `PlayerPrefs.` 的状态
 *     （允许的只有定义处与平台层存储实现 `PrefsStore.kt`）；
 *  2. 偏好 setter 不许再收 `Context` 形参，生产落盘点传的必须是普通值；
 *  3. `null` 的解释只许留在 `resolveDarkMode`：UI 里不许再出现
 *     `darkPref ?: …` 这种就地兜底（P2-16 的成果）。
 *
 * ## 铁律（与全仓守卫一致）
 *
 * 断言前先 [GuardSourceScan.stripNonCode]（连字符串字面量一起剔，行号不变）；
 * 判据匹配**调用**而不是字符串出现；文件找不到直接 `error()`，
 * 绝不用 `assume` 家族静默跳过。每条判据都有内存样本自检（违规必须命中、
 * 注释/字符串必须不命中），避免本仓库反复出现的「守卫自己永远不会红」。
 */
class PrefsBoundaryGuardTest {

    @Test
    fun `UI 里不得直接读偏好单例`() {
        val offenders = uiFiles().flatMap { prefsReadViolations(it.name, it.readText()) }
        assertTrue(
            "以下 UI 文件直接读了 `AppPrefs.` / `PlayerPrefs.` 的状态：\n" +
                offenders.joinToString("\n").prependIndent("  ") +
                "\n偏好应该经 [PrefsStore] 注入 MainViewModel，UI 只读 VM 暴露的状态" +
                "（review/03-ui.md P2-17 的剩余部分）。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `偏好读取判据自检 调用命中而 import 与注释不命中`() {
        assertTrue(
            "真的读了单例状态时必须命中",
            prefsReadViolations("AppUiState.kt", "val d by AppPrefs.darkMode.collectAsStateWithLifecycle()")
                .isNotEmpty(),
        )
        assertTrue(
            "注释与字符串字面量里的名字不算命中（否则删掉读取、留一句说明就照样绿）",
            prefsReadViolations(
                "AppUiState.kt",
                "/** AppPrefs.darkMode 的说明 */\n// val d by AppPrefs.darkMode\nval s = \"PlayerPrefs.aspect\"",
            ).isEmpty(),
        )
        assertTrue(
            "import 行不是状态读（它只是把名字引进来）",
            prefsReadViolations("AppUiState.kt", "import com.nbalive.app.ui.AppPrefs").isEmpty(),
        )
        // 反向锚点：定义处与平台层存储实现允许出现（否则判据会逼着把存储藏起来）
        assertTrue(
            "AppPrefs.kt（定义处）不该被自己的判据判违规",
            prefsReadViolations("AppPrefs.kt", "val d by AppPrefs.darkMode.collectAsStateWithLifecycle()").isEmpty(),
        )
        assertTrue(
            "PrefsStore.kt（平台层存储实现）不该被自己的判据判违规",
            prefsReadViolations("PrefsStore.kt", "override val darkMode get() = AppPrefs.darkMode").isEmpty(),
        )
    }

    @Test
    fun `偏好 setter 不得再收 Context 形参`() {
        val offenders = productionFiles().flatMap { contextSetterViolations(it.name, it.readText()) }
        assertTrue(
            "以下 setter 还在收 / 传 `Context`：\n" +
                offenders.joinToString("\n").prependIndent("  ") +
                "\nContext 只该由平台层 attach 一次（见 AppPrefs/PlayerPrefs 的 attach），" +
                "之后读写都是普通值（review/03-ui.md P2-17）。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `setter 判据自检 签名与调用都要能抓到`() {
        assertTrue(
            "带 Context 形参的定义必须命中",
            contextSetterViolations(
                "AppPrefs.kt",
                "fun setDarkMode(context: Context, dark: Boolean) {}",
            ).isNotEmpty(),
        )
        assertTrue(
            "调用点传 Context 也必须命中（否则「签名改了、调用还传」会漏）",
            contextSetterViolations(
                "MainActivity.kt",
                "onDarkModeChange = { AppPrefs.setDarkMode(applicationContext, it) }",
            ).isNotEmpty(),
        )
        assertTrue(
            "无 Context 的普通值调用不算违规",
            contextSetterViolations(
                "MainActivity.kt",
                "onDarkModeChange = { AppPrefs.setDarkMode(it) }",
            ).isEmpty(),
        )
        assertTrue(
            "注释里提到 Context 不算违规",
            contextSetterViolations(
                "AppPrefs.kt",
                "// 早先这里是 setDarkMode(context: Context, dark: Boolean)\nfun setDarkMode(dark: Boolean) {}",
            ).isEmpty(),
        )
    }

    @Test
    fun `写链路必须有真实消费点`() {
        // 03-P2-17 **写路径收口**：这一轮之前 `PrefsStore.setDarkMode/setAspect` **只有实现、
        // 没有任何生产调用点**（审计口径的「假安全感」）—— 开关能存下来靠的是
        // MainActivity 直接传的回调。新链路：UI → viewModel 写入口 → PrefsStore
        // → AndroidPrefsStore → AppPrefs/PlayerPrefs。下面两条就是这条链的两个接点。
        val vmEntries = uiFiles().flatMap { vmWriteEntryViolations(it.name, it.readText()) }
        assertTrue(
            "UI 里没有消费 VM 的偏好写入口（`viewModel.setDarkMode(...)` / `viewModel.setAspectMode(...)`）：\n" +
                "这条链断了就退回到「接口方法只有实现、没人调」—— 开关点了不落盘，" +
                "而纯判据测试仍然全绿（03-P2-17 写路径收口）。",
            vmEntries.isNotEmpty(),
        )

        val storeConsumption = productionFiles().flatMap { prefsStoreWriteViolations(it.name, it.readText()) }
        assertTrue(
            "`PrefsStore.setDarkMode/setAspect` 在 src/main 里没有**实现之外**的调用点：\n" +
                "接口声明了却没人消费 = 本仓库最忌的「假安全感」。" +
                "VM 的写方法必须真的调注入的 `prefs.setDarkMode(...)` / `prefs.setAspect(...)`。",
            storeConsumption.isNotEmpty(),
        )

        // 判据自检（与生产判定走**同一个**函数）
        assertTrue(
            "真的消费 VM 写入口的样本必须命中",
            vmWriteEntryViolations("X.kt", "onDarkModeChange = { dark -> viewModel.setDarkMode(dark) }").isNotEmpty(),
        )
        assertTrue(
            "没消费 VM 写入口的样本必须不命中",
            vmWriteEntryViolations("X.kt", "onDarkModeChange = { dark -> println(dark) }").isEmpty(),
        )
        assertTrue(
            "注释里的 VM 写入口不算消费点",
            vmWriteEntryViolations("X.kt", "// viewModel.setDarkMode(true)").isEmpty(),
        )
        assertTrue(
            "`prefs.setDarkMode(...)` 样本必须命中",
            prefsStoreWriteViolations("MainViewModel.kt", "fun setDarkMode(dark: Boolean) = prefs.setDarkMode(dark)")
                .isNotEmpty(),
        )
        assertTrue(
            "接口声明（没有 prefs. 接收者）不算消费点",
            prefsStoreWriteViolations("Other.kt", "fun setDarkMode(dark: Boolean) {}").isEmpty(),
        )
    }

    @Test
    fun `生产落盘点必须传普通值`() {
        val calls = productionFiles().flatMap { file ->
            plainSetterCalls(file.name, file.readText())
        }
        assertTrue(
            "生产里找不到 `AppPrefs.setDarkMode(<普通值>)` / `PlayerPrefs.setAspect(<普通值>)`：" +
                "偏好落盘必须有真实调用点，否则开关点了不会持久化（P2-17）",
            calls.isNotEmpty(),
        )
    }

    @Test
    fun `null 的解释只能留在 resolveDarkMode`() {
        val offenders = uiFiles()
            .filter { DARK_PREF_NULL_FALLBACK.containsMatchIn(GuardSourceScan.stripNonCode(it.readText())) }
            .map { it.name }
        assertTrue(
            "以下 UI 文件又就地写了 `darkPref ?: …` 兜底：$offenders\n" +
                "「null 怎么解释」只允许有一处实现（AppPrefs.kt 的 resolveDarkMode，" +
                "review/03-ui.md P2-16）：两份实现时改一处漏一处，就会出现" +
                "「主题是浅色、状态栏图标按深色画」的错配。",
            offenders.isEmpty(),
        )
        assertTrue(
            "就地兜底样本必须命中",
            DARK_PREF_NULL_FALLBACK.containsMatchIn(GuardSourceScan.stripNonCode("val d = darkPref ?: true")),
        )
        assertTrue(
            "resolveDarkMode 自己的 `pref ?: DARK_FIRST_WHEN_UNSET` 不该被误判",
            !DARK_PREF_NULL_FALLBACK.containsMatchIn(
                GuardSourceScan.stripNonCode("internal fun resolveDarkMode(pref: Boolean?) = pref ?: DARK_FIRST_WHEN_UNSET"),
            ),
        )
    }

    @Test
    fun `内存实现能承接读与写`() {
        // 接口的写方法是给注入用的（生产实现 AndroidPrefsStore 自己持有 Context，
        // 测试/预览实现 InMemoryPrefsStore 完全不碰 Android）——这里钉住它的语义。
        val store = InMemoryPrefsStore(initialDarkMode = null, initialAspect = AspectMode.ZOOM)
        assertNull("内存实现的初值必须如实反映传入值", store.darkMode.value)
        assertEquals(AspectMode.ZOOM, store.aspect.value)
        store.setDarkMode(true)
        store.setAspect(AspectMode.FILL)
        assertEquals(true, store.darkMode.value)
        assertEquals(AspectMode.FILL, store.aspect.value)
        store.init()
        assertEquals("init 必须幂等（不把用户设置冲回初值）", true, store.darkMode.value)
        assertEquals(AspectMode.FILL, store.aspect.value)
    }

    @Test
    fun `扫描必须真的扫到 ui 源码`() {
        val files = uiFiles()
        assertTrue(
            "只扫到 ${files.size} 个 ui 源文件（应 ≥8，cwd=${System.getProperty("user.dir")}）—— " +
                "扫描范围走偏时上面几条会静默全绿",
            files.size >= 8,
        )
        assertTrue("没扫到 AppUiState.kt（锚点文件）", files.any { it.name == "AppUiState.kt" })
        assertTrue("没扫到 PrefsStore.kt（平台层实现）", files.any { it.name == "PrefsStore.kt" })
    }

    // ---------------------------------------------------------------
    // 判据（内存样本与生产源码走**同一个**函数）
    // ---------------------------------------------------------------

    /** UI 文件里直接读偏好单例的违规说明（空 = 达标）。 */
    private fun prefsReadViolations(fileName: String, rawText: String): List<String> {
        // 定义处与平台层存储实现：允许出现（它们就是"偏好"住的地方）
        if (fileName in STORE_FILES) return emptyList()
        val code = GuardSourceScan.stripNonCode(rawText)
        return PREF_SINGLETON_READ.findAll(code)
            .map { "$fileName: ${it.value.trim()}" }
            .toList()
    }

    /** setter 签名或调用里出现 `Context` 的违规说明（空 = 达标）。 */
    private fun contextSetterViolations(fileName: String, rawText: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawText)
        return SETTER_WITH_CONTEXT.findAll(code)
            .map { "$fileName: ${it.value.trim()}" }
            .toList()
    }

    /** 生产里"传普通值"的 setter 调用（空 = 没有落盘点）。 */
    private fun plainSetterCalls(fileName: String, rawText: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawText)
        return PLAIN_SETTER_CALL.findAll(code)
            .map { "$fileName: ${it.value.trim()}" }
            .toList()
    }

    /** UI 里 VM 写入口的消费点（空 = 没人消费）。 */
    private fun vmWriteEntryViolations(fileName: String, rawText: String): List<String> =
        VM_WRITE_ENTRY.findAll(GuardSourceScan.stripNonCode(rawText))
            .map { "$fileName: ${it.value.trim()}" }
            .toList()

    /** 注入的 `PrefsStore` 写方法的消费点（空 = 接口方法没人调）。 */
    private fun prefsStoreWriteViolations(fileName: String, rawText: String): List<String> {
        // 接口与实现所在地：定义不算消费
        if (fileName == STORE_IMPL_FILE) return emptyList()
        return PREFS_STORE_WRITE_CALL.findAll(GuardSourceScan.stripNonCode(rawText))
            .map { "$fileName: ${it.value.trim()}" }
            .toList()
    }

    // ---------------------------------------------------------------
    // 文件定位
    // ---------------------------------------------------------------

    private fun uiFiles(): List<File> {
        val root = File(productionRoot(), "ui")
        if (!root.isDirectory) {
            error("找不到 ui 源码目录（${root.path}）—— 本文件所有守卫会静默全绿")
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    private fun productionFiles(): List<File> =
        productionRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun productionRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录（cwd=$cwd，候选=${candidates.map { it.path }}）—— 守卫会静默全绿")
    }

    private companion object {
        /** 偏好"住"的文件：定义处 + 平台层存储实现。 */
        val STORE_FILES = setOf("AppPrefs.kt", "PlayerPrefs.kt", "PrefsStore.kt")

        /** 直接读偏好单例（`AppPrefs.darkMode` 这种，import 行不匹配）。 */
        val PREF_SINGLETON_READ = Regex("""\b(?:AppPrefs|PlayerPrefs)\s*\.\s*\w""")

        /**
         * setter 的签名或调用里出现 Context。
         *
         * ⚠️ 用子串 `[Cc]ontext` 而**不是** `\bContext\b`：`applicationContext` 里
         * "Context" 前面是字母 `n`，词边界断言不成立 —— 带词边界的写法会漏掉
         * 「调用点传 applicationContext」这种最常见的形态（自检里喂过这个样本，
         * 它当时没命中，正是本仓库最忌讳的"守卫抓不到真违规"）。
         */
        val SETTER_WITH_CONTEXT =
            Regex("""\b(?:setDarkMode|setAspect)\s*\([^)]*[Cc]ontext[^)]*\)""")

        /** setter 调用且实参是"普通值"（不含 Context）。 */
        val PLAIN_SETTER_CALL =
            Regex("""\b(?:AppPrefs|PlayerPrefs)\s*\.\s*(?:setDarkMode|setAspect)\s*\((?![^)]*Context)[^)]*\)""")

        /** UI 里就地决定 null 该怎么解释（P2-16 只允许一处）。 */
        val DARK_PREF_NULL_FALLBACK = Regex("""\bdarkPref\s*\?:""")

        /** UI 里 VM 的偏好写入口（调用与方法引用都算）。 */
        val VM_WRITE_ENTRY = Regex(
            """\bviewModel\s*(?:\.\s*(?:setDarkMode|setAspectMode)\s*\(|::\s*(?:setDarkMode|setAspectMode)\b)""",
        )

        /**
         * 注入的 `PrefsStore` 的写方法消费点。
         *
         * 接收者限定 `prefs` / `store`（`MainViewModel` 的构造形参就叫 `prefs`）——
         * 刻意**不**写成"任意 `.setDarkMode(`"：那样会把 MainActivity 里
         * `AppPrefs.setDarkMode(` 也算成"接口被消费"，恰好掩盖了这轮要消灭的零消费点。
         */
        val PREFS_STORE_WRITE_CALL = Regex("""\b(?:prefs|store)\s*\.\s*(?:setDarkMode|setAspect)\s*\(""")

        /** 平台层存储实现所在文件（接口/实现定义不算消费点）。 */
        const val STORE_IMPL_FILE = "PrefsStore.kt"
    }
}