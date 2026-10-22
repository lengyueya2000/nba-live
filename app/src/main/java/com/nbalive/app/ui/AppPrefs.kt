package com.nbalive.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级偏好（与播放相关的偏好放在 PlayerPrefs）。
 *
 * 目前只有一项：深色模式。默认跟随系统（null = 未手动设置过）。
 */
object AppPrefs {
    private const val KEY_DARK = "dark_mode"

    private val _darkMode = MutableStateFlow<Boolean?>(null)
    /** null = 跟随系统；true/false = 用户手动指定 */
    val darkMode: StateFlow<Boolean?> = _darkMode.asStateFlow()

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val sp = sp(context)
        _darkMode.value = if (sp.contains(KEY_DARK)) sp.getBoolean(KEY_DARK, true) else null
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        _darkMode.value = dark
        sp(context).edit().putBoolean(KEY_DARK, dark).apply()
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
}
