package com.nbalive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.BuildConfig
import com.nbalive.app.data.model.Match
import com.nbalive.app.ui.components.LiveDotPhase
import com.nbalive.app.ui.components.SquareIconButton
import com.nbalive.app.ui.screens.LiveNowScreen
import com.nbalive.app.ui.screens.MatchListScreen
import com.nbalive.app.ui.screens.ProfileScreen
import com.nbalive.app.ui.theme.GradLive
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space

/**
 * 界面外壳（chrome）：应用骨架 / 顶栏 / 底栏 / 缺数据占位 / 列表路由装配。
 *
 * 这些 composable 原来是 `NbaLiveApp.kt` 里的顶层私有函数（review/03-ui.md
 * P2-18「820 行上帝组件」）：搬家是为了让根组合项只承担"读状态 → 摆骨架"，
 * 而不是同时揣着 5 个组件的实现。`private` 改成 `internal`，
 * 这样根组合项与源码守卫都能按名字定位到它们。
 *
 * ⚠️ 搬迁是**逐字**的：注释、文案、判据一个字都没改 —— 它们是对照审查记录的证据，
 * 改文案等于改证据。
 */

/**
 * 应用骨架：`Scaffold` + 内容区的 inset 处理。
 *
 * 根节点只提供三个槽位（顶栏 / 底栏 / 内容），自己不再背着 Box/Scaffold 的嵌套。
 *
 * ⚠️ `videoOnly`（全屏或小窗）时内容区**不**应用 innerPadding：那时界面只剩视频，
 * 顶栏/底栏都没渲染，留着 padding 会在画面上下留出黑边。
 */
@Composable
internal fun AppShell(
    snackbarHostState: SnackbarHostState,
    videoOnly: Boolean,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = topBar,
            bottomBar = bottomBar,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (videoOnly) Modifier else Modifier.padding(padding)),
            ) {
                content()
            }
        }
    }
}

/**
 * 底部标签的图标（review/03-ui.md P2-3）。
 *
 * ⚠️ 图标放在 UI 层按 [NavTab] 映射，而不是塞进标签枚举：
 * 标签现在是**导航状态机**的一部分（在 ViewModel 的 `NavState` 里），
 * 一旦枚举带上 `ImageVector`，那个状态机就没法在 JVM 单测里跑了。
 */
internal fun bottomTabIcon(tab: NavTab, active: Boolean): ImageVector = when (tab) {
    NavTab.MATCHES -> if (active) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth
    NavTab.LIVE -> if (active) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle
    NavTab.PROFILE -> if (active) Icons.Filled.Person else Icons.Outlined.Person
}

/**
 * 顶栏宿主：按路由选顶栏内容（品牌顶栏 / 二级页顶栏）。
 *
 * ⚠️ 播放页的标题来自 `playerState.match`，所以 `playerState` 收集在**这里**
 * 而不是根节点：根节点只认路由，任何一次"解析中/换线路"的状态抖动都不需要
 * 推动整棵树重组（review/03-ui.md P2-15）。
 */
@Composable
internal fun AppTopBar(
    route: Route,
    viewModel: MainViewModel,
    darkMode: Boolean,
    onSearch: () -> Unit,
    onToggleTheme: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    when (route) {
        Route.LIST -> BrandTopBar(
            darkMode = darkMode,
            onSearch = onSearch,
            onToggleTheme = onToggleTheme,
            onRefresh = onRefresh,
        )
        Route.DETAIL -> SubPageTopBar(
            title = "赛事详情",
            onBack = onBack,
            moreItems = listOf("刷新线路" to viewModel::refreshSignals),
        )
        Route.PLAYER -> SubPageTopBar(
            title = playerState.match?.let { "${it.homeTeam} VS ${it.awayTeam}" } ?: "播放",
            onBack = onBack,
        )
    }
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
internal fun MissingMatchPlaceholder(message: String, onBack: () -> Unit) {
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
internal fun BrandTopBar(
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
internal fun SubPageTopBar(
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
internal fun SportBottomBar(selected: NavTab, onSelect: (NavTab) -> Unit) {
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
        NavTab.entries.forEach { item ->
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
                        imageVector = bottomTabIcon(item, active),
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

/**
 * 列表路由的装配：赛事 / 直播 / 我的三个标签页（review/03-ui.md P2-18 的页面装配）。
 *
 * ⚠️ 这里的判据全部来自调用方传入的状态：列表状态由 `RouteHost` 收集，
 * `liveMatches` 由它算好传进来（P2-10：同一份过滤只允许一处实现）。
 */
@Composable
internal fun ListBranch(
    tab: NavTab,
    listState: MatchListUiState,
    viewModel: MainViewModel,
    liveMatches: List<Match>,
    aspectMode: AspectMode,
    darkMode: Boolean,
    onSetFullscreen: (Boolean) -> Unit,
    onAspectChange: (AspectMode) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
) {
    // ⚠️ visibleMatches / counts **不需要**再包一层 remember（review/03-ui.md P2-9）：
    // MainViewModel 里它们已经是 data class 的 `val`（构造时求值一次），
    // 原来那段注释描述的"每次重组都新建 List/Map"是 getter 时代的旧问题，
    // 已经在源头修掉 —— 在这里再 remember 只多持一份引用，
    // 还会让后来者以为"这里必须 remember，否则会分配"。
    val visibleMatches = listState.visibleMatches
    val leagueCounts = listState.counts

    // ⚠️ 列表/直播页的红点共用**一个**呼吸相位（review/03-ui.md P2-13）：
    // 原来每颗 LiveDot 各自 rememberInfiniteTransition，
    // 一屏十几场直播就是十几份独立无限动画、永不停止。
    // 包在这一层后整屏只有一份动画，且所有红点相位一致。
    LiveDotPhase {
        when (tab) {
            NavTab.MATCHES -> MatchListScreen(
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
                    onSetFullscreen(true)
                    viewModel.playMatch(m)
                },
                onRetry = viewModel::refresh,
            )

            // 直播页：只列正在直播的，点一下直接看。
            // ⚠️ 传**已经算好的** liveMatches，别把完整列表丢进去让它再过滤一遍
            // （review/03-ui.md P2-10：同一份过滤原来两处各写一份）
            NavTab.LIVE -> LiveNowScreen(
                liveMatches = liveMatches,
                loading = listState.loading,
                error = listState.error,
                refreshing = listState.refreshing,
                onRetry = viewModel::refresh,
                onMatchClick = { m ->
                    onSetFullscreen(true)
                    viewModel.playMatch(m)
                },
            )

            // 我的：真实生效的播放/外观设置
            NavTab.PROFILE -> ProfileScreen(
                aspectMode = aspectMode,
                darkMode = darkMode,
                // BuildConfig.VERSION_NAME：PackageManager.getPackageInfo(pkg, 0) 在 API 33+ 已废弃
                versionName = BuildConfig.VERSION_NAME,
                onAspectChange = onAspectChange,
                onDarkModeChange = onDarkModeChange,
            )
        }
    }
}