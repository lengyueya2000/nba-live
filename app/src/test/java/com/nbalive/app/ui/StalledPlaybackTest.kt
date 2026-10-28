package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放中途卡死的判定（review/01-player.md P2-1）。
 *
 * ## 要解决什么
 *
 * 现有的看门狗只覆盖**首帧**：`LaunchedEffect(streamUrl) { delay(30s); if (!reachedReady) 判死 }`。
 * 一旦首帧成功、播了十分钟之后 CDN 节点抽风（连接不断、只是不再吐分片），
 * 播放器会停在 BUFFERING，`onPlayerError` **不触发**，
 * 于是界面只有转圈，永远不会自动换线 —— 用户只能手动点换线。
 *
 * ## 判据要点
 *
 * 1. **必须已就绪过**：没就绪的那段归首帧看门狗管，两条判据不能重叠，
 *    否则一个"首帧慢"的线路会被两条逻辑同时判死（重复换线）；
 * 2. **必须是持续 BUFFERING**：直播流正常重缓冲就是 BUFFERING，
 *    短暂几秒不该换线 —— 只有连续卡住超过阈值才算；
 * 3. **任何播放进度都要能解除**：位置前进 / 状态回到 READY 就清零计时。
 */
class StalledPlaybackTest {

    /** 卡死阈值：30 秒连续无进展。 */
    private val threshold = StalledPlayback.STALL_THRESHOLD_MS

    @Test
    fun `首帧就没就绪时不归它管`() {
        // 首帧阶段由 FirstFrame 看门狗负责；两条判据重叠会导致重复换线
        val verdict = StalledPlayback.evaluate(
            reachedReady = false,
            playbackState = StalledPlayback.STATE_BUFFERING,
            bufferingForMs = threshold * 2,
        )
        assertEquals(
            "从未就绪过的线路属于首帧看门狗的范围，不该由中途卡死判据再判一次",
            StalledPlayback.Verdict.NOT_APPLICABLE,
            verdict,
        )
    }

    @Test
    fun `就绪后持续缓冲超过阈值判卡死`() {
        assertEquals(
            StalledPlayback.Verdict.STALLED,
            StalledPlayback.evaluate(
                reachedReady = true,
                playbackState = StalledPlayback.STATE_BUFFERING,
                bufferingForMs = threshold,
            ),
        )
    }

    @Test
    fun `缓冲未到阈值不算卡死`() {
        // 直播流正常重缓冲就是 BUFFERING，几秒钟不该换线
        assertEquals(
            StalledPlayback.Verdict.PLAYING,
            StalledPlayback.evaluate(
                reachedReady = true,
                playbackState = StalledPlayback.STATE_BUFFERING,
                bufferingForMs = threshold - 1,
            ),
        )
    }

    @Test
    fun `就绪后正常播放不算卡死`() {
        assertEquals(
            StalledPlayback.Verdict.PLAYING,
            StalledPlayback.evaluate(
                reachedReady = true,
                playbackState = StalledPlayback.STATE_READY,
                bufferingForMs = 0,
            ),
        )
    }

    @Test
    fun `暂停和结束状态不算卡死`() {
        // 用户主动暂停时状态不是 BUFFERING；播放结束也不该判卡死
        for (state in listOf(StalledPlayback.STATE_IDLE, StalledPlayback.STATE_ENDED)) {
            assertEquals(
                "状态 $state 不该判卡死",
                StalledPlayback.Verdict.PLAYING,
                StalledPlayback.evaluate(reachedReady = true, playbackState = state, bufferingForMs = threshold * 3),
            )
        }
    }

    @Test
    fun `计时器在状态变化时重置 而不是一直累加`() {
        // 回归风险：如果只在 LaunchedEffect 里起一个计时器而不看状态，
        // 播了两小时里累计的缓冲时间会早就超过阈值 -> 每次进缓冲就误判。
        val tracker = StalledPlayback.Tracker(threshold)
        assertFalse(tracker.onTick(reachedReady = true, playbackState = StalledPlayback.STATE_BUFFERING, nowMs = 0))
        assertFalse(tracker.onTick(true, StalledPlayback.STATE_READY, nowMs = threshold / 2))
        // 状态回到 READY 后应清零：再进缓冲要重新计时
        assertFalse(
            "回到 READY 必须清零计时，否则累计的缓冲时间会误判",
            tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = threshold / 2 + 1),
        )
        // 从这次进入缓冲开始累计满阈值 -> 判卡死
        assertTrue(
            "连续缓冲满阈值应当判卡死",
            tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = threshold / 2 + 1 + threshold),
        )
    }

    @Test
    fun `播放位置前进会重新计时 而不是立刻判卡死`() {
        // 有些播放器在追帧时状态短暂停在 BUFFERING 但位置在前进 —— 那是在播，不是卡死。
        // 位置前进的正确语义是**重新开始计时**，不是"永远不判卡死"：
        // 否则一个先前进一次、随后彻底死掉的流就永远换不掉了。
        val tracker = StalledPlayback.Tracker(threshold)
        tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = 0)
        // 在 100ms 时观察到位置前进 -> 计时从 100 重新开始
        tracker.onProgress(positionMs = 1_000, nowMs = 100)
        assertFalse(
            "刚有进展时不该判卡死",
            tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = threshold + 50),
        )
        assertTrue(
            "但位置停止前进后再连续满阈值，仍然要判卡死（否则死流永远换不掉）",
            tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = 100 + threshold),
        )
    }

    @Test
    fun `同一段卡死只报一次 避免连续换线`() {
        val tracker = StalledPlayback.Tracker(threshold)
        tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = 0)
        assertTrue("到阈值要报一次", tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = threshold))
        assertFalse(
            "同一段卡死不该反复上报，否则会连续触发多次故障切换",
            tracker.onTick(true, StalledPlayback.STATE_BUFFERING, nowMs = threshold + 1_000),
        )
    }

    // ---------- B5：判据唯一来源 ----------

    @Test
    fun `卡死判据只有一个来源 Tracker 必须委托 evaluate`() {
        // 审计 B5：阈值/就绪判据在 evaluate 与 Tracker.onTick 里**各写一份**，
        // 改一处不影响另一处，而两边都有测试覆盖 → 看起来"都测了"。
        // 上面那几条纯判据永远绿，因为它们测的是 evaluate —— 生产只走 Tracker。
        // 所以这里查的是**委托关系**：onTick 函数体内必须出现 evaluate( 调用。
        val raw = productionSource("StalledPlayback.kt")
        assertTrue(
            "StalledPlayback.kt 只有 ${raw.lines().size} 行（应 >100）—— " +
                "扫描范围走偏时这条守卫会静默全绿",
            raw.lines().size > 100,
        )
        val body = functionBody(raw, "fun onTick(")
        assertTrue(
            "Tracker.onTick 体内必须出现 `evaluate(` 调用（审计 B5）：\n" +
                "把委托删掉、退回内联的 `nowMs - since < thresholdMs` 判据时，这条必须变红 ——\n" +
                "否则阈值判据又变成两份实现，改一处不影响另一处。\n" +
                "实际 onTick 函数体：\n$body",
            body.contains("evaluate("),
        )
    }

    // ---------------------------------------------------------------

    /** 生产源码原文（找不到就硬失败，绝不用 assume 家族静默跳过）。 */
    private fun productionSource(name: String): String {
        val root = listOf(
            File("src/main/java/com/nbalive/app"),
            File("app/src/main/java/com/nbalive/app"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到生产源码根（cwd=${System.getProperty("user.dir")}）")
        return root.walkTopDown()
            .firstOrNull { it.isFile && it.name == name }
            ?.readText()
            ?: error("找不到生产文件 $name —— 这条守卫会静默全绿，所以直接失败")
    }

    /** 从 `declaration`（如 `fun onTick(`）开始、用花括号配对截出的函数体（已剔注释/字符串）。 */
    private fun functionBody(rawText: String, declaration: String): String {
        val code = GuardSourceScan.stripNonCode(rawText)
        val start = code.indexOf(declaration)
        if (start < 0) error("找不到声明 `$declaration`（守卫会静默失效，所以直接失败）")
        val open = code.indexOf('{', start)
        if (open < 0) error("`$declaration` 后面找不到函数体起始 `{`")
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        error("`$declaration` 的函数体花括号没有配对")
    }
}