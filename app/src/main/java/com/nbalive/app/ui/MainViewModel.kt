package com.nbalive.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.repository.MatchRepository
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
    val loading: Boolean = true,
    val error: String? = null,
    val matches: List<Match> = emptyList(),
    val selectedLeague: League = League.ALL,
) {
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
    val errorMessage: String? = null,
)

class MainViewModel(
    private val repository: MatchRepository = MatchRepository(),
) : ViewModel() {

    private val _route = MutableStateFlow(Route.LIST)
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _listState = MutableStateFlow(MatchListUiState())
    val listState: StateFlow<MatchListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(MatchDetailUiState())
    val detailState: StateFlow<MatchDetailUiState> = _detailState.asStateFlow()

    /** 已确认播不出来的线路，重试时跳过，避免死循环 */
    private val failedSources = mutableSetOf<String>()

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    /**
     * 同一时刻只允许一个解析任务：用户快速连点线路时，
     * 慢的那个解析若后完成，会把快的那个（用户最新选择）覆盖掉，
     * 甚至把已经播起来的直连流换成 WebView。
     */
    private var playJob: Job? = null
    private var detailJob: Job? = null
    private var loadJob: Job? = null

    init {
        refresh()
    }

    // ---------- 列表 ----------

    fun refresh() {
        viewModelScope.launch {
            _listState.update { it.copy(loading = true, error = null) }
            repository.loadMatches()
                .onSuccess { matches ->
                    _listState.update {
                        it.copy(loading = false, error = null, matches = matches)
                    }
                }
                .onFailure { e ->
                    _listState.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: "网络异常，请稍后重试",
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
        detailJob?.cancel()
        _detailState.update { it.copy(loadingSignals = true) }
        detailJob = viewModelScope.launch {
            val detail = repository.loadSignals(match)
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
        _playerState.update {
            it.copy(selectedIndex = index, resolving = true, errorMessage = null)
        }
        play(index)
    }

    /**
     * 重连：按当前线路重新解析并播放。
     * 直播地址带时效签名，过期后画面卡住/黑屏，重连可拿新地址；
     * 同时清除该线路的失败记录（用户主动重连 = 再给一次机会）。
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
        failedSources.clear()
        _route.value = Route.PLAYER
        _detailState.value = MatchDetailUiState(match = match, loadingSignals = true)
        _playerState.value = PlayerUiState(match = match, resolving = true)
        loadJob = viewModelScope.launch {
            val detail = repository.loadSignals(match)
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
                    it.copy(resolving = false, errorMessage = "该赛事暂无可用线路")
                }
            } else {
                play(0)
            }
        }
    }

    /**
     * 播放某条线路，并带**自动故障切换**：
     * 从当前线路开始依次尝试可直连的线路，第一个真正拉到清单的胜出；
     * 全部失败才降级为 WebView 承载源站播放页。
     */
    private fun play(index: Int) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val state = _playerState.value
            if (state.signals.isEmpty()) {
                _playerState.update { it.copy(resolving = false, errorMessage = "该赛事暂无可用线路") }
                return@launch
            }
            val start = index.coerceIn(0, state.signals.lastIndex)

            _playerState.update {
                it.copy(resolving = true, streamUrl = null, streamReferer = null,
                    webFallbackUrl = null, sniffedStream = false, failedSniffUrl = null,
                    errorMessage = null)
            }

            // 候选顺序：当前线路优先，其余可直连线路依次兜底
            val ordered = buildList {
                add(state.signals[start])
                state.signals.forEachIndexed { i, s -> if (i != start && s.isDirectHls) add(s) }
            }.filter { it.isDirectHls && it.pageUrl !in failedSources }

            for ((offset, candidate) in ordered.withIndex()) {
                val resolved = repository.resolveStream(candidate)
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
                            errorMessage = if (offset > 0) {
                                "${state.signals[start].label} 不可用，已自动切换到 ${candidate.label}"
                            } else {
                                null
                            },
                        )
                    }
                    return@launch
                }
            }

            // 走 WebView 承载。注意区分两种情况：
            //  - 本来就没有直连候选（JRS / 加密线路）：这是该线路的正常播放方式，不打扰用户
            //  - 有直连候选但全部解析失败：才提示"直连不可用"
            if (!isActive) return@launch
            val hadDirectCandidates = ordered.isNotEmpty()
            val fallback = state.signals[start]
            _playerState.update {
                it.copy(
                    resolving = false,
                    streamUrl = null,
                    streamReferer = null,
                    webFallbackUrl = fallback.pageUrl,
                    sniffedStream = false,
                    errorMessage = if (hadDirectCandidates) {
                        "所有直连线路均不可用，已切换为内嵌播放"
                    } else {
                        null
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
        _playerState.update {
            it.copy(
                streamUrl = m3u8Url,
                streamReferer = referer ?: it.streamReferer,
                webFallbackUrl = null,
                sniffedStream = true,
                errorMessage = null,
            )
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
                    webFallbackUrl = pageUrl,
                )
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
                _playerState.update {
                    it.copy(streamUrl = null, streamReferer = null, webFallbackUrl = null, resolving = false)
                }
            }
            Route.DETAIL -> {
                detailJob?.cancel()
                _route.value = Route.LIST
            }
            Route.LIST -> Unit
        }
    }
}
