package com.nbalive.app.data.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 比赛状态的**纯时钟判定**：状态随时间老化，不需要网络。
 *
 * 为什么要把这套逻辑单独提出来（review/02-data.md P1-1 / P1-4 / P2-2）：
 *
 * 1. **状态原来是一次性快照**。它只在两个时刻算一次——解析列表时、列表加载完成后
 *    校正一次——算完就写进 `Match.status`，之后没有任何机制让它老化。
 *    用户早上打开 App、放一下午再切回来，列表不会重新拉取（这是刻意的取舍，
 *    自动轮询会打爆源站），于是 13:30 就该结束的比赛到晚上仍显示「直播中」。
 *    修法不是加网络轮询，而是把判定提成**可注入 now 的纯函数**，
 *    让 ViewModel 在列表可见时纯内存重算（119 场在微秒级）。
 *
 * 2. **`now` 必须真的能注入**。原 `statusByClock` 内部自己取 `ZonedDateTime.now()`，
 *    于是 `correctLiveStatuses(now = 固定时刻)` 对 JRKAN 场次完全无效 ——
 *    同一份测试上午跑和晚上跑结果不同，跨零点分支根本钉不住。
 *
 * 3. **源站时间按北京时间解释**。源站给的 "10-25 07:30" / "19:35" 都是北京时间，
 *    用 `ZoneId.systemDefault()` 解释时，设备时区设成 UTC-5 会让状态整体偏移 13 小时，
 *    「直播中」与排序全错。展示侧仍然用设备时区，只有**解析与判定**固定东八区。
 */
object MatchStatusClock {

    /** 源站时间的解释时区：源站是中国站点，时间串一律北京时间。 */
    val SOURCE_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 一场篮球赛的合理时长上限：开赛 3.5 小时后判已结束。 */
    private const val LIVE_WINDOW_HOURS = 3L
    private const val LIVE_WINDOW_MINUTES = 30L

    /** 距开赛还有这么久就算「未开赛」（源站会提前把未开赛标成直播中）。 */
    private const val UPCOMING_MARGIN_MINUTES = 15L

    /** 时间文案里出现 HH:mm 才说明 startAtMillis 是真实开赛时刻，否则只是日期占位。 */
    private val HHMM = Regex("""\d{1,2}:\d{2}""")

    /**
     * 计划开赛时间 -> 状态：超 3.5 小时=已结束，早于 15 分钟=未开赛，窗口内=进行中。
     *
     * @param now 判定基准时刻，**必须由调用方注入**（见类注释第 2 点）
     */
    fun byClock(startAtMillis: Long, now: ZonedDateTime): MatchStatus {
        val start = Instant.ofEpochMilli(startAtMillis).atZone(SOURCE_ZONE)
        return when {
            now.isAfter(start.plusHours(LIVE_WINDOW_HOURS).plusMinutes(LIVE_WINDOW_MINUTES)) ->
                MatchStatus.FINISHED
            now.isBefore(start.minusMinutes(UPCOMING_MARGIN_MINUTES)) -> MatchStatus.UPCOMING
            else -> MatchStatus.LIVE
        }
    }

    /**
     * 该时间戳所在的日期是否**整体已经过去**。
     *
     * 用于 JRS「直播中」行的兜底判断：这类行的 `list-info-time` 文本就是"直播中"，
     * 真实 HH:mm 被顶掉了，解析出来的 startAtMillis 只是**分组日期的 00:00 占位**。
     * 直接套 [byClock] 会在 03:30 就把 19:35 开球的比赛判成已结束 —— 误杀正在直播的比赛。
     * 按整天判断虽然粗，但方向是安全的（宁可多标一会儿直播中，也不误杀）。
     */
    fun isPastDayEnd(startAtMillis: Long, now: ZonedDateTime): Boolean {
        val day = Instant.ofEpochMilli(startAtMillis).atZone(SOURCE_ZONE).toLocalDate()
        val dayEnd = day.plusDays(1).atStartOfDay(SOURCE_ZONE)
        return now.isAfter(dayEnd)
    }

    /**
     * 这条记录的 startAtMillis 是不是"只有日期、没有时刻"的占位值。
     *
     * 判据用时间**文案**而不是时间戳：解析器在文案里抠不到 HH:mm 时会退回当天 00:00
     * （`JrsParser.parseDateTime` 的兜底），所以"文案里没有 HH:mm"与"时间戳是占位"
     * 是同一件事。反过来，真实 00:00 开赛的比赛文案里带着 "00:00"，不会被误判。
     */
    fun isDayPlaceholder(match: Match): Boolean = !HHMM.containsMatchIn(match.timeLabel)

    /**
     * 让一场比赛的状态随当前时刻老化。
     *
     * ⚠️ 两条硬约束：
     *  1. **已结束绝不回正**。FINISHED 可能来自详情页的真实开赛时间（比列表的
     *     00:00 占位准得多），无条件按时钟重算是把一场真结束的比赛改回「直播中」，
     *     比不老化更糟。
     *  2. **状态没变就返回原对象**。ViewModel 靠 `equals` 决定要不要 `update`，
     *     每次都 `copy()` 会让整表引用变化，白白触发全列表重组。
     */
    fun aged(match: Match, now: ZonedDateTime): Match {
        if (match.status == MatchStatus.FINISHED) return match
        val start = match.startAtMillis ?: return match
        val next = if (isDayPlaceholder(match)) {
            if (isPastDayEnd(start, now)) MatchStatus.FINISHED else match.status
        } else {
            byClock(start, now)
        }
        return if (next == match.status) match else match.copy(status = next)
    }

    /** 整表老化：顺序、场次数、未变化的元素对象都保持不变。 */
    fun aged(matches: List<Match>, now: ZonedDateTime): List<Match> {
        if (matches.isEmpty()) return matches
        var changed = false
        val out = matches.map { m ->
            val a = aged(m, now)
            if (a !== m) changed = true
            a
        }
        // 一场都没变就返回**原列表**：避免 StateFlow 因为新 list 实例而重发
        return if (changed) out else matches
    }
}
