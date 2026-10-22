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
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.theme.GradRed
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.theme.SportDark

/**
 * "直播"标签页：只列正在直播的场次，点一下直接全屏看。
 *
 * 和"赛事"页的分工：赛事页是当天全量（含未开赛/已结束），
 * 这一页是"现在能看的"，进来就能点。
 */
@Composable
fun LiveNowScreen(
    matches: List<Match>,
    loading: Boolean,
    onMatchClick: (Match) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = matches.filter { it.status == MatchStatus.LIVE }

    Column(modifier = modifier.fillMaxSize()) {
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
                text = "正在直播",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = if (loading) "加载中…" else "${live.size} 场",
                style = MaterialTheme.typography.labelSmall,
                color = SportDark.Txt3,
                maxLines = 1,
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在加载赛事…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            live.isEmpty() -> EmptyLiveState()

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    bottom = Space.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(live, key = { it.id }) { match ->
                    LiveNowCard(match = match, onClick = { onMatchClick(match) })
                }
            }
        }
    }
}

@Composable
private fun EmptyLiveState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = SportDark.Txt3,
            )
            Spacer(Modifier.height(Space.lg))
            Text(
                text = "当前没有正在直播的比赛",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "去「赛事」页看看今天还有哪些场次",
                style = MaterialTheme.typography.bodySmall,
                color = SportDark.Txt3,
            )
        }
    }
}

@Composable
private fun LiveNowCard(match: Match, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(status = match.status)
            Spacer(Modifier.width(Space.sm))
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

        Spacer(Modifier.height(Space.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(match.homeLogo, match.homeTeam, size = 34.dp)
            Spacer(Modifier.width(Space.sm))
            Text(
                text = match.homeTeam,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "vs",
                style = MaterialTheme.typography.labelSmall,
                color = SportDark.Txt3,
                modifier = Modifier.padding(horizontal = Space.sm),
            )
            Text(
                text = match.awayTeam,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.sm))
            TeamLogo(match.awayLogo, match.awayTeam, size = 34.dp)
        }

        Spacer(Modifier.height(Space.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (match.signalCount > 0) "共 ${match.signalCount} 条线路" else "多源线路",
                style = MaterialTheme.typography.labelSmall,
                color = SportDark.Txt3,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.btn))
                    .background(GradRed)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                    )
                    Text(
                        text = "立即观看",
                        color = Color.White,
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
