package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单一导航状态机（review/03-ui.md P2-3）。
 *
 * ## 要解决什么
 *
 * `tab` 是 Composable 本地 `rememberSaveable`，`route` 在 ViewModel ——
 * 两个状态机互不知情，靠"切标签时顺手调一次 `viewModel.back()`"来对齐。
 *
 * 这个补救动作**只在当前这条路径上是够的**：
 * `route != LIST` 时点底部标签，先 `back()` 回 LIST，再改 `tab`。
 * 但 `back()` 的语义是"回上一层"，不是"我要这个标签" ——
 * 将来若允许从「我的」跳到详情，`back()` 只把 route 拉回 LIST，
 * 而 `tab` 仍是 PROFILE，页面内容与选中标签就错位了。
 *
 * ## 修法
 *
 * 把"当前标签"并入同一个导航状态 [NavState]：
 * 标签切换与路由迁移是**同一次状态迁移**，不存在需要补救的中间态。
 * 图标留在 UI 层按 [NavTab] 映射，所以这个状态机是纯的、可在 JVM 单测里跑。
 */
class NavStateTest {

    @Test
    fun `切标签是顶层跳转 一并回到列表层并丢掉详情上下文`() {
        val onDetail = NavState(tab = NavTab.LIVE, route = Route.DETAIL, matchId = "m1")
        val after = onDetail.selectTab(NavTab.PROFILE)
        assertEquals(NavTab.PROFILE, after.tab)
        assertEquals(
            "切标签必须同一次迁移里回到列表层 —— 旧实现靠'顺手调一次 back()'补救（P2-3）",
            Route.LIST,
            after.route,
        )
        assertNull("离开详情页后不该还挂着那个 match", after.matchId)
    }

    @Test
    fun `从列表直接播放 返回回到列表`() {
        val fromList = NavState(tab = NavTab.MATCHES, route = Route.LIST)
        val playing = fromList.openPlayer("m1")
        assertEquals(Route.PLAYER, playing.route)
        assertEquals("m1", playing.matchId)
        val back = playing.back()
        assertEquals(Route.LIST, back.route)
        assertNull(back.matchId)
    }

    @Test
    fun `从详情页进入播放页 返回回到详情页且保留 match`() {
        val onDetail = NavState(route = Route.DETAIL, matchId = "m1")
        val back = onDetail.openPlayer("m1").back()
        assertEquals(Route.DETAIL, back.route)
        assertEquals(
            "回详情页必须带着 match：详情页靠它渲染，丢了就只剩占位提示",
            "m1",
            back.matchId,
        )
    }

    @Test
    fun `播放页内切场不会把返回目标改成播放页`() {
        // 「同时在播」面板切场：route 一直是 PLAYER，来源必须保持不变
        val playing = NavState(route = Route.LIST).openPlayer("m1")
        val switched = playing.openPlayer("m2")
        assertEquals(Route.PLAYER, switched.route)
        assertEquals("m2", switched.matchId)
        assertEquals(Route.LIST, switched.back().route)
    }

    @Test
    fun `详情层返回回到列表并清掉 match`() {
        val back = NavState(route = Route.DETAIL, matchId = "m1").back()
        assertEquals(Route.LIST, back.route)
        assertNull(back.matchId)
    }

    @Test
    fun `列表层返回是空操作`() {
        val onList = NavState(tab = NavTab.LIVE, route = Route.LIST)
        assertEquals(onList, onList.back())
    }

    // ---------------- 进程重建（P2-2 的存档解码） ----------------

    @Test
    fun `进程重建时播放页恢复成详情页 并保住 match 与标签`() {
        // 直播地址带时效签名，恢复到播放页只会是一块黑屏 + 错误；
        // 审查建议回到"线路列表 + 重新播放"（review/03-ui.md P2-2）。
        val restored = navStateFromStored(
            tab = "LIVE",
            route = "PLAYER",
            matchId = "m9",
            beforePlayer = "LIST",
        )
        assertEquals(Route.DETAIL, restored.route)
        assertEquals("m9", restored.matchId)
        assertEquals(NavTab.LIVE, restored.tab)
    }

    @Test
    fun `重建时列表层不带 match 上下文`() {
        val restored = navStateFromStored("MATCHES", "LIST", "m1", "LIST")
        assertEquals(Route.LIST, restored.route)
        assertNull(restored.matchId)
    }

    @Test
    fun `存档字段缺失或非法时退回默认值 而不是崩`() {
        assertEquals(NavState(), navStateFromStored(null, null, null, null))
        assertEquals(NavState(), navStateFromStored("NOPE", "NOPE", "m1", "NOPE"))
        // 半截存档：route 合法但 tab 非法，各字段独立兜底
        assertEquals(NavTab.MATCHES, navStateFromStored("NOPE", "DETAIL", "m1", null).tab)
    }

    @Test
    fun `存档是纯字段 不依赖 Android 类型`() {
        // 这个状态机必须能在 JVM 单测里跑：字段只有枚举名与字符串
        val stored = NavState(tab = NavTab.PROFILE, route = Route.DETAIL, matchId = "m1")
        val fields = stored.toStoredFields()
        assertEquals(setOf("nav.tab", "nav.route", "nav.match", "nav.beforePlayer"), fields.keys)
        assertEquals(stored, navStateFromStored(
            tab = fields["nav.tab"] as String?,
            route = fields["nav.route"] as String?,
            matchId = fields["nav.match"] as String?,
            beforePlayer = fields["nav.beforePlayer"] as String?,
        ))
    }
}