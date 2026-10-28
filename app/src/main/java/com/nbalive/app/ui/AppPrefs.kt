package com.nbalive.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级偏好（与播放相关的偏好放在 PlayerPrefs）。
 *
 * 目前只有一项：深色模式。
 *
 * ⚠️ `darkMode` 为 null 的语义是**「用户从未手动设置过」**，不是「跟随系统」。
 * 本应用是深色优先设计：null 由 [resolveDarkMode] 解释成**深色**。
 * （早先这里的注释声称 null 会跟随系统，与两个消费方的实现正好相反 ——
 * 注释与行为打架，review/03-ui.md P2-16，已修正。）
 */
object AppPrefs {
    private const val KEY_DARK = "dark_mode"

    private val _darkMode = MutableStateFlow<Boolean?>(null)
    /** null = 用户从未手动设置过（由 [resolveDarkMode] 解释成深色）；true/false = 用户手动指定 */
    val darkMode: StateFlow<Boolean?> = _darkMode.asStateFlow()

    fun init(context: Context) {
        // ⚠️ 不要用 loaded 短路（与 PlayerPrefs 的明确约定一致，review/03-ui.md P2-31）：
        // 本对象是单例，任何早于 MainActivity.onCreate 的读取
        // （将来若加了 Service / AppWidget / Receiver 读深色模式）都会拿到初值 null，
        // 而消费方把 null 解释成深色 —— 与用户真实设置不符。
        // SharedPreferences 本身已在内存里，读一次开销可忽略，所以每次都读。
        val sp = sp(context)
        _darkMode.value = darkModeFromStorage(
            hasValue = sp.contains(KEY_DARK),
            // 这个默认值只在 key 不存在时被读到，而那种情况我们已经还原成 null，
            // 所以它不再冒充"用户的设置"
            stored = sp.getBoolean(KEY_DARK, false),
        )
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        _darkMode.value = dark
        sp(context).edit().putBoolean(KEY_DARK, dark).apply()
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
}

/** 深色优先：用户从未手动设置过（null）时就是深色（产品决定）。 */
internal const val DARK_FIRST_WHEN_UNSET: Boolean = true

/**
 * 「深色模式偏好为 null 时怎么解释」的**唯一**判定处（review/03-ui.md P2-16）。
 *
 * 本应用是深色优先设计：从未手动设置过（null）时按**深色**处理，
 * **刻意不跟随系统** —— 这是产品决定，不是"跟随系统还没实现"。
 * 两个消费方（MainActivity 的主题 与 NbaLiveApp 的界面状态）都调这个函数，
 * 所以规则只会有一份；将来真要改成跟随系统，改这一行即可。
 * DarkModeResolveTest 钉住了"null 一律按深色"，静默的行为变更会变红。
 *
 * ⚠️ 这里**没有** `systemDark` 参数：当前规则根本不消费系统值，
 * 传一个不参与判定的参数会让这段代码读起来像"null 跟随系统"，
 * 那正是 P2-16 要修掉的那种「实现与表述相反」。
 */
internal fun resolveDarkMode(pref: Boolean?): Boolean = pref ?: DARK_FIRST_WHEN_UNSET

/**
 * 从 SharedPreferences 的读取结果还原偏好（review/03-ui.md P2-31 的语义前提）。
 *
 * 只有 key **真的存在**才代表用户手动设置过；缺失时必须是 null。
 * 不能拿 `getBoolean(key, true)` 的默认值冒充用户设置 ——
 * 那会把「从未设置过」与「设置了深色」混成一谈，
 * 也会让"跟随系统"这类将来语义无从实现。
 */
internal fun darkModeFromStorage(hasValue: Boolean, stored: Boolean): Boolean? =
    if (hasValue) stored else null