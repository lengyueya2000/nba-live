package com.nbalive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * 系统返回键的接线（review/03-ui.md P2-18 的 BackHandlerHost 职责）。
 *
 * 判据仍然全部来自 [BackPolicy]（有单测），这里只做"判据 → 动作"的派发。
 * 根节点早先自己揣着这段 40 行的 `when`，现在它只剩一次调用。
 *
 * ⚠️ 注释与判据**逐字保留**：它们是 review/03-ui.md P2-1 的证据，
 * `DeadCodeWiringGuardTest` 也按这些调用点守着接线。
 */
@Composable
internal fun AppBackHandler(
    searchOpen: Boolean,
    route: Route,
    fullscreen: Boolean,
    tab: NavTab,
    inPip: Boolean,
    playerLocked: Boolean,
    onCloseSearch: () -> Unit,
    onExitFullscreen: () -> Unit,
    onLeaveRoute: () -> Unit,
    onResetTab: () -> Unit,
) {
    // 系统返回键：搜索页 > 全屏 > 路由
    // ⚠️ 画中画时要放行：部分系统版本在关小窗时会把返回键派发给界面，
    // 被这里吃掉就会变成"先退详情页、再退列表"，小窗关不掉。
    // ⚠️ 锁定时返回键不退出播放页：用户明确要求「只有点解锁键才会解锁」，
    // 锁定期间返回键被吃掉（而不是退出播放页），否则用户会被困在锁定态。
    //
    // ⚠️ 判据抽在 BackPolicy（有单测）。原来的 enabled 条件不含**标签页**：
    // 用户切到「直播」/「我的」后按返回，条件全为假 → 不接管 →
    // 系统默认行为 = 退出 Activity（用户只是想回「赛事」标签）。
    val onDefaultTab = tab == NavTab.MATCHES
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
            BackAction.CloseSearch -> onCloseSearch()
            // 锁定态：吃掉这次返回，什么都不做
            BackAction.Swallow -> Unit
            BackAction.ExitFullscreen -> onExitFullscreen()
            BackAction.LeaveRoute -> onLeaveRoute()
            // 非默认标签：回赛事标签，而不是退出 App
            // （走的也是**同一次**导航迁移，不再手改本地 tab）
            BackAction.ResetTab -> onResetTab()
            // 理论上不该走到（enabled 已经挡住了），保留穷尽分支
            BackAction.None -> Unit
        }
    }
}