package com.nbalive.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.MainActivity
import com.nbalive.app.ui.screens.MatchDetailScreen
import com.nbalive.app.ui.screens.MatchListScreen
import com.nbalive.app.ui.screens.PlayerScreen
import com.nbalive.app.ui.theme.GradLive
import com.nbalive.app.ui.theme.GradRed
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.theme.SportDark


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

    var tab by remember { mutableStateOf(Tab.MATCHES) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? MainActivity }

    // ---------- 播放偏好：画面模式 / 屏幕常亮 ----------
    val aspectMode by PlayerPrefs.aspect.collectAsStateWithLifecycle()
    val keepScreenOn by PlayerPrefs.keepScreenOn.collectAsStateWithLifecycle()

    // ---------- 全屏 / 画中画 ----------
    // saveable：字体缩放/语言变化会重建 Activity（不在 configChanges 里），不能丢全屏
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    val videoOnly = route == Route.PLAYER && (fullscreen || inPip)

    // 直连 HLS 与 WebView 两条播放路径都算"正在看"
    val playingNow = playerState.streamUrl != null || playerState.webFallbackUrl != null
    DisposableEffect(route, playingNow) {
        PipState.playerActive = route == Route.PLAYER && playingNow
        onDispose { PipState.playerActive = false }
    }
    // 观看直播期间保持屏幕常亮，避免球赛看着看着自动熄屏
    KeepScreenOnEffect(enabled = route == Route.PLAYER && playingNow && keepScreenOn)
    // 离开播放页自动退出全屏，避免下次进来是横屏
    LaunchedEffect(route) {
        if (route != Route.PLAYER) fullscreen = false
    }
    // 全屏时横屏 + 隐藏系统栏
    FullscreenSystemEffect(enabled = fullscreen && !inPip)

    // 系统返回键：全屏时先退全屏
    BackHandler(enabled = route != Route.LIST || fullscreen) {
        if (fullscreen) fullscreen = false else viewModel.back()
    }

    // 播放降级提示：只在播放页呈现，避免用户已经返回列表还弹出"直连线路不可用"
    LaunchedEffect(route, playerState.errorMessage) {
        if (route == Route.PLAYER) {
            playerState.errorMessage?.let {
                snackbarHostState.showSnackbar(it)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!videoOnly) {
                when (route) {
                    Route.LIST -> BrandTopBar()
                    Route.DETAIL -> SubPageTopBar(title = "赛事详情", onBack = viewModel::back)
                    Route.PLAYER -> SubPageTopBar(
                        title = playerState.match?.let { "${it.homeTeam} VS ${it.awayTeam}" } ?: "播放",
                        onBack = viewModel::back,
                    )
                }
            }
        },
        bottomBar = {
            if (route != Route.PLAYER && !videoOnly) {
                SportBottomBar(selected = tab, onSelect = { tab = it })
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (videoOnly) Modifier else Modifier.padding(padding)),
        ) {
            when (route) {
                Route.LIST -> MatchListScreen(
                    matches = listState.visibleMatches,
                    loading = listState.loading,
                    error = listState.error,
                    selectedLeague = listState.selectedLeague,
                    counts = listState.counts,
                    onLeagueSelected = viewModel::selectLeague,
                    onMatchClick = viewModel::openDetail,
                    onRetry = viewModel::refresh,
                )

                Route.DETAIL -> MatchDetailScreen(
                    match = detailState.match ?: return@Box,
                    signals = detailState.signals,
                    loadingSignals = detailState.loadingSignals,
                    onSignalClick = viewModel::openPlayer,
                )

                Route.PLAYER -> PlayerScreen(
                    match = playerState.match,
                    signals = playerState.signals,
                    selectedIndex = playerState.selectedIndex,
                    resolving = playerState.resolving,
                    streamUrl = playerState.streamUrl,
                    streamReferer = playerState.streamReferer,
                    webFallbackUrl = playerState.webFallbackUrl,
                    errorMessage = null, // 提示已通过 Snackbar 呈现
                    onSignalSelected = viewModel::selectSignal,
                    otherMatches = listState.matches,
                    onOtherMatchClick = { m ->
                        viewModel.openDetail(m)
                    },
                    onPlaybackError = viewModel::onPlaybackFailed,
                    onStreamSniffed = viewModel::onStreamSniffed,
                    sniffFailed = playerState.failedSniffUrl != null,
                    aspectMode = aspectMode,
                    keepScreenOn = keepScreenOn,
                    onAspectChange = { PlayerPrefs.setAspect(context, it) },
                    onKeepScreenOnChange = { PlayerPrefs.setKeepScreenOn(context, it) },
                    fullscreen = fullscreen,
                    inPip = inPip,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onEnterPip = { activity?.enterPip() },
                )
            }
        }
    }
}

/** 品牌顶栏：渐变标记 + 标题 + 副标题 + 右侧操作 */
@Composable
private fun BrandTopBar() {
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
                color = SportDark.Txt3,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                letterSpacing = 2.4.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        TopIconButton(icon = Icons.Filled.Search, label = "搜索")
        Spacer(Modifier.width(Space.sm))
        TopIconButton(icon = Icons.Filled.DarkMode, label = "深色模式")
    }
}

@Composable
private fun TopIconButton(icon: ImageVector, label: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Radius.btn))
            .background(MaterialTheme.colorScheme.surface),
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

/** 二级页顶栏 */
@Composable
private fun SubPageTopBar(title: String, onBack: () -> Unit) {
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
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Radius.btn))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "更多",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    .clickable { onSelect(item) }
                    .padding(vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 26.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (active) Modifier.background(Color(0x24FF3B4E))
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (active) item.filled else item.outlined,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else SportDark.Txt3,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.label,
                    color = if (active) MaterialTheme.colorScheme.primary else SportDark.Txt3,
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
