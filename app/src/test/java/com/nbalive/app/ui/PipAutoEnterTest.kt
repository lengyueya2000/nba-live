package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按 Home 自动进小窗」的判定。
 *
 * 早先只看 `route == PLAYER`，于是这些情况按 Home 也会进小窗：
 *  - 还在解析线路（没有流）
 *  - 线路全部失败、显示"该线路暂无信号"
 *  - 比赛已结束、页面是黑的
 * 用户看到的是一块空的小窗，比不进小窗更困惑。
 *
 * 现在的规则：**播放页 + 确实有流可播** 才允许自动小窗。
 *
 * 测的是生产代码 [PipState.shouldAutoEnterPip]，
 * 不是测试里另写一份镜像逻辑 —— 镜像测试改了生产代码照样绿。
 */
class PipAutoEnterTest {

    @Test
    fun `播放中按 Home 要进小窗`() {
        assertTrue(
            "播放页 + 有流可播，按 Home 应该进小窗",
            PipState.shouldAutoEnterPip(isPlayerRoute = true, hasStream = true),
        )
    }

    @Test
    fun `不在播放页时绝不进小窗`() {
        // 列表页/详情页按 Home 应该正常退到桌面，不能弹出一块空小窗
        assertFalse(
            "不在播放页，即使状态里残留着地址也不该进小窗",
            PipState.shouldAutoEnterPip(isPlayerRoute = false, hasStream = true),
        )
        assertFalse(
            PipState.shouldAutoEnterPip(isPlayerRoute = false, hasStream = false),
        )
    }

    @Test
    fun `播放页但还没解析出流时不进小窗`() {
        // 刚进播放页、线路还在解析
        assertFalse(
            "没有流可播时弹小窗是一块黑窗",
            PipState.shouldAutoEnterPip(isPlayerRoute = true, hasStream = false),
        )
    }

    @Test
    fun `播放页但所有线路都失败时不进小窗`() {
        // 故障切换把所有线路试完、界面显示"该线路暂无信号"
        assertFalse(
            PipState.shouldAutoEnterPip(isPlayerRoute = true, hasStream = false),
        )
    }
}
