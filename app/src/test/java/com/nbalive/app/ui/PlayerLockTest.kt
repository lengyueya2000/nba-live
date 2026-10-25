package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页「锁定」的纯逻辑。
 *
 * 锁定 = 防误触：浮层全部隐藏、亮度/音量滑动停用，看球时手滑不会误切线路/退出。
 * 视频继续播，屏幕继续常亮。
 *
 * 交互（用户明确要求）：
 *  - 锁定时画面**完全干净** —— 不显示锁按钮、不显示任何提示
 *  - **单击画面**才唤出锁按钮，3 秒后自动收起
 *  - 只有点锁按钮才解锁；返回键在锁定时不退出播放页
 */
class PlayerLockTest {

    @Test
    fun `锁定时浮层全部隐藏`() {
        // 时钟/画质/右上角按钮/底部控制栏都不能再出现
        assertFalse(PlayerLock.showOverlays(locked = true))
    }

    @Test
    fun `未锁定时浮层照常显示`() {
        assertTrue(PlayerLock.showOverlays(locked = false))
    }

    @Test
    fun `锁定时亮度音量手势必须停用`() {
        // 手势不停用的话，滑动还是会触发亮度/音量变化 —— 那就等于没锁
        assertFalse(PlayerLock.gesturesEnabled(locked = true))
    }

    @Test
    fun `未锁定时手势层正常`() {
        assertTrue(PlayerLock.gesturesEnabled(locked = false))
    }

    @Test
    fun `锁定时底部控制栏不显示`() {
        // 底部栏的完整规则在 PlayerControls（含全屏/画中画/浮层显隐），
        // 这里只钉"锁定"这一个维度：锁定时即使全屏、浮层可见也不显示。
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = true,
                streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8",
                webFallbackUrl = null,
            ),
        )
        assertTrue(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8",
                webFallbackUrl = null,
            ),
        )
    }

    @Test
    fun `锁定时默认不显示锁按钮`() {
        // 用户要求：锁屏时不要显示按钮，画面保持干净
        assertFalse(
            "锁定且没单击过画面时，锁按钮不该出现",
            PlayerLock.showLockButton(locked = true, unlockUiVisible = false),
        )
    }

    @Test
    fun `锁定后单击画面才显示锁按钮`() {
        assertTrue(
            "锁定态下单击唤出解锁 UI 后，锁按钮必须出现（否则无法解锁）",
            PlayerLock.showLockButton(locked = true, unlockUiVisible = true),
        )
    }

    @Test
    fun `未锁定时锁按钮跟随浮层显示`() {
        assertTrue(PlayerLock.showLockButton(locked = false, unlockUiVisible = false))
        assertTrue(PlayerLock.showLockButton(locked = false, unlockUiVisible = true))
    }

    @Test
    fun `锁定时返回键不退出播放页`() {
        // 返回键在锁定时不能把用户带出播放页
        assertFalse(PlayerLock.backExitsPlayer(locked = true))
        assertTrue(PlayerLock.backExitsPlayer(locked = false))
    }

    @Test
    fun `锁定时默认不显示提示`() {
        // 锁定时画面要干净，提示不能常驻
        assertFalse(
            "锁定且没单击过画面时，提示不该出现",
            PlayerLock.showLockHint(locked = true, unlockUiVisible = false),
        )
    }

    @Test
    fun `单击唤出解锁UI时提示一起出现`() {
        assertTrue(
            "唤出解锁 UI 时要有提示告诉用户点哪里",
            PlayerLock.showLockHint(locked = true, unlockUiVisible = true),
        )
    }

    @Test
    fun `未锁定时不显示锁定提示`() {
        assertFalse(PlayerLock.showLockHint(locked = false, unlockUiVisible = true))
        assertFalse(PlayerLock.showLockHint(locked = false, unlockUiVisible = false))
    }

    @Test
    fun `锁定态下单击应该唤出解锁UI`() {
        assertTrue(PlayerLock.tapShowsUnlockUi(locked = true))
        // 未锁定时单击归调用方处理（切换浮层显隐），不走这条
        assertFalse(PlayerLock.tapShowsUnlockUi(locked = false))
    }

    @Test
    fun `解锁UI的自动收起时间要够点得到`() {
        // 太短来不及点，太长又破坏画面干净
        assertTrue(
            "3 秒是可用性与画面干净的折中，不该短于 2 秒",
            PlayerLock.UNLOCK_UI_TIMEOUT_MS >= 2_000,
        )
        assertTrue(
            "超过 5 秒按钮就一直挂着了，失去自动收起的意义",
            PlayerLock.UNLOCK_UI_TIMEOUT_MS <= 5_000,
        )
    }

    @Test
    fun `切到另一场比赛时锁定自动失效`() {
        // 回归：「同时在播」面板切场时 route 一直是 PLAYER，
        // 裸布尔锁会跟着漏到新的一场 —— 用户看着新比赛却是锁着的、浮层全没了。
        // 锁绑定到比赛 id 后，换场自然不再锁定。
        val lockedMatchId = "match-A"
        assertFalse(
            "锁的是 A、看的是 B，不该处于锁定态",
            PlayerLock.isLocked(lockedMatchId = lockedMatchId, currentMatchId = "match-B"),
        )
        assertTrue(
            "锁的就是当前这场，处于锁定态",
            PlayerLock.isLocked(lockedMatchId = lockedMatchId, currentMatchId = "match-A"),
        )
        assertFalse(
            "没锁过就不该是锁定态",
            PlayerLock.isLocked(lockedMatchId = null, currentMatchId = "match-A"),
        )
    }

    @Test
    fun `切场后新的一场可以被正常锁定`() {
        var lockedMatchId: String? = "match-A"
        lockedMatchId = "match-B"
        assertTrue(PlayerLock.isLocked(lockedMatchId, "match-B"))
        assertFalse(PlayerLock.isLocked(lockedMatchId, "match-A"))
    }
}
