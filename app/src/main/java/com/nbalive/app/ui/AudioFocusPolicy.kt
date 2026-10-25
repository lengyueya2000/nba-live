package com.nbalive.app.ui

/**
 * 音频焦点丢失时要不要暂停播放。
 *
 * 实测（Android 14 / API 34，v1.2.9）：小窗看直播时打开抖音，
 * 直播会暂停 —— ExoPlayer 建的时候传了
 * `setAudioAttributes(..., handleAudioFocus = true)`，
 * 任何 App 抢音频焦点都会让它自动停。
 *
 * 用户明确要求：**小窗刷抖音时不要暂停播放**。
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
 * 最直观的想法是 `TelephonyManager.getCallState()` /
 * `TelecomManager.isInCall()`，但这两个从 API 29 起都需要
 * `READ_PHONE_STATE` 权限。本项目 manifest 只有 INTERNET ——
 * 为了判断来电加一个敏感权限不值得，用户也会在安装时看到
 * "电话"权限而感到不安。
 *
 * `AudioManager.getMode()` 是**公开且免权限**的，而且它正是
 * 系统自己的"现在音频路由在干什么"状态，比电话 API 更贴近
 * "该不该让路"这个问题的本质：
 *  - 通话/响铃 -> MODE_IN_CALL / MODE_RINGTONE / MODE_IN_COMMUNICATION
 *  - 普通媒体播放 -> MODE_NORMAL
 */
object AudioFocusPolicy {

    /**
     * 音频模式是否表示"正在通话相关的事"。
     *
     * ⚠️ 免权限：只用 [android.media.AudioManager.getMode]，
     * 不碰 TelephonyManager / TelecomManager（那些要 READ_PHONE_STATE）。
     */
    fun isCallAudioMode(mode: Int): Boolean = when (mode) {
        android.media.AudioManager.MODE_IN_CALL,
        android.media.AudioManager.MODE_RINGTONE,
        android.media.AudioManager.MODE_IN_COMMUNICATION,
        // 来电筛查（Android 10+）：系统在替你筛来电，同样该让路
        android.media.AudioManager.MODE_CALL_SCREENING,
        -> true
        else -> false
    }

    /**
     * @param focusLossType [android.media.AudioManager.AUDIOFOCUS_LOSS_*]
     * @param inCall 当前是否处于通话相关音频模式。
     *   由调用方用 `AudioManager.getMode()` + [isCallAudioMode] 现查，
     *   **不要缓存** —— 焦点变化和来电几乎同时发生，缓存值可能是上一轮的。
     */
    fun shouldPauseOnFocusLoss(focusLossType: Int, inCall: Boolean): Boolean = when (focusLossType) {
        // 永久丢失：系统不会再还焦点，不停就一直在后台响
        android.media.AudioManager.AUDIOFOCUS_LOSS -> true

        // 短暂丢失：只在**确实在通话**时让路。
        // 没在通话就是媒体类（抖音 / B 站 / 别的播放器）抢焦点，不停。
        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> inCall

        // 可延迟丢失（通知音之类）：不需要停
        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> false

        else -> false
    }
}
