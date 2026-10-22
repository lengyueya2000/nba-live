package com.nbalive.app

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
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

class MainActivity : ComponentActivity() {

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

    /** 进入画中画（16:9，与视频区比例一致） */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
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
        if (PipState.playerActive && !PipState.inPip.value) enterPip()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            PipState.inPip.value = false
            PipState.playerActive = false
        }
    }
}
