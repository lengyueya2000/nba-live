package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.MatchStatusClock

/**
 * 两源合并的**纯函数**集合：归一化 key、状态可信度、联赛取优、id 唯一化、supported 过滤。
 *
 * ## 为什么从 `MatchRepository` 抽出来（review/optimization-review.md D10）
 *
 * `MatchRepository` 一个类同时承担抓取/重试、合并去重、时钟校正、详情页 TTL 缓存、
 * 播放页展开、m3u8 候选校验 —— 1180 行里"判据类"逻辑与 IO/协程样板混在一起，
 * 每次改合并规则都要在一堆网络代码里定位（D2 的 `league` 丢数据 bug 就长在这里）。
 *
 * 抽出来之后：
 *  - 本对象的每个函数都**没有 IO、没有协程、没有 Android 依赖**，可直接单测；
 *  - `MatchRepository` 只留入口与装配（详见 [MatchRepository.mergeForTest] 的签名保持不变）。
 *
 * ## 合并方向：`jrkan` 先、`jrs` 后
 *
 * 顺序有意义：后合并的那个只用来**补缺**（见 [mergeOne] 的 `copy(...)`），
 * 而"状态取更可信"与"联赛取更优"是各自独立的判据，
 * 不再由"谁后合并"决定 —— JRS 列表的"直播中"会滞后好几小时。
 */
internal object MatchMerger {

    /** [merge] 的结果：合并后的场次 + 因非 NBA/CBA 被丢弃的数量。 */
    internal data class Merged(val matches: List<Match>, val filteredOutCount: Int)

    /**
     * 合并两个源：按 "日期|主队|客队" 归一化 key 归并，信号列表合并去重。
     *
     * 日期必须参与 key：同一对球队背靠背/系列赛会重复出现，
     * 只按队名归并会把真实存在的第二场直接吞掉。
     */
    fun merge(jrs: List<Match>, jrkan: List<Match>): Merged {
        val merged = LinkedHashMap<String, Match>()
        // 已用过的 id：避免每次都 O(n) 扫 merged.values（几十场还行，上百场会退化）
        val usedIds = HashSet<String>()

        fun mergeOne(m: Match) {
            val key = normalizeKey(m)
            // id 兜底唯一化：LazyVerticalGrid 的 key 重复会直接崩溃，
            // 宁可 id 加后缀，也绝不丢比赛
            var unique = m.id
            var suffix = merged.size
            while (!usedIds.add(unique)) {
                unique = "${m.id}#${suffix++}"
            }
            val existing = merged[key]
            merged[key] = if (existing == null) {
                m.copy(id = unique)
            } else {
                val sources = (existing.sources + m.sources).distinctBy { it.pageUrl }
                // ⚠️ 联赛必须走 betterLeague（review/optimization-review.md H4 / data.md D2）：
                // 旧实现的 copy(...) 里**没有** `league`，于是沿用先合并的 JRKAN。
                // 而 JRKAN 的联赛文案一旦变成非空但认不出的值（"篮球"「夏季联赛」…），
                // 它会判 OTHER，接着把同场 JRS 记录（明明判出 NBA/CBA）一起带进 OTHER，
                // 被下面的 supported 过滤**整场静默丢弃** —— 连 JRS 的线路一起，
                // 用户只看到"场次少了"，filteredOutCount 也没有界面落点。
                val league = betterLeague(existing.league, m.league)
                existing.copy(
                    // 状态取"更可信"的那个，而不是"后合并的那个"：
                    // JRS 的列表会滞后（已结束还标直播中），
                    // JRKAN 自带 MM-dd HH:mm 可以做时钟校正。
                    // 原来直接用 m.status（JRS 后合并）会把 JRKAN 更准的状态覆盖掉。
                    status = betterStatus(existing.status, m.status),
                    // ⚠️ 联赛与其原始文案必须**成对**取用同一个来源：
                    // 只换 league 不换 leagueRawName 会出现"联赛=NBA、原始文案=篮球"的自相矛盾，
                    // 之后任何按 leagueRawName 二次判定的代码都会重新踩坑。
                    league = league,
                    leagueRawName = if (league == m.league) m.leagueRawName else existing.leagueRawName,
                    timeLabel = existing.timeLabel.ifBlank { m.timeLabel },
                    startAtMillis = existing.startAtMillis ?: m.startAtMillis,
                    homeLogo = existing.homeLogo ?: m.homeLogo,
                    awayLogo = existing.awayLogo ?: m.awayLogo,
                    sources = sources,
                    signalCount = sources.size,
                    jrsDetailId = existing.jrsDetailId ?: m.jrsDetailId,
                )
            }
        }

        jrkan.forEach(::mergeOne)
        jrs.forEach(::mergeOne)

        val all = merged.values.toList()
        // 产品只做 NBA + CBA：源站混入的足球/外联赛在这里统一丢弃。
        // ⚠️ 丢弃数量要能被观测（P1-新）：联赛名解析失败会落到 League.OTHER，
        // 整场静默消失 —— 至少让 filteredOutCount 能把"丢了几场"暴露出来。
        val kept = all.filter { it.league in League.supported }
            .distinctBy { it.id }
        return Merged(kept, all.size - kept.size)
    }

    /**
     * 两个源的联赛判定取"更可信/更优"的那个（review/optimization-review.md H4）。
     *
     * 优先级（高到低）：
     *  1. `League.supported`（NBA / CBA）—— 产品范围内，任何非 supported 都不该压过它；
     *  2. 非 `OTHER`（能判出联赛名的，总比"认不出"强）；
     *  3. 其余（两个都 OTHER，或两个都 supported 时）保持**先合并的那个**，
     *     与旧行为一致 —— 平手时不做无谓的改动，避免引入新的不确定语义。
     */
    fun betterLeague(a: League, b: League): League = when {
        a in League.supported && b !in League.supported -> a
        b in League.supported && a !in League.supported -> b
        a != League.OTHER && b == League.OTHER -> a
        b != League.OTHER && a == League.OTHER -> b
        else -> a
    }

    /**
     * 合并两个来源的状态：取更可信的那个。
     *
     * 可信度排序：FINISHED > LIVE > UPCOMING。
     *
     * 为什么要这样而不是"后合并的赢"：
     * - JRS 列表的"直播中"会滞后好几小时（已结束的场次还标着直播中）
     * - JRKAN 自带 "MM-dd HH:mm"，可以拿本地时钟双向校正
     * JRS 后合并，直接用 m.status 会把 JRKAN 更准的状态覆盖成滞后值。
     *
     * 后续 correctLiveStatuses 还会用详情页/时钟再校正一遍，这里只是别把
     * 已经更准的状态改差。
     */
    fun betterStatus(a: MatchStatus, b: MatchStatus): MatchStatus =
        if (statusRank(b) > statusRank(a)) b else a

    /** 状态的可信度打分（[betterStatus] 用）。 */
    fun statusRank(s: MatchStatus): Int = when (s) {
        MatchStatus.FINISHED -> 3
        MatchStatus.LIVE -> 2
        MatchStatus.UPCOMING -> 1
    }

    /**
     * 归一化队名用于跨源匹配（去掉空白差异），并带上开赛日期防止同队重复场次被合并。
     *
     * ⚠️ 主客队必须**排序后**拼接：实测 JRS 列表与 JRKAN 的主客顺序相反
     * （JRS "骑士 vs 76人" / JRKAN "76人 vs 骑士"），按固定 "home|away" 排列
     * 会让两源的 key 永远对不上——同一场被拆成两张卡，点进去各只有半边信号。
     * 排序后顺序无关，仅日期+队名对决定归属（背靠背两场靠日期区分，仍然安全）。
     */
    fun normalizeKey(m: Match): String {
        val day = m.startAtMillis?.let {
            // ⚠️ 合并键的"哪一天"必须按北京时间算（review/02-data.md P2-2）：
            // 用 systemDefault() 时，设备在 UTC-5 会让同一个 epoch 落到前一天的日期，
            // 两源对同一场的 key 就对不上 —— 同一场拆成两张卡、各只有半边信号。
            java.time.Instant.ofEpochMilli(it)
                .atZone(MatchStatusClock.SOURCE_ZONE)
                .toLocalDate()
                .toString()
        } ?: fallbackDayKey(m)
        val (t1, t2) = listOf(m.homeTeam, m.awayTeam).map { it.replace(WHITESPACE, "") }.sorted()
        return "$day|$t1|$t2"
    }

    /**
     * 拿不到开赛时间戳时的合并键日期部分（review/02-data.md P2-12）。
     *
     * ## 旧实现的问题
     *
     * 旧兜底是 `m.timeLabel.substringBefore(' ')`。JRS 的"直播中"行
     * `timeLabel` 就是 `"直播中"`，于是 key 变成 `"直播中|A|B"`，
     * 而 JRKAN 同一场是 `"10-25|A|B"` —— 两者永不相等，
     * 同一场被拆成两张卡、各只有半边信号（用户看到"场次重复"且线路不全）。
     *
     * ## 现在的兜底
     *
     * 先看 `timeLabel` 里有没有 `MM-dd`（JRKAN 形态），有就用它；
     * 否则退到**当天**（北京时间）—— "直播中"的比赛必然发生在今天，
     * 这个推断比字面量强得多，且两源都会落到同一个日期上。
     *
     * 仍然可能误合并（同一天同一对队打两场），但那种情况极少，
     * 而"同一场拆成两张卡"是每次都会发生 —— 取舍明确。
     */
    fun fallbackDayKey(m: Match): String {
        val md = MMDD.find(m.timeLabel)
        if (md != null) {
            // "10-25" 形态：补上当前年份，保持与正常路径同样的 key 形状
            val year = java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE).year
            return runCatching {
                java.time.LocalDate.of(
                    year,
                    md.groupValues[1].toInt(),
                    md.groupValues[2].toInt(),
                ).toString()
            }.getOrDefault(m.timeLabel)
        }
        // 连 MM-dd 都没有（"直播中"/"未开始"）：用当天
        return java.time.LocalDate.now(MatchStatusClock.SOURCE_ZONE).toString()
    }

    /**
     * 队名归一化用的空白正则。
     *
     * ⚠️ 必须提成常量：每次调用 `Regex("\\s+")` 都会新建一个对象，
     * 而 normalizeKey 每场比赛要调两次 —— 几十场还好，上百场就是无谓的分配。
     */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 从 `timeLabel` 里抠 `MM-dd`（JRKAN 形态：`"10-25 07:30"`）。
     *
     * 用于 [fallbackDayKey]：拿不到 startAtMillis 时，至少还能从文案里
     * 拿到真实日期，而不是退化成 `"直播中"` 这种字面量（review/02-data.md P2-12）。
     */
    private val MMDD = Regex("""(\d{1,2})-(\d{1,2})""")
}