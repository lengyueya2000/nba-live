package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「音频焦点丢了要不要暂停播放」的判定。
 *
 * 实测（Android 14 / API 34，v1.2.9）：小窗看直播时打开抖音，
 * 直播**确实会暂停** —— ExoPlayer 建的时候传了
 * `setAudioAttributes(..., handleAudioFocus = true)`，
 * 任何 App 抢音频焦点都会让它自动停。
 *
 * 用户明确要求：小窗刷抖音时不要暂停播放。
 *
 * 但不能一刀切地关掉焦点处理：来电时直播声音一直响更糟。
 * 所以按丢失原因区分 —— 电话/语音类才停，媒体类（抖音、B 站、
 * 别的播放器）不停。
 *
 * ⚠️ 媒体类抢焦点时**不主动暂停**，但也不保证声音不重叠：
 * 那是系统混音策略的事，App 只能决定自己要不要停。
 */
class AudioFocusPolicyTest {

    @Test
    fun `来电要暂停`() {
        // AUDIOFOCUS_LOSS_TRANSIENT 且请求方是电话/语音助手
        assertTrue(
            "来电时必须停，否则球赛声音盖住通话",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                requesterUsage = AudioFocusPolicy.Usage.PHONE,
            ),
        )
    }

    @Test
    fun `语音助手要暂停`() {
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                requesterUsage = AudioFocusPolicy.Usage.ASSISTANT,
            ),
        )
    }

    @Test
    fun `刷抖音不要暂停`() {
        // 用户在直播小窗里刷抖音：焦点被抖音抢走也不停播
        assertFalse(
            "用户要求小窗刷抖音时直播继续播",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                requesterUsage = AudioFocusPolicy.Usage.MEDIA,
            ),
        )
    }

    @Test
    fun `别的播放器永久抢走焦点要暂停`() {
        // AUDIOFOCUS_LOSS（不是 TRANSIENT）意味着系统**不会再还回来**。
        // 不停的话直播会在后台一直出声，用户以为 App 忘了关。
        // 这和"刷抖音短暂抢焦点"是两回事：抖音是 TRANSIENT，
        // 系统会在合适时机还回来，那时才不该停。
        assertTrue(
            "永久丢失焦点必须停，否则直播在后台一直出声",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                requesterUsage = AudioFocusPolicy.Usage.MEDIA,
            ),
        )
    }

    @Test
    fun `别的播放器短暂抢走焦点不要暂停`() {
        // 抖音/短视频划走时的短暂抢焦点：系统会还回来，停了反而要手动恢复
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                requesterUsage = AudioFocusPolicy.Usage.MEDIA,
            ),
        )
    }

    @Test
    fun `导航播报不要暂停`() {
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                requesterUsage = AudioFocusPolicy.Usage.NAVIGATION,
            ),
        )
    }

    @Test
    fun `永久丢失焦点要暂停`() {
        // AUDIOFOCUS_LOSS（不是 TRANSIENT）：系统不会再还回来，
        // 不停就会一直在后台响
        assertTrue(
            "永久丢失焦点必须停，否则直播在后台一直出声",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                requesterUsage = AudioFocusPolicy.Usage.UNKNOWN,
            ),
        )
    }
}
