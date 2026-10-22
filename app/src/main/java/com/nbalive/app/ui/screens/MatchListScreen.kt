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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.LeagueStrip
import com.nbalive.app.ui.components.SignalBadge
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.hhmm
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.mmdd
import com.nbalive.app.ui.theme.GradLive
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.accentBrush
import com.nbalive.app.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 日期分组 */
private data class DayGroup(val label: String, val matches: List<Match>)

private fun groupByDay(matches: List<Match>, today: LocalDate): List<DayGroup> {
    val zone = ZoneId.systemDefault()
    return matches
        .groupBy { m ->
            m.startAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
        }
        .toSortedMap()
        .map { (date, list) ->
            val label = when (date) {
                today -> "今天 · ${date.monthValue}月${date.dayOfMonth}日"
                today.plusDays(1) -> "明天 · ${date.monthValue}月${date.dayOfMonth}日"
                today.minusDays(1) -> "昨天 · ${date.monthValue}月${date.dayOfMonth}日"
                else -> "${date.monthValue}月${date.dayOfMonth}日"
            }
            DayGroup(label, list)
        }
}

@Composable
fun MatchListScreen(
    matches: List<Match>,
    loading: Boolean,
    error: String?,
    selectedLeague: League,
    counts: Map<League, Int>,
    onLeagueSelected: (League) -> Unit,
    onMatchClick: (Match) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LeagueSelector(
            selected = selectedLeague,
            counts = counts,
            onSelect = onLeagueSelected,
        )
        when {
            loading -> LoadingState()
            error != null -> ErrorState(error = error, onRetry = onRetry)
            matches.isEmpty() -> EmptyState()
            else -> MatchGrid(matches = matches, onMatchClick = onMatchClick)
        }
    }
}

@Composable
private fun LeagueSelector(
    selected: League,
    counts: Map<League, Int>,
    onSelect: (League) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .padding(top = Space.sm, bottom = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        League.filters.forEach { league ->
            val active = league == selected
            val n = counts[league] ?: 0
            Surface(
                modifier = Modifier
                    .weight(1f)
                    // 触摸目标不小于 48dp（不改变视觉尺寸）
                    .minimumInteractiveComponentSize()
                    .height(44.dp)
                    .clip(RoundedCornerShape(Radius.btn))
                    .clickable { onSelect(league) },
                shape = RoundedCornerShape(Radius.btn),
                color = if (active) Color.Transparent else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (active) Modifier.background(accentBrush()) else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = league.displayName,
                            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        if (n > 0) {
                            Text(
                                text = "$n",
                                color = if (active) Color.White.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchGrid(matches: List<Match>, onMatchClick: (Match) -> Unit) {
    val liveMatch = matches.firstOrNull { it.status == MatchStatus.LIVE }
    // 直播中的比赛已用全宽 hero 卡呈现，网格里不再重复
    val gridMatches = if (liveMatch != null) matches.filter { it.id != liveMatch.id } else matches
    // 分组要 remember：里面读系统时钟，每次重组都重算既浪费，
    // 跨零点时还会让 "day-…" 的 key 突变导致网格整体重建
    val today = remember { LocalDate.now(ZoneId.systemDefault()) }
    val groups = remember(gridMatches, today) { groupByDay(gridMatches, today) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, bottom = Space.xxl),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 直播中的赛事：横贯全宽的 hero 卡
        if (liveMatch != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hero-${liveMatch.id}") {
                LiveHeroCard(match = liveMatch, onClick = { onMatchClick(liveMatch) })
            }
        }

        groups.forEach { group ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "day-${group.label}") {
                DayHeader(
                    label = group.label,
                    count = group.matches.size,
                    liveCount = group.matches.count { it.status == MatchStatus.LIVE },
                )
            }
            items(group.matches, key = { it.id }) { match ->
                MatchCard(match = match, onClick = { onMatchClick(match) })
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int, liveCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = "$count 场",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (liveCount > 0) {
            Text(
                text = "$liveCount 场直播中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

/** 直播中的大卡（设计稿 hero：红橙渐变 + 大字对阵 + 立即观看） */
@Composable
private fun LiveHeroCard(match: Match, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(GradLive)
            .clickable(onClick = onClick)
            .padding(Space.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = MatchStatus.LIVE)
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = match.leagueLabel(),
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = match.hhmm(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(Space.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroTeam(name = match.homeTeam, logo = match.homeLogo, modifier = Modifier.weight(1f))
                Text(
                    text = "VS",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Space.sm),
                )
                HeroTeam(name = match.awayTeam, logo = match.awayLogo, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(Space.lg))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (match.signalCount > 0) {
                    HeroPill(
                        icon = Icons.Filled.SignalCellularAlt,
                        text = "${match.signalCount} 条线路",
                    )
                    Spacer(Modifier.width(Space.sm))
                }
                // JRS 列表页的 sources 恒为空（JrsParser.parseList 里 sources = emptyList()），
                // 不能凭空写"多源"；没有真实源站就不显示这个药丸。
                match.sources.firstOrNull()?.provider?.displayName?.let { provider ->
                    HeroPill(
                        icon = Icons.Filled.SportsBasketball,
                        text = provider,
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(Radius.btn),
                    color = Color.White.copy(alpha = 0.22f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White,
                        )
                        Text(
                            text = "立即观看",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTeam(name: String, logo: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TeamLogo(url = logo, teamName = name, size = 52.dp)
        Spacer(Modifier.height(Space.sm))
        Text(
            text = name,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(Color(0x33000000))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** 双列网格里的赛事卡（设计稿 .mcard） */
@Composable
private fun MatchCard(match: Match, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        LeagueStrip(league = match.league, modifier = Modifier.fillMaxWidth())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.md, end = Space.md, top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeagueChip(
                text = match.leagueLabel(),
                league = match.league,
            )
            Spacer(Modifier.weight(1f))
            if (match.signalCount > 0) SignalBadge(match.signalCount)
        }

        Row(
            modifier = Modifier.padding(start = Space.md, end = Space.md, top = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = match.hhmm(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = match.mmdd(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = Space.sm),
            verticalAlignment = Alignment.Top,
        ) {
            GridTeam(name = match.homeTeam, logo = match.homeLogo, modifier = Modifier.weight(1f))
            Text(
                text = "VS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            GridTeam(name = match.awayTeam, logo = match.awayLogo, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.md, end = Space.md, top = Space.sm),
        ) {
            StatusChip(status = match.status)
        }
    }
}

@Composable
private fun GridTeam(name: String, logo: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TeamLogo(url = logo, teamName = name, size = 36.dp)
        Spacer(Modifier.height(Space.xxs))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- 三态 ----------

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Space.lg))
            Text(
                text = "正在加载赛事…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.lg))
            Text(
                text = "今日暂无赛事",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "换个联赛看看，或稍后刷新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Space.xxl),
        ) {
            Icon(
                Icons.Filled.SignalCellularAlt,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(Space.lg))
            Text(
                text = "加载失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xxl))
            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(Radius.btn),
                color = Color.Transparent,
            ) {
                Box(
                    modifier = Modifier
                        .background(accentBrush(), RoundedCornerShape(Radius.btn))
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "重试",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
