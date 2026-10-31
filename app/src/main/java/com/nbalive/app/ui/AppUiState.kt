package com.nbalive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.data.model.Provider

/**
 * 根节点消费的**一个**状态对象（review/03-ui.md P2-15）。
 *
 * 以前根节点自己 `collectAsStateWithLifecycle()` 6~8 个 flow，任何一条变化
 * 都要在根节点的重组里重新走一遍 `Scaffold` / 顶栏 / 底栏。现在：
 *  - 根节点只拿这一个对象 + 一条 `PipState.inPip`（进程级全局态，本来就独立）；
 *  - 列表/详情/播放页的状态**下沉到各自的路由 composable**（见 `NbaLiveApp.kt`
 *    的 `RouteHost`），那些高频抖动不再推动根节点重组。
 *
 * ⚠️ 这里只放**根节点真的要用**的东西：
 *  - `route` 决定渲染哪条路由；
 *  - `nav` 提供底栏要用的当前标签；
 *  - `darkPref` 给顶栏的深浅色图标（`null` 的解释仍由 [resolveDarkMode] 在根节点一处完成）；
 *  - `aspectMode` 透传给播放页/「我的」页（它们各自也只用这一个值）。
 *
 * ⚠️ review/03-ui.md P2-17 的剩余部分：这两个偏好**从 VM 读**（`viewModel.darkPref` /
 * `viewModel.aspectMode`），VM 又是从注入的 [PrefsStore] 拿的 ——
 * UI 层不再 import `AppPrefs` / `PlayerPrefs`，也碰不到 `Context`。
 *
 * 播放页的 `playerState` **不在这里**：它只在系统返回键的锁定判据、
 * 画中画接线和播放路由里用得到，所以收集发生在 [RouteHost] 与 [AppTopBar] 内部。
 */
internal data class AppUiState(
    val route: Route,
    val nav: NavState,
    val darkPref: Boolean?,
    val aspectMode: AspectMode,
    /**
     * 数据源开关：现在参与解析的源（2026-10-31 用户需求）。
     *
     * ⚠️ 放在这里是因为「我的」页是根节点直接渲染的分支，
     * 开关状态要跟着 `AppUiState` 一起送到 [com.nbalive.app.ui.screens.ProfileScreen]；
     * 判据（至少留一个）仍只由 [SourceTogglePolicy] 一处决定。
     */
    val enabledProviders: Set<Provider>,
)

/** 收集根节点需要的状态；调用点在 `NbaLiveApp` 的第一行。 */
@Composable
internal fun rememberAppUiState(viewModel: MainViewModel): AppUiState {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val darkPref by viewModel.darkPref.collectAsStateWithLifecycle()
    val aspectMode by viewModel.aspectMode.collectAsStateWithLifecycle()
    val enabledProviders by viewModel.enabledProviders.collectAsStateWithLifecycle()
    return AppUiState(
        route = route,
        nav = nav,
        darkPref = darkPref,
        aspectMode = aspectMode,
        enabledProviders = enabledProviders,
    )
}