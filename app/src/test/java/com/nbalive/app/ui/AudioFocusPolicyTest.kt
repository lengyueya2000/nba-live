package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「焦点丢了要不要暂停 / 要不要重新申请」的判定。
 *
 * 这里守两条用户反复反馈过的行为：
 *  1) **小窗刷抖音时直播不能停**（v1.2.10 修过一次又回归过）
 *  2) **来电时直播必须停**（声音盖住通话，v1.2.10 / v1.2.11 都修过，
 *     但判据只依赖瞬时 `AudioManager.mode`，响铃瞬间常常还是 MODE_NORMAL）
 *
 * 判据的推导见 [AudioFocusPolicy] 的注释。测试按「焦点丢失类型 × 是否通话模式」
 * 把整张真值表钉死，避免以后有人只改一半。
 */
class AudioFocusPolicyTest {

    // ---------- 暂停判定（P1-3：来电判据不再只看瞬时 mode） ----------

    @Test
    fun `抖音永久抢走焦点也不要暂停`() {
        // 用户要求：小窗刷抖音时直播继续播。
        // 抖音用的是 AUDIOFOCUS_GAIN（永久独占），我们收到的是 AUDIOFOCUS_LOSS。
        // LOSS 只表示"我被抢了"，不表示"永远丢了" —— 抖音划走会 abandon，系统把焦点还回来。
        assertFalse(
            "AUDIOFOCUS_LOSS 不代表永远丢了，抖音场景不能停",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                inCall = false,
            ),
        )
    }

    @Test
    fun `短暂丢失焦点直接暂停`() {
        // 这是 v1.2.11 的回归点：旧判据只看现查的 AudioManager.mode，
        // 而来电时系统先发 AUDIOFOCUS_LOSS_TRANSIENT，音频路由切到
        // MODE_RINGTONE 往往晚几毫秒 —— 现查拿到 MODE_NORMAL，
        // 判定"不是来电"，于是响铃/通话期间直播继续外放。
        //
        // 为什么 TRANSIENT 可以直接停：媒体类 App（抖音/B 站/别的播放器）
        // 实测都用 AUDIOFOCUS_GAIN 抢焦点，TRANSIENT 基本只来自
        // 来电 / 语音助手 / 导航播报 —— 这几类让路都是对的。
        assertTrue(
            "LOSS_TRANSIENT 基本只来自来电/助手/导航，直接暂停才安全",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = false,
            ),
        )
    }

    @Test
    fun `来电时任何丢失方式都要暂停`() {
        // 通话音频模式：不管焦点是哪种丢失方式都必须让路，
        // 否则球赛声音盖住通话（用户连续反馈两次的问题）
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                inCall = true,
            ),
        )
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = true,
            ),
        )
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                inCall = true,
            ),
        )
    }

    @Test
    fun `可延迟丢失不影响播放`() {
        // 通知音之类：CAN_DUCK 表示"我小声点就行，不用停"。
        // 注意不测 inCall=true 的组合：来电时系统给的是 LOSS_TRANSIENT
        // 而不是 CAN_DUCK，那个组合实际不会发生。
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                inCall = false,
            ),
        )
    }

    @Test
    fun `未知的丢失类型保守处理`() {
        // 未知类型：不确定是谁抢的，宁可不停（抖音场景高频），
        // 但通话模式下必须停 —— 来电是唯一不能赌的场景。
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(focusLossType = 9999, inCall = false),
        )
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(focusLossType = 9999, inCall = true),
        )
    }

    // ---------- 焦点申请（P1-2：返回值以前被丢弃） ----------

    @Test
    fun `焦点申请结果要按 GRANTED 判定`() {
        assertTrue(
            "只有 GRANTED 才算真的持有焦点",
            AudioFocusPolicy.isFocusGranted(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED),
        )
        assertFalse(
            "FAILED 说明没拿到，不能当成持有",
            AudioFocusPolicy.isFocusGranted(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED),
        )
        assertFalse(
            "DELAYED 表示还没给，同样不算持有",
            AudioFocusPolicy.isFocusGranted(android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED),
        )
    }

    @Test
    fun `播放中且没有焦点才需要重新申请`() {
        // 被抖音以 GAIN 抢走后我们已不在焦点栈，之后来电收不到 LOSS
        // -> 必须重新申请，把自己放回栈里（时机见 AudioFocusPolicy 的注释：
        //    只在回前台和起播时申请，不要在 LOSS 回调里立刻申请）
        assertTrue(
            "在播、没焦点、不在通话 -> 该重新申请",
            AudioFocusPolicy.shouldRequestFocusAgain(hasFocus = false, inCall = false, playing = true),
        )
        assertFalse(
            "已经持有焦点就不用再申请（重复申请会打扰别人）",
            AudioFocusPolicy.shouldRequestFocusAgain(hasFocus = true, inCall = false, playing = true),
        )
        assertFalse(
            "没在播就别占着焦点",
            AudioFocusPolicy.shouldRequestFocusAgain(hasFocus = false, inCall = false, playing = false),
        )
        assertFalse(
            "通话中不要抢焦点 —— 那会和电话/抖音互相打乒乓",
            AudioFocusPolicy.shouldRequestFocusAgain(hasFocus = false, inCall = true, playing = true),
        )
    }

    @Test
    fun `焦点回来后恢复被焦点打断的播放`() {
        // 来电结束/抖音划走后系统还回焦点：只有"当初是被焦点打断的"
        // 才自动恢复；用户自己按的暂停不能替他继续播。
        assertTrue(
            AudioFocusPolicy.shouldResumeOnFocusGain(pausedByFocus = true, playing = false),
        )
        assertFalse(
            "用户主动暂停的不许自动恢复",
            AudioFocusPolicy.shouldResumeOnFocusGain(pausedByFocus = false, playing = false),
        )
        assertFalse(
            "已经在播就不用再 play 一次",
            AudioFocusPolicy.shouldResumeOnFocusGain(pausedByFocus = true, playing = true),
        )
    }

    // ---------- 轮询兜底（P1-2 的第二信号源） ----------

    @Test
    fun `播放中现查到通话模式就要暂停`() {
        // 这条不依赖焦点栈：即使我们不在焦点栈（被抖音抢走后），
        // 只要轮询到 MODE_RINGTONE/MODE_IN_CALL 就必须停 ——
        // 这是"声音盖住通话"的最后一道保险。
        assertTrue(
            AudioFocusPolicy.shouldPauseForAudioMode(
                playing = true,
                mode = android.media.AudioManager.MODE_RINGTONE,
            ),
        )
        assertTrue(
            AudioFocusPolicy.shouldPauseForAudioMode(
                playing = true,
                mode = android.media.AudioManager.MODE_IN_CALL,
            ),
        )
        assertFalse(
            "普通媒体模式不用停",
            AudioFocusPolicy.shouldPauseForAudioMode(
                playing = true,
                mode = android.media.AudioManager.MODE_NORMAL,
            ),
        )
        assertFalse(
            "没在播就不用管（暂停态轮询到通话模式不该做任何事）",
            AudioFocusPolicy.shouldPauseForAudioMode(
                playing = false,
                mode = android.media.AudioManager.MODE_IN_CALL,
            ),
        )
    }

    // ---------- 音频模式 -> inCall 的映射 ----------

    @Test
    fun `各种音频模式的来电判定`() {
        assertTrue(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_IN_CALL))
        assertTrue(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_RINGTONE))
        assertTrue(
            AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_IN_COMMUNICATION)
        )
        assertTrue(
            AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_CALL_SCREENING)
        )

        // 这些不是：普通播放 / 无效值 / 当前值
        assertFalse(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_NORMAL))
        assertFalse(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_INVALID))
        assertFalse(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_CURRENT))
    }
}
