package com.nbalive.app.ui

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.BuildConfig
import com.nbalive.app.MainActivity
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.ui.components.LiveDotPhase
import com.nbalive.app.ui.components.SquareIconButton
import com.nbalive.app.ui.screens.LiveNowScreen
import com.nbalive.app.ui.screens.MatchDetailScreen
import com.nbalive.app.ui.screens.MatchListScreen
import com.nbalive.app.ui.screens.PlayerScreen
import com.nbalive.app.ui.screens.ProfileScreen
import com.nbalive.app.ui.screens.SearchScreen
import com.nbalive.app.ui.theme.GradLive
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * 列表状态老化的轮询间隔（P1-1）。
 *
 * 只做纯内存重算（`MainViewModel.ageListStatuses`），不发网络请求，
 * 因此 60 秒既能让「比赛已结束」在一分钟内从「直播中」纠正过来，
 * 又不会对源站产生任何额外压力。
 */
private const val LIST_STATUS_AGE_INTERVAL_MS = 60_000L


private enum class Tab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    MATCHES("赛事", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    LIVE("直播", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    PROFILE("我的", Icons.Filled.Person, Icons.Outlined.Person),
}

@Composable
fun NbaLiveApp(
    viewModel: MainViewModel,
    /**
     * 深色模式**落盘**回调（review/03-ui.md P2-17）。
     *
     * 持久化需要 Context，而 Context 不该漏进 UI 层（也无法在纯 Compose 单测里验证），
     * 所以由平台层 MainActivity 实现并在调用处注入；这里只传值。
     */
    onDarkModeChange: (Boolean) -> Unit,
    /** 画面模式落盘回调，同 [onDarkModeChange] */
    onAspectChange: (AspectMode) -> Unit,
) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // ⚠️ visibleMatches / counts **不需要**再包一层 remember（review/03-ui.md P2-9）：
    // MainViewModel 里它们已经是 data class 的 `val`（构造时求值一次），
    // 原来那段注释描述的"每次重组都新建 List/Map"是 getter 时代的旧问题，
    // 已经在源头修掉 —— 在这里再 remember 只多持一份引用，
    // 还会让后来者以为"这里必须 remember，否则会分配"。
    val visibleMatches = listState.visibleMatches
    val leagueCounts = listState.counts
    // liveMatches 是真需要的：state 里没有预过滤过的 LIVE 列表
    val liveMatches = remember(listState.matches) {
        listState.matches.filter { it.status == MatchStatus.LIVE }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.MATCHES) }
    // 搜索页：全屏覆盖层，不占用路由，返回键先关它
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? MainActivity }
    val versionName = remember {
        // BuildConfig.VERSION_NAME：PackageManager.getPackageInfo(pkg, 0) 在 API 33+ 已废弃
        BuildConfig.VERSION_NAME
    }

    // ---------- 播放偏好：画面模式 ----------
    // 屏幕常亮不在这里：它是产品固定行为（默认开启、无开关），
    // 由播放页的 PlayerView / WebView 自己设 keepScreenOn，
    // 不需要 Compose 状态，也不该出现在设置界面里。
    val aspectMode by PlayerPrefs.aspect.collectAsStateWithLifecycle()

    // ---------- 深色模式 ----------
    // 与 MainActivity 调用**同一个**判定函数（AppPrefs.resolveDarkMode）：
    // 设计是深色优先，没手动设置过（null）就按深色算（review/03-ui.md P2-16）。
    // 两边各写一遍 `?: true` 时，将来规则一变、漏改一处就会出现
    // 「主题是浅色、状态栏图标按深色画」的错配。
    val darkPref by AppPrefs.darkMode.collectAsStateWithLifecycle()
    val darkMode = resolveDarkMode(darkPref)

    // ---------- 全屏 / 画中画 ----------
    // saveable：进程被杀后重建（不要在后台）再回来，全屏状态不该丢。
    // ⚠️ 早先的注释写的是"字体缩放/语言变化会重建 Activity"——
    // 那不对：Manifest 的 configChanges 已经包含 fontScale|locale，
    // 这两种变化不会重建 Activity。真正需要 saveable 的是进程被杀重建。
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // 锁定（防误触）：只认解锁键解锁，所以返回键在锁定时不该退出播放页。
    //
    // ⚠️ 锁绑定到**比赛 id**，而不是一个裸布尔：
    // 「同时在播」面板切到另一场时 route 一直是 PLAYER，
    // 裸布尔会跟着漏到新的一场（用户看着新比赛却是锁着的、浮层全没了）。
    // 换成"锁住哪一场"，切场时自然失效。
    var lockedMatchId by remember { mutableStateOf<String?>(null) }
    val playerLocked = PlayerLock.isLocked(lockedMatchId, playerState.match?.id)
    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    val videoOnly = route == Route.PLAYER && (fullscreen || inPip)

    // 直连 HLS 与 WebView 两条播放路径都算"正在看"
    //
    // ⚠️ 播放报错时不算：实测回归（v1.2.8）—— 播放页显示「网络错误」、
    // 画面完全是死的，按 Home 仍会进小窗（`webFallbackUrl` 还带着地址，
    // hasStream 判定拦不住），小窗里只剩一句点不动的错误提示。
    // 用户既看不了球，也没有任何办法操作那个小窗。
    // "有地址"不等于"在播"：地址拿到的瞬间只是"该试试"。
    //
    // ⚠️ 这里判的是 playerState.playbackDead（播放器自己报的死），
    // **不是** errorMessage：错误文案还承担"已自动切换线路"这类过渡提示，
    // 那种情况切换完照样能播，不能把小窗收掉。
    //
    // ⚠️ P0-1：疑似异常（playbackSuspect，来自嗅探超时这类**推断**）
    // 也要挡住自动进小窗 —— 但只挡"新按 Home 时自动弹窗"，
    // **不**收掉已经在播的小窗。分级与处置权限集中在 [PlaybackHealth]。
    // 当前健康等级：事实优先于推断
    val playbackLevel = PlaybackHealth.levelOf(
        sniffTimedOut = playerState.playbackSuspect,
        confirmedFailure = playerState.playbackDead,
    )
    val playingNow = (playerState.streamUrl != null || playerState.webFallbackUrl != null) &&
        !PlaybackHealth.blocksAutoEnterPip(playbackLevel)
    DisposableEffect(route, playingNow) {
        // 只有**真的在看视频**时才允许「按 Home 自动进小窗」。
        // 判定集中在 PipState.shouldAutoEnterPip（可单测），
        // 早先只看 route == PLAYER，导致解析中/无信号/比赛已结束时
        // 按 Home 也会弹出一块空小窗。
        PipState.setPlayerActive(
            PipState.shouldAutoEnterPip(
                isPlayerRoute = route == Route.PLAYER,
                hasStream = playingNow,
            ),
        )
        onDispose { PipState.setPlayerActive(false) }
    }
    // 播放状态变化时把系统级「自动进小窗」的开关复位：
    // setAutoEnterEnabled(true) 是**粘性**的（见 MainActivity.syncAutoEnterEnabled），
    // 一旦设上，系统在按 Home 时自己进小窗，不经过上面那道判断。
    //
    // ⚠️ 收小窗只在等级为「确认失败」时发生（P0-1）。
    // 处置权限由 PlaybackHealth.pipDisposition 决定，这里不自己判断，
    // 避免"疑似异常"这类推断拿走不可逆的处置。
    LaunchedEffect(playingNow, playbackLevel) {
        activity?.syncAutoEnterEnabled()
        activity?.finishPipIfBroken(playbackLevel)
    }
    // 离开播放页自动退出全屏，避免下次进来是横屏
    // 同时解除锁定：锁是"看当前这场"的状态，换场/退出就该恢复
    LaunchedEffect(route) {
        if (route != Route.PLAYER) {
            fullscreen = false
            lockedMatchId = null
        }
    }
    // 列表状态随时间老化（P1-1）。
    //
    // 状态原来只在 loadMatches 结束时算一次，之后永不重算 ——
    // 用户不手动刷新，早上 10:00 开赛的比赛到晚上还挂着「直播中」，
    // 点进去是"网络错误"。这是那个反馈的结构性根因：
    // 之前修的是"校正算错了"，这层是"不再算第二次"。
    //
    // ⚠️ 这是**纯内存**重算，零网络请求 —— 不违反"不轮询源站"的取舍。
    // ⚠️ 只在列表可见时跑：进详情页/播放页就停，切后台时组合被移出也会停。
    LaunchedEffect(route, tab) {
        if (route != Route.LIST || tab == Tab.PROFILE) return@LaunchedEffect
        while (true) {
            viewModel.ageListStatuses()
            delay(LIST_STATUS_AGE_INTERVAL_MS)
        }
    }
    // 全屏时横屏 + 隐藏系统栏
    // ⚠️ 必须把 inPip 传进去（P2-7）：进小窗时 enabled 从 true 变 false，
    // 但方向不能复位 —— 否则小窗窗口跟着设备方向乱转。
    FullscreenSystemEffect(enabled = fullscreen && !inPip, inPip = inPip)

    // 系统返回键：搜索页 > 全屏 > 路由
    // ⚠️ 画中画时要放行：部分系统版本在关小窗时会把返回键派发给界面，
    // 被这里吃掉就会变成"先退详情页、再退列表"，小窗关不掉。
    // ⚠️ 锁定时返回键不退出播放页：用户明确要求「只有点解锁键才会解锁」，
    // 锁定期间返回键被吃掉（而不是退出播放页），否则用户会被困在锁定态。
    //
    // ⚠️ 判据抽在 BackPolicy（有单测）。原来的 enabled 条件不含**标签页**：
    // 用户切到「直播」/「我的」后按返回，条件全为假 → 不接管 →
    // 系统默认行为 = 退出 Activity（用户只是想回「赛事」标签）。
    val onDefaultTab = tab == Tab.MATCHES
    BackHandler(
        enabled = !inPip && BackPolicy.shouldIntercept(
            searchOpen = searchOpen,
            route = route,
            fullscreen = fullscreen,
            onDefaultTab = onDefaultTab,
        ),
    ) {
        when (
            BackPolicy.action(
                searchOpen = searchOpen,
                route = route,
                fullscreen = fullscreen,
                onDefaultTab = onDefaultTab,
                // 锁定语义只有一份实现：PlayerLock.backExitsPlayer
                backExitsPlayer = PlayerLock.backExitsPlayer(playerLocked),
            )
        ) {
            BackAction.CloseSearch -> searchOpen = false
            // 锁定态：吃掉这次返回，什么都不做
            BackAction.Swallow -> Unit
            BackAction.ExitFullscreen -> fullscreen = false
            BackAction.LeaveRoute -> viewModel.back()
            // 非默认标签：回赛事标签，而不是退出 App
            BackAction.ResetTab -> tab = Tab.MATCHES
            // 理论上不该走到（enabled 已经挡住了），保留穷尽分支
            BackAction.None -> Unit
        }
    }

    // 播放降级提示：只在播放页呈现，避免用户已经返回列表还弹出"直连线路不可用"
    // ⚠️ key 用 errorId（每次报错自增），不能只拿文案当 key：
    // 同样的文案连着报两次时，StateFlow 会把相等值合并掉，
    // LaunchedEffect 不重启，第二次就再也弹不出来了。
    LaunchedEffect(route, playerState.errorId) {
        if (route == Route.PLAYER) {
            playerState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!videoOnly) {
                when (route) {
                    Route.LIST -> BrandTopBar(
                        darkMode = darkMode,
                        onSearch = { searchOpen = true },
                        onToggleTheme = { onDarkModeChange(!darkMode) },
                        onRefresh = viewModel::refresh,
                    )
                    Route.DETAIL -> SubPageTopBar(
                        title = "赛事详情",
                        onBack = viewModel::back,
                        moreItems = listOf("刷新线路" to viewModel::refreshSignals),
                    )
                    Route.PLAYER -> SubPageTopBar(
                        title = playerState.match?.let { "${it.homeTeam} VS ${it.awayTeam}" } ?: "播放",
                        onBack = viewModel::back,
                    )
                }
            }
        },
        bottomBar = {
            if (route != Route.PLAYER && !videoOnly) {
                SportBottomBar(
                    selected = tab,
                    onSelect = {
                        tab = it
                        // 在详情页点底部标签 -> 先回列表，否则标签切了却没反应
                        if (route != Route.LIST) viewModel.back()
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (videoOnly) Modifier else Modifier.padding(padding)),
        ) {
            when (route) {
                // ⚠️ 列表/直播页的红点共用**一个**呼吸相位（review/03-ui.md P2-13）：
                // 原来每颗 LiveDot 各自 rememberInfiniteTransition，
                // 一屏十几场直播就是十几份独立无限动画、永不停止。
                // 包在这一层后整屏只有一份动画，且所有红点相位一致。
                Route.LIST -> LiveDotPhase {
                    when (tab) {
                    Tab.MATCHES -> MatchListScreen(
                        matches = visibleMatches,
                        loading = listState.loading,
                        refreshing = listState.refreshing,
                        error = listState.error,
                        // 单源失败时把"少了什么"告诉用户（review/02-data.md P1-2）：
                        // 数据层早就算好了 failedProviders，此前没有任何 UI 读它，
                        // 用户只能看到「场次变少了」，无法区分源挂了与今天没比赛。
                        partialError = listState.partialError,
                        selectedLeague = listState.selectedLeague,
                        counts = leagueCounts,
                        onLeagueSelected = viewModel::selectLeague,
                        // 点比赛直接全屏自动播放，不再先跳详情页选线路
                        onMatchClick = { m ->
                            fullscreen = true
                            viewModel.playMatch(m)
                        },
                        onRetry = viewModel::refresh,
                    )

                    // 直播页：只列正在直播的，点一下直接看。
                    // ⚠️ 传**已经算好的** liveMatches，别把完整列表丢进去让它再过滤一遍
                    // （review/03-ui.md P2-10：同一份过滤原来两处各写一份）
                    Tab.LIVE -> LiveNowScreen(
                        liveMatches = liveMatches,
                        loading = listState.loading,
                        error = listState.error,
                        refreshing = listState.refreshing,
                        onRetry = viewModel::refresh,
                        onMatchClick = { m ->
                            fullscreen = true
                            viewModel.playMatch(m)
                        },
                    )

                    // 我的：真实生效的播放/外观设置
                    Tab.PROFILE -> ProfileScreen(
                        aspectMode = aspectMode,
                        darkMode = darkMode,
                        versionName = versionName,
                        onAspectChange = onAspectChange,
                        onDarkModeChange = onDarkModeChange,
                    )
                    }
                }

                Route.DETAIL -> when (
                    val state = MissingMatchPolicy.forDetail(detailState.match != null)
                ) {
                    // ⚠️ 缺 match 时不能 `return@Box` 静默白屏（review/03-ui.md P2-5）：
                    // 界面上什么都没有，没有提示也没有返回入口。
                    // 进程被系统回收后恢复路由时最容易触发。
                    is MissingMatchState.ShowPlaceholder -> MissingMatchPlaceholder(
                        message = state.message,
                        onBack = viewModel::back,
                    )
                    MissingMatchState.Render -> MatchDetailScreen(
                        match = detailState.match!!,
                        signals = detailState.signals,
                        loadingSignals = detailState.loadingSignals,
                        onSignalClick = viewModel::openPlayer,
                        // 拉线路失败必须让用户看见并能重试：
                        // 原来只传 loading，失败就被渲染成"该赛事暂无可用线路"
                        error = detailState.error,
                        onRetry = viewModel::refreshSignals,
                    )
                }

                Route.PLAYER -> PlayerScreen(
                    match = playerState.match,
                    signals = playerState.signals,
                    selectedIndex = playerState.selectedIndex,
                    resolving = playerState.resolving,
                    streamUrl = playerState.streamUrl,
                    streamReferer = playerState.streamReferer,
                    webFallbackUrl = playerState.webFallbackUrl,
                    // 非全屏时常驻提示卡（全屏下卡片不渲染，由 Snackbar 负责）
                    errorMessage = playerState.errorMessage,
                    onSignalSelected = viewModel::selectSignal,
                    otherMatches = listState.matches,
                    onOtherMatchClick = { m ->
                        viewModel.playMatch(m)
                    },
                    onPlaybackError = viewModel::onPlaybackFailed,
                    onPlaybackDead = viewModel::onPlaybackDead,
                    onPlaybackSuspect = viewModel::onPlaybackSuspect,
                    onStreamSniffed = viewModel::onStreamSniffed,
                    sniffFailed = playerState.failedSniffUrl != null,
                    aspectMode = aspectMode,
                    onAspectChange = onAspectChange,
                    onReconnect = viewModel::reconnect,
                    liveMatches = liveMatches,
                    onLiveMatchClick = viewModel::playMatch,
                    refreshing = listState.busy,
                    onRefreshLive = { viewModel.refresh() },
                    fullscreen = fullscreen,
                    inPip = inPip,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onEnterPip = { activity?.enterPip() },
                    // 点画中画但还没流可播：给一句提示（P2-6）。
                    // 复用播放页已有的错误提示通道（snackbar 由 errorMessage 驱动）。
                    onPipBlocked = { message -> viewModel.showPlayerNotice(message) },
                    locked = playerLocked,
                    onToggleLock = {
                        val id = playerState.match?.id
                        // 锁/解锁都作用于当前这场
                        lockedMatchId = if (playerLocked) null else id
                    },
                )
            }
        }
    }

    // ---------- 搜索页覆盖层 ----------
    if (searchOpen) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            SearchScreen(
                matches = listState.matches,
                onClose = { searchOpen = false },
                onMatchClick = { m ->
                    searchOpen = false
                    fullscreen = true
                    viewModel.playMatch(m)
                },
            )
        }
    }
    } // end Box
}

/**
 * 缺数据占位（review/03-ui.md P2-5）。
 *
 * 原来 `Route.DETAIL -> MatchDetailScreen(match = detailState.match ?: return@Box)`
 * 拿不到 match 就直接 return —— 界面纯白屏，没有提示、没有返回入口。
 * 进程被系统回收后恢复路由时最容易触发。
 *
 * 这里给一句能解释问题的提示 + 一个明确的返回按钮，
 * 而不是让用户对着白屏不知道该做什么。
 */
@Composable
private fun MissingMatchPlaceholder(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.lg))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.lg))
        Button(onClick = onBack) { Text("返回列表") }
    }
}

/** 品牌顶栏：渐变标记 + 标题 + 副标题 + 右侧操作 */
@Composable
private fun BrandTopBar(
    darkMode: Boolean,
    onSearch: () -> Unit,
    onToggleTheme: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Space.lg)
            .padding(top = Space.md, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GradLive),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SportsBasketball,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                text = "NBA Live",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = "SPORTS CENTER",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                letterSpacing = 2.4.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        // 顶栏按钮统一用共享的 SquareIconButton（review/03-ui.md P2-22）：
        // 原来这里有一份私有 TopIconButton，与二级页/搜索页各写一份且已经分叉
        SquareIconButton(
            icon = Icons.Filled.Refresh,
            contentDescription = "刷新",
            onClick = onRefresh,
        )
        Spacer(Modifier.width(Space.sm))
        SquareIconButton(
            icon = Icons.Filled.Search,
            contentDescription = "搜索",
            onClick = onSearch,
        )
        Spacer(Modifier.width(Space.sm))
        SquareIconButton(
            icon = if (darkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = if (darkMode) "浅色模式" else "深色模式",
            onClick = onToggleTheme,
        )
    }
}

// TopIconButton 已删：顶栏/二级页的方形图标按钮统一到
// ui/components/Common.kt 的 SquareIconButton（review/03-ui.md P2-22）。
// 这里原来那份与 SubPageTopBar、SearchScreen 的两份实现参数已经分叉
// （40dp / 40dp / 48dp），同一个"顶栏按钮"在不同页面长得不一样。

/**
 * 二级页顶栏左右两侧的**布局**边长（review/03-ui.md P2-35）。
 *
 * 左侧返回键的**视觉**尺寸是 40dp，但 `minimumInteractiveComponentSize()`
 * 会把它的布局尺寸撑到 48dp（Material 的最小触摸目标）。
 * 右侧「更多」按钮/占位必须用同一个值：两侧的 `Spacer(weight = 1f)`
 * 分的是剩余空间，一边 48 一边 40 时标题中心会相对屏幕中心左偏
 * (48 - 40) / 2 = 4dp —— 而"标题居中"正是这个顶栏的显式意图。
 */
internal val SubPageBarSideSlot = 48.dp

/**
 * 二级页顶栏。
 *
 * [moreItems] 为空时不渲染"更多"按钮（用等宽占位保持标题居中），
 * 避免出现点了没反应的死按钮。
 */
@Composable
private fun SubPageTopBar(
    title: String,
    onBack: () -> Unit,
    moreItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Space.lg)
            .padding(top = Space.md, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBackIos,
            contentDescription = "返回",
            onClick = onBack,
            iconSize = 16.dp,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.md),
        )
        Spacer(Modifier.weight(1f))
        if (moreItems.isEmpty()) {
            // 等宽占位：宽度必须与左侧按钮的**布局**尺寸一致（都是 48dp，
            // 见 SubPageBarSideSlot），写 40dp 会让标题中心左偏 4dp（P2-35）
            Spacer(Modifier.size(SubPageBarSideSlot))
        } else {
            Box {
                SquareIconButton(
                    icon = Icons.Filled.MoreHoriz,
                    contentDescription = "更多",
                    onClick = { menuOpen = true },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    moreItems.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                action()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 底部导航 */
@Composable
private fun SportBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            // ⚠️ selectableGroup() 是必须的（review/03-ui.md P2-24）：
            // 三个标签各自是 selectable 节点，但不声明"这是一组"时，
            // TalkBack 逐个读成独立按钮，用户不知道它们是互斥导航、
            // 也不知道"1/3、已选中"这类位置信息。
            .selectableGroup()
            .padding(horizontal = Space.sm, vertical = Space.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { item ->
            val active = item == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sheet))
                    // ⚠️ 必须用 selectable 而不是 clickable：
                    // 只有 clickable(role=Role.Tab) 时 TalkBack 读不出"已选中"，
                    // 视障用户无法判断当前在哪个标签页。
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(item) },
                    )
                    // 触摸目标高度不小于 48dp
                    .heightIn(min = 48.dp)
                    .padding(vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 26.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            // 选中底色跟随主题主色，不再写死红色
                            if (active) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (active) item.filled else item.outlined,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.label,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.4.sp,
                )
            }
        }
    }
}
/** 从任意 Context 找到宿主 Activity：已抽到 ui/ContextExt.kt 共用（review/03-ui.md P2-21）。
 *  这里原来有一份私有实现，与 theme/Theme.kt 的直接强转是两套不同的写法。 */

/**
 * 全屏时切横屏并隐藏系统栏；退出时恢复。
 * 只在真正的全屏（非画中画）下生效。
 */
@Composable
private fun FullscreenSystemEffect(enabled: Boolean, inPip: Boolean) {
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
