package com.nbalive.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 方案 B「运动卡片 / Sport Card」设计 token。
 *
 * 来源：design/sportcard/index.html 的 CSS 变量，一一对应，
 * 保证 Compose 实现与设计稿像素级一致。
 */

// ---------- 暗色（默认） ----------
object SportDark {
    val Bg = Color(0xFF0A0D18)
    val Bg2 = Color(0xFF0F1424)
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
object SportLight {
    val Bg = Color(0xFFEDF0F8)
    val Bg2 = Color(0xFFFFFFFF)
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
    val Warn = Color(0xFFC9820A)
}

/** 渐变：直播 / 主 CTA（135deg 红→橙） */
val GradLive = Brush.linearGradient(
    colors = listOf(Color(0xFFFF2E4D), Color(0xFFFF6B2C), Color(0xFFFF8A3D)),
)
val GradRed = Brush.linearGradient(
    colors = listOf(Color(0xFFFF3B4E), Color(0xFFFF7A2F)),
)
val GradNba = Brush.linearGradient(
    colors = listOf(Color(0xFF2563EB), Color(0xFF60A5FA)),
)
val GradCba = Brush.linearGradient(
    colors = listOf(Color(0xFFE11D48), Color(0xFFFB923C)),
)

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

private val DarkColors = darkColorScheme(
    primary = SportDark.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1220),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = SportDark.Nba,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF16243F),
    onSecondaryContainer = Color(0xFFBBD3FF),
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
)

private val LightColors = lightColorScheme(
    primary = SportLight.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E6),
    onPrimaryContainer = Color(0xFF7A0018),
    secondary = SportLight.Nba,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8FF),
    onSecondaryContainer = Color(0xFF10265C),
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
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
