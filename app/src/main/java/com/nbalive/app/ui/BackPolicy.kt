package com.nbalive.app.ui

/**
 * 返回键的行为判定（review/03-ui.md P2-1）。
 *
 * ## 要解决什么
 *
 * `BackHandler` 的 enabled 条件原来是
 * `searchOpen || route != Route.LIST || fullscreen` —— **不含标签页**。
 * 用户切到「直播」或「我的」标签后按返回：
 * 条件全为假 → BackHandler 不接管 → 系统默认行为 = 退出 Activity。
 * 用户只是想回「赛事」标签，结果 App 直接没了。
 *
 * ## 为什么抽成纯判据
 *
 * 「按一次返回该做哪一件事」是一条有顺序的规则（先收最上层的临时状态），
 * 埋在 Composable 的 `when` 里只能靠人眼审，改坏了测试也不红。
 * 抽出来后 `BackAndMissingDataTest` 可以逐条钉住。
 *
 * ⚠️ 这里用 `onDefaultTab: Boolean` 而不是引入自己的 Tab 枚举：
 * UI 层已有自己的 `Tab`（带图标与文案），再造一个只会让两边漂移。
 */
sealed interface BackAction {
    /** 关闭搜索页 */
    data object CloseSearch : BackAction

    /** 退出全屏 */
    data object ExitFullscreen : BackAction

    /** 锁定态：吃掉这次返回，什么都不做（用户明确要求只有解锁键能解锁） */
    data object Swallow : BackAction

    /** 离开当前路由（详情/播放页回上一层） */
    data object LeaveRoute : BackAction

    /** 从非默认标签回到默认标签 */
    data object ResetTab : BackAction

    /** 什么都不做：交给系统（= 退出 App） */
    data object None : BackAction
}

object BackPolicy {

    /**
     * 这次返回是否需要 App 接管。
     *
     * ⚠️ 必须含标签判据（P2-1）：否则在「直播」/「我的」标签按返回会直接退出 App。
     *
     * @param onDefaultTab 当前是否停在默认（首页）标签
     */
    fun shouldIntercept(
        searchOpen: Boolean,
        route: Route,
        fullscreen: Boolean,
        onDefaultTab: Boolean,
    ): Boolean = searchOpen || route != Route.LIST || fullscreen || !onDefaultTab

    /**
     * 该执行哪个动作。
     *
     * 顺序 = 从最上层的临时状态开始收：
     * 搜索页 → 锁定态（吃掉）→ 全屏 → 路由 → 标签 → 交给系统。
     *
     * ⚠️ 锁定态必须排在**全屏之前**：锁定态下按返回要"什么都不做"，
     * 而不是先退出全屏 —— 否则用户会以为锁没生效，
     * 而且退出全屏本身就是可见的界面变化（用户明确要求只有解锁键能解锁）。
     *
     * @param backExitsPlayer 由 `PlayerLock.backExitsPlayer(locked)` 提供 ——
     *   不在这里重写一遍「锁定语义」，避免规则出现两份实现
     *   （`PlayerLock` 的注释记录过这个坑：一份是死函数、测试测的是没人用的那份）
     */
    fun action(
        searchOpen: Boolean,
        route: Route,
        fullscreen: Boolean,
        onDefaultTab: Boolean,
        backExitsPlayer: Boolean = true,
    ): BackAction = when {
        searchOpen -> BackAction.CloseSearch
        route == Route.PLAYER && !backExitsPlayer -> BackAction.Swallow
        fullscreen -> BackAction.ExitFullscreen
        route != Route.LIST -> BackAction.LeaveRoute
        !onDefaultTab -> BackAction.ResetTab
        else -> BackAction.None
    }
}

/** 缺数据时该渲染什么。 */
sealed interface MissingMatchState {
    /** 正常渲染 */
    data object Render : MissingMatchState

    /** 显示占位（提示 + 返回入口），而不是静默白屏 */
    data class ShowPlaceholder(val message: String) : MissingMatchState
}

/**
 * 缺 match 时的渲染判定（review/03-ui.md P2-5）。
 *
 * `Route.DETAIL -> MatchDetailScreen(match = detailState.match ?: return@Box)`
 * 拿不到 match 就直接 return —— 界面上什么都没有（纯白屏），
 * 没有提示、没有返回入口。进程被系统回收后恢复路由时最容易触发。
 */
object MissingMatchPolicy {

    fun forDetail(hasMatch: Boolean): MissingMatchState =
        if (hasMatch) MissingMatchState.Render
        else MissingMatchState.ShowPlaceholder("赛事信息已失效，请返回列表重新选择")

    fun forPlayer(hasMatch: Boolean): MissingMatchState =
        if (hasMatch) MissingMatchState.Render
        else MissingMatchState.ShowPlaceholder("播放信息已失效，请返回列表重新选择")
}