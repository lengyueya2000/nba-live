package com.nbalive.app

import android.app.PictureInPictureParams
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
import com.nbalive.app.data.remote.safeLogWarn
import com.nbalive.app.ui.AppPrefs
import com.nbalive.app.ui.MainViewModel
import com.nbalive.app.ui.NbaLiveApp
import com.nbalive.app.ui.PipState
import com.nbalive.app.ui.PlaybackHealth
import com.nbalive.app.ui.PlayerPrefs
import com.nbalive.app.ui.resolveDarkMode
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
        PlayerPrefs.init(applicationContext)
        AppPrefs.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            // 深色模式：本应用设计是深色优先 —— 没手动设置过（null）就是深色。
            // ⚠️「null 到底怎么解释」只有**一处**实现：AppPrefs.resolveDarkMode（有单测）。
            // 这里与 NbaLiveApp 必须调用同一个函数，否则将来规则一变、漏改一处，
            // 就会出现「主题是浅色、状态栏图标按深色画」的错配（review/03-ui.md P2-16）。
            val darkPref by AppPrefs.darkMode.collectAsStateWithLifecycle()
            NbaLiveTheme(darkTheme = resolveDarkMode(darkPref)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()
                    NbaLiveApp(
                        viewModel = vm,
                        // 偏好落盘是"平台层"的活：需要 Context 的事情留在 Activity，
                        // Composable 只回调、不持有 Context（review/03-ui.md P2-17）
                        onDarkModeChange = { AppPrefs.setDarkMode(applicationContext, it) },
                        onAspectChange = { PlayerPrefs.setAspect(applicationContext, it) },
                    )
                }
            }
        }
    }

    // ⚠️ 这里**故意不实现** `onNewIntent`（review/03-ui.md P2-4）。
    //
    // launchMode=singleTask 保证再次点图标时复用本实例、不叠第二份 Compose 树
    // （这正是当初选 singleTask 的理由）。而 Manifest 的 intent-filter 只有
    // MAIN/LAUNCHER，**没有任何深链**（无 VIEW/BROWSABLE），新 Intent 里没有
    // 可读的数据 —— 原来那个只调 `setIntent(intent)` 的空实现是纯死代码，
    // 而它的注释写得像"为深链准备"，会让后来者以为深链已经支持。
    //
    // 将来真加深链时再实现 onNewIntent，并且必须**真的消费** intent；
    // NavHostGuardTest 会拦住"又加回一个只调 setIntent 的空实现"。

    /**
     * 把「系统按 Home 时自动进小窗」的开关同步到当前播放状态。
     *
     * ⚠️ `setAutoEnterEnabled(true)` 是**粘性**的：一旦设上，
     * 系统就在用户按 Home / 手势上滑时自己进小窗，**完全不经过**
     * [onUserLeaveHint] 里的 `PipState.playerActive` 判断。
     * 于是出现回归（v1.2.8 实测）：播放页显示「网络错误」、
     * 画面完全是死的，按 Home 照样弹出一个小窗，里面还是那句
     * 「网络错误 / 请试试 刷新」—— 坏掉的小窗挂在桌面上，
     * 用户既看不了球，也没有任何办法操作它。
     *
     * 更糟的是它一旦粘上就再也不掉：从播放页返回列表、甚至比赛结束之后，
     * 按 Home 依然会进小窗。
     *
     * 所以这里**跟着播放状态动态开关**：真的在看（有流且没报错）才允许
     * 系统自动进小窗，其它情况一律关掉，让 [onUserLeaveHint]
     * 的显式判断成为唯一入口。
     */
    fun syncAutoEnterEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val active = PipState.playerActive.value
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio())
                .setAutoEnterEnabled(active)
                .apply {
                    // 源区域提示只在真进小窗时才有意义，关掉时不必给
                    if (active) videoSourceRect()?.let { setSourceRectHint(it) }
                }
                .build()
            setPictureInPictureParams(params)
        }.onFailure {
            Log.w("MainActivity", "syncAutoEnterEnabled($active) failed: ${it.javaClass.simpleName}")
        }
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
                        // Android 12+：系统在手势缩小/按 Home 时自动进小窗。
                        // ⚠️ 只在真的在看时开：见 syncAutoEnterEnabled 的说明
                        setAutoEnterEnabled(PipState.playerActive.value)
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
            safeLogWarn("MainActivity", "enterPip failed", it)
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

    /** 按 Home 键离开时，如果正在看直播就自动小窗，不中断播放 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipState.playerActive.value && !PipState.inPip.value) enterPip()
    }

    /**
     * 播放状态变化后，把系统级「自动进小窗」的开关同步过去。
     *
     * Compose 侧的 [NbaLiveApp] 写 [PipState.playerActive]，
     * 这里在 start 时读一次最新的值 —— 覆盖"用户还在 App 内
     * 就切换了比赛/线路"的情况（那时 Activity 不会重新走 onCreate）。
     */
    override fun onStart() {
        super.onStart()
        syncAutoEnterEnabled()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
        // 刚进小窗：播放器要过一会儿才知道能不能播，先不动，
        // 交给 Compose 侧在拿到真实错误后再决定要不要收掉
        if (isInPictureInPictureMode) return
        // 退出小窗后把开关复位：留在 true 会继续粘住系统行为
        syncAutoEnterEnabled()
    }

    /**
     * 按播放健康等级处置小窗（P0-1 改为分级 + 可逆）。
     *
     * ⚠️ 两个关键约束：
     *
     * 1. **只有"确认失败"才会真的收窗**。25 秒没嗅探到流属于推断，
     *    等级是 [PlaybackHealth.Level.SUSPECT]，那里的处置是 NONE —— 不收窗。
     *    否则 MSE/`blob:` 播放、地址不带 `.m3u8` 字面量、弱网首帧超 25 秒
     *    这三种正常在播的情况都会被误杀。
     *
     * 2. **处置必须可逆**。原来这里用 `finishAndRemoveTask()`，
     *    会把任务从**最近任务**里一并移除：单 Activity 的应用里，
     *    用户等于彻底失去了这个 App 的入口，而触发它的是一次误判。
     *    改成 `finish()` 后任务记录保留，点开还能看到完整错误页和重试入口
     *    —— 这才是本函数旧注释里承诺的行为（旧代码做不到，任务已被移除）。
     */
    fun finishPipIfBroken(level: PlaybackHealth.Level = PlaybackHealth.Level.DEAD) {
        if (!PipState.inPip.value) return
        // 处置权限由分级判定给出，避免"推断拿走不可逆处置"
        when (PlaybackHealth.pipDisposition(level)) {
            PlaybackHealth.PipDisposition.NONE -> Unit
            PlaybackHealth.PipDisposition.KEEP_RECENTS -> runCatching {
                // finish() 而不是 finishAndRemoveTask()：保留最近任务
                finish()
            }.onFailure {
                safeLogWarn("MainActivity", "finishPipIfBroken failed", it)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            PipState.inPip.value = false
            PipState.setPlayerActive(false)
        }
    }
}
