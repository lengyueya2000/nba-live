package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「焦点丢了要不要暂停」的判定 —— 一律不停，只在来电时停。
 *
 * 实测回归（Android 14 / API 34，v1.2.10 之后）：
 * 小窗看直播时打开抖音，直播**仍然会暂停**。
 *
 * 根因：抖音用的是 `AUDIOFOCUS_GAIN`（**永久独占**）抢焦点，
 * 我们收到的是 `AUDIOFOCUS_LOSS`。而旧策略里
 * 「AUDIOFOCUS_LOSS -> 一律暂停」，理由是"系统不会再还回来，
 * 不停就会在后台一直响"。
 *
 * 这个理由**不成立**：抖音划走时会 `abandonAudioFocus`，
 * 系统会把焦点还回来。AUDIOFOCUS_LOSS 只表示"我被抢了"，
 * 不表示"永远丢了"。
 *
 * 用户明确要求：小窗刷抖音时直播继续播。所以现在**一律不停**，
 * 只有来电（音频模式是 MODE_IN_CALL / MODE_RINGTONE 等）才停。
 */
class AudioFocusPolicyTest {

    @Test
    fun `抖音永久抢走焦点也不要暂停`() {
        // 用户要求：小窗刷抖音时直播继续播
        assertFalse(
            "AUDIOFOCUS_LOSS 只表示被抢，不表示永远丢了 —— 抖音划走会还回来",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                inCall = false,
            ),
        )
    }

    @Test
    fun `别的播放器永久抢走焦点也不要暂停`() {
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                inCall = false,
            ),
        )
    }

    @Test
    fun `短暂丢焦点也不要暂停`() {
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = false,
            ),
        )
    }

    @Test
    fun `来电一定要暂停`() {
        // 通话音频模式：不管焦点是哪种丢失方式都必须让路
        assertTrue(
            "来电时必须停，否则球赛声音盖住通话",
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
    fun `判定只看来电不看焦点类型`() {
        // 焦点丢失方式完全不影响结果，只看 inCall。
        // CAN_DUCK 不测 inCall=true：来电时系统给的是 LOSS_TRANSIENT，
        // CAN_DUCK + 来电这个组合实际不会发生。
        val typesWhenInCall = listOf(
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            // 未知类型也要停：宁可漏停，也不能来电时不停
            9999,
        )
        typesWhenInCall.forEach { type ->
            assertTrue(
                "inCall=true 必须停（type=$type）",
                AudioFocusPolicy.shouldPauseOnFocusLoss(type, inCall = true),
            )
        }

        listOf(
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            9999,
        ).forEach { type ->
            assertFalse(
                "inCall=false 一律不停（type=$type）",
                AudioFocusPolicy.shouldPauseOnFocusLoss(type, inCall = false),
            )
        }
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
