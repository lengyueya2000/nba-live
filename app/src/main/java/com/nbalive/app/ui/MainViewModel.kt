package com.nbalive.app.ui

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.repository.MatchRepository
import com.nbalive.app.data.remote.StreamUrlResolver
import com.nbalive.app.data.remote.safeLogWarn
import com.nbalive.app.data.remote.safeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 页面路由 */
enum class Route { LIST, DETAIL, PLAYER }

data class MatchListUiState(
    /** 首载中（还没有任何数据）：整屏 loading */
    val loading: Boolean = true,
    /** 刷新中（已有数据）：保留旧列表，只在角落显示进度，不闪空 */
    val refreshing: Boolean = false,
    val error: String? = null,
    val matches: List<Match> = emptyList(),
    val selectedLeague: League = League.ALL,
    /**
     * "部分数据源不可用"的提示（P1-2）。
     *
     * 与 [error] 的区别：error 是整页失败（没有数据可展示），
     * 这里是有数据、但缺了一部分。原来单源失败只写一行 Log.w，
     * 用户只看到"场次变少了"，完全不知道是某个源挂了 ——
     * 静默缺数据比明确报错更难排查。
     */
    val partialError: String? = null,
) {
    /** 列表是否正忙（首载或刷新中）：供刷新按钮显示进度 */
    val busy: Boolean get() = loading || refreshing

    /**
     * 按联赛筛选后的列表。
     *
     * ⚠️ 用 `val` 而不是 `get()`：
     * 原来写成 getter 的话，每次读取都新建一个 List —— 而界面在每次重组时
     * 都会读它，等于每帧都在分配。上百场比赛时这是实打实的 GC 压力。
     * data class 的属性在构造时求值一次，列表变了会重新构造整个 state。
     */
    val visibleMatches: List<Match> = when (selectedLeague) {
        League.ALL -> matches
        League.NBA -> matches.filter { it.league == League.NBA }
        League.CBA -> matches.filter { it.league == League.CBA }
        // ⚠️ 这个分支实际走不到（review/02-data.md P2-13）：
        // merge 里已经 `filter { it.league in League.supported }`，
        // supported = {NBA, CBA}，所以 OTHER 场次根本进不了 matches。
        // 保留它是**防御性兜底**：哪天 merge 的过滤放宽（比如产品要展示
        // "其他联赛"），这里不会静默漏掉；League.filters 目前也不含 OTHER，
        // 所以 UI 上暴露不到这个筛选。删掉它反而会在那天变成编译错误之外的空列表。
        League.OTHER -> matches.filter { it.league == League.OTHER }
    }

    /** 各联赛的场次数（联赛筛选胶囊上的角标） */
    val counts: Map<League, Int> = mapOf(
        League.ALL to matches.size,
        League.NBA to matches.count { it.league == League.NBA },
        League.CBA to matches.count { it.league == League.CBA },
        // 同上：merge 已过滤 OTHER，这里恒为 0；保留以与 visibleMatches 对齐
        League.OTHER to matches.count { it.league == League.OTHER },
    )
}

data class MatchDetailUiState(
    val match: Match? = null,
    val signals: List<StreamSource> = emptyList(),
    val loadingSignals: Boolean = false,
    /** 拉信号失败的原因；null 表示没有错误（加载中/空列表是另外的分支） */
    val error: String? = null,
)

data class PlayerUiState(
    val match: Match? = null,
    val signals: List<StreamSource> = emptyList(),
    val selectedIndex: Int = 0,
    val resolving: Boolean = false,
    /** 已解析出的直连 m3u8（ExoPlayer 播放） */
    val streamUrl: String? = null,
    /** 拉流所需 Referer（源站有防盗链） */
    val streamReferer: String? = null,
    /** 无法静态解析时的网页播放地址（WebView 承载） */
    val webFallbackUrl: String? = null,
    /** streamUrl 是否来自 WebView 嗅探：失败时退回 WebView，而不是拉黑线路 */
    val sniffedStream: Boolean = false,
    /** 嗅探到但直连失败的地址：不再重试同一个，避免 嗅探→失败→退回→再嗅探 的死循环 */
    val failedSniffUrl: String? = null,
    /**
     * 升级失败过的**播放页**（m3u8 每轮签名不同，只挡 URL 挡不住回环）：
     * 同一播放页本轮不再嗅探升级，退回网页后就老实待在网页里。
     */
    val failedSniffPage: String? = null,
    val errorMessage: String? = null,
    /**
     * 是否处于**彻底播不出来**的状态。
     *
     * ⚠️ 不能只靠 [errorMessage] 判断：它同时承担两种语义 ——
     *  1) 过渡提示："直连线路不可用，已自动切换到 信号2"（切换后照样能播）
     *  2) 彻底失败："网络错误"（画面是死的，看不了）
     * 老代码把小窗判定挂在"有没有流地址"上（[PipState.shouldAutoEnterPip] 的
     * hasStream），于是这条链出了回归（v1.2.8 实测）：播放页显示「网络错误」，
     * 按 Home 仍弹出小窗，里面就是那句点不动的错误提示。
     * 有了这个标志，**"在播"的判定只认它**。
     */
    val playbackDead: Boolean = false,
    /**
     * 是否处于**疑似异常**状态（P0-1）。
     *
     * ⚠️ 与 [playbackDead] 严格分开，区别在**判据的来源**：
     *  - `playbackDead` = 播放器自己报的错（事实）→ 允许收掉小窗；
     *  - `playbackSuspect` = 嗅探超时这类**推断** → 只显示提示、停自动进小窗，
     *    **绝不**触发关闭类的处置。
     *
     * 分不开的后果就是 v1.2.12 之前那条链：判据是推断，处置却是
     * `finishAndRemoveTask()`（不可逆）。MSE/`blob:` 播放、m3u8 地址不带
     * `.m3u8` 字面量、弱网首帧 > 25 秒 —— 这三种正常在播的情况都会被误判，
     * 用户正在看的小窗连同整个任务一起消失，找都找不回来。
     */
    val playbackSuspect: Boolean = false,
    /**
     * 错误提示的自增序号。
     *
     * 界面用它当 LaunchedEffect 的 key：只拿 [errorMessage] 当 key 时，
     * 同样的文案连着报两次，StateFlow 会合并相等值 → 第二次不再弹提示。
     */
    val errorId: Long = 0,
)

class MainViewModel(
    /**
     * ⚠️ 用**进程级单例**而不是每次 new：
     * MatchRepository 持有详情页 TTL 缓存（省源站请求的关键），
     * 每次 new 一个就等于每次都从零开始缓存 ——
     * 用户在"列表 → 详情 → 播放页 → 返回"之间往返，
     * ViewModel 重建就把缓存丢了，同一个详情页要重复拉。
     */
    private val repository: MatchRepository = MatchRepository.shared,
    /**
     * 列表加载入口（**测试专用**覆盖点）。
     *
     * 非 null 时优先于 [loadOutcome]：测试注入它以可控地驱动刷新时序（全程不触网）。
     * 生产路径传 null，走 [loadOutcome] 拿完整结果。
     */
    private val loadMatches: (suspend () -> Result<List<Match>>)? = null,
    /**
     * 带"哪些源失败了"的加载入口（P1-2）。
     *
     * 生产默认走它：单源失败不再是只写一行 Log.w 的静默缺数据，
     * 调用方能把"JRS 源暂时不可用，仅显示部分场次"如实告诉用户。
     */
    private val loadOutcome: suspend (Set<Provider>) -> MatchRepository.LoadOutcome = { enabled ->
        repository.loadMatchesOutcome(enabled)
    },
    /**
     * 信号加载入口。测试注入它以驱动"进场期间返回/抛异常"等时序，
     * 也覆盖 loadSignals 的取消与失败路径（与 [loadMatches] 同一个理由）。
     */
    private val loadSignals: suspend (Match) -> MatchRepository.Detail = { repository.loadSignals(it) },
    /**
     * 直连接解入口。测试注入它以覆盖故障切换状态机，
     * 以及"畸形地址抛异常时必须落错误态而不是闪退"这条契约。
     */
    private val resolveStream: suspend (StreamSource) -> StreamUrlResolver.Resolved? = {
        repository.resolveStream(it)
    },
    /**
     * 跨进程重建用的存档（review/03-ui.md P2-2）。
     *
     * ⚠️ **生产必须注入真正接了 SavedStateRegistry 的 handle**
     * （MainActivity 里 `createSavedStateHandle()`）。这个默认值只是为了
     * 不强迫既有测试改构造调用；用默认值 = 持久化静默失效（测试全绿、
     * 线上重建照旧掉回列表）—— 所以源码守卫
     * `NavRestoreTest.生产必须注入真正可持久化的 SavedStateHandle` 钉住注入点。
     */
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    /**
     * 偏好存储（review/03-ui.md P2-17 的剩余部分）。
     *
     * ⚠️ 用**接口注入**而不是 `AndroidViewModel`：JVM 单测没有 `Application` 实例，
     * 构造 Android 桩会抛 `Stub!`，`MainViewModelTest` / `NavRestoreTest` 会整批挂；
     * 而且把 `Application` 塞进 VM 等于把 Context 依赖请回 UI 层。
     * 生产由 `MainActivity` 注入 `AndroidPrefsStore`（自己持有 applicationContext），
     * 测试/预览走默认的 [InMemoryPrefsStore]，一行 Android 代码都不用碰。
     */
    private val prefs: PrefsStore = InMemoryPrefsStore(),
) : ViewModel() {

    companion object {
        /**
         * 一次故障切换最多尝试几条直连线路。
         *
         * 每条直连线路的解析代价 = 最多 4 跳 + 最多 5 个候选 ≈ 9 次 HTTP 请求。
         * 全部扫完是 9D 次；不设上限时看门狗与"重连"按钮会把这一轮反复重放。
         */
        private const val MAX_FALLBACK_CANDIDATES = 3
    }

    /**
     * 单一导航状态（review/03-ui.md P2-3）：
     * 标签与路由在**同一次迁移**里变，不再需要"切标签时顺手 back()"这种补救动作。
     *
     * ⚠️ 详情层不在这里直接落地：`MatchDetailUiState.match` 来自列表，
     * 重建时列表还是空的 —— 先把"要回哪场"记进 [pendingRestoreMatchId]，
     * 等列表加载完再跳（否则会先闪一下缺 match 的占位提示）。
     */
    private val restoredNav = navStateFromStored(
        tab = savedStateHandle[navStateKeyFor("tab")],
        route = savedStateHandle[navStateKeyFor("route")],
        matchId = savedStateHandle[navStateKeyFor("match")],
        beforePlayer = savedStateHandle[navStateKeyFor("beforePlayer")],
    )

    private val _nav = MutableStateFlow(
        if (restoredNav.route == Route.DETAIL) restoredNav.copy(route = Route.LIST) else restoredNav,
    )
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    /**
     * 进程重建后要自动回到的详情页（review/03-ui.md P2-2）。
     *
     * ⚠️ 找不到那场比赛（源站已撤）时就留在列表：硬跳详情只会显示占位提示。
     */
    private var pendingRestoreMatchId: String? =
        restoredNav.matchId?.takeIf { restoredNav.route == Route.DETAIL }

    /**
     * `route` 是 [nav] 的**同步镜像**，只为兼容既有调用点与测试而保留。
     *
     * ⚠️ 唯一写入口是 [setNav]：它同时更新 [nav]/[route] 并落盘。
     * 直接写 `_route.value` 会让三者（nav、route、存档）不一致 ——
     * 那正是"两个状态机"问题的复现。
     */
    private val _route = MutableStateFlow(_nav.value.route)
    val route: StateFlow<Route> = _route.asStateFlow()

    /** 导航状态的唯一写入口：同步镜像 + 落盘（进程重建用）。 */
    private fun setNav(next: NavState) {
        if (next == _nav.value) return
        _nav.value = next
        _route.value = next.route
        next.toStoredFields().forEach { (key, value) -> savedStateHandle[key] = value }
    }

    /**
     * 切换底部标签（review/03-ui.md P2-3）。
     *
     * ⚠️ 这是**一次**导航迁移：标签与路由一起变，不存在"标签切了但页面还在详情页"
     * 的中间态。原来这件事由 UI 层两支代码拼出来（`tab = it` + 顺手调一次
     * `viewModel.back()`），谁漏写一半就错位。
     */
    fun selectTab(tab: NavTab) {
        when (_nav.value.route) {
            // 播放页不渲染底栏（点不到），保险起见按返回把播放态收干净
            Route.PLAYER -> back()
            Route.DETAIL -> {
                // 离开详情页：在途拉线路要停，加载态要复位，
                // 否则详情页的信号区会永久停在"正在获取信号列表…"
                detailJob?.cancel()
                _detailState.update { it.copy(loadingSignals = false, error = null) }
            }
            Route.LIST -> Unit
        }
        // 用户明确切了标签 = 放弃"重建后回到详情"的意图
        pendingRestoreMatchId = null
        setNav(_nav.value.selectTab(tab))
    }

    /**
     * 进程重建后回到那场比赛的详情页（review/03-ui.md P2-2）。
     *
     * ⚠️ 必须在列表加载**落地之后**调用：详情页渲染靠 `detailState.match`，
     * 而它唯一的来源是列表。找不到那场（源站已撤 / 比赛结束）就留在列表 ——
     * 硬跳详情只会显示一个"缺 match"的占位提示，比掉回列表更糟。
     *
     * ⚠️ 列表加载**失败**时有意不调用：留着 [pendingRestoreMatchId]，
     * 等用户下次刷新成功后再恢复，而不是把意图悄悄丢掉。
     */
    private fun restorePendingDetail() {
        val id = pendingRestoreMatchId ?: return
        val match = _listState.value.matches.firstOrNull { it.id == id }
        if (match == null) return
        pendingRestoreMatchId = null
        _detailState.value = MatchDetailUiState(match = match, loadingSignals = true)
        setNav(_nav.value.openDetail(match.id))
        // 走缓存：用户没点过"刷新线路"，重建恢复不该白白回源一次
        loadDetailSignals(invalidateCache = false)
    }

    private val _listState = MutableStateFlow(MatchListUiState())
    val listState: StateFlow<MatchListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(MatchDetailUiState())
    val detailState: StateFlow<MatchDetailUiState> = _detailState.asStateFlow()

    /**
     * 已被故障切换**临时**拉黑的线路（review/01-P1-5）。
     *
     * ⚠️ 用带 TTL 的记账而不是裸 Set：
     * 15 秒看门狗会把"弱网首帧 >15s"的健康线路判成卡死，
     * 而裸 Set 在会话内永不清除 —— 那条线其实是好的，只是第一帧慢了点，
     * 之后整场都不会再被自动选中，用户看到"线路莫名被跳走且回不来"。
     * 限流窗口、弱网、CDN 抖动都会过去，永久黑名单只是把暂时故障变长期故障
     * （与 `02-P1-9` 域名候选是同一个错误）。
     *
     * 规则见 [SourceFailureBook]；单测 `SourceFailureBookTest` 钉住"过期解封"。
     */
    private val failedSources = SourceFailureBook()

    /**
     * 已经嗅探成功过的播放页 -> m3u8 的短期缓存（2026-10-30「解析播放太慢」）。
     *
     * 过滤掉主播线路后，剩下的 JRKAN 线路全部只能走 WebView：
     * 加载播放页（外部脚本 parser-blocking，实测 0.1~27 秒）→ 页面里的播放器发 m3u8 请求
     * → 嗅探 → 升级 ExoPlayer。再点开同一场时这条链路会**从头再走一遍**，
     * 尽管上一次已经拿到过带签名的可直连地址（`expire` 往往在几小时后）。
     *
     * 规则与失效路径（直连失败必须作废）见 [SniffedPlaylistCache]。
     */
    private val sniffedPlaylists = SniffedPlaylistCache()

    /** 单调时钟（毫秒）：不受用户改系统时间影响，与 [SourceFailureBook] 共用。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * 墙钟（毫秒）：给 [SniffedPlaylistCache] 用。
     *
     * 缓存有效期来自源站签名的**绝对时间戳**（`expire`），必须与墙钟同基准；
     * 用单调时钟会把 2026 年的时间戳当成"几亿毫秒后"。
     */
    private fun wallNowMs(): Long = System.currentTimeMillis()

    /** 这条线现在还能不能被自动选中。 */
    private fun isSourceFailed(pageUrl: String): Boolean = failedSources.isFailed(pageUrl, nowMs())

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    /**
     * 已经**自动换线试过**的线路下标（线上黑屏修复，见 [onPlaybackSuspect]）。
     *
     * 每条线路只自动试一次：否则一条死线路与另一条死线路之间会来回跳，
     * 把源站的播放页请求量翻倍（同 `play` 里 `MAX_FALLBACK_CANDIDATES` 的理由）。
     * 换一场比赛时清空（[playMatch]）；用户**主动**点某条线时把那条移出
     * （[selectSignal]）——用户的选择要能再试一次。
     */
    private val autoTriedIndices = mutableSetOf<Int>()

    /**
     * 深色模式偏好（review/03-ui.md P2-17 的剩余部分）。
     *
     * ⚠️ 界面**只**从 VM 读它（不再直接 `AppPrefs.darkMode`）：存储以 [PrefsStore]
     * 注入，所以 VM 与它的单测都不依赖 Android `Context`。
     * `null` 怎么解释（深色优先）仍然只由 [resolveDarkMode] 一处完成（P2-16）。
     */
    val darkPref: StateFlow<Boolean?> = prefs.darkMode

    /**
     * 画面模式偏好。同上：界面只从 VM 读；写入是无 `Context` 的 setter 落盘
     * （平台层负责，见 `MainActivity`）。
     */
    val aspectMode: StateFlow<AspectMode> = prefs.aspect

    /**
     * 数据源开关：现在参与解析的源（2026-10-31 用户需求）。
     *
     * 界面只从 VM 读；写入走 [setProviderEnabled]（判据 [SourceTogglePolicy]）。
     */
    val enabledProviders: StateFlow<Set<Provider>> = prefs.enabledProviders

    /**
     * 切换某个数据源（「我的」页开关）。
     *
     * ## 两条必须由 VM 兜住的约束
     *
     * 1. **最后一个源关不掉**（[SourceTogglePolicy.canDisable]）：两个都关 = 永远空列表。
     *    界面会把开关置灰，但**这里也要挡一次** —— 界面置灰与状态写入分成两处实现，
     *    任何一处漏了都会让用户进入那个死状态，而单测只测其中一处时照样全绿。
     * 2. **切换后要重新拉列表**：不重拉的话用户关了 JRS 却还看着 JRS 的场次，
     *    得手动刷新才生效 —— 那正是"开关点了没反应"的观感。
     */
    fun setProviderEnabled(provider: Provider, enabled: Boolean) {
        val current = prefs.enabledProviders.value
        val next = SourceTogglePolicy.toggle(current, provider, on = enabled)
        if (next == current) return
        prefs.setProviderEnabled(next)
        // 开关改变 = 数据来源变了：详情页缓存里可能存着上一次配置下的正文，
        // 不清会让"刚打开的源"在 5 分钟 TTL 内看不到新线路。
        repository.clearDetailCache()
        // 开关改变 = 数据来源变了，列表必须重拉（否则界面还挂着刚被关掉的源的场次）
        refresh()
    }

    /**
     * 切换深色模式（**唯一的写入口**，review/03-ui.md P2-17 的写路径收口）。
     *
     * 链路：UI → [setDarkMode] → 注入的 [PrefsStore] → `AndroidPrefsStore`（平台层）
     * → `AppPrefs`。界面因此既拿不到 `Context`，也不认识 `AppPrefs` / `PlayerPrefs`；
     * 判据见 `PrefsBoundaryGuardTest` 与 `NavHostGuardTest`（两侧都查真实调用点）。
     */
    fun setDarkMode(dark: Boolean) {
        prefs.setDarkMode(dark)
    }

    /**
     * 切换画面模式（**唯一的写入口**）：链路同 [setDarkMode]。
     *
     * ⚠️ 两个消费页（「我的」的胶囊 / 播放页的胶囊）都必须调它 ——
     * 只接一处时另一页点了没反应，而且没有编译期信号。
     */
    fun setAspectMode(mode: AspectMode) {
        prefs.setAspect(mode)
    }

    /**
     * 写错误提示：顺带把 [PlayerUiState.errorId] 自增。
     *
     * 界面拿 errorId 当提示的 key——只拿文案当 key 时，同样的文案连着报两次，
     * StateFlow 会合并相等值，第二次就再也弹不出来了。
     */
    private fun PlayerUiState.withError(message: String?): PlayerUiState =
        copy(errorMessage = message, errorId = errorId + 1)

    /**
     * 标记"彻底播不出来"。
     *
     * 与 [withError] 分开：错误文案可能只是过渡提示（自动切换线路），
     * 而这里才是"画面已死、该收起小窗/不再自动进小窗"的信号。
     * 见 [PlayerUiState.playbackDead] 的说明。
     */
    private fun PlayerUiState.markPlaybackDead(dead: Boolean): PlayerUiState =
        copy(playbackDead = dead)

    /**
     * 标记「疑似异常」（P0-1）：只来自**推断**（嗅探超时），不是播放器报的错。
     *
     * 与 [markPlaybackDead] 的关键区别是处置权限 —— 疑似**只**影响提示与
     * 「按 Home 自动进小窗」开关，不会触发任何关闭类的、不可逆的动作。
     */
    private fun PlayerUiState.markPlaybackSuspect(suspect: Boolean): PlayerUiState =
        copy(playbackSuspect = suspect)

    /**
     * 同一时刻只允许一个解析任务：用户快速连点线路时，
     * 慢的那个解析若后完成，会把快的那个（用户最新选择）覆盖掉，
     * 甚至把已经播起来的直连流换成 WebView。
     */
    private var playJob: Job? = null
    private var detailJob: Job? = null
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * 「直连失败、退回网页」之后的宽限计时（2026-10-30 解析播放太慢）。
     *
     * 判据与取值依据见 [SniffRecoveryPolicy]。宽限期内页面重新产出可用的流就取消；
     * 任何"重新开始播/换线/离开播放页"的动作也必须取消，否则旧计时会把新线路误判成死线。
     */
    private var sniffRecoveryJob: Job? = null

    /** 取消上一条线的恢复计时。 */
    private fun cancelSniffRecovery() {
        sniffRecoveryJob?.cancel()
        sniffRecoveryJob = null
    }

    init {
        refresh()
    }

    // ---------- 列表 ----------

    fun refresh() {
        // 取消上一次：连点刷新会让多次 loadMatches 并发跑，
        // 每次都要为直播中的场次并发拉详情页，对源站是成倍的请求量
        refreshJob?.cancel()
        val job = viewModelScope.launch {
            // ⚠️ 记住"我是不是当前那次刷新"（review/02-data.md P2-4）。
            // 取消收尾里若无条件写 refreshing=false，会把**新任务**刚设的
            // refreshing=true 抹掉 —— 指示器闪一下就消失，但请求还在跑。
            // 用协程自身的 Job 与 refreshJob 比对：被替换掉的旧协程不该再写状态。
            val self = coroutineContext[Job]
            fun stillCurrent(): Boolean = refreshJob === self
            // 刷新时**保留旧列表**：整屏换成转圈会把已有卡片全抹掉（滚动位置也丢）。
            // 只有首载（一条数据都还没有）才用全屏 loading。
            val firstLoad = _listState.value.matches.isEmpty()
            _listState.update {
                it.copy(loading = firstLoad, refreshing = !firstLoad, error = null)
            }
            try {
                // 测试注入 loadMatches 时走它（返回不带失败信息的 Result）；
                // 生产走 loadOutcome，拿得到"哪些源失败了"（P1-2）
                val injected = loadMatches
                if (injected != null) {
                    injected()
                        .onSuccess { matches ->
                            _listState.update {
                                it.copy(
                                    loading = false, refreshing = false,
                                    error = null, partialError = null, matches = matches,
                                )
                            }
                        }
                        .onFailure { e ->
                            _listState.update {
                                it.copy(
                                    loading = false,
                                    refreshing = false,
                                    error = safeMessage(e).ifBlank { "网络异常，请稍后重试" },
                                )
                            }
                        }
                } else {
                    // ⚠️ 把**当前**开关状态传下去：关掉的源在数据层就不发请求
                    // （用户说的是"不解析"，不是"解析完再过滤"）
                    val outcome = loadOutcome(prefs.enabledProviders.value)
                    _listState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = null,
                            matches = outcome.matches,
                            // 单源失败不再静默 —— 用户要能看出
                            // "场次变少"是某个源挂了，而不是今天真没比赛
                            partialError = outcome.partialError(),
                        )
                    }
                }
                // ⚠️ 列表落地**之后**才谈得上"回到那场比赛的详情页"（review/03-ui.md P2-2）：
                // 详情页要的 match 对象就在列表里。
                restorePendingDetail()
            } catch (ce: CancellationException) {
                // 协程被取消：收尾 loading/refreshing。
                // ⚠️ 只在**自己仍是当前那次刷新**时才收尾（review/02-data.md P2-4）：
                // refresh() 先 cancel 旧的再起新的，旧协程的这个 catch 若比新协程
                // 写 refreshing=true 更晚落地，无条件写 false 就会把新任务的
                // 指示器抹掉 —— 用户看到指示器闪一下消失，但请求还在跑。
                if (stillCurrent()) { _listState.update { it.copy(loading = false, refreshing = false) } }
                throw ce
            } catch (t: Throwable) {
                // 注入的加载入口抛异常时也不能闪退，更不能把列表清空
                // ⚠️ 不把 throwable 交给 Log：堆栈帧的 message 可能内嵌带签名的源站 URL
                safeLogWarn("MainViewModel", "refresh failed", t)
                // 同理：被替换掉的旧任务不该覆盖新任务的状态
                if (stillCurrent()) {
                    _listState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = safeMessage(t).ifBlank { "网络异常，请稍后重试" },
                        )
                    }
                }
            }
        }
        refreshJob = job
    }

    fun selectLeague(league: League) {
        _listState.update { it.copy(selectedLeague = league) }
    }

    /**
     * 让列表里已缓存的场次状态随时间老化（P1-1）。
     *
     * 状态原来只在 loadMatches 结束时算一次，之后永不重算 ——
     * 用户不手动刷新，早上 10:00 开赛的比赛到晚上还挂着「直播中」。
     * 这里做**纯内存**重算：零网络请求，不违反"不轮询源站"的取舍。
     *
     * 由界面在列表可见时每分钟调一次（切后台自动停，见 NbaLiveApp）。
     */
    fun ageListStatuses() {
        val current = _listState.value.matches
        if (current.isEmpty()) return
        val aged = repository.agedMatches(current, java.time.ZonedDateTime.now())
        // agedMatches 没变化时会返回原引用，这里再挡一层 equals，
        // 避免 StateFlow 收到"内容相同但实例不同"的列表触发全列表重组
        if (aged != current) {
            _listState.update { it.copy(matches = aged) }
        }
    }

    // ---------- 详情 ----------

    /**
     * 按「我的」页的数据源开关过滤线路（2026-10-31 用户需求）。
     *
     * ## 为什么数据层已经跳过请求了，这里还要过滤
     *
     * 两处防的是不同的事：
     *  - 列表层（`loadMatchesWithOutcome(enabled=…)`）跳过**源站列表请求** —— 省流量、不打扰源站；
     *  - 这里过滤**线路**：`loadSignals` 拿到的线路是"这场比赛的线路"，
     *    而合并后的 `Match.sources` 可能同时带两个源的线路（列表是上一次配置下合并的，
     *    或者用户中途改了开关）。不过滤的话，关掉 JRS 之后播放页里仍会列出 JRS 线路，
     *    点下去照样能播 —— 开关等于没生效。
     */
    private fun allowedSignals(signals: List<StreamSource>): List<StreamSource> {
        val enabled = prefs.enabledProviders.value
        return signals.filter { SourceTogglePolicy.isEnabled(enabled, it.provider) }
    }

    /**
     * 详情页「更多 -> 刷新线路」：重新拉一次该场次的信号列表。
     * 源站偶尔会补线路，重进页面不一定能刷新到，给个显式入口。
     */
    fun refreshSignals() = loadDetailSignals(invalidateCache = true)

    /**
     * 拉当前详情页的线路。
     *
     * @param invalidateCache 用户显式点「刷新线路」时为 true：先让详情页缓存失效，
     *   随后的 `loadSignals` 才会真的回源。
     *   ⚠️ `playMatch()`（首次进场）与"进程重建后回到详情"必须走 **false**：
     *   否则 5 分钟 TTL 内点「刷新线路」拿到的是同一份旧 HTML，
     *   源站后补的线路永远看不到，按钮成了摆设（review/02-data.md 的取舍）。
     */
    private fun loadDetailSignals(invalidateCache: Boolean) {
        val match = _detailState.value.match ?: return
        if (invalidateCache) match.jrsDetailId?.let { repository.invalidateDetailCache(it) }
        detailJob?.cancel()
        _detailState.update { it.copy(loadingSignals = true, error = null) }
        detailJob = viewModelScope.launch {
            val detail = try {
                loadSignals(match)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 拉信号失败必须落成可见的错误态：
                // 否则 loadingSignals 永远停在 true，详情页一直转圈且没有重试入口
                safeLogWarn("MainViewModel", "loadSignals failed", t)
                _detailState.update {
                    it.copy(loadingSignals = false, error = "加载线路失败：${safeMessage(t)}")
                }
                return@launch
            }
            val c = detail.correction
            if (c != null && (c.status != match.status || c.startAtMillis != match.startAtMillis)) {
                fun fix(m: Match): Match =
                    if (m.id == match.id) m.copy(status = c.status, startAtMillis = c.startAtMillis) else m
                _detailState.update { it.copy(match = fix(it.match ?: match)) }
                _listState.update { st -> st.copy(matches = st.matches.map(::fix)) }
            }
            _detailState.update { it.copy(signals = allowedSignals(detail.signals), loadingSignals = false) }
        }
    }

    // ---------- 播放 ----------

    fun openPlayer(index: Int) {
        val detail = _detailState.value
        val match = detail.match ?: return
        failedSources.clearAll()
        // 从详情页的线路列表进来：返回键该回详情页（来源由 NavState 记住）
        setNav(_nav.value.openPlayer(match.id))
        _playerState.value = PlayerUiState(
            match = match,
            signals = detail.signals,
            selectedIndex = index,
            resolving = true,
        )
        play(index)
    }

    fun selectSignal(index: Int) {
        val state = _playerState.value
        if (index !in state.signals.indices) return
        // 用户明确点了这条线，就再给它一次机会（此前被自动故障切换拉黑过）
        state.signals.getOrNull(index)?.let { failedSources.clear(it.pageUrl) }
        // 用户主动选 = 也从"自动换线试过"的名单里拿掉，让它还能被自动重试
        autoTriedIndices.remove(index)
        // 用户主动换线 = 换了个播放页，"这个页升级失败过"的记录不再适用。
        // ⚠️ 必须**无条件清空**：
        // 之前写成 `if (it.failedSniffPage == newPage) null else it.failedSniffPage`
        // 只会清掉"切回同一个页"的情况 —— 用户切到**另一条**线时旧记录还在，
        // 之后切回那条线时嗅探升级会被静默拒绝，用户看不到任何提示。
        // 用户主动选择 = 给它一次新机会。
        _playerState.update {
            it.copy(
                selectedIndex = index,
                resolving = true,
                failedSniffPage = null,
            ).withError(null)
        }
        play(index)
    }

    /**
     * 重连：按当前线路重新解析并播放。
     * 直播地址带时效签名，过期后画面卡住/黑屏，重连可拿新地址；
     * 同时清除该线路的失败记录（用户主动重连 = 再给一次机会）。
     *
     * ⚠️ 这里**不清** failedSniffPage：
     * 嗅探升级失败过的播放页，重连时重新嗅探一遍又会把整条页面链重放一次
     * （WebView 销毁重建 + 4~6 个文档 + 1 个 m3u8），
     * 用户每点一次"重连"就是一轮完整重放 —— 这是最容易把源站打出限流的路径。
     * 真要恢复这条线，用户可以主动切走再切回来（selectSignal）。
     */
    fun reconnect() {
        val state = _playerState.value
        if (state.signals.isEmpty()) return
        state.signals.getOrNull(state.selectedIndex)?.let { failedSources.clear(it.pageUrl) }
        play(state.selectedIndex)
    }

    /**
     * "同时在播"面板：直接切到另一场正在直播的比赛。
     * 先拉该场线路，拉完自动播第一条；同时把详情页状态同步过去，
     * 这样用户从播放页返回看到的是刚切过来的这场，而不是上一场。
     */
    fun playMatch(match: Match) {
        playJob?.cancel()
        // 换场后，上一场“直连失败退回网页”的宽限计时不再有意义
        cancelSniffRecovery()
        loadJob?.cancel()
        // 详情页那条拉线路的任务也要停：否则它晚回来会把**上一场**的线路
        // 写进当前详情页
        detailJob?.cancel()
        // 在途的列表刷新也要停：它和本场校正都在写 _listState，
        // 两边整表替换会互相抹掉对方的修正结果
        refreshJob?.cancel()
        _listState.update { it.copy(loading = false, refreshing = false) }
        failedSources.clearAll()
        // 换了一场比赛：上一场的"自动换线试过哪些线路"作废
        autoTriedIndices.clear()
        // ⚠️ 记住**来源**而不是写死 DETAIL：
        // 列表卡片「立即观看」、直播页、搜索页、"同时在播"面板切场
        // 都是从 LIST 直达 PLAYER，返回时若回 DETAIL，
        // 用户会掉进一个从未见过的详情页。
        // 已经在 PLAYER（"同时在播"切场）时保持原来源不变，
        // 否则返回键会把用户卡在 PLAYER 与 LIST 之间来回跳。
        // ⚠️ 标签不变：播放页是盖在**当前标签**上的（从「直播」点进去，
        // 返回还是「直播」）—— 这是 P2-3 要求的"一次迁移"。
        setNav(_nav.value.openPlayer(match.id))
        _detailState.value = MatchDetailUiState(match = match, loadingSignals = true)
        _playerState.value = PlayerUiState(match = match, resolving = true)
        loadJob = viewModelScope.launch {
            val detail = try {
                loadSignals(match)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 畸形地址 / 解析器回归会让拉线路抛异常：
                // 没有兜底时它会逃逸出 viewModelScope 直接闪退
                safeLogWarn("MainViewModel", "loadSignals failed", t)
                _detailState.update {
                    it.copy(loadingSignals = false, error = "加载线路失败：${safeMessage(t)}")
                }
                _playerState.update {
                    it.copy(resolving = false)
                        .withError("加载线路失败：${t.message ?: "网络异常"}")
                }
                return@launch
            }
            // 拉线路是挂起点：期间用户可能已经按返回离开了播放页。
            // 取消发生在挂起返回之后时，后面的同步代码仍会跑完，
            // 所以写状态前必须确认自己还是"当前那个"，也不能擅自起播。
            if (!isActive) return@launch
            if (_route.value != Route.PLAYER) return@launch
            // 详情页带回来的开赛时间可用于校正列表里滞后的"直播中"
            val fixed = detail.correction
                ?.let { c -> match.copy(status = c.status, startAtMillis = c.startAtMillis) }
                ?: match
            fun fix(m: Match): Match = if (m.id == match.id) fixed else m
            _listState.update { st -> st.copy(matches = st.matches.map(::fix)) }
            // 关掉的源发出的线路不许进播放页（判据见 allowedSignals）
            val playable = allowedSignals(detail.signals)
            _detailState.value = MatchDetailUiState(
                match = fixed,
                signals = playable,
                loadingSignals = false,
            )
            _playerState.update { it.copy(match = fixed, signals = playable) }
            if (playable.isEmpty()) {
                _playerState.update {
                    it.copy(resolving = false, playbackDead = true)
                        .withError("该赛事暂无可用线路")
                }
            } else {
                // 默认线路：优先选能直连的那条，省一次 WebView 往返。
                // （用户手动点某条线路时仍然按用户的选择走，见 play()）
                val preferred = playable.indexOfFirst { it.isDirectHls }
                play(if (preferred >= 0) preferred else 0)
            }
        }
    }

    /**
     * 播放某条线路，并带**自动故障切换**：
     * 从当前线路开始依次尝试可直连的线路，第一个真正拉到清单的胜出；
     * 全部失败才降级为 WebView 承载源站播放页。
     *
     * ⚠️ "用户选中的那条"和"其它可直连线路"必须分开：
     *  - 选中的是**直连**线路：解析它；它确实失败才自动切到别的直连线路（这是故障切换）
     *  - 选中的是**只能网页播放**的线路：不自动切走。用户明确点了它，
     *    之前却先播别的线路、再把用户选的那条报成"不可用"——
     *    那不是故障切换，是把用户的选择吞了。这种情况直接交给 WebView 承载。
     */
    private fun play(index: Int) {
        playJob?.cancel()
        cancelSniffRecovery()
        playJob = viewModelScope.launch {
            val state = _playerState.value
            if (state.signals.isEmpty()) {
                _playerState.update {
                    it.copy(resolving = false, playbackDead = true)
                        .withError("该赛事暂无可用线路")
                }
                return@launch
            }
            val start = index.coerceIn(0, state.signals.lastIndex)
            val chosen = state.signals[start]
            // 之前已经确认这条播不了（被自动故障切换拉黑过）：不再重复尝试
            val chosenUsable = !isSourceFailed(chosen.pageUrl)

            _playerState.update {
                it.copy(resolving = true, streamUrl = null, streamReferer = null,
                    webFallbackUrl = null, sniffedStream = false, failedSniffUrl = null,
                    // 重新开始解析：上一轮"播不出来 / 疑似异常"的判定全部作废
                    playbackDead = false, playbackSuspect = false)
                        .withError(null)
            }

            // 候选顺序：用户选中的那条优先，其余可直连线路依次兜底。
            // 选中的是网页线路时不生成任何候选——尊重用户的选择。
            //
            // ⚠️ 但"网页线路"如果**上一次已经嗅探成功过**，就用缓存里的 m3u8 直接起播：
            // 那条 WebView 链路（加载页面 → 页面播放器发 m3u8）实测 0.1~27 秒，
            // 而缓存的地址是同一页面发出的、有效期往往还有几小时。
            // 直连真的失败了会走 onPlaybackFailed → invalidate，下次回网页重新嗅探。
            if (!chosen.isDirectHls) {
                sniffedPlaylists.get(chosen.pageUrl, wallNowMs())?.let { hit ->
                    _playerState.update {
                        it.copy(
                            resolving = false,
                            selectedIndex = start,
                            streamUrl = hit.m3u8Url,
                            streamReferer = hit.referer,
                            webFallbackUrl = null,
                            // 与嗅探升级同语义：这条流失败时要退回**这个播放页**
                            sniffedStream = true,
                            failedSniffUrl = null,
                        ).withError(null)
                    }
                    return@launch
                }
            }
            val ordered = buildList {
                if (chosen.isDirectHls) {
                    if (chosenUsable) add(chosen)
                    state.signals.forEachIndexed { i, s ->
                        if (i != start && s.isDirectHls && !isSourceFailed(s.pageUrl)) add(s)
                    }
                }
            }

            // ⚠️ 故障切换有**次数上限**：
            // 每条直连线路的解析代价是"最多 4 跳 + 最多 5 个候选"≈ 9 次请求，
            // 全部扫一遍就是 9D 次。没有上限时：
            //  - 15 秒看门狗每次触发都会重扫一遍 → 9D²
            //  - 用户每点一次"重连"再扫一遍 → 9D × 点击次数
            // 这些请求全打在源站上，是最容易触发限流的路径。
            // 扫完一轮还不行就直接降级到网页承载（那条路不消耗解析请求）。
            val candidates = ordered.take(MAX_FALLBACK_CANDIDATES)

            // 解析过程中出的异常（畸形 m3u8 地址会让 OkHttp 抛 IllegalArgumentException）
            // 不能逃逸出协程：那会直接闪退。记下来，落到错误提示里。
            var resolveError: String? = null
            for ((offset, candidate) in candidates.withIndex()) {
                val resolved = try {
                    resolveStream(candidate)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    safeLogWarn("MainViewModel", "resolveStream failed", t)
                    // ⚠️ 用脱敏后的 message：这个字符串会落到 errorMessage 显示在界面上
                    resolveError = safeMessage(t)
                    null
                }
                // 解析是挂起点：期间用户可能已切线路（本协程被 cancel）。
                // 取消发生在挂起返回之后时，剩余同步代码仍会跑完，
                // 所以写终态前必须再确认自己还是"最新的那个"。
                if (!isActive) return@launch
                if (resolved != null) {
                    val idx = state.signals.indexOfFirst { it.pageUrl == candidate.pageUrl }
                    _playerState.update {
                        it.copy(
                            resolving = false,
                            selectedIndex = if (idx >= 0) idx else it.selectedIndex,
                            streamUrl = resolved.m3u8Url,
                            streamReferer = resolved.referer,
                            webFallbackUrl = null,
                        ).withError(
                            // 只有"选中的那条确实失败过"才提示切换；
                            // 否则用户会看到一条自己从没点过的线路被说成"不可用"
                            if (offset > 0 || !chosenUsable) {
                                "${chosen.label} 不可用，已自动切换到 ${candidate.label}"
                            } else {
                                null
                            },
                        )
                    }
                    return@launch
                }
            }

            // 走 WebView 承载。注意区分三种情况：
            //  - 解析抛了异常：告诉用户是解析失败（可能是源站改版）
            //  - 本来就没有直连候选（JRS / 加密线路）：这是该线路的正常播放方式，不打扰用户
            //  - 有直连候选但全部解析失败：才提示"直连不可用"
            if (!isActive) return@launch
            val hadDirectCandidates = ordered.isNotEmpty()
            _playerState.update {
                it.copy(
                    resolving = false,
                    streamUrl = null,
                    streamReferer = null,
                    // 兜底的网页承载对应的是"用户选中的那条"，
                    // selectedIndex 必须跟着它走，
                    // 否则"线路 N/M"显示的是另一条（playMatch 的默认线路可能不是第 0 条）
                    selectedIndex = start,
                    webFallbackUrl = chosen.pageUrl,
                    sniffedStream = false,
                    // ⚠️ 网页兜底**不算**彻底失败：源站播放器刚加载，
                    // 它自己会去拉流、也可能报"网络错误"。
                    // 那时 PlayerScreen 会通过 onPlaybackError 上报，才轮到 playbackDead=true。
                    // 这里若急着置 true，会在网页还没加载完时就把小窗收掉/禁止自动小窗。
                    playbackDead = false,
                    playbackSuspect = false,
                ).withError(
                    when {
                        resolveError != null -> "解析失败：${resolveError}"
                        hadDirectCandidates -> "所有直连线路均不可用，已切换为内嵌播放"
                        else -> null
                    },
                )
            }
        }
    }

    /**
     * WebView 里源站 JS 解密后真正请求的 m3u8 被嗅探到。
     *
     * 立即升级成 ExoPlayer 直连：更稳（原生 HLS 自适应/秒开）、
     * 画中画/全屏可用、且甩掉网页里的博彩广告浮层。
     * 只保留 WebView 作为直连失败时的最终兜底。
     */
    fun onStreamSniffed(m3u8Url: String, referer: String?) {
        // ⚠️ 页面又开始发流了 = 它没有停在"直连失败的死页"上：
        // 立刻取消宽限计时（哪怕这次地址因防回环被忽略），否则 8 秒后会把
        // 一条其实恢复了的线路判死并换走。
        cancelSniffRecovery()
        val state = _playerState.value
        // 只处理"正在网页播放"时的嗅探；已经在直连播或正在解析就忽略
        if (state.webFallbackUrl == null || state.streamUrl != null || state.resolving) return
        // 同一个坏地址不重复升级（防回环）
        if (m3u8Url == state.failedSniffUrl) return
        // ⚠️ 同一播放页升级失败过就不再升级：
        // m3u8 带时效签名，每轮嗅探到的地址都不同，
        // 只挡"同一个 URL"挡不住 升级→直连失败→退回网页→再嗅探→再升级 的回环
        // （每轮都销毁重建 WebView、重载源站页，画面闪黑、耗流量）
        if (state.failedSniffPage != null && state.failedSniffPage == state.webFallbackUrl) return
        // ⚠️ referer 归属校验（review/01-player.md P2-4）：
        // 切网页线路时旧页面的在途请求可能晚到，把**旧线路**的流当成新线路升级，
        // 界面显示线路 B、实际播的是已判失败的线路 A。
        // WebSurface 侧已用 key(webFallbackUrl) 重建 WebView 兜住，
        // 这里再加一道：嗅探到的流的 Referer 必须属于当前播放页，
        // 否则忽略（拿不到 referer 时放行 —— 不能因为缺信息就丢掉真流）。
        if (referer != null && !belongsToPage(referer, state.webFallbackUrl!!)) {
            Log.w("MainViewModel", "忽略不属于当前播放页的嗅探结果（P2-4）")
            return
        }
        _playerState.update {
            it.copy(
                streamUrl = m3u8Url,
                streamReferer = referer ?: it.streamReferer,
                webFallbackUrl = null,
                sniffedStream = true,
            ).withError(null)
        }
        // 记住"这个播放页能解出真流"：下次点开同一场直接起播，不再走 WebView 那条
        // 0.1~27 秒的链路（见 [SniffedPlaylistCache]）。直连失败时会被 invalidate。
        sniffedPlaylists.put(
            pageUrl = state.webFallbackUrl!!,
            m3u8Url = m3u8Url,
            referer = referer,
            nowWallMs = wallNowMs(),
        )
    }

    /**
     * 嗅探到的流的 Referer 是否属于当前播放页。
     *
     * 只比**注册域**（不是完整 URL）：播放页与 m3u8 常在同一站点的不同 host 上
     * （列表页在 `jrszbt.com`、播放页在 `play.sportsteam*.com`），
     * 比完整 URL 会把正常升级全部挡掉。
     */
    private fun belongsToPage(referer: String, pageUrl: String): Boolean {
        fun host(u: String): String =
            runCatching { java.net.URI(u).host.orEmpty() }.getOrDefault("").lowercase()
        val refHost = host(referer)
        val pageHost = host(pageUrl)
        if (refHost.isEmpty() || pageHost.isEmpty()) return true // 解析不了就放行
        return refHost == pageHost || refHost.endsWith("." + pageHost) || pageHost.endsWith("." + refHost)
    }

    /**
     * 网页线路**疑似**播不出来（25 秒没嗅探到任何流）。
     *
     * ⚠️ 这是 P0-1 的核心分界：嗅探超时是**推断**，不是播放器报的事实，
     * 所以这里只置 [PlayerUiState.playbackSuspect]，**不**置 `playbackDead`。
     * 后果区别很大：
     *  - 疑似：显示提示 + 停掉「按 Home 自动进小窗」，**不动已经在播的小窗**；
     *  - 确认（[onPlaybackDead]）：才允许收掉小窗。
     *
     * 为什么必须这么分：至少三种正常在播的情况会嗅探不到流 ——
     * 源站用 MSE/`blob:` 播放；m3u8 地址不带 `.m3u8` 字面量；弱网首帧超过 25 秒。
     * 旧代码把它们一并判死，然后用 `finishAndRemoveTask()` 移除整个任务，
     * 用户正在看的东西连同入口一起消失且找不回来。
     *
     * 不改线路、不重试：这条路本来就是最后的兜底。
     */
    /**
     * 播放页的一次性提示（review/01-player.md P2-6）。
     *
     * 用于"用户明确操作了、但当前条件不满足"这类反馈 ——
     * 例如点了「画中画」但线路还没解析出流。
     * 走 [PlayerUiState.errorMessage] + errorId 这条通道：
     * 界面已有 snackbar 消费它，不需要新增一套提示机制。
     *
     * ⚠️ 必须走 withError（而不是直接 copy message）：errorId 自增才能让
     * 同样的文案连着弹两次（StateFlow 会合并相等值）。
     */
    fun showPlayerNotice(message: String) {
        _playerState.update { it.withError(message) }
    }

    fun onPlaybackSuspect() {
        val state = _playerState.value
        // 已经在直连播了就不管：网页组件可能滞后于嗅探升级才回调
        if (state.streamUrl != null) return

        // 同一条线路只处理一次：播放器的疑似回调可能连发多次
        if (state.playbackSuspect) return
        _playerState.update { it.markPlaybackSuspect(true) }

        // ⚠️ 线上黑屏修复（2026-10-29）：光置"疑似"会让用户对着黑屏干等。
        //
        // 源站首条线路（多数是 JRS）只能走 WebView，而它依赖的
        // `document.write` 跨站脚本会被 chromium 拦截 → 永远嗅不到媒体 →
        // 旧行为只是显示一句提示，**从不换线**，而同一场的 JRKAN 线路
        // 明明可以静态解析出真 m3u8（已用真实播放页验证）。
        // 现在按 [PlaybackAutoAdvance] 的判据换下一条，每条只试一次。
        //
        // 判据里 `hasDirectStream = false` 是**写死的**：上面那行
        // `streamUrl != null` 已经提前返回，走到这里必然是"一条流都没有"。
        advanceAfterNoSignal(state, "无信号")
    }

    /**
     * 源站播放器**自己画出了错误面板**（2026-10-31「在这个页面点刷新没有反应」）。
     *
     * ## 现场
     *
     * 网页兜底播放时 xgplayer 报错，画一块「网络错误 / 请试试 刷新」。
     * 点那个「刷新」**什么都不发生**：`retry()` 只是把**同一个** `config.url`
     * 重新赋给 `<video>`，而那个地址已经失效（见 [WebPageDeadEndPolicy] 的注释）。
     * 源站页面里也没有任何 `location.reload()`，所以它永远不会自己好。
     *
     * ## 与另外两条路径的区别
     *
     * | 回调 | 判据来源 | 处置 |
     * |---|---|---|
     * | [onPlaybackDead] | WebView 主框架真实加载失败（事实） | 可收小窗 |
     * | [onPlaybackSuspect] | 嗅探超时（**推断**） | 只提示 + 换线 |
     * | 本回调 | 页面 DOM 里的错误状态类（事实） | 换线（用户也可先点「重新加载」） |
     *
     * 走的是与"嗅探超时"同一套 [PlaybackAutoAdvance] 判据：每条线只试一次，
     * 全部试完给一句可执行的提示，不会无限换线把源站打出限流。
     */
    fun onPageDeadEnd() {
        val state = _playerState.value
        // 已经在直连播了就不管（页面组件可能滞后于嗅探升级才回调）
        if (state.streamUrl != null) return
        // 同一条线路只处理一次：探测是每秒轮询的，回调可能连发
        if (state.playbackSuspect) return
        _playerState.update { it.markPlaybackSuspect(true) }
        advanceAfterNoSignal(state, WebPageDeadEndPolicy.advanceNote())
    }

    /**
     * 「这条线没有信号了」的统一处置：按 [PlaybackAutoAdvance] 判据换下一条 / 或给明确提示。
     *
     * @param note 拼进提示里的原因（"无信号" / "直连失败后网页也没有恢复"），
     *   两种触发路径的文案必须能区分，否则用户不知道是嗅探超时还是直连挂了。
     */
    private fun advanceAfterNoSignal(state: PlayerUiState, note: String) {
        when (
            val decision = PlaybackAutoAdvance.decide(
                signalCount = state.signals.size,
                currentIndex = state.selectedIndex,
                hasDirectStream = false,
                suspect = true,
                alreadyTried = autoTriedIndices,
            )
        ) {
            is PlaybackAutoAdvance.Decision.Advance -> {
                val current = state.selectedIndex
                autoTriedIndices += current
                // 记进失败账本：`play` 会跳过被拉黑的线路，避免又转回同一条死线
                state.signals.getOrNull(current)?.let { failedSources.markFailed(it.pageUrl, nowMs()) }
                _playerState.update {
                    it.withError("线路 ${current + 1} $note，正在切换线路 ${decision.nextIndex + 1}")
                }
                selectSignal(decision.nextIndex)
            }
            PlaybackAutoAdvance.Decision.Exhausted -> {
                _playerState.update {
                    it.withError("所有线路都没有信号，请稍后重试或换一场")
                }
            }
            PlaybackAutoAdvance.Decision.Wait -> Unit
        }
    }

    /**
     * 直连失败退回网页后开一个**有界**恢复计时（判据见 [SniffRecoveryPolicy]）。
     *
     * 到点时再读一次最新状态：只有"仍然没有流 + 还停在这个失败播放页 + 不在解析中"
     * 才换线。期间页面重新发流（[onStreamSniffed]）、用户换线/返回（[play]/[back]）
     * 都会取消这个计时。
     */
    private fun startSniffRecovery(failedPageUrl: String?) {
        cancelSniffRecovery()
        if (failedPageUrl == null) return
        sniffRecoveryJob = viewModelScope.launch {
            delay(SniffRecoveryPolicy.GRACE_MS)
            val now = _playerState.value
            if (SniffRecoveryPolicy.shouldAdvance(
                    elapsedSinceRevertMs = SniffRecoveryPolicy.GRACE_MS,
                    hasStream = now.streamUrl != null,
                    stillOnFailedPage = now.webFallbackUrl == failedPageUrl,
                    resolving = now.resolving,
                )
            ) {
                advanceAfterNoSignal(now, SniffRecoveryPolicy.advanceNote())
            }
        }
    }

    /**
     * 网页线路**确认**播不出来（播放页主框架加载失败，错误码经过筛选）。
     *
     * 页面都打不开，不可能有流 —— 这是 WebView 自己报的失败，属于**事实**，
     * 因此允许置 [PlayerUiState.playbackDead]，上层据此关掉自动进小窗并收掉坏窗。
     * 处置仍是可逆的：只 `finish()`，任务保留在最近任务里（见 MainActivity）。
     *
     * 不改线路、不重试：这条路本来就是最后的兜底，
     * 用户要恢复就点"重连"或手动换线。
     */
    fun onPlaybackDead() {
        val state = _playerState.value
        // 已经在直连播了就不管：网页组件可能滞后于嗅探升级才回调
        if (state.streamUrl != null) return
        if (state.playbackDead) return
        // 播放页主框架确认加载失败：这是"事实"，按现有设计不自动换线（用户手动重连/换线），
        // 所以这条线的恢复计时也该停，别再叠加一次自动换线。
        cancelSniffRecovery()
        _playerState.update { it.markPlaybackDead(true).markPlaybackSuspect(false) }
    }

    /**
     * ExoPlayer 播放失败：把当前线路拉黑后自动换下一条。
     * 全部失败时给出明确提示，而不是一直转圈。
     */
    fun onPlaybackFailed() {
        // 正在解析/切换时忽略重复上报（播放器错误回调可能连发多次）
        val state = _playerState.value
        if (state.resolving) return
        // 嗅探升级的流失败：退回刚才还在播的 WebView，不拉黑线路、不再试同一地址
        if (state.sniffedStream) {
            val pageUrl = state.signals.getOrNull(state.selectedIndex)?.pageUrl
            // ⚠️ 这份缓存必须立刻作废：它记的正是刚刚 404 的那条地址。
            // 不作废的话，下次点开同一场会拿旧地址再失败一遍（"反复解析"）。
            pageUrl?.let(sniffedPlaylists::invalidate)
            _playerState.update {
                it.copy(
                    streamUrl = null,
                    streamReferer = null,
                    sniffedStream = false,
                    failedSniffUrl = it.streamUrl,
                    // 记下是**哪个播放页**升级失败的：
                    // m3u8 每轮签名都不同，只记 URL 挡不住回环
                    failedSniffPage = pageUrl ?: it.failedSniffPage,
                    webFallbackUrl = pageUrl,
                    // 退回网页后网页还在加载，不是"彻底死了"，也还没到"疑似"
                    playbackDead = false,
                    playbackSuspect = false,
                    // ⚠️ 必须给用户一句提示：
                    // 这里只 copy 不 withError 的话 errorId 不变，
                    // 界面的 LaunchedEffect 不会重启 —— 用户只看到画面从直连变回网页，
                    // 完全不知道发生了什么（实测：静默退回，没有任何提示）。
                ).withError("直连播放失败，已退回网页播放")
            }
            // ⚠️ 2026-10-30：退回网页**不能就此一直等**。
            // 实测：嗅探到的 m3u8 返回 404 → 退回网页 → 页面自己也画"网络错误"，
            // 而同页已禁止再升级、页面又确实发过媒体请求（25s 嗅探看门狗不会触发）
            // —— 界面永久停在源站的错误页，用户只能手动换线（实测挂了 5 分钟没动静）。
            // 现在给一个有界宽限：期间页面重新发流会取消计时（见 onStreamSniffed），
            // 否则 [SniffRecoveryPolicy.GRACE_MS] 后按"无信号"判据换下一条。
            startSniffRecovery(pageUrl)
            return
        }
        state.signals.getOrNull(state.selectedIndex)?.let { failedSources.markFailed(it.pageUrl, nowMs()) }
        _playerState.update {
            it.copy(streamUrl = null, streamReferer = null, webFallbackUrl = null)
        }
        // 还有别的线路要试：这轮是"故障切换"而不是"彻底失败"。
        // 但若没有任何可直连线路剩下，play() 会走网页兜底 —— 那时也不算死。
        // 真正的"彻底死了"由网页播放器自己的错误回调置上。
        play(state.selectedIndex)
    }

    // ---------- 返回 ----------

    fun back() {
        when (_nav.value.route) {
            Route.PLAYER -> {
                playJob?.cancel()
                loadJob?.cancel()
                // ⚠️ detailJob 也要取消（review/02-data.md P2-5）：
                // 用户点「刷新线路」后立刻进播放页再返回，这个 job 可能在返回后
                // 才落地并写 _detailState —— 界面已经离开详情页了，
                // 却还在被一个早该结束的请求改状态。
                detailJob?.cancel()
                // 离开播放页：这条线的"直连失败恢复计时"必须停，
                // 否则计时到点会去换一条用户已经不看的天。
                cancelSniffRecovery()
                // ⚠️ 回到**进入播放页之前**的路由，不是无条件回 DETAIL。
                // 从列表「立即观看」直接播放时，回 DETAIL 会掉进
                // 用户从未见过的详情页（要再按一次返回才到列表）。
                // 来源与迁移判据都在 NavState 里（有单测）。
                setNav(_nav.value.back())
                // 播放态要清干净：直连地址、兜底地址、错误提示都不能留在状态里
                // （错误提示原来只靠 UI 层的 route==PLAYER 条件遮住，是隐性不变量）
                _playerState.update {
                    it.copy(
                        streamUrl = null,
                        streamReferer = null,
                        webFallbackUrl = null,
                        resolving = false,
                        sniffedStream = false,
                        failedSniffUrl = null,
                        failedSniffPage = null,
                        playbackDead = false,
                        playbackSuspect = false,
                    ).withError(null)
                }
                // ⚠️ 进场拉线路途中按返回：loadJob 被取消，它尾部那句
                // loadingSignals=false 永远不会执行——这里必须复位，
                // 否则详情页信号区永久停在"正在获取信号列表…"
                _detailState.update { it.copy(loadingSignals = false, error = null) }
            }
            Route.DETAIL -> {
                detailJob?.cancel()
                setNav(_nav.value.back())
                _detailState.update { it.copy(loadingSignals = false, error = null) }
            }
            Route.LIST -> Unit
        }
    }
}
