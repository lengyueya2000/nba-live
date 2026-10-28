package com.nbalive.app.ui

/**
 * 底部标签。
 *
 * ⚠️ 只有展示名，**没有图标**：图标是 UI 的事，放进状态机会让这个状态机
 * 依赖 Compose 的 `ImageVector`，从而没法在 JVM 单测里跑
 * （review/03-ui.md P2-3 要求"标签并入导航状态"，那它就得是可测的）。
 * 图标按 [NavTab] 在底栏里映射（见 `SportBottomBar`）。
 */
enum class NavTab(val label: String) {
    MATCHES("赛事"),
    LIVE("直播"),
    PROFILE("我的"),
}

/**
 * 单一导航状态（review/03-ui.md P2-3 / P2-2）。
 *
 * ## 为什么把 tab 和 route 放进同一个状态
 *
 * 原来 `tab` 是 Composable 本地 `rememberSaveable`、`route` 在 ViewModel，
 * 两个状态机互不知情，靠"切标签时顺手调一次 `viewModel.back()`"对齐。
 * 补救动作只在当前路径上够用：`back()` 的语义是"回上一层"，
 * 不是"我要这个标签" —— 将来从「我的」跳详情时，`back()` 只把 route 拉回 LIST，
 * tab 仍是 PROFILE，页面内容与选中标签就错位了。
 *
 * 现在标签切换与路由迁移是**同一次状态迁移**（[selectTab]），没有中间态。
 *
 * ## 字段
 *
 * @param tab 当前选中的底部标签
 * @param route 当前页面
 * @param matchId 当前页面所属的比赛 id（只有详情层需要它；列表层为 null）
 * @param beforePlayer 进入播放页**之前**的路由，决定返回键回哪里
 *   （从列表直接播放时回 DETAIL 会掉进用户从未见过的详情页）
 */
data class NavState(
    val tab: NavTab = NavTab.MATCHES,
    val route: Route = Route.LIST,
    val matchId: String? = null,
    val beforePlayer: Route = Route.DETAIL,
)

/**
 * 切标签：**顶层跳转**，一并回到列表层并丢掉详情上下文。
 *
 * 这是原来那两行（`tab = it` + `if (route != Route.LIST) viewModel.back()`）
 * 合并成的**一次**迁移 —— 它们本来就该是同一件事。
 */
fun NavState.selectTab(tab: NavTab): NavState = copy(
    tab = tab,
    route = Route.LIST,
    matchId = null,
)

/** 进入详情页。 */
fun NavState.openDetail(matchId: String): NavState =
    copy(route = Route.DETAIL, matchId = matchId)

/**
 * 进入播放页。
 *
 * ⚠️ 记录**来源**而不是写死 DETAIL：进入播放页有两条路
 * （列表/直播/搜索/切场 → 直接播；详情页线路 → DETAIL -> PLAYER），
 * 写死 DETAIL 会让第一条路的用户按返回时掉进从未见过的详情页。
 * 已经在播放页时（「同时在播」切场）来源保持不变。
 */
fun NavState.openPlayer(matchId: String?): NavState = copy(
    route = Route.PLAYER,
    matchId = matchId,
    beforePlayer = if (route == Route.PLAYER) beforePlayer else route,
)

/**
 * 返回上一层。
 *
 * - 播放页 → 回 [beforePlayer]；回列表层时清掉 match（详情上下文已离开）
 * - 详情页 → 回列表并清掉 match
 * - 列表层 → 空操作（是否退出 App 由 UI 的 BackPolicy 决定）
 */
fun NavState.back(): NavState = when (route) {
    Route.PLAYER -> copy(
        route = beforePlayer,
        matchId = if (beforePlayer == Route.LIST) null else matchId,
    )
    Route.DETAIL -> copy(route = Route.LIST, matchId = null)
    Route.LIST -> this
}

// ---------------- 进程重建（P2-2） ----------------

/**
 * 存档字段名。
 *
 * ⚠️ 顺序/命名改了要连带改单测；这里只放**字符串**，
 * 所以 [navStateFromStored] 是纯函数，能直接在 JVM 单测里覆盖各种半截存档。
 */
private const val KEY_TAB = "nav.tab"
private const val KEY_ROUTE = "nav.route"
private const val KEY_MATCH = "nav.match"
private const val KEY_BEFORE_PLAYER = "nav.beforePlayer"

/** 展开成 SavedStateHandle 里的字段（纯值，便于单测）。 */
internal fun NavState.toStoredFields(): Map<String, String?> = mapOf(
    KEY_TAB to tab.name,
    KEY_ROUTE to route.name,
    KEY_MATCH to matchId,
    KEY_BEFORE_PLAYER to beforePlayer.name,
)

internal fun navStateKeyFor(field: String): String = when (field) {
    "tab" -> KEY_TAB
    "route" -> KEY_ROUTE
    "match" -> KEY_MATCH
    else -> KEY_BEFORE_PLAYER
}

/**
 * 从存档重建导航状态。
 *
 * ⚠️ **播放页不恢复成播放页**（review/03-ui.md P2-2）：直播地址带时效签名，
 * 重建后回到播放页只会是一块黑屏加一条错误。回到 DETAIL 让用户看到
 * "线路列表 + 重新播放"。
 *
 * ⚠️ 每个字段**独立兜底**：存档可能来自旧版本（枚举名不存在），
 * 一个字段非法不能把整个导航状态搞崩。
 */
internal fun navStateFromStored(
    tab: String?,
    route: String?,
    matchId: String?,
    beforePlayer: String?,
): NavState {
    val restoredTab = NavTab.entries.firstOrNull { it.name == tab } ?: NavTab.MATCHES
    val storedRoute = Route.entries.firstOrNull { it.name == route } ?: Route.LIST
    val restoredRoute = if (storedRoute == Route.PLAYER) Route.DETAIL else storedRoute
    return NavState(
        tab = restoredTab,
        route = restoredRoute,
        // 只有详情层需要 match：列表层留着它只会让"回到列表"看起来还挂着旧场次
        matchId = matchId?.takeIf { it.isNotBlank() && restoredRoute == Route.DETAIL },
        beforePlayer = Route.entries.firstOrNull { it.name == beforePlayer } ?: Route.DETAIL,
    )
}