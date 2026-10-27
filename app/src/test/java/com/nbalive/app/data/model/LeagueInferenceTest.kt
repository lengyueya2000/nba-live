package com.nbalive.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 联赛名解析失败时，用**队名**反查联赛（review/02-data.md P1-3）。
 *
 * ## 要解决什么
 *
 * 旧链路是「联赛名解析失败 → `League.OTHER` → merge 里 `filter { league in supported }`
 * → 整场被丢弃」。这是一条**连日志都没有**的丢场次路径：
 * 源站把 `<span class="name">` 换成 `<span class="league">`，
 * 整个 JRKAN 源的 119 场会全部变成 OTHER、全部被丢掉，
 * 而 App 的表现只是"场次少了"，日志里一行都看不到。
 *
 * ## 修法
 *
 * 队名是**独立字段**，比联赛名更难被改版一次性打空；
 * 而且 CBA 球队在源站普遍用省份/城市简称（"浙江"、"山东"），
 * NBA 球队用通用昵称（"湖人"、"勇士"），辨识度很高。
 * 所以联赛名拿不到时，用队名兜底反查。
 *
 * ⚠️ 兜底**只在联赛名为空**时启用：
 * 联赛名是"英超"这种**非空但认不出**的值时，那是真的非篮球赛事，
 * 不能靠队名硬猜成 NBA/CBA（否则会把足球赛塞进列表）。
 *
 * ⚠️ 两队指向**不同**联赛（或都认不出）时返回 null —— 不猜。
 */
class LeagueInferenceTest {

    @Test
    fun `能从 NBA 队名反查联赛`() {
        assertEquals(League.NBA, League.inferFromTeams("湖人", "勇士"))
        assertEquals(League.NBA, League.inferFromTeams("凯尔特人", "雄鹿"))
        assertEquals(League.NBA, League.inferFromTeams("掘金", "热火"))
    }

    @Test
    fun `能从 CBA 队名反查联赛`() {
        assertEquals(League.CBA, League.inferFromTeams("浙江", "山东"))
        assertEquals(League.CBA, League.inferFromTeams("广东", "辽宁"))
        assertEquals(League.CBA, League.inferFromTeams("深圳", "新疆"))
    }

    @Test
    fun `只有一队认得出也能反查`() {
        // 源站有一侧写"待定"是很常见的
        assertEquals(League.NBA, League.inferFromTeams("湖人", "待定"))
        assertEquals(League.CBA, League.inferFromTeams("", "广东"))
    }

    @Test
    fun `两队指向不同联赛时不猜`() {
        assertNull("湖人 vs 广东 明显矛盾，不能猜", League.inferFromTeams("湖人", "广东"))
    }

    @Test
    fun `认不出的队名返回 null`() {
        assertNull(League.inferFromTeams("曼联", "阿森纳"))
        assertNull(League.inferFromTeams("", ""))
    }

    @Test
    fun `只认队名不认其它联赛名`() {
        // "国王"是 NBA 的萨克拉门托国王；"太阳"是菲尼克斯太阳。
        // 但"广东宏远"这类含省份简称的必须是 CBA，不能被误判。
        assertEquals(League.CBA, League.inferFromTeams("广东宏远", "浙江稠金"))
        assertEquals(League.NBA, League.inferFromTeams("国王", "鹈鹕"))
    }
}
