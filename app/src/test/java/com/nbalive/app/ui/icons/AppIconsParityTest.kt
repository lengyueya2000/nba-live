package com.nbalive.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地图标集的**等价比对**（review/optimization-review.md **B3** 的验收判据）。
 *
 * ## 为什么需要它
 *
 * B3 把 `material-icons-extended`（`classes.jar` 35.7 MB）从 `implementation` 移走，
 * 换成：由 `material-icons-core` 提供的 11 个继续用官方图标，其余 **25 个由
 * `review/fix/icon-extract.py` 从官方 AAR 的字节码里逐命令还原**成 `AppIcons`。
 *
 * "从字节码还原"这件事**必须被验证**，否则就是典型的假安全感：
 * 路径少一段、参数顺序错、`autoMirror` 忘了开，界面都会"看起来还行"但实际是错的 ——
 * 而这类错误**没有编译器、没有运行时异常、也没有肉眼可见的失败**。
 *
 * 所以这条测试把每个本地图标与官方 `Icons.*` 的 `ImageVector` **逐项比对**：
 *  - viewport / 默认尺寸 / `autoMirror`；
 *  - 每一个 `VectorPath` 的 `pathData`（逐节点、逐参数，含相对/绝对命令的区别）；
 *  - 填充色与 alpha。
 *
 * `material-icons-extended` 现在只挂在 **testImplementation** 上（不影响 APK），
 * 正是为了让这条比对能成立 —— 这也是"删依赖"与"能验证"同时做到的代价最小的办法。
 *
 * ⚠️ 若有人改了 `AppIcons` 里任何一个路径，这条测试会立刻红并指出是哪个图标。
 */
class AppIconsParityTest {

    @Test
    fun `25 个本地图标必须与官方 extended 逐命令一致`() {
        val pairs: List<Pair<String, Pair<ImageVector, ImageVector>>> = listOf(
            "filled.BrightnessHigh" to (AppIcons.filled_BrightnessHigh to Icons.Filled.BrightnessHigh),
            "filled.BrightnessLow" to (AppIcons.filled_BrightnessLow to Icons.Filled.BrightnessLow),
            "filled.CalendarMonth" to (AppIcons.filled_CalendarMonth to Icons.Filled.CalendarMonth),
            "filled.CloudOff" to (AppIcons.filled_CloudOff to Icons.Filled.CloudOff),
            "filled.DarkMode" to (AppIcons.filled_DarkMode to Icons.Filled.DarkMode),
            "filled.ErrorOutline" to (AppIcons.filled_ErrorOutline to Icons.Filled.ErrorOutline),
            "filled.Fullscreen" to (AppIcons.filled_Fullscreen to Icons.Filled.Fullscreen),
            "filled.FullscreenExit" to (AppIcons.filled_FullscreenExit to Icons.Filled.FullscreenExit),
            "filled.LightMode" to (AppIcons.filled_LightMode to Icons.Filled.LightMode),
            "filled.LiveTv" to (AppIcons.filled_LiveTv to Icons.Filled.LiveTv),
            "filled.LockOpen" to (AppIcons.filled_LockOpen to Icons.Filled.LockOpen),
            "filled.MoreHoriz" to (AppIcons.filled_MoreHoriz to Icons.Filled.MoreHoriz),
            "filled.OpenInNew" to (AppIcons.filled_OpenInNew to Icons.Filled.OpenInNew),
            "filled.Pause" to (AppIcons.filled_Pause to Icons.Filled.Pause),
            "filled.PictureInPictureAlt" to (AppIcons.filled_PictureInPictureAlt to Icons.Filled.PictureInPictureAlt),
            "filled.PlayCircle" to (AppIcons.filled_PlayCircle to Icons.Filled.PlayCircle),
            "filled.Schedule" to (AppIcons.filled_Schedule to Icons.Filled.Schedule),
            "filled.SignalCellularAlt" to (AppIcons.filled_SignalCellularAlt to Icons.Filled.SignalCellularAlt),
            "filled.SportsBasketball" to (AppIcons.filled_SportsBasketball to Icons.Filled.SportsBasketball),
            "outlined.CalendarMonth" to (AppIcons.outlined_CalendarMonth to Icons.Outlined.CalendarMonth),
            "outlined.PlayCircle" to (AppIcons.outlined_PlayCircle to Icons.Outlined.PlayCircle),
            "automirrored.filled.ArrowBackIos" to
                (AppIcons.automirrored_filled_ArrowBackIos to Icons.AutoMirrored.Filled.ArrowBackIos),
            "automirrored.filled.ArrowForwardIos" to
                (AppIcons.automirrored_filled_ArrowForwardIos to Icons.AutoMirrored.Filled.ArrowForwardIos),
            "automirrored.filled.VolumeOff" to
                (AppIcons.automirrored_filled_VolumeOff to Icons.AutoMirrored.Filled.VolumeOff),
            "automirrored.filled.VolumeUp" to
                (AppIcons.automirrored_filled_VolumeUp to Icons.AutoMirrored.Filled.VolumeUp),
        )
        assertEquals("本地图标数量变了 —— 这条比对的覆盖面必须跟着更新（现在是 25 个）", 25, pairs.size)

        val failures = pairs.mapNotNull { pair ->
            val label = pair.first
            val local = pair.second.first
            val official = pair.second.second
            val localSig = signature(local)
            val officialSig = signature(official)
            if (localSig == officialSig) null
            else "$label:\n    local    = $localSig\n    official = $officialSig"
        }
        assertTrue(
            "以下本地图标与官方实现**结构不一致**（少一段路径、参数顺序错、autoMirror 忘了开……）：\n" +
                failures.joinToString("\n") +
                "\n请重新跑 `review/fix/icon-extract.py` 生成，而不是手工改 AppIcons.kt。",
            failures.isEmpty(),
        )
    }

    @Test
    fun `由 core 提供的图标必须仍然解析到官方实现`() {
        // 这 11 个留在 `Icons.*`（material-icons-core 只有 0.8 MB）：
        // 这里断言它们的路径非空 —— 若哪天 core 不再提供、或有人误删依赖，
        // 会以"空路径图标"的形态静默变丑，所以钉一下。
        val coreIcons = listOf(
            "filled.Check" to Icons.Filled.Check,
            "filled.Clear" to Icons.Filled.Clear,
            "filled.Close" to Icons.Filled.Close,
            "filled.Info" to Icons.Filled.Info,
            "filled.Lock" to Icons.Filled.Lock,
            "filled.Person" to Icons.Filled.Person,
            "filled.PlayArrow" to Icons.Filled.PlayArrow,
            "filled.Refresh" to Icons.Filled.Refresh,
            "filled.Search" to Icons.Filled.Search,
            "filled.Warning" to Icons.Filled.Warning,
            "outlined.Person" to Icons.Outlined.Person,
        )
        for ((label, icon) in coreIcons) {
            assertTrue("$label 的路径是空的（图标会渲染成空白）", pathCommandCount(icon) > 0)
        }
    }

    @Test
    fun `比对判据自检 改一个参数就必须被发现`() {
        // 防"永远绿的比对"：手工构造一个与官方不同的 vector，证明 diff 抓得到。
        val official = Icons.Filled.Pause
        val tampered = ImageVector.Builder(
            name = official.name,
            defaultWidth = official.defaultWidth,
            defaultHeight = official.defaultHeight,
            viewportWidth = official.viewportWidth + 0.5f, // ← 故意改一个数
            viewportHeight = official.viewportHeight,
            autoMirror = official.autoMirror,
        ).apply {
            path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
                moveTo(1f, 1f)
                close()
            }
        }.build()
        assertTrue(
            "比对函数对「viewport 被改过」的 vector 报了等（判据退化成摆设）",
            signature(tampered) != signature(official),
        )
        // ⚠️ 反射可用性自检：`childrenOf` 依赖 Compose 的私有字段名 `children`。
        // 它一旦改名/改可见性，上面那条比对会**静默变成空扫**（每个图标都"相等"），
        // 所以这里先钉住"反射真的读得到子节点"。
        assertTrue(
            "反射读不到 VectorGroup.children 了 —— Compose 改了内部结构，" +
                "上面的等价比对会退化成空扫（所有图标都判等）。请更新 childrenOf 的实现。",
            childrenOf(official.root).isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------

    /** 结构化签名：尺寸 + autoMirror + 每条路径的每个节点（用 `toString()` 避免依赖 PathNode 子类型可见性）。 */
    /**
     * 把一个 `PathNode` 编成**确定性**文本。
     *
     * `PathNode` 的多数子类（`MoveTo`/`LineTo`/`RelativeCurveTo`…）覆写了 `toString`，
     * 但 `Close` 没有 —— 它打出来是 `…$Close@<identityHash>`。抹掉身份哈希即可，
     * 既保留结构（命令种类 + 参数），又不会每次运行都不一样。
     */
    private fun nodeText(node: androidx.compose.ui.graphics.vector.PathNode): String =
        node.toString().replace(Regex("@[0-9a-fA-F]+"), "")

    private fun signature(icon: ImageVector): String {
        val sb = StringBuilder()
        sb.append(icon.defaultWidth).append('|')
            .append(icon.defaultHeight).append('|')
            .append(icon.viewportWidth).append('|')
            .append(icon.viewportHeight).append('|')
            .append(icon.autoMirror).append('|')
        appendGroup(icon.root, sb)
        return sb.toString()
    }

    private fun appendGroup(group: VectorGroup, sb: StringBuilder) {
        sb.append("G(").append(group.name).append(')')
        for (node in childrenOf(group)) {
            when (node) {
                is VectorPath -> {
                    sb.append("P[")
                    // ⚠️ 不能用裸 `toString()`：`PathNode.Close` 没覆写 toString，
                    // 打出来是 `PathNode$Close@<identityHash>` —— 每次运行都不同，
                    // 比对会变成"随机红"。这里把身份哈希抹掉，只留结构信息。
                    node.pathData.forEach { sb.append(nodeText(it)).append(',') }
                    sb.append("]fill=").append(node.fill)
                        .append(",fa=").append(node.fillAlpha)
                        .append(",sa=").append(node.strokeAlpha)
                        .append(",st=").append(node.stroke)
                        .append(",sw=").append(node.strokeLineWidth)
                        .append(';')
                }
                is VectorGroup -> appendGroup(node, sb)
                else -> sb.append("?")
            }
        }
    }

    /**
     * 取 `VectorGroup` 的子节点。
     *
     * ⚠️ 这里**必须用反射**：Compose 把 `VectorGroup.children` 声明成
     * `private val`（实测编译报 "Cannot access 'val children': it is private"），
     * 而 `VectorPath.pathData` 是公开的。要在不渲染像素的前提下做"逐命令等价比对"，
     * 这是唯一入口。
     *
     * 为什么宁愿反射也不放弃比对：这是**唯一**能证明"25 个图标与原版一致"的手段；
     * 放弃它等于让 B3 变成"看起来对"（正是本仓库反复批评的假安全感）。
     * 为了让反射失效时**不会静默变绿**，下面 [比对判据自检] 里有一条专门的
     * "反射仍然可用"断言（Compose 改名/改可见性时会当场红，并告诉你怎么改）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun childrenOf(group: VectorGroup): List<VectorNode> =
        (CHILDREN_FIELD.get(group) as? List<VectorNode>)
            ?: error("反射读不到 VectorGroup.children —— Compose 改了字段（本比对判据会静默失效，所以硬失败）")

    private fun pathCommandCount(icon: ImageVector): Int {
        var count = 0
        fun walk(group: VectorGroup) {
            for (node in childrenOf(group)) {
                when (node) {
                    is VectorPath -> count += node.pathData.size
                    is VectorGroup -> walk(node)
                    else -> Unit
                }
            }
        }
        walk(icon.root)
        return count
    }

    private companion object {
        /** `VectorGroup.children`（私有字段）—— 见 [childrenOf] 的说明。 */
        val CHILDREN_FIELD: java.lang.reflect.Field =
            VectorGroup::class.java.getDeclaredField("children").apply { isAccessible = true }
    }
}