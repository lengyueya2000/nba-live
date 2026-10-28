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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.nbalive.app.ui.theme.AccentContrast
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.data.model.Match
import com.nbalive.app.ui.CardSemantics
import com.nbalive.app.ui.hhmm
import com.nbalive.app.data.model.isPostponed
import com.nbalive.app.ui.components.FullScreenErrorState
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.RetryErrorBanner
import com.nbalive.app.ui.components.SignalBadge
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.accentBrush
import com.nbalive.app.ui.theme.Space

/**
 * "直播"标签页：只列正在直播的场次，点一下直接全屏看。
 *
 * 和"赛事"页的分工：赛事页是当天全量（含未开赛/已结束），
 * 这一页是"现在能看的"，进来就能点。
 */
@Composable
fun LiveNowScreen(
    /**
     * **已经过滤好的**"正在直播"列表（review/03-ui.md P2-10）。
     *
     * ⚠️ 这里原来收的是完整列表、然后在内部 `remember(matches) { filter LIVE }`，
     * 而 NbaLiveApp 已经为播放页的"同时在播"面板算过一份同样的过滤 ——
     * 同一份过滤逻辑两处独立实现，列表几百场时是两次 O(n) 扫描 + 两份 List 分配，
     * 且两边会各自漂移。现在由调用方传进来，这个页面只负责渲染。
     */
    liveMatches: List<Match>,
    loading: Boolean,
    onMatchClick: (Match) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    refreshing: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val live = liveMatches

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
                text = when {
                    loading -> "加载中…"
                    refreshing -> "刷新中…"
                    else -> "${live.size} 场"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // ⚠️ 加载失败必须单独成态：不能混进"空列表"，
            // 否则用户看到的是"当前没有正在直播的比赛"——把故障说成"今天没球"，
            // 而且这一页原本还没有任何重试入口。
            // 但"已有直播列表 + 刷新失败"不该整屏遮掉：那种情况用顶部横幅（见下）
            error != null && live.isEmpty() -> LiveErrorState(message = error, onRetry = onRetry)

            live.isEmpty() -> EmptyLiveState()

            else -> Column(Modifier.fillMaxSize()) {
                if (error != null) LiveErrorBanner(message = error, onRetry = onRetry)
                LazyColumn(
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
}

/**
 * 刷新失败横幅：列表还在时用它，别把正在看的场次全遮掉。
 * 点一下即重试。
 *
 * ⚠️ 布局已提到 [RetryErrorBanner]（review/03-ui.md P2-20）：
 * 这里原来与 MatchListScreen.ErrorBanner 逐字相同。
 */
@Composable
private fun LiveErrorBanner(message: String, onRetry: () -> Unit) {
    RetryErrorBanner(message = message, onRetry = onRetry)
}

/**
 * 加载失败态：说明白是真出错了，并给一个重试入口。
 *
 * ⚠️ 布局已提到 [FullScreenErrorState]（review/03-ui.md P2-20）：
 * 这里原来是一份私有实现，与列表页/详情页的错误态结构完全一样。
 */
@Composable
private fun LiveErrorState(message: String, onRetry: () -> Unit) {
    FullScreenErrorState(
        title = "加载失败",
        message = message,
        onRetry = onRetry,
        icon = Icons.Filled.CloudOff,
        primaryAction = true,
    )
}

@Composable
private fun EmptyLiveState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // ⚠️ 卡片里还有「立即观看」按钮，两者是**同一个** onClick。
            // 不合并语义时 TalkBack 会读成两个独立可点节点：用户滑到这里要停两次、
            // 听到两遍相同动作（review/03-ui.md P2-25）。
            // mergeDescendants 把它并成一个节点，onClickLabel 给出动作词
            // —— 读屏用户听到的是一句话、一个动作。
            // 判据见 CardSemantics（有单测）。
            .semantics(mergeDescendants = CardSemantics.shouldMergeIntoParent(hasInnerButton = true)) {
                // 语义合并后由父节点统一承载点击动作
            }
            .clickable(
                onClickLabel = CardSemantics.actionLabel(hasInnerButton = true),
                onClick = onClick,
            )
            .padding(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(status = match.status, isPostponed = match.isPostponed)
            Spacer(Modifier.width(Space.sm))
            LeagueChip(
                text = match.leagueLabel(),
                league = match.league,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = match.hhmm(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // signalCount：>0 = 实际线路数；0 = 没有线路；-1 = 未解析（JRS 列表行）。
            // 只有确知数量才画徽章，0 / -1 一律不渲染——不凭空断言"多源"。
            if (match.signalCount > 0) {
                SignalBadge(match.signalCount)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    // 触摸目标补到 48dp（视觉尺寸不变，只是可点区域变大）
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(Radius.btn))
                    .background(accentBrush())
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = AccentContrast.OnAccent,
                    )
                    Text(
                        text = "立即观看",
                        color = AccentContrast.OnAccent,
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 主 CTA（"立即观看"）底色。
 *
 * 原来的 GradRed 渐变（#FF3B4E → #FF7A2F）对白字只有 3.51:1 / 2.60:1，
 * 12sp 白字达不到 WCAG AA 的 4.5:1。
 * 这里改用共享的 [accentBrush]：按主题主色生成，并把每一档都压暗到白字可读。
 */
