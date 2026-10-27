package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放健康度分级（P0-1）。
 *
 * 钉住三件事：
 *  1. **推断只能到 SUSPECT**，不允许触发任何不可逆处置；
 *  2. WebView 的报错要按 errorCode / 主框架 / 重定向 / 加载是否完成**四重过滤**；
 *  3. 小窗处置必须是**可逆**的（保留最近任务）。
 *
 * 背景：旧链路的处置条件是
 * `PipState.shouldKeepPip(hasStream = false, hasPlaybackError = true)` ——
 * 传的是常量 false/true，结果恒为 false，所以 v1.2.9 之后
 * 「小窗里播不出来就收窗」其实**从来没生效过**（review/01-P0-1 的连带发现）。
 */
class PlaybackHealthTest {

    // ---------- 1. 分级：推断 vs 事实 ----------

    @Test
    fun `嗅探超时只到疑似不确认失败`() {
        assertEquals(
            "25 秒没嗅探到 .m3u8 是推断：MSE/blob 播放、地址不带 .m3u8 字面量、" +
                "弱网首帧超 25 秒都会误判，绝不能直接算播不出来",
            PlaybackHealth.Level.SUSPECT,
            PlaybackHealth.levelOf(sniffTimedOut = true, confirmedFailure = false),
        )
    }

    @Test
    fun `播放器真实报错才是确认失败`() {
        assertEquals(
            PlaybackHealth.Level.DEAD,
            PlaybackHealth.levelOf(sniffTimedOut = false, confirmedFailure = true),
        )
        // 两个信号同时成立时以事实为准：确认失败优先于疑似
        assertEquals(
            "推断和事实同时出现时必须按事实分级，否则不可逆处置会被推断顶掉",
            PlaybackHealth.Level.DEAD,
            PlaybackHealth.levelOf(sniffTimedOut = true, confirmedFailure = true),
        )
    }

    @Test
    fun `两个信号都没有时是正常`() {
        assertEquals(
            PlaybackHealth.Level.OK,
            PlaybackHealth.levelOf(sniffTimedOut = false, confirmedFailure = false),
        )
    }

    // ---------- 2. 处置必须可逆 ----------

    @Test
    fun `疑似异常不收小窗`() {
        assertEquals(
            "疑似是推断出来的，推断不配拿走不可逆的处置：小窗可能是用户主动开的，" +
                "也可能画面其实正常（嗅探不到而已）",
            PlaybackHealth.PipDisposition.NONE,
            PlaybackHealth.pipDisposition(PlaybackHealth.Level.SUSPECT),
        )
    }

    @Test
    fun `正常状态也不收小窗`() {
        assertEquals(
            PlaybackHealth.PipDisposition.NONE,
            PlaybackHealth.pipDisposition(PlaybackHealth.Level.OK),
        )
    }

    @Test
    fun `确认失败收窗但保留最近任务`() {
        assertEquals(
            "不能用 finishAndRemoveTask：那会把任务从最近任务里一并移除，" +
                "用户再也找不到入口，而报错本身可能是暂时的",
            PlaybackHealth.PipDisposition.KEEP_RECENTS,
            PlaybackHealth.pipDisposition(PlaybackHealth.Level.DEAD),
        )
    }

    // ---------- 3. 自动进小窗：疑似也要挡住 ----------

    @Test
    fun `疑似与确认都挡住自动进小窗`() {
        assertTrue(PlaybackHealth.blocksAutoEnterPip(PlaybackHealth.Level.SUSPECT))
        assertTrue(PlaybackHealth.blocksAutoEnterPip(PlaybackHealth.Level.DEAD))
        assertFalse(PlaybackHealth.blocksAutoEnterPip(PlaybackHealth.Level.OK))
    }

    @Test
    fun `只有疑似状态才显示疑似提示`() {
        assertTrue(PlaybackHealth.showsSuspectHint(PlaybackHealth.Level.SUSPECT))
        assertFalse(PlaybackHealth.showsSuspectHint(PlaybackHealth.Level.OK))
        // DEAD 有自己的完整错误页，不需要再叠一层"疑似"提示
        assertFalse(PlaybackHealth.showsSuspectHint(PlaybackHealth.Level.DEAD))
    }

    // ---------- 4. WebView 报错的四重过滤（P1-1） ----------

    @Test
    fun `子资源失败不判死`() {
        assertFalse(
            "图片/广告 iframe 加载失败非常常见，主画面不受影响",
            PlaybackHealth.isConfirmedWebFailure(
                errorCode = PlaybackHealth.WEB_ERROR_CONNECT,
                isForMainFrame = false,
                isRedirect = false,
                pageFinished = true,
            ),
        )
    }

    @Test
    fun `ERR_ABORTED 不判死`() {
        // 源站播放页里的 location.href 跳转，或用户连点两次「重连」
        // 让上一次导航被取代 → 主框架收到 ERR_ABORTED。
        // 这是导航被取代，不是加载失败，旧代码一律判死属于误杀。
        assertFalse(
            "重定向/被打断的导航不是失败",
            PlaybackHealth.isConfirmedWebFailure(
                errorCode = PlaybackHealth.WEB_ERROR_ABORTED,
                isForMainFrame = true,
                isRedirect = false,
                pageFinished = true,
            ),
        )
        assertFalse(
            PlaybackHealth.isConfirmedWebFailure(
                errorCode = PlaybackHealth.WEB_ERROR_UNKNOWN,
                isForMainFrame = true,
                isRedirect = true,
                pageFinished = true,
            ),
        )
    }

    @Test
    fun `首次加载完成前不判死`() {
        assertFalse(
            "源站首页常有跳转，页面还没加载完时的错误不能算线路坏了",
            PlaybackHealth.isConfirmedWebFailure(
                errorCode = PlaybackHealth.WEB_ERROR_HOST_LOOKUP,
                isForMainFrame = true,
                isRedirect = false,
                pageFinished = false,
            ),
        )
    }

    @Test
    fun `真实致命错误要判死`() {
        val fatal = listOf(
            PlaybackHealth.WEB_ERROR_HOST_LOOKUP,
            PlaybackHealth.WEB_ERROR_CONNECT,
            PlaybackHealth.WEB_ERROR_TIMEOUT,
            PlaybackHealth.WEB_ERROR_FAILED_SSL_HANDSHAKE,
            PlaybackHealth.WEB_ERROR_BAD_URL,
            PlaybackHealth.WEB_ERROR_FILE_NOT_FOUND,
            PlaybackHealth.WEB_ERROR_UNKNOWN,
        )
        fatal.forEach { code ->
            assertTrue(
                "错误码 $code 是主框架真实失败，必须判死（否则用户对着黑屏干等）",
                PlaybackHealth.isConfirmedWebFailure(
                    errorCode = code,
                    isForMainFrame = true,
                    isRedirect = false,
                    pageFinished = true,
                ),
            )
        }
    }

    @Test
    fun `拿不到错误码时不判死`() {
        assertFalse(
            PlaybackHealth.isConfirmedWebFailure(
                errorCode = null,
                isForMainFrame = true,
                isRedirect = false,
                pageFinished = true,
            ),
        )
    }

    @Test
    fun `未知错误码不判死`() {
        // 源站/WebView 版本会引入新的错误码。判据是白名单而不是"非 ABORTED 即死"，
        // 新码出现时宁可退回"疑似"，也不要拿不可逆处置赌。
        assertFalse(
            PlaybackHealth.isConfirmedWebFailure(
                errorCode = -999,
                isForMainFrame = true,
                isRedirect = false,
                pageFinished = true,
            ),
        )
    }

    // ---------- 5. 媒体请求识别 ----------

    @Test
    fun `不带 m3u8 字面量的分片也算媒体在动`() {
        // 这些是旧判据（只看 .m3u8 字面量）会误杀的真实形态
        val media = listOf(
            "https://cdn.example.com/live/a.m3u8?auth_key=x",
            "https://cdn.example.com/hls/seg/1.ts",
            "https://cdn.example.com/dash/init.m4s",
            "https://cdn.example.com/live/a.mp4",
            "https://cdn.example.com/xxx/playlist.mpd",
            "http://cdn.example.com/live/stream.flv",
            "https://cdn.example.com/segment/12?token=y",
        )
        media.forEach { u ->
            assertTrue("$u 应该被认成媒体请求（在播），不能报疑似", PlaybackHealth.isMediaRequest(u))
        }
    }

    @Test
    fun `页面文档和静态资源不算媒体请求`() {
        // ⚠️ 关键：源站播放页自己的 URL 常常就在 /live/ 目录下
        // （http://www.jrszbt.com/live/555.html）。若把它算成"媒体在动"，
        // 「疑似无信号」的提示就永远不触发 —— 比赛打完了也不提示，等于修出新的静默失败。
        val notMedia = listOf(
            "http://www.jrszbt.com/live/555.html",
            "https://play.example.com/play/sm.html?id=439&id2=",
            "https://cdn.example.com/player/xgplayer.min.js",
            "https://cdn.example.com/css/app.css?v=2",
            "https://img.example.com/live/logo.png",
            "https://m.example.com/ad/redirect?to=https://a.example.com/live/x.m3u8",
        )
        notMedia.forEach { u ->
            assertFalse("$u 不是媒体请求，必须能让疑似判定正常触发", PlaybackHealth.isMediaRequest(u))
        }
    }

    @Test
    fun `静态资源里夹带 m3u8 字样也不升级直连`() {
        // 旧代码用 !u.contains(".js") 粗排；脚本 URL 里经常带 m3u8 字样
        // （播放器配置、日志文本），若当成清单去直连会立刻失败。
        assertFalse(
            PlaybackHealth.isPlaylistRequest("https://cdn.example.com/player/config.js?src=a.m3u8"),
        )
        assertFalse(
            PlaybackHealth.isPlaylistRequest("https://cdn.example.com/live/a.html"),
        )
        assertTrue(
            PlaybackHealth.isPlaylistRequest("https://cdn.example.com/live/a.m3u8?auth_key=x"),
        )
        assertTrue(
            PlaybackHealth.isPlaylistRequest("https://cdn.example.com/dash/manifest.mpd"),
        )
    }

    @Test
    fun `空地址既不算媒体也不算清单`() {
        assertFalse(PlaybackHealth.isMediaRequest(null))
        assertFalse(PlaybackHealth.isMediaRequest(""))
        assertFalse(PlaybackHealth.isPlaylistRequest(null))
        assertFalse(PlaybackHealth.isPlaylistRequest("   "))
    }
}
