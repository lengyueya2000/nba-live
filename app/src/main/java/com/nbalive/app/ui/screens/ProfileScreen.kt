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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import com.nbalive.app.data.model.Provider
import com.nbalive.app.ui.AspectMode
import com.nbalive.app.ui.SourceTogglePolicy
import com.nbalive.app.ui.UpdateCheckPolicy
import com.nbalive.app.ui.components.AspectModeSelector
import com.nbalive.app.ui.components.InfoRow
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
    /** 现在参与解析的数据源（2026-10-31 用户需求：可以手动关掉某一个） */
    enabledProviders: Set<Provider>,
    /** 切换某个源；判据（至少留一个）由 SourceTogglePolicy 决定，界面只负责置灰 */
    onProviderToggle: (Provider, Boolean) -> Unit,
    /**
     * 点「检查更新」（2026-10-31 用户需求）。
     *
     * ⚠️ 只有**用户主动点**才发起请求：启动就查会让冷启动多一个大概率失败的往返
     * （GitHub 在国内经常不通），而失败时我们又不该弹任何东西。
     */
    onCheckUpdate: () -> Unit = {},
    /** 检查结果；null = 还没查过 */
    updateState: UpdateCheckPolicy.Decision? = null,
    /** 是否正在检查（按钮显示进度、防连点） */
    checkingUpdate: Boolean = false,
    /** 打开外链（下载新版 APK 的 release 页）；用系统浏览器，不在 App 内下载 */
    onOpenUrl: (String) -> Unit = {},
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

        // 数据源开关（2026-10-31 用户需求）：
        // 「增加一个控制 jrs 和 jrkan 的开关，比如我可以选择不解析某一个播放源，就可以手动关掉」。
        // ⚠️ 关掉的源在数据层就**不发请求**（不是解析完再过滤），
        // 判据见 SourceTogglePolicy（至少留一个，否则永远空列表）。
        SectionTitle("数据源")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.surface)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = SourceTogglePolicy.summary(enabledProviders),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Provider.entries.forEach { provider ->
                // canDisable 只负责**置灰**：真正写状态时 VM 还会再挡一次
                // （两处各写一份实现时，任何一处漏了都会让用户进入"两个都关"的死状态）
                val canDisable = SourceTogglePolicy.canDisable(enabledProviders, provider)
                val checked = SourceTogglePolicy.isEnabled(enabledProviders, provider)
                SwitchRow(
                    title = "${provider.displayName} 源",
                    subtitle = when {
                        !checked -> "已关闭，不会请求这个源"
                        canDisable -> "正在解析这个源的线路"
                        else -> "正在解析 · 至少保留一个源"
                    },
                    checked = checked,
                    onCheckedChange = { onProviderToggle(provider, it) },
                    // 最后一个开着的源：开关置灰，避免用户点出一个永远空列表。
                    // 关着的源永远可以点回来。
                    enabled = !checked || canDisable,
                )
            }
            if (enabledProviders.size == 1) {
                Text(
                    text = SourceTogglePolicy.lastOneHint(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            // 检查更新（2026-10-31 用户需求）：本 App 走 GitHub Releases 分发，
            // 装完就再也不知道有没有新版 —— 这里给一个手动入口。
            // ⚠️ 失败**静默**（GitHub 在国内经常不通）：只在用户点了之后把那一行
            // 显示成「已是最新」或「有新版本 x.y.z」，不弹任何错误。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.btn))
                    .clickable(enabled = !checkingUpdate, onClick = onCheckUpdate)
                    .padding(vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "检查更新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            checkingUpdate -> "正在检查…"
                            updateState != null -> UpdateCheckPolicy.statusText(updateState)
                            else -> "点一下看看有没有新版本"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (checkingUpdate) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 查到了新版本：给一个明确的下载入口（点开浏览器，不在 App 内下载）
            (updateState as? UpdateCheckPolicy.Decision.Available)?.let { available ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.btn))
                        .clickable(onClick = { onOpenUrl(available.downloadUrl) })
                        .padding(vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = "下载 ${available.latest}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
    enabled: Boolean = true,
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
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
// InfoRow 已提到 ui/components/Common.kt（review/03-ui.md P2-19）：
// 这里原来有一份与 MatchDetailScreen 逐字相同的私有副本。
