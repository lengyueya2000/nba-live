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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.data.model.MatchStatusClock
import com.nbalive.app.data.model.ReplayItem
import com.nbalive.app.ui.ReplayDatePolicy
import com.nbalive.app.ui.ReplayUiState
import com.nbalive.app.ui.displayTime
import java.time.LocalDate
import com.nbalive.app.ui.components.FullScreenErrorState
import com.nbalive.app.ui.components.LoadingState
import com.nbalive.app.ui.components.TeamLogo
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
    /** 日期筛选：选中后由 VM 按新日期重新拉窗口赛程。默认空实现以兼容既有调用。 */
    onSelectDate: (LocalDate) -> Unit = {},
) {
    val items = state.items
    // 失败时 VM 只写 error、保留旧 items（见 MainViewModel.loadReplays 的 catch）。
    // 若继续把 items 画出来，顶部日期已是新日期、卡片与「N 场」却还是旧日期的数据，
    // 用户会点进不属于该日期的录像 —— UI 侧一律按失败态处理，不渲染旧卡片。
    val staleHidden = state.error != null && items.isNotEmpty()
    val feedback = refreshingFeedbackFor(state.refreshing, state.loading, items.size)
    var showDatePicker by remember { mutableStateOf(false) }

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
                // 日期筛选（2026-11-25）：点开日历选最近 30 天内任一天
                TextButton(onClick = { showDatePicker = true }) {
                    Text(
                        text = "${ReplayDatePolicy.label(state.selectedDate)} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        state.loading -> "加载中…"
                        // 失败时不能报旧列表的场次数（日期已变，这个数字是上一个日期的）
                        state.error != null -> "加载失败"
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
                // 门槛不再看 items.isEmpty()：换日失败后旧 items 还在，不能继续露出旧卡片。
                state.error != null -> FullScreenErrorState(
                    title = "加载失败",
                    message = if (staleHidden) {
                        "仍为旧日期数据，已隐藏旧场次，请重试获取所选日期的录像：${state.error}"
                    } else {
                        state.error!!
                    },
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

    // 日期弹窗：selectableDates 用纯判据 ReplayDatePolicy 限制最近 30 天。
    if (showDatePicker) {
        val today = LocalDate.now(MatchStatusClock.SOURCE_ZONE)
        val selectable = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = ReplayDatePolicy.fromPickerMillis(utcTimeMillis)
                return ReplayDatePolicy.isSelectable(date, today)
            }

            override fun isSelectableYear(year: Int): Boolean =
                year >= today.minusDays(ReplayDatePolicy.MAX_DAYS - 1).year && year <= today.year
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = ReplayDatePolicy.toPickerMillis(state.selectedDate),
            selectableDates = selectable,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = ReplayDatePolicy.fromPickerMillis(millis)
                        if (picked != state.selectedDate) onSelectDate(picked)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
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
            // 左侧：队徽 + 比分 + 队伍（2026-11-25 用户需求：战队头像）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.leagueRawName.ifBlank { item.league.displayName },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(url = item.homeLogo, teamName = item.homeTeam, size = 28.dp)
                    Spacer(Modifier.width(Space.xs))
                    Text(
                        text = "${item.homeTeam}  ${item.homeScore ?: "-"} : ${item.awayScore ?: "-"}  ${item.awayTeam}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Space.xs))
                    TeamLogo(url = item.awayLogo, teamName = item.awayTeam, size = 28.dp)
                }
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
