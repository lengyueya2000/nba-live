package com.nbalive.app.ui

import kotlin.math.abs

/**
 * 「自动」画面档的策略（2026-11-26 用户需求：针对不同机型做画面自适应）。
 *
 * 拿到**视频宽高比**和**容器（屏幕）宽高比**后决定最终档位：
 *  - 宽高未就绪（<=0）：[AspectMode.FIT] —— 首帧前/WebView 拿不到宽高时的安全默认，
 *    完整显示、绝不裁切；
 *  - 比例接近（相对偏差 <= [MATCH_THRESHOLD]）：[AspectMode.ZOOM] —— 铺满，
 *    差异小到几乎不裁，视频里的比分条安全；
 *  - 差异大：[AspectMode.FIT] —— 留黑边完整显示，避免把底部比分条裁掉
 *    （iQOO z11x 20:9 长屏放 16:9 视频的原始反馈）。
 *
 * 永不返回 [AspectMode.FILL]（强行拉伸变形）；永不返回 [AspectMode.AUTO] 自己 ——
 * 手动档原样透传，只有 AUTO 档才进入判断。
 *
 * 纯函数、不碰 Android 类，可在 JVM 单测覆盖；阈值集中在此，便于调参。
 */
internal object AutoAspectPolicy {

    /** 比例接近判定阈值：`|videoAspect / containerAspect - 1| <= 此值` 视为接近。 */
    const val MATCH_THRESHOLD = 0.10f

    /**
     * @param mode 用户当前选的画面档
     * @param videoAspect 视频宽高比（width / height），未知传 <=0
     * @param containerAspect 容器宽高比（width / height），未知传 <=0
     * @return 最终生效的档位（FIT / ZOOM / FILL 之一，不会是 AUTO）
     */
    fun resolve(mode: AspectMode, videoAspect: Float, containerAspect: Float): AspectMode {
        if (mode != AspectMode.AUTO) return mode
        if (videoAspect <= 0f || containerAspect <= 0f) return AspectMode.FIT
        // 对称偏差：容器变宽 11% 与变窄 11% 要同样判定。
        // 单向除 videoAspect/containerAspect 不对称（变宽 11% 时比值只降约 9%，
        // 会错落进阈值），所以用 max(v/c, c/v) - 1 == |v-c| / min(v,c)。
        val ratio = if (videoAspect >= containerAspect) {
            videoAspect / containerAspect
        } else {
            containerAspect / videoAspect
        }
        val deviation = abs(ratio - 1f)
        return if (deviation <= MATCH_THRESHOLD) AspectMode.ZOOM else AspectMode.FIT
    }
}
