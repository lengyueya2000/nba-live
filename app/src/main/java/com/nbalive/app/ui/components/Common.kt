package com.nbalive.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.theme.GradCba
import com.nbalive.app.ui.theme.GradCbaOnWhite
import com.nbalive.app.ui.theme.GradLiveOnWhite
import com.nbalive.app.ui.theme.GradNba
import com.nbalive.app.ui.theme.GradNbaOnWhite
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space

/**
 * 队徽：白底圆形 + contain 适配（设计稿 .logo），加载失败回退队名首字色块。
 *
 * ⚠️ 默认可被无障碍忽略（[contentDescription] 为 null）：
 * 队徽旁边永远紧跟着球队名文本，两边都带语义时 TalkBack 会把队名连读两遍
 *（"湖人，湖人"）。只有当图标是**唯一**信息载体时才传 contentDescription。
 */
@Composable
fun TeamLogo(
    url: String?,
    teamName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // 回退块里的首字是纯装饰（真正的队名就在旁边），清掉语义避免重复朗读
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(size)
                .clearAndSetSemantics { }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = teamName.take(1),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Black,
                fontSize = (size.value / 2.4f).sp,
            )
        }
    }

    if (url.isNullOrBlank()) {
        Box(modifier = modifier) { fallback() }
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White),
        loading = { fallback() },
        error = { fallback() },
    )
}

/** 联赛渐变色条（设计稿 .strip，高 5dp，纯装饰不压字） */
@Composable
fun LeagueStrip(league: League, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(5.dp)
            .background(
                when (league) {
                    League.NBA -> GradNba
                    League.CBA -> GradCba
                    else -> GradCba
                },
            ),
    )
}

/** 联赛标签（设计稿 .lg-chip，高 20dp / 圆角 7dp / 9.5sp 900） */
@Composable
fun LeagueChip(
    text: String,
    league: League,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(20.dp)
            .clip(RoundedCornerShape(Radius.chip))
            .background(
                // 9.5sp 白字：必须用压暗过的白字安全版，原始渐变只有 3.2~3.6:1
                when (league) {
                    League.NBA -> GradNbaOnWhite
                    League.CBA -> GradCbaOnWhite
                    else -> GradCbaOnWhite
                },
            )
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 9.5.sp,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

/** 信号数徽章（设计稿 .sig-badge） */
@Composable
fun SignalBadge(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(20.dp)
            .clip(RoundedCornerShape(Radius.chip))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.SignalCellularAlt,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$count",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.5.sp,
            maxLines = 1,
        )
    }
}

/**
 * 赛事状态标签（设计稿 .st）。
 * 直播中 = 红橙渐变；未开赛 = 蓝调；已结束 = 中性。
 * 全部取主题派生色：浅色主题下半透明深底会近乎隐形，写死的 SportDark 色板
 * 又会在白页上压出深色砖，所以这里跟随 colorScheme。
 */
@Composable
fun StatusChip(
    status: MatchStatus,
    modifier: Modifier = Modifier,
    liveLabel: String = "直播中",
) {
    val (bg, fg, border) = when (status) {
        // LIVE 用压暗版：这条渐变上压的是 10.5sp 白字，原始渐变只有 2.3~2.7:1
        MatchStatus.LIVE -> Triple<Brush, Color, Color>(GradLiveOnWhite, Color.White, Color.Transparent)
        MatchStatus.UPCOMING -> {
            // remember：Brush 每帧新建会让背景节点持续失效（列表里每行一个 chip）
            val secondary = MaterialTheme.colorScheme.secondary
            Triple(
                remember(status, secondary) {
                    Brush.linearGradient(
                        listOf(secondary.copy(alpha = 0.16f), secondary.copy(alpha = 0.16f)),
                    )
                },
                secondary,
                secondary.copy(alpha = 0.30f),
            )
        }
        MatchStatus.FINISHED -> {
            val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
            Triple(
                remember(status, surfaceVariant) {
                    Brush.linearGradient(listOf(surfaceVariant, surfaceVariant))
                },
                MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    val label = when (status) {
        MatchStatus.LIVE -> liveLabel
        MatchStatus.UPCOMING -> "未开赛"
        MatchStatus.FINISHED -> "已结束"
    }
    Row(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(Radius.chipLg))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.chipLg))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (status == MatchStatus.LIVE) {
            LiveDot()
        }
        Text(
            text = label,
            color = fg,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.5.sp,
            maxLines = 1,
        )
    }
}

/**
 * 画面模式三档选择器（自适应 / 铺满裁切 / 拉伸铺满）。
 *
 * 「我的」页与播放器用的是同一控件，之前两份逐字复制，
 * 改配色时只改到一半（浅色主题下未选中项描边不可见）。
 */
@Composable
fun AspectModeSelector(
    aspectMode: AspectMode,
    onAspectChange: (AspectMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        AspectMode.entries.forEach { mode ->
            val active = mode == aspectMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.btn))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.background,
                    )
                    .border(
                        1.dp,
                        // 未选中项的描边必须跟随主题：写死半透明白色在浅色页面上等于没有描边
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
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
}

/**
 * 呼吸红点：颜色之外还有"直播中"文字，不依赖颜色单独传达信息。
 *
 * ⚠️ 透明度走 [graphicsLayer] 的 lambda 而不是 `Modifier.alpha(a)`：
 * 前者只在**绘制阶段**读状态，不触发重组；后者每帧都会重组这个节点。
 * 列表里可能有十几颗红点同时呼吸，差别是可观的。
 */
@Composable
fun LiveDot(size: Dp = 6.dp) {
    val t = rememberInfiniteTransition(label = "live-dot")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = a }
            .clip(CircleShape)
            .background(Color.White),
    )
}
