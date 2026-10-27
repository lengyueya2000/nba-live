package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动点「画中画」按钮的守卫（review/01-player.md P2-6）。
 *
 * ## 要解决什么
 *
 * 按钮的 onClick 直接调 `onEnterPip()`，`enterPip()` 只判系统版本与硬件特性，
 * **不判当前有没有流可播**。于是：
 *  - 线路还在解析时点画中画 → 得到一块转圈/黑的小窗
 *  - 所有线路都失败（界面显示"暂无信号"）时点 → 得到一块坏掉的小窗
 *
 * 「按 Home 自动进小窗」那条路早就有 `shouldAutoEnterPip` 守卫
 * （`PipAutoEnterTest` 覆盖了），**手动按钮这条路没有** —— 同一个问题两条路径、
 * 只有一条被守住，是典型的"改了一半"。
 *
 * ## 规则
 *
 * 手动进小窗与自动进小窗用**同一条**判据（[PipState.shouldAutoEnterPip]），
 * 但给用户的反馈不同：
 *  - 自动（按 Home）：静默不弹，用户本来就没打算小窗；
 *  - 手动（点按钮）：必须给一句提示，否则用户以为按钮坏了。
 */
class ManualPipGuardTest {

    @Test
    fun `没有流时点画中画要给提示 而不是弹黑窗`() {
        val decision = ManualPipPolicy.decide(isPlayerRoute = true, hasStream = false)
        assertTrue(
            "没有流可播时点画中画必须拦住（否则得到一块黑/转圈小窗）",
            decision is ManualPipDecision.Blocked,
        )
        assertTrue(
            "拦住时必须给用户提示，否则他会以为按钮坏了",
            (decision as ManualPipDecision.Blocked).message.isNotBlank(),
        )
    }

    @Test
    fun `正在解析时点画中画同样要拦住`() {
        // 解析中 = 还没有流；界面上是转圈，进小窗就是转圈小窗
        assertTrue(
            ManualPipPolicy.decide(isPlayerRoute = true, hasStream = false) is ManualPipDecision.Blocked,
        )
    }

    @Test
    fun `有流可播时正常进小窗`() {
        assertEquals(
            ManualPipDecision.Allow,
            ManualPipPolicy.decide(isPlayerRoute = true, hasStream = true),
        )
    }

    @Test
    fun `不在播放页时也不该进小窗`() {
        // 防御：按钮只在播放页可见，但状态机不该依赖 UI 的可见性
        assertTrue(
            ManualPipPolicy.decide(isPlayerRoute = false, hasStream = true) is ManualPipDecision.Blocked,
        )
    }

    @Test
    fun `手动与自动用同一条判据 避免两条路径漂移`() {
        // 关键约束：两条路径的"能不能进小窗"必须完全一致。
        // 不一致会出现"按 Home 不进、点按钮进"这种自相矛盾的行为。
        for (playerRoute in listOf(true, false)) {
            for (hasStream in listOf(true, false)) {
                val auto = PipState.shouldAutoEnterPip(playerRoute, hasStream)
                val manual = ManualPipPolicy.decide(playerRoute, hasStream) == ManualPipDecision.Allow
                assertEquals(
                    "手动与自动的判定必须一致（isPlayerRoute=$playerRoute, hasStream=$hasStream）",
                    auto,
                    manual,
                )
            }
        }
    }

    // ---------- P2-5 小窗比例不沿用上一场 ----------

    @Test
    fun `切场后小窗比例要复位 不能沿用上一场的宽高`() {
        // 回归：setVideoSize 只在 onVideoSizeChanged 里写，切场时没人清零 ——
        // 新场次的首帧到达之前，小窗按**上一场**的比例显示。
        assertTrue(
            "切场时必须把视频尺寸复位（否则小窗沿用上一场比例）",
            PipAspectReset.shouldResetOnMatchSwitch(),
        )
    }

    @Test
    fun `复位后回退到 16比9`() {
        val ratio = PipAspectReset.ratioFor(0, 0)
        assertEquals("没有真实宽高时用 16:9 兜底", 16f / 9f, ratio, 0.001f)
    }

    @Test
    fun `有真实宽高时用真实比例`() {
        val ratio = PipAspectReset.ratioFor(1920, 1080)
        assertEquals(1920f / 1080f, ratio, 0.001f)
    }

    @Test
    fun `异常宽高不让比例变成 NaN 或负数`() {
        // 负数/0 都来自"还没拿到"或"播放器给了脏数据"
        for ((w, h) in listOf(-1 to 1080, 1920 to 0, 0 to 0, -5 to -5)) {
            val ratio = PipAspectReset.ratioFor(w, h)
            assertFalse("宽高 ($w, $h) 不能算出非法比例 $ratio", ratio.isNaN() || ratio <= 0f)
        }
    }
}