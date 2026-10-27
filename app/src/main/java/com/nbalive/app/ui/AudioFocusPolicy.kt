package com.nbalive.app.ui

/**
 * 音频焦点策略：焦点丢了要不要暂停、要不要重新申请。
 *
 * 这里要把两个用户反复反馈过的需求同时满足：
 *  1) **小窗刷抖音时直播不能停**（v1.2.10 修过一次，之后又回归过）
 *  2) **来电时直播必须停**（v1.2.11 修过，但判据本身有竞态）
 *
 * ## 为什么不能靠焦点信息区分来电和抖音
 *
 * Android 的音频焦点协议是**单向**的：
 *  - `OnAudioFocusChangeListener` 只给变化类型
 *    （LOSS / LOSS_TRANSIENT / GAIN），不说是谁抢的
 *  - `AudioFocusRequest` 也只能拿到**自己请求时**的属性
 *
 * 协议里没有任何字段告诉应用"抢焦点的一方是谁、用它干什么"。
 *
 * ## 为什么用音频模式而不是电话状态
 *
 * `TelephonyManager.getCallState()` / `TelecomManager.isInCall()` 从 API 29 起
 * 都需要 `READ_PHONE_STATE` 权限。本项目 manifest 只有 INTERNET ——
 * 为了判断来电加一个敏感权限不值得，用户也会在安装时看到"电话"权限而担心。
 *
 * `AudioManager.getMode()` 是**公开且免权限**的，而且它正是系统自己的
 * "现在音频路由在干什么"状态：
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
     * 焦点丢失时是否暂停播放。
     *
     * ## 判据（P1-3）
     *
     *  - `AUDIOFOCUS_LOSS_TRANSIENT` -> **直接暂停**，不看 `inCall`
     *  - 其余类型（含 `AUDIOFOCUS_LOSS`）-> 看 `inCall`
     *
     * ## 为什么 TRANSIENT 可以无脑暂停
     *
     * 旧判据（v1.2.10 的写法）是「一律只看现查的 `AudioManager.mode`」，
     * 想靠"是不是通话模式"来区分来电和抖音。但它有个**竞态**：
     * 来电时系统先派发 `AUDIOFOCUS_LOSS_TRANSIENT`，把音频路由切到
     * `MODE_RINGTONE` 往往晚几毫秒 —— 回调里现查经常还是 `MODE_NORMAL`，
     * 于是判定"不是来电"，响铃/通话期间直播继续外放。这正是
     * v1.2.11 想修却没修干净的那个问题。
     *
     * 焦点类型本身其实足以区分：
     *  - 媒体类 App（抖音 / B 站 / 别的播放器）实测都用 `AUDIOFOCUS_GAIN`
     *    **永久独占**抢焦点，我们收到的是 `AUDIOFOCUS_LOSS`；
     *  - `AUDIOFOCUS_LOSS_TRANSIENT` 基本只来自**来电 / 语音助手 /
     *    导航播报** —— 这几类让路都是对的，用户不会抱怨"导航播报时
     *    球赛静了两秒"。
     *
     * 所以：TRANSIENT 直接停，`LOSS` 才退回 `inCall` 现查。
     * 这样既保住"抖音不停"，又消掉来电那几毫秒的竞态。
     *
     * @param focusLossType [android.media.AudioManager.AUDIOFOCUS_LOSS_*]
     * @param inCall 当前是否处于通话相关音频模式。
     *   由调用方用 `AudioManager.getMode()` + [isCallAudioMode] 现查，
     *   **不要缓存** —— 焦点变化和来电几乎同时发生，缓存值可能是上一轮的。
     */
    fun shouldPauseOnFocusLoss(focusLossType: Int, inCall: Boolean): Boolean = when {
        // 来电是唯一不能赌的场景：只要在通话模式就停
        inCall -> true
        // 瞬时丢失基本只来自来电/助手/导航，直接停（消掉 mode 竞态）
        focusLossType == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
        // AUDIOFOCUS_LOSS 只表示"被抢了"（抖音的 GAIN 抢法），不停。
        // CAN_DUCK 表示"小声点就行"，也不停。
        else -> false
    }

    /**
     * `requestAudioFocus` 的返回值是否代表真的持有焦点。
     *
     * ## 为什么要单独判（P1-2）
     *
     * 旧代码把返回值直接丢掉，`AUDIOFOCUS_REQUEST_FAILED` 和 `GRANTED`
     * 走同一条路。后面所有"收到 LOSS 才暂停"的逻辑都建立在
     * **我们确实持有焦点**这个前提上，前提没保证，整条链就是空的。
     */
    fun isFocusGranted(requestResult: Int): Boolean =
        requestResult == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    /**
     * 要不要重新申请音频焦点。
     *
     * ## 为什么必须重申请（P1-2）
     *
     * AOSP 的 `MediaFocusControl` 在向某个条目派发**永久**
     * `AUDIOFOCUS_LOSS` 之后会把它移出焦点栈（只有 `GAIN_TRANSIENT`
     * 类丢失才保留）。也就是说收到 LOSS 之后我们**已经不在栈里**了：
     *  - 不在栈里就收不到任何后续焦点事件：来电时系统的 `GAIN_TRANSIENT`
     *    只派给当前持有者（抖音），**我们什么都收不到 -> 直播继续外放，
     *    盖住通话**；
     *  - 抖音退出后也不会给我们 `AUDIOFOCUS_GAIN`，
     *    "丢了再还回来"的恢复逻辑永远不会触发。
     *
     * ## 为什么不在 LOSS 回调里立刻申请
     *
     * 抖音刚拿到焦点，我们立刻去抢会形成**焦点乒乓**：两个 App 互相
     * 抢/被抢，抖音那边可能表现为"声音一顿一顿"。所以只在
     * **回前台（ON_START）** 和 **每次起播** 这两个时机补申请，
     * 把"是否持有焦点"变成我们自己的显式状态，而不是靠系统记住我们。
     *
     * @param hasFocus 我们记录的显式焦点状态（由 requestAudioFocus 返回值维护）
     * @param inCall 当前是否通话模式 —— 通话中别去抢，会和电话/抖音打乒乓
     * @param playing 是否在播 —— 没在播就不该占着焦点
     */
    fun shouldRequestFocusAgain(hasFocus: Boolean, inCall: Boolean, playing: Boolean): Boolean =
        !hasFocus && !inCall && playing

    /**
     * 焦点回来时要不要自动恢复播放。
     *
     * 只恢复**被焦点打断的**播放；用户自己按了暂停（`playWhenReady=false`）
     * 不能替他继续播 —— 那是用户意图。
     *
     * @param pausedByFocus 这次暂停是不是焦点丢失造成的
     * @param playing 当前是否已经在播
     */
    fun shouldResumeOnFocusGain(pausedByFocus: Boolean, playing: Boolean): Boolean =
        pausedByFocus && !playing

    /**
     * 轮询兜底：现查到通话模式时是否该暂停。
     *
     * 这是"声音盖住通话"的**最后一道保险**：即使我们不在焦点栈里
     * （被抖音抢走之后），只要还在播、并且 `getMode()` 现查到
     * 响铃/通话，就必须停。调用方（PlayerScreen）在播放中每 1-2 秒
     * 轮询一次即可 —— 只在播放中轮询，暂停态不做任何事。
     *
     * @param playing 当前是否在播
     * @param mode `AudioManager.getMode()` 的现值
     */
    fun shouldPauseForAudioMode(playing: Boolean, mode: Int): Boolean =
        playing && isCallAudioMode(mode)
}
