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
) {
    /** 列表是否正忙（首载或刷新中）：供刷新按钮显示进度 */
    val busy: Boolean get() = loading || refreshing

    val visibleMatches: List<Match>
        get() = when (selectedLeague) {
            League.ALL -> matches
            League.NBA -> matches.filter { it.league == League.NBA }
            League.CBA -> matches.filter { it.league == League.CBA }
            League.OTHER -> matches.filter { it.league == League.OTHER }
        }

    val counts: Map<League, Int>
        get() = mapOf(
            League.ALL to matches.size,
            League.NBA to matches.count { it.league == League.NBA },
            League.CBA to matches.count { it.league == League.CBA },
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
     * 错误提示的自增序号。
     *
     * 界面用它当 LaunchedEffect 的 key：只拿 [errorMessage] 当 key 时，
     * 同样的文案连着报两次，StateFlow 会合并相等值 → 第二次不再弹提示。
     */
    val errorId: Long = 0,
)

class MainViewModel(
    private val repository: MatchRepository = MatchRepository(),
    /**
     * 列表加载入口。生产默认走 [MatchRepository.loadMatches]；
     * 测试注入它以可控地驱动刷新时序（全程不触网）。
     */
    private val loadMatches: suspend () -> Result<List<Match>> = { repository.loadMatches() },
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

    private val _listState = MutableStateFlow(MatchListUiState())
    val listState: StateFlow<MatchListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(MatchDetailUiState())
    val detailState: StateFlow<MatchDetailUiState> = _detailState.asStateFlow()

    /**
     * 已确认播不出来的线路，重试时跳过，避免死循环。
     *
     * ⚠️ 只在主线程读写：所有写点都在 viewModelScope 的协程里（Main 调度器），
     * 不要把它挪到后台线程访问——普通 MutableSet 并发写会损坏内部结构。
     */
    private val failedSources = mutableSetOf<String>()

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
        refreshJob = viewModelScope.launch {
            // 刷新时**保留旧列表**：整屏换成转圈会把已有卡片全抹掉（滚动位置也丢）。
            // 只有首载（一条数据都还没有）才用全屏 loading。
            val firstLoad = _listState.value.matches.isEmpty()
            _listState.update {
                it.copy(loading = firstLoad, refreshing = !firstLoad, error = null)
            }
            try {
                loadMatches()
                    .onSuccess { matches ->
                        _listState.update {
                            it.copy(loading = false, refreshing = false, error = null, matches = matches)
                        }
                    }
                    .onFailure { e ->
                        _listState.update {
                            it.copy(
                                loading = false,
                                refreshing = false,
                                error = e.message ?: "网络异常，请稍后重试",
                            )
                        }
                    }
            } catch (ce: CancellationException) {
                // 协程被取消：收尾 loading/refreshing。
                // 取消发生的两个地方（refresh 自身重发、playMatch 切场）都会复位这两个标志，
                // 所以这条目前是**冗余保险**——但它让"取消必须收尾"这条约定落在代码里，
                // 以后新增取消点时不会忘了收尾。
                _listState.update { it.copy(loading = false, refreshing = false) }
                throw ce
            } catch (t: Throwable) {
                // 注入的加载入口抛异常时也不能闪退，更不能把列表清空
                Log.w("MainViewModel", "refresh failed: ${t.message}", t)
                _listState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = t.message ?: "网络异常，请稍后重试",
                    )
                }
            }
        }
    }

    fun selectLeague(league: League) {
        _listState.update { it.copy(selectedLeague = league) }
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
                Log.w("MainViewModel", "loadSignals failed: ${t.message}", t)
                _detailState.update {
                    it.copy(loadingSignals = false, error = "加载线路失败：${t.message ?: "网络异常"}")
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
        failedSources.clear()
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
        state.signals.getOrNull(index)?.let { failedSources.remove(it.pageUrl) }
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
        state.signals.getOrNull(state.selectedIndex)?.let { failedSources.remove(it.pageUrl) }
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
        failedSources.clear()
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
                Log.w("MainViewModel", "loadSignals failed: ${t.message}", t)
                _detailState.update {
                    it.copy(loadingSignals = false, error = "加载线路失败：${t.message ?: "网络异常"}")
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
                    it.copy(resolving = false).withError("该赛事暂无可用线路")
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
                    it.copy(resolving = false).withError("该赛事暂无可用线路")
                }
                return@launch
            }
            val start = index.coerceIn(0, state.signals.lastIndex)
            val chosen = state.signals[start]
            // 之前已经确认这条播不了（被自动故障切换拉黑过）：不再重复尝试
            val chosenUsable = chosen.pageUrl !in failedSources

            _playerState.update {
                it.copy(resolving = true, streamUrl = null, streamReferer = null,
                    webFallbackUrl = null, sniffedStream = false, failedSniffUrl = null)
                    .withError(null)
            }

            // 候选顺序：用户选中的那条优先，其余可直连线路依次兜底。
            // 选中的是网页线路时不生成任何候选——尊重用户的选择。
            val ordered = buildList {
                if (chosen.isDirectHls) {
                    if (chosenUsable) add(chosen)
                    state.signals.forEachIndexed { i, s ->
                        if (i != start && s.isDirectHls && s.pageUrl !in failedSources) add(s)
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
                    Log.w("MainViewModel", "resolveStream failed: ${t.message}", t)
                    resolveError = t.message
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
                    // ⚠️ 必须给用户一句提示：
                    // 这里只 copy 不 withError 的话 errorId 不变，
                    // 界面的 LaunchedEffect 不会重启 —— 用户只看到画面从直连变回网页，
                    // 完全不知道发生了什么（实测：静默退回，没有任何提示）。
                ).withError("直连播放失败，已退回网页播放")
            }
            return
        }
        state.signals.getOrNull(state.selectedIndex)?.let { failedSources += it.pageUrl }
        _playerState.update {
            it.copy(streamUrl = null, streamReferer = null, webFallbackUrl = null)
        }
        play(state.selectedIndex)
    }

    // ---------- 返回 ----------

    fun back() {
        when (_route.value) {
            Route.PLAYER -> {
                playJob?.cancel()
                loadJob?.cancel()
                _route.value = Route.DETAIL
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
