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
     * @param focusLossType [android.media.AudioManager.AUDIOFOCUS_LOSS_*]。
     *   ⚠️ 这个参数**不再参与暂停判定**，只保留是为了让调用点能把
     *   系统给的原值传进来、也方便以后加回"按类型区分"的策略。
     *   见下面的说明。
     * @param inCall 当前是否处于通话相关音频模式。
     *   由调用方用 `AudioManager.getMode()` + [isCallAudioMode] 现查，
     *   **不要缓存** —— 焦点变化和来电几乎同时发生，缓存值可能是上一轮的。
     */
    fun shouldPauseOnFocusLoss(focusLossType: Int, inCall: Boolean): Boolean = inCall

    /**
     * 为什么 [focusLossType] 不参与判定。
     *
     * 实测回归（Android 14 / API 34，v1.2.10 之后）：小窗看直播时打开抖音，
     * 直播仍然会暂停。
     *
     * 旧策略是「AUDIOFOCUS_LOSS（永久丢失）一律暂停」，
     * 理由是"系统不会再还回来，不停就会在后台一直响"。
     * 抖音正好用的是 `AUDIOFOCUS_GAIN`（永久独占）抢焦点，
     * 于是收到 AUDIOFOCUS_LOSS -> 暂停。
     *
     * 但那个理由**不成立**：抖音划走时会 `abandonAudioFocus`，
     * 系统随即使焦点还回来。`AUDIOFOCUS_LOSS` 只表示"我被抢了"，
     * 不表示"永远丢了"。
     *
     * 用户明确要求「小窗刷抖音时直播继续播」，所以现在
     * **一律不停，只在来电时停**（来电看音频模式，不看焦点类型）。
     */
}
