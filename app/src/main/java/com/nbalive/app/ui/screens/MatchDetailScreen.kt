package com.nbalive.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.displayTime
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.mmdd
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space

/**
 * 赛事详情：左右对阵大字号 + 信号列表 + 源站信息 + 底部 CTA。
 *
 * 说明：源站不提供分辨率与实时可用性数据，因此信号行只展示**真实已知信息**
 * （线路名、源站、播放方式），不编造 1080P / 可用性状态。
 */
@Composable
fun MatchDetailScreen(
    match: Match,
    signals: List<StreamSource>,
    loadingSignals: Boolean,
    onSignalClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
    ) {
        // 头部信息行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeagueChip(
                text = match.leagueLabel(),
                league = match.league,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = match.displayTime(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.sm))
            StatusChip(status = match.status)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (loadingSignals) "加载中…" else "${signals.size} 条信号",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(Space.xxl))

        // 左右对阵
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VersusTeam(
                name = match.homeTeam,
                logo = match.homeLogo,
                modifier = Modifier.weight(1f),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = Space.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "VS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = match.mmdd(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VersusTeam(
                name = match.awayTeam,
                logo = match.awayLogo,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Space.xxl))

        // 选择信号
        SectionHeader(title = "选择信号", hint = "点击进入播放器并选中该线路")

        if (loadingSignals) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Space.xxl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "正在获取信号列表…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (signals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Space.xxl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "该赛事暂无可用线路",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                signals.forEachIndexed { index, source ->
                    SignalRow(
                        index = index,
                        source = source,
                        onClick = { onSignalClick(index) },
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.xxl))

        // 源站信息（次要）
        SectionHeader(title = "源站信息", hint = "第三方聚合源")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .clip(RoundedCornerShape(Radius.sheet))
                .background(MaterialTheme.colorScheme.surface)
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            InfoRow(label = "数据源", value = "JRS / JRKAN 双源")
            InfoRow(
                label = "信号数",
                value = "${signals.size} 条",
            )
        }
    }
}

@Composable
private fun VersusTeam(name: String, logo: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TeamLogo(url = logo, teamName = name, size = 76.dp)
        Spacer(Modifier.height(Space.md))
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .padding(bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.md))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** 单条信号行 */
@Composable
private fun SignalRow(
    index: Int,
    source: StreamSource,
    onClick: () -> Unit,
) {
    // 详情页没有"当前选中线路"这个状态（选中发生在播放器里），
    // 所以这里不做"高亮第一行"的假选中，全部用中性样式。
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val bg = MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.card))
            .clickable(
                onClickLabel = "播放该线路",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(Radius.chipLg))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.width(Space.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "源站 ${source.provider.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(Space.sm))

        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier
                .padding(start = Space.xs)
                .size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
