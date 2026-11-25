package com.nbalive.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.ui.ReplayUiState
import com.nbalive.app.ui.displayTime
import com.nbalive.app.ui.components.FullScreenErrorState
import com.nbalive.app.ui.components.LoadingState
import com.nbalive.app.ui.components.RetryErrorBanner
import com.nbalive.app.ui.icons.AppIcons
import com.nbalive.app.ui.theme.AccentContrast
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.theme.accentBrush

/**
 * 「录像」标签页（2026-11-25 新增）：已完赛场次 + 真实比分 + 多条清晰度线路。
 *
 * 数据只来自 xxzb5（西西直播）的 `/api/v1/replays`，不是直播信号。
 * 每场带真实比分；无线路的场次仍显示卡片（点进去播放器给"暂无线路"提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(
    state: ReplayUiState,
    onMatchClick: (ReplayItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = state.items
    val feedback = refreshingFeedbackFor(state.refreshing, state.loading, items.size)

    PullToRefreshBox(
        isRefreshing = shouldShowRefreshing(state.refreshing, state.loading, items.size),
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg)
                    .padding(top = Space.sm, bottom = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "已完赛录像",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = when {
                        state.loading -> "加载中…"
                        state.refreshing -> "刷新中…"
                        else -> "${items.size} 场"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            when {
                state.loading -> LoadingState(text = "正在加载录像…")
                state.error != null && items.isEmpty() -> FullScreenErrorState(
                    title = "加载失败",
                    message = state.error!!,
                    onRetry = onRefresh,
                    icon = AppIcons.filled_CloudOff,
                    primaryAction = true,
                )
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (feedback == RefreshingFeedback.EmptyPlaceholder) "正在刷新…"
                        else "暂无已完赛的篮球录像",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null) RetryErrorBanner(message = state.error!!, onRetry = onRefresh)
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = Space.lg,
                            end = Space.lg,
                            bottom = Space.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { item ->
                            ReplayCard(item = item, onClick = { onMatchClick(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayCard(item: ReplayItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .clickable(role = Role.Button, onClickLabel = "观看${item.homeTeam}对${item.awayTeam}录像", onClick = onClick),
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：比分 + 队伍
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.leagueRawName.ifBlank { item.league.displayName },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "${item.homeTeam}  ${item.homeScore ?: "-"} : ${item.awayScore ?: "-"}  ${item.awayTeam}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "${item.displayTime()}  ·  ${item.lines.size} 条清晰度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.sm))
            // 圆形「播放」徽标（语义是主行动圆钮，不是顶栏那种方形图标按钮），
            // 因此用 CircleShape + 专属尺寸，不走 SquareIconButton 的方角 + Radius.btn 形状。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(accentBrush()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "观看录像",
                    tint = AccentContrast.OnAccent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
