package com.nbalive.app.ui

import com.nbalive.app.data.remote.EspnScoreboard

/**
 * 把 ESPN 的英文队名对到源站的**中文**队名（2026-10-31 用户需求「比赛比分」）。
 *
 * ## 为什么必须做这层映射
 *
 * 源站（JRS / JRKAN）给的是中文队名（`湖人`、`勇士`），
 * ESPN 给的是英文（`Los Angeles Lakers`、`Golden State Warriors`）。
 * 两边**没有任何共同 id**（源站的 `data-lid` 是它自己的赛事号，ESPN 是 `401909285`），
 * 所以只能按名字对。
 *
 * ## 为什么按"昵称"对而不是全名
 *
 * ESPN 的 `displayName` 是 `Golden State Warriors`（城市+队名），
 * 而源站只写 `勇士`。用全名精确匹配一条都对不上；
 * 用 `nickname`（`Warriors`）对中文表才是稳定锚点 ——
 * 城市名（`Golden State` / `LA` / `Los Angeles`）反而是最容易变的部分
 * （同一个队 ESPN 自己就有 `LA Clippers` 和 `Los Angeles Clippers` 两种写法）。
 *
 * ## 认不出来就**不显示**
 *
 * 对不上时返回 null，卡片不画比分区块。**绝不**用"猜一个最像的"：
 * 把太阳的比分画到湖人头上，比不显示比分糟得多。
 */
object TeamNameBridge {

    /**
     * ESPN 昵称（小写）→ 源站中文名。
     *
     * 覆盖 NBA 全 30 队。键是 ESPN `team.name`（如 `Warriors`）小写后的值，
     * 值是源站实际用的中文写法（与 `League.NBA_TEAM_KEYWORDS` 保持同一套用词）。
     */
    private val NICK_TO_CN: Map<String, String> = mapOf(
        "hawks" to "老鹰",
        "celtics" to "凯尔特人",
        "nets" to "篮网",
        "hornets" to "黄蜂",
        "bulls" to "公牛",
        "cavaliers" to "骑士",
        "mavericks" to "独行侠",
        "nuggets" to "掘金",
        "pistons" to "活塞",
        "warriors" to "勇士",
        "rockets" to "火箭",
        "pacers" to "步行者",
        "clippers" to "快船",
        "lakers" to "湖人",
        "grizzlies" to "灰熊",
        "heat" to "热火",
        "bucks" to "雄鹿",
        "timberwolves" to "森林狼",
        "pelicans" to "鹈鹕",
        "knicks" to "尼克斯",
        "thunder" to "雷霆",
        "magic" to "魔术",
        "76ers" to "76人",
        "suns" to "太阳",
        "trail blazers" to "开拓者",
        "blazers" to "开拓者",
        "kings" to "国王",
        "spurs" to "马刺",
        "raptors" to "猛龙",
        "jazz" to "爵士",
        "wizards" to "奇才",
    )

    /** ESPN 英文队名（全名/短名/昵称均可）→ 中文名；认不出返回 null。 */
    fun toChinese(espnName: String?): String? {
        val raw = espnName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()
        // ① 整个名字就是昵称
        NICK_TO_CN[lower]?.let { return it }
        // ② 全名里以某个昵称结尾（`Golden State Warriors` → `warriors`）
        //    取最长的那个键，避免 `trail blazers` 被 `blazers` 抢先命中（两者都指向开拓者，无害，
        //    但换成一个真有歧义的名字时，短键优先会出错）
        return NICK_TO_CN.entries
            .filter { lower.endsWith(" ${it.key}") || lower == it.key }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /**
     * 在一份 ESPN 比分板里找这一场。
     *
     * 匹配规则：**主客队都**能对上中文名，且两边的（主、客）方向一致。
     *
     * ⚠️ 主客方向必须一致：ESPN 与源站的主客顺序**实测相反**过
     * （`MatchRepository.normalizeKey` 的注释里记着 JRS 与 JRKAN 就反过一次）。
     * 只判"两个队名都出现"会把方向搞反，比分画到对面去。
     *
     * @param homeTeam 源站的主队中文名
     * @param awayTeam 源站的客队中文名
     */
    fun findGame(
        games: List<EspnScoreboard.GameScore>,
        homeTeam: String,
        awayTeam: String,
    ): EspnScoreboard.GameScore? {
        val h = homeTeam.trim()
        val a = awayTeam.trim()
        if (h.isEmpty() || a.isEmpty()) return null
        return games.firstOrNull { g ->
            toChinese(g.homeName) == h && toChinese(g.awayName) == a
        }
    }

    /**
     * 卡片上比分旁边那一小块状态文案。
     *
     * - 未开赛（`pre`）：不显示比分，只显示开赛时间（由卡片自己的时间标签负责）→ 返回 null；
     * - 进行中（`in`）：`第3节 05:21`；
     * - 已结束（`post`）：`已结束`。
     */
    fun statusLabel(game: EspnScoreboard.GameScore): String? = when (game.state) {
        "pre" -> null
        "post" -> "已结束"
        else -> {
            val quarter = when (game.period) {
                1 -> "第1节"
                2 -> "第2节"
                3 -> "第3节"
                4 -> "第4节"
                in 5..9 -> "加时${game.period - 4}"
                else -> ""
            }
            listOf(quarter, game.clock).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "进行中" }
        }
    }

    /**
     * 比分是否可显示。
     *
     * ⚠️ 未开赛时 ESPN **不给** `score` 字段（不是 "0"），解析出来是 null；
     * 这里再判一次 `state`：即使哪天 ESPN 给了 `"0"`，未开赛也不该显示 `0 - 0`
     * （用户会以为比赛已经开打且一分未得）。
     */
    fun displayableScore(game: EspnScoreboard.GameScore): Pair<Int, Int>? {
        if (game.state == "pre") return null
        val h = game.homeScore ?: return null
        val a = game.awayScore ?: return null
        return h to a
    }
}
