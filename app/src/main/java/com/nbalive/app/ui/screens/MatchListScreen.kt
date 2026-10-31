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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.data.model.isPostponed
import com.nbalive.app.ui.components.FullScreenErrorState
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.RetryErrorBanner
import com.nbalive.app.ui.components.LeagueStrip
import com.nbalive.app.ui.components.SignalBadge
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.CardSemantics
import com.nbalive.app.ui.ListNotice
import com.nbalive.app.ui.ListNoticePolicy
import com.nbalive.app.ui.DAY_LABEL_TICK_MS
import com.nbalive.app.ui.TeamNameBridge
import com.nbalive.app.ui.dayLabel
import com.nbalive.app.ui.groupByDay
import com.nbalive.app.ui.hhmm
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.mmdd
import com.nbalive.app.ui.systemZone
import com.nbalive.app.ui.theme.AccentContrast
import com.nbalive.app.ui.theme.GradLiveOnWhite
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.accentBrush
import com.nbalive.app.ui.theme.Space
import java.time.LocalDate
import kotlinx.coroutines.delay

// 日期分组逻辑（DayGroup / dayLabel / groupByDay）已移到 ui/MatchFormat.kt 的纯函数区
// （review/03-ui.md P2-33）：那是"展示格式化"的收敛点，且移出去才能被单测覆盖。
// 这里原来有一份与它等价的私有实现。

@OptIn(ExperimentalMaterial3Api::class)
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
    refreshing: Boolean = false,
    /**
     * 部分数据源不可用时的说明（review/02-data.md P1-2）。
     *
     * ⚠️ 不能只写进状态了事：单源挂掉时用户原来只看到"场次变少了"，
     * 无法区分"源挂了"和"今天真没比赛"，线上问题也就没法被准确反馈。
     */
    partialError: String? = null,
    /**
     * 比分快照：`match.id` → 那一场的比分（2026-10-31 用户需求）。
     *
     * ⚠️ CBA 没有数据（ESPN 的 CBA 端点返回 400，见 `ScoreboardRepository`），
     * 查不到就**不画**比分区块 —— 不显示 `0 - 0`、也不显示空白占位。
     */
    scores: Map<String, EspnScoreboard.GameScore> = emptyMap(),
) {
    // 下拉刷新（2026-10-31 用户需求）：
    // 之前只有右上角那个小按钮，看球时单手够不到、也不知道能拉。
    // ⚠️ isRefreshing 只在**已有数据**时为 true：首载（整屏 loading）时下拉指示器
    // 会和整屏转圈叠在一起，观感像卡住。
    PullToRefreshBox(
        isRefreshing = refreshing && matches.isNotEmpty(),
        onRefresh = onRetry,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LeagueSelector(
                selected = selectedLeague,
                counts = counts,
                onSelect = onLeagueSelected,
            )
            when {
                // 首载：还没有任何数据，整屏 loading
                loading -> LoadingState()
                // 刷新失败：给出错误 + 重试（但已有列表还在下面，不清空）
                error != null && matches.isEmpty() -> ErrorState(error = error, onRetry = onRetry)
                matches.isEmpty() -> EmptyState()
                else -> {
                    // 刷新中：保留旧列表，只在顶部挂一条细进度条。
                    // 原来刷新时整屏换成转圈，已有卡片全消失、滚动位置也丢。
                    if (refreshing) RefreshingBar()
                    // 有旧数据：错误提示要看得见（还能点重试）；
                    // 没有整次错误但某个源缺了数据时，也要让用户知道少了什么
                    // —— 规则见 ListNoticePolicy（纯判据，有单测）。
                    when (val notice = ListNoticePolicy.of(error, partialError)) {
                        is ListNotice.Failed -> ErrorBanner(message = notice.message, onRetry = onRetry)
                        is ListNotice.Partial -> PartialBanner(message = notice.message)
                        ListNotice.None -> Unit
                    }
                    MatchGrid(matches = matches, onMatchClick = onMatchClick, scores = scores)
                }
            }
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
                                color = if (active) AccentContrast.OnAccent
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
private fun MatchGrid(
    matches: List<Match>,
    onMatchClick: (Match) -> Unit,
    scores: Map<String, EspnScoreboard.GameScore> = emptyMap(),
) {
    val liveMatch = matches.firstOrNull { it.status == MatchStatus.LIVE }
    // 直播中的比赛已用全宽 hero 卡呈现，网格里不再重复
    val gridMatches = if (liveMatch != null) matches.filter { it.id != liveMatch.id } else matches
    // 分组要 remember：里面读系统时钟，每次重组都重算既浪费。
    // ⚠️ key 用**绝对日期**而不是标签字符串：
    // 标签是"今天/明天/1月1日"这种展示文案，跨年时两个日期可能撞成同一个"1月1日"，
    // 用它当 LazyVerticalGrid 的 key 就会重复 key 崩溃（这个仓库崩过两次）。
    // 用绝对日期做 key，跨零点 key 不变，网格也不会整体重建。
    //
    // ⚠️ today 必须是**会变的**（review/03-ui.md P2-8）：
    // 原来这里是 `LocalDate.now(...)` 的组合期快照，注释却承诺"跨零点后不用切走
    // 页面标签也会自动变新"—— 那个承诺做不到：跨零点本身不触发重组，
    // 列表静止时标签会一直停在旧值（今天被继续标成"明天"）。
    // 现在用 60 秒一跳的 ticker 驱动，跨零点后最多 1 分钟自动修正，
    // 不依赖滚动/刷新这类外部事件；日期没变时值相等，不会引起多余重组。
    // 时区复用 MatchFormat 里缓存的 systemZone，不再每次重组查系统设置。
    val today by produceState(initialValue = LocalDate.now(systemZone)) {
        while (true) {
            delay(DAY_LABEL_TICK_MS)
            value = LocalDate.now(systemZone)
        }
    }
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
                LiveHeroCard(
                    match = liveMatch,
                    onClick = { onMatchClick(liveMatch) },
                    score = scores[liveMatch.id],
                )
            }
        }

        groups.forEach { group ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "day-${group.date}") {
                DayHeader(
                    label = dayLabel(group.date, today),
                    count = group.matches.size,
                    liveCount = group.matches.count { it.status == MatchStatus.LIVE },
                )
            }
            items(group.matches, key = { it.id }) { match ->
                MatchCard(
                    match = match,
                    onClick = { onMatchClick(match) },
                    score = scores[match.id],
                )
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
private fun LiveHeroCard(
    match: Match,
    onClick: () -> Unit,
    /**
     * 这一场的比分（2026-10-31 用户需求）。
     *
     * ⚠️ null = 没有数据（CBA 永远没有：ESPN 的 CBA 端点返回 400）。那时整块不渲染。
     */
    score: EspnScoreboard.GameScore? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            // 整张卡上全是白字（队名、时间、"立即观看"），必须用压暗过的白字安全版：
            // 原始红橙渐变只有 2.3~2.7:1，远达不到 AA 4.5:1
            .background(GradLiveOnWhite)
            // ⚠️ 整卡可点却没有动作词：TalkBack 会先读一长串队名/时间/线路数，
            // 最后只说"双击激活"，用户不知道点了会发生什么（review/03-ui.md P2-26）。
            // 详情页的 SignalRow 早就写了 onClickLabel，这里漏了 —— 统一用
            // CardSemantics 的动作词（有单测）。卡内没有内嵌可点按钮
            // （"立即观看"是纯装饰 Surface），所以不需要合并语义。
            .clickable(
                onClickLabel = CardSemantics.ACTION_LABEL,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(Space.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = MatchStatus.LIVE)
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = match.leagueLabel(),
                    color = AccentContrast.OnAccent,
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
                    color = AccentContrast.OnAccent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Space.sm),
                )
                HeroTeam(name = match.awayTeam, logo = match.awayLogo, modifier = Modifier.weight(1f))
            }

            // 比分（有数据才画）。hero 卡整卡是白字：这里用 OnAccent 保证对比度
            // （AccentContrast 那套色就是为"渐变底上的文字"准备的，有专门单测）。
            score?.let { g ->
                val pair = TeamNameBridge.displayableScore(g)
                val label = TeamNameBridge.statusLabel(g)
                if (pair != null) {
                    Spacer(Modifier.height(Space.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${pair.first} : ${pair.second}",
                            color = AccentContrast.OnAccent,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        if (label != null) {
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                text = label,
                                color = AccentContrast.OnAccent,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
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
                    // ⚠️ 原来这里是 22% **白**：那是把渐变底**提亮**，
                    // 提亮后的底色上压纯白字只有 3.09~3.47:1，达不到 AA。
                    // 改用 20% **黑**（与 HeroPill 同一配方），白字 6.4:1。
                    color = AccentContrast.HeroPillBackground,
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
            // ⚠️ 与"立即观看"用同一个配方（AccentContrast.HeroPillBackground）：
            // 原来这里是字面量 Color(0x33000000)，与上面那个 Surface 是同一配方
            // 却各写一份 —— 改配色时必然只改到一半（review/03-ui.md P2-30）。
            .background(AccentContrast.HeroPillBackground)
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
private fun MatchCard(
    match: Match,
    onClick: () -> Unit,
    /**
     * 这一场的比分（2026-10-31 用户需求）。
     *
     * ⚠️ null = 没有数据（CBA 永远没有：ESPN 的 CBA 端点返回 400；NBA 未开赛/认不出队名也没有）。
     * 那时**整块不渲染** —— 不画 `0 - 0`、也不留空白占位。
     */
    score: EspnScoreboard.GameScore? = null,
) {
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

        // 比分（有数据才画）：主客顺序与上方队名**一致**（左主右客），
        // 与 ESPN 的 home/away 对应关系由 TeamNameBridge.findGame 保证。
        score?.let { g ->
            val pair = TeamNameBridge.displayableScore(g)
            val label = TeamNameBridge.statusLabel(g)
            if (pair != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Space.md, end = Space.md, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${pair.first} : ${pair.second}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (label != null) {
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.md, end = Space.md, top = Space.sm),
        ) {
            StatusChip(status = match.status, isPostponed = match.isPostponed)
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

/** 刷新中的细进度条：保留旧列表，只在最上方提示"正在刷新" */
@Composable
private fun RefreshingBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .padding(bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "正在刷新…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 刷新失败横幅：列表还有旧数据时用它（整屏错误态会把旧数据全遮掉）。
 * 点一下就能重试。
 *
 * ⚠️ 布局已提到 [RetryErrorBanner]（review/03-ui.md P2-20）：
 * 这里原来与 LiveNowScreen.LiveErrorBanner 逐字相同。
 */
@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    RetryErrorBanner(message = message, onRetry = onRetry)
}

/**
 * 部分源不可用横幅（review/02-data.md P1-2）。
 *
 * 与 [ErrorBanner] 的三点不同，都是有意的：
 *  1. **不可点**：这不是"刷新失败"，重试多半没用（源站限流/宕机有自己的恢复节奏），
 *     给一个点了没用的按钮比不给更糟；
 *  2. 文案不带「刷新失败：」前缀 —— 整次失败和缺一个源必须能区分；
 *  3. 用 `surfaceVariant` 而不是 error 色：列表本身是可用的，
 *     不该把界面染成"出错了"的样子。
 */
@Composable
private fun PartialBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.chip),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .padding(bottom = Space.sm),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

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

/**
 * 加载失败态（review/03-ui.md P2-20）。
 *
 * ⚠️ 布局已提到 [FullScreenErrorState]：这里原来是一份私有实现，
 * 与 LiveNowScreen.LiveErrorState / MatchDetailScreen.SignalErrorState
 * 结构完全一样、只是图标与配色不同，三份副本的间距与触摸目标已经在分叉。
 */
@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    FullScreenErrorState(
        title = "加载失败",
        message = error,
        onRetry = onRetry,
        icon = Icons.Filled.SignalCellularAlt,
        iconTint = MaterialTheme.colorScheme.error,
    )
}
