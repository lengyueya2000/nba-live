package com.nbalive.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nbalive.app.ui.findActivity

/**
 * 方案 B「运动卡片 / Sport Card」设计 token。
 *
 * 来源：design/sportcard/index.html 的 CSS 变量，一一对应，
 * 保证 Compose 实现与设计稿像素级一致。
 *
 * ⚠️ Composable 里**不要**直接引用 [SportDark] / [SportLight]：
 * 那是浅色主题下"深色砖/隐形文字"的来源（Common.kt 的 StatusChip 踩过这个坑）。
 * 统一按语义槽位取色：
 *
 * | 语义 | 槽位 |
 * |---|---|
 * | 三级文字（时间、次要说明） | `colorScheme.onSurfaceVariant` |
 * | 分隔线 / 卡片描边 | `colorScheme.outlineVariant`（强调用 `outline`） |
 * | 警告（无信号、故障切换提示） | `colorScheme.tertiary`（文字用 `onTertiary`） |
 * | 卡面 / 次级容器 | `colorScheme.surface` / `surfaceVariant` |
 */

// ---------- 暗色（默认） ----------
// internal：跨模块边界（review/03-ui.md P2-29）。⚠️ 本仓库是单模块，
// internal 对 app 内的兄弟包**仍然可见**，编译器拦不住 `SportDark.Card`；
// 模块内的约束由 ThemeSlotTest 的源码守卫兜（禁止 Theme.kt 之外引用色板）。
internal object SportDark {
    val Bg = Color(0xFF0A0D18)
    val Card = Color(0xFF161C33)
    val Card2 = Color(0xFF1E2542)
    val Card3 = Color(0xFF252D4E)
    val Line = Color(0x1AFFFFFF)      // rgba(255,255,255,.10)
    val Line2 = Color(0x2EFFFFFF)     // rgba(255,255,255,.18)
    val Txt = Color(0xFFF4F7FF)
    val Txt2 = Color(0xFFA7B0CC)
    val Txt3 = Color(0xFF6E7799)
    val Red = Color(0xFFFF3B4E)
    val Orange = Color(0xFFFF8A3D)
    val Nba = Color(0xFF3B82F6)
    val Cba = Color(0xFFFF4D6D)
    val Ok = Color(0xFF2FD07E)
    val Warn = Color(0xFFFFC24B)
}

// ---------- 浅色 ----------
internal object SportLight {
    val Bg = Color(0xFFEDF0F8)
    val Card = Color(0xFFFFFFFF)
    val Card2 = Color(0xFFF4F6FC)
    val Card3 = Color(0xFFE9EDF8)
    val Line = Color(0x1A121C3C)
    val Line2 = Color(0x33121C3C)
    val Txt = Color(0xFF0B1020)
    val Txt2 = Color(0xFF4E5878)
    val Txt3 = Color(0xFF818AA8)
    val Red = Color(0xFFE01F3D)
    val Orange = Color(0xFFF07A22)
    val Nba = Color(0xFF2563EB)
    val Cba = Color(0xFFE11D48)
    val Ok = Color(0xFF0E9F5C)
    /**
     * 警告色（浅色主题）。
     *
     * ⚠️ 取 #9E6400 而不是 #C9820A：
     * 原来的 #C9820A 在浅色卡片上只有 **3.14:1**（on Card3 只有 2.91:1），
     * 达不到 WCAG AA 的 4.5:1 —— "该线路暂无信号"这行提示字在亮色主题下偏淡。
     * #9E6400 在白底 4.91:1、Card3 上 4.54:1，两处都达标，且仍是同一个琥珀色相。
     */
    val Warn = Color(0xFF9E6400)
}

/**
 * 把一组颜色逐档压暗到"白字可读"，再拼成渐变。
 *
 * 这些渐变的原始色（设计稿值）压白字只有 2.6~3.6:1，达不到 WCAG AA 的 4.5:1。
 * 但界面又不能全部换成主色（联赛色/直播红是产品识别的一部分），
 * 所以保留色相、只压亮度——视觉上仍是"红→橙""蓝"，白字却真的能读。
 *
 * 注意：只该用在**真的压白字**的地方（状态标签、联赛标签、hero 卡）。
 * 纯装饰（渐变色条、图标底）不需要压暗，继续用原始的 [GradNba] / [GradCba]。
 */
private fun safeOnWhite(colors: List<Color>): Brush =
    Brush.linearGradient(colors = colors.map { it.whiteTextSafe() })

/**
 * 红橙三档渐变色值的**唯一来源**（review/audit-guards.md B11）。
 *
 * 这三档原来在生产里各写了一份：这里 [GradLive]、这里 [GradLiveOnWhite]、
 * 以及 `AccentContrast.HeroGradientStops` —— 三份平行副本。后果是
 * `AccentContrastTest` 的 3 条 "hero 卡 AA 4.5:1" 遍历的是**副本**：
 * 改这里的界面底色，hero 卡可能掉出 AA，而断言照样绿。
 *
 * 现在三处都从这一份派生（改这里三处一起变）：
 *  - [GradLive]：原始亮度，只用于装饰/主 CTA 底；
 *  - [GradLiveOnWhite]：白字安全版（hero 卡、直播状态标签真正用的那条）；
 *  - `AccentContrast.HeroGradientStops`：同一份来源压暗后的裸颜色，给单测算对比度。
 *
 * ⚠️ 别在别处再抄这三个字面量：`ZeroConsumerGuardTest` 会扫生产源码，
 * 同一档色值出现在第二个生产文件里就红。
 */
val LiveGradientSource: List<Color> = listOf(
    Color(0xFFFF2E4D),
    Color(0xFFFF6B2C),
    Color(0xFFFF8A3D),
)

/** 渐变：直播 / 主 CTA（135deg 红→橙）——**原始亮度**，不要在上面压白字 */
val GradLive = Brush.linearGradient(colors = LiveGradientSource)

/** 渐变：直播 / 主 CTA 的**白字安全版**（状态标签、hero 卡用这条） */
val GradLiveOnWhite = safeOnWhite(LiveGradientSource)

/**
 * 强调渐变（白字底色）：按主题主色生成，并逐档压暗到"白字可读"。
 *
 * 用于主 CTA 按钮：写死的红橙渐变压白字只有 2.6~3.5:1，达不到 AA。
 */
@Composable
fun accentBrush(): Brush {
    val primary = MaterialTheme.colorScheme.primary
    // remember：Brush 每次重建都会让使用它的节点失效，主题不变就该复用
    return remember(primary) {
        Brush.linearGradient(colors = AccentContrast.accentStops(primary))
    }
}

/**
 * 把颜色压暗到"白字可读"：背景相对亮度 ≤ 0.1833 时，白字对比度 ≥ 4.5:1。
 *
 * @param scale 额外的整体缩放，用来生成渐变的第二档
 */
fun Color.whiteTextSafe(scale: Float = 1f): Color {
    val maxLuminance = 0.1833f
    var color = Color(red * scale, green * scale, blue * scale, alpha)
    if (color.luminance() <= maxLuminance) return color
    // ⚠️ 原来是 while 循环每档减 0.05，最多 16 次迭代，
    // 而且**步长是固定的**：刚好差一点没压到位时，
    // 结果色会明显比"刚好达标"的那档更暗（视觉上偏色）。
    // 改成二分：直接求"满足亮度的最大缩放系数"，又快又准。
    // 上界必须是 scale —— 这个函数只会把颜色压得更暗，绝不能压得比 scale 亮。
    var lo = 0.2f
    var hi = scale
    repeat(12) {
        val mid = (lo + hi) / 2f
        val c = Color(red * mid, green * mid, blue * mid, alpha)
        if (c.luminance() > maxLuminance) hi = mid else lo = mid
    }
    return Color(red * lo, green * lo, blue * lo, alpha)
}
/** 联赛渐变色 —— 装饰用（色条 / 图标底），不压字 */
val GradNba = Brush.linearGradient(
    colors = listOf(Color(0xFF2563EB), Color(0xFF60A5FA)),
)
val GradCba = Brush.linearGradient(
    colors = listOf(Color(0xFFE11D48), Color(0xFFFB923C)),
)

/** 联赛渐变的**白字安全版**：联赛标签（LeagueChip）压的是 9.5sp 白字，必须压暗 */
val GradNbaOnWhite = safeOnWhite(listOf(Color(0xFF2563EB), Color(0xFF60A5FA)))
val GradCbaOnWhite = safeOnWhite(listOf(Color(0xFFE11D48), Color(0xFFFB923C)))

/** 8dp 间距节奏（设计稿实测 6/8/10/11/16 混用，这里归一到 4 的倍数） */
object Space {
    val xxs = 4.dp
    val xs = 6.dp
    val sm = 8.dp
    val md = 10.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

/** 圆角（设计稿实测值） */
object Radius {
    val card = 22.dp
    val chip = 7.dp
    val chipLg = 8.dp
    val pill = 9.dp
    val btn = 14.dp
    val sheet = 16.dp
    val logo = 999.dp
}

// internal 只为单测能读到槽位取值（ThemeSlotTest）——不是对外 API。
// 生产代码一律从 MaterialTheme.colorScheme 取。
internal val DarkColors = darkColorScheme(
    primary = SportDark.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1220),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = SportDark.Nba,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF16243F),
    onSecondaryContainer = Color(0xFFBBD3FF),
    // tertiary 承载"警告"语义（无信号提示、故障切换提示条）。
    // 设计稿没有第三色板，用琥珀色占用 tertiary 槽位。
    tertiary = SportDark.Warn,
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF3B2A00),
    onTertiaryContainer = SportDark.Warn,
    background = SportDark.Bg,
    onBackground = SportDark.Txt,
    surface = SportDark.Card,
    onSurface = SportDark.Txt,
    surfaceVariant = SportDark.Card3,
    onSurfaceVariant = SportDark.Txt2,
    outline = SportDark.Line2,
    outlineVariant = SportDark.Line,
    error = SportDark.Red,
    onError = Color.White,
    // tonal surface 槽位必须显式铺满（review/03-ui.md P2-28）。
    // 不铺的话 DropdownMenu（容器色就是 surfaceContainer）、ModalBottomSheet、
    // DatePicker 会走 M3 自带的**中性紫灰**（深色 #211F26），挂在上面 #161C33
    // 的蓝调卡片上既不同族、也看不出层级 —— 项目「按语义槽位取色」的约定
    // 只覆盖了一半组件。铺满后这些 M3 组件自动跟随本主题。
    surfaceContainerLowest = SportDark.Bg,
    surfaceContainerLow = SportDark.Card,
    surfaceContainer = SportDark.Card2,
    surfaceContainerHigh = SportDark.Card3,
    surfaceContainerHighest = SportDark.Card3,
    surfaceBright = SportDark.Card3,
    surfaceDim = SportDark.Bg,
    // 反转色（Snackbar）：反色面用主文字色，压在上面的用页面底色，实测对比度足够
    inverseSurface = SportDark.Txt,
    inverseOnSurface = SportDark.Bg,
)

internal val LightColors = lightColorScheme(
    primary = SportLight.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E6),
    onPrimaryContainer = Color(0xFF7A0018),
    secondary = SportLight.Nba,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8FF),
    onSecondaryContainer = Color(0xFF10265C),
    tertiary = SportLight.Warn,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEFC7),
    onTertiaryContainer = Color(0xFF4A3200),
    background = SportLight.Bg,
    onBackground = SportLight.Txt,
    surface = SportLight.Card,
    onSurface = SportLight.Txt,
    surfaceVariant = SportLight.Card3,
    onSurfaceVariant = SportLight.Txt2,
    outline = SportLight.Line2,
    outlineVariant = SportLight.Line,
    error = SportLight.Red,
    onError = Color.White,
    // 同深色：浅色主题的 tonal surface 槽位也必须铺满，否则 DropdownMenu 用
    // M3 默认的 #F3EDF7，与本主题卡片 #FFFFFF 差不到 1.2:1，菜单像没上色的浮层（P2-28）。
    surfaceContainerLowest = SportLight.Card,
    surfaceContainerLow = SportLight.Card2,
    surfaceContainer = SportLight.Card2,
    surfaceContainerHigh = SportLight.Card3,
    surfaceContainerHighest = SportLight.Card3,
    surfaceBright = SportLight.Card,
    surfaceDim = SportLight.Bg,
    inverseSurface = SportLight.Txt,
    inverseOnSurface = SportLight.Bg,
)

private val AppTypography = Typography(
    // 开赛时间大字号（设计稿 mc-time b: 21px/24px 900）
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 30.sp, lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 24.sp, lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 21.sp, lineHeight = 25.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp, lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp, lineHeight = 19.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp, lineHeight = 17.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp, lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, lineHeight = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, lineHeight = 13.sp,
    ),
)

@Composable
fun NbaLiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // ⚠️ 用共用的 findActivity()（递归 ContextWrapper 链），
            // 不要写 `view.context as? Activity`：遇到 Context 包装会**静默失败**，
            // 表现为"状态栏图标不跟随主题"（review/03-ui.md P2-21）。
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            // ⚠️ 状态栏和导航栏要**一起**设（review/03-P1-3）：
            // 原来只设了 isAppearanceLightStatusBars，浅色主题下
            // 底部系统导航键仍是**白色图标**，压在浅色 surfaceScrim 上几乎看不见，
            // 用户会以为返回/主页键坏了。两者是同一个"系统栏配色"问题的两半。
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
