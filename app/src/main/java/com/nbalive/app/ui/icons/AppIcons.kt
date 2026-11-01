package com.nbalive.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本地图标集（review/optimization-review.md **B3**）。
 *
 * ## 为什么不直接用 `material-icons-extended`
 *
 * 那个 AAR 的 `classes.jar` 是 **35.7 MB**（每个图标一个类），而本 App 实际只用三十来个；
 * release 侧 R8 会砍到只剩用到的，但**每个 clean build 都要 D8/R8 处理这 35.7 MB**，
 * debug 包更是把整包打进 dex（22 MB / 12 dex）。
 *
 * ## 为什么敢用"生成"而不是"手绘"
 *
 * 这些路径是从官方 AAR 的**字节码**里逐命令还原的（`review/fix/icon-extract.py`），
 * 不是照着图片描的；并且 `AppIconsParityTest` 把每个图标与官方 `Icons.*` 的
 * `ImageVector` 结构（viewport / autoMirror / 每条 path 命令与参数）**逐项比对**，
 * 任何一处还原错误都会当场变红。所以视觉与原版一致这件事是**可验证**的，不靠眼睛。
 *
 * ⚠️ 改这里的图标 = 改 UI 外观。请只新增，不要"顺手调整"路径。
 */
@Suppress("UnusedReceiverParameter")
object AppIcons {

    /** 等价于官方 `Filled.BrightnessHigh`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_BrightnessHigh: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.BrightnessHigh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(20.0f, 8.69f)
                lineTo(20.0f, 4.0f)
                horizontalLineToRelative(-4.69f)
                lineTo(12.0f, 0.69f)
                lineTo(8.69f, 4.0f)
                lineTo(4.0f, 4.0f)
                verticalLineToRelative(4.69f)
                lineTo(0.69f, 12.0f)
                lineTo(4.0f, 15.31f)
                lineTo(4.0f, 20.0f)
                horizontalLineToRelative(4.69f)
                lineTo(12.0f, 23.31f)
                lineTo(15.31f, 20.0f)
                lineTo(20.0f, 20.0f)
                verticalLineToRelative(-4.69f)
                lineTo(23.31f, 12.0f)
                lineTo(20.0f, 8.69f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                reflectiveCurveToRelative(6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                close()
                moveTo(12.0f, 8.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
                reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.BrightnessLow`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_BrightnessLow: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.BrightnessLow",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(20.0f, 15.31f)
                lineTo(23.31f, 12.0f)
                lineTo(20.0f, 8.69f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-4.69f)
                lineTo(12.0f, 0.69f)
                lineTo(8.69f, 4.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(4.69f)
                lineTo(0.69f, 12.0f)
                lineTo(4.0f, 15.31f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(4.69f)
                lineTo(12.0f, 23.31f)
                lineTo(15.31f, 20.0f)
                horizontalLineTo(20.0f)
                verticalLineToRelative(-4.69f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                reflectiveCurveToRelative(6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.CalendarMonth`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_CalendarMonth: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.CalendarMonth",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(19.0f, 4.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(2.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(5.0f)
                curveTo(3.89f, 4.0f, 3.01f, 4.9f, 3.01f, 6.0f)
                lineTo(3.0f, 20.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(6.0f)
                curveTo(21.0f, 4.9f, 20.1f, 4.0f, 19.0f, 4.0f)
                close()
                moveTo(19.0f, 20.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(14.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(9.0f, 14.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(13.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(17.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(9.0f, 18.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(13.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(17.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.CloudOff`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_CloudOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.CloudOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-1.48f, 0.0f, -2.85f, 0.43f, -4.01f, 1.17f)
                lineToRelative(1.46f, 1.46f)
                curveTo(10.21f, 6.23f, 11.08f, 6.0f, 12.0f, 6.0f)
                curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
                verticalLineToRelative(0.5f)
                horizontalLineTo(19.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
                curveToRelative(0.0f, 1.13f, -0.64f, 2.11f, -1.56f, 2.62f)
                lineToRelative(1.45f, 1.45f)
                curveTo(23.16f, 18.16f, 24.0f, 16.68f, 24.0f, 15.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(3.0f, 5.27f)
                lineToRelative(2.75f, 2.74f)
                curveTo(2.56f, 8.15f, 0.0f, 10.77f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(11.73f)
                lineToRelative(2.0f, 2.0f)
                lineTo(21.0f, 20.73f)
                lineTo(4.27f, 4.0f)
                lineTo(3.0f, 5.27f)
                close()
                moveTo(7.73f, 10.0f)
                lineToRelative(8.0f, 8.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
                horizontalLineToRelative(1.73f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.DarkMode`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_DarkMode: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.DarkMode",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(12.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                reflectiveCurveToRelative(4.03f, 9.0f, 9.0f, 9.0f)
                reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
                curveToRelative(0.0f, -0.46f, -0.04f, -0.92f, -0.1f, -1.36f)
                curveToRelative(-0.98f, 1.37f, -2.58f, 2.26f, -4.4f, 2.26f)
                curveToRelative(-2.98f, 0.0f, -5.4f, -2.42f, -5.4f, -5.4f)
                curveToRelative(0.0f, -1.81f, 0.89f, -3.42f, 2.26f, -4.4f)
                curveTo(12.92f, 3.04f, 12.46f, 3.0f, 12.0f, 3.0f)
                lineTo(12.0f, 3.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.ErrorOutline`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_ErrorOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.ErrorOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(11.0f, 15.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(11.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.Fullscreen`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_Fullscreen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Fullscreen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(7.0f, 14.0f)
                lineTo(5.0f, 14.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-2.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(-3.0f)
                close()
                moveTo(5.0f, 10.0f)
                horizontalLineToRelative(2.0f)
                lineTo(7.0f, 7.0f)
                horizontalLineToRelative(3.0f)
                lineTo(10.0f, 5.0f)
                lineTo(5.0f, 5.0f)
                verticalLineToRelative(5.0f)
                close()
                moveTo(17.0f, 17.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-5.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                lineTo(19.0f, 5.0f)
                horizontalLineToRelative(-5.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.FullscreenExit`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_FullscreenExit: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FullscreenExit",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(5.0f, 16.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-5.0f)
                lineTo(5.0f, 14.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(8.0f, 8.0f)
                lineTo(5.0f, 8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(5.0f)
                lineTo(10.0f, 5.0f)
                lineTo(8.0f, 5.0f)
                verticalLineToRelative(3.0f)
                close()
                moveTo(14.0f, 19.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-5.0f)
                verticalLineToRelative(5.0f)
                close()
                moveTo(16.0f, 8.0f)
                lineTo(16.0f, 5.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(5.0f)
                lineTo(19.0f, 8.0f)
                horizontalLineToRelative(-3.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.LightMode`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_LightMode: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.LightMode",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(12.0f, 7.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                reflectiveCurveTo(14.76f, 7.0f, 12.0f, 7.0f)
                lineTo(12.0f, 7.0f)
                close()
                moveTo(2.0f, 13.0f)
                lineToRelative(2.0f, 0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(-2.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveTo(1.45f, 13.0f, 2.0f, 13.0f)
                close()
                moveTo(20.0f, 13.0f)
                lineToRelative(2.0f, 0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(-2.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveTo(19.45f, 13.0f, 20.0f, 13.0f)
                close()
                moveTo(11.0f, 2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveTo(11.0f, 1.45f, 11.0f, 2.0f)
                close()
                moveTo(11.0f, 20.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                curveTo(11.45f, 19.0f, 11.0f, 19.45f, 11.0f, 20.0f)
                close()
                moveTo(5.99f, 4.58f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0.0f)
                reflectiveCurveToRelative(0.39f, -1.03f, 0.0f, -1.41f)
                lineTo(5.99f, 4.58f)
                close()
                moveTo(18.36f, 16.95f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                lineTo(18.36f, 16.95f)
                close()
                moveTo(19.42f, 5.99f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0.0f)
                lineTo(19.42f, 5.99f)
                close()
                moveTo(7.05f, 18.36f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0.0f)
                lineTo(7.05f, 18.36f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.LiveTv`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_LiveTv: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.LiveTv",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(21.0f, 6.0f)
                horizontalLineToRelative(-7.59f)
                lineToRelative(3.29f, -3.29f)
                lineTo(16.0f, 2.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(-0.71f, 0.71f)
                lineTo(10.59f, 6.0f)
                lineTo(3.0f, 6.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.89f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(18.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(23.0f, 8.0f)
                curveToRelative(0.0f, -1.11f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(21.0f, 20.0f)
                lineTo(3.0f, 20.0f)
                lineTo(3.0f, 8.0f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(9.0f, 10.0f)
                verticalLineToRelative(8.0f)
                lineToRelative(7.0f, -4.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.LockOpen`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_LockOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.LockOpen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(12.0f, 17.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(18.0f, 8.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(17.0f, 6.0f)
                curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                horizontalLineToRelative(1.9f)
                curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineToRelative(2.0f)
                lineTo(6.0f, 8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 10.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 20.0f)
                lineTo(6.0f, 20.0f)
                lineTo(6.0f, 10.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.MoreHoriz`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_MoreHoriz: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.MoreHoriz",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(6.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(12.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.OpenInNew`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_OpenInNew: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.OpenInNew",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(19.0f, 19.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(7.0f)
                close()
                moveTo(14.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.59f)
                lineToRelative(-9.83f, 9.83f)
                lineToRelative(1.41f, 1.41f)
                lineTo(19.0f, 6.41f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.Pause`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                lineTo(10.0f, 5.0f)
                lineTo(6.0f, 5.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                lineTo(18.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.PictureInPictureAlt`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_PictureInPictureAlt: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.PictureInPictureAlt",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(19.0f, 11.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-6.0f)
                close()
                moveTo(23.0f, 19.0f)
                lineTo(23.0f, 4.98f)
                curveTo(23.0f, 3.88f, 22.1f, 3.0f, 21.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.88f, -2.0f, 1.98f)
                lineTo(1.0f, 19.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(18.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                close()
                moveTo(21.0f, 19.02f)
                lineTo(3.0f, 19.02f)
                lineTo(3.0f, 4.97f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(14.05f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.PlayCircle`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_PlayCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.PlayCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(9.5f, 16.5f)
                verticalLineToRelative(-9.0f)
                lineToRelative(7.0f, 4.5f)
                lineTo(9.5f, 16.5f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.Schedule`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_Schedule: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Schedule",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(12.5f, 7.0f)
                horizontalLineTo(11.0f)
                verticalLineToRelative(6.0f)
                lineToRelative(5.25f, 3.15f)
                lineToRelative(0.75f, -1.23f)
                lineToRelative(-4.5f, -2.67f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.SignalCellularAlt`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_SignalCellularAlt: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SignalCellularAlt",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(17.0f, 4.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(16.0f)
                horizontalLineToRelative(-3.0f)
                close()
                moveTo(5.0f, 14.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(6.0f)
                lineTo(5.0f, 20.0f)
                close()
                moveTo(11.0f, 9.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(11.0f)
                horizontalLineToRelative(-3.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Filled.SportsBasketball`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val filled_SportsBasketball: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SportsBasketball",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(17.09f, 11.0f)
                horizontalLineToRelative(4.86f)
                curveToRelative(-0.16f, -1.61f, -0.71f, -3.11f, -1.54f, -4.4f)
                curveTo(18.68f, 7.43f, 17.42f, 9.05f, 17.09f, 11.0f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(6.91f, 11.0f)
                curveTo(6.58f, 9.05f, 5.32f, 7.43f, 3.59f, 6.6f)
                curveTo(2.76f, 7.89f, 2.21f, 9.39f, 2.05f, 11.0f)
                horizontalLineTo(6.91f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(15.07f, 11.0f)
                curveToRelative(0.32f, -2.59f, 1.88f, -4.79f, 4.06f, -6.0f)
                curveToRelative(-1.6f, -1.63f, -3.74f, -2.71f, -6.13f, -2.95f)
                verticalLineTo(11.0f)
                horizontalLineTo(15.07f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(8.93f, 11.0f)
                horizontalLineTo(11.0f)
                verticalLineTo(2.05f)
                curveTo(8.61f, 2.29f, 6.46f, 3.37f, 4.87f, 5.0f)
                curveTo(7.05f, 6.21f, 8.61f, 8.41f, 8.93f, 11.0f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(15.07f, 13.0f)
                horizontalLineTo(13.0f)
                verticalLineToRelative(8.95f)
                curveToRelative(2.39f, -0.24f, 4.54f, -1.32f, 6.13f, -2.95f)
                curveTo(16.95f, 17.79f, 15.39f, 15.59f, 15.07f, 13.0f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(3.59f, 17.4f)
                curveToRelative(1.72f, -0.83f, 2.99f, -2.46f, 3.32f, -4.4f)
                horizontalLineTo(2.05f)
                curveTo(2.21f, 14.61f, 2.76f, 16.11f, 3.59f, 17.4f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(17.09f, 13.0f)
                curveToRelative(0.33f, 1.95f, 1.59f, 3.57f, 3.32f, 4.4f)
                curveToRelative(0.83f, -1.29f, 1.38f, -2.79f, 1.54f, -4.4f)
                horizontalLineTo(17.09f)
                close()
            }
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(8.93f, 13.0f)
                curveToRelative(-0.32f, 2.59f, -1.88f, 4.79f, -4.06f, 6.0f)
                curveToRelative(1.6f, 1.63f, 3.74f, 2.71f, 6.13f, 2.95f)
                verticalLineTo(13.0f)
                horizontalLineTo(8.93f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Outlined.CalendarMonth`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val outlined_CalendarMonth: ImageVector by lazy {
        ImageVector.Builder(
            name = "Outlined.CalendarMonth",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(19.0f, 4.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(2.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(5.0f)
                curveTo(3.89f, 4.0f, 3.01f, 4.9f, 3.01f, 6.0f)
                lineTo(3.0f, 20.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(6.0f)
                curveTo(21.0f, 4.9f, 20.1f, 4.0f, 19.0f, 4.0f)
                close()
                moveTo(19.0f, 20.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(14.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(19.0f, 8.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(14.0f)
                verticalLineTo(8.0f)
                close()
                moveTo(9.0f, 14.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(13.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(17.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(9.0f, 18.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(13.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(17.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `Outlined.PlayCircle`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val outlined_PlayCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Outlined.PlayCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
                reflectiveCurveTo(16.41f, 20.0f, 12.0f, 20.0f)
                close()
                moveTo(9.5f, 16.5f)
                lineToRelative(7.0f, -4.5f)
                lineToRelative(-7.0f, -4.5f)
                verticalLineTo(16.5f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `AutoMirrored.Filled.ArrowBackIos`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val automirrored_filled_ArrowBackIos: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoMirrored.Filled.ArrowBackIos",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(11.67f, 3.87f)
                lineTo(9.9f, 2.1f)
                lineTo(0.0f, 12.0f)
                lineToRelative(9.9f, 9.9f)
                lineToRelative(1.77f, -1.77f)
                lineTo(3.54f, 12.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `AutoMirrored.Filled.ArrowForwardIos`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val automirrored_filled_ArrowForwardIos: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoMirrored.Filled.ArrowForwardIos",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(6.23f, 20.23f)
                lineToRelative(1.77f, 1.77f)
                lineToRelative(10.0f, -10.0f)
                lineToRelative(-10.0f, -10.0f)
                lineToRelative(-1.77f, 1.77f)
                lineToRelative(8.23f, 8.23f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `AutoMirrored.Filled.VolumeOff`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val automirrored_filled_VolumeOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoMirrored.Filled.VolumeOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(2.21f)
                lineToRelative(2.45f, 2.45f)
                curveToRelative(0.03f, -0.2f, 0.05f, -0.41f, 0.05f, -0.63f)
                close()
                moveTo(19.0f, 12.0f)
                curveToRelative(0.0f, 0.94f, -0.2f, 1.82f, -0.54f, 2.64f)
                lineToRelative(1.51f, 1.51f)
                curveTo(20.63f, 14.91f, 21.0f, 13.5f, 21.0f, 12.0f)
                curveToRelative(0.0f, -4.28f, -2.99f, -7.86f, -7.0f, -8.77f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                close()
                moveTo(4.27f, 3.0f)
                lineTo(3.0f, 4.27f)
                lineTo(7.73f, 9.0f)
                lineTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                verticalLineToRelative(-6.73f)
                lineToRelative(4.25f, 4.25f)
                curveToRelative(-0.67f, 0.52f, -1.42f, 0.93f, -2.25f, 1.18f)
                verticalLineToRelative(2.06f)
                curveToRelative(1.38f, -0.31f, 2.63f, -0.95f, 3.69f, -1.81f)
                lineTo(19.73f, 21.0f)
                lineTo(21.0f, 19.73f)
                lineToRelative(-9.0f, -9.0f)
                lineTo(4.27f, 3.0f)
                close()
                moveTo(12.0f, 4.0f)
                lineTo(9.91f, 6.09f)
                lineTo(12.0f, 8.18f)
                lineTo(12.0f, 4.0f)
                close()
            }
        }.build()
    }

    /** 等价于官方 `AutoMirrored.Filled.VolumeUp`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val automirrored_filled_VolumeUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoMirrored.Filled.VolumeUp",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {
                moveTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                lineTo(12.0f, 4.0f)
                lineTo(7.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14.0f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
                reflectiveCurveToRelative(-2.99f, -7.86f, -7.0f, -8.77f)
                close()
            }
        }.build()
    }
}
