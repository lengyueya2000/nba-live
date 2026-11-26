package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「自动」画面档（2026-11-26 用户需求：针对不同机型做画面自适应）。
 *
 * 自动档要做的事：拿**视频宽高比**和**容器（屏幕）宽高比**比一比 ——
 *  - 比例接近（差异 <= [AutoAspectPolicy.MATCH_THRESHOLD]）：ZOOM 铺满，
 *    差异小到几乎不裁，比分条安全；
 *  - 差异大：FIT 完整显示留黑边，绝不裁掉视频里的比分条；
 *  - 宽高未知（首帧前 / WebView 拿不到）：FIT 安全默认；
 *  - 永不用 FILL（强行拉伸变形）。
 *
 * 这些判据必须能在 JVM 上纯函数测到，不能靠真机截图肉眼判断。
 */
class AutoAspectPolicyTest {

    @Test
    fun `手动三档原样返回 不受宽高影响`() {
        // 用户手动切到某档后，自动策略必须是透明的：选什么就是什么。
        for (aspect in listOf(0.5f, 1f, 16f / 9f, 2.5f)) {
            assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.FIT, aspect, 16f / 9f))
            assertEquals(AspectMode.ZOOM, AutoAspectPolicy.resolve(AspectMode.ZOOM, aspect, 16f / 9f))
            assertEquals(AspectMode.FILL, AutoAspectPolicy.resolve(AspectMode.FILL, aspect, 16f / 9f))
        }
    }

    @Test
    fun `自动档 比例接近时铺满`() {
        // 16:9 视频在 16.5:9 容器：差异约 3%，铺满几乎不裁。
        val video = 16f / 9f
        val container = 16.5f / 9f
        assertEquals(AspectMode.ZOOM, AutoAspectPolicy.resolve(AspectMode.AUTO, video, container))

        // 反过来容器更窄也一样（差异在阈值内）。
        assertEquals(AspectMode.ZOOM, AutoAspectPolicy.resolve(AspectMode.AUTO, 1f, 0.96f))
    }

    @Test
    fun `自动档 差异大时完整显示保住比分条`() {
        // iQOO z11x 20:9 长屏放 16:9 视频：差异约 20%，必须 FIT 留黑边，
        // 否则 ZOOM 会把视频底部比分条裁掉（用户原始反馈）。
        val video = 16f / 9f
        val container = 20f / 9f
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, video, container))

        // 竖屏短视频放横屏容器同理。
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, 0.6f, 2f))
    }

    @Test
    fun `自动档 阈值边界 内铺满外完整`() {
        // 阈值取 videoAspect/containerAspect 与 1 的偏差绝对值。
        // 0.09 <= 10% 铺满；0.11 > 10% 完整。
        assertEquals(AspectMode.ZOOM, AutoAspectPolicy.resolve(AspectMode.AUTO, 1f, 1.09f))
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, 1f, 1.11f))
        // 容器更窄一侧同样对称。
        assertEquals(AspectMode.ZOOM, AutoAspectPolicy.resolve(AspectMode.AUTO, 1f, 0.92f))
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, 1f, 0.88f))
    }

    @Test
    fun `自动档 宽高未知时安全回退 FIT`() {
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, 0f, 16f / 9f))
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, 16f / 9f, 0f))
        assertEquals(AspectMode.FIT, AutoAspectPolicy.resolve(AspectMode.AUTO, -1f, 1f))
    }

    @Test
    fun `自动档 永不选 FILL 也永不返回 AUTO`() {
        // 自动档只会在 FIT / ZOOM 之间二选一，绝不变形、绝不把 AUTO 自己当结果。
        val aspects = listOf(0.3f, 0.8f, 1f, 1.78f, 2.2f, 4f)
        val containers = listOf(0.5f, 1f, 1.78f, 2.2f, 3f)
        for (v in aspects) for (c in containers) {
            val r = AutoAspectPolicy.resolve(AspectMode.AUTO, v, c)
            assertEquals(true, r == AspectMode.FIT || r == AspectMode.ZOOM)
        }
    }
}
