package com.nbalive.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.isPostponed
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.data.remote.debugLog
import com.nbalive.app.data.remote.redactUrl
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.AudioFocusPolicy
import com.nbalive.app.ui.CardSemantics
import com.nbalive.app.ui.PipState
import com.nbalive.app.ui.PlayerClock
import com.nbalive.app.ui.WebSniffState
import com.nbalive.app.ui.PlayerControls
import com.nbalive.app.ui.ReplayHistoryPolicy
import com.nbalive.app.ui.SeekGesturePolicy
import com.nbalive.app.ui.PlaybackHealth
import com.nbalive.app.ui.PlayerLoadingHintPolicy
import com.nbalive.app.ui.StalledPlayback
import com.nbalive.app.ui.hhmm
import com.nbalive.app.ui.ManualPipDecision
import com.nbalive.app.ui.ManualPipPolicy
import com.nbalive.app.ui.SystemBarsPolicy
import com.nbalive.app.ui.PipAspectReset
import com.nbalive.app.ui.WebMediaPauseScript
import com.nbalive.app.ui.WebNavigationPolicy
import com.nbalive.app.ui.WebErrorProbeScript
import com.nbalive.app.ui.WebPageDeadEndPolicy
import com.nbalive.app.ui.WebSniffWatchdog
import com.nbalive.app.ui.PlayerLock
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.accentBrush
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.icons.AppIcons


/**
 * 播放器界面。
 *
 * 播放策略：
 *  - 若已解析出 m3u8 -> Media3 ExoPlayer 直连播放（JRKAN 线路）
 *  - 否则 -> WebView 承载源站播放页（JRS 线路，播放页由源站 JS 自行解密）
 */
@Composable
fun PlayerScreen(
    match: Match?,
    signals: List<StreamSource>,
    selectedIndex: Int,
    resolving: Boolean,
    streamUrl: String?,
    streamReferer: String?,
    webFallbackUrl: String?,
    errorMessage: String?,
    onSignalSelected: (Int) -> Unit,
    otherMatches: List<Match>,
    onOtherMatchClick: (Match) -> Unit,
    /** Modifier 必须是第一个可选参数（Compose API 规范） */
    modifier: Modifier = Modifier,
    onPlaybackError: () -> Unit = {},
    /**
     * 网页线路**确认**播不出来（播放页主框架真实加载失败，错误码已筛选）。
     *
     * 上层据此关掉「按 Home 自动进小窗」并收掉已存在的小窗（可逆处置）。
     */
    onPlaybackDead: () -> Unit = {},
    /**
     * 网页线路**疑似**播不出来（页面加载完成后 15 秒没看到任何媒体请求）。
     *
     * ⚠️ 与 [onPlaybackDead] 分开是因为判据性质不同（P0-1）：
     * 这条是**推断**，只用于显示提示 + 停自动进小窗，**不允许**收窗或关任务。
     */
    onPlaybackSuspect: () -> Unit = {},
    /**
     * 页面里检测到源站播放器**自己的错误面板**（xgplayer 的 `xgplayer-is-error`）。
     *
     * 与 [onPlaybackDead]/[onPlaybackSuspect] 的区别：这条是页面 DOM 里的**事实**
     * （播放器自己设的状态类），不是我们推断的"嗅探不到"。
     * 判据见 [com.nbalive.app.ui.WebPageDeadEndPolicy]。
     */
    onPageDeadEnd: () -> Unit = {},
    /**
     * 死胡同**恢复条出现**的那一刻上报一次"疑似"（P18，复核修正版）。
     *
     * ⚠️ 与 [onPlaybackSuspect]/[onPageDeadEnd] 都不同，必须接到一个
     * **只置疑似标记、不换线**的入口：
     *  - `onPlaybackSuspect` 在 `MainViewModel` 里置标记之后紧接着
     *    `advanceAfterNoSignal(...)`（`MainViewModel.kt:1242-1254`）→ 1.5 秒就换线，
     *    会把恢复条与用户的 8 秒选择窗口一起抹掉；
     *  - `onPageDeadEnd` 是 9.5 秒后的处置，太晚 —— 中间那 8 秒里
     *    `playbackSuspect` 仍是 false，按 Home 会进一块坏小窗。
     *
     * 用途只有一句：让上层（`NbaLiveApp` 的 `playingNow` 判据）在这 8 秒里也停掉
     * 「按 Home 自动进小窗」。⛔ 默认空实现 = 本项行为与改动前一致（接线由 Lead 协调）。
     */
    onDeadEndSuspect: () -> Unit = {},
    onStreamSniffed: (String, String?) -> Unit = { _, _ -> },
    /** 嗅探升级失败退回网页：不再弹"暂无信号"提示 */
    sniffFailed: Boolean = false,
    aspectMode: AspectMode = AspectMode.FIT,
    onAspectChange: (AspectMode) -> Unit = {},
    /** 重连：重新解析当前线路（直播地址带时效签名，过期后需要换新地址） */
    onReconnect: () -> Unit = {},
    /**
     * 「用户要求重连」的意图序号（根因见 [com.nbalive.app.ui.PlayerUiState.reconnectNonce]）。
     *
     * ⚠️ 必须一路传到 VideoSurface / WebSurface：重连时地址常常**逐字不变**
     * （嗅探缓存命中，或网页线路本来就是同一个播放页），
     * 界面只看地址就判定"没变化"，于是用户点了刷新、什么都不发生。
     */
    reconnectNonce: Long = 0L,
    /** 正在直播的其他场次（"同时在播"面板数据源） */
    liveMatches: List<Match> = emptyList(),
    onLiveMatchClick: (Match) -> Unit = {},
    /**
     * 播放期间列表由用户在"同时在播"面板上点按钮手动刷新（不自动刷）：
     * 看球时背后持续请求源站既不发全，也会让面板内容不可控地跳变。
     */
    refreshing: Boolean = false,
    onRefreshLive: () -> Unit = {},
    fullscreen: Boolean = false,
    inPip: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    /**
     * 点「画中画」但当前没有流可播时，给用户一句提示（review/01-player.md P2-6）。
     *
     * ⚠️ 为什么手动路径需要提示而自动路径（按 Home）不需要：
     * 按 Home 的用户本来没打算进小窗，静默不弹即可；
     * 点按钮的用户明确表达了意图，不给反馈他会以为按钮坏了。
     */
    onPipBlocked: (String) -> Unit = {},
    /**
     * 疑似无信号：嗅探超时这类**推断**结果给用户的一句提示（review/01-player.md:41）。
     *
     * ⚠️ 判据由调用方用 `PlaybackHealth.showsSuspectHint(level)` 算好传进来，
     * 本文件**不许**再自己判一次等级 —— 判据两份实现是 P0-1 那轮踩过的坑。
     * 与 [onPlaybackDead] 的区别：这只是提示，**绝不**收窗、绝不关任务。
     */
    suspectHint: Boolean = false,
    /**
     * 锁定（防误触）：浮层全隐藏、手势停用。
     * 解锁入口唯一 —— 右侧中间的锁按钮（见 [onToggleLock]）。
     */
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
    /**
     * 是否录像回放（VOD）。只有它为真时才画可拖拽进度条；直播恒 false。
     * 判据收敛在 [PlayerControls.shouldShowSeekBar]，本文件不再另写一套。
     */
    isVod: Boolean = false,
    /** 录像续播起始位置（毫秒）；0 = 从头播。仅 VOD 消费。 */
    initialSeekMs: Long = 0L,
    /** 录像播放位置上报（节流后由 VideoSurface 调用，VM 写历史）。 */
    onReplayProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    val videoOnly = fullscreen || inPip

    // 播放页的单调时钟（P14 / review/opt/player.md 第 14 条）：
    // 所有"多久了"的判断都走它，而不是散落的 `SystemClock.elapsedRealtime()` ——
    // 纯逻辑（WebSniffState）因此可以在 JVM 单测里注入假时钟。
    val clock = remember { PlayerClock.System }

    // "同时在播"右侧面板
    // ⚠️ rememberSaveable：进程被杀重建（用户切后台太久被回收）后，
    // 已打开的面板会被静默关掉 —— 用户看到"面板自己关了"。
    var showLivePanel by rememberSaveable { mutableStateOf(false) }
    // 播放源 / 播放线路 选择面板（不用 DropdownMenu：Popup 会被视频 SurfaceView 盖住）
    var picker by rememberSaveable { mutableStateOf<PickerKind?>(null) }
    // 视频播放器句柄：自定义控制栏要读播放状态、要能暂停/继续
    // ⚠️ P9 完整版：句柄绑定**播放页**而不是单条线路 ——
    // 播放器现在跨线路复用，工厂只跑一次；句柄若还按 streamUrl 重建，
    // `handle.attach(player)` 不会再执行，底部控制栏会读不到播放/缓冲状态。
    val videoHandle = remember { VideoPlayerHandle() }

    // 全屏时界面默认干净：所有浮层（顶部信息、右上按钮、底部控制栏）隐藏，
    // 单击画面显示、再单击隐藏；显示后 10 秒无操作自动收起。
    // ⚠️ 直连与网页两条路径的显隐行为**完全一致**：
    // 早先为了"保护源站 H5 播放器自己的底部控制条"给网页线路开了特例
    // （webFullscreen 跳过 controlsVisible / 不画底部栏），
    // 结果用户在网页线路里既点不出按钮、也失去换源换线路的入口。
    // 两条路径现在走同一套规则，见 [PlayerControls.shouldShowBottomBar]。
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    // 浮层上的"用户操作"计数：每次点按钮/开面板就 +1，
    // 用来重置"10 秒无操作自动收起"的计时。
    // 否则用户在全屏里慢慢挑线路，挑到一半控制栏自己消失了。
    var controlsTouchSeq by remember { mutableIntStateOf(0) }

    // 录像快进/快退的 OSD（2026-11-25）：显示本次手势累计 seek 到的位置，
    // 用计数式 token 重置收起计时（与 controlsTouchSeq 同款取舍，避免布尔被合并吞掉）。
    var seekHintMs by remember { mutableLongStateOf(-1L) }
    var seekHintSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekHintSeq) {
        if (seekHintSeq == 0 || seekHintMs < 0L) return@LaunchedEffect
        delay(1_200L)
        seekHintMs = -1L
    }

    // 包装一层：所有"用户操作浮层"的点击都走这里，自动续期
    fun touchControls() {
        controlsVisible = true
        controlsTouchSeq++
    }
    // 锁定态下"单击画面唤出的解锁 UI"是否显示。
    // 锁定后画面完全干净（无按钮、无提示），单击才唤出锁按钮，3 秒后自动收起。
    // 用 remember 而不是 saveable：这是瞬时交互态，进程重建后重新收起才合理。
    var unlockUiVisible by remember { mutableStateOf(false) }
    // 解锁 UI 的自动收起计时；每次单击重新计时
    LaunchedEffect(locked, unlockUiVisible) {
        if (locked && unlockUiVisible) {
            delay(PlayerLock.UNLOCK_UI_TIMEOUT_MS)
            unlockUiVisible = false
        }
    }
    // 解除锁定后立刻收起解锁 UI，避免下次进入锁定态时它还是"亮着"的
    LaunchedEffect(locked) {
        if (!locked) unlockUiVisible = false
    }

    // 浮层"显示后 10 秒无操作自动收起"。
    //
    // ⚠️ 只在**全屏**且浮层可见时计时：
    //  - 非全屏时浮层是常驻的（有滚动内容要读），不该自己消失
    //  - 锁定时浮层本来就全隐藏，由 unlockUiVisible 那套 3 秒计时负责
    //
    // key 里带 controlsVisible 和 controlsTouchSeq：
    //  - controlsVisible 变化 → 每次"显示"重新计时
    //  - controlsTouchSeq 变化 → 用户在浮层上做了操作（点按钮/开面板）
    //    也重新计时，否则操作到一半浮层自己消失
    //
    // ⚠️ 面板打开期间**暂停计时**（review/01-player.md P2-8）：
    // 用户在面板里挑线路超过 10 秒，计时器到点会把控制栏收掉；
    // 面板关闭后没人回补 —— 全屏底部控制栏就再也不出现了。
    // 判据抽在 SystemBarsPolicy.shouldAutoHideControls（有单测）。
    LaunchedEffect(fullscreen, controlsVisible, locked, inPip, controlsTouchSeq, picker, showLivePanel) {
        if (SystemBarsPolicy.shouldAutoHideControls(
                fullscreen = fullscreen,
                locked = locked,
                inPip = inPip,
                pickerOpen = picker != null,
                livePanelOpen = showLivePanel,
            ) && controlsVisible
        ) {
            delay(FULLSCREEN_CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    // ⚠️ 进全屏"收起浮层"与"面板刚关闭回补"必须排在**同一个 effect** 里
    // （P6 / review/opt/player.md 第 6 条）：
    // 旧代码是两个都以 `fullscreen` 为 key 的 LaunchedEffect ——
    // 一个写 `controlsVisible = false`，另一个在"无面板 + 全屏"时写 `true`。
    // 进全屏那一次两个都命中，净结果取决于 Compose 启动两个 effect 的顺序
    // （`AppEffects` 自己点名批评过这种依赖），观感是全屏后浮层没立刻收起、
    // 要等 10 秒自动隐。
    //
    // 现在把"面板是否**刚刚关闭**"变成显式输入（判据在 SystemBarsPolicy，有单测）：
    // 进全屏没有这种变化，所以不会抵消收起。
    //
    // ⚠️ `panelWasOpen` 故意**不进 key**：它在 effect 末尾被写成 `panelOpen`，
    // 进了 key 就会自触发一次重启，而第二次跑时它已经是 false ——
    // 反而命中"进全屏收起"，把刚回补的栏又收掉。
    val panelOpen = picker != null || showLivePanel
    var panelWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(fullscreen, panelOpen) {
        if (fullscreen && panelOpen) {
            // 面板开着时强制露出控制栏，避免面板还开着、栏却消失了
            controlsVisible = true
        } else if (SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = picker != null,
                livePanelOpen = showLivePanel,
                fullscreen = fullscreen,
                panelWasOpen = panelWasOpen,
            )
        ) {
            // 面板**刚刚关闭**：显式回补（P2-8 的第二半）
            controlsVisible = true
        } else if (fullscreen && !panelOpen) {
            // 进入全屏（这一次没有"面板刚关闭"）：收起浮层
            controlsVisible = false
        }
        panelWasOpen = panelOpen
    }

    // ---------- 死胡同恢复条的显示内容（P1 真机回归修复，2026-11-01） ----------
    // ⚠️ 状态在这里，**渲染也必须在手势层之后**：
    // 恢复条原来画在 `WebSurface` 内部 —— 而手势层是 `Modifier.fillMaxSize()` 的
    // **后置兄弟**，命中测试先落进手势层那棵子树，于是用户点「重新加载 / 换线路」
    // **完全没有反应**（雷电模拟器实测：连点三次、`input keyevent BACK` 正常，
    // 而 CDP 里 URL 6 秒都没变、也没有任何导航日志）。
    // 现在由 WebSurface 上报"该不该显示 + 文案"，渲染挪到与顶栏/底栏同级的浮层区。
    var webDeadEndBar by remember(webFallbackUrl) { mutableStateOf<WebDeadEndBar?>(null) }
    // 「重新加载」用**计数**而不是布尔事件：与 PipState.expandRequests 同一取舍 ——
    // 布尔事件在重组合并时可能被吞掉，计数只增不减，永远不会丢。
    var webDeadEndReloadRequests by remember(webFallbackUrl) { mutableIntStateOf(0) }

    // 滚动状态必须**无条件** remember（v1.2.26）：
    // 原来写成 `.then(if (videoOnly) Modifier else Modifier.verticalScroll(rememberScrollState()))`
    // —— remember 落在条件分支里，进出全屏/小窗时那条槽位消失又重建，
    // 列表滚到哪儿全丢了（用户退全屏回到顶上，而视频区还在放）。
    // 无条件 remember 之后滚动位置跨全屏保持；videoOnly 时只是**不挂**这个修饰符。
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (videoOnly) Modifier else Modifier.verticalScroll(scrollState))
            .then(if (videoOnly) Modifier else Modifier.padding(bottom = 24.dp)),
    ) {
        // ---------- 视频区 ----------
        // 注意：全屏/画中画只改这个 Box 的尺寸，不改变 VideoSurface 的调用位置，
        // 因此 ExoPlayer 实例不会被重建，切换全屏不会中断播放。
        Box(
            modifier = if (videoOnly) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            },
        ) {
            when {
                // ⚠️ P9 完整版：**不再** `key(streamUrl, streamReferer)` ——
                // 同一个播放页内换线路**复用同一个 ExoPlayer**（解码器/渲染器/缓冲都不重建），
                // 换线只换媒体项（一次性，判据见 LineSwitchPolicy）。
                // Referer 由 RefererDataSourceFactory 在每次 createDataSource() 时**现查**，
                // 所以复用播放器不会出现"换了线路、CDN 还拿旧 Referer → 403"。
                // 回归守卫：`PlayerFixTest.P9完整版`（生产里不许再出现 key(streamUrl, streamReferer)）。
                streamUrl != null -> VideoSurface(
                    streamUrl = streamUrl,
                    referer = streamReferer,
                    aspectMode = aspectMode,
                    handle = videoHandle,
                    onError = onPlaybackError,
                    reconnectNonce = reconnectNonce,
                    initialSeekMs = if (isVod) initialSeekMs else 0L,
                    onReplayProgress = onReplayProgress,
                )
                webFallbackUrl != null -> {
                    // ⚠️ 网页兜底全屏时，WebView **不能铺满整个容器**。
                    //
                    // 实测复现（20:9 屏幕 2400x1080 全屏）：底部源站比分条消失。
                    // 同一场比赛在 16:9 屏幕（1280x720）上比分条可见 ——
                    // 因为真机屏幕比视频源（16:9）细长得多，
                    // WebView 里的 xgplayer 把 16:9 画面**放大铺满** 20:9 容器，
                    // 上下各裁掉一截，底部的比分条正好被裁没。
                    //
                    // 直连路径没这个问题：PlayerView.resizeMode = FIT 会保持
                    // 16:9 加黑边。网页路径得自己算。
                    //
                    // 修法：给 WebView 一个 16:9 的区域（居中、上下留黑边），
                    // 画面完整不裁切，比分条留在画面里。
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val targetSize = if (videoOnly) {
                            PlayerControls.webViewSizeForAspect(
                                containerWidth = constraints.maxWidth,
                                containerHeight = constraints.maxHeight,
                                aspectRatio = 16f / 9f,
                            )
                        } else {
                            null
                        }
                        Box(
                            modifier = if (targetSize != null) {
                                with(LocalDensity.current) {
                                    Modifier
                                        .width(targetSize.width.toDp())
                                        .height(targetSize.height.toDp())
                                }.align(Alignment.Center)
                            } else {
                                Modifier.fillMaxSize()
                            },
                        ) {
                            // ⚠️ key(webFallbackUrl) 是必须的（review/01-player.md P2-4）：
                            // 换网页线路时如果复用同一个 WebView，旧页面**在途的嗅探回调**
                            // 仍会打到 onStreamSniffed —— 界面显示线路 B，
                            // 实际升级去播的却是线路 A 的流（而 A 已被判失败）。
                            // 加 key 让切换线路时销毁重建，旧页面的回调随 WebView 一起作废。
                            // （直连路径早就有 key(streamUrl, streamReferer)，这里补齐。）
                            key(webFallbackUrl) {
                                WebSurface(
                                    url = webFallbackUrl,
                                    onStreamFound = onStreamSniffed,
                                    suppressHint = sniffFailed,
                                    onPlaybackDead = onPlaybackDead,
                                    onPlaybackSuspect = onPlaybackSuspect,
                                    onPageDeadEnd = onPageDeadEnd,
                                    onDeadEndSuspect = onDeadEndSuspect,
                                    // P1 真机回归修复：恢复条的内容上报 + 重载请求下传，
                                    // 渲染在**手势层之后**的浮层区（见 PlayerDeadEndBar）
                                    onDeadEndBarChanged = { webDeadEndBar = it },
                                    reloadRequest = webDeadEndReloadRequests,
                                    reconnectNonce = reconnectNonce,
                                )
                            }
                        }
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (resolving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(Modifier.height(Space.md))
                            Text(
                                text = "正在解析直播源…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(Space.sm))
                            Text(
                                text = "选择下方信号开始播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }

            // 网页兜底加载期间的非阻塞提示（2026-10-30 感知优化）：
            // 实测源站播放页要加载 0.1~27 秒，这期间界面是纯黑 —— 用户看不到任何
            // "App 在工作"的信号，观感就是"点开就卡死"。判据见 PlayerLoadingHintPolicy。
            // ⚠️ 只叠一行文字、不加 clickable：不能挡住 WebView（单击要透传给源站播放器）。
            //
            // ⚠️ P7 完整版（2026-11-01）：**整块搬进 `PlayerLoadingHint`** ——
            // 250ms 的 ticker 连同它的读取点都收进那个 composable 自己的作用域，
            // 于是它只让**那一小块**重组，不再每 250ms 失效本函数体内这块 content lambda
            //（含"其他直播"的过滤与列表分配），而这段时间正是 chromium 与源站 JS
            // 最吃 CPU 的时候。本函数体里只剩一次调用，也不再出现 ticker 的
            // `LaunchedEffect` / `delay(`。
            // 判据：`PlayerLoadingHintPolicyTest.PlayerScreen 只许调用加载提示组件` 与
            // `PlayerFixTest.P7 ...`（两条都锚在调用形状上）。
            webFallbackUrl?.let { page ->
                PlayerLoadingHint(
                    page = page,
                    hasStream = streamUrl != null,
                    hasErrorMessage = errorMessage != null,
                    inPip = inPip,
                    clock = clock,
                )
            }

            // 手势层（必须压在视频之上、**后声明的浮层按钮之下**）：
            //  - 左半屏上下滑调亮度，右半屏上下滑调音量
            //  - 全屏时单击画面显示/隐藏浮层（直连与网页两条路径一致）
            //  - 网页线路只在**发生垂直拖动**时才消费事件，
            //    单击依旧透传给源站播放器（否则网页线路根本没法调亮度音量）
            //
            // ⚠️ "按钮之下"只对**在本层之后声明的**浮层成立（顶栏/底栏/选择面板都满足）。
            // 画在**更早的兄弟子树**里（例如原来在 `WebSurface` 内部）的按钮拿不到点击 ——
            // 命中测试先落进本层这棵 `fillMaxSize()` 的子树。
            // 这正是 P1 真机回归的根因，见下面「死胡同恢复条」那段。
            //
            // ⚠️ 判定全部收敛到 PlayerLock.gestureConfig（P2-9）：
            //    以前 enabled/tapOnly/onTap 的分流写死在这里的组合表达式里，
            //    PlayerLock 里的同名函数是死代码，测试测的是没人用的那份。
            //    现在生产代码与单测共用同一个判定源。
            run {
                val gesture = PlayerLock.gestureConfig(
                    locked = locked,
                    fullscreen = fullscreen,
                    inPip = inPip,
                )
                PlayerMediaGestures(
                    enabled = gesture.layerEnabled,
                    // 滑动只在"全屏且未锁定"时接管（P2-2 非全屏让页面能滚）
                    tapOnly = !gesture.dragEnabled,
                    // 锁定态消费单击（P1-6）：否则误触照旧透传给
                    // WebView 里的源站播放器，"防误触锁"只锁住了我们这层
                    consumeTap = gesture.consumeTap,
                    // 录像快进/快退（2026-11-25）：仅录像全屏未锁非 PIP 时接管水平拖动。
                    // 直播不传 onSeek（永不快进）；Slider 拖动 consume 事件时手势层自动让位。
                    durationMs = videoHandle.durationMs,
                    onSeek = if (streamUrl != null && SeekGesturePolicy.canSeek(
                            isVod = isVod,
                            dragEnabled = gesture.dragEnabled,
                            layerEnabled = gesture.layerEnabled,
                        )
                    ) { deltaMs ->
                        val landed = videoHandle.seekBy(deltaMs)
                        if (landed > 0L) {
                            seekHintMs = landed
                            seekHintSeq++
                        }
                    } else null,
                    modifier = Modifier.fillMaxSize(),
                    onTap = when (gesture.tapAction) {
                        PlayerLock.TapAction.TOGGLE_UNLOCK_UI ->
                            ({ unlockUiVisible = !unlockUiVisible })
                        PlayerLock.TapAction.TOGGLE_CONTROLS ->
                            ({ controlsVisible = !controlsVisible })
                        // 非全屏时浮层常驻，不处理单击（网页线路交给源站播放器）
                        PlayerLock.TapAction.PASS_THROUGH -> null
                    },
                )
            }

            // 录像快进/快退 OSD（2026-11-25）：居中显示 seek 后落点，1.2 秒自动收起。
            // 声明在手势层之后（同死胡同恢复条的理由），不拦截触摸。
            if (seekHintMs >= 0L) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(Radius.card))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = Space.lg, vertical = Space.sm),
                ) {
                    Text(
                        text = PlayerControls.formatPlaybackTime(seekHintMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
            }

            // ---------- 死胡同恢复条（P1 真机回归修复） ----------
            // ⚠️ **必须声明在手势层之后**（与顶栏/底栏同级）：
            // 手势层是 `Modifier.fillMaxSize()` 的兄弟，命中测试先于更早的兄弟子树；
            // 恢复条以前画在 `WebSurface` 内部（手势层之前的子树）→ 两个按钮点不动。
            // 判据：`PlayerFixTest.P1真机回归 恢复条必须渲染在手势层之后 否则按钮点不动`
            // （源码下标断言，搬回 WebSurface 内部就红）。
            // 只在网页线路还挂着、且还没升级成直连时渲染（与上面 `when` 的分支条件一致）。
            if (streamUrl == null && webFallbackUrl != null) {
                webDeadEndBar?.let { bar ->
                    PlayerDeadEndBar(
                        bar = bar,
                        onReload = {
                            // 先收起，再把"重新加载"当**计数式请求**交给 WebSurface ——
                            // 真正的导航逻辑（targetUrl / reloadSeq / pageErrorAt）只有它知道。
                            webDeadEndBar = null
                            webDeadEndReloadRequests++
                        },
                        onSwitchLine = {
                            webDeadEndBar = null
                            // 与旧实现同一个出口：换线路仍走上层的自动换线判据
                            //（WebPageDeadEndPolicy.shouldAutoAdvance 那条 9.5 秒兜底不变）
                            onPageDeadEnd()
                        },
                    )
                }
            }

            // 视频区顶部浮层：**只留实时时钟**。
            // 原来这里还画"直播中/未开赛"+联赛名（左上的 NBA 药丸），
            // 那两枚信息在播放页里没有决策价值，反而挡住画面左上角，
            // 用户明确要求去掉。
            // 网页全屏同样受 controlsVisible 控制（单击显示、10 秒后自动收起）：
            // 早先这里用 `|| webFullscreen` 硬编码常显，结果网页全屏时
            // 时钟和按钮一直压在源站画面上，用户要求改掉。
            // ⚠️ 锁定时浮层全部隐藏（防误触）：只留右侧的解锁按钮。
            if (match != null && !inPip && PlayerLock.showOverlays(locked) &&
                (!fullscreen || controlsVisible)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerClock()
                    // 画质：直连路径拿真实宽高（最准）。
                    // 网页路径只能从**嗅探到的 m3u8** 里抠 —— webFallbackUrl 是
                    // 源站的播放页地址（`?id=720` 这种），拿它解析会把线路 id
                    // 误当成画质，显示一个错误的档位。
                    PlayerQualityPill(
                        label = videoHandle.qualityLabel
                            ?: streamUrl?.let { VideoResolution.fromStreamUrl(it) },
                    )
                }
            }

            // 右上角：画中画 / 全屏（画中画窗口内不再显示按钮）
            // 网页线路全屏时保留：这两个是"逃生按钮"，在右上角，
            // 不与源站播放器底部的控制条冲突。
            // 网页全屏同样受 controlsVisible 控制（单击显示、10 秒后自动收起）。
            // ⚠️ 锁定时隐藏：防误触，锁住期间不该能误触画中画/全屏
            if (!inPip && PlayerLock.showOverlays(locked) &&
                (!fullscreen || controlsVisible)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    VideoControlButton(
                        icon = AppIcons.filled_PictureInPictureAlt,
                        description = "画中画",
                        onClick = {
                            touchControls()
                            // ⚠️ 手动点画中画也要判"有没有流可播"（review/01-player.md P2-6）：
                            // 「按 Home 自动进小窗」那条路早有 shouldAutoEnterPip 守卫，
                            // 手动按钮这条路原来直接调 onEnterPip —— 解析中/全失败时
                            // 点出来是一块转圈或黑掉的小窗。
                            // 两条路径用**同一条**判据（ManualPipPolicy 内部委托它），
                            // 但手动必须给提示，否则用户以为按钮坏了。
                            when (val decision = ManualPipPolicy.decide(
                                isPlayerRoute = true,
                                hasStream = streamUrl != null || webFallbackUrl != null,
                            )) {
                                ManualPipDecision.Allow -> onEnterPip()
                                is ManualPipDecision.Blocked ->
                                    // 提示走界面已有的 snackbar 通道（由 errorMessage 驱动）
                                    onPipBlocked(decision.message)
                            }
                        },
                    )
                    VideoControlButton(
                        icon = if (fullscreen) AppIcons.filled_FullscreenExit else AppIcons.filled_Fullscreen,
                        description = if (fullscreen) "退出全屏" else "全屏",
                        onClick = {
                            touchControls()
                            onToggleFullscreen()
                        },
                    )
                }
            }

            // ---------- 锁定按钮：右侧中间 ----------
            // ⚠️ 位置选右侧中间而不是底部控制栏：
            //  1) 底部控制栏只在全屏时出现，非全屏时没地方放
            //  2) 锁定后底部控制栏要隐藏，按钮会跟着消失 → 用户没法解锁
            //
            // 右侧滑出的面板（同时在播 / 源·线路）会盖住这个位置，
            // 面板打开时把它藏起来，面板自己的关闭按钮承担退出。
            //
            // 显示规则：
            //  - 未锁定：跟随浮层（全屏直连下浮层默认收起，单击才出现）
            //  - 锁定：**默认不显示**，单击画面唤出，3 秒后自动收起。
            //    锁定后画面完全干净，这是"防误触"该有的样子；
            //    但单击必须能唤出它，否则用户找不到解锁入口。
            if (!inPip && picker == null && !showLivePanel &&
                PlayerLock.showLockButton(locked, unlockUiVisible) &&
                (locked || !fullscreen || controlsVisible)
            ) {
                VideoControlButton(
                    icon = if (locked) Icons.Filled.Lock else AppIcons.filled_LockOpen,
                    description = if (locked) "解锁" else "锁定（防误触）",
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(Space.md),
                    highlighted = locked,
                )
            }

            // ---------- 锁定提示 ----------
            // 只在单击唤出解锁 UI 时跟着出现，告诉用户"现在是锁着的、点右边那个锁"。
            // 锁定时常驻的话，画面顶部永远挂一条黑条，与"锁定让画面干净"自相矛盾。
            //
            // ⚠️ 放在**顶部居中**而不是画面中央：
            // 画面正中央经常已经有"该线路暂无信号"之类的提示，
            // 两者叠在一起会糊成一团（实机截图确认过）。
            // 顶部居中那块是空的（时钟在左上、按钮在右上），不会撞车。
            if (!inPip && PlayerLock.showLockHint(locked, unlockUiVisible)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Space.md),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.chip))
                            .background(Color.Black.copy(alpha = 0.68f))
                            .padding(horizontal = Space.md, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "已锁定 · 点右侧锁按钮解锁",
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // 缓冲指示：原生控制条已关闭，自己给一个转圈
            if (streamUrl != null && videoHandle.isBuffering && !inPip) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                )
            }

            // 底部自定义控制栏：只在**全屏播放**时显示。
            // 布局参考 yingshi 播放器：左侧圆形半透明图标按钮（播放/重连），
            // 右侧白描边功能胶囊（画面/源/线路/同时在播），一眼能读、按得准。
            // 关键：不再使用 Media3 原生控制条 —— 它自带快进/后退/进度条，
            // 直播流上误触会跳离直播边缘，而且不支持"重连/换源"这类直播操作。
            //
            // ⚠️ **直连与网页兜底都要画这条栏**。
            // 早先按 `streamUrl == null`（webFullscreen）把网页线路整条排除，
            // 理由有两条，现在都不成立：
            //  1) "源站 H5 播放器自带底部控制条，我们的栏盖上去就没法用了" ——
            //     实测源站控制条在 WebView 内部，我们的 Compose 浮层画在它**之上**，
            //     两条各在各的层，源站那条仍然可以点；我们的按钮用的是半透明渐变底，
            //     不是实色块。真正损失的只是视觉位置重叠。
            //  2) "网页路径没法接管点击把浮层收起来" ——
            //     v1.2.8 起两条路径都支持「单击显隐 + 10 秒无操作自动收起」。
            // 而排除它的代价是实的：用户在网页线路里**完全无法操作**
            // （换源/换线路/重连/退出全屏全都没有入口）。
            // 录像全屏进度条：放在底部栏**之上**的独立浮层，不改动底部栏结构。
            // 直播 isVod=false → 判据恒 false，连组合都不进入（用户明确要求直播无进度条）。
            if (PlayerControls.shouldShowSeekBar(
                    isVod = isVod,
                    streamUrl = streamUrl,
                    fullscreen = fullscreen,
                    inPip = inPip,
                ) && controlsVisible && !locked
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg)
                        .padding(bottom = 76.dp)
                        .clip(RoundedCornerShape(Radius.btn))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = Space.md, vertical = Space.xs),
                ) {
                    VodSeekBar(handle = videoHandle, onInteract = { touchControls() })
                }
            }
            // 规则收敛到 [PlayerControls.shouldShowBottomBar]（可单测）。
            if (PlayerControls.shouldShowBottomBar(
                    fullscreen = fullscreen,
                    inPip = inPip,
                    controlsVisible = controlsVisible,
                    locked = locked,
                    streamUrl = streamUrl,
                    webFallbackUrl = webFallbackUrl,
                )
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                            ),
                        )
                        .padding(horizontal = Space.lg, vertical = Space.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左：播放控制
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            // 播放 / 暂停：仅直连 ExoPlayer 可控（网页线路由源站页面自己管）
                            if (streamUrl != null) {
                                PlayerIconButton(
                                    icon = if (videoHandle.isPlaying) AppIcons.filled_Pause
                                    else Icons.Filled.PlayArrow,
                                    description = if (videoHandle.isPlaying) "暂停" else "播放",
                                    onClick = {
                                        touchControls()
                                        videoHandle.togglePlay()
                                    },
                                )
                            }
                            PlayerIconButton(
                                icon = Icons.Filled.Refresh,
                                description = "重连",
                                onClick = {
                                    touchControls()
                                    onReconnect()
                                },
                            )
                        }
                        // 右：功能胶囊
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 画面自适应：三档循环
                            // ⚠️ 网页兜底路径**不渲染**这个胶囊（review/01-P2-11）：
                            // 网页路径的画面缩放由源站 H5 播放器决定，我们改不了，
                            // 三档切下去画面毫无变化，却会被 PlayerPrefs 持久化，
                            // 之后进直连线路才突然生效 —— 用户会觉得"按钮有时灵有时不灵"。
                            // 判据在 PlayerControls.shouldShowAspectChip（有单测），
                            // 早先写好了却零调用点，等于没修。
                            if (PlayerControls.shouldShowAspectChip(streamUrl)) {
                                PlayerChip(
                                    label = "画面 ${aspectMode.label}",
                                    actionLabel = "切换画面比例",
                                    onClick = {
                                        touchControls()
                                        val next = AspectMode.entries[
                                            (aspectMode.ordinal + 1) % AspectMode.entries.size,
                                        ]
                                        onAspectChange(next)
                                    },
                                )
                            }
                            if (signals.isNotEmpty()) {
                                // 刷新线路后线路数会变，selectedIndex 可能越界 -> 显示"线路 5/3"
                                val safeIndex = selectedIndex.coerceIn(0, signals.lastIndex)
                                PlayerChip(
                                    label = "源 ${signals.getOrNull(safeIndex)?.provider?.displayName ?: "-"}",
                                    actionLabel = "切换播放源",
                                    onClick = {
                                        touchControls()
                                        picker = PickerKind.SOURCE
                                    },
                                )
                                PlayerChip(
                                    label = "线路 ${safeIndex + 1}/${signals.size}",
                                    actionLabel = "切换线路",
                                    onClick = {
                                        touchControls()
                                        picker = PickerKind.LINE
                                    },
                                )
                            }
                            PlayerChip(
                                label = "同时在播 ${liveMatches.count { it.id != match?.id }}",
                                actionLabel = "打开同时在播面板",
                                onClick = {
                                    touchControls()
                                    showLivePanel = true
                                },
                            )
                        }
                    }
                }
            }
        }

        // 全屏 / 画中画下只留视频，其余内容不参与渲染
        if (!videoOnly) {

        // ---------- 赛事信息卡 ----------
        // P8（review/optimization-review.md 第 8 条）：本函数原来约 990 行、
        // 全屏/网页/面板/提示/选择器全在一个函数体里，任何改动都要在这个体量里重新定位副作用。
        // 现在按"视频区 / 浮层 / 面板 / 侧栏"拆出独立的 private composable，
        // **行为不变**（纯搬运），并把这些块的重组面收在它们自己里面。
        if (match != null) {
            PlayerMatchCard(match = match, signals = signals)
        }

        // ---------- 信号切换 ----------
        // P8：拆成 PlayerSignalStrip（纯搬运）
        if (signals.isNotEmpty()) {
            PlayerSignalStrip(
                signals = signals,
                selectedIndex = selectedIndex,
                onSignalSelected = onSignalSelected,
            )
        }

        // ---------- 故障切换 / 错误提示 + 疑似无信号提示 ----------
        // P8：拆成 PlayerNoticeCards（纯搬运；"有确认错误时错误卡优先、两张卡不叠加"
        // 的优先级判据不变，见它内部的 `if (suspectHint && errorMessage == null)`）。
        PlayerNoticeCards(errorMessage = errorMessage, suspectHint = suspectHint)

        // ---------- 其他直播 ----------
        // P8：拆成 PlayerOtherLiveSection；过滤/裁剪在它内部 remember（P7）
        PlayerOtherLiveSection(
            otherMatches = otherMatches,
            currentMatch = match,
            onOtherMatchClick = onOtherMatchClick,
        )
        } // end if (!videoOnly)
    } // end Column

    // ---------- 同时在播：右侧滑出面板 ----------
    // 全屏/画中画下也可用：换场次不必先退出全屏
    if (!inPip) {
        // 遮罩
        AnimatedVisibility(
            visible = showLivePanel,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        onClickLabel = "关闭面板",
                        role = Role.Button,
                    ) { showLivePanel = false },
            )
        }
        AnimatedVisibility(
            visible = showLivePanel,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            LiveNowPanel(
                matches = liveMatches,
                currentMatchId = match?.id,
                refreshing = refreshing,
                onRefresh = onRefreshLive,
                onSelect = {
                    showLivePanel = false
                    onLiveMatchClick(it)
                },
                onClose = { showLivePanel = false },
            )
        }
    }

    // ---------- 播放源 / 播放线路 选择面板 ----------
    // 参考 yingshi 播放器：右侧竖排浮层。
    // 不用 DropdownMenu —— 它是独立 Popup 窗口，会被 PlayerView 的
    // SurfaceView 图层盖住（实测点不动也看不见）。
    // P8：整个面板拆成 BoxScope.PlayerPickerPanel（纯搬运）；
    // ⚠️ `val kind = picker` 保留：`picker` 是委托属性（rememberSaveable），
    // 编译器不会对它做智能转换，拆出去之后这里必须仍是**普通局部 val**。
    val kind = picker
    if (kind != null) {
        PlayerPickerPanel(
            kind = kind,
            signals = signals,
            selectedIndex = selectedIndex,
            onSignalSelected = onSignalSelected,
            onClose = { picker = null },
        )
    }
    } // end Box
}

/** 选择面板类型 */
private enum class PickerKind { SOURCE, LINE }

/**
 * 赛事信息卡（P8 拆出来的第一块：原 `PlayerScreen` 体内的 `// ---------- 赛事信息卡 ----------`）。
 *
 * 行为与拆分前逐字一致 —— 这块只依赖 `match` 与 `signals`、没有任何副作用，
 * 是最安全的抽取对象。
 */
@Composable
private fun PlayerMatchCard(match: Match, signals: List<StreamSource>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Space.lg)
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(match.homeLogo, match.homeTeam, size = 44.dp)
        Spacer(Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${match.homeTeam} VS ${match.awayTeam}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(match.leagueLabel())
                    append(" · ")
                    append(match.hhmm())
                    if (signals.isNotEmpty()) {
                        append(" · 共 ${signals.size} 条信号")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Space.sm))
        TeamLogo(match.awayLogo, match.awayTeam, size = 44.dp)
    }
}

/** 信号切换区（P8 拆出来的第二块）：标题行 + 横向线路列表，行为逐字不变。 */
@Composable
private fun PlayerSignalStrip(
    signals: List<StreamSource>,
    selectedIndex: Int,
    onSignalSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .padding(bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "信号切换",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = "共 ${signals.size} 条线路",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "当前 ${signals.getOrNull(selectedIndex)?.label ?: "-"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }

    // 线路可能多达 9 条：横向滚动、每条固定宽度，
    // 不再用 weight(1f) 把标签挤成"线…"
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        itemsIndexed(signals, key = { _, s -> s.id }) { index, source ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(Radius.btn))
                    .then(
                        if (active) Modifier.background(accentBrush())
                        else Modifier.background(Color.Transparent),
                    )
                    .clickable(
                        onClickLabel = "切换线路",
                        role = Role.Button,
                    ) { onSignalSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = source.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) Color.White
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = source.provider.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 错误卡 + 疑似无信号卡（P8 拆出来的第三块）。
 *
 * ⚠️ 两张卡的优先级判据必须留在这里、并且保持逐字形状：
 * `if (suspectHint && errorMessage == null)` ——
 * `review/01-player.md:41`、`PlayerHealthWiringTest` 都锚在这个条件上
 * （有确认错误时错误卡优先，两张卡不叠加；嗅探超时只是**推断**，绝不收窗/关任务）。
 */
@Composable
private fun PlayerNoticeCards(errorMessage: String?, suspectHint: Boolean) {
    // ---------- 故障切换 / 错误提示 ----------
    if (errorMessage != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(top = Space.md)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                    RoundedCornerShape(Radius.card),
                )
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // ---------- 疑似无信号（推断）提示 ----------
    // ⚠️ 有意与错误卡分开（P0-1）：嗅探超时只是**推断** —— 既不能收小窗、
    // 也不能关任务，但**必须让用户看见**。否则现象是"页面加载完、15 秒过去，
    // 界面毫无反应"，用户只能干等（review/01-player.md:41 明确要求"用于显示提示"；
    // 旧代码只实现了后半"停自动进小窗"，提示从未接线 = 审计 B2 的假安全感）。
    // 有确认错误（errorMessage）时错误卡优先，两张卡不叠加。
    if (suspectHint && errorMessage == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(top = Space.md)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
                    RoundedCornerShape(Radius.card),
                )
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = "疑似无信号，可换线路试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 网页兜底加载期间的非阻塞提示（P7 完整版，2026-11-01）。
 *
 * ## 为什么要独立成一个 composable（而不是留在 `fun PlayerScreen(` 里）
 *
 * Compose 的失效范围是**读取状态的那个 restart 作用域**。原来 ticker 每 250ms 写一次
 * `sinceLoadMs`，而读取点就在同一块 content lambda 里 → **整块 lambda**
 *（含"其他直播"的 `filter` + `take(4)` 的列表分配）每 250ms 失效一次。
 * 网页加载的 0.1~27 秒恰恰是 chromium 与源站播放器 JS 最吃 CPU/带宽的时候，
 * 主线程重组会和 WebView 抢帧（review/opt/player.md 第 7 条）。
 *
 * 搬进来之后：
 *  - ticker（`LaunchedEffect` + `delay(250)`）与 `sinceLoadMs` 都只活在本 composable 内，
 *    写入只让**本 composable**失效；
 *  - 判据仍下沉在 `derivedStateOf`：250ms 的写入只让那个布尔变脏，
 *    且只在"该显示 / 不该显示"**翻转**时才真正通知读取方；
 *  - 20 秒上限、出流/报错/小窗不显示 —— 全部仍由 [PlayerLoadingHintPolicy.shouldShow]
 *    决定，**语义一个字没改**（判据见 `PlayerLoadingHintPolicyTest`）。
 *
 * ⚠️ 必须是 `BoxScope` 扩展：提示贴在视频区左上角（`Modifier.align(TopStart)`），
 * 而 `align` 只在 BoxScope 里可见。
 */
@Composable
private fun BoxScope.PlayerLoadingHint(
    /** 网页兜底页地址：同时作为 ticker / 计时的重启键（换线路要重新计时） */
    page: String,
    hasStream: Boolean,
    hasErrorMessage: Boolean,
    inPip: Boolean,
    clock: PlayerClock,
) {
    var sinceLoadMs by remember(page) { mutableLongStateOf(0L) }
    LaunchedEffect(page) {
        val startedAt = clock.nowMs()
        while (sinceLoadMs < PlayerLoadingHintPolicy.MAX_HINT_MS) {
            sinceLoadMs = clock.nowMs() - startedAt
            kotlinx.coroutines.delay(250)
        }
    }
    // 判据下沉到 derivedStateOf：250ms 的写入只让**这个布尔**失效，
    // 而它只在"该显示 / 不该显示"翻转时才真正通知读取方。
    val show by remember(page, hasStream, hasErrorMessage, inPip) {
        derivedStateOf {
            PlayerLoadingHintPolicy.shouldShow(
                hasWebFallback = true,
                hasStream = hasStream,
                sinceLoadMs = sinceLoadMs,
                hasErrorMessage = hasErrorMessage,
                inPip = inPip,
            )
        }
    }
    if (show) {
        Text(
            text = "正在连接源站播放页…",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(Space.sm),
        )
    }
}

/**
 * 死胡同恢复条要显示的内容（P1 真机回归修复，2026-11-01）。
 *
 * ⚠️ 文案的**唯一来源仍是 [WebPageDeadEndPolicy]**，由 `WebSurface`（探测到页面进入
 * 错误态的那一处）产出后上抛给浮层区渲染 —— 两处都不许各自另写一份字面量。
 * 之所以上抛而不是让浮层区自己调 policy：探测与判定都在 `WebSurface` 里，
 * 文案跟着判定走才不会出现"探测改了、文案没跟着改"。
 */
private data class WebDeadEndBar(
    val note: String,
    val reloadLabel: String,
    val switchLabel: String,
)

/**
 * 死胡同恢复条（P1 真机回归修复：从 `WebSurface` 内部提到浮层区渲染）。
 *
 * ## 为什么必须挪出来（真机实测，2026-11-01 雷电模拟器 / Android 14）
 *
 * 源站播放器报错后恢复条画得出来，但两个按钮**点不动**：
 * 连点三次（含 uiautomator 精确 bounds）没有任何状态变化、没有一行日志，
 * 而同一时刻 `KEYCODE_BACK` 正常、CDP 里 URL 6 秒没变（导航根本没发生）。
 *
 * 根因是**声明位置**：恢复条原来画在 `WebSurface` 内部的 `Box` 里，
 * 而手势层 `PlayerMediaGestures(modifier = Modifier.fillMaxSize())` 是视频区 `Box` 的
 * **后置兄弟** —— 命中测试先落进手势层那棵 `fillMaxSize()` 的子树，
 * 更早的兄弟子树（`WebSurface`，恢复条在其中）拿不到这次点击。
 *
 * ⚠️ 顺带纠正一条注释误解：手势层并**没有**"消费"点击 ——
 * `PlayerMediaGestures` 不用 `detectTapGestures`，且在未锁定 + 非全屏时
 * `PlayerLock.gestureConfig(...)` 给的是 `tapAction = PASS_THROUGH`（`onTap = null`）、
 * `consumeTap = false`，它一个事件都不消费。**问题出在命中范围，不是消费**。
 *
 * @param bar 文案（来源见 [WebDeadEndBar]）
 * @param onReload 用户点了「重新加载」
 * @param onSwitchLine 用户点了「换线路」
 */
@Composable
private fun BoxScope.PlayerDeadEndBar(
    bar: WebDeadEndBar,
    onReload: () -> Unit,
    onSwitchLine: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.card))
                .background(Color.Black.copy(alpha = 0.86f))
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = bar.note,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
            // 两个胶囊的标签本身就是动作词（「重新加载」「换线路」），
            // 所以 PlayerChip 的 actionLabel 用默认值（= label）即可。
            PlayerChip(label = bar.reloadLabel, onClick = onReload)
            PlayerChip(label = bar.switchLabel, onClick = onSwitchLine)
        }
    }
}

/**
 * 「其他直播」区（P8 拆出来的第四块）。
 *
 * P7：过滤 + `take(4)` 的结果用 `remember` 缓存 —— 键是**数据来源**
 * （`otherMatches` 与当前场次），所以播放状态每翻转一次不再重新分配一个列表。
 */
@Composable
private fun PlayerOtherLiveSection(
    otherMatches: List<Match>,
    currentMatch: Match?,
    onOtherMatchClick: (Match) -> Unit,
) {
    // 只列真正在直播的场次：otherMatches 是"全部赛事"，
    // 直接拿来用会把未开赛/已结束的比赛摆进"其他直播"里
    val others = remember(otherMatches, currentMatch) {
        otherMatches
            .filter { it.id != currentMatch?.id && it.status == MatchStatus.LIVE }
            .take(4)
    }
    if (others.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(top = Space.xxl, bottom = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "其他直播",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = "今日赛事",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            others.forEach { other ->
                OtherMatchRow(match = other, onClick = { onOtherMatchClick(other) })
            }
        }
    }
}

/**
 * 播放源 / 播放线路选择面板（P8 拆出来的第五块）。
 *
 * ⚠️ 必须是 `BoxScope` 的**扩展函数**：面板本体靠
 * `Modifier.align(Alignment.CenterEnd)` 定位，而 `align` 只在 BoxScope 里可见 ——
 * 普通 private composable 拿不到它（且不能让调用方把它挪出那个 Box）。
 *
 * P7：`currentProvider` / `providers` 用 `remember` 缓存，面板打开期间不再每次重组重新分配。
 */
@Composable
private fun BoxScope.PlayerPickerPanel(
    kind: PickerKind,
    signals: List<StreamSource>,
    selectedIndex: Int,
    onSignalSelected: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                onClickLabel = "关闭面板",
                role = Role.Button,
                onClick = onClose,
            ),
    )
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(0.36f)
            .widthIn(max = 300.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .padding(horizontal = Space.md, vertical = Space.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (kind == PickerKind.SOURCE) "播放源" else "播放线路",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    // ⚠️ 图标只有 16dp，但**可点区域要 48dp**：
                    // 直接 .size(28.dp) 的话触摸目标远低于无障碍下限，
                    // 看球时单手点关闭很容易点不中。
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = "关闭",
                        role = Role.Button,
                        onClick = onClose,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Space.xs))
        // P7：派生值用 remember（键 = 数据来源），避免每次重组重新 map/distinct
        val currentProvider = remember(signals, selectedIndex) {
            signals.getOrNull(selectedIndex)?.provider
        }
        val providers = remember(signals) { signals.map { it.provider }.distinct() }
        if (kind == PickerKind.SOURCE) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(providers, key = { it.name }) { p ->
                    PickerRow(
                        label = p.displayName,
                        sub = "${signals.count { it.provider == p }} 条线路",
                        selected = p == currentProvider,
                        onClick = {
                            onClose()
                            val idx = signals.indexOfFirst { it.provider == p }
                            if (idx >= 0) onSignalSelected(idx)
                        },
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(signals, key = { _, s -> s.id }) { index, s ->
                    PickerRow(
                        label = s.label,
                        sub = "源站 ${s.provider.displayName}",
                        selected = index == selectedIndex,
                        onClick = {
                            onClose()
                            onSignalSelected(index)
                        },
                    )
                }
            }
        }
    }
}

/** 选择面板的一行：标题 + 副标题 + 选中勾（选中行整行高亮） */
@Composable
private fun PickerRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Radius.btn))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent,
            )
            .clickable(
                onClickLabel = "切换线路",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "同时在播"面板：右侧竖排列表，上下滑动选择要看的场次。
 * 只展示状态为直播中的场次，点一下直接切过去播。
 */
@Composable
private fun LiveNowPanel(
    matches: List<Match>,
    currentMatchId: String?,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelect: (Match) -> Unit,
    onClose: () -> Unit,
) {
    val others = matches.filter { it.id != currentMatchId }
    Column(
        modifier = Modifier
            // ⚠️ 宽度只保留"屏幕的一半"，不再叠加 widthIn(max=320.dp)：
            // 两者同时用时 fillMaxWidth(0.78f) 会先把宽度定成 499dp（横屏 640dp 的 78%），
            // 后接的 widthIn 只约束"上限"，在尺寸已确定后不会回缩 ——
            // 实测面板占了 450dp（约 70% 屏宽），把直播画面压得只剩左侧一条。
            // 横屏看球时面板最多占一半，画面留一半才看得清。
            .fillMaxHeight()
            .fillMaxWidth(0.5f)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = Space.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.filled_LiveTv,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = "同时在播",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // 手动刷新：看球期间不自动刷，用户点这个按钮才拉最新列表
            Box(
                modifier = Modifier
                    // ⚠️ 图标 20dp，但可点区域给到 44dp（接近无障碍 48dp 下限）：
                    // 直接 .size(30.dp) 的话看球时单手点刷新很容易点不中。
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = !refreshing,
                        onClickLabel = "刷新同时在播列表",
                        role = Role.Button,
                        onClick = onRefresh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 旋转动画只在真正刷新时存在：
                // rememberInfiniteTransition 会一直按帧取值，
                // 放在 if 外面等于面板一打开就 60fps 重组、白耗电
                if (refreshing) {
                    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = LinearEasing),
                        ),
                        label = "refreshSpin",
                    )
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新同时在播列表",
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(spin),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新同时在播列表",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Space.sm))
            Box(
                modifier = Modifier
                    // 同上：图标 16dp，可点区域 48dp
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = "关闭",
                        role = Role.Button,
                        onClick = onClose,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            text = when {
                refreshing -> "刷新中…"
                others.isEmpty() -> "暂无其他正在直播的场次"
                else -> "共 ${others.size} 场正在直播"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.lg),
        )
        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.md,
                vertical = Space.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(others, key = { it.id }) { m ->
                LiveNowRow(match = m, onClick = { onSelect(m) })
            }
        }
    }
}

@Composable
private fun LiveNowRow(match: Match, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                onClickLabel = CardSemantics.ACTION_LABEL,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(status = match.status, isPostponed = match.isPostponed)
            Spacer(Modifier.width(Space.xs))
            LeagueChip(
                text = match.leagueLabel(),
                league = match.league,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = match.hhmm(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(match.homeLogo, match.homeTeam, size = 26.dp)
            Spacer(Modifier.width(Space.xs))
            Text(
                text = match.homeTeam,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "vs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = match.awayTeam,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Spacer(Modifier.width(Space.xs))
            TeamLogo(match.awayLogo, match.awayTeam, size = 26.dp)
        }
    }
}

/** 视频区浮层小按钮：半透明圆形，保证在任何画面上都看得清 */
@Composable
private fun VideoControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前处于激活态时加深底色（锁定按钮锁定后用） */
    highlighted: Boolean = false,
) {
    // 触控区 48dp（无障碍最小值），视觉圆 34dp：
    // 外层负责点击，内层负责画圆，两者分开才不会把圆撑大。
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (highlighted) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    } else {
                        Color.Black.copy(alpha = 0.45f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(19.dp),
                tint = Color.White,
            )
        }
    }
}

/**
 * 播放器句柄：把 ExoPlayer 的播放状态暴露给外层自定义控制栏。
 * 用 Compose 快照状态，播放器回调改值后控制栏自动重组。
 */
private class VideoPlayerHandle {
    var player: Player? by mutableStateOf(null)
    var isPlaying: Boolean by mutableStateOf(false)
    var isBuffering: Boolean by mutableStateOf(false)

    /** VOD 进度：由 VideoSurface 的轮询循环喂值，仅供录像进度条读取。 */
    var positionMs: Long by mutableLongStateOf(0L)
    var durationMs: Long by mutableLongStateOf(0L)

    /** 拖动进度条后跳转；直播路径永远不调用它。 */
    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0L))
    }

    /**
     * 快进/快退：把有符号增量叠加到当前位置，夹到 [0, durationMs]（2026-11-25 用户需求）。
     * 时长未知（<=0）时不 seek，避免直播/未就绪跳变。返回实际落点，供 OSD 显示。
     */
    fun seekBy(deltaMs: Long): Long {
        val p = player ?: return 0L
        val duration = p.duration.takeIf { it > 0L } ?: return 0L
        val target = SeekGesturePolicy.applySeek(p.currentPosition, deltaMs, duration)
        p.seekTo(target)
        positionMs = target
        return target
    }

    /**
     * 当前画质标签（如 "1080P"）。
     *
     * 走真实视频宽高（`onVideoSizeChanged`）—— 这是唯一准确的来源。
     * WebView 承载路径拿不到宽高，由调用方用 m3u8 地址兜底。
     */
    var qualityLabel: String? by mutableStateOf(null)

    fun attach(p: Player) {
        player = p
        isPlaying = p.isPlaying
        isBuffering = p.playbackState == Player.STATE_BUFFERING
    }

    fun detach() {
        player = null
        isPlaying = false
        isBuffering = false
        qualityLabel = null
        positionMs = 0L
        durationMs = 0L
    }

    /** 播放器上报真实视频尺寸 -> 画质档位 */
    fun onVideoSize(width: Int, height: Int) {
        qualityLabel = VideoResolution.labelOf(width, height)
    }

    /**
     * 换线路时清掉画质标签（P9 完整版）。
     *
     * ⚠️ 播放器被复用后，`onVideoSizeChanged` 要等**新线路首帧**到达才会再报一次；
     * 中间这段时间若继续显示旧线路的分辨率，界面就是在说谎。
     * 所以换媒体项之前先清空，由新线的首帧重新填。
     */
    fun clearQuality() {
        qualityLabel = null
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }
}

/**
 * 录像全屏进度条：当前时间 + 可拖拽 Slider + 总时长。
 *
 * 拖动期间用本地 [dragMs] 显示手指位置，松手时才真正 `seekTo` 一次 ——
 * 避免每帧都把 seek 指令压给 ExoPlayer（尤其 HLS 会触发多次分片请求）。
 */
@Composable
private fun VodSeekBar(handle: VideoPlayerHandle, onInteract: () -> Unit) {
    val duration = handle.durationMs.coerceAtLeast(0L)
    val upper = duration.coerceAtLeast(1L).toFloat()
    var dragMs by remember { mutableLongStateOf(-1L) }
    val shownMs = if (dragMs >= 0L) dragMs else handle.positionMs.coerceIn(0L, duration)
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = PlayerControls.formatPlaybackTime(shownMs),
            style = textStyle,
            maxLines = 1,
        )
        Slider(
            value = shownMs.toFloat().coerceIn(0f, upper),
            valueRange = 0f..upper,
            onValueChange = { value ->
                dragMs = value.toLong()
                onInteract()
            },
            onValueChangeFinished = {
                if (dragMs >= 0L) handle.seekTo(dragMs)
                dragMs = -1L
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.sm),
        )
        Text(
            text = PlayerControls.formatPlaybackTime(duration),
            style = textStyle,
            maxLines = 1,
        )
    }
}

/**
 * 圆形半透明图标按钮。
 * 参考 yingshi 播放器：40dp 圆、白色 10% 底、20dp 白色图标，
 * 压在任意画面上都看得清，也不会像"图标+小字"那样把字挤成省略号。
 */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    // 触控区 48dp（无障碍最小值），视觉圆 40dp：
    // 外层负责点击，内层负责画圆，两者分开才不会把圆撑大。
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 功能胶囊：白描边 + 白字。
 * 参考 yingshi 播放器的 Chip —— 文字本身就是标签，
 * 不用图标，读起来更快（"线路 2/3" 比一个列表图标直观）。
 */
@Composable
private fun PlayerChip(
    label: String,
    onClick: () -> Unit,
    /**
     * 读屏动作词（`Modifier.clickable(onClickLabel = …)`）。
     *
     * ⚠️ 默认用 [label]：多数胶囊的标签本身就是动作（如「重新加载」「换线路」），
     * 但**名词式**的胶囊（「画面 FIT」「源 直播吧」「线路 2/3」「同时在播 3」）
     * 必须由调用方给一句准确的动作词，否则 TalkBack 只会把名词重念一遍
     * （review/03-ui.md P2-25 的同款口径：动作词要说明"双击会发生什么"）。
     */
    actionLabel: String = label,
) {
    // ⚠️ 必须用 Box 包一层做居中，不能把 Modifier 直接挂在 Text 上：
    // `.heightIn(min = 40.dp)` 让胶囊至少 40dp 高，而 Text 按自身行高
    // 贴在容器顶部 —— 文字会明显偏上（实测：11sp 文字在 40dp 胶囊里
    // 上边只剩 2dp、下边空 20dp），用户反馈"按钮文字没居中"。
    // Box 的 contentAlignment = Center 让文字在两个方向都真正居中。
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.btn))
            .border(1.dp, Color.White.copy(alpha = 0.26f), RoundedCornerShape(Radius.btn))
            .clickable(
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onClick,
            )
            // ⚠️ 触控区要够：只有文字 + 6dp 上下内边距时整个胶囊才 ~25dp 高，
            // 远低于 48dp 的无障碍下限，看球时点"换线路"容易点不中。
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OtherMatchRow(match: Match, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                onClickLabel = CardSemantics.ACTION_LABEL,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeagueChip(
            text = match.leagueLabel(),
            league = match.league,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = match.hhmm(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        TeamLogo(match.homeLogo, match.homeTeam, size = 24.dp)
        Spacer(Modifier.width(Space.xs))
        Text(
            text = match.homeTeam,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // ⚠️ 必须有 weight：没有它两个队名 Text 会按内容宽度抢占空间，
            // 把 "vs" 和客队挤出可视区（overflow=Ellipsis 形同虚设）。
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            text = "vs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            text = match.awayTeam,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Space.xs))
        TeamLogo(match.awayLogo, match.awayTeam, size = 24.dp)
    }
}

/**
 * Media3 ExoPlayer 直连 HLS。
 *
 * 数据源复用应用共享的 OkHttpClient：连接池与 DNS 缓存已由前面的解析请求预热，
 * 首帧明显更快，也避免 HttpURLConnection 在弱网下频繁超时。
 *
 * ## P9 完整版：**播放器跨线路复用**（2026-11-01）
 *
 * 早先调用方用 `key(streamUrl, referer)` 包住本组件：Referer 烧在数据源工厂里，
 * 换线路必须整体重建播放器，否则"换了线路、CDN 还拿旧 Referer → 403"。
 * 现在两件事都变了：
 *  - [RefererDataSourceFactory] 在**每次 `createDataSource()`** 时现查 Referer
 *    （`rememberUpdatedState(referer)`），换线路不必重建；
 *  - 换线只做**一次** `setMediaItem + prepare + playWhenReady`，判据与"一次性"
 *    守卫都在 [LineSwitchPolicy]（纯函数，有单测）。
 *
 * 于是换线延迟与黑帧不再包含"重新初始化解码器/渲染器/缓冲"这段。
 * ⚠️ 代价是**所有随线路变化的量都必须显式管理**：`reachedReady`（按 streamUrl remember）、
 * 两个看门狗（按 streamUrl 重启）、监听器里的 streamUrl（读 rememberUpdatedState）。
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun VideoSurface(
    streamUrl: String,
    referer: String?,
    aspectMode: AspectMode,
    handle: VideoPlayerHandle,
    onError: () -> Unit,
    /** 「用户要求重连」的意图序号；根因见 [com.nbalive.app.ui.PlayerUiState.reconnectNonce] */
    reconnectNonce: Long = 0L,
    /** 录像续播起始位置（毫秒）；0 = 从头播。 */
    initialSeekMs: Long = 0L,
    /** 录像位置上报（节流后调用；VM 写历史）。 */
    onReplayProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    // 每条地址只上报一次错误 —— 但**必须按"轮"记**：重连时地址常常逐字不变
    // （嗅探缓存命中），若集合不随重连清零，重连后的第二次失败会被静默吞掉，
    // 用户看到的是"点了刷新以后连自动换线都不发生了"。
    val reportedRef = remember(streamUrl, reconnectNonce) { mutableSetOf<String>() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val onErrorState = rememberUpdatedState(onError)
    // ⚠️ 监听器只在工厂里注册**一次**（播放器复用），所以它必须读**当前**的地址：
    // 直接捕获 streamUrl 的话，错误去重会一直拿第一条线路的地址去判重。
    val currentStreamUrl = rememberUpdatedState(streamUrl)
    // 数据源工厂同样只建一次，Referer 必须读当前值（P9 的核心）。
    val currentReferer = rememberUpdatedState(referer)
    // 已经**应用到播放器上**的媒体地址（换线"一次性"的守卫，判据见 LineSwitchPolicy）。
    var appliedUrl by remember { mutableStateOf<String?>(null) }
    // 已经消费掉的**重连意图**序号。
    // ⚠️ 为什么不能只靠 appliedUrl：重连时地址往往一模一样（嗅探缓存还没过期），
    // 只看地址的判据会 Ignore —— 用户点了刷新，一次 prepare() 都没跑。
    // 这个序号让"用户要求重投"成为一个独立于地址的信号，且**只生效一次**
    // （消费后写回），所以普通重组仍不会重复 prepare（P9 / P11 的原意保住）。
    var appliedNonce by remember { mutableStateOf(-1L) }
    // 是否曾经进入过 READY：直播流正常也会周期性重缓冲（STATE_BUFFERING），
    // 只看"当前不是 READY"会把健康线路误判成卡死。
    // ⚠️ 按 streamUrl remember：换线路是"重新开始"，必须回到"未就绪"。
    // ⚠️ 也必须按 reconnectNonce remember（2026-11-17「刷新没反应」的二阶问题）：
    // 重连时地址常常不变，若不回到 false，首帧看门狗会因"曾经就绪过"永久失效，
    // 用户点完刷新后连自动换线都不再发生 —— 比原来的空转更难发现。
    var reachedReady by remember(streamUrl, reconnectNonce) { mutableStateOf(false) }
    // 单调时钟（P14）：卡死看门狗的采样时刻全部走它。
    val clock = remember { PlayerClock.System }
    // VOD 进度条喂值（2026-11-25）：ExoPlayer 没有"位置持续变化"回调，只能低频轮询。
    // 直播路径 handle 上同样挂着的字段不会被渲染 —— 进度条判据 isVod 恒 false。
    // 同一循环还负责：① 续播 seek（媒体就绪后一次性）；② 位置上报节流（每 5 秒一次）。
    val currentProgress by rememberUpdatedState(onReplayProgress)
    LaunchedEffect(handle, streamUrl, initialSeekMs) {
        var seekApplied = false
        var lastReportAt = 0L
        while (isActive) {
            handle.player?.let { p ->
                val position = p.currentPosition.coerceAtLeast(0L)
                val duration = p.duration.takeIf { it > 0L } ?: 0L
                handle.positionMs = position
                handle.durationMs = duration

                // 续播：等媒体时长已知（READY）再 seekTo，避免准备阶段 seek 被忽略。
                if (!seekApplied && initialSeekMs > 0L && duration > 0L &&
                    ReplayHistoryPolicy.shouldResume(initialSeekMs, duration)
                ) {
                    p.seekTo(initialSeekMs)
                    seekApplied = true
                    handle.positionMs = initialSeekMs
                }
                // 位置上报历史：节流，且只在 seek 已落地(或无需 seek)后开始记。
                if (duration > 0L && (initialSeekMs <= 0L || seekApplied)) {
                    val now = clock.nowMs()
                    if (now - lastReportAt >= PlayerControls.REPLAY_PROGRESS_REPORT_INTERVAL_MS) {
                        lastReportAt = now
                        currentProgress(handle.positionMs, duration)
                    }
                }
            }
            delay(400L)
        }
    }
    // P3：音频模式轮询链的重投开关。
    // "暂停态不重投"（与 AudioFocusPolicy 的注释一致）会带来一个新问题 ——
    // 用户手动点播放之后没人把这条链接回来，于是这道"来电盖住通话"的
    // 最后保险就永久失效了。播放监听（onIsPlayingChanged）负责重新接上。
    val focusPollRearm = remember { FocusPollRearm() }

    // 手动音频焦点：媒体类抢焦点（抖音/B 站/别的播放器）不停播，来电才停。
    // 见 AudioFocusPolicy 的说明。
    //
    // ⚠️ 焦点变化只当触发器，"是不是来电"必须**现查**音频模式：
    // 音频焦点协议是单向的，OnAudioFocusChangeListener 不告诉你是谁抢的。
    // 也不能缓存 —— 焦点丢失和来电几乎同时发生，缓存值可能是上一轮的。
    //
    // ⚠️ 用 AudioManager.getMode() 而不是 TelephonyManager.getCallState() /
    // TelecomManager.isInCall()：后两个从 API 29 起要 READ_PHONE_STATE 权限，
    // 本项目 manifest 只有 INTERNET，不为判断来电加敏感权限。
    // getMode() 免权限，而且它就是系统自己的音频路由状态。
    //
    // ## 为什么要有 hasFocus 这个显式状态（P1-2）
    //
    // 旧代码把 requestAudioFocus 的返回值丢掉，"是否持有焦点"完全靠系统记着。
    // 但 AOSP 的 MediaFocusControl 在派发**永久** AUDIOFOCUS_LOSS 之后
    // 会把我们移出焦点栈 —— 移出之后：
    //  - 来电的 GAIN_TRANSIENT 只派给当前持有者（抖音），我们**什么都收不到**
    //    → 直播声音盖住通话；
    //  - 抖音退出也不会再给我们 GAIN，恢复逻辑永远不触发。
    // 所以自己记一份 hasFocus，在回前台/起播时补申请。
    //
    // ## 为什么还要轮询一次音频模式（最后一道保险）
    //
    // 不在焦点栈里就收不到任何回调，事件驱动这条路是断的。
    // 播放期间低频轮询 getMode()：只要在播 + 现查到响铃/通话，就停。
    // 只在播放中轮询，暂停态什么都不做，不烧电。
    val context = LocalContext.current
    val focusLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        var pausedByFocus = false
        var hasFocus = false
        val listener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
            val player = playerView?.player ?: return@OnAudioFocusChangeListener
            val inCall = AudioFocusPolicy.isCallAudioMode(
                am?.mode ?: android.media.AudioManager.MODE_NORMAL,
            )
            when (change) {
                android.media.AudioManager.AUDIOFOCUS_LOSS,
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> {
                    // 永久 LOSS 之后系统已把我们移出焦点栈，显式状态要跟着改
                    if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                        hasFocus = false
                    }
                    if (AudioFocusPolicy.shouldPauseOnFocusLoss(change, inCall)) {
                        if (player.playWhenReady) {
                            pausedByFocus = true
                            player.pause()
                        }
                    }
                }
                android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (AudioFocusPolicy.shouldResumeOnFocusGain(
                            pausedByFocus = pausedByFocus,
                            playing = player.playWhenReady,
                        )
                    ) {
                        pausedByFocus = false
                        player.play()
                    }
                }
                else -> Unit
            }
        }
        /** 申请焦点并记录结果（返回值不再丢弃）。 */
        fun acquire() {
            val result = am?.requestAudioFocus(
                listener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN,
            )
            hasFocus = AudioFocusPolicy.isFocusGranted(result ?: -1)
        }
        acquire()
        // 轮询兜底：见上方说明。
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // ⚠️ 必须能自我重投：postDelayed 只跑一次，链断了这道保险就永久失效。
        //
        // P3（review/opt/player.md 第 3 条）：重投是**有条件**的，但暂停态照样重投 ——
        // 因为"被焦点暂停"本身正是要靠轮询恢复的状态（我们不在焦点栈里、收不到 GAIN）。
        // 同时补上对称的**恢复**分支：现查到已经离开通话模式、而播放是被焦点停的，
        // 就重新申请焦点 + 恢复播放（判据复用 AudioFocusPolicy.shouldResumeOnFocusGain）。
        lateinit var poll: Runnable
        poll = Runnable {
            val player = playerView?.player
            val mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL
            if (player != null) {
                if (AudioFocusPolicy.shouldPauseForAudioMode(
                        playing = player.playWhenReady,
                        mode = mode,
                    )
                ) {
                    pausedByFocus = true
                    player.pause()
                } else if (AudioFocusPolicy.shouldResumeForAudioMode(
                        pausedByFocus = pausedByFocus,
                        playing = player.playWhenReady,
                        mode = mode,
                    )
                ) {
                    pausedByFocus = false
                    acquire()
                    player.play()
                } else if (player.playWhenReady) {
                    // 已经在播、且当前不是通话模式：把"被焦点暂停"这层状态清掉。
                    // 不清的话，用户随后手动暂停时恢复分支会误以为那是焦点造成的，
                    // 替他继续播 —— 违反"不替用户继续播"这条既有语义。
                    pausedByFocus = false
                }
            }
            // 只在"播放中 / 被焦点暂停"时重投（暂停且不是被焦点停的 → 链自然停在
            // 用户手动暂停这一态，不烧电）；重新起播由 onIsPlayingChanged 接回。
            if (player != null && (player.playWhenReady || pausedByFocus)) {
                handler.postDelayed(poll, FOCUS_MODE_POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(poll, FOCUS_MODE_POLL_INTERVAL_MS)
        // 轮询链的重投入口（P3）：真正起播时重新接上这条链（见 onIsPlayingChanged）
        focusPollRearm.rearm = {
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, FOCUS_MODE_POLL_INTERVAL_MS)
        }
        // 回前台时补申请：被抖音抢走焦点后我们已不在栈里，
        // 不补申请就永远收不到后续事件（这里正是补回的那一次）
        val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                val player = playerView?.player
                val inCall = AudioFocusPolicy.isCallAudioMode(
                    am?.mode ?: android.media.AudioManager.MODE_NORMAL,
                )
                if (AudioFocusPolicy.shouldRequestFocusAgain(
                        hasFocus = hasFocus,
                        inCall = inCall,
                        playing = player?.playWhenReady == true,
                    )
                ) {
                    acquire()
                }
            }
        }
        focusLifecycleOwner.lifecycle.addObserver(resumeObserver)
        onDispose {
            // 先摘开关再清回调：残留的起播回调不该再往已销毁的 effect 里投递
            focusPollRearm.rearm = null
            handler.removeCallbacks(poll)
            focusLifecycleOwner.lifecycle.removeObserver(resumeObserver)
            am?.abandonAudioFocus(listener)
        }
    }

    // 后台暂停：按 Home 进小窗时 Activity 只是 PAUSED 不收此事件，播放不受影响；
    // 但被返回键划掉小窗、锁屏、切到别的 App（未进小窗）时会 ON_STOP，必须停播，
    // 否则球赛声音在后台一直响。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // ⚠️ ON_STOP 造成的暂停，回到前台必须自动恢复：
        // 只停不恢复的话，锁屏/切走再回来直播就停在暂停态（边缘已经漂移），
        // 用户还得手动点一次播放。
        // 但用户自己按了暂停（playWhenReady=false）就不能替他继续播——
        // 用这个标志区分"被系统停的"和"用户主动停的"。
        var pausedByStop = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    val player = playerView?.player
                    if (player != null && player.playWhenReady) {
                        pausedByStop = true
                        player.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    val player = playerView?.player
                    if (pausedByStop && player != null) {
                        pausedByStop = false
                        player.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ⚠️ 切场时复位小窗视频尺寸（review/01-player.md P2-5）：
    // setVideoSize 只在 onVideoSizeChanged 里写，切到另一场比赛后
    // 新场次首帧到达之前**没人清零** —— 小窗按上一场的比例显示
    // （上一场 4:3、这一场 16:9 时，小窗会被拉成错误形状）。
    // 清零后 pipAspectRatio() 回退 16:9，等真实宽高到了再更新。
    LaunchedEffect(streamUrl) {
        // P17（player.md 第 17 条）：判据不再是恒真函数，真判据是"上一次是否真的记过尺寸"。
        // 先查再写：`setVideoSize(0, 0)` 在本来就 (0,0) 时是空操作，跳过它不影响任何可观察状态。
        if (PipAspectReset.shouldResetOnMatchSwitch(
                hasRecordedVideoSize = PipState.videoSize.value.width > 0,
            )
        ) {
            PipState.setVideoSize(0, 0)
        }
    }

    // 卡死看门狗：有些线路清单能拉到但直播边缘流卡住，永远停在 BUFFERING，
    // 不会触发 onPlayerError，也就不会故障切换。
    //
    // ⚠️ 阈值从 15s 提到 30s（review/01-P1-5）：
    // 15 秒对弱网首帧太紧 —— 实测移动网络冷启动 + CDN 首次建连经常就要 20s+，
    // 那些线路是**健康**的，只是第一帧慢。误判后它会被记进失败记账、
    // 自动跳走，用户看到"线路莫名被跳走且回不来"。
    // 现在失败记账本身有 TTL（SourceFailureBook，5 分钟自动解封），
    // 双重降低"一次误判 = 整场看不了"的概率。
    // ⚠️ key 里必须带 reconnectNonce：重连时地址常常不变，不带的话这一轮
    // 永远不会再被首帧看门狗盯（配合上面 reachedReady 的复位，两条是同一个二阶问题）。
    LaunchedEffect(streamUrl, reconnectNonce) {
        delay(FIRST_FRAME_WATCHDOG_MS)
        val player = playerView?.player
        // 只有"从未就绪过"才算卡死：直播流正常重缓冲时状态就是 BUFFERING，
        // 之前只看 !=STATE_READY，健康线路会被误杀并被永久拉黑（failedSources）
        if (player != null && !reachedReady &&
            player.playbackState != Player.STATE_READY &&
            reportedRef.add(streamUrl)
        ) {
            onErrorState.value()
        }
    }

    // 中途卡死看门狗（review/01-player.md P2-1）。
    //
    // 上面的首帧看门狗只覆盖"从未就绪"。首帧成功、播十分钟后 CDN 抽风时，
    // 播放器停在 BUFFERING 而 onPlayerError **不触发** —— 界面只有转圈，
    // 永远不会自动换线，用户只能自己点。
    //
    // 判据抽在 StalledPlayback（纯逻辑，有单测）：
    //  - 未就绪过 -> NOT_APPLICABLE，交给首帧看门狗，两条判据不重叠
    //    （重叠会让首帧慢的线路被两边各判一次、连续换两条线）；
    //  - 已就绪且连续缓冲满阈值 -> STALLED，触发一次换线；
    //  - 同一段卡死只报一次，避免连续触发多次故障切换。
    // ⚠️ key 里必须带 reconnectNonce：Tracker 记的是"上一段卡死只报一次"，
    // 重连后地址不变的话这个记忆会跨轮存活，新一轮的真卡死就再也报不出来。
    LaunchedEffect(streamUrl, reconnectNonce) {
        val tracker = StalledPlayback.Tracker()
        while (isActive) {
            delay(STALL_POLL_INTERVAL_MS)
            val player = playerView?.player ?: continue
            // 位置前进说明在播（有些播放器追帧时短暂停在 BUFFERING）
            tracker.onProgress(player.currentPosition, clock.nowMs())
            val shouldSwitch = tracker.onTick(
                reachedReady = reachedReady,
                playbackState = player.playbackState,
                nowMs = clock.nowMs(),
            )
            if (shouldSwitch && reportedRef.add(streamUrl)) {
                onErrorState.value()
            }
        }
    }

    // ---------- P9 完整版：换线路 → 换媒体项（**一次性**，不重建播放器） ----------
    // 判据 [LineSwitchPolicy.decide]（纯函数 + 单测）：
    //  - 已经应用过同一个地址 → Ignore（**重组不会重复 prepare** —— 这正是 P11 当年
    //    删掉的那个"每次重组都 setMediaItem"失效模式，现在由判据显式挡住）；
    //  - 新地址 → Apply，`playWhenReady` 沿用换线前一刻的播放意图：
    //    在播 → 自动起播；用户按过暂停 → 不起播（不替用户做决定）。
    //
    // ⚠️ key 里带 `playerView`：工厂是"布局阶段"才跑、把 view 写进这个状态的，
    // 所以首轮 effect 可能看到 null（直接返回），view 就绪后会再跑一次。
    LaunchedEffect(streamUrl, playerView, reconnectNonce) {
        val view = playerView ?: return@LaunchedEffect
        val player = view.player ?: return@LaunchedEffect
        when (
            val action = LineSwitchPolicy.decide(
                appliedUrl = appliedUrl,
                newUrl = streamUrl,
                playingIntent = player.playWhenReady,
                // 本轮重连还没被消费过 → 强制重投一次（地址相同也要 prepare）
                forceReapply = reconnectNonce != appliedNonce,
            )
        ) {
            LineSwitchPolicy.Action.Ignore -> Unit
            is LineSwitchPolicy.Action.Apply -> {
                appliedUrl = streamUrl
                appliedNonce = reconnectNonce
                // 换线时先清掉上一线的画质标签：新线首帧到达前显示旧分辨率是错的
                handle.clearQuality()
                player.setMediaItem(MediaItem.fromUri(streamUrl))
                player.prepare()
                player.playWhenReady = action.playWhenReady
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            // ⚠️ P9 完整版：Referer 不再"烧"在 build 里，而是**每次 createDataSource()
            // 时现查** —— 这是"换线路不重建播放器"能成立的前提。
            // 用 rememberUpdatedState 而不是捕获 referer：工厂只跑一次，
            // 捕获的话换线路后 CDN 仍会拿到第一条线路的 Referer。
            val dataSourceFactory = RefererDataSourceFactory(
                refererProvider = { currentReferer.value },
                userAgent = HttpClient.USER_AGENT,
            )

            val player = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    // ⚠️ handleAudioFocus 传 **false**，焦点自己管。
                    //
                    // 传 true 的话，任何 App 抢音频焦点 ExoPlayer 都会自动暂停。
                    // 实测（Android 14 / API 34）：小窗看直播时打开抖音，
                    // 直播立刻停 —— 用户明确要求「小窗刷抖音时不要暂停播放」。
                    //
                    // 但不处理焦点也不行：来电时直播声音会盖住通话。
                    // 所以这里只声明音频属性，焦点变化走下面的
                    // AudioFocusPolicy.shouldPauseOnFocusLoss ——
                    // 电话/语音助手才停，媒体类（抖音/B 站/别的播放器）不停。
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        /* handleAudioFocus = */ false,
                    )
                    // 拔耳机时停播：这是物理断连，声音突然外放很尴尬
                    setHandleAudioBecomingNoisy(true)
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    prepare()
                    playWhenReady = true
                }

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 每条地址只上报一次，避免重试风暴
                    if (reportedRef.add(streamUrl)) {
                        onErrorState.value()
                    }
                }

                // 自定义控制栏要读播放/缓冲状态
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handle.isPlaying = isPlaying
                    // P3：真正起播时把"音频模式轮询"这条链重新接上。
                    // 暂停态不重投（省电），但用户手动点播放之后没人接回来 ——
                    // 挂在这里，这道"来电盖住通话"的最后保险才不会永久失效。
                    if (isPlaying) focusPollRearm.request()
                }

                // 上报视频宽高：画中画窗口按真实比例算，非 16:9 的源不用加黑边
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        PipState.setVideoSize(videoSize.width, videoSize.height)
                        // 同一份宽高也是画质档位的准确来源
                        handle.onVideoSize(videoSize.width, videoSize.height)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    handle.isBuffering = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) reachedReady = true
                }
            })

            // 关键：关闭 Media3 原生控制条。
            // 直播流不需要快进/后退/拖动进度条，原生控制条在直播上表现为
            // "可拖到任意历史位置"，误触就跳离直播边缘、画面直接卡住。
            // 控制交给底部的自定义控制栏（暂停/重连/画面/换源/换线路/同时在播）。
            PlayerView(ctx).apply {
                useController = false
                resizeMode = aspectMode.resizeMode
                this.player = player
                // 屏幕常亮加在 View 上而不是 Activity 的 window 上：
                // View.keepScreenOn 随这个 View 的可见性自动生效/失效，
                // 进画中画、退后台、切页面都不需要手动清标志——
                // window flag 要靠外部状态手动 add/clear，漏一次就一直亮着。
                keepScreenOn = true
                playerView = this
                handle.attach(player)
            }
        },
        update = { view ->
            // P11（player.md 第 11 条）：删掉原来的 `setMediaItem` 分支。
            // 组件被 `key(streamUrl, streamReferer)` 包住，同一 view 实例上 streamUrl 永不改变，
            // 所以那个分支正常不可达；而一旦某个 m3u8 地址的 `Uri.toString()` 与原串不相等
            // （百分号编码/转义），它会在**每次重组**（缓冲状态每秒翻转数次）都
            // `setMediaItem + prepare`，表现为画面不断重缓冲。
            // 死代码却带一个"每次重组都 prepare"的失效模式 —— 删掉比留着安全。
            view.resizeMode = aspectMode.resizeMode
        },
        onRelease = { view ->
            handle.detach()
            // ⚠️ 先把引用摘掉再 release：
            // Media3 的 release() 会同步回调监听器，
            // 如果这时 view.player 还指着这个实例，回调里可能又碰到它。
            // 先置 null 再 release，状态更干净，也不会漏掉引用。
            val p = view.player
            view.player = null
            p?.release()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** WebView 承载源站播放页（用于加密的 JRS 线路） */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebSurface(
    url: String,
    onStreamFound: (String, String?) -> Unit,
    suppressHint: Boolean = false,
    /**
     * 网页线路**确认**播不出来时上报（播放页主框架真实加载失败）。
     *
     * ⚠️ P0-1 之后这里只收**事实**，不再收推断：
     * 只有 [PlaybackHealth.isConfirmedWebFailure] 认可的主框架致命错误码才上报。
     * 上报后上层允许收掉小窗（处置仍是可逆的 `finish()`）。
     */
    onPlaybackDead: () -> Unit = {},
    /**
     * 网页线路**疑似**播不出来时上报（时限内没嗅探到任何流）。
     *
     * 时限有两条，都在 [WebSniffWatchdog]：页面已 finish 时是 finish 后 15 秒；
     * 页面**永不** `onPageFinished` 时是从加载起算 30 秒（后者是"永久黑屏"的兜底）。
     *
     * 这是**推断**：源站用 MSE/`blob:` 播放、m3u8 地址不带 `.m3u8` 字面量、
     * 弱网首帧超过时限，都会嗅探不到但画面其实正常。
     * 所以走独立回调，上层只用于显示提示 + 停自动进小窗，**不**收窗、不关任务。
     */
    onPlaybackSuspect: () -> Unit = {},
    /**
     * 页面里检测到源站播放器**自己的错误面板**（xgplayer 的 `xgplayer-is-error`）。
     *
     * 与 [onPlaybackDead]/[onPlaybackSuspect] 的区别：这条是页面 DOM 里的**事实**
     * （播放器自己设的状态类），不是我们推断的"嗅探不到"。
     * 判据见 [com.nbalive.app.ui.WebPageDeadEndPolicy]。
     */
    onPageDeadEnd: () -> Unit = {},
    /**
     * 恢复条**出现**的那一刻上报一次"疑似"（P18，复核修正版）。
     *
     * 为什么需要一条比 [onPageDeadEnd] 更早、又比 [onPlaybackSuspect] 更轻的回调：
     * 恢复条 1.5 秒就出现，而 [onPageDeadEnd] 要等 9.5 秒；中间这 8 秒里
     * `playbackSuspect` 仍是 false，用户此刻按 Home 会进一块点不动的坏小窗。
     *
     * ⚠️ **绝不能**把它接到 `MainViewModel.onPlaybackSuspect`：
     * 那个入口在置标记之后紧接着 `advanceAfterNoSignal(...)`（`MainViewModel.kt:1242-1254`），
     * 1.5 秒就换线会把恢复条与用户的 8 秒选择窗口一起抹掉。
     * 上层需要的是一个**只置疑似标记、不换线**的入口。
     */
    onDeadEndSuspect: () -> Unit = {},
    /**
     * 死胡同恢复条的**显示内容**上报（null = 收起）。P1 真机回归修复。
     *
     * ⚠️ 为什么恢复条不在这里渲染：本组件是视频区 `Box` 的**早于手势层**的兄弟子树，
     * 画在这里的按钮拿不到点击（命中测试先落进 `fillMaxSize()` 的手势层）。
     * 所以这里只负责"该不该显示 + 文案"（文案唯一来源仍是
     * [com.nbalive.app.ui.WebPageDeadEndPolicy]），渲染交回 `PlayerScreen` 的浮层区。
     * 判据：`PlayerFixTest.恢复条必须在手势层之后渲染`。
     */
    onDeadEndBarChanged: (WebDeadEndBar?) -> Unit = {},
    /**
     * 「重新加载」请求（**计数**，不是布尔事件）。
     *
     * 浮层区的按钮只能表达"用户点了"，真正的导航逻辑（`targetUrl` / `reloadSeq` /
     * `pageErrorAt`）只有本组件知道，所以用计数把请求传进来：
     * 计数只增不减，重组合并时不会像布尔事件那样被吞掉
     * （同一取舍见 `PipState.expandRequests`）。
     */
    reloadRequest: Int = 0,
    /**
     * 「用户要求重连」的意图序号（根因见 [com.nbalive.app.ui.PlayerUiState.reconnectNonce]）。
     *
     * ⚠️ 网页线路的重连**只能**靠它：`play()` 会把同一个播放页地址原地写回，
     * `navigationTag` 不变 → `update` 块不调 `loadUrl`，WebView 原样停在死页上。
     * 用户点刷新"没反应"，指的就是这一支。
     */
    reconnectNonce: Long = 0L,
) {
    val onStreamFoundState = rememberUpdatedState(onStreamFound)
    val onPlaybackDeadState = rememberUpdatedState(onPlaybackDead)
    val onPlaybackSuspectState = rememberUpdatedState(onPlaybackSuspect)
    val onPageDeadEndState = rememberUpdatedState(onPageDeadEnd)
    val onDeadEndSuspectState = rememberUpdatedState(onDeadEndSuspect)
    val onDeadEndBarChangedState = rememberUpdatedState(onDeadEndBarChanged)
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    // ---------- 嗅探状态（P4 / P5 / P10） ----------
    // ⚠️ 计时一律用**单调时钟** `SystemClock.elapsedRealtime()`，用墙钟会被用户改系统时间/
    // 跨时区搞坏（计时永远不到点或立刻到点）。这里展开写默认实现（等价于 `PlayerClock.System`），
    // 让这条口径在**本组件体内**直接可读 —— WebSniffWatchdogTest 的
    // "loadStartedAt 必须来自单调时钟" 锚的正是这里。
    val clock = remember { PlayerClock { android.os.SystemClock.elapsedRealtime() } }
    // 媒体请求记账（见过吗 / 最近一次什么时候）+ 加载起点 + 导航代次，全部收进 WebSniffState。
    // 原来：`sawMediaRequest` 是只 set(true) 的 AtomicBoolean（P4）、
    // `loadStartedAt` 只按 url remember（P5）—— 两者都不随导航复位，于是
    // 「重新加载 / 退回原页」之后看门狗要么永久沉默、要么按旧基准提前判疑似。
    // 线程约定与判据见 WebSniffState（它有 JVM 单测）。
    val sniff = remember(url) { WebSniffState(clock) }
    // 页面是否已完成首次加载：嗅探计时必须在它**之后**才开始。
    // 否则计时从 loadUrl 那一刻起跑，源站播放页常有 2~3 跳重定向，
    // 光导航就吃掉十几秒，留给播放器的时间根本不够 —— 正常线路也会被判疑似。
    //
    // ⚠️ 它必须留在这里做 **Compose 快照状态**：`LaunchedEffect(url, pageFinishedAt, …)`
    // 靠它变化立刻重跑那一轮采样（WebSniffState 里是普通字段，不会触发 effect 重启）。
    var pageFinishedAt by remember(url) { mutableLongStateOf(0L) }
    // 被守卫拦下的跳转说明（04-P1-3/4）。源站广告把播放页导航走时，
    // 这条路径不报 ExoPlayer 错误、也不报 onReceivedError，界面必须自己说话，
    // 否则用户只看到一个动不了的广告页，以为 App 卡死。
    var blockedNavigation by remember(url) { mutableStateOf("") }

    /**
     * 每次"重新导航"（首次加载 / 回到原始壳页 / 退回原播放页）都必须先走这里：
     * **P4 + P5 的唯一复位入口**（导航代次 +1、媒体请求记账清零、加载基准重置）。
     */
    fun beginNavigation() {
        sniff.onNavigationStarted()
        pageFinishedAt = 0L
    }

    // ---------- 任务 D：跳过播放页壳，直接加载玩家页（2026-10-30「解析播放太慢」） ----------
    // 播放页壳（约 2KB）会先拉跨站 jquery 与 parser-blocking 的 t.js，实测 0.1~27 秒；
    // 真正的玩家页地址可以从壳页 HTML 里算出来（见 PlayerPageResolver）。
    // 用 OkHttp 拉壳页（**不执行脚本**，通常几百毫秒内返回），算出 paps 地址后让 WebView 直接加载它。
    //
    // ⚠️ 任何失败都必须静默回退到原播放页：
    //  - 拉不到 / 解析不出 → targetUrl 保持 url；
    //  - 直接加载 paps 后 6 秒内一个媒体请求都没有 → 再退回原播放页（源站改版时的兜底）。
    var targetUrl by remember(url) { mutableStateOf(url) }
    // 导航代次（H6）：原来用 `directPlayerTried` 把这条捷径钉在"每个 url 只跑一次"上，
    // 于是「重新加载」只能 `reload()` 当前地址 —— 捷径路径下那正是**同一个旧签名**。
    // 现在"重新跑一次捷径"变成显式代次（进 effect 的 key）：点重新加载 → 代次 +1。
    var reloadSeq by remember(url) { mutableIntStateOf(0) }
    // 导航标识 = 代次 + 重连意图 + 目标地址。
    // ⚠️ 只比较地址会让"地址没变但要求重新加载"落空（旧实现在捷径路径下就是这样），
    // 带上代次之后，即使 targetUrl 与当前 tag 相同也会真的 `loadUrl` 一次。
    // ⚠️ reconnectNonce 也必须进来（2026-11-17「刷新没反应」）：底栏那个刷新图标
    // 走的是 VM 的 reconnect，它把**同一个播放页地址**原地写回，
    // 只有意图序号能让这里观察到"用户要求重来一次"。
    val navigationTag = "$reloadSeq|$reconnectNonce|$targetUrl"
    LaunchedEffect(url, reloadSeq, reconnectNonce) {
        // ⚠️ 首轮、「重新加载」与「重连」走**同一条路**：拉壳页 → 算新的 paps → 让 WebView 直接加载它。
        // 解析失败时 targetUrl 保持 url（回退语义与改动前一致，只是现在每次都显式写一遍）。
        val html = kotlinx.coroutines.withTimeoutOrNull(PlayerPageResolver.SHELL_FETCH_TIMEOUT_MS) {
            runCatching {
                HttpClient.shared.get(url, HttpClient.shared.refererOf(url), fastFail = true)
            }.getOrNull()
        }
        val direct = html?.let { PlayerPageResolver.resolvePlayerUrl(it, url) }
        // 壳页 → paps 也是一次新导航：嗅探状态与计时基准都要复位，
        // 否则壳页那一小段里观察到的媒体请求会被算到新页面上。
        beginNavigation()
        targetUrl = direct ?: url
    }
    // 兜底：直接加载玩家页后迟迟没有任何媒体请求 → 可能源站改版/需要壳页里的额外脚本，
    // 退回原播放页重来一次（只退一次，避免来回跳）。
    LaunchedEffect(targetUrl) {
        if (targetUrl == url) return@LaunchedEffect
        delay(6_000)
        if (!sniff.sawMediaRequest() && pageFinishedAt > 0L) {
            // 退回原播放页同样是一次导航：不复位的话，刚加载的原页会按 paps 页的
            // finish 时刻计时（P5 —— 只等几秒就被判疑似）。
            beginNavigation()
            targetUrl = url
        }
    }

    // 源站页面可能一片黑且永远不出流（比赛已结束、播放器 JS 没跑起来）：
// 两条时限内没看到任何媒体请求，就给用户明确提示、并让上层自动换线，
// 而不是干等黑屏。嗅探成功后本组件会被 VideoSurface 替换、协程自动取消，无需手动复位。
//
// ⚠️ 两条时限都定义在 [WebSniffWatchdog]（判据唯一来源）：
//  - 页面已 finish：finish 之后 15 秒；
//  - 页面**永不** finish：从 loadUrl 起算 30 秒（没有这条兜底时限时，
//    这类页面会让看门狗彻底失效 —— 见下面的说明）。
    //
    // ⚠️ suppressHint 只抑制**用户可见的提示**，不能连判定一起跳过：
    // 嗅探过但直连失败、退回网页播放时 suppressHint=true，
    // 而那恰恰是最需要留痕的情况 —— 网页里的源站播放器会自己画一句
    // 「网络错误 / 请试试 刷新」（这句不在 App 代码里，读不到）。
    //
    // ⚠️ 判死分级（P0-1）：这一条只报**疑似**（onPlaybackSuspect），不再报死亡。
    // 它本质是推断，误判代价原本却是"移除整个任务"，两者不成比例。
    var noSignal by remember(url) { mutableStateOf(false) }
    // ⚠️ 判据**唯一来源**是 [WebSniffWatchdog]（有单测）；这里只负责轮询采样与执行。
    //
    // 早先这里的第一句是 `if (pageFinishedAt == 0L) return@LaunchedEffect`：
    // 判据被 onPageFinished 门控 —— 源站播放页被广告/坏 iframe 卡住永不 finish 时，
    // 看门狗永远不触发，既不提示也不自动换线，就是**永久黑屏**（实测纯黑 95 秒、
    // logcat 一条相关输出都没有）。现在两条时限并列：永不 finish 走"从加载起算的硬上限"，
    // 已 finish 走"finish 后 15 秒"。
    LaunchedEffect(url, pageFinishedAt, suppressHint) {
        while (true) {
            val reported = WebSniffWatchdog.shouldReport(
                loadStartedAt = sniff.loadStartedAtMs(),
                pageFinishedAt = pageFinishedAt,
                nowMs = clock.nowMs(),
                sawMediaRequest = sniff.sawMediaRequest(),
            )
            if (reported) {
                // ⚠️ suppressHint 只抑制**用户可见的提示**，不抑制判定与上报：
                // 嗅探过但直连失败、退回网页播放时 suppressHint=true，
                // 而那恰恰是最需要留痕的情况（见上面那段说明）。
                if (!suppressHint) noSignal = true
                onPlaybackSuspectState.value()
                return@LaunchedEffect
            }
            delay(WebSniffWatchdog.POLL_MS)
        }
    }

    // ---------- 源站播放器自己报错（2026-10-31「点刷新没有反应」） ----------
    // 页面里画了「网络错误 / 请试试 刷新」时，点那个「刷新」是**点不动**的：
    // xgplayer 的 retry() 只是把同一个 config.url 重新赋给 <video>，
    // 而那个地址已经失效（详见 WebPageDeadEndPolicy 的注释）。
    // 这条路径既没有 onPlayerError、也没有 onReceivedError（页面加载是成功的），
    // 只能由我们主动探测页面 DOM，并给出自己的出口。
    //
    // ⚠️ 探测用轮询 + evaluateJavascript：WebView 没有"页面 JS 报错"的宿主回调，
    // 这是唯一能知道页面进入错误态的办法。
    var pageDeadEnd by remember(url) { mutableStateOf(false) }
    // 首次探测到错误的时刻（单调时钟）；0 = 还没探测到
    var pageErrorAt by remember(url) { mutableLongStateOf(0L) }
    // 连续「干净」的探测轮数：P12 的退避输入（探到错误立刻归零）
    var cleanProbes by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, targetUrl, reconnectNonce) {
        // 导航变化 → 这条循环重启：与**上一个**页面绑定的出口状态先清零（P4/P5 的同一入口）。
        // 不清的话，上一个页面留下的错误时刻会让新页面刚打开就顶着恢复条。
        // ⚠️ reconnectNonce 也进 key：重连就是一次新的 loadUrl，
        // 地址没变时只有它能让这条探测循环回到"重新开始"的基准。
        pageErrorAt = 0L
        pageDeadEnd = false
        cleanProbes = 0
        while (true) {
            val view = webView
            if (view != null) {
                view.evaluateJavascript(WebErrorProbeScript.HAS_ERROR) { raw ->
                    if (WebErrorProbeScript.detectsError(raw)) {
                        cleanProbes = 0
                        if (pageErrorAt == 0L) pageErrorAt = clock.nowMs()
                    } else if (pageErrorAt != 0L) {
                        // 页面自己恢复了（源站偶尔会重连成功）：把出口收掉，
                        // 别让用户对着一条"其实已经好了"的提示。
                        pageErrorAt = 0L
                        pageDeadEnd = false
                    } else {
                        cleanProbes++
                    }
                }
            }
            // P12（player.md 第 12 条）：原来是无条件 1Hz —— 整个网页播放期间每秒一次
            // JSBridge 往返（页面内还要遍历同源 iframe 并取 getComputedStyle），
            // 与同样 1Hz 的嗅探看门狗叠加成 2Hz。现在首轮密集、页面稳定后 / 已出流后退避。
            delay(
                WebPageDeadEndPolicy.probeIntervalMs(
                    consecutiveClean = cleanProbes,
                    hasStream = PlaybackHealth.hasStreamNow(
                        recentMediaRequestAtMs = sniff.lastMediaRequestAtMs(),
                        nowMs = clock.nowMs(),
                    ),
                ),
            )
        }
    }
    // 判据唯一来源是 WebPageDeadEndPolicy（有单测）；这里只采样 + 执行。
    // 计时起点是"第一次探测到错误"，所以确认期与自动兜底都从那一刻算。
    LaunchedEffect(url, pageErrorAt) {
        if (pageErrorAt == 0L) return@LaunchedEffect
        while (true) {
            val elapsed = clock.nowMs() - pageErrorAt
            // P2（复核修正版）：`hasStream` 不再是写死的常量，而是"**现在**是否真的在出流"——
            // 最近 2 秒内还有新的媒体请求才算（判据 PlaybackHealth.hasStreamNow）。
            // ⚠️ 不能传 `sniff.sawMediaRequest()`：那个标志一旦为 true 就永不回 false，
            // 而本功能唯一的目标场景恰恰是"页面发过媒体请求、只是那条 404"——
            // 接上它会把这道出口永久关掉（review/opt/verification.md 第 2 节）。
            val hasStream = PlaybackHealth.hasStreamNow(
                recentMediaRequestAtMs = sniff.lastMediaRequestAtMs(),
                nowMs = clock.nowMs(),
            )
            val detected = pageErrorAt > 0L
            if (WebPageDeadEndPolicy.shouldOffer(
                    hasStream = hasStream,
                    pageErrorDetected = detected,
                    sinceDetectedMs = elapsed,
                )
            ) {
                // P18（复核修正版）：恢复条一出现就**同步**上报"疑似"，上层据此停掉
                // 「按 Home 自动进小窗」。onPageDeadEnd 要等到 9.5 秒，中间这 8 秒里
                // playbackSuspect 仍是 false —— 用户此刻按 Home 会进一块点不动的坏小窗。
                // ⚠️ 必须走 onDeadEndSuspect（只置标记、不换线）：onPlaybackSuspect 在
                // MainViewModel 里紧跟着 advanceAfterNoSignal，1.5 秒就换线会把恢复条
                // 与用户的 8 秒选择窗口一起抹掉。
                if (WebPageDeadEndPolicy.shouldEscalateSuspectOnOffer(pageDeadEnd)) {
                    pageDeadEnd = true
                    onDeadEndSuspectState.value()
                }
            }
            if (WebPageDeadEndPolicy.shouldAutoAdvance(
                    hasStream = hasStream,
                    pageErrorDetected = detected,
                    sinceDetectedMs = elapsed,
                )
            ) {
                // 出口摆了一会儿没人点：替用户换下一条线，别让他干等一块死面板
                onPageDeadEndState.value()
                return@LaunchedEffect
            }
            delay(WebPageDeadEndPolicy.PROBE_INTERVAL_MS)
        }
    }

    // 与 ExoPlayer 同理：退到后台（非小窗）时暂停页面定时器，省电并防止声音后台外放。
    //
    // ⚠️ `onPause()` + `pauseTimers()` **停不掉 HTML5 媒体**（review/01-player.md P2-3）：
    // 它们只暂停页面渲染与 JS 定时器，`<video>`/`<audio>` 照样出声 ——
    // 用户锁屏后声音还在外放。WebView 没有公开 API 能停媒体，
    // 只能注入脚本调 pause()（脚本见 WebMediaPauseScript，有单测）。
    // 直连路径没这个问题：ExoPlayer 由 ON_STOP 显式 pause()。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    webView?.evaluateJavascript(WebMediaPauseScript.PAUSE_ALL, null)
                    webView?.pauseTimers()
                    webView?.onPause()
                }
                Lifecycle.Event.ON_START -> {
                    webView?.resumeTimers()
                    webView?.onResume()
                    // 只恢复**被我们暂停过**的媒体，不碰用户自己暂停的
                    webView?.evaluateJavascript(WebMediaPauseScript.RESUME_PAUSED, null)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.BLACK)
                    // 网页线路没有 PlayerView，常亮标志加在 WebView 上。
                    // 与直连路径同理：绑在 View 上，随可见性自动生效/失效。
                    keepScreenOn = true
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        // 播放页是 http，其视频切片常是 https 反向代理后的 http；
                        // 收紧成 COMPATIBILITY 会黑屏，这是实测后的有意取舍
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = HttpClient.USER_AGENT
                        // ⚠️ 显式关掉本地文件访问（review/04-security.md P2-3）：
                        // API 24~29 上这两个开关**默认是 true**，源站页面里的 JS
                        // 可以借此探测 `file://` 路径下的文件是否存在
                        // （存在性探测 → 指纹/本地文件枚举）。
                        // 播放页完全不需要读本地文件，关掉零成本。
                        allowFileAccess = false
                        allowContentAccess = false
                    }
                    // ⚠️ 主框架导航必须过白名单（review/01「广告把播放页导航走」+ 04-P1-3/4）。
                    // 旧实现只挡非 http(s)：源站广告脚本一句 location.href = "https://广告页"
                    // 就能把播放页整个换掉，而那条路径既不报 ExoPlayer 错误、
                    // 也不报 onReceivedError（导航本身成功），App 会一直以为"还在播"。
                    // 判据集中在 WebNavigationPolicy（可单测），这里只负责执行与留痕。
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val blocked = WebNavigationPolicy.blockReason(
                                url = request?.url?.toString(),
                                currentUrl = view?.url,
                                isMainFrame = request?.isForMainFrame != false,
                            )
                            if (blocked.isEmpty()) return false
                            blockedNavigation = blocked
                            debugLog("WebSurface", blocked)
                            return true
                        }

                        // 嗅探源站 JS 解密后真正请求的 m3u8：拿到就上报，交给 ExoPlayer 直连
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            val u = request?.url?.toString().orEmpty()
                            // ⚠️ 本回调在**后台线程**执行，写 Compose 状态会踩线程，
                            // 所以媒体请求的记账放在 WebSniffState 里（原子类型，方法自己取时钟），
                            // 判定处再读 —— 见 WebSniffState 的线程约定。
                            if (PlaybackHealth.isMediaRequest(u)) sniff.markMediaRequest()
                            if (PlaybackHealth.isPlaylistRequest(u)) {
                                val ref = request?.requestHeaders?.entries
                                    ?.firstOrNull { it.key.equals("Referer", true) }?.value
                                onStreamFoundState.value(u, ref)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        /**
                         * 播放页主框架报错。
                         *
                         * ⚠️ P1-1：**不是所有 errorCode 都算播不出来**。
                         * 旧代码只判 `isForMainFrame` 就上报死亡，于是
                         * 源站页面里一句 `location.href = ...` 跳转、
                         * 或用户连点两次「重连」（update 里的 loadUrl 打断上一次导航），
                         * 主框架会收到 `ERR_ABORTED` —— 那是"导航被取代"，不是失败，
                         * 却被判成了彻底死了。
                         * 判据筛选集中在 [PlaybackHealth.isConfirmedWebFailure]（可单测）。
                         */
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (!PlaybackHealth.isConfirmedWebFailure(
                                    errorCode = error?.errorCode,
                                    isForMainFrame = request?.isForMainFrame == true,
                                    isRedirect = request?.isRedirect == true,
                                    pageFinished = pageFinishedAt > 0L,
                                )
                            ) {
                                // 子资源失败 / 重定向 / 加载中：都可能自愈。
                                // 真出问题由 15 秒的「疑似」计时器兜住，不在这里抢判死亡。
                                return
                            }
                            onPlaybackDeadState.value()
                        }

                        /**
                         * 主页面加载完成 —— 「疑似」计时从这里才开始跑（P0-1 建议的第三条修法）。
                         *
                         * ⚠️ 这个回调在 `WebViewClient` 上，不在 `WebChromeClient` 上。
                         *
                         * 用单调时钟（`SystemClock.elapsedRealtime`）而不是墙钟：
                         * 用户改系统时间/时区、或跨时区旅行时墙钟会跳变，
                         * 那会让计时永远不到点或立刻到点。
                         */
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            pageFinishedAt = clock.nowMs()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        // 源站常弹“备用网址”alert，直接吞掉，避免挡住播放器
                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?,
                        ): Boolean {
                            result?.confirm()
                            return true
                        }

                        // confirm 多为广告/引流弹窗，替用户点"取消"而不是默认同意
                        override fun onJsConfirm(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?,
                        ): Boolean {
                            result?.cancel()
                            return true
                        }
                    }
                    loadUrl(targetUrl)
                    // ⚠️ tag 用 navigationTag（代次 + 地址）而不是裸地址：见它的 KDoc ——
                    // 地址没变但要求"重新加载"时（H6），裸地址比较会让 loadUrl 落空。
                    tag = navigationTag
                    webView = this
                }
            },
            update = { view ->
                // 注意不能比较 view.url：页面会自行跳转（sm.html -> 内层播放页），
                // 那样每次重组都会重新 loadUrl，把正在播的流打断。用 tag 记住"我们请求的导航"。
                if (view.tag != navigationTag) {
                    view.tag = navigationTag
                    // ⚠️ 真机取证锚点（与 H6 那条同一条纪律）：用户报"点了刷新没反应"时，
                    // 这一行是"重连意图确实变成了一次真实导航"的唯一客观证据 ——
                    // 没有它，"界面没收到意图"与"收到了但导航没发生"分不开。
                    // ⚠️ 绝不能整串打 navigationTag：它拼了 targetUrl，
                    // 而捷径路径下那是带 encodedStr 签名的 paps 地址 —— 签名会直接进 logcat。
                    // 只打代次/意图这两个数 + 脱敏后的地址。
                    debugLog("WebSurface", "navigate seq=$reloadSeq nonce=$reconnectNonce → ${redactUrl(targetUrl)}")
                    view.loadUrl(targetUrl)
                }
            },
            onRelease = { view ->
                // WebView 不 destroy 会一直持有 Activity 上下文和渲染进程内存；
                // 反复进出网页线路会累积到 OOM
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.loadUrl("about:blank")
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.clearHistory()
                view.destroy()
                // 必须置空：生命周期观察者还持有这个引用，
                // 对已 destroy 的 WebView 调 pauseTimers() 会崩
                webView = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (noSignal) {
            // 半透明提示压在黑屏上：告诉用户大概率是比赛已结束，可换线路；点击可关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        onClickLabel = "关闭提示",
                        role = Role.Button,
                    ) { noSignal = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "该线路暂无信号",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = "比赛可能已结束，可切换其他线路",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
        if (blockedNavigation.isNotEmpty()) {
            // 广告跳转已被拦下：页面仍在原处，但必须告诉用户刚刚发生了什么，
            // 否则他只看到一个动不了的广告页，以为 App 卡死。点击即关闭。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        onClickLabel = "关闭提示",
                        role = Role.Button,
                    ) { blockedNavigation = "" },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "已拦下一个可疑跳转",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        // blockReason 只含域名，不带 query —— 签名凭证不能上屏。
                        text = blockedNavigation,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ---------- 死胡同恢复条：只上报"该不该显示 + 文案"，渲染交给浮层区 ----------
        // ⚠️ P1 真机回归修复（2026-11-01 雷电模拟器实测）：
        // 恢复条原来就画在这个位置，而本组件是视频区 `Box` 中**早于手势层**的兄弟子树 ——
        // 命中测试先落进 `fillMaxSize()` 的手势层那棵子树，于是
        // 「重新加载 / 换线路」两个按钮**点不动**：连点三次没有任何状态变化、
        // 没有一行日志，而同一时刻 `KEYCODE_BACK` 正常、CDP 里 URL 6 秒都没变。
        // 现在渲染挪到 `PlayerScreen` 的浮层区（`PlayerDeadEndBar`，声明在手势层之后），
        // 与顶栏/底栏同级 —— 那才是"点得动"的那一层。
        //
        // ⚠️ 文案的唯一来源仍是 [com.nbalive.app.ui.WebPageDeadEndPolicy]：
        // 探测与判定都在本组件里，文案跟着判定走，浮层区只渲染、不另写字面量
        //（既有 `WebPageDeadEndWiringTest` 的锚点就在 `fun WebSurface(` 体内）。
        LaunchedEffect(pageDeadEnd) {
            // 真机回归的可观测性（2026-11-01）：这条路径原来一行日志都没有，
            // 按钮点不动时无法区分"没点到"与"点了没生效"（两者的排查方向完全相反）。
            debugLog("WebSurface", "dead-end bar -> ${if (pageDeadEnd) "show" else "hide"}")
            onDeadEndBarChangedState.value(
                if (pageDeadEnd) {
                    WebDeadEndBar(
                        note = WebPageDeadEndPolicy.note(),
                        reloadLabel = WebPageDeadEndPolicy.reloadLabel(),
                        switchLabel = WebPageDeadEndPolicy.switchLabel(),
                    )
                } else {
                    // 页面自己恢复了 / 用户已经处理过 / 换线路了：把恢复条收掉
                    null
                },
            )
        }

        // 「重新加载」的**计数式请求**：按钮现在住在浮层区，而真正的导航逻辑
        // （targetUrl / reloadSeq / pageErrorAt）只有本组件知道，所以用计数把请求传进来
        //（布尔事件会在重组合并时被吞掉；同一取舍见 `PipState.expandRequests`）。
        //
        // ⚠️ H6（player.md 第 1 条）：**不能** `webView.reload()` ——
        // 走 PlayerPageResolver 捷径时当前页就是 `paps.html?id=<旧 encodedStr>`，
        // reload 只会用同一个 encodedStr 重发一次请求，解出来还是那条死地址。
        // 回到**原始壳页**重新解析（并让捷径重跑一次）才拿得到新的 encodedStr。
        // 判据抽在 WebPageDeadEndPolicy.targetOnReload（有单测）。
        LaunchedEffect(reloadRequest) {
            if (reloadRequest <= 0) return@LaunchedEffect
            // 同上：这条路径原来没有任何日志，真机上"点了没反应"时无法定位。
            // URL 走 redactUrl：签名（encodedStr / auth_key）不进 logcat，只留 host+path 便于定位。
            debugLog("WebSurface", "reload requested #$reloadRequest from ${redactUrl(targetUrl)}")
            pageDeadEnd = false
            pageErrorAt = 0L
            targetUrl = WebPageDeadEndPolicy.targetOnReload(url, targetUrl)
            reloadSeq++
            beginNavigation()
        }
    }
}


/**
 * 音频模式轮询链的"重投开关"（P3，`player.md` 第 3 条）。
 *
 * ## 为什么要有它
 *
 * 轮询改成"只在播放中重投"之后（与 `AudioFocusPolicy` 的注释一致），
 * 链会停在"用户手动暂停"那一态。而用户随后点播放时**没有任何生命周期事件**，
 * 于是这条"来电盖住通话"的最后保险再也不跑了 —— 修好一处又漏一处。
 * 播放监听（`onIsPlayingChanged`）通过这个开关把链重新接上。
 *
 * ## 为什么是普通对象而不是 Compose 快照状态
 *
 * 它只是两段 effect/监听器之间的一根线，写它不该引起任何重组
 * （播放/暂停每翻转一次就重组整页是没必要的）。
 */
private class FocusPollRearm {
    var rearm: (() -> Unit)? = null

    fun request() {
        rearm?.invoke()
    }
}

/**
 * 每次 `createDataSource()` 时**现查** Referer 的数据源工厂（P9 的安全一半）。
 *
 * ## 为什么需要它
 *
 * `OkHttpDataSource.Factory.setDefaultRequestProperties(headers)` 是在 **build 期**
 * 固定请求头的，所以 `VideoSurface` 只能把 Referer 提前算好、再用
 * `key(streamUrl, streamReferer)` 在换线路时把整个 ExoPlayer 重建一遍
 * （解码器/渲染器/缓冲全部重来，换线延迟与黑帧都比"换 MediaItem"长）。
 *
 * 这个包装把"请求头从哪来"变成**每次创建数据源时才求值**的提供者 ——
 * 完整版（不重建播放器 + Referer 每请求可变）可以直接复用它。
 *
 * ⚠️ 本轮**只落地这一半**：`key(streamUrl, streamReferer)` 原样保留，
 * 因此当前行为与改动前逐字等价（转发路径与请求头集合完全相同），
 * 不在最贵的播放链路上引入未经验证的生命周期改动。
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class RefererDataSourceFactory(
    private val refererProvider: () -> String?,
    private val userAgent: String,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val headers = buildMap {
            refererProvider()?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            put("Accept", "*/*")
        }
        return OkHttpDataSource.Factory(HttpClient.shared.client)
            .setDefaultRequestProperties(headers)
            .setUserAgent(userAgent)
            .createDataSource()
    }
}

/**
 * 首帧看门狗：直连线路多久没进入 READY 就判"卡死"并触发故障切换。
 *
 * ## 为什么从 15 秒提到 30 秒（review/01-P1-5）
 *
 * 15 秒对**弱网首帧**太紧：移动网络冷启动 + CDN 首次建连，实测经常要 20s+。
 * 那些线路是健康的，只是第一帧慢。误判的代价不是"换一条线"，而是
 * 这条线被记进失败记账、本次会话不再自动选中 —— 用户看到
 * "线路莫名被跳走且回不来"，而它本来能播。
 *
 * 30 秒仍在"用户愿意等"的范围内（这段时间界面有缓冲指示），
 * 同时比"永远等下去"好得多：真卡死的线路 30 秒后仍然换得掉。
 *
 * 配套改动：失败记账本身加了 TTL（`SourceFailureBook`，5 分钟自动解封），
 * 所以即使这次仍然误判，也不会把好线路永久拉黑。
 */
private const val FIRST_FRAME_WATCHDOG_MS: Long = 30_000

/**
 * 中途卡死判据的采样间隔（review/01-player.md P2-1）。
 *
 * 1 秒：卡死阈值是 30 秒，1 秒粒度足够准；再快只是白烧电（这条循环
 * 在整个播放期间都跑着），再慢则换线延迟可感知。
 */
private const val STALL_POLL_INTERVAL_MS: Long = 1_000

/**
 * 全屏浮层「显示后多久无操作自动收起」。
 *
 * 10 秒是可用性与画面干净的折中：
 * 太短（3 秒）用户还没找到要点的按钮就消失了；
 * 太长（20 秒+）按钮一直压在画面上，等于没隐藏。
 */
private const val FULLSCREEN_CONTROLS_TIMEOUT_MS: Long = 10_000

/**
 * 音频模式轮询间隔：来电"声音盖住通话"的兜底保险查一次 getMode() 的节奏。
 *
 * 取 1 秒：来电响铃到用户接起至少有几秒钟窗口，1 秒足够让直播在
 * 通话真正开始前静下来；再快就是白烧 CPU（这条轮询在每次小窗播放期间都跑着）。
 */
private const val FOCUS_MODE_POLL_INTERVAL_MS: Long = 1_000