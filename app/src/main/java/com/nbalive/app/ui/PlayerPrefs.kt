package com.nbalive.app.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 画面填充模式。 */
enum class AspectMode(val label: String) {
    /** 保持比例完整显示（可能有黑边） */
    FIT("自适应"),

    /** 保持比例铺满，超出部分裁掉 */
    ZOOM("铺满裁切"),

    /** 无视比例强行拉伸铺满（画面会变形） */
    FILL("拉伸铺满"),
    ;

    @get:OptIn(UnstableApi::class)
    val resizeMode: Int
        get() = when (this) {
            FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

    companion object {
        fun of(name: String?): AspectMode = entries.firstOrNull { it.name == name } ?: FIT
    }
}

/**
 * 播放偏好：画面模式 + 屏幕常亮。
 * 用 SharedPreferences 持久化，进程内以 StateFlow 供 Compose 观察。
 */
object PlayerPrefs {
    private const val KEY_ASPECT = "aspect_mode"
    private const val KEY_KEEP_ON = "keep_screen_on"

    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val sp = sp(context)
        _aspect.value = AspectMode.of(sp.getString(KEY_ASPECT, null))
        _keepScreenOn.value = sp.getBoolean(KEY_KEEP_ON, true)
    }

    fun setAspect(context: Context, mode: AspectMode) {
        _aspect.value = mode
        sp(context).edit().putString(KEY_ASPECT, mode.name).apply()
    }

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        _keepScreenOn.value = enabled
        sp(context).edit().putBoolean(KEY_KEEP_ON, enabled).apply()
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
}
