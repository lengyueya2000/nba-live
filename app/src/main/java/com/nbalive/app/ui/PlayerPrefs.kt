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
 * 播放偏好：画面模式。
 * 用 SharedPreferences 持久化，进程内以 StateFlow 供 Compose 观察。
 *
 * ⚠️ 屏幕常亮**不在这里**：它已经是产品固定行为（默认开启、不提供关闭），
 * 由播放页的 PlayerView / WebView 各自设 `keepScreenOn = true` 保证。
 * 早先这里存过 keep_screen_on 开关，现在开关移除了 ——
 * 保留一个没人读的偏好项只会让后来的人以为"它还有用"。
 */
object PlayerPrefs {
    private const val KEY_ASPECT = "aspect_mode"

    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    fun init(context: Context) {
        // ⚠️ 不要用 loaded 短路：
        // PlayerPrefs 是 object 单例，任何早于 MainActivity.onCreate 的读取
        // （比如将来加了 Service/Receiver）都会拿到初值而不是用户设置的值。
        // SharedPreferences 本身已在内存里，读一次开销可忽略，所以每次都读。
        val sp = sp(context)
        _aspect.value = AspectMode.of(sp.getString(KEY_ASPECT, null))
    }

    fun setAspect(context: Context, mode: AspectMode) {
        _aspect.value = mode
        sp(context).edit().putString(KEY_ASPECT, mode.name).apply()
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
}
