package com.nbalive.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nbalive.app.data.model.isDirectHls
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.HttpClient
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.theme.GradRed
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.theme.SportDark


/**
 * 播放器界面。
 *
 * 播放策略：
 *  - 若已解析出 m3u8 -> Media3 ExoPlayer 直连播放（JRKAN 线路）
 *  - 否则 -> WebView 承载源站播放页（JRS 线路，播放页由源站 JS 自行解密）
 */
@Composable
fun PlayerScreen(
    match: Match?,
    signals: List<StreamSource>,
    selectedIndex: Int,
    resolving: Boolean,
    streamUrl: String?,
    streamReferer: String?,
    webFallbackUrl: String?,
    errorMessage: String?,
    onSignalSelected: (Int) -> Unit,
    otherMatches: List<Match>,
    onOtherMatchClick: (Match) -> Unit,
    onPlaybackError: () -> Unit = {},
    onStreamSniffed: (String, String?) -> Unit = { _, _ -> },
    /** 嗅探升级失败退回网页：不再弹"暂无信号"提示 */
    sniffFailed: Boolean = false,
    aspectMode: AspectMode = AspectMode.FIT,
    keepScreenOn: Boolean = true,
    onAspectChange: (AspectMode) -> Unit = {},
    onKeepScreenOnChange: (Boolean) -> Unit = {},
    /** 重连：重新解析当前线路（直播地址带时效签名，过期后需要换新地址） */
    onReconnect: () -> Unit = {},
    /** 正在直播的其他场次（"同时在播"面板数据源） */
    liveMatches: List<Match> = emptyList(),
    onLiveMatchClick: (Match) -> Unit = {},
    fullscreen: Boolean = false,
    inPip: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val videoOnly = fullscreen || inPip

    // "同时在播"右侧面板
    var showLivePanel by remember { mutableStateOf(false) }
    // 播放源 / 播放线路 选择面板（不用 DropdownMenu：Popup 会被视频 SurfaceView 盖住）
    var picker by remember { mutableStateOf<PickerKind?>(null) }
    // 视频播放器句柄：自定义控制栏要读播放状态、要能暂停/继续
    val videoHandle = remember(streamUrl) { VideoPlayerHandle() }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (videoOnly) Modifier else Modifier.verticalScroll(rememberScrollState()))
            .then(if (videoOnly) Modifier else Modifier.padding(bottom = 24.dp)),
    ) {
        // ---------- 视频区 ----------
        // 注意：全屏/画中画只改这个 Box 的尺寸，不改变 VideoSurface 的调用位置，
        // 因此 ExoPlayer 实例不会被重建，切换全屏不会中断播放。
        Box(
            modifier = if (videoOnly) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            },
        ) {
            when {
                streamUrl != null -> key(streamUrl, streamReferer) {
                    VideoSurface(
                        streamUrl = streamUrl,
                        referer = streamReferer,
                        aspectMode = aspectMode,
                        handle = videoHandle,
                        onError = onPlaybackError,
                    )
                }
                webFallbackUrl != null -> WebSurface(
                    url = webFallbackUrl,
                    onStreamFound = onStreamSniffed,
                    suppressHint = sniffFailed,
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (resolving) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(Modifier.height(Space.md))
                            Text(
                                text = "正在解析直播源…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(Space.sm))
                            Text(
                                text = "选择下方信号开始播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }

            // 视频区顶部浮层：直播态 + 联赛（画中画里不显示，避免遮挡画面）
            if (match != null && !inPip) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    StatusChip(status = match.status)
                    LeagueChip(
                        text = match.leagueRawName.ifBlank { match.league.displayName },
                        league = match.league,
                    )
                }
            }

            // 右上角：画中画 / 全屏（画中画窗口内不再显示按钮）
            if (!inPip) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    VideoControlButton(
                        icon = Icons.Filled.PictureInPictureAlt,
                        description = "画中画",
                        onClick = onEnterPip,
                    )
                    VideoControlButton(
                        icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        description = if (fullscreen) "退出全屏" else "全屏",
                        onClick = onToggleFullscreen,
                    )
                }
            }

            // 缓冲指示：原生控制条已关闭，自己给一个转圈
            if (streamUrl != null && videoHandle.isBuffering && !inPip) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                )
            }

            // 底部自定义控制栏：只在**全屏播放**时显示。
            // 布局参考 yingshi 播放器：左侧圆形半透明图标按钮（播放/重连），
            // 右侧白描边功能胶囊（画面/源/线路/同时在播），一眼能读、按得准。
            // 关键：不再使用 Media3 原生控制条 —— 它自带快进/后退/进度条，
            // 直播流上误触会跳离直播边缘，而且不支持"重连/换源"这类直播操作。
            if (fullscreen && !inPip) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                            ),
                        )
                        .padding(horizontal = Space.lg, vertical = Space.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左：播放控制
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            // 播放 / 暂停：仅直连 ExoPlayer 可控（网页线路由源站页面自己管）
                            if (streamUrl != null) {
                                PlayerIconButton(
                                    icon = if (videoHandle.isPlaying) Icons.Filled.Pause
                                    else Icons.Filled.PlayArrow,
                                    description = if (videoHandle.isPlaying) "暂停" else "播放",
                                    onClick = { videoHandle.togglePlay() },
                                )
                            }
                            PlayerIconButton(
                                icon = Icons.Filled.Refresh,
                                description = "重连",
                                onClick = onReconnect,
                            )
                        }
                        // 右：功能胶囊
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 画面自适应：三档循环
                            PlayerChip(
                                label = "画面 ${aspectMode.label}",
                                onClick = {
                                    val next = AspectMode.entries[
                                        (aspectMode.ordinal + 1) % AspectMode.entries.size,
                                    ]
                                    onAspectChange(next)
                                },
                            )
                            if (signals.isNotEmpty()) {
                                PlayerChip(
                                    label = "源 ${signals.getOrNull(selectedIndex)?.provider?.displayName ?: "-"}",
                                    onClick = { picker = PickerKind.SOURCE },
                                )
                                PlayerChip(
                                    label = "线路 ${selectedIndex + 1}/${signals.size}",
                                    onClick = { picker = PickerKind.LINE },
                                )
                            }
                            PlayerChip(
                                label = "同时在播 ${liveMatches.count { it.id != match?.id }}",
                                onClick = { showLivePanel = true },
                            )
                        }
                    }
                }
            }
        }

        // 全屏 / 画中画下只留视频，其余内容不参与渲染
        if (!videoOnly) {

        // ---------- 赛事信息卡 ----------
        if (match != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Space.lg)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamLogo(match.homeLogo, match.homeTeam, size = 44.dp)
                Spacer(Modifier.width(Space.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${match.homeTeam} VS ${match.awayTeam}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            append(match.leagueRawName.ifBlank { match.league.displayName })
                            append(" · ")
                            append(match.timeLabel)
                            if (signals.isNotEmpty()) {
                                append(" · 共 ${signals.size} 条信号")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SportDark.Txt3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(Space.sm))
                TeamLogo(match.awayLogo, match.awayTeam, size = 44.dp)
            }
        }

        // ---------- 信号切换 ----------
        if (signals.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .padding(bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "信号切换",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "共 ${signals.size} 条线路",
                    style = MaterialTheme.typography.labelSmall,
                    color = SportDark.Txt3,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "当前 ${signals.getOrNull(selectedIndex)?.label ?: "-"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }

            // 线路可能多达 9 条：横向滚动、每条固定宽度，
            // 不再用 weight(1f) 把标签挤成"线…"
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                itemsIndexed(signals) { index, source ->
                    val active = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .width(76.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(Radius.btn))
                            .then(
                                if (active) Modifier.background(GradRed)
                                else Modifier.background(Color.Transparent),
                            )
                            .clickable { onSignalSelected(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = source.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) Color.White
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = source.provider.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) Color.White.copy(alpha = 0.8f) else SportDark.Txt3,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // ---------- 故障切换 / 错误提示 ----------
        if (errorMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .padding(top = Space.md)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(Color(0x1FFFC24B))
                    .border(1.dp, Color(0x4DFFC24B), RoundedCornerShape(Radius.card))
                    .padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = SportDark.Warn,
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = SportDark.Warn,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------- 画面设置 ----------
        if (streamUrl != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .padding(top = Space.md)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Space.md),
            ) {
                Text(
                    text = "画面",
                    style = MaterialTheme.typography.labelMedium,
                    color = SportDark.Txt3,
                )
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    AspectMode.entries.forEach { mode ->
                        val active = mode == aspectMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(Radius.btn))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.background,
                                )
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else SportDark.Line,
                                    RoundedCornerShape(Radius.btn),
                                )
                                .clickable { onAspectChange(mode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "屏幕常亮",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (keepScreenOn) "观赛期间不自动熄屏" else "跟随系统熄屏设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = SportDark.Txt3,
                        )
                    }
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = onKeepScreenOnChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }

        // ---------- 其他直播 ----------
        val others = otherMatches.filter { it.id != match?.id }.take(4)
        if (others.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .padding(top = Space.xxl, bottom = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "其他直播",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "今日赛事",
                    style = MaterialTheme.typography.labelSmall,
                    color = SportDark.Txt3,
                    maxLines = 1,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                others.forEach { other ->
                    OtherMatchRow(match = other, onClick = { onOtherMatchClick(other) })
                }
            }
        }
        } // end if (!videoOnly)
    } // end Column

    // ---------- 同时在播：右侧滑出面板 ----------
    // 全屏/画中画下也可用：换场次不必先退出全屏
    if (!inPip) {
        // 遮罩
        AnimatedVisibility(
            visible = showLivePanel,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { showLivePanel = false },
            )
        }
        AnimatedVisibility(
            visible = showLivePanel,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            LiveNowPanel(
                matches = liveMatches,
                currentMatchId = match?.id,
                onSelect = {
                    showLivePanel = false
                    onLiveMatchClick(it)
                },
                onClose = { showLivePanel = false },
            )
        }
    }

    // ---------- 播放源 / 播放线路 选择面板 ----------
    // 参考 yingshi 播放器：右侧竖排浮层。
    // 不用 DropdownMenu —— 它是独立 Popup 窗口，会被 PlayerView 的
    // SurfaceView 图层盖住（实测点不动也看不见）。
    if (picker != null) {
        val kind = picker
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { picker = null },
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.36f)
                .widthIn(max = 300.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                .padding(horizontal = Space.md, vertical = Space.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (kind == PickerKind.SOURCE) "播放源" else "播放线路",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { picker = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Spacer(Modifier.height(Space.xs))
            if (kind == PickerKind.SOURCE) {
                val currentProvider = signals.getOrNull(selectedIndex)?.provider
                val providers = signals.map { it.provider }.distinct()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(providers, key = { it.name }) { p ->
                        PickerRow(
                            label = p.displayName,
                            sub = "${signals.count { it.provider == p }} 条线路",
                            selected = p == currentProvider,
                            onClick = {
                                picker = null
                                val idx = signals.indexOfFirst { it.provider == p }
                                if (idx >= 0) onSignalSelected(idx)
                            },
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(signals, key = { _, s -> s.id }) { index, s ->
                        PickerRow(
                            label = s.label,
                            sub = "源站 ${s.provider.displayName}",
                            selected = index == selectedIndex,
                            onClick = {
                                picker = null
                                onSignalSelected(index)
                            },
                        )
                    }
                }
            }
        }
    }
    } // end Box
}

/** 选择面板类型 */
private enum class PickerKind { SOURCE, LINE }

/** 选择面板的一行：标题 + 副标题 + 选中勾（选中行整行高亮） */
@Composable
private fun PickerRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Radius.btn))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "同时在播"面板：右侧竖排列表，上下滑动选择要看的场次。
 * 只展示状态为直播中的场次，点一下直接切过去播。
 */
@Composable
private fun LiveNowPanel(
    matches: List<Match>,
    currentMatchId: String?,
    onSelect: (Match) -> Unit,
    onClose: () -> Unit,
) {
    val others = matches.filter { it.id != currentMatchId }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.78f)
            .widthIn(max = 320.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = Space.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = "同时在播",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(16.dp),
                    tint = SportDark.Txt3,
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            text = if (others.isEmpty()) "暂无其他正在直播的场次" else "共 ${others.size} 场正在直播",
            style = MaterialTheme.typography.labelSmall,
            color = SportDark.Txt3,
            modifier = Modifier.padding(horizontal = Space.lg),
        )
        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = SportDark.Line)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.md,
                vertical = Space.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(others, key = { it.id }) { m ->
                LiveNowRow(match = m, onClick = { onSelect(m) })
            }
        }
    }
}

@Composable
private fun LiveNowRow(match: Match, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(status = match.status)
            Spacer(Modifier.width(Space.xs))
            LeagueChip(
                text = match.leagueRawName.ifBlank { match.league.displayName },
                league = match.league,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = match.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = SportDark.Txt3,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(match.homeLogo, match.homeTeam, size = 26.dp)
            Spacer(Modifier.width(Space.xs))
            Text(
                text = match.homeTeam,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "vs",
                style = MaterialTheme.typography.labelSmall,
                color = SportDark.Txt3,
            )
            Text(
                text = match.awayTeam,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Spacer(Modifier.width(Space.xs))
            TeamLogo(match.awayLogo, match.awayTeam, size = 26.dp)
        }
    }
}

/** 视频区浮层小按钮：半透明圆形，保证在任何画面上都看得清 */
@Composable
private fun VideoControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(19.dp),
            tint = Color.White,
        )
    }
}

/**
 * 播放器句柄：把 ExoPlayer 的播放状态暴露给外层自定义控制栏。
 * 用 Compose 快照状态，播放器回调改值后控制栏自动重组。
 */
private class VideoPlayerHandle {
    var player: Player? by mutableStateOf(null)
    var isPlaying: Boolean by mutableStateOf(false)
    var isBuffering: Boolean by mutableStateOf(false)

    fun attach(p: Player) {
        player = p
        isPlaying = p.isPlaying
        isBuffering = p.playbackState == Player.STATE_BUFFERING
    }

    fun detach() {
        player = null
        isPlaying = false
        isBuffering = false
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }
}

/**
 * 圆形半透明图标按钮。
 * 参考 yingshi 播放器：40dp 圆、白色 10% 底、20dp 白色图标，
 * 压在任意画面上都看得清，也不会像"图标+小字"那样把字挤成省略号。
 */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 功能胶囊：白描边 + 白字。
 * 参考 yingshi 播放器的 Chip —— 文字本身就是标签，
 * 不用图标，读起来更快（"线路 2/3" 比一个列表图标直观）。
 */
@Composable
private fun PlayerChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.btn))
            .border(1.dp, Color.White.copy(alpha = 0.26f), RoundedCornerShape(Radius.btn))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun OtherMatchRow(match: Match, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeagueChip(
            text = match.leagueRawName.ifBlank { match.league.displayName },
            league = match.league,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = match.timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = SportDark.Txt3,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        TeamLogo(match.homeLogo, match.homeTeam, size = 24.dp)
        Spacer(Modifier.width(Space.xs))
        Text(
            text = match.homeTeam,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            text = "vs",
            style = MaterialTheme.typography.labelSmall,
            color = SportDark.Txt3,
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            text = match.awayTeam,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Space.xs))
        TeamLogo(match.awayLogo, match.awayTeam, size = 24.dp)
    }
}

/**
 * Media3 ExoPlayer 直连 HLS。
 *
 * 数据源复用应用共享的 OkHttpClient：连接池与 DNS 缓存已由前面的解析请求预热，
 * 首帧明显更快，也避免 HttpURLConnection 在弱网下频繁超时。
 *
 * 调用方用 `key(streamUrl, referer)` 包住本组件：Referer 是烧在数据源工厂里的，
 * 换线路必须整体重建播放器，否则会出现"换了线路但 CDN 还拿旧 Referer -> 403"。
 */
@Composable
private fun VideoSurface(
    streamUrl: String,
    referer: String?,
    aspectMode: AspectMode,
    handle: VideoPlayerHandle,
    onError: () -> Unit,
) {
    val reportedRef = remember { mutableSetOf<String>() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val onErrorState = rememberUpdatedState(onError)

    // 后台暂停：按 Home 进小窗时 Activity 只是 PAUSED 不收此事件，播放不受影响；
    // 但被返回键划掉小窗、锁屏、切到别的 App（未进小窗）时会 ON_STOP，必须停播，
    // 否则球赛声音在后台一直响。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playerView?.player?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 卡死看门狗：有些线路清单能拉到但直播边缘流卡住，永远停在 BUFFERING，
    // 不会触发 onPlayerError，也就不会故障切换。15 秒内没进入 READY 视为失败。
    LaunchedEffect(streamUrl) {
        delay(15_000)
        val player = playerView?.player
        if (player != null && player.playbackState != Player.STATE_READY &&
            reportedRef.add(streamUrl)
        ) {
            onErrorState.value()
        }
    }

    AndroidView(
        factory = { ctx ->
            val headers = buildMap {
                referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                put("Accept", "*/*")
            }
            val dataSourceFactory = OkHttpDataSource.Factory(HttpClient.shared.client)
                .setDefaultRequestProperties(headers)
                .setUserAgent(HttpClient.USER_AGENT)

            val player = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    // 来电/其他媒体抢焦点时自动暂停，拔耳机时停播
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        /* handleAudioFocus = */ true,
                    )
                    setHandleAudioBecomingNoisy(true)
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    prepare()
                    playWhenReady = true
                }

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 每条地址只上报一次，避免重试风暴
                    if (reportedRef.add(streamUrl)) {
                        onErrorState.value()
                    }
                }

                // 自定义控制栏要读播放/缓冲状态
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handle.isPlaying = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    handle.isBuffering = playbackState == Player.STATE_BUFFERING
                }
            })

            // 关键：关闭 Media3 原生控制条。
            // 直播流不需要快进/后退/拖动进度条，原生控制条在直播上表现为
            // "可拖到任意历史位置"，误触就跳离直播边缘、画面直接卡住。
            // 控制交给底部的自定义控制栏（暂停/重连/画面/换源/换线路/同时在播）。
            PlayerView(ctx).apply {
                useController = false
                resizeMode = aspectMode.resizeMode
                this.player = player
                playerView = this
                handle.attach(player)
            }
        },
        update = { view ->
            view.resizeMode = aspectMode.resizeMode
            val player = view.player
            if (player != null) {
                val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (current != streamUrl) {
                    player.setMediaItem(MediaItem.fromUri(streamUrl))
                    player.prepare()
                    player.playWhenReady = true
                }
            }
        },
        onRelease = { view ->
            handle.detach()
            view.player?.release()
            view.player = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** WebView 承载源站播放页（用于加密的 JRS 线路） */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebSurface(
    url: String,
    onStreamFound: (String, String?) -> Unit,
    suppressHint: Boolean = false,
) {
    val onStreamFoundState = rememberUpdatedState(onStreamFound)
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 比赛已结束时源站页面是一片黑且永远不会出流：
    // 25 秒内没嗅探到任何 m3u8 就给用户明确提示，而不是干等黑屏。
    // 嗅探成功后本组件会被 VideoSurface 替换、协程自动取消，无需手动复位。
    // suppressHint：嗅探过但直连失败退回网页播放时，网页本身就是最终播放器，不再打扰。
    var noSignal by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, suppressHint) {
        if (suppressHint) return@LaunchedEffect
        delay(25_000)
        noSignal = true
    }

    // 与 ExoPlayer 同理：退到后台（非小窗）时暂停页面定时器，省电并防止声音后台外放
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { webView?.pauseTimers(); webView?.onPause() }
                Lifecycle.Event.ON_START -> { webView?.resumeTimers(); webView?.onResume() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        // 播放页是 http，其视频切片常是 https 反向代理后的 http；
                        // 收紧成 COMPATIBILITY 会黑屏，这是实测后的有意取舍
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = HttpClient.USER_AGENT
                    }
                    // 拦截非 http(s) 跳转：源站广告经常用 intent:// / market:// 拉起商店和外链 App
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val scheme = request?.url?.scheme ?: return false
                            return scheme != "http" && scheme != "https"
                        }

                        // 嗅探源站 JS 解密后真正请求的 m3u8：拿到就上报，交给 ExoPlayer 直连
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            val u = request?.url?.toString().orEmpty()
                            if (u.contains(".m3u8") && !u.contains(".js")) {
                                val ref = request?.requestHeaders?.entries
                                    ?.firstOrNull { it.key.equals("Referer", true) }?.value
                                onStreamFoundState.value(u, ref)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        // 源站常弹“备用网址”alert，直接吞掉，避免挡住播放器
                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?,
                        ): Boolean {
                            result?.confirm()
                            return true
                        }

                        // confirm 多为广告/引流弹窗，替用户点"取消"而不是默认同意
                        override fun onJsConfirm(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?,
                        ): Boolean {
                            result?.cancel()
                            return true
                        }
                    }
                    loadUrl(url)
                    tag = url
                    webView = this
                }
            },
            update = { view ->
                // 注意不能比较 view.url：页面会自行跳转（sm.html -> 内层播放页），
                // 那样每次重组都会重新 loadUrl，把正在播的流打断。用 tag 记住请求的 url。
                if (view.tag != url) {
                    view.tag = url
                    view.loadUrl(url)
                }
            },
            onRelease = { view ->
                // WebView 不 destroy 会一直持有 Activity 上下文和渲染进程内存；
                // 反复进出网页线路会累积到 OOM
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.loadUrl("about:blank")
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.clearHistory()
                view.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (noSignal) {
            // 半透明提示压在黑屏上：告诉用户大概率是比赛已结束，可换线路；点击可关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { noSignal = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = SportDark.Warn,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "该线路暂无信号",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = "比赛可能已结束，可切换其他线路",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
