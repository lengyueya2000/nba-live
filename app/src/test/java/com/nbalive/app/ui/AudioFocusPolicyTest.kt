package com.nbalive.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「焦点丢了要不要暂停」的判定 —— 靠**音频模式**判断来电，不猜抢焦点的是谁。
 *
 * ## 为什么不能靠焦点信息区分来电和抖音
 *
 * Android 的音频焦点协议是**单向**的：
 *  - `OnAudioFocusChangeListener` 只给变化类型
 *    （LOSS / LOSS_TRANSIENT / GAIN），不说是谁抢的
 *  - `AudioFocusRequest` 也只能拿到**自己请求时**的属性
 *
 * 协议里没有任何字段告诉应用"抢焦点的一方是谁、用它干什么"。
 * 早期实现的漏洞正是这个：`usage` 硬编码成 UNKNOWN，
 * 来电和抖音判不出区别，结果**来电时直播声音不停**。
 *
 * ## 为什么用音频模式而不是电话状态
 *
 * 最直观的想法是 `TelephonyManager.getCallState()` / `TelecomManager.isInCall()`，
 * 但这两个从 API 29 起都需要 `READ_PHONE_STATE` 权限，
 * 而本项目 manifest 只有 INTERNET —— 为了判断来电加一个敏感权限不值得，
 * 用户也会在安装时看到"电话"权限而感到不安。
 *
 * `AudioManager.getMode()` 是**公开且免权限**的：
 *  - 通话/响铃时系统把它切成 MODE_IN_CALL / MODE_RINGTONE / MODE_IN_COMMUNICATION
 *  - 普通媒体播放时是 MODE_NORMAL
 *
 * 它正是系统自己的"现在音频路由是什么"状态，比电话 API 更贴近
 * "该不该让路"这个问题的本质。
 */
class AudioFocusPolicyTest {

    // ---------- 焦点变化类型本身的规则 ----------

    @Test
    fun `永久丢失焦点一定要暂停`() {
        // AUDIOFOCUS_LOSS：系统不会再还回来。不停就一直在后台响
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                inCall = false,
            ),
        )
    }

    @Test
    fun `响铃时要暂停`() {
        assertTrue(
            "来电铃响时必须让路，否则球赛声音盖住铃声",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = true,
            ),
        )
    }

    @Test
    fun `通话中要暂停`() {
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = true,
            ),
        )
    }

    @Test
    fun `刷抖音不要暂停`() {
        // 抖音 / B 站 / 别的播放器：音频模式仍是 NORMAL，焦点被抢走也不停
        assertFalse(
            "用户要求小窗刷抖音时直播继续播",
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = false,
            ),
        )
    }

    @Test
    fun `同样是短暂丢焦点来电停抖音不停`() {
        // 这条是整个改动的核心：两者在焦点协议里完全一样，
        // 差异全部来自音频模式
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = true,
            ),
        )
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                inCall = false,
            ),
        )
    }

    @Test
    fun `可延迟丢失不影响播放`() {
        // 通知音之类：降噪即可，不需要停
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                inCall = false,
            ),
        )
        assertFalse(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                inCall = true,
            ),
        )
    }

    // ---------- 音频模式 -> inCall 的映射 ----------

    @Test
    fun `各种音频模式的来电判定`() {
        // 这些模式都表示"系统正在用音频路由做通话相关的事"
        assertTrue(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_IN_CALL))
        assertTrue(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_RINGTONE))
        assertTrue(
            AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_IN_COMMUNICATION)
        )
        assertTrue(
            AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_CALL_SCREENING)
        )

        // 这些不是：普通播放 / 无效值 / 通话转接
        assertFalse(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_NORMAL))
        assertFalse(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_INVALID))
        assertFalse(AudioFocusPolicy.isCallAudioMode(android.media.AudioManager.MODE_CURRENT))
    }

    @Test
    fun `通话中永久丢焦点也要暂停`() {
        assertTrue(
            AudioFocusPolicy.shouldPauseOnFocusLoss(
                focusLossType = android.media.AudioManager.AUDIOFOCUS_LOSS,
                inCall = true,
            ),
        )
    }
}
