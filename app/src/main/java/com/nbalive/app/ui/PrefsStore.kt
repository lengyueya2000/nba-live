package com.nbalive.app.ui

import android.content.Context
import com.nbalive.app.data.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 偏好读写的**唯一**入口（review/03-ui.md P2-17 的剩余部分）。
 *
 * ## 为什么要这层抽象
 *
 * 早先界面直接读 `AppPrefs.darkMode` / `PlayerPrefs.aspect` 这两个单例 `StateFlow`，
 * 写又走**带 `Context` 形参**的 setter —— 于是「Android Context」这层依赖横穿 UI，
 * 纯 Compose 预览与 JVM 单测都碰不了。现在：
 *  - 界面只从 `MainViewModel` 暴露的状态读（见 [AppUiState]）；
 *  - 写入是无 `Context` 形参的方法，Context 只活在**平台层的实现**里；
 *  - `MainViewModel` 依赖这个接口，所以它的单测可以注入 [InMemoryPrefsStore]，
 *    一行 Android 代码都不用碰。
 *
 * ## ⚠️ 为什么**不用** `AndroidViewModel`
 *
 * `AndroidViewModel` 要求构造时拿到 `Application` 实例。本工程的单测是**纯 JVM 单测**
 * （没有 Robolectric）：在 JVM 上构造 `Application` 会命中 Android 桩并抛 `Stub!`，
 * `MainViewModelTest` / `NavRestoreTest` 等整批 VM 用例会当场挂掉 —— 那正是本仓库
 * 反复出现的「为了一个抽象把整批测试弄挂」。而且「把 Application 塞进 VM」本身就是
 * 把 Context 依赖又请回 UI 层，与 P2-17 要修的正好相反。
 * 接口注入没有这两个问题：生产注入 [AndroidPrefsStore]（自己持有 `applicationContext`），
 * 测试注入 [InMemoryPrefsStore]。
 */
interface PrefsStore {
    /** 深色模式偏好。`null` = 用户从未手动设置过（解释成什么由 [resolveDarkMode] 决定） */
    val darkMode: StateFlow<Boolean?>

    /** 画面填充模式偏好 */
    val aspect: StateFlow<AspectMode>

    /**
     * 数据源开关：现在参与解析的源（2026-10-31 用户需求）。
     *
     * 默认全开（[SourceTogglePolicy.default]）；"至少留一个"的判据见 [SourceTogglePolicy]。
     */
    val enabledProviders: StateFlow<Set<Provider>>

    /** 写入深色模式。⚠️ **不带 `Context`** —— Context 只活在平台层实现里 */
    fun setDarkMode(dark: Boolean)

    /** 写入画面模式。⚠️ 同上，不带 `Context` */
    fun setAspect(mode: AspectMode)

    /** 写入数据源开关。⚠️ 同上，不带 `Context` */
    fun setProviderEnabled(enabled: Set<Provider>)

    /**
     * 从持久化里读一次。
     *
     * ⚠️ 幂等，且**不用 `loaded` 短路**（与 `AppPrefs` / `PlayerPrefs` 的既有约定一致）：
     * 任何早于 `MainActivity.onCreate` 的读取都该拿到用户真实设置，而不是初值。
     */
    fun init()
}

/**
 * 内存实现：JVM 单测与 Compose 预览用，完全不碰 Android。
 *
 * ⚠️ 它不是"测试专用摆设"：`MainViewModel` 的默认参数就是它，所以没有注入的
 * 既有测试（以及预览）走的就是这条路径，而不是某个会抛 `Stub!` 的 Android 桩。
 */
class InMemoryPrefsStore(
    initialDarkMode: Boolean? = null,
    initialAspect: AspectMode = AspectMode.FIT,
    initialProviders: Set<Provider> = SourceTogglePolicy.ALL,
) : PrefsStore {
    private val _darkMode = MutableStateFlow(initialDarkMode)
    override val darkMode: StateFlow<Boolean?> = _darkMode.asStateFlow()

    private val _aspect = MutableStateFlow(initialAspect)
    override val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    private val _providers = MutableStateFlow(initialProviders.ifEmpty { SourceTogglePolicy.ALL })
    override val enabledProviders: StateFlow<Set<Provider>> = _providers.asStateFlow()

    override fun setDarkMode(dark: Boolean) {
        _darkMode.value = dark
    }

    override fun setAspect(mode: AspectMode) {
        _aspect.value = mode
    }

    override fun setProviderEnabled(enabled: Set<Provider>) {
        // 与生产实现同一份兜底：空集按全开处理（见 SourceTogglePolicy.fromStorage 的说明）
        _providers.value = enabled.ifEmpty { SourceTogglePolicy.ALL }
    }

    /** 内存实现没有持久化，init 是空操作（保留幂等语义） */
    override fun init() = Unit
}

/**
 * Android 实现（**平台层**）：由 `MainActivity` 构造并 [init]，
 * 把 `applicationContext` 交给 `AppPrefs` / `PlayerPrefs` 的存储；
 * 对外只暴露无 `Context` 的读/写 API。
 *
 * ⚠️ 它是"同一个状态源"的门面：`MainActivity` 的落盘回调与这里的 [setDarkMode]
 * 都写进同一个 `SharedPreferences` / 同一个 `StateFlow`，不存在第二份真相。
 */
internal class AndroidPrefsStore(context: Context) : PrefsStore {
    private val appContext = context.applicationContext

    override val darkMode: StateFlow<Boolean?> get() = AppPrefs.darkMode

    override val aspect: StateFlow<AspectMode> get() = PlayerPrefs.aspect

    override val enabledProviders: StateFlow<Set<Provider>> get() = SourcePrefs.enabled

    override fun setDarkMode(dark: Boolean) = AppPrefs.setDarkMode(dark)

    override fun setAspect(mode: AspectMode) = PlayerPrefs.setAspect(mode)

    override fun setProviderEnabled(enabled: Set<Provider>) = SourcePrefs.setEnabled(enabled)

    /**
     * 平台层初始化（U8）。
     *
     * ## 为什么要拆成"注入"与"读一次"两步
     *
     * `getSharedPreferences` **不阻塞**：它只登记文件并异步起一个加载线程；
     * 阻塞发生在**第一次数据访问**（`contains`/`getBoolean`/`getStringSet`
     * → `awaitLoadedLocked` 等 XML 解析完）。所以：
     *  - ① 注入 Context 是廉价的，可以留在主线程（顺带保证"未 attach 直接 error()"
     *    这条纪律立刻生效，偏好读写永远不会静默失效）；
     *  - ② 首次读盘交给 [PrefsPreload]：`NbaLiveApplication.onCreate` 已经预热过，
     *    这里通常直接返回 —— 主线程一次磁盘 IO 都不做。
     *
     * ⚠️ 若预热**尚未完成**（罕见：Application 与 Activity 之间只隔几毫秒），
     * 这里退回**同步读**（= 改动前的行为）。这是有意的取舍：宁可偶尔慢一次，
     * 也不能让主题/画面模式先按默认值渲染一帧再跳变（`darkPref = null` 会被
     * [resolveDarkMode] 解释成深色，浅色用户会看到闪一下）。
     */
    override fun init() {
        AppPrefs.attachStore(appContext)
        PlayerPrefs.attachStore(appContext)
        SourcePrefs.attachStore(appContext)
        if (PrefsPreload.isFinished) return
        PrefsPreload.readAll()
    }
}

/**
 * 三份 `SharedPreferences` 首次磁盘读的**预热点**（review/optimization-review.md U8）。
 *
 * ## 它解决什么
 *
 * `MainActivity.onCreate` 里同步跑 `AndroidPrefsStore(...) + init()`，
 * 而 `init()` 的第一次 `contains/getBoolean/getStringSet` 会阻塞到三份 XML
 * 解析完 —— 冷启动在 `setContent` **之前**被主线程磁盘 IO 挡住
 *（StrictMode 记为 `DiskReadViolation`）。
 *
 * 现在 `NbaLiveApplication.onCreate` 调一次 [warmUp]：**注入**留在主线程，
 * **读一次**丢到单线程 executor —— 主线程不再等磁盘。
 *
 * ## 为什么不干脆"未就绪时先用默认值"
 *
 * 那会让主题/画面模式按默认值先渲染再跳变（浅色用户冷启动闪一下深色）。
 * 这里选择"预热 + 未就绪时同步兜底"：性能收益照拿，语义一点都不变。
 *
 * ⚠️ 线程是 daemon：不阻止进程退出；executor 只跑一次 `readAll()`，不排队新任务。
 */
internal object PrefsPreload {

    private const val THREAD_NAME = "prefs-preload"

    private val executor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    /** 三份偏好是否已经读过一次（`@Volatile`：主线程与预热线程跨线程通信）。 */
    @Volatile
    private var finished: Boolean = false

    val isFinished: Boolean get() = finished

    /**
     * 主体：**注入 Context（不读盘）+ 后台读一次**。
     *
     * 调用点：`NbaLiveApplication.onCreate`（越早越好，让异步 XML 加载先跑起来）。
     */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        AppPrefs.attachStore(appContext)
        PlayerPrefs.attachStore(appContext)
        SourcePrefs.attachStore(appContext)
        executor.execute(::readAll)
    }

    /**
     * 同步读一次三份偏好。
     *
     * ⚠️ 允许被调用两次（预热线程 + 主线程兜底）：SharedPreferences 的读取幂等，
     * 且写入走 `apply()` 会**同步**更新内存里的值，所以后读的一次不会把
     * 用户刚写的设置冲回旧值。
     */
    fun readAll() {
        AppPrefs.init()
        PlayerPrefs.init()
        SourcePrefs.init()
        finished = true
    }
}