package com.nbalive.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.MainActivity
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.ui.screens.MatchDetailScreen
import com.nbalive.app.ui.screens.PlayerScreen

/**
 * 根组合项：导航骨架（顶栏/底栏/内容区）+ 系统级效果接线。
 *
 * review/03-ui.md P2-18（820 行上帝组件）之后的职责边界：
 *  - 顶栏/底栏/骨架/列表装配 → `AppChrome.kt`；
 *  - 系统栏、画中画、老化 ticker、提示 snackbar → `AppEffects.kt`；
 *  - 返回键判据派发 → `AppBackHandling.kt`；
 *  - 路由分发与各页装配 → 同文件的 [RouteHost]；
 *  - 搜索覆盖层 → `SearchOverlay.kt`；
 *  - 根节点自己只留"读状态 → 摆骨架"。
 */
@Composable
fun NbaLiveApp(viewModel: MainViewModel) {
    // ⚠️ 根节点只消费**一个**状态对象 + 一条进程级全局态（review/03-ui.md P2-15）：
    // 列表/详情/播放页的状态各自在 RouteHost 里收集，那些高频抖动
    // 不再推动根节点重组（根节点原来各自 collect 6~8 条 flow）。
    val ui = rememberAppUiState(viewModel)
    val route = ui.route
    // ⚠️ 标签不再是这里的本地 `rememberSaveable`（review/03-ui.md P2-3）：
    // 它与 route 是**同一个**导航状态的两个字段，原来分居两处、
    // 靠"切标签时顺手调一次 back()"对齐；两处状态在进程重建时的语义也不一致
    // （tab 被恢复、route 丢失 → 见 P2-2）。
    val tab = ui.nav.tab
    // ---------- 深色模式 ----------
    // 与 MainActivity 调用**同一个**判定函数（AppPrefs.resolveDarkMode）：
    // 设计是深色优先，没手动设置过（null）就按深色算（review/03-ui.md P2-16）。
    // 两边各写一遍 `?: true` 时，将来规则一变、漏改一处就会出现
    // 「主题是浅色、状态栏图标按深色画」的错配。
    val darkMode = resolveDarkMode(ui.darkPref)
    val enabledProviders = ui.enabledProviders
    val snackbarHostState = remember { SnackbarHostState() }

    // 搜索页：全屏覆盖层，不占用路由，返回键先关它
    // （纯 UI 态，放本地 rememberSaveable 即可：它不参与导航一致性判断）
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    // ---------- 全屏 / 画中画 ----------
    // saveable：进程被杀后重建（不要在后台）再回来，全屏状态不该丢。
    // ⚠️ 早先的注释写的是"字体缩放/语言变化会重建 Activity"——
    // 那不对：Manifest 的 configChanges 已经包含 fontScale|locale，
    // 这两种变化不会重建 Activity。真正需要 saveable 的是进程被杀重建。
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    /** 从任意 Context 找到宿主 Activity：已抽到 ui/ContextExt.kt 共用（review/03-ui.md P2-21）。
     *  这里原来有一份私有实现，与 theme/Theme.kt 的直接强转是两套不同的写法。 */
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? MainActivity }

    val inPip by PipState.inPip.collectAsStateWithLifecycle()
    val videoOnly = route == Route.PLAYER && (fullscreen || inPip)

    // ---------- 系统级副作用（实现都在 AppEffects.kt，P2-18） ----------
    // 全屏时横屏 + 隐藏系统栏
    // ⚠️ 必须把 inPip 传进去（P2-7）：进小窗时 enabled 从 true 变 false，
    // 但方向不能复位 —— 否则小窗窗口跟着设备方向乱转。
    FullscreenSystemEffect(enabled = fullscreen && !inPip, inPip = inPip)
    // 退出小窗（系统的「全屏」按钮）后必须回到全屏播放：
    // `fullscreen` 只是进小窗前那一刻的值，从竖屏播放页进的小窗退出时它是 false，
    // 界面就回到竖屏页 —— 用户按的却是「全屏」。判据见 PipExitPolicy。
    PipExitFullscreenEffect { fullscreen = true }
    // 列表状态随时间老化（P1-1）：进详情页/播放页就停
    AppListStatusAgeEffect(route = route, tab = tab) { viewModel.ageListStatuses() }
    // 比分定时刷新（2026-10-31 用户需求）：与状态老化同一个可见性判据 ——
    // 只在列表标签可见时跑，进播放页/「我的」页就停。
    AppScoresRefreshEffect(route = route, tab = tab) { viewModel.refreshScores() }
    // 离开播放页自动退出全屏，避免下次进来是横屏
    // （解除锁定在 RouteHost 里做 —— 锁状态住在那里，见 P2-18 的职责划分）
    AppLeavePlayerEffect(route = route) { fullscreen = false }

    AppShell(
        snackbarHostState = snackbarHostState,
        videoOnly = videoOnly,
        topBar = {
            if (!videoOnly) {
                AppTopBar(
                    route = route,
                    viewModel = viewModel,
                    darkMode = darkMode,
                    onSearch = { searchOpen = true },
                    // 偏好写入的唯一链路（P2-17 写路径收口）：UI → VM 写入口 → PrefsStore
                    onToggleTheme = { viewModel.setDarkMode(!darkMode) },
                    onRefresh = viewModel::refresh,
                    onBack = viewModel::back,
                )
            }
        },
        bottomBar = {
            if (route != Route.PLAYER && !videoOnly) {
                SportBottomBar(
                    selected = tab,
                    // ⚠️ 切标签是**一次**导航迁移（标签 + 路由一起变）：
                    // 原来这里写的是 `tab = it` 再"顺手调一次 viewModel.back()"，
                    // 于是"标签切了却没反应"要靠补救动作兜（review/03-ui.md P2-3）
                    onSelect = viewModel::selectTab,
                )
            }
        },
    ) {
        RouteHost(
            route = route,
            tab = tab,
            viewModel = viewModel,
            activity = activity,
            snackbarHostState = snackbarHostState,
            fullscreen = fullscreen,
            inPip = inPip,
            searchOpen = searchOpen,
            aspectMode = ui.aspectMode,
            darkMode = darkMode,
            enabledProviders = enabledProviders,
            onSetFullscreen = { fullscreen = it },
            onCloseSearch = { searchOpen = false },
        )
    }

    // ---------- 搜索页覆盖层 ----------
    // ⚠️ 它不占路由、也不在 Scaffold 的内容区里（全屏覆盖层），
    // 列表状态的收集下沉在 SearchOverlay 内部（只有它可见时才需要）。
    if (searchOpen) {
        SearchOverlay(
            viewModel = viewModel,
            onClose = { searchOpen = false },
            onMatchClick = { m ->
                searchOpen = false
                fullscreen = true
                viewModel.playMatch(m)
            },
        )
    }
}

/**
 * 路由宿主（review/03-ui.md P2-18 的 `RouteHost` 职责）。
 *
 * 从根节点搬出来的东西：
 *  - 列表 / 详情 / 播放三条路由的装配（含"缺 match 给占位"的同构判据 P2-5 / 审计 B4）；
 *  - 锁定状态（绑定比赛 id 而不是裸布尔）；
 *  - 画中画接线（[AppPipEffects]，顺序显式，P2-6）；
 *  - 播放降级 snackbar 与系统返回键；
 *  - 列表 / 详情 / 播放状态**各自的收集**（P2-15）：这些状态高频变化，
 *    根节点不再因为它们推动整棵树重组。
 */
@Composable
private fun RouteHost(
    route: Route,
    tab: NavTab,
    viewModel: MainViewModel,
    activity: MainActivity?,
    snackbarHostState: SnackbarHostState,
    fullscreen: Boolean,
    inPip: Boolean,
    searchOpen: Boolean,
    aspectMode: AspectMode,
    darkMode: Boolean,
    /** 数据源开关（2026-10-31 用户需求）：透传给「我的」页 */
    enabledProviders: Set<Provider>,
    onSetFullscreen: (Boolean) -> Unit,
    onCloseSearch: () -> Unit,
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    // 比分（2026-10-31 用户需求）：**在这里收集**，不在根节点 ——
    // 它 45 秒变一次，放根节点会让 Scaffold/顶栏/底栏跟着重组（P2-15，有守卫）。
    val scores by viewModel.scores.collectAsStateWithLifecycle()
    // 检查更新（2026-10-31 用户需求）：只在用户点过「我的」页那一行之后才有值，
    // 同样不放进根节点（判据同 P2-15）。
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.checkingUpdate.collectAsStateWithLifecycle()
    // liveMatches 是真需要的：state 里没有预过滤过的 LIVE 列表
    // ⚠️ 过滤实现只有一处：[liveOnly]（review/audit-guards.md「未验证的怀疑 #5」）。
    // 原来这里是就地写的 `filter { it.status == MatchStatus.LIVE }`，
    // 测试侧又抄了一份逐字相同的 —— 生产加条件时测试不会红。
    val liveMatches = remember(listState.matches) {
        liveOnly(listState.matches)
    }

    // 锁定（防误触）：只认解锁键解锁，所以返回键在锁定时不该退出播放页。
    //
    // ⚠️ 锁绑定到**比赛 id**，而不是一个裸布尔：
    // 「同时在播」面板切到另一场时 route 一直是 PLAYER，
    // 裸布尔会跟着漏到新的一场（用户看着新比赛却是锁着的、浮层全没了）。
    // 换成"锁住哪一场"，切场时自然失效。
    var lockedMatchId by remember { mutableStateOf<String?>(null) }
    val playerLocked = PlayerLock.isLocked(lockedMatchId, playerState.match?.id)

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

    // 画中画接线（P2-6）：状态与粘性开关的**顺序**写在 AppPipEffects 里，
    // 不再依赖 Compose 把 DisposableEffect 排在 LaunchedEffect 之前。
    AppPipEffects(
        activity = activity,
        route = route,
        playingNow = playingNow,
        playbackLevel = playbackLevel,
    )

    // 离开播放页同时解除锁定：锁是"看当前这场"的状态，换场/退出就该恢复
    AppLeavePlayerEffect(route = route) { lockedMatchId = null }

    // 播放降级提示：只在播放页呈现，避免用户已经返回列表还弹出"直连线路不可用"
    // （key 用 errorId，理由见 PlayerNoticeEffect 的 KDoc）
    PlayerNoticeEffect(
        route = route,
        errorId = playerState.errorId,
        errorMessage = playerState.errorMessage,
        host = snackbarHostState,
    )

    // 系统返回键的判据与动作派发（review/03-ui.md P2-1 / P2-18）
    AppBackHandler(
        searchOpen = searchOpen,
        route = route,
        fullscreen = fullscreen,
        tab = tab,
        inPip = inPip,
        playerLocked = playerLocked,
        onCloseSearch = onCloseSearch,
        onExitFullscreen = { onSetFullscreen(false) },
        onLeaveRoute = viewModel::back,
        onResetTab = { viewModel.selectTab(NavTab.MATCHES) },
    )

    when (route) {
        Route.LIST -> ListBranch(
            tab = tab,
            listState = listState,
            viewModel = viewModel,
            liveMatches = liveMatches,
            aspectMode = aspectMode,
            darkMode = darkMode,
            // 数据源开关（2026-10-31 用户需求）：「我的」页要读它
            enabledProviders = enabledProviders,
            scores = scores,
            updateState = updateState,
            checkingUpdate = checkingUpdate,
            onSetFullscreen = onSetFullscreen,
        )

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

        Route.PLAYER -> when (
            // ⚠️ 与详情路由同款判据（审计 B4）：`MissingMatchPolicy.forPlayer`
            // 早就写好了却只有测试在调 —— 直接把可空 match 交给 PlayerScreen，
            // 数据丢失时用户看到的是一块空播放器（没有提示、没有返回入口）。
            // PLAYER 路由下 match 为 null 只可能是**数据丢失**：
            // `playMatch`/`openPlayer` 都是同步把 match 写进 state 的。
            val state = MissingMatchPolicy.forPlayer(playerState.match != null)
        ) {
            is MissingMatchState.ShowPlaceholder -> MissingMatchPlaceholder(
                message = state.message,
                onBack = viewModel::back,
            )

            MissingMatchState.Render -> PlayerBranch(
                playerState = playerState,
                playbackLevel = playbackLevel,
                liveMatches = liveMatches,
                otherMatches = listState.matches,
                refreshing = listState.busy,
                aspectMode = aspectMode,
                fullscreen = fullscreen,
                inPip = inPip,
                activity = activity,
                playerLocked = playerLocked,
                viewModel = viewModel,
                onSetFullscreen = onSetFullscreen,
                // 锁/解锁都作用于当前这场
                onToggleLock = { id -> lockedMatchId = if (playerLocked) null else id },
            )
        }
    }
}

/**
 * 播放路由的装配（review/03-ui.md P2-18）。
 *
 * `PlayerScreen` 有 30+ 个参数，全塞进 `RouteHost` 会把路由分发淹掉；
 * 这里只负责"把播放状态与回调接到播放页上"，判据仍然全部来自传入的参数
 * （`suspectHint` 只算一次、锁定按当前比赛 id）。
 */
@Composable
private fun PlayerBranch(
    playerState: PlayerUiState,
    playbackLevel: PlaybackHealth.Level,
    liveMatches: List<Match>,
    otherMatches: List<Match>,
    refreshing: Boolean,
    aspectMode: AspectMode,
    fullscreen: Boolean,
    inPip: Boolean,
    activity: MainActivity?,
    playerLocked: Boolean,
    viewModel: MainViewModel,
    onSetFullscreen: (Boolean) -> Unit,
    onToggleLock: (String?) -> Unit,
) {
    PlayerScreen(
        match = playerState.match!!,
        signals = playerState.signals,
        selectedIndex = playerState.selectedIndex,
        resolving = playerState.resolving,
        streamUrl = playerState.streamUrl,
        streamReferer = playerState.streamReferer,
        webFallbackUrl = playerState.webFallbackUrl,
        // 非全屏时常驻提示卡（全屏下卡片不渲染，由 Snackbar 负责）
        errorMessage = playerState.errorMessage,
        onSignalSelected = viewModel::selectSignal,
        otherMatches = otherMatches,
        onOtherMatchClick = { m ->
            viewModel.playMatch(m)
        },
        onPlaybackError = viewModel::onPlaybackFailed,
        onPlaybackDead = viewModel::onPlaybackDead,
        onPlaybackSuspect = viewModel::onPlaybackSuspect,
        onPageDeadEnd = viewModel::onPageDeadEnd,
        onStreamSniffed = viewModel::onStreamSniffed,
        sniffFailed = playerState.failedSniffUrl != null,
        // 疑似无信号（推断）的提示：判据只在这里算一次（审计 B2），
        // 播放页不许再判一遍等级
        suspectHint = PlaybackHealth.showsSuspectHint(playbackLevel),
        aspectMode = aspectMode,
        // 播放页的胶囊同样走 VM 写入口（与「我的」页同一个函数，P2-17 写路径收口）
        onAspectChange = { mode -> viewModel.setAspectMode(mode) },
        onReconnect = viewModel::reconnect,
        liveMatches = liveMatches,
        onLiveMatchClick = viewModel::playMatch,
        refreshing = refreshing,
        onRefreshLive = { viewModel.refresh() },
        fullscreen = fullscreen,
        inPip = inPip,
        onToggleFullscreen = { onSetFullscreen(!fullscreen) },
        onEnterPip = { activity?.enterPip() },
        // 点画中画但还没流可播：给一句提示（P2-6）。
        // 复用播放页已有的错误提示通道（snackbar 由 errorMessage 驱动）。
        onPipBlocked = { message -> viewModel.showPlayerNotice(message) },
        locked = playerLocked,
        onToggleLock = {
            val id = playerState.match?.id
            onToggleLock(id)
        },
    )
}