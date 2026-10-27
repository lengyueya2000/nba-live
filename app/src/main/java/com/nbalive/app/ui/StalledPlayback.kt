package com.nbalive.app.ui

/**
 * 播放**中途**卡死的判定（review/01-player.md P2-1）。
 *
 * ## 要解决什么
 *
 * 现有看门狗只覆盖**首帧**（`PlayerScreen` 里 `delay(FIRST_FRAME_WATCHDOG_MS)`
 * + `!reachedReady`）。一旦首帧成功、播了十分钟之后 CDN 节点抽风
 * （连接不断、只是不再吐分片），播放器会停在 BUFFERING，
 * `onPlayerError` **不触发** —— 界面只有转圈，永远不会自动换线，
 * 用户只能自己点换线。
 *
 * ## 与首帧看门狗的分工（关键）
 *
 * | 场景 | 判据 |
 * |---|---|
 * | 从未就绪过（首帧慢/死） | 首帧看门狗（`FIRST_FRAME_WATCHDOG_MS`） |
 * | 已就绪过、之后连续卡住 | 本判据 |
 *
 * 两条判据**不能重叠**：否则一个"首帧慢"的线路会被两边各判一次，
 * 连续触发两次故障切换，用户看到画面莫名其妙连跳两条线。
 * 所以 [evaluate] 在 `reachedReady = false` 时直接返回
 * [Verdict.NOT_APPLICABLE]。
 *
 * ## 为什么必须"持续"缓冲
 *
 * 直播流正常重缓冲的状态就是 BUFFERING，短暂几秒是健康的。
 * 只有**连续**超过 [STALL_THRESHOLD_MS] 没有进展才算卡死，
 * 且任何进度（位置前进 / 回到 READY）都要清零计时。
 */
object StalledPlayback {

    /** 与 `androidx.media3.common.Player.STATE_*` 对齐的本地副本（JVM 单测跑不到 Android stub）。 */
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    /**
     * 连续卡住多久判卡死。
     *
     * 30 秒：直播流正常重缓冲通常 1~3 秒，弱网也就 10 秒级；
     * 连续 30 秒没有任何进展，基本可以确定这条线已经不可用。
     * 与首帧看门狗同量级，但两者作用在不同阶段（见对象说明）。
     */
    const val STALL_THRESHOLD_MS: Long = 30_000L

    enum class Verdict {
        /** 正常（或在正常重缓冲） */
        PLAYING,

        /** 已就绪过、且连续卡住超过阈值：该换线了 */
        STALLED,

        /** 不归这条判据管（还没就绪过，交给首帧看门狗） */
        NOT_APPLICABLE,
    }

    /**
     * 单次采样判定。
     *
     * @param reachedReady 本次播放是否曾经进入过 READY
     * @param playbackState `Player.STATE_*`
     * @param bufferingForMs 当前这一段连续缓冲已经持续多久
     */
    fun evaluate(reachedReady: Boolean, playbackState: Int, bufferingForMs: Long): Verdict = when {
        !reachedReady -> Verdict.NOT_APPLICABLE
        playbackState == STATE_BUFFERING && bufferingForMs >= STALL_THRESHOLD_MS -> Verdict.STALLED
        else -> Verdict.PLAYING
    }

    /**
     * 带状态的跟踪器：负责"从什么时候开始卡"以及"同一段卡死只报一次"。
     *
     * 用类而不是纯函数，是因为真实调用点每 ~1 秒采样一次，
     * 需要跨采样记住状态。把这段状态机抽出来才能被 JVM 单测覆盖
     * （`PlayerScreen` 是 Compose，纯单测跑不到）。
     *
     * @param thresholdMs 卡死阈值，默认 [STALL_THRESHOLD_MS]（测试可注入小值）
     */
    class Tracker(private val thresholdMs: Long = STALL_THRESHOLD_MS) {

        /** 当前这一段连续缓冲的起点；null = 现在没在缓冲 */
        private var bufferingSinceMs: Long? = null

        /** 当前这段卡死是否已经上报过（防止连续换线） */
        private var reportedThisStall = false

        /** 上一次观察到的播放位置，用于识别"位置在前进" */
        private var lastPositionMs: Long = -1

        /**
         * 采样一次。
         *
         * @return true = 本段卡死**第一次**达到阈值，调用方应触发一次故障切换
         */
        fun onTick(reachedReady: Boolean, playbackState: Int, nowMs: Long): Boolean {
            if (!reachedReady || playbackState != STATE_BUFFERING) {
                // 离开缓冲态：清零，下次卡住重新计时
                bufferingSinceMs = null
                reportedThisStall = false
                return false
            }
            val since = bufferingSinceMs
            if (since == null) {
                bufferingSinceMs = nowMs
                reportedThisStall = false
                return false
            }
            if (nowMs - since < thresholdMs) return false
            if (reportedThisStall) return false
            reportedThisStall = true
            return true
        }

        /**
         * 播放位置前进：说明其实在播（有些播放器追帧时会短暂停在 BUFFERING）。
         *
         * 位置没变（或拿不到位置）时**不清零** —— 否则一个"位置恒为 0"的
         * 死流会永远不被判卡死。
         */
        fun onProgress(positionMs: Long, nowMs: Long) {
            // ⚠️ 首次观察（lastPositionMs < 0）**也算有进展**：
            // 拿到第一个非零位置本身就说明播放器在动。
            // 只有"连续两次位置完全相同"才是真的没进展。
            val advanced = lastPositionMs < 0 || positionMs > lastPositionMs
            lastPositionMs = positionMs
            if (advanced) {
                // 有进展：这一段不算卡死，重新计时
                bufferingSinceMs = nowMs
                reportedThisStall = false
            }
        }

        /** 换线/换场时重置，避免上一条线的计时污染下一条。 */
        fun reset() {
            bufferingSinceMs = null
            reportedThisStall = false
            lastPositionMs = -1
        }
    }
}