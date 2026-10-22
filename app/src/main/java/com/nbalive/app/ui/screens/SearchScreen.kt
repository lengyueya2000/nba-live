package com.nbalive.app.ui.screens

import android.util.Log
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbalive.app.data.model.Match
import com.nbalive.app.ui.components.LeagueChip
import com.nbalive.app.ui.components.StatusChip
import com.nbalive.app.ui.components.TeamLogo
import com.nbalive.app.ui.leagueLabel
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import kotlinx.coroutines.CancellationException

/**
 * 搜索页：在已加载的赛事里按球队 / 联赛名过滤。
 *
 * 纯本地过滤（数据已经在内存里），打字即出结果，不发网络请求。
 */
@Composable
fun SearchScreen(
    matches: List<Match>,
    onClose: () -> Unit,
    onMatchClick: (Match) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // 提到 when 外面：否则分支切换时列表状态被丢掉，换查询词会沿用旧滚动位置
    val listState = rememberLazyListState()

    // 进来就聚焦输入框，省一次点击
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: CancellationException) {
            // 协程被取消不是错误，照原样抛回去
            throw e
        } catch (e: IllegalStateException) {
            // 输入框还没挂上时 requestFocus 会抛异常：记一笔，别再静默吞掉
            Log.w("SearchScreen", "自动聚焦输入框失败", e)
        }
    }

    val results = remember(query, matches) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else matches.filter { m ->
            m.homeTeam.contains(q, ignoreCase = true) ||
                m.awayTeam.contains(q, ignoreCase = true) ||
                m.leagueRawName.contains(q, ignoreCase = true) ||
                m.league.displayName.contains(q, ignoreCase = true)
        }
    }

    // 换查询词就回到顶部，别停在上一轮的滚动位置
    LaunchedEffect(query) {
        if (results.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Space.lg)
                .padding(top = Space.md, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    // 触摸目标补到 48dp
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.btn))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = "返回",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(Space.sm))
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    // Material3 文本框默认 56dp：48dp 塞不下 48dp 的"清空"触摸目标
                    .heightIn(min = 56.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "搜索球队 / 联赛",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                // 图标只有 18dp，触摸目标补到 48dp
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(Radius.chip))
                                .clickable { query = "" }
                                .semantics { contentDescription = "清空" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // 搜索键：收起键盘，别一直挡着结果列表
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(Radius.btn),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }

        when {
            query.isBlank() -> HintState("输入球队名开始搜索，例如「湖人」")
            results.isEmpty() -> HintState("没有找到匹配的赛事")
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.sm,
                    bottom = Space.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
                // 这一页是 Scaffold 的兄弟节点，拿不到 Scaffold 的 innerPadding；
                // 开了 edge-to-edge 后不补 navigationBarsPadding，最后一行会被系统栏压住。
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                items(results, key = { it.id }) { match ->
                    SearchResultRow(match = match, onClick = { onMatchClick(match) })
                }
            }
        }
    }
}

@Composable
private fun HintState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultRow(match: Match, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeagueChip(
                    text = match.leagueLabel(),
                    league = match.league,
                )
                Spacer(Modifier.width(Space.sm))
                StatusChip(status = match.status)
                Spacer(Modifier.weight(1f))
                Text(
                    text = match.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(match.homeLogo, match.homeTeam, size = 24.dp)
                Spacer(Modifier.width(Space.xs))
                Text(
                    text = match.homeTeam,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = " vs ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = match.awayTeam,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(Space.xs))
                TeamLogo(match.awayLogo, match.awayTeam, size = 24.dp)
            }
        }
    }
}
