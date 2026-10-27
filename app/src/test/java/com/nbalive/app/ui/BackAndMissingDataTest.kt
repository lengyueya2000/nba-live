package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回键与「缺数据」两个 UI 判据（review/03-ui.md P2-1 / P2-5）。
 *
 * ## P2-1 非默认标签按返回直接退出 App
 *
 * `BackHandler` 的 enabled 条件原来是
 * `searchOpen || route != LIST || fullscreen` —— **不含标签页**。
 * 用户切到「直播」或「我的」标签后按返回，条件全为假 → BackHandler 不接管
 * → 系统默认行为 = 退出 Activity。用户只是想回「赛事」标签。
 *
 * ## P2-5 缺 match 时静默白屏
 *
 * `Route.DETAIL -> MatchDetailScreen(match = detailState.match ?: return@Box)`
 * —— 拿不到 match 就直接 return，界面上什么都没有（纯白屏），
 * 没有提示、没有返回入口。进程被系统回收后恢复路由时最容易触发。
 */
class BackAndMissingDataTest {

    // ---------- P2-1 返回键 ----------

    @Test
    fun `在非默认标签按返回要回赛事标签 而不是退出 App`() {
        assertTrue(
            "在「直播」/「我的」标签按返回必须被 App 接管（否则系统默认行为是退出 Activity）",
            BackPolicy.shouldIntercept(
                searchOpen = false,
                route = Route.LIST,
                fullscreen = false,
                onDefaultTab = false,
            ),
        )
    }

    @Test
    fun `在默认标签按返回不接管 让系统退出`() {
        // 赛事标签是首页：这里按返回应该退出 App，这是用户预期
        assertFalse(
            BackPolicy.shouldIntercept(
                searchOpen = false,
                route = Route.LIST,
                fullscreen = false,
                onDefaultTab = true,
            ),
        )
    }

    @Test
    fun `搜索 全屏 非列表路由仍然接管`() {
        // 原有三种情形不能因为加了标签判据而退化
        assertTrue(BackPolicy.shouldIntercept(true, Route.LIST, false, onDefaultTab = true))
        assertTrue(BackPolicy.shouldIntercept(false, Route.PLAYER, false, onDefaultTab = true))
        assertTrue(BackPolicy.shouldIntercept(false, Route.LIST, true, onDefaultTab = true))
    }

    @Test
    fun `返回动作的顺序是先关搜索再退路由再退标签`() {
        // 一次按返回只做一件事，且从"最上层的临时状态"开始收
        assertEquals(
            BackAction.CloseSearch,
            BackPolicy.action(true, Route.PLAYER, true, onDefaultTab = false),
        )
        assertEquals(
            BackAction.ExitFullscreen,
            BackPolicy.action(false, Route.PLAYER, true, onDefaultTab = false),
        )
        assertEquals(
            BackAction.LeaveRoute,
            BackPolicy.action(false, Route.PLAYER, false, onDefaultTab = false),
        )
        assertEquals(
            BackAction.ResetTab,
            BackPolicy.action(false, Route.LIST, false, onDefaultTab = false),
        )
        assertEquals(
            BackAction.None,
            BackPolicy.action(false, Route.LIST, false, onDefaultTab = true),
        )
    }

    @Test
    fun `锁定态吃掉返回 且不退出播放页`() {
        // 用户明确要求：只有点解锁键才会解锁，锁定期间返回键被吃掉。
        // backExitsPlayer 由 PlayerLock.backExitsPlayer(locked) 提供，
        // 这里不重写「锁定语义」（那个坑 PlayerLock 的注释记录过）
        assertEquals(
            BackAction.Swallow,
            BackPolicy.action(
                searchOpen = false, route = Route.PLAYER, fullscreen = true,
                onDefaultTab = true, backExitsPlayer = false,
            ),
        )
        // 未锁定时全屏按返回应当退出全屏（不能被 Swallow 吞掉）
        assertEquals(
            BackAction.ExitFullscreen,
            BackPolicy.action(
                searchOpen = false, route = Route.PLAYER, fullscreen = true,
                onDefaultTab = true, backExitsPlayer = true,
            ),
        )
    }

    // ---------- P2-5 缺 match ----------

    @Test
    fun `详情页缺 match 时不能静默白屏`() {
        val state = MissingMatchPolicy.forDetail(hasMatch = false)
        assertTrue(
            "拿不到 match 时必须给出可解释的状态（提示 + 返回入口），而不是 return@Box 白屏",
            state is MissingMatchState.ShowPlaceholder,
        )
    }

    @Test
    fun `详情页有 match 时正常渲染`() {
        assertEquals(MissingMatchState.Render, MissingMatchPolicy.forDetail(hasMatch = true))
    }

    @Test
    fun `播放页缺 match 时同样要给占位而不是白屏`() {
        assertTrue(MissingMatchPolicy.forPlayer(hasMatch = false) is MissingMatchState.ShowPlaceholder)
        assertEquals(MissingMatchState.Render, MissingMatchPolicy.forPlayer(hasMatch = true))
    }
}