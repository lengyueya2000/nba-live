package com.nbalive.app.ui

import android.content.pm.ActivityInfo
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.MainActivity
import kotlinx.coroutines.delay

/**
 * 根节点的**系统级副作用**（review/03-ui.md P2-18）。
 *
 * 原来这些 effect 全挤在 `NbaLiveApp` 的函数体里（约 90 行），既是"上帝组件"
 * 的一部分，又制造了一个隐式依赖：`syncAutoEnterEnabled()` 能否读到正确的
 * `PipState.playerActive`，取决于 Compose 把 `DisposableEffect` 排在
 * `LaunchedEffect` 之前执行（review/03-ui.md P2-6）。
 * 现在每条副作用各自成函数，`AppPipEffects` 里把顺序写成**代码顺序**。
 */

/**
 * 列表状态老化的轮询间隔（P1-1）。
 *
 * 只做纯内存重算（`MainViewModel.ageListStatuses`），不发网络请求，
 * 因此 60 秒既能让「比赛已结束」在一分钟内从「直播中」纠正过来，
 * 又不会对源站产生任何额外压力。
 */
internal const val LIST_STATUS_AGE_INTERVAL_MS = 60_000L

/**
 * 画中画 / 自动进小窗的接线（review/03-ui.md P2-6 的修法）。
 *
 * ## 为什么合并成一个 effect
 *
 * 早先是两个 effect 分工：
 *  - `DisposableEffect(route, playingNow)` 写进程级 `PipState.playerActive`；
 *  - `LaunchedEffect(playingNow, playbackLevel)` 调 `activity.syncAutoEnterEnabled()`，
 *    而 `syncAutoEnterEnabled()` **读的正是** `PipState.playerActive`。
 *
 * 它能读到正确值，靠的只是"同一次组合里 DisposableEffect 排在 LaunchedEffect
 * 之前执行"这个 Compose 调度顺序 —— 一个没有任何编译期/测试信号的隐式依赖。
 * 调整声明位置、把其中一个抽成独立 Composable，都会静默变成
 * "自动进小窗偶尔不生效"。
 *
 * 现在顺序是**显式**的：同一个 effect 内按代码顺序先写状态、再刷新开关，
 * 不再依赖 Compose 把哪个 effect 排在前面。清理仍由 `DisposableEffect` 负责。
 */
@Composable
internal fun AppPipEffects(
    activity: MainActivity?,
    route: Route,
    playingNow: Boolean,
    playbackLevel: PlaybackHealth.Level,
) {
    LaunchedEffect(route, playingNow, playbackLevel) {
        // ① 只有**真的在看视频**时才允许「按 Home 自动进小窗」。
        // 判定集中在 PipState.shouldAutoEnterPip（可单测），
        // 早先只看 route == PLAYER，导致解析中/无信号/比赛已结束时
        // 按 Home 也会弹出一块空小窗。
        PipState.setPlayerActive(
            PipState.shouldAutoEnterPip(
                isPlayerRoute = route == Route.PLAYER,
                hasStream = playingNow,
            ),
        )
        // ② 再按**刚落下的**状态刷新系统级「自动进小窗」开关。
        // setAutoEnterEnabled(true) 是**粘性**的（见 MainActivity.syncAutoEnterEnabled）：
        // 一旦设上，系统在按 Home 时自己进小窗，完全不经过 ① 的判断。
        // ⚠️ 这一句必须在 ① 之后 —— 顺序现在就写在代码里，不再靠 effect 调度顺序。
        activity?.syncAutoEnterEnabled()
        // ③ 收小窗只在等级为「确认失败」时发生（P0-1）。
        // 处置权限由 PlaybackHealth.pipDisposition 决定，这里不自己判断，
        // 避免"疑似异常"这类推断拿走不可逆的处置。
        activity?.finishPipIfBroken(playbackLevel)
    }
    DisposableEffect(route, playingNow) {
        onDispose { PipState.setPlayerActive(false) }
    }
}

/**
 * 离开播放页自动退出全屏，避免下次进来是横屏。
 *
 * 同时解除锁定：锁是"看当前这场"的状态，换场/退出就该恢复
 * （review/03-ui.md P2-18：把这段副作用从根节点搬出来）。
 */
@Composable
internal fun AppLeavePlayerEffect(route: Route, onLeavePlayer: () -> Unit) {
    LaunchedEffect(route) {
        if (route != Route.PLAYER) onLeavePlayer()
    }
}

/**
 * 列表状态随时间老化（P1-1）。
 *
 * 状态原来只在 loadMatches 结束时算一次，之后永不重算 ——
 * 用户不手动刷新，早上 10:00 开赛的比赛到晚上还挂着「直播中」，
 * 点进去是"网络错误"。这是那个反馈的结构性根因：
 * 之前修的是"校正算错了"，这层是"不再算第二次"。
 *
 * ⚠️ 这是**纯内存**重算，零网络请求 —— 不违反"不轮询源站"的取舍。
 * ⚠️ 只在列表可见时跑：进详情页/播放页就停，切后台时组合被移出也会停。
 */
@Composable
internal fun AppListStatusAgeEffect(route: Route, tab: NavTab, onTick: () -> Unit) {
    LaunchedEffect(route, tab) {
        if (route != Route.LIST || tab == NavTab.PROFILE) return@LaunchedEffect
        while (true) {
            onTick()
            delay(LIST_STATUS_AGE_INTERVAL_MS)
        }
    }
}

/**
 * 播放降级提示：只在播放页呈现，避免用户已经返回列表还弹出"直连线路不可用"。
 *
 * ⚠️ key 用 errorId（每次报错自增），不能只拿文案当 key：
 * 同样的文案连着报两次时，StateFlow 会把相等值合并掉，
 * LaunchedEffect 不重启，第二次就再也弹不出来了。
 *
 * ## P2-7 的定论：这是**有意**的「只显示最新一条」，不是 bug
 *
 * 审查（03-ui.md P2-7）指出：`showSnackbar` 是挂起调用，`errorId` 再自增时本 effect
 * 重启 → 上一条的协程被取消 → **前一条提示提前消失**；故障切换连发两条时用户只看到最后一条。
 *
 * 复核后的结论是**不改代码**，理由是审查自己给的第二个选项：
 * 「若只想显示最新一条」——这正是当前行为（`showSnackbar` 本身就会先撤掉上一条），
 * 所以"覆盖"已经是实际语义；再补一句 `currentSnackbarData?.dismiss()` 只是把同一件事写第二遍
 * （新增的显式调用在旧协程尚未处理完取消时最多省一次等待），属无效修复，已回退。
 * 至于审查的第一个选项（用 Channel 逐条顺序展示），那是**产品选择**：连发两条时
 * 用户要不要排队看完，得由产品定；在没定之前保持"最新一条"更符合"错误提示不堆积"的直觉。
 *
 * ⚠️ 因此：**不要**给这里加 Channel/队列，除非产品明确要求逐条展示
 * （那会改变用户看到提示的时长与数量）。
 */
@Composable
internal fun PlayerNoticeEffect(
    route: Route,
    errorId: Long,
    errorMessage: String?,
    host: SnackbarHostState,
) {
    LaunchedEffect(route, errorId) {
        if (route == Route.PLAYER) {
            errorMessage?.let { host.showSnackbar(it) }
        }
    }
}

/**
 * 全屏时切横屏并隐藏系统栏；退出时恢复。
 * 只在真正的全屏（非画中画）下生效。
 */
@Composable
internal fun FullscreenSystemEffect(enabled: Boolean, inPip: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(enabled, inPip, activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        // ⚠️ 判据抽在 SystemBarsPolicy（有单测），这里只负责执行。
        // P2-7：进小窗时 enabled 会从 true 变 false，DisposableEffect 走 onDispose ——
        // 那里若把方向复位成 UNSPECIFIED，小窗窗口会跟着设备方向乱转
        // （用户正看着的画面突然竖过来）。所以小窗期间是 KeepLandscape。
        when (SystemBarsPolicy.orientationFor(fullscreen = enabled, inPip = inPip, onPlayer = true)) {
            OrientationAction.Landscape,
            OrientationAction.KeepLandscape,
            -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationAction.Reset ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (SystemBarsPolicy.shouldHideSystemBars(fullscreen = enabled, inPip = inPip)) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // ⚠️ onDispose 里也要判小窗（P2-7 的核心）：
            // 进小窗会销毁这个 effect，若这里无条件 Reset，
            // 小窗一出现方向就被复位 —— 正是审查指出的那个 bug。
            if (!inPip) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/**
 * 退出小窗后回到全屏播放（2026-10-30 现场反馈的修复）。
 *
 * ## 问题
 *
 * 竖屏播放页 → 点「画中画」→ 小窗里点系统的「全屏」→ **回到竖屏播放页**。
 *
 * `fullscreen` 是进小窗前那一刻的值，竖屏页进小窗时是 false；
 * 退出小窗没有任何一步把它改回 true —— 用户按的明明是「全屏」。
 *
 * ## 为什么用计数（[PipState.expandRequests]）而不是 Boolean
 *
 * 小窗期间 Activity 是 PAUSED，`collectAsStateWithLifecycle` 停止收集。
 * "写 true → 消费 → 复位 false" 全发生在不可见时段时，复位的 false 会盖掉 true，
 * Compose 永远看不到这次请求。计数只会单调增，回到 STARTED 时一定看得到最新值。
 *
 * `handled` 的初值取首次组合看到的值：进程重建后不会把历史请求再执行一遍。
 */
@Composable
internal fun PipExitFullscreenEffect(onExpand: () -> Unit) {
    val requests by PipState.expandRequests.collectAsStateWithLifecycle()
    var handled by remember { mutableIntStateOf(requests) }
    // 回调里用到最新的 route/fullscreen 写入器，别用启动那一刻捕获的旧 lambda
    val currentOnExpand by rememberUpdatedState(onExpand)
    LaunchedEffect(requests) {
        if (requests > handled) {
            handled = requests
            currentOnExpand()
        }
    }
}