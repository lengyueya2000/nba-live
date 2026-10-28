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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
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
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.AudioFocusPolicy
import com.nbalive.app.ui.PipState
import com.nbalive.app.ui.PlayerControls
import com.nbalive.app.ui.PlaybackHealth
import com.nbalive.app.ui.StalledPlayback
import com.nbalive.app.ui.hhmm
import com.nbalive.app.ui.ManualPipDecision
import com.nbalive.app.ui.ManualPipPolicy
import com.nbalive.app.ui.SystemBarsPolicy
import com.nbalive.app.ui.PipAspectReset
import com.nbalive.app.ui.WebMediaPauseScript
import com.nbalive.app.ui.WebNavigationPolicy
import com.nbalive.app.ui.PlayerLock
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.accentBrush
import com.nbalive.app.ui.theme.Space


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
     * 网页线路**疑似**播不出来（页面加载完成后 25 秒没看到任何媒体请求）。
     *
     * ⚠️ 与 [onPlaybackDead] 分开是因为判据性质不同（P0-1）：
     * 这条是**推断**，只用于显示提示 + 停自动进小窗，**不允许**收窗或关任务。
     */
    onPlaybackSuspect: () -> Unit = {},
    onStreamSniffed: (String, String?) -> Unit = { _, _ -> },
    /** 嗅探升级失败退回网页：不再弹"暂无信号"提示 */
    sniffFailed: Boolean = false,
    aspectMode: AspectMode = AspectMode.FIT,
    onAspectChange: (AspectMode) -> Unit = {},
    /** 重连：重新解析当前线路（直播地址带时效签名，过期后需要换新地址） */
    onReconnect: () -> Unit = {},
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
     * 锁定（防误触）：浮层全隐藏、手势停用。
     * 解锁入口唯一 —— 右侧中间的锁按钮（见 [onToggleLock]）。
     */
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
) {
    val videoOnly = fullscreen || inPip

    // "同时在播"右侧面板
    // ⚠️ rememberSaveable：进程被杀重建（用户切后台太久被回收）后，
    // 已打开的面板会被静默关掉 —— 用户看到"面板自己关了"。
    var showLivePanel by rememberSaveable { mutableStateOf(false) }
    // 播放源 / 播放线路 选择面板（不用 DropdownMenu：Popup 会被视频 SurfaceView 盖住）
    var picker by rememberSaveable { mutableStateOf<PickerKind?>(null) }
    // 视频播放器句柄：自定义控制栏要读播放状态、要能暂停/继续
    val videoHandle = remember(streamUrl) { VideoPlayerHandle() }

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
    var controlsTouchSeq by remember { mutableStateOf(0) }
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
    // ⚠️ 只在**进入全屏**时收起浮层，key 里不能有 streamUrl：
    // 原来的 `LaunchedEffect(fullscreen, streamUrl)` 在换线路时也会重跑，
    // 把 controlsVisible 重置成 false —— 用户刚点完"线路"胶囊，
    // 底部控制栏就自己消失了，得再点一次画面才回来。
    //
    // 直连地址解析出来时也不需要额外收起：用户是从非全屏进播放页的
    // （那时浮层本来就显示），点全屏才触发这个 effect。
    LaunchedEffect(fullscreen) {
        if (fullscreen) controlsVisible = false
    }
    // 打开任一选择面板时强制露出控制栏，避免面板还开着栏却消失了
    LaunchedEffect(picker, showLivePanel) {
        if (picker != null || showLivePanel) controlsVisible = true
    }
    // ⚠️ 面板**关闭**后要回补控制栏（review/01-player.md P2-8 的第二半）：
    // 只暂停计时还不够 —— 若面板开着期间计时器已经触发过（或用户手动收起了栏），
    // 关闭面板时必须有一步显式恢复，否则栏不会自己回来，用户以为 UI 卡住了。
    // 判据抽在 SystemBarsPolicy.shouldRestoreControlsOnPanelClose（有单测）。
    LaunchedEffect(picker, showLivePanel, fullscreen) {
        if (SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
                pickerOpen = picker != null,
                livePanelOpen = showLivePanel,
                fullscreen = fullscreen,
            )
        ) {
            controlsVisible = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (videoOnly) Modifier else Modifier.verticalScroll(rememberScrollState()))
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
                streamUrl != null -> key(streamUrl, streamReferer) {
                    VideoSurface(
                        streamUrl = streamUrl,
                        referer = streamReferer,
                        aspectMode = aspectMode,
                        handle = videoHandle,
                        onError = onPlaybackError,
                    )
                }
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

            // 手势层（必须压在视频之上、按钮之下）：
            //  - 左半屏上下滑调亮度，右半屏上下滑调音量
            //  - 全屏时单击画面显示/隐藏浮层（直连与网页两条路径一致）
            //  - 网页线路只在**发生垂直拖动**时才消费事件，
            //    单击依旧透传给源站播放器（否则网页线路根本没法调亮度音量）
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
                        icon = Icons.Filled.PictureInPictureAlt,
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
                        icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
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
                    icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
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
                                    icon = if (videoHandle.isPlaying) Icons.Filled.Pause
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
                                    onClick = {
                                        touchControls()
                                        picker = PickerKind.SOURCE
                                    },
                                )
                                PlayerChip(
                                    label = "线路 ${safeIndex + 1}/${signals.size}",
                                    onClick = {
                                        touchControls()
                                        picker = PickerKind.LINE
                                    },
                                )
                            }
                            PlayerChip(
                                label = "同时在播 ${liveMatches.count { it.id != match?.id }}",
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
        if (match != null) {
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

        // ---------- 信号切换 ----------
        if (signals.isNotEmpty()) {
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
                            .clickable { onSignalSelected(index) },
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

        // ---------- 其他直播 ----------
        // 只列真正在直播的场次：otherMatches 是"全部赛事"，
        // 直接拿来用会把未开赛/已结束的比赛摆进"其他直播"里
        val others = otherMatches
            .filter { it.id != match?.id && it.status == MatchStatus.LIVE }
            .take(4)
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
                    .clickable { showLivePanel = false },
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
    if (picker != null) {
        val kind = picker
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { picker = null },
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
                        .clickable { picker = null },
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
            if (kind == PickerKind.SOURCE) {
                val currentProvider = signals.getOrNull(selectedIndex)?.provider
                val providers = signals.map { it.provider }.distinct()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(providers, key = { it.name }) { p ->
                        PickerRow(
                            label = p.displayName,
                            sub = "${signals.count { it.provider == p }} 条线路",
                            selected = p == currentProvider,
                            onClick = {
                                picker = null
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
                                picker = null
                                onSignalSelected(index)
                            },
                        )
                    }
                }
            }
        }
    }
    } // end Box
}

/** 选择面板类型 */
private enum class PickerKind { SOURCE, LINE }

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
            .clickable(onClick = onClick)
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
                Icons.Filled.LiveTv,
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
                    .clickable(enabled = !refreshing, onClick = onRefresh),
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
                    .clickable(onClick = onClose),
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
            .clickable(onClick = onClick)
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
            .clickable(onClick = onClick),
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
    }

    /** 播放器上报真实视频尺寸 -> 画质档位 */
    fun onVideoSize(width: Int, height: Int) {
        qualityLabel = VideoResolution.labelOf(width, height)
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
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
            .clickable(onClick = onClick),
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
private fun PlayerChip(label: String, onClick: () -> Unit) {
    // ⚠️ 必须用 Box 包一层做居中，不能把 Modifier 直接挂在 Text 上：
    // `.heightIn(min = 40.dp)` 让胶囊至少 40dp 高，而 Text 按自身行高
    // 贴在容器顶部 —— 文字会明显偏上（实测：11sp 文字在 40dp 胶囊里
    // 上边只剩 2dp、下边空 20dp），用户反馈"按钮文字没居中"。
    // Box 的 contentAlignment = Center 让文字在两个方向都真正居中。
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.btn))
            .border(1.dp, Color.White.copy(alpha = 0.26f), RoundedCornerShape(Radius.btn))
            .clickable(onClick = onClick)
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
            .clickable(onClick = onClick)
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
 * 调用方用 `key(streamUrl, referer)` 包住本组件：Referer 是烧在数据源工厂里的，
 * 换线路必须整体重建播放器，否则会出现"换了线路但 CDN 还拿旧 Referer -> 403"。
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun VideoSurface(
    streamUrl: String,
    referer: String?,
    aspectMode: AspectMode,
    handle: VideoPlayerHandle,
    onError: () -> Unit,
) {
    val reportedRef = remember { mutableSetOf<String>() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val onErrorState = rememberUpdatedState(onError)
    // 是否曾经进入过 READY：直播流正常也会周期性重缓冲（STATE_BUFFERING），
    // 只看"当前不是 READY"会把健康线路误判成卡死。
    var reachedReady by remember(streamUrl) { mutableStateOf(false) }

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
        // 轮询兜底：见上方说明。只在"在播"时查。
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // ⚠️ 必须自我重投：postDelayed 只跑一次，不重投的话
        // 第一次之后这道保险就永远不生效了。
        lateinit var poll: Runnable
        poll = Runnable {
            val player = playerView?.player
            if (player != null &&
                AudioFocusPolicy.shouldPauseForAudioMode(
                    playing = player.playWhenReady,
                    mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL,
                )
            ) {
                pausedByFocus = true
                player.pause()
            }
            handler.postDelayed(poll, FOCUS_MODE_POLL_INTERVAL_MS)
        }
        handler.postDelayed(poll, FOCUS_MODE_POLL_INTERVAL_MS)
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
        if (PipAspectReset.shouldResetOnMatchSwitch()) {
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
    LaunchedEffect(streamUrl) {
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
    LaunchedEffect(streamUrl) {
        val tracker = StalledPlayback.Tracker()
        while (isActive) {
            delay(STALL_POLL_INTERVAL_MS)
            val player = playerView?.player ?: continue
            // 位置前进说明在播（有些播放器追帧时短暂停在 BUFFERING）
            tracker.onProgress(player.currentPosition, System.nanoTime() / 1_000_000L)
            val shouldSwitch = tracker.onTick(
                reachedReady = reachedReady,
                playbackState = player.playbackState,
                nowMs = System.nanoTime() / 1_000_000L,
            )
            if (shouldSwitch && reportedRef.add(streamUrl)) {
                onErrorState.value()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val headers = buildMap {
                referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                put("Accept", "*/*")
            }
            val dataSourceFactory = OkHttpDataSource.Factory(HttpClient.shared.client)
                .setDefaultRequestProperties(headers)
                .setUserAgent(HttpClient.USER_AGENT)

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
            view.resizeMode = aspectMode.resizeMode
            val player = view.player
            if (player != null) {
                val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (current != streamUrl) {
                    player.setMediaItem(MediaItem.fromUri(streamUrl))
                    player.prepare()
                    player.playWhenReady = true
                }
            }
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
     * 网页线路**疑似**播不出来时上报（25 秒内没嗅探到任何流）。
     *
     * 这是**推断**：源站用 MSE/`blob:` 播放、m3u8 地址不带 `.m3u8` 字面量、
     * 弱网首帧超过 25 秒，都会嗅探不到但画面其实正常。
     * 所以走独立回调，上层只用于显示提示 + 停自动进小窗，**不**收窗、不关任务。
     */
    onPlaybackSuspect: () -> Unit = {},
) {
    val onStreamFoundState = rememberUpdatedState(onStreamFound)
    val onPlaybackDeadState = rememberUpdatedState(onPlaybackDead)
    val onPlaybackSuspectState = rememberUpdatedState(onPlaybackSuspect)
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 是否观察到过任何"媒体请求"（见 [PlaybackHealth.isMediaRequest]）。
    // ⚠️ 必须是 AtomicBoolean：shouldInterceptRequest 在 WebView 后台线程回调，
    // 写 Compose 的 mutableStateOf 会跨线程触碰快照系统。
    val sawMediaRequest = remember(url) { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 页面是否已完成首次加载：嗅探计时必须在它**之后**才开始。
    // 否则计时从 loadUrl 那一刻起跑，源站播放页常有 2~3 跳重定向，
    // 光导航就吃掉十几秒，留给播放器的时间根本不够 —— 正常线路也会被判疑似。
    var pageFinishedAt by remember(url) { mutableStateOf(0L) }
    // 被守卫拦下的跳转说明（04-P1-3/4）。源站广告把播放页导航走时，
    // 这条路径不报 ExoPlayer 错误、也不报 onReceivedError，界面必须自己说话，
    // 否则用户只看到一个动不了的广告页，以为 App 卡死。
    var blockedNavigation by remember(url) { mutableStateOf("") }

    // 比赛已结束时源站页面是一片黑且永远不会出流：
    // 页面加载完成后 25 秒内没看到任何媒体请求，就给用户明确提示，而不是干等黑屏。
    // 嗅探成功后本组件会被 VideoSurface 替换、协程自动取消，无需手动复位。
    //
    // ⚠️ suppressHint 只抑制**用户可见的提示**，不能连判定一起跳过：
    // 嗅探过但直连失败、退回网页播放时 suppressHint=true，
    // 而那恰恰是最需要留痕的情况 —— 网页里的源站播放器会自己画一句
    // 「网络错误 / 请试试 刷新」（这句不在 App 代码里，读不到）。
    //
    // ⚠️ 判死分级（P0-1）：这一条只报**疑似**（onPlaybackSuspect），不再报死亡。
    // 它本质是推断，误判代价原本却是"移除整个任务"，两者不成比例。
    var noSignal by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, pageFinishedAt, suppressHint) {
        // 页面还没加载完不起跑：source 站点常有几跳重定向
        if (pageFinishedAt == 0L) return@LaunchedEffect
        delay(25_000)
        // 已经看到媒体请求 = 播放器在拉数据了（哪怕地址不带 .m3u8 字面量），
        // 这不是"播不出来"，不再报疑似。
        if (sawMediaRequest.get()) return@LaunchedEffect
        if (!suppressHint) noSignal = true
        onPlaybackSuspectState.value()
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
                            // 所以"看到过媒体请求"用 AtomicBoolean 记，判定处再读。
                            if (PlaybackHealth.isMediaRequest(u)) sawMediaRequest.set(true)
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
                                // 真出问题由 25 秒的「疑似」计时器兜住，不在这里抢判死亡。
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
                            pageFinishedAt = android.os.SystemClock.elapsedRealtime()
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
                    loadUrl(url)
                    tag = url
                    webView = this
                }
            },
            update = { view ->
                // 注意不能比较 view.url：页面会自行跳转（sm.html -> 内层播放页），
                // 那样每次重组都会重新 loadUrl，把正在播的流打断。用 tag 记住请求的 url。
                if (view.tag != url) {
                    view.tag = url
                    view.loadUrl(url)
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
                    .clickable { noSignal = false },
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
                    .clickable { blockedNavigation = "" },
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