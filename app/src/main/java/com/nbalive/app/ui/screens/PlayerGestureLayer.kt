package com.nbalive.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbalive.app.ui.theme.Radius
import com.nbalive.app.ui.theme.Space
import com.nbalive.app.ui.theme.accentBrush
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放器的亮度 / 音量手势层。
 *
 * 左半屏上下滑调**屏幕亮度**（只改本 Activity 的窗口亮度，不动系统设置，
 * 离开播放页时复位，也不需要 WRITE_SETTINGS 权限）；
 * 右半屏上下滑调**媒体音量**。归属看按下点落在哪半边，一次手势内不切换。
 *
 * 为什么不用 `detectVerticalDragGestures` 再叠 `clickable`：
 * 两个修饰符都在等同一个 down 事件，谁先消费谁赢，单击与拖动会互相吞，
 * 表现为"滑动偶尔失效/单击没反应"。这里用 `awaitEachGesture` 手工判定：
 * 位移超过 touchSlop 才算拖动，否则抬手算单击，两条路径互斥且确定。
 */
@Composable
fun PlayerMediaGestures(
    /** 只在可触摸的视频区启用（网页线路的触摸必须留给源站播放器） */
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** 单击回调（全屏时切换浮层显隐）；null 表示不处理单击 */
    onTap: (() -> Unit)? = null,
    /**
     * 只响应单击、忽略滑动（锁定态 / 非全屏时用）。
     *
     * 两种场景都要求"滑动不接管"：
     *  - **锁定**：亮度/音量手势必须停用（否则"防误触锁"形同虚设），
     *    但单击仍要能唤出解锁按钮 —— 否则用户找不到解锁入口。
     *  - **非全屏**（P2-2）：非全屏时播放页是一列可滚动内容，
     *    视频区占屏幕上部约 1/3，从画面起手向上滑是想滚页面。
     *    接管垂直拖动会被解释成调亮度/音量并弹 OSD，页面一动不动。
     *
     * ⚠️ 生产代码不要自己拼条件，传 `!PlayerLock.gestureConfig(...).dragEnabled`
     * （判定收敛在 PlayerLock，有单测守着）。
     */
    tapOnly: Boolean = false,
    /**
     * 单击是否消费掉（不再透传给 WebView 里的源站播放器）。
     *
     * ## 为什么要这个开关（P1-6）
     *
     * 锁定后我们的浮层全隐藏了，但手势层还在（要能唤出解锁按钮）。
     * 不消费的话，每一次误触的单击都照旧透传给源站播放器：
     * 手掌蹭一下就会点亮源站控制条、甚至双击进源站自己的全屏 ——
     * 用户以为锁住了，实际只锁住了我们这一层。
     *
     * ## ⚠️ 非锁定态必须保持 false
     *
     * v1.2.5 明确要求：网页线路未锁定时，单击要透传给源站播放器
     * （源站用它显隐控制条 / 调亮度音量）。"顺手统一消费"会直接破坏这个行为。
     *
     * 生产代码传 `PlayerLock.gestureConfig(...).consumeTap`。
     */
    consumeTap: Boolean = false,
) {
    if (!enabled) return

    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    var osd by remember { mutableStateOf<MediaOsd?>(null) }

    // 用户是否真的滑动调过亮度。
    //
    // 只有"改过"才需要在离开播放页时复位（P1-4）。没改过就写
    // BRIGHTNESS_OVERRIDE_NONE 也有副作用：会抹掉 Activity 上
    // 别处设置的 screenBrightness。
    var brightnessChanged by remember { mutableStateOf(false) }

    // "松手 800ms 后收起指示器"的计时。
    //
    // ⚠️ 不要用"数值变化就自增 token、LaunchedEffect(token) 里 delay"那套：
    // 拖动时每个 move 事件都会自增（每秒几十次），
    // 等于每帧取消并重建一个延时协程 —— 白白烧 CPU，还可能出现竞态。
    // 正确做法是**防抖**：只在数值真正停下来 800ms 后才收起。
    val osdScope = rememberCoroutineScope()
    var osdHideJob by remember { mutableStateOf<Job?>(null) }
    val scheduleHideOsd = {
        osdHideJob?.cancel()
        osdHideJob = osdScope.launch {
            delay(800)
            osd = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            osdHideJob?.cancel()
            // 离开播放页复位亮度（P1-4）。
            //
            // 只改窗口亮度、从不复位的后果：本 App 是单 Activity，
            // 从播放页返回列表时窗口不重建，用户滑到 2% 看球，
            // 返回列表后整个 App 都是 2%，且列表页没有恢复入口。
            //
            // 只在"用户确实改过"时写（brightnessOnDispose 返回 null 表示不动）。
            PlayerGestureMath.brightnessOnDispose(brightnessChanged)?.let { value ->
                resetBrightness(activity, value)
            }
        }
    }

    // ⚠️ onTap 用 rememberUpdatedState 包一层：
    // pointerInput 的 key 是 (enabled, activity, audio)，不含 onTap。
    // 调用点传的 onTap 是 `if (immersive) {...} else null`，
    // immersive 会随 fullscreen / streamUrl 变化 —— 如果手势块拿着旧值，
    // 进全屏后单击画面会没有反应（旧的 onTap 是 null）。
    // rememberUpdatedState 让手势块每次都读到最新的回调，
    // 不必把 onTap 塞进 pointerInput 的 key（那会导致整个手势块重启、丢状态）。
    val currentOnTap by rememberUpdatedState(onTap)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled, activity, audio, tapOnly, consumeTap) {
                // 滑动距离 = 屏高的 70% 走完全量程：太长滑不到底，太短容易误触
                val fullSwipe = PlayerGestureMath.fullSwipe(size.height.toFloat())
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val leftSide = PlayerGestureMath.sideOf(down.position.x, size.width.toFloat())
                    val side = if (leftSide) Side.BRIGHTNESS else Side.VOLUME
                    var dragging = false
                    var startValue = 0f
                    var moved = 0f
                    // 上层按钮（画中画/全屏/底部控制栏）消费掉这次手势后，
                    // 我们绝不能再把抬手当成"单击画面"——否则点按钮会顺手把浮层关掉
                    var takenByOthers = false

                    // 锁定态：从 down 起就吃掉（P1-6）。
                    //
                    // 不消费的话，锁定后每一次误触的单击都照旧透传给
                    // WebView 里的源站播放器：手掌蹭一下就点亮源站控制条、
                    // 甚至双击进源站自己的全屏 —— 用户以为锁住了，
                    // 实际只锁住了我们这一层 UI。
                    //
                    // ⚠️ 非锁定态绝不能消费（consumeTap=false）：
                    // v1.2.5 明确要求网页线路单击透传给源站播放器。
                    if (consumeTap) down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // ⚠️ 顺序很重要：先判"有没有被别人消费"，再轮到自己消费。
                        // isConsumed 是按**单个事件**计的，我们消费上一个事件
                        // 不会污染这一条的判断。
                        if (change.isConsumed) {
                            takenByOthers = true
                            break
                        }
                        if (!change.pressed) {
                            // 抬手：锁定态连 up 也要吃掉，否则源站仍能凑出一次完整点击
                            if (consumeTap) change.consume()
                            break
                        }
                        moved += change.positionChange().y
                        if (!dragging) {
                            if (abs(moved) > viewConfiguration.touchSlop) {
                                // tapOnly（锁定 / 非全屏）：滑动不接管、
                                // 不改亮度音量，也不算"单击"（用户确实滑了）。
                                // 直接结束本次手势，什么都不做。
                                //
                                // 非全屏（P2-2）这里**不 consume**，让未被接管的
                                // 拖动自然落到父级 verticalScroll —— 否则从视频区
                                // 起手上滑滚不动页面。
                                if (tapOnly) {
                                    if (consumeTap) change.consume()
                                    takenByOthers = true
                                    break
                                }
                                dragging = true
                                startValue = when (side) {
                                    Side.BRIGHTNESS -> readBrightness(activity)
                                    Side.VOLUME -> readVolume(audio)
                                }
                            }
                        }
                        if (dragging) {
                            change.consume()
                            val target = PlayerGestureMath.valueFromDrag(
                                startValue = startValue,
                                movedPx = moved,
                                fullSwipe = fullSwipe,
                                min = if (side == Side.BRIGHTNESS) MIN_BRIGHTNESS else 0f,
                            )
                            osd = applyGesture(side, target, activity, audio)
                            // 记下"用户确实改过亮度"：离开播放页时只有改过才复位
                            // （P1-4，见 PlayerGestureMath.brightnessOnDispose）
                            if (side == Side.BRIGHTNESS) brightnessChanged = true
                            // 拖动中：每次数值变化都重置收起计时（防抖）
                            scheduleHideOsd()
                        } else if (consumeTap) {
                            // 锁定态下未成拖动的移动事件也不许透传
                            change.consume()
                        }
                    }

                    if (dragging) {
                        // 抬手：重置计时，让指示器再留 800ms
                        scheduleHideOsd()
                    } else if (!takenByOthers) {
                        // 全程没超过 touchSlop 且没被别人消费 = 单击（浮层显隐）
                        currentOnTap?.invoke()
                    }
                }
            },
    ) {
        osd?.let { current ->
            MediaOsdView(
                osd = current,
                modifier = Modifier
                    .align(
                        if (current.side == Side.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd,
                    )
                    .padding(horizontal = Space.xxl),
            )
        }
    }
}

/** 当前显示中的手势指示器 */
private data class MediaOsd(val side: Side, val value: Float)

private enum class Side { BRIGHTNESS, VOLUME }

/** 亮度不能归零：全黑会让用户以为 App 崩了/花屏，留一丝亮度兜底 */
private const val MIN_BRIGHTNESS = 0.02f

private fun applyGesture(
    side: Side,
    rawValue: Float,
    activity: Activity?,
    audio: AudioManager?,
): MediaOsd = when (side) {
    Side.BRIGHTNESS -> {
        val v = rawValue.coerceIn(MIN_BRIGHTNESS, 1f)
        writeBrightness(activity, v)
        MediaOsd(Side.BRIGHTNESS, v)
    }
    Side.VOLUME -> {
        val v = rawValue.coerceIn(0f, 1f)
        writeVolume(audio, v)
        MediaOsd(Side.VOLUME, v)
    }
}

/**
 * 手势换算的纯函数，单独抽出来是为了能写单元测试。
 *
 * 这些公式错了后果都很"静默"：方向反了（下滑变亮、上滑变暗），
 * 满量程不对（滑到底只有 30%，怎么调都调不到最大）。
 * 上真机才发现就太晚了，所以在这里锁死并用单测守住。
 */
internal object PlayerGestureMath {

    /** 走完整个量程需要的滑动距离 = 屏高的这个比例 */
    const val FULL_SWIPE_RATIO = 0.7f

    /** 一次滑动的满量程距离；尺寸非法时给 1f，避免除零 */
    fun fullSwipe(heightPx: Float): Float =
        (heightPx * FULL_SWIPE_RATIO).coerceAtLeast(1f)

    /**
     * 从 [startValue] 出发、累计位移 [movedPx] 后的目标值。
     *
     * 播放器的手感惯例是"上滑变大、下滑变小"，而屏幕坐标 y 向下增长，
     * 所以位移要**取反**：[movedPx] 为正（下滑）时结果变小。
     */
    fun valueFromDrag(
        startValue: Float,
        movedPx: Float,
        fullSwipe: Float,
        min: Float,
        max: Float = 1f,
    ): Float {
        val safeSwipe = if (fullSwipe <= 0f) 1f else fullSwipe
        return (startValue - movedPx / safeSwipe).coerceIn(min, max)
    }

    /** 音量百分比 → 系统音量档位（四舍五入并夹到 [0, maxStep]） */
    fun volumeStep(value: Float, maxStep: Int): Int {
        val steps = maxStep.coerceAtLeast(1)
        return (value.coerceIn(0f, 1f) * steps).roundToInt().coerceIn(0, steps)
    }

    /** 0..1 → 0..100 整数百分比 */
    fun percent(value: Float): Int =
        (value.coerceIn(0f, 1f) * 100).roundToInt().coerceIn(0, 100)

    /** 按下点落在左半屏还是右半边 */
    fun sideOf(x: Float, widthPx: Float): Boolean = x < widthPx / 2f

    /**
     * 离开播放页时该写回的窗口亮度；null 表示"不要碰窗口属性"。
     *
     * ## 为什么必须复位（P1-4）
     *
     * 手势只改**本 Activity 的窗口亮度**（`window.attributes.screenBrightness`），
     * 注释里"退出 App 自动回落到系统亮度"其实只在**退出 Activity** 时成立。
     * 本 App 是单 Activity（`AndroidManifest.xml` 里只有 MainActivity，
     * `launchMode="singleTask"`），从播放页返回列表/详情时窗口不重建，
     * `screenBrightness` 保持被改过的值 —— 用户把亮度滑到 2% 看球，
     * 返回列表后**整个 App 都是 2%**，列表页还没有任何恢复入口。
     *
     * ## 为什么只在"用户确实改过"时复位
     *
     * `BRIGHTNESS_OVERRIDE_NONE`（-1f）表示跟随系统。没动过亮度就写 -1f
     * 也有副作用：会把 Activity 上别处设置的 `screenBrightness` 抹掉。
     * 所以没改过就返回 null，调用方跳过写窗口。
     *
     * @param userChanged 本次播放页期间用户是否真的滑动调过亮度
     * @return 要写入 `attrs.screenBrightness` 的值；null = 不要写
     */
    fun brightnessOnDispose(userChanged: Boolean): Float? =
        if (userChanged) BRIGHTNESS_OVERRIDE_NONE else null

    /**
     * `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE` 的本地副本。
     *
     * 不直接引用常量是为了让这个纯函数在 JVM 单测里也能跑
     * （`PlayerGestureMathTest` 跑在 JVM 上，没有 android.jar 的运行时实现）。
     * 值必须与系统常量一致：-1f。
     */
    const val BRIGHTNESS_OVERRIDE_NONE = -1f
}

// ---------- 亮度 ----------

/**
 * 读当前亮度（0..1）。
 *
 * `window.attributes.screenBrightness` 为 -1 表示"跟随系统"，这时要从
 * Settings.System 取系统亮度换算；否则第一次滑动会从 0 起跳，画面先黑一下。
 */
private fun readBrightness(activity: Activity?): Float {
    val win = activity?.window ?: return 0.5f
    val current = win.attributes.screenBrightness
    if (current >= 0f) return current.coerceIn(MIN_BRIGHTNESS, 1f)
    val sys = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (sys / 255f).coerceIn(MIN_BRIGHTNESS, 1f)
}

/** 只改窗口亮度：不写系统设置，离开播放页时由 [resetBrightness] 复位 */
private fun writeBrightness(activity: Activity?, value: Float) {
    val win = activity?.window ?: return
    val attrs = win.attributes
    attrs.screenBrightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
    win.attributes = attrs
}

/**
 * 把窗口亮度写回 [PlayerGestureMath.BRIGHTNESS_OVERRIDE_NONE]（-1f，跟随系统）。
 *
 * P1-4：单 Activity 架构下，从播放页返回列表时窗口不重建，
 * 不复位就会让整个 App 停在被调暗的亮度上。
 */
private fun resetBrightness(activity: Activity?, value: Float) {
    val win = activity?.window ?: return
    val attrs = win.attributes
    attrs.screenBrightness = value
    win.attributes = attrs
}

// ---------- 音量 ----------

private fun readVolume(audio: AudioManager?): Float {
    if (audio == null) return 0.5f
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
}

private fun writeVolume(audio: AudioManager?, value: Float) {
    if (audio == null) return
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val target = PlayerGestureMath.volumeStep(value, max)
    // flag 传 0：不弹系统音量条，改用我们自己的指示器
    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
}

/** 手势指示器：半透明深色药丸 + 图标 + 竖向进度 + 百分比 */
@Composable
private fun MediaOsdView(osd: MediaOsd, modifier: Modifier = Modifier) {
    val percent = PlayerGestureMath.percent(osd.value)
    val icon = when (osd.side) {
        Side.BRIGHTNESS ->
            if (percent >= 50) Icons.Filled.BrightnessHigh else Icons.Filled.BrightnessLow
        Side.VOLUME ->
            if (percent == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        // 竖向细进度条：和"上下滑动"的方向一致，比横向条更好读
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.28f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxHeight(percent / 100f)
                    .width(4.dp)
                    .background(accentBrush(), RoundedCornerShape(2.dp)),
            )
        }
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------- 实时时钟 ----------

/**
 * 播放页顶部的实时时钟（"现在几点"）。
 *
 * 每秒醒一次，但只在**分钟变化**时才写回状态：`mutableStateOf` 值相同不会触发
 * 重组，实际一分钟才重组一次，比每秒重组整条浮层便宜得多。
 */
@Composable
fun rememberClockText(): String {
    var text by remember { mutableStateOf(nowClockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val current = nowClockText()
            if (current != text) text = current
        }
    }
    return text
}

private fun nowClockText(): String {
    val now = Calendar.getInstance()
    return String.format(
        Locale.ROOT,
        "%02d:%02d",
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
    )
}

/**
 * 时钟药丸：半透明深底 + 白字，压在视频上也看得清。
 *
 * ⚠️ 原来左边还有个闹钟图标（Icons.Filled.Schedule），用户明确要求去掉：
 * 对着数字看就知道是时间，图标只是多占位置。
 */
@Composable
fun PlayerClock(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.chip))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = Space.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = rememberClockText(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.width(1.dp))
    }
}

/**
 * 画质药丸：显示当前分辨率（如 1080P）。
 *
 * 取值两条路径：
 *  - ExoPlayer 直连：拿真实视频宽高，最准
 *  - WebView 承载：App 看不到画面，只能从嗅探到的 m3u8 地址里抠源站标注的分辨率
 *
 * 解析不到就不渲染这个药丸（不显示空的标签）。
 */
@Composable
fun PlayerQualityPill(
    label: String?,
    modifier: Modifier = Modifier,
) {
    if (label == null) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.chip))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 从 Compose 的 Context 找到宿主 Activity（改窗口亮度要用） */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
