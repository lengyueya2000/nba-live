package com.nbalive.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nbalive.app.ui.AppPrefs
import com.nbalive.app.ui.MainViewModel
import com.nbalive.app.ui.NbaLiveApp
import com.nbalive.app.ui.PipState
import com.nbalive.app.ui.theme.NbaLiveTheme
import kotlin.math.min

class MainActivity : ComponentActivity() {

    companion object {
        /** 系统允许的画中画宽高比上下限（超出 enterPictureInPictureMode 会抛异常） */
        private const val MAX_PIP_RATIO = 2.39f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 新 Activity 实例必然不在小窗里。若不重置：用户把小窗划掉时系统
        // 不保证回调 onPictureInPictureModeChanged(false)，进程还活着的话，
        // 下一次进播放页会直接渲染成"只有视频、无任何控件"的假死界面。
        PipState.inPip.value = false
        com.nbalive.app.ui.PlayerPrefs.init(applicationContext)
        AppPrefs.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            // 深色模式：本应用设计是深色优先，没手动设置过就保持深色；
            // 用户在「我的」或顶栏切换后按选择走
            val darkPref by AppPrefs.darkMode.collectAsStateWithLifecycle()
            val darkTheme = darkPref ?: true
            NbaLiveTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()
                    NbaLiveApp(viewModel = vm)
                }
            }
        }
    }

    /**
     * 复用已有实例（launchMode=singleTask）：从桌面图标再次点进来时，
     * 系统不会再建一个 Activity，而是把已有实例调到前台。
     *
     * 不这样做的话：标准启动模式会在栈顶再叠一个 MainActivity，
     * 两份 Compose 树、两份 ViewModel 同时存在，进程级单例 PipState
     * 被新实例复位成 false（旧实例还在小窗里，状态就打架了）。
     *
     * 这里没有深链，但仍然要 setIntent：系统默认不会把新 Intent
     * 写回 getIntent()，后续读取会拿到过期的那个。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** 进入画中画（比例取真实视频宽高，没有则 16:9） */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // minSdk 是 24：Android 7.x 根本没有画中画，按钮点了必须给个说法
            toast("当前系统版本不支持画中画")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toast("当前设备不支持画中画")
            return
        }
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio())
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+：系统在手势缩小/按 Home 时自动进小窗
                        setAutoEnterEnabled(true)
                        // 源区域提示：告诉系统视频在屏幕上的位置，
                        // 小窗过渡动画才不会整屏闪一下
                        videoSourceRect()?.let { setSourceRectHint(it) }
                    }
                }
                .build()
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        }.onFailure {
            // 静默失败会让用户以为按钮坏了
            Log.w("MainActivity", "enterPip failed: ${it.message}", it)
            toast("进入画中画失败")
        }
    }

    /**
     * 画中画窗口比例：优先用真实视频宽高（非 16:9 的源不该加黑边），
     * 拿不到时回退 16:9。
     *
     * 系统要求比例必须落在 1/2.39 ~ 2.39 之间，超出会直接抛异常。
     */
    private fun pipAspectRatio(): Rational {
        // 宽高是一对，原子读（避免读到"新宽 + 旧高"）
        val size = PipState.videoSize.value
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val ratio = w.toFloat() / h.toFloat()
        if (ratio > MAX_PIP_RATIO) return Rational(239, 100)
        if (ratio < 1f / MAX_PIP_RATIO) return Rational(100, 239)
        // 约分：Rational 对超大整数不友好（系统内部会做乘法）
        val g = gcd(w, h)
        return Rational(w / g, h / g)
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** 轻提示：画中画这类系统级操作失败时必须让用户知道 */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 视频区在窗口中的位置（居中的 16:9）。
     *
     * 视频在 Compose 里渲染，这里拿不到真正的控件边界，
     * 用窗口居中的 16:9 区域近似——它只用于小窗过渡动画的起点提示。
     */
    private fun videoSourceRect(): Rect? {
        val decor = window?.decorView ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        val videoHeight = min(decor.width * 9 / 16, decor.height)
        val videoWidth = videoHeight * 16 / 9
        val left = (decor.width - videoWidth) / 2
        val top = (decor.height - videoHeight) / 2
        return Rect(
            loc[0] + left,
            loc[1] + top,
            loc[0] + left + videoWidth,
            loc[1] + top + videoHeight,
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
    }

    /** 按 Home 键离开时，如果正在看直播就自动小窗，不中断播放 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipState.playerActive.value && !PipState.inPip.value) enterPip()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            PipState.inPip.value = false
            PipState.setPlayerActive(false)
        }
    }
}
