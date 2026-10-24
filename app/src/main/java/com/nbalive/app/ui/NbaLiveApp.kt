package com.nbalive.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.nbalive.app.ui.screens.LiveNowScreen
import com.nbalive.app.ui.screens.MatchDetailScreen
import com.nbalive.app.ui.screens.MatchListScreen
import com.nbalive.app.ui.screens.PlayerScreen
import com.nbalive.app.ui.screens.ProfileScreen
import com.nbalive.app.ui.screens.SearchScreen
import com.nbalive.app.ui.theme.GradLive
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space


private enum class Tab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    MATCHES("赛事", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    LIVE("直播", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    PROFILE("我的", Icons.Filled.Person, Icons.Outlined.Person),
}

@Composable
fun NbaLiveApp(viewModel: MainViewModel) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // ⚠️ 这些原来是 `listState.visibleMatches` / `listState.counts` 这类
    // **getter**：每次重组都新建 List/Map 实例，下游的 remember(key) 永远命中不了，
    // 等于每次重组都全量过滤 + groupByDay。提到这里按输入 remember 住。
    val visibleMatches = remember(listState.matches, listState.selectedLeague) {
        listState.visibleMatches
    }
    val leagueCounts = remember(listState.matches) { listState.counts }
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

    // ---------- 播放偏好：画面模式 / 屏幕常亮 ----------
    val aspectMode by PlayerPrefs.aspect.collectAsStateWithLifecycle()
    val keepScreenOn by PlayerPrefs.keepScreenOn.collectAsStateWithLifecycle()

    // ---------- 深色模式 ----------
    // 与 MainActivity 保持一致：设计是深色优先，没手动设置过就按深色算
    val darkPref by AppPrefs.darkMode.collectAsStateWithLifecycle()
    val darkMode = darkPref ?: true

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
    val playingNow = playerState.streamUrl != null || playerState.webFallbackUrl != null
    DisposableEffect(route, playingNow) {
        // ⚠️ 进了播放页就算"正在看"，**包括解析中的那几秒**：
        // 解析时 streamUrl/webFallbackUrl 都还是 null，
        // 如果按 playingNow 判定，用户点进播放页后马上按 Home 就不会进小窗，
        // 表现为"小窗坏了"。
        PipState.playerActive = route == Route.PLAYER
        onDispose { PipState.playerActive = false }
    }
    // 观看直播期间保持屏幕常亮，避免球赛看着看着自动熄屏
    KeepScreenOnEffect(enabled = route == Route.PLAYER && playingNow && keepScreenOn)
    // 离开播放页自动退出全屏，避免下次进来是横屏
    // 同时解除锁定：锁是"看当前这场"的状态，换场/退出就该恢复
    LaunchedEffect(route) {
        if (route != Route.PLAYER) {
            fullscreen = false
            lockedMatchId = null
        }
    }
    // 全屏时横屏 + 隐藏系统栏
    FullscreenSystemEffect(enabled = fullscreen && !inPip)

    // 系统返回键：搜索页 > 全屏 > 路由
    // ⚠️ 画中画时要放行：部分系统版本在关小窗时会把返回键派发给界面，
    // 被这里吃掉就会变成"先退详情页、再退列表"，小窗关不掉。
    // ⚠️ 锁定时返回键不退出播放页：用户明确要求「只有点解锁键才会解锁」，
    // 锁定期间返回键被吃掉（而不是退出播放页），否则用户会被困在锁定态。
    BackHandler(enabled = !inPip && (searchOpen || route != Route.LIST || fullscreen)) {
        when {
            searchOpen -> searchOpen = false
            // 锁定中：吃掉这次返回，什么都不做
            route == Route.PLAYER && !PlayerLock.backExitsPlayer(playerLocked) -> Unit
            fullscreen -> fullscreen = false
            else -> viewModel.back()
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
                        onToggleTheme = { AppPrefs.setDarkMode(context, !darkMode) },
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
                Route.LIST -> when (tab) {
                    Tab.MATCHES -> MatchListScreen(
                        matches = visibleMatches,
                        loading = listState.loading,
                        refreshing = listState.refreshing,
                        error = listState.error,
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

                    // 直播页：只列正在直播的，点一下直接看
                    Tab.LIVE -> LiveNowScreen(
                        matches = listState.matches,
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
                        keepScreenOn = keepScreenOn,
                        darkMode = darkMode,
                        versionName = versionName,
                        onAspectChange = { PlayerPrefs.setAspect(context, it) },
                        onKeepScreenOnChange = { PlayerPrefs.setKeepScreenOn(context, it) },
                        onDarkModeChange = { AppPrefs.setDarkMode(context, it) },
                    )
                }

                Route.DETAIL -> MatchDetailScreen(
                    match = detailState.match ?: return@Box,
                    signals = detailState.signals,
                    loadingSignals = detailState.loadingSignals,
                    onSignalClick = viewModel::openPlayer,
                    // 拉线路失败必须让用户看见并能重试：
                    // 原来只传 loading，失败就被渲染成"该赛事暂无可用线路"
                    error = detailState.error,
                    onRetry = viewModel::refreshSignals,
                )

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
                    onStreamSniffed = viewModel::onStreamSniffed,
                    sniffFailed = playerState.failedSniffUrl != null,
                    aspectMode = aspectMode,
                    keepScreenOn = keepScreenOn,
                    onAspectChange = { PlayerPrefs.setAspect(context, it) },
                    onKeepScreenOnChange = { PlayerPrefs.setKeepScreenOn(context, it) },
                    onReconnect = viewModel::reconnect,
                    liveMatches = liveMatches,
                    onLiveMatchClick = viewModel::playMatch,
                    refreshing = listState.busy,
                    onRefreshLive = { viewModel.refresh() },
                    fullscreen = fullscreen,
                    inPip = inPip,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onEnterPip = { activity?.enterPip() },
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
        TopIconButton(icon = Icons.Filled.Refresh, label = "刷新", onClick = onRefresh)
        Spacer(Modifier.width(Space.sm))
        TopIconButton(icon = Icons.Filled.Search, label = "搜索", onClick = onSearch)
        Spacer(Modifier.width(Space.sm))
        TopIconButton(
            icon = if (darkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            label = if (darkMode) "浅色模式" else "深色模式",
            onClick = onToggleTheme,
        )
    }
}

@Composable
private fun TopIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            // 触摸目标不小于 48dp（图标视觉尺寸保持 19dp 不变）
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(RoundedCornerShape(Radius.btn))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
        Box(
            modifier = Modifier
                // 触摸目标不小于 48dp
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(RoundedCornerShape(Radius.btn))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBackIos,
                contentDescription = "返回",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
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
            // 等宽占位：没有更多操作时标题仍然居中
            Spacer(Modifier.size(40.dp))
        } else {
            Box {
                Box(
                    modifier = Modifier
                        // 触摸目标不小于 48dp
                        .minimumInteractiveComponentSize()
                        .size(40.dp)
                        .clip(RoundedCornerShape(Radius.btn))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = "更多",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
/** 从任意 Context 找到宿主 Activity */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 观看直播期间保持屏幕常亮；退出播放/停止播放时恢复系统熄屏策略。
 */
@Composable
private fun KeepScreenOnEffect(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(enabled, activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * 全屏时切横屏并隐藏系统栏；退出时恢复。
 * 只在真正的全屏（非画中画）下生效。
 */
@Composable
private fun FullscreenSystemEffect(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(enabled, activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
