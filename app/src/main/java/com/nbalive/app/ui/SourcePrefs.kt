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

    /**
     * 「新增源迁移做过没有」的标记（2026-11-15 第三源接入）。
     *
     * ⚠️ 必须是**独立**的布尔键，不能从 `enabled_providers` 里推断：
     * "用户自己关掉第三源"之后磁盘上剩的 `{JRS, JRKAN}` 与老用户的旧数据**逐字相同**，
     * 没有标记就只能靠猜，而猜错的代价是每次启动替用户打开一个他刚关掉的源。
     */
    private const val KEY_NEW_SOURCE_MIGRATED = "new_source_migrated"

    private var store: SharedPreferences? = null

    private val _enabled = MutableStateFlow(SourceTogglePolicy.ALL)
    /** 现在参与解析的源。默认全开（[SourceTogglePolicy.default]）。 */
    val enabled: StateFlow<Set<Provider>> = _enabled.asStateFlow()

    /**
     * 平台层注入 `applicationContext`（**唯一**需要 Context 的地方）并立刻读一次。
     *
     * ⚠️ U8：`attach` = [attachStore] + [init]；想不阻塞主线程就分开调
     *（`attachStore` 不读盘），见 `PrefsStore.kt` 的 `PrefsPreload`。
     */
    fun attach(context: Context) {
        attachStore(context)
        init()
    }

    /**
     * **只**注入 `applicationContext`，不读盘（U8）。
     *
     * ⚠️ `getSharedPreferences` 只登记并异步解析 XML；阻塞在第一次
     * `contains`/`getStringSet`。未 attach 时 [sp] 仍直接 `error()`。
     */
    fun attachStore(context: Context) {
        store = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    fun init() {
        // ⚠️ 不用 loaded 短路（与 AppPrefs/PlayerPrefs 的既有约定一致）：
        // 任何早于 MainActivity.onCreate 的读取都该拿到用户真实设置。
        val sp = sp()
        val migrated = sp.contains(KEY_NEW_SOURCE_MIGRATED)
        val resolved = SourceTogglePolicy.fromStorage(
            // 未存过 → null → fromStorage 回落到全开
            if (sp.contains(KEY_ENABLED)) sp.getStringSet(KEY_ENABLED, emptySet()) else null,
            migrated = migrated,
        )
        _enabled.value = resolved
        // ⚠️ 迁移那一次必须**同时**落两样东西：标记 + 迁移后的集合。
        // 只落标记是错的：磁盘上仍是 {JRS, JRKAN}，用户没碰过开关的话，
        // **第二次**启动就走到 migrated=true 的"原样读回"分支，第三源又关了 ——
        // 现象变成"升级后第一天能看新源、之后又没了"，比一直不迁移更难排查。
        // 写回是幂等的：中途被杀进程，下次启动补开的是同一个源。
        if (!migrated) {
            sp.edit()
                .putBoolean(KEY_NEW_SOURCE_MIGRATED, true)
                .putStringSet(KEY_ENABLED, SourceTogglePolicy.toStorage(resolved))
                .apply()
        }
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
