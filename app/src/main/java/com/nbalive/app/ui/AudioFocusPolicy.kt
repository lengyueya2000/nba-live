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
 * 但不能一刀切关掉焦点处理：来电时直播声音一直响更糟。
 * 所以按丢失原因区分：
 *  - 电话 / 语音助手 -> 暂停（这是必须让路的）
 *  - 媒体类（抖音、B 站、别的播放器）-> 不停
 *  - 永久丢失（AUDIOFOCUS_LOSS）-> 暂停，系统不会还回来，
 *    不停就会在后台一直响
 *  - 短暂丢失（AUDIOFOCUS_LOSS_TRANSIENT）非语音类 -> 不停，
 *    而且这种情况下通常应该**降低音量**而不是停
 */
object AudioFocusPolicy {

    /** 抢焦点的 App 属于哪一类 */
    enum class Usage {
        /** 通话、语音助手 */
        PHONE,
        ASSISTANT,
        /** 抖音、B 站、音乐播放器等 */
        MEDIA,
        /** 导航播报 */
        NAVIGATION,
        /** 判断不出来 */
        UNKNOWN,
    }

    /**
     * @param focusLossType [android.media.AudioManager.AUDIOFOCUS_LOSS_*]
     * @param requesterUsage 抢焦点的一方属于哪类
     */
    fun shouldPauseOnFocusLoss(focusLossType: Int, requesterUsage: Usage): Boolean = when {
        // 永久丢失：系统不会再还焦点，不停就一直在后台响
        focusLossType == android.media.AudioManager.AUDIOFOCUS_LOSS -> true

        // 短暂丢失：只有语音类（来电 / 语音助手）需要让路
        focusLossType == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            requesterUsage == Usage.PHONE || requesterUsage == Usage.ASSISTANT

        // 可延迟丢失（通知音之类）：不影响播放
        focusLossType == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> false

        else -> false
    }
}
