package com.nbalive.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.repository.MatchRepository
import com.nbalive.app.data.remote.StreamUrlResolver
import com.nbalive.app.data.remote.safeLogWarn
import com.nbalive.app.data.remote.safeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val loadOutcome: suspend () -> MatchRepository.LoadOutcome = {
        repository.loadMatchesOutcome()
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

    private val _route = MutableStateFlow(Route.LIST)
    val route: StateFlow<Route> = _route.asStateFlow()

    /**
     * 进入播放页**之前**所在的路由，决定返回键该回哪里。
     *
     * ⚠️ 历史 bug：`back()` 里 PLAYER 分支无条件回 DETAIL。
     * 但进入播放页有两条路：
     *  - 列表卡片的「立即观看」→ `playMatch()`，从 LIST 直接跳 PLAYER
     *  - 详情页的线路列表 → `openPlayer()`，DETAIL -> PLAYER
     * 前者按返回会掉进一个**用户从未见过的详情页**，再按一次才回列表。
     * 切场（"同时在播"面板）同样从 LIST 直达 PLAYER，问题更明显。
     *
     * 所以必须记住来源，而不是写死 DETAIL。
     */
    private var routeBeforePlayer: Route = Route.DETAIL

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

    /** 单调时钟（毫秒）：不受用户改系统时间影响，与 [SourceFailureBook] 共用。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** 这条线现在还能不能被自动选中。 */
    private fun isSourceFailed(pageUrl: String): Boolean = failedSources.isFailed(pageUrl, nowMs())

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

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
                    val outcome = loadOutcome()
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
     * 详情页「更多 -> 刷新线路」：重新拉一次该场次的信号列表。
     * 源站偶尔会补线路，重进页面不一定能刷新到，给个显式入口。
     */
    fun refreshSignals() {
        val match = _detailState.value.match ?: return
        // ⚠️ 用户显式刷新：先让详情页缓存失效，随后的 loadSignals 才会真的回源。
        // playMatch()（首次进场）走缓存 —— 这两条路径就此分开，
        // 否则 5 分钟 TTL 内点"刷新线路"拿到的是同一份旧 HTML，
        // 源站后补的线路永远看不到，按钮成了摆设。
        match.jrsDetailId?.let { repository.invalidateDetailCache(it) }
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
            _detailState.update { it.copy(signals = detail.signals, loadingSignals = false) }
        }
    }

    // ---------- 播放 ----------

    fun openPlayer(index: Int) {
        val detail = _detailState.value
        val match = detail.match ?: return
        failedSources.clearAll()
        // 从详情页的线路列表进来：返回键该回详情页
        routeBeforePlayer = Route.DETAIL
        _route.value = Route.PLAYER
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
        loadJob?.cancel()
        // 详情页那条拉线路的任务也要停：否则它晚回来会把**上一场**的线路
        // 写进当前详情页
        detailJob?.cancel()
        // 在途的列表刷新也要停：它和本场校正都在写 _listState，
        // 两边整表替换会互相抹掉对方的修正结果
        refreshJob?.cancel()
        _listState.update { it.copy(loading = false, refreshing = false) }
        failedSources.clearAll()
        // ⚠️ 记住**来源**而不是写死 DETAIL：
        // 列表卡片「立即观看」、直播页、搜索页、"同时在播"面板切场
        // 都是从 LIST 直达 PLAYER，返回时若回 DETAIL，
        // 用户会掉进一个从未见过的详情页。
        // 已经在 PLAYER（"同时在播"切场）时保持原来源不变，
        // 否则返回键会把用户卡在 PLAYER 与 LIST 之间来回跳。
        if (_route.value != Route.PLAYER) {
            routeBeforePlayer = _route.value
        }
        _route.value = Route.PLAYER
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
            _detailState.value = MatchDetailUiState(
                match = fixed,
                signals = detail.signals,
                loadingSignals = false,
            )
            _playerState.update { it.copy(match = fixed, signals = detail.signals) }
            if (detail.signals.isEmpty()) {
                _playerState.update {
                    it.copy(resolving = false, playbackDead = true)
                        .withError("该赛事暂无可用线路")
                }
            } else {
                // 默认线路：优先选能直连的那条，省一次 WebView 往返。
                // （用户手动点某条线路时仍然按用户的选择走，见 play()）
                val preferred = detail.signals.indexOfFirst { it.isDirectHls }
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
        if (state.playbackSuspect) return
        _playerState.update { it.markPlaybackSuspect(true) }
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
        when (_route.value) {
            Route.PLAYER -> {
                playJob?.cancel()
                loadJob?.cancel()
                // ⚠️ detailJob 也要取消（review/02-data.md P2-5）：
                // 用户点「刷新线路」后立刻进播放页再返回，这个 job 可能在返回后
                // 才落地并写 _detailState —— 界面已经离开详情页了，
                // 却还在被一个早该结束的请求改状态。
                detailJob?.cancel()
                // ⚠️ 回到**进入播放页之前**的路由，不是无条件回 DETAIL。
                // 从列表「立即观看」直接播放时，回 DETAIL 会掉进
                // 用户从未见过的详情页（要再按一次返回才到列表）。
                _route.value = routeBeforePlayer
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
                _route.value = Route.LIST
                _detailState.update { it.copy(loadingSignals = false, error = null) }
            }
            Route.LIST -> Unit
        }
    }
}
