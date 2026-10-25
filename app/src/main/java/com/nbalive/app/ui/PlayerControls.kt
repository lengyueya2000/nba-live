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
}
