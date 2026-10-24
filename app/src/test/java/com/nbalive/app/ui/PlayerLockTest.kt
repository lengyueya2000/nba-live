package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页「锁定」的纯逻辑。
 *
 * 锁定 = 防误触：浮层全部隐藏、手势层停用，看球时手滑不会误切线路/退出。
 * 视频继续播，屏幕继续常亮。
 *
 * 用户明确要求：**只有点解锁键才能解锁**——
 * 锁定后画面不可点、返回键不退出播放页，解锁入口唯一（右侧中间的锁按钮）。
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
    fun `锁定时手势层必须停用`() {
        // 手势层不停用的话，滑动还是会触发亮度/音量变化，
        // 单击还会切换浮层显隐 —— 那就等于没锁
        assertFalse(PlayerLock.gesturesEnabled(locked = true))
    }

    @Test
    fun `未锁定时手势层正常`() {
        assertTrue(PlayerLock.gesturesEnabled(locked = false))
    }

    @Test
    fun `锁定时底部控制栏不显示`() {
        assertFalse(PlayerLock.showBottomBar(locked = true))
        assertTrue(PlayerLock.showBottomBar(locked = false))
    }

    @Test
    fun `锁定按钮本身在锁定时仍然可见`() {
        // 解锁入口唯一，不能自己把自己藏了
        assertTrue(PlayerLock.showLockButton(locked = true))
        assertTrue(PlayerLock.showLockButton(locked = false))
    }

    @Test
    fun `锁定时返回键不退出播放页`() {
        // 返回键在锁定时不能把用户带出播放页（唯一解锁入口是按钮）
        assertFalse(PlayerLock.backExitsPlayer(locked = true))
        assertTrue(PlayerLock.backExitsPlayer(locked = false))
    }

    @Test
    fun `锁定时中央提示要显示`() {
        assertTrue(PlayerLock.showLockHint(locked = true))
        assertFalse(PlayerLock.showLockHint(locked = false))
    }

    @Test
    fun `锁定时锁按钮不能跟着浮层隐藏`() {
        // 锁定时锁按钮是唯一的解锁入口，浮层藏了它也必须留着
        assertFalse(
            "锁定时锁按钮不能跟着浮层一起隐藏",
            PlayerLock.hideLockButtonWhenOverlaysHidden(locked = true),
        )
        assertTrue(
            "未锁定时锁按钮应该跟着浮层一起隐藏（全屏下挡住画面）",
            PlayerLock.hideLockButtonWhenOverlaysHidden(locked = false),
        )
    }

    @Test
    fun `切到另一场比赛时锁定自动失效`() {
        // 回归：「同时在播」面板切场时 route 一直是 PLAYER，
        // 裸布尔锁会跟着漏到新的一场 —— 用户看着新比赛却是锁着的、浮层全没了。
        // 锁绑定到比赛 id 后，换场自然不再锁定。
        val lockedMatchId = "match-A"
        val currentMatchId = "match-B"
        assertFalse(
            "锁的是 A、看的是 B，不该处于锁定态",
            PlayerLock.isLocked(lockedMatchId = lockedMatchId, currentMatchId = currentMatchId),
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
        // 换场后 lockedMatchId 要能换成新 id，而不是残留旧 id
        var lockedMatchId: String? = "match-A"
        // 切场：解锁旧锁，锁新的一场
        lockedMatchId = "match-B"
        assertTrue(PlayerLock.isLocked(lockedMatchId, "match-B"))
        assertFalse(PlayerLock.isLocked(lockedMatchId, "match-A"))
    }
}
