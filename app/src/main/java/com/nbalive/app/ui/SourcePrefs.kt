package com.nbalive.app.ui

import android.content.Context
import android.content.SharedPreferences
import com.nbalive.app.data.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 数据源开关的 **Android 存储**（2026-10-31 用户需求）。
 *
 * 与 [AppPrefs] / [PlayerPrefs] 同一套约定：
 *  - `applicationContext` 只由平台层 [attach] 注入一次；
 *  - 写入是无 `Context` 形参的方法；
 *  - 界面不直接读这个单例，经 [PrefsStore] 注入 `MainViewModel`。
 *
 * 存的是**枚举名集合**（`{"JRS","JRKAN"}`）而不是序号或位掩码：
 * 序号会随枚举顺序变化串味，位掩码读不懂、加源时还得算位。
 */
object SourcePrefs {
    private const val NAME = "source_prefs"
    private const val KEY_ENABLED = "enabled_providers"

    private var store: SharedPreferences? = null

    private val _enabled = MutableStateFlow(SourceTogglePolicy.ALL)
    /** 现在参与解析的源。默认全开（[SourceTogglePolicy.default]）。 */
    val enabled: StateFlow<Set<Provider>> = _enabled.asStateFlow()

    /** 平台层注入 `applicationContext`（**唯一**需要 Context 的地方）并立刻读一次。 */
    fun attach(context: Context) {
        store = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        init()
    }

    fun init() {
        // ⚠️ 不用 loaded 短路（与 AppPrefs/PlayerPrefs 的既有约定一致）：
        // 任何早于 MainActivity.onCreate 的读取都该拿到用户真实设置。
        val sp = sp()
        _enabled.value = SourceTogglePolicy.fromStorage(
            // 未存过 → null → fromStorage 回落到全开
            if (sp.contains(KEY_ENABLED)) sp.getStringSet(KEY_ENABLED, emptySet()) else null,
        )
    }

    /** 写入（无 `Context` 形参）。 */
    fun setEnabled(enabled: Set<Provider>) {
        // 再兜一次底：调用方若传了空集（不该发生，但偏好是持久化的，
        // 一旦写进去用户下次启动就是空列表），落盘前按全开处理。
        val safe = enabled.ifEmpty { SourceTogglePolicy.ALL }
        _enabled.value = safe
        sp().edit().putStringSet(KEY_ENABLED, SourceTogglePolicy.toStorage(safe)).apply()
    }

    /** 没 [attach] 就直接炸（理由与 `AppPrefs.sp()` 相同：不允许偏好无声失效）。 */
    private fun sp(): SharedPreferences = store
        ?: error("SourcePrefs 还没 attach(applicationContext)：偏好读写会无声失败，所以直接失败")
}
