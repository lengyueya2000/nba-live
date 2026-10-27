package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页兜底全屏时底部控制栏的显隐。
 *
 * 实测回归（v1.2.9 之后，用户截图反馈）：
 * 直连失败退回网页播放（WebView 承载源站播放页）后，全屏单击画面，
 * **底部没有任何按钮** —— 用户没法换源、换线路、重连，也找不到退出入口，
 * 只能干看着一个自己操作不了的播放器。
 *
 * 根因在 PlayerScreen：`webFullscreen = fullscreen && !inPip && streamUrl == null`
 * 被塞进底部栏的显示条件里，把网页线路整条排除了。
 *
 * 当初的理由有两条，现在都不成立：
 *  1) "源站 H5 播放器自带底部控制条，我们的栏盖上去就没法用了" ——
 *     实测：源站控制条在 WebView 内部，我们的 Compose 浮层画在它之上，
 *     两条控制条各在各的层，源站那条仍然可以点。
 *     真正被挡的只是视觉位置重叠，而我们的按钮用的是**半透明渐变底**，
 *     不是不透明的实色块。
 *  2) "网页路径没法接管点击把浮层收起来" ——
 *     v1.2.8 起两条路径都支持「单击显隐 + 10 秒无操作自动收起」，
 *     这条前提已经不存在了。
 *
 * 而排除它的代价是实的：用户在网页线路里**完全失去操作能力**。
 * 两害相权，按钮必须画出来。
 */
class WebFallbackControlsTest {

    @Test
    fun `网页兜底全屏时底部控制栏也要显示`() {
        assertTrue(
            "退回网页播放后用户还要换源/换线路/重连，按钮不能消失",
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = null,          // 网页兜底：没有直连地址
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `直连全屏时底部控制栏正常显示`() {
        assertTrue(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8?sign=x",
                webFallbackUrl = null,
            ),
        )
    }

    @Test
    fun `非全屏不显示底部控制栏`() {
        // 底部栏是全屏专属：非全屏时信号列表就在页面上，不需要浮层按钮
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = false,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `画中画窗口内不显示底部控制栏`() {
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = true,
                controlsVisible = true,
                locked = false,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `锁定时不显示底部控制栏`() {
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = true,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    @Test
    fun `浮层收起时不显示底部控制栏`() {
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = false,
                locked = false,
                streamUrl = null,
                webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
            ),
        )
    }

    // ---------- 「不区分路径」必须被测试守住（P2-10） ----------
    //
    // 这条是这次审查点出的问题：streamUrl / webFallbackUrl 两个参数
    // **完全没参与判定**，而上面那些用例精心构造了它们的组合 ——
    // 把 streamUrl=null 换成任意值结果都一样，等于没测。
    //
    // 保留参数是有意的：将来若要按路径区分（比如网页线路隐藏
    // 「画面」胶囊），签名不用再动；而且调用方传进来本身就是自文档。
    // 代价是必须显式钉住"不区分"这个契约 —— 下面用**等价性**来钉：
    // 对同一组布尔条件遍历四种 URL 组合，结果必须完全一致。

    @Test
    fun `底部控制栏不区分直连与网页路径`() {
        val urlCombos = listOf(
            // (streamUrl, webFallbackUrl, 说明)
            Triple("https://hlsz.yjjcfw.com/live/1.m3u8", null, "直连"),
            Triple(null, "https://play.sportsteam368.com/play/kbs/?id=1", "网页兜底"),
            // 解析中/两样都有：实际不会发生，但函数必须照样只按布尔判定
            Triple(null, null, "解析中"),
            Triple(
                "https://hlsz.yjjcfw.com/live/1.m3u8",
                "https://play.sportsteam368.com/play/kbs/?id=1",
                "两者都有",
            ),
        )

        listOf(true, false).forEach { fullscreen ->
            listOf(true, false).forEach { inPip ->
                listOf(true, false).forEach { controlsVisible ->
                    listOf(true, false).forEach { locked ->
                        val results = urlCombos.map { (stream, web, _) ->
                            PlayerControls.shouldShowBottomBar(
                                fullscreen = fullscreen,
                                inPip = inPip,
                                controlsVisible = controlsVisible,
                                locked = locked,
                                streamUrl = stream,
                                webFallbackUrl = web,
                            )
                        }
                        assertEquals(
                            "URL 组合不该影响结果：fullscreen=$fullscreen inPip=$inPip " +
                                "controlsVisible=$controlsVisible locked=$locked " +
                                "results=$results（顺序：直连/网页/解析中/两者都有）",
                            1,
                            results.toSet().size,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `网页路径与直连路径在同样布尔条件下逐项相同`() {
        // 上面那条是"全组合等价"，这条更直白地钉住两条路径本身：
        // 用户实际会遇到的两种状态（全屏+浮层可见+未锁定）都必须为 true。
        listOf(true, false).forEach { locked ->
            listOf(true, false).forEach { controlsVisible ->
                val direct = PlayerControls.shouldShowBottomBar(
                    fullscreen = true,
                    inPip = false,
                    controlsVisible = controlsVisible,
                    locked = locked,
                    streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8",
                    webFallbackUrl = null,
                )
                val web = PlayerControls.shouldShowBottomBar(
                    fullscreen = true,
                    inPip = false,
                    controlsVisible = controlsVisible,
                    locked = locked,
                    streamUrl = null,
                    webFallbackUrl = "https://play.sportsteam368.com/play/kbs/?id=1",
                )
                assertEquals(
                    "直连与网页两条路径必须同进同退（locked=$locked visible=$controlsVisible）",
                    direct,
                    web,
                )
            }
        }
    }

    // ---------- 「画面」胶囊：网页路径上点了没反应（P2-11） ----------
    //
    // 审查发现：`aspectMode` 只传给了直连路径的 `PlayerView.resizeMode`，
    // 网页路径完全没用它 —— WebView 尺寸被硬编码成 16:9 内接矩形。
    // 用户在网页线路全屏里点「画面 自适应」切到「铺满裁切」/「拉伸铺满」，
    // 胶囊文字变了、画面毫无变化；更糟的是这个选择被 PlayerPrefs 持久化，
    // 之后进直连线路才突然生效 —— 用户会认为"这个按钮有时灵有时不灵"。
    //
    // 最差的一种是"显示了三档但不生效"。这里选择**网页路径不渲染这枚胶囊**：
    // 网页路径的画面缩放由源站 H5 播放器自己决定，我们改不了，
    // 假装能改只会误导用户。

    @Test
    fun `直连路径才显示画面胶囊`() {
        assertTrue(
            "直连路径由 PlayerView.resizeMode 控制，胶囊有效",
            PlayerControls.shouldShowAspectChip(streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8"),
        )
        assertFalse(
            "网页路径改不了源站播放器的缩放，别给用户一个点了没反应的按钮",
            PlayerControls.shouldShowAspectChip(streamUrl = null),
        )
    }
}
