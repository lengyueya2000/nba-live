package com.nbalive.app.ui

/**
 * 播放页浮层的显隐判定（纯逻辑）。
 *
 * 把 Compose 里的 `if (fullscreen && !inPip && controlsVisible && !webFullscreen)`
 * 这类散在布局代码里的条件提出来，为的是能单测 ——
 * 浮层显隐是用户最能直接感知的行为，而它此前的规则只活在
 * `PlayerScreen` 的组合表达式里，改了没有任何测试会红。
 *
 * 典型后果就是 v1.2.9 之后的回归：`webFullscreen` 被塞进底部栏条件，
 * 网页兜底全屏时底部按钮整个消失，用户失去换源/换线路能力。
 */
object PlayerControls {

    /**
     * 底部控制栏是否显示。
     *
     * 规则：
     *  - 只在**全屏**且不在画中画时显示（非全屏时信号列表就在页面上）
     *  - 跟随浮层显隐（单击切换、10 秒无操作自动收起）
     *  - 锁定时隐藏（防误触）
     *
     * ⚠️ **不区分直连与网页兜底**：两条路径都必须有按钮。
     * 早先按 `streamUrl == null` 排除网页线路，理由有两条且都不成立：
     *  1) 源站 H5 播放器自带底部控制条 —— 实测它在 WebView 内部，
     *     我们的浮层画在它之上，两条各在各的层，源站那条仍可点；
     *  2) 网页路径没法接管点击收起浮层 —— v1.2.8 起已支持。
     * 而排除的代价是用户在网页线路里完全无法操作（换源/换线路/重连）。
     *
     * @param streamUrl 直连 m3u8 地址（null = 走网页兜底）
     * @param webFallbackUrl 网页兜底地址
     */
    fun shouldShowBottomBar(
        fullscreen: Boolean,
        inPip: Boolean,
        controlsVisible: Boolean,
        locked: Boolean,
        streamUrl: String?,
        webFallbackUrl: String?,
    ): Boolean = fullscreen && !inPip && controlsVisible && !locked

    /** 一个矩形区域的像素尺寸。 */
    data class BoxSize(val width: Int, val height: Int)

    /**
     * 网页兜底全屏时，WebView 该占的尺寸。
     *
     * ## 为什么要算这个
     *
     * 全屏容器是 `fillMaxSize()`，在真机上就是 19.5:9 / 20:9 这种细长比例，
     * 而视频源是 16:9。WebView 里的源站播放器把 16:9 画面**放大铺满**
     * 整个细长容器，上下各裁掉一截 —— 底部的比分条正好被裁没。
     *
     * 直连路径没这个问题：`PlayerView.resizeMode = FIT` 会保持 16:9
     * 加黑边，画面完整。
     *
     * 实测对照：同一场比赛
     *  - 16:9 屏幕（1280x720）全屏 -> 比分条可见
     *  - 20:9 屏幕全屏 -> 比分条被裁掉
     *
     * ## 算法：等比缩放取「内接矩形」
     *
     * 把 16:9 的矩形塞进容器，两个方向都要试，取能完整放下的那个：
     *  - 先按宽度顶满 -> 算高度，若高度不超容器，宽度就是限制项
     *  - 否则按高度顶满 -> 算宽度
     *
     * ⚠️ **只算高度是不够的**。横屏 20:9 容器（1600x720）里，
     * 16:9 视频受限的是**宽度**（应得 1280x720）；
     * 早先只算高度会得到 `min(720, 1600/1.78=900) = 720`，
     * 和原样完全一样 —— 横屏下等于没修，画面照样被裁。
     *
     * @return 16:9 内接矩形；非全屏时返回 null 表示"不用改"
     *   （非全屏容器本来就是 `aspectRatio(16:9)`，再套一层是多余的）
     */
    fun webViewSizeForAspect(
        containerWidth: Int,
        containerHeight: Int,
        aspectRatio: Float,
        fullscreen: Boolean = true,
    ): BoxSize? {
        if (!fullscreen) return null
        if (containerWidth <= 0 || containerHeight <= 0 || aspectRatio <= 0f) return null

        // 先假设宽度顶满，看高度放不放得下
        val heightIfFullWidth = (containerWidth / aspectRatio).toInt()
        return if (heightIfFullWidth <= containerHeight) {
            // 宽度是限制项：左右留黑边
            BoxSize(containerWidth, heightIfFullWidth)
        } else {
            // 高度是限制项：上下留黑边
            BoxSize((containerHeight * aspectRatio).toInt(), containerHeight)
        }
    }
}
