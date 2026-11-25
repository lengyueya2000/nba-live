package com.nbalive.app.data.remote

import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [LineEquivalence] 判据：跨 host / 跨源站的"同一路线路"识别（2026-11-15 第三源接入）。
 *
 * 背景（实测）：`play.sportsteam356.com` 与 `play.sportsteam368.com` 是同一套
 * 播放后端的镜像（同 path+id 的页面 `playerUrlBase64` 解出同一个
 * `demo.songzenet.com/paps.html?id=` 入口，仅每次下发的 `encodedStr` 时效签名不同），
 * 而 xxzb5的「国内线路⑤–⑨」正是 JRS（368）/ JRKAN（356）同一批线路的第三种拼法。
 * 只按整串 URL 去重（旧 [distinctBy pageUrl] 语义）时，host 或参数顺序一变
 * 就漏进来一批语义重复的线路；而按"播放器 + id"归一化后：
 *  - `kbs` 与 `vxt` 即便同 id 也**必须**算两条（不同上游，实测 88 同场都给）；
 *  - `88player.top/m3u8.html?url=` 代理壳按**解码后的真实 m3u8** 归一，
 *    `auth_key` 不同 = 不同签名快照，**都要保留**（一个过期另一个还能用）。
 */
class LineEquivalenceTest {

    @Test
    fun `跨 host 同播放器同 id 判为同一路`() {
        val a = "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2="
        val b = "http://play.sportsteam356.com/play/kbs/?id=10022600231&id2="
        assertEquals(LineEquivalence.key(a), LineEquivalence.key(b))
    }

    @Test
    fun `id 不同必须算两条`() {
        val a = "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2="
        val b = "http://play.sportsteam368.com/play/kbs/?id=10022600232&id2="
        assertNotEquals(LineEquivalence.key(a), LineEquivalence.key(b))
    }

    @Test
    fun `同 id 不同播放器必须算两条`() {
        // 实测 88 同一场给 kbs/?id=10022600231 与 vxt/?id=10022600231 —— 两个不同上游
        val a = "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2="
        val b = "http://play.sportsteam368.com/play/vxt/?id=10022600231&id2="
        assertNotEquals(LineEquivalence.key(a), LineEquivalence.key(b))
    }

    @Test
    fun `参数顺序不影响归一`() {
        val a = "http://play.sportsteam368.com/play/mgxl/?id=00588403&id2="
        val b = "http://play.sportsteam368.com/play/mgxl/?id2=&id=00588403"
        assertEquals(LineEquivalence.key(a), LineEquivalence.key(b))
    }

    @Test
    fun `sm 中转页与内层页按 id 归一 跨 host 同路`() {
        assertEquals(
            LineEquivalence.key("http://play.sportsteam356.com/play/sm.html?id=439&id2="),
            LineEquivalence.key("http://play.sportsteam368.com/play/sm.html?id=439&id2="),
        )
        assertNotEquals(
            LineEquivalence.key("http://h/play/sm.html?id=439&id2="),
            LineEquivalence.key("http://h/play/sm.html?id=440&id2="),
        )
        assertNotEquals(
            LineEquivalence.key("http://h/play/sm.html?id=439&id2="),
            LineEquivalence.key("http://h/play/439.html"),
        )
    }

    @Test
    fun `steam 入口页按 playId 归一`() {
        assertEquals(
            LineEquivalence.key("http://play.sportsteam356.com/play/steam826640.html"),
            LineEquivalence.key("http://play.sportsteam368.com/play/steam826640.html"),
        )
        assertNotEquals(
            LineEquivalence.key("http://h/play/steam826640.html"),
            LineEquivalence.key("http://h/play/steam826641.html"),
        )
    }

    @Test
    fun `m3u8 代理壳按真实清单地址归一 跨包装同路`() {
        val real = "https://www.rtmpcdn.com/live/qq1.m3u8"
        val wrapped = "https://play.88player.top/m3u8.html?url=" +
            java.net.URLEncoder.encode(real, "UTF-8")
        assertEquals(LineEquivalence.key(wrapped), LineEquivalence.key(real))
    }

    @Test
    fun `auth_key 不同的两条签名快照都保留`() {
        val a = "https://play.88player.top/m3u8.html?url=" +
            java.net.URLEncoder.encode(
                "https://play.aarray.cn/rrty/hd-zh-2-3931673_gqzm.m3u8?auth_key=1794703894-0-0-aaa",
                "UTF-8",
            )
        val b = "https://play.88player.top/m3u8.html?url=" +
            java.net.URLEncoder.encode(
                "https://play.aarray.cn/rrty/hd-zh-2-3931673_gqzm.m3u8?auth_key=1794707697-0-0-bbb",
                "UTF-8",
            )
        assertNotEquals(LineEquivalence.key(a), LineEquivalence.key(b))
    }

    @Test
    fun `未知形态退回整串归一 不做激进合并`() {
        // taifood / cctv 这类源在解析层就被剔除；这里只保证判据对陌生 URL
        // 保守：不同 host 就是不同 key，绝不误合并。
        assertNotEquals(
            LineEquivalence.key("https://a.example.com/some/path?id=1"),
            LineEquivalence.key("https://b.example.com/some/path?id=1"),
        )
    }

    @Test
    fun `空串与畸形地址不抛异常`() {
        // 判据用在 distinctBy 上，坏输入必须能落到"可比较的字符串"而不是炸
        assertEquals(LineEquivalence.key(""), LineEquivalence.key(""))
        assertNotEquals(LineEquivalence.key("not a url"), LineEquivalence.key(""))
    }

    @Test
    fun `大小写与 scheme 差异归一`() {
        assertEquals(
            LineEquivalence.key("HTTP://PLAY.Sportsteam368.COM/play/kbs/?id=1&id2="),
            LineEquivalence.key("http://play.sportsteam368.com/play/kbs/?id=1"),
        )
    }

    /** 语义锚点：判据是**跨 Provider 的线路去重键**，与"哪个源报的"无关。 */
    @Test
    fun `同一线路来自不同源也同键`() {
        val url = "http://play.sportsteam368.com/play/kbs/?id=10022600231&id2="
        val fromJrs = StreamSource("jrs-1", "信号3", Provider.JRS, url)
        val fromThird = StreamSource("third-1", "国内线路 Q ⑤", Provider.XXZB5, url)
        assertEquals(LineEquivalence.key(fromJrs.pageUrl), LineEquivalence.key(fromThird.pageUrl))
    }

    /** Provider 扩到三源后，旧断言里「JRS+JRKAN 双源」的地方都要显式声明范围。 */
    @Test
    fun `三源枚举齐全且名字唯一`() {
        assertEquals(3, Provider.entries.size)
        assertEquals(
            listOf("JRS", "JRKAN", "XXZB5"),
            Provider.entries.map { it.name },
        )
    }
}
