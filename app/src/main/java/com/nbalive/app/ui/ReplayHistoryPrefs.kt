package com.nbalive.app.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 录像播放历史的 **Android 存储**（2026-11-25 用户需求）。
 *
 * 与 [AppPrefs] / [SourcePrefs] 同一套约定：
 *  - `applicationContext` 只由平台层注入一次；
 *  - 写入无 `Context` 形参；
 *  - 序列化逻辑在 [ReplayHistoryStorage]（纯 Kotlin，可 JVM 单测），这里只管落盘。
 */
object ReplayHistoryPrefs {
    private const val NAME = "replay_history_prefs"
    private const val KEY_ITEMS = "items"

    private var store: SharedPreferences? = null

    private val _history = MutableStateFlow<List<ReplayHistoryItem>>(emptyList())
    val history: StateFlow<List<ReplayHistoryItem>> = _history.asStateFlow()

    fun attachStore(context: Context) {
        store = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    fun init() {
        val raw = sp().getString(KEY_ITEMS, null).orEmpty()
        _history.value = ReplayHistoryPolicy.merge(ReplayHistoryStorage.decode(raw))
    }

    /** 写入历史（无 `Context` 形参），落盘前再按判据归一化一次（去重/排序/上限）。 */
    fun setHistory(items: List<ReplayHistoryItem>) {
        val safe = ReplayHistoryPolicy.merge(items)
        _history.value = safe
        sp().edit().putString(KEY_ITEMS, ReplayHistoryStorage.encode(safe)).apply()
    }

    private fun sp(): SharedPreferences = store
        ?: error("ReplayHistoryPrefs 还没 attach(applicationContext)：偏好读写会无声失败，所以直接失败")
}
