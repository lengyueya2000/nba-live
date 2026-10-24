package com.nbalive.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.components.AspectModeSelector
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space

/**
 * "我的"标签页：播放偏好 + 外观 + 关于。
 *
 * 这里放的都是**真的会生效**的设置项：画面模式直接改 PlayerPrefs，
 * 深色模式直接切主题；不做纯展示的假开关。
 *
 * ⚠️ 屏幕常亮不在这里：它是产品固定行为（默认开启、不提供关闭），
 * 由播放页的 PlayerView / WebView 各自设 keepScreenOn 保证。
 * 早先这里有个开关，现在需求改成"默认开启且不暴露给用户"，
 * 所以开关本身也一并移除了。
 */
@Composable
fun ProfileScreen(
    aspectMode: AspectMode,
    darkMode: Boolean,
    versionName: String,
    onAspectChange: (AspectMode) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // padding 必须在 verticalScroll 之前：
            // 反过来的话上下留白会被算进可滚动内容，滚一下就跟着内容跑掉了。
            .padding(top = Space.sm, bottom = Space.xxl)
            .verticalScroll(rememberScrollState()),
    ) {
        // 播放设置
        SectionTitle("播放设置")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.surface)
                .padding(Space.md),
        ) {
            Text(
                text = "画面模式",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "全屏播放时的画面填充方式",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.sm))
            // 与播放器共用同一个控件（components/Common.kt 的 AspectModeSelector），
            // 不再各抄一份——之前两份逐字复制，改配色只改到一半。
            AspectModeSelector(
                aspectMode = aspectMode,
                onAspectChange = onAspectChange,
            )
        }

        // 外观
        SectionTitle("外观")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.surface)
                .padding(Space.md),
        ) {
            SwitchRow(
                title = "深色模式",
                subtitle = if (darkMode) "已开启深色主题" else "已切换为浅色主题",
                checked = darkMode,
                onCheckedChange = onDarkModeChange,
                leadingIcon = true,
                darkMode = darkMode,
            )
        }

        // 关于
        SectionTitle("关于")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.surface)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            InfoRow(label = "数据来源", value = "JRS / JRKAN 双源")
            InfoRow(label = "支持联赛", value = "NBA / CBA")
            InfoRow(label = "播放内核", value = "Media3 ExoPlayer")
            InfoRow(label = "版本", value = versionName)
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    text = "本应用仅聚合第三方公开线路，不存储、不转播任何赛事内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: Boolean = false,
    darkMode: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                // 图标跟着当前主题走：浅色主题下亮一个月亮图标会显得莫名其妙。
                // 纯装饰，模式本身由行标题 + 开关状态说明，所以 contentDescription 为空。
                Icon(
                    imageVector = if (darkMode) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Space.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        )
    }
}
