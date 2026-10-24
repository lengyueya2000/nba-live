package com.nbalive.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 画质（分辨率）显示的取值逻辑。
 *
 * 两条播放路径的画质来源完全不同：
 *  - **ExoPlayer 直连**：拿得到真实视频宽高（`onVideoSizeChanged`）→ 最准
 *  - **WebView 承载**：App 侧看不到画面，只能从嗅探到的 m3u8 地址里
 *    抠源站标注的分辨率参数（很多源站 URL 带 `1080p`/`720p` 这类标记）
 *
 * 解析不到就返回 null —— 界面上不显示空的画质药丸。
 */
class VideoResolutionTest {

    // ---------- 直连路径：真实宽高 -> 档位 ----------

    @Test
    fun `竖屏视频按短边算档位`() {
        // 手机竖拍素材：宽 1080 高 1920，播放器按短边算清晰度
        assertEquals("1080P", VideoResolution.labelOf(1080, 1920))
    }

    @Test
    fun `标准 16 比 9 按短边算档位`() {
        assertEquals("1080P", VideoResolution.labelOf(1920, 1080))
        assertEquals("720P", VideoResolution.labelOf(1280, 720))
        assertEquals("480P", VideoResolution.labelOf(854, 480))
    }

    @Test
    fun `非标准分辨率向下取整到常见档位`() {
        // 常见的 1280x720 容器里放 1278x718
        assertEquals("720P", VideoResolution.labelOf(1278, 718))
        // 960x540
        assertEquals("540P", VideoResolution.labelOf(960, 540))
    }

    @Test
    fun `宽度不足时不显示画质`() {
        // 还没拿到视频尺寸（0），或拿到的是无效值
        assertNull(VideoResolution.labelOf(0, 0))
        assertNull(VideoResolution.labelOf(-1, 100))
        assertNull(VideoResolution.labelOf(320, 240))
    }

    @Test
    fun `超高分辨率不会被算成离谱的档位`() {
        // 4K 是真实存在的档位
        assertEquals("2160P", VideoResolution.labelOf(3840, 2160))
        // 8K 也要有个说法，不能变成 "7680P" 这种看不懂的
        assertEquals("4320P", VideoResolution.labelOf(7680, 4320))
    }

    // ---------- WebView 路径：从 m3u8 URL 里抠分辨率 ----------

    @Test
    fun `从 m3u8 地址里解析分辨率`() {
        assertEquals(
            "1080P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc_1080p.m3u8"),
        )
        assertEquals(
            "720P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc-720P.m3u8"),
        )
        assertEquals(
            "480P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/hd/abc_480p/playlist.m3u8"),
        )
    }

    @Test
    fun `从 URL 参数里解析分辨率`() {
        assertEquals(
            "1080P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc.m3u8?quality=1080p"),
        )
        assertEquals(
            "720P",
            VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc.m3u8?hd=720"),
        )
    }

    @Test
    fun `地址里没有分辨率就返回 null`() {
        assertNull(VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc.m3u8"))
        assertNull(VideoResolution.fromStreamUrl("http://cdn.example.com/live/abc.m3u8?token=xyz"))
        // 端口号里的数字不是分辨率
        assertNull(VideoResolution.fromStreamUrl("http://cdn.example.com:1080/live/abc.m3u8"))
        assertNull(VideoResolution.fromStreamUrl(""))
    }

    @Test
    fun `解析不出已知档位的数字不显示`() {
        // 源站里的编号（如 live/20241024/abc.m3u8）不是分辨率
        assertNull(VideoResolution.fromStreamUrl("http://cdn.example.com/live/1080.m3u8"))
    }
}
