package com.nbalive.app.ui

import android.content.Context
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

    /** 写入深色模式。⚠️ **不带 `Context`** —— Context 只活在平台层实现里 */
    fun setDarkMode(dark: Boolean)

    /** 写入画面模式。⚠️ 同上，不带 `Context` */
    fun setAspect(mode: AspectMode)

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
) : PrefsStore {
    private val _darkMode = MutableStateFlow(initialDarkMode)
    override val darkMode: StateFlow<Boolean?> = _darkMode.asStateFlow()

    private val _aspect = MutableStateFlow(initialAspect)
    override val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    override fun setDarkMode(dark: Boolean) {
        _darkMode.value = dark
    }

    override fun setAspect(mode: AspectMode) {
        _aspect.value = mode
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

    override fun setDarkMode(dark: Boolean) = AppPrefs.setDarkMode(dark)

    override fun setAspect(mode: AspectMode) = PlayerPrefs.setAspect(mode)

    override fun init() {
        AppPrefs.attach(appContext)
        PlayerPrefs.attach(appContext)
    }
}