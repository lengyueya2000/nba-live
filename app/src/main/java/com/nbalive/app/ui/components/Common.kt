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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.StatusLabel
import com.nbalive.app.ui.theme.GradCba
import com.nbalive.app.ui.theme.GradCbaOnWhite
import com.nbalive.app.ui.theme.GradLiveOnWhite
import com.nbalive.app.ui.theme.GradNba
import com.nbalive.app.ui.theme.GradNbaOnWhite
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.theme.accentBrush
import com.nbalive.app.ui.theme.logoPlate

/**
 * 队徽回退首字的字号（review/03-ui.md P2-27）。
 *
 * 圆形底是 [size]（dp，**不随**字体缩放），字号用裸 `.sp` 时会乘系统 fontScale：
 * 用户把字体调到 1.5×~2.0× 时首字直接溢出圆形被裁掉
 *（`AndroidManifest.xml` 的 configChanges 显式包含 `fontScale`，项目确实要考虑这个场景）。
 *
 * 做法：先按密度算出"容器里放得下"的 px，再用 Density 自己的逆换算
 *（Density 作用域里的 `Float.toSp()`，px → sp）转回 sp。这样无论
 * fontScale 是 1 还是 2，`toPx()` 都严格回到容器内的 px。
 */
internal fun logoFallbackFontSize(size: Dp, density: Density): TextUnit {
    val containerPx = with(density) { (size / 2.4f).toPx() }
    // ⚠️ 两个真踩到的坑（ComponentPerfTest 当场抓到）：
    // 1. 不能自己写 `(containerPx / density.fontScale).sp`：fontScale≠1 时这套算术
    //    反推回 px 并不精确（实测 fontScale=1.3、40dp 时字占 16.91px，容器只有 16.67px），
    //    必须走库自己的逆换算。
    // 2. 也不能抄成 `(containerPx / fontScale).toSp()`：Density 作用域里的 `.toSp()`
    //    本身就是 px → sp 的换算，会把 fontScale 除**两遍**（实测 24dp/1.3 时只剩
    //    7.69px，比不缩放还小）。
    return with(density) { containerPx.toSp() }
}

/**
 * 队徽的回退块（队名首字色块）。
 *
 * ⚠️ 必须是**独立**的私有 Composable，不能写成 [TeamLogo] 里的局部 lambda：
 * 局部 lambda 每次重组都是新实例，`SubcomposeAsyncImage` 的 loading/error
 * 子组合因此要重新执行（列表页里 MatchCard 两个、LiveHeroCard 两个、
 * SearchResultRow 两个队徽都受影响，review/03-ui.md P2-11）。
 *
 * 首字是纯装饰（真正的队名就在旁边），清掉语义避免 TalkBack 重复朗读。
 */
@Composable
private fun LogoFallback(size: Dp, teamName: String) {
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
            // 字号锁在容器内：系统字体放大时首字不能溢出圆形（P2-27）
            fontSize = logoFallbackFontSize(size, LocalDensity.current),
        )
    }
}

/**
 * 队徽：主题圆底 + contain 适配（设计稿 .logo），加载失败回退队名首字色块。
 *
 * ⚠️ 圆底必须是**随主题走的 token**（[logoPlate]），不能写死 `Color.White`：
 * 深色主题下白盘压在 `SportDark.Card=#161C33` 上是 16.83:1（正常），
 * 但浅色主题的卡片就是 `SportLight.Card=#FFFFFF` —— 白盘压白卡 **1.00:1**，
 * 「边缘完全消失」，圆盘和卡片糊在一起（review/03-ui.md P2-30）。
 * 判据见 `ui/theme/LogoPlateContrastTest`（纯 token 对比度 + 本函数的调用点扫描两层）。
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
    if (url.isNullOrBlank()) {
        Box(modifier = modifier) { LogoFallback(size, teamName) }
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
            .background(logoPlate()),
        // 两个槽只做调用：回退块提到函数外，重组不再产生新的子组合内容（P2-11）
        loading = { LogoFallback(size, teamName) },
        error = { LogoFallback(size, teamName) },
    )
}

/**
 * 左标签 + 右值的单行信息（review/03-ui.md P2-19）。
 *
 * ## 为什么抽到 components
 *
 * 原来 MatchDetailScreen 与 ProfileScreen 各有一份 `private fun InfoRow`，
 * 逐字相同（只差一个 `overflow` 参数）。两份副本改样式时只会改到一半 ——
 * 这个仓库已经因此踩过坑（`AspectModeSelector` 的两份副本，
 * 浅色主题下描边漏改，见 `AspectModeSelector` 的注释）。
 *
 * @param value 右侧值；过长时省略（详情页的队名/时间可能很长）
 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
            // 两份旧副本里只有详情页那份有 overflow —— 统一带上，
            // 值过长时省略号比硬裁切好看
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 刷新失败横幅：列表还有旧数据时用它（整屏错误态会把旧数据全遮掉）。
 * 点一下就能重试（review/03-ui.md P2-20）。
 *
 * ## 为什么抽出来
 *
 * `MatchListScreen.ErrorBanner` 与 `LiveNowScreen.LiveErrorBanner`
 * 原来逐字相同 —— 文案前缀、图标、配色、触摸目标全一样。
 * 两份副本意味着改一次要改两处，漏一处就出现"列表页能重试、直播页不能"。
 *
 * @param message 具体错误说明（脱敏后的用户可读文案）
 */
@Composable
fun RetryErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onRetry,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(Radius.chip),
        modifier = modifier
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
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "刷新失败：$message",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * 全屏错误态：图标 + 标题 + 说明 + 重试按钮（review/03-ui.md P2-20）。
 *
 * ## 为什么抽出来
 *
 * 原来三个页面各写一份：`MatchListScreen.ErrorState`、
 * `LiveNowScreen.LiveErrorState`、`MatchDetailScreen.SignalErrorState` ——
 * 结构完全一样（图标/标题/说明/重试），只是图标、配色、文案不同。
 * 三份副本的触摸目标、间距、文案已经在慢慢分叉
 * （有的 `Space.lg` 有的 `Space.sm`，有的带 overflow 有的不带）。
 *
 * ## 参数化而非复制
 *
 * 差异部分全部做成参数：图标、图标色、标题、说明、按钮样式。
 * 调用方只需传自己的文案，布局与无障碍行为由这里统一保证。
 *
 * @param message 具体错误说明（脱敏后的用户可读文案）
 * @param onRetry 重试回调；null 表示不给重试入口（如"该赛事暂无可用线路"）
 * @param primaryAction 是否用主色实心按钮（详情页在滚动容器里用）
 */
@Composable
fun FullScreenErrorState(
    title: String,
    message: String,
    onRetry: (() -> Unit)? = null,
    icon: ImageVector = Icons.Filled.CloudOff,
    iconTint: Color? = null,
    primaryAction: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xxl),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(Space.lg))
                Surface(
                    onClick = onRetry,
                    shape = RoundedCornerShape(Radius.btn),
                    color = if (primaryAction) MaterialTheme.colorScheme.primary else Color.Transparent,
                ) {
                    Box(
                        modifier = Modifier
                            // 触摸目标不小于 48dp（三个旧副本里只有两个做到）
                            .then(
                                if (primaryAction) Modifier
                                else Modifier.background(accentBrush(), RoundedCornerShape(Radius.btn)),
                            )
                            .heightIn(min = 48.dp)
                            .padding(horizontal = Space.xl, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "重试",
                            color = if (primaryAction) MaterialTheme.colorScheme.onPrimary else Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
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
    /**
     * 源站是否标注了延期/推迟/待定（`Match.isPostponed`）。
     *
     * 延期场次的状态也是 UPCOMING，文案必须与「未开赛」区分 ——
     * 否则用户会一直等一场不会开打的比赛。
     */
    isPostponed: Boolean = false,
) {
    // ⚠️ 两个 Brush 的 remember 必须在 `when` **之外**无条件调用（review/03-ui.md P2-12）：
    // remember 出现在条件分支里意味着 status 变化时这个 Composable 的 slot 结构会变。
    // 当前缓存的是纯派生值（Brush），即使错位也只是重算；但把更贵或带状态的东西
    // 放进来就会变成真缺陷。缓存键也去掉 status：Brush 只由颜色决定，
    // 带上 status 反而让状态切换时白丢缓存。
    val secondary = MaterialTheme.colorScheme.secondary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    // remember：Brush 每帧新建会让背景节点持续失效（列表里每行一个 chip）
    val upcomingBg = remember(secondary) {
        Brush.linearGradient(
            listOf(secondary.copy(alpha = 0.16f), secondary.copy(alpha = 0.16f)),
        )
    }
    val finishedBg = remember(surfaceVariant) {
        Brush.linearGradient(listOf(surfaceVariant, surfaceVariant))
    }
    val (bg, fg, border) = when (status) {
        // LIVE 用压暗版：这条渐变上压的是 10.5sp 白字，原始渐变只有 2.3~2.7:1
        MatchStatus.LIVE -> Triple<Brush, Color, Color>(GradLiveOnWhite, Color.White, Color.Transparent)
        MatchStatus.UPCOMING -> Triple(upcomingBg, secondary, secondary.copy(alpha = 0.30f))
        MatchStatus.FINISHED -> Triple(
            finishedBg,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant,
        )
    }
    // ⚠️ 文案走 StatusLabel（review/02-data.md P2-10）：
    // 延期场次的状态是 UPCOMING，原来一律显示「未开赛」——
    // 用户按时间找过去发现没有直播，以为数据错了。
    // 规则抽在 StatusLabel（有单测），这里只负责渲染。
    val label = if (status == MatchStatus.LIVE) {
        liveLabel
    } else {
        StatusLabel.of(status, isPostponed)
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
 * 共享的呼吸相位（review/03-ui.md P2-13）。
 *
 * ## 为什么需要共享
 *
 * 原来 `LiveDot` 内部各自 `rememberInfiniteTransition`。列表页一屏可能有
 * 十几场正在直播的比赛，每颗红点一份**独立无限动画**，十几份动画同时
 * 逐帧运行且永不停止 —— 是列表页持续耗电的来源之一。
 *
 * 现在由 [LiveDotPhase] 在列表/页面层级算**一次** alpha，
 * 通过 CompositionLocal 下发；所有红点复用同一个值。
 * 好处不只是省电：所有红点相位一致，视觉上也更整齐。
 *
 * ⚠️ 默认值是 `1f`（不呼吸）而不是 0.35f：没有 Provider 时
 * （比如详情页单独用 LiveDot）红点应该是稳定可见的，不能一上来就是暗的。
 */
val LocalLiveDotAlpha = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/**
 * 提供共享呼吸相位的容器（在列表/页面层级用一次）。
 *
 * 内部只建**一个** `rememberInfiniteTransition`，子树的红点全部复用它。
 * 透明度走 [graphicsLayer] 的 lambda，只在绘制阶段读状态、不触发重组。
 */
@Composable
fun LiveDotPhase(content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "live-dot-phase")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalLiveDotAlpha provides a) {
        content()
    }
}

/**
 * 呼吸红点：颜色之外还有"直播中"文字，不依赖颜色单独传达信息。
 *
 * ⚠️ 透明度走 [graphicsLayer] 的 lambda 而不是 `Modifier.alpha(a)`：
 * 前者只在**绘制阶段**读状态，不触发重组；后者每帧都会重组这个节点。
 *
 * ⚠️ 动画相位来自 [LocalLiveDotAlpha]（由 [LiveDotPhase] 提供），
 * 不在函数内部自建 —— 否则每颗红点一份无限动画（P2-13）。
 *
 * @param alpha 透明度；默认读共享相位，显式传入可用于静态展示（如截图/测试）
 */
@Composable
fun LiveDot(size: Dp = 6.dp, alpha: Float = LocalLiveDotAlpha.current) {
    Box(
        Modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(Color.White),
    )
}

/**
 * 顶栏 / 二级页的方形图标按钮的**视觉**边长（review/03-ui.md P2-22）。
 *
 * 视觉 40dp，但布局与触摸目标由 `minimumInteractiveComponentSize()` 撑到 48dp ——
 * 视觉尺寸与触摸目标分离是 Material 的推荐做法，也是本仓库原来的本意。
 */
val SquareIconButtonVisualSize: Dp = 40.dp

/**
 * 顶栏 / 二级页的方形图标按钮（review/03-ui.md P2-22）。
 *
 * ## 为什么要抽这一份
 *
 * 同一个 App 里"圆角方块 + surface 底色 + 居中 Icon"原来写了**三份**，
 * 参数已经分叉：
 *  - `NbaLiveApp.TopIconButton`：40dp 视觉 + `minimumInteractiveComponentSize`（图标 19dp）
 *  - `SubPageTopBar` 的返回键 / 「更多」：同样是 40dp + 最小触摸目标，但图标 16dp / 19dp
 *  - `SearchScreen` 的返回键：直接 `size(48.dp)` —— 视觉上比列表页顶栏按钮**大一圈**，
 *    视觉走查一眼就能看出来
 *
 * 分叉的代价不是"多写几行"，而是**同一个语义在不同页面长得不一样**，
 * 且改一次样式要改三处（这个仓库在 `AspectModeSelector` / `InfoRow` 上已经吃过两次亏）。
 *
 * ## 定案：视觉 40dp
 *
 * 统一到 40dp（三处里两处的取值），触摸目标仍是 48dp。
 * 搜索页的返回键会视觉变小一圈 —— 这是**有意**的：它和二级页返回键是同一个东西。
 *
 * @param iconSize 图标（图形）尺寸；顶栏 19dp、二级页返回键 16dp
 * @param tint 图标色；顶栏用 onSurfaceVariant，二级页返回键用 onSurface
 */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 19.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            // 触摸目标不小于 48dp（视觉尺寸保持 40dp 不变）
            .minimumInteractiveComponentSize()
            .size(SquareIconButtonVisualSize)
            .clip(RoundedCornerShape(Radius.btn))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
