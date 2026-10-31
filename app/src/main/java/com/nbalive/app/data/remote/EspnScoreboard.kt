package com.nbalive.app.data.remote

/**
 * ESPN 比分板解析器（2026-10-31 用户需求「比赛比分」）。
 *
 * ## 为什么比分必须来自外部
 *
 * 已实测确认（2026-10-31，抓源站真实页面）：
 *  - **JRS 列表页**：`<p class="list-info-time">` 里只有「直播中」或「20:30」，
 *    全页没有任何 `数字:数字` 的比分形态（唯一命中的 `11-07` 是队徽图片的日期路径）。
 *  - **JRKAN 列表 JS**：`<em class="bf" title="2">` 的 `title` 全是 `2`（这是**源站类型编号**，
 *    不是比分）；`<li class="lab_bc">` 里是空的 `<span></span>`。108 个 `bf` 全为 `"2"`。
 *  - **播放页**（`/play/mgxl/`、`paps.html`）：全文 `score`/`比分`/`lab_bc` 命中数为 0。
 *
 * 也就是说源站**不提供**比分，只能从第三方取。选 ESPN 的理由：
 *  - 无需 API key（`site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard`）；
 *  - 真机实测可达（模拟器 `curl` 返回 200，约 238 KB）；
 *  - 同时给出 `status.type.state`（pre/in/post）与剩余时间，可用于卡片角标。
 *
 * ## ⚠️ CBA 没有数据
 *
 * ESPN 的 `basketball/cba/scoreboard` 返回 **HTTP 400**（实测）。
 * 所以 CBA 场次**没有比分**，必须如实降级：卡片不显示比分区块，
 * 而不是显示 `0 - 0` 或空白。判据见 [EspnScoreboard.lookup] 返回 null 时调用方的处理。
 *
 * ## 为什么不用 Gson/Moshi
 *
 * 本仓库没有任何 JSON 依赖（`build.gradle.kts` 里只有 jsoup/OkHttp）。
 * 为一份 238 KB 的比分板引入一个反射型 JSON 库不划算（还会拖慢 R8 后的启动）。
 * 这里用**手写的最小 JSON 取值器**：只需要"从任意嵌套里取出几个字段"，
 * 不建对象模型，也不需要处理全部 JSON 语法之外的东西。
 */
object EspnScoreboard {

    /** 一场比赛的比分（只保留界面真正要用的字段）。 */
    data class GameScore(
        /** 主队全名（ESPN 原文，如 `Golden State Warriors`） */
        val homeName: String,
        val awayName: String,
        val homeScore: Int?,
        val awayScore: Int?,
        /** `pre` / `in` / `post` */
        val state: String,
        /** 剩余时间显示串（如 `12:00` / `Final`）；可能为空 */
        val clock: String,
        /** 当前节次（1..4 / 加时为 5+）；0 = 未知 */
        val period: Int,
        /** 开赛时间（epoch millis）；0 = 未知 */
        val startAtMillis: Long,
    )

    /** 解析结果：成功时给场次列表，失败给 null（**静默失败**，见类说明）。 */
    fun parse(json: String?): List<GameScore>? {
        if (json.isNullOrBlank()) return null
        val events = JsonCursor(json).array("events") ?: return null
        val out = ArrayList<GameScore>(events.size)
        for (event in events) {
            val comps = event.array("competitions") ?: continue
            val comp = comps.firstOrNull() ?: continue
            val competitors = comp.array("competitors") ?: continue
            var home: JsonCursor? = null
            var away: JsonCursor? = null
            for (c in competitors) {
                when (c.string("homeAway")) {
                    "home" -> home = c
                    "away" -> away = c
                }
            }
            val h = home ?: continue
            val a = away ?: continue
            val status = comp.obj("status") ?: event.obj("status")
            val type = status?.obj("type")
            val state = type?.string("state").orEmpty()
            out += GameScore(
                homeName = h.obj("team")?.string("displayName").orEmpty(),
                awayName = a.obj("team")?.string("displayName").orEmpty(),
                // ⚠️ 未开赛时 score 字段**不存在**（不是 "0"）：必须给 null，
                // 显示 0-0 会让用户以为比赛已经开打
                homeScore = h.string("score")?.toIntOrNull(),
                awayScore = a.string("score")?.toIntOrNull(),
                state = state,
                clock = status?.string("displayClock") ?: type?.string("shortDetail").orEmpty(),
                period = status?.int("period") ?: 0,
                startAtMillis = parseIso8601(event.string("date")) ?: 0L,
            )
        }
        return out
    }

    /**
     * `2026-10-31T02:00Z` → epoch millis。
     *
     * ⚠️ 不用 `java.time.Instant.parse`：它在 minSdk 24 上需要 desugaring，
     * 而本工程没开 coreLibraryDesugaring（开了会显著增大 APK）。
     * 这个格式是**固定**的（ESPN 只给 UTC 的 `Z` 结尾），手写解析足够且零依赖。
     */
    fun parseIso8601(text: String?): Long? {
        val t = text?.trim().orEmpty()
        // ESPN 的真实形态是 `2026-10-31T02:00Z`（**17** 字符，没有秒）。
        // ⚠️ 早先按"必须有秒"写成 `length < 20` + `substring(17,19)`，
        // 于是每一个正常时间戳都越界抛异常（测试全红才发现）。秒是可选的。
        if (t.length < 17) return null
        val year = t.substring(0, 4).toIntOrNull() ?: return null
        val month = t.substring(5, 7).toIntOrNull() ?: return null
        val day = t.substring(8, 10).toIntOrNull() ?: return null
        val hour = t.substring(11, 13).toIntOrNull() ?: return null
        val minute = t.substring(14, 16).toIntOrNull() ?: return null
        // 秒只在 `HH:mm:ssZ`（20 字符）里出现；缺省按 0
        val second = if (t.length >= 19) t.substring(17, 19).toIntOrNull() ?: 0 else 0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    /**
     * 一份**最小** JSON 游标：只支持"按 key 取子节点/标量"，够用即可。
     *
     * 刻意不做完整解析：ESPN 的响应里有 `highlights`/`leaders`/`statistics` 等
     * 上百 KB 我们完全不需要的子树，建完整对象模型是纯浪费。
     */
    internal class JsonCursor(private val src: String, private val start: Int = 0, private val end: Int = src.length) {

        fun obj(key: String): JsonCursor? {
            val idx = keyIndex(key) ?: return null
            // ⚠️ 偏移量：idx 指向 key 的**左引号**，key 本身占 key.length 个字符，
            // 所以右引号在 idx + key.length + 1，冒号从它的下一个位置开始找。
            // 早先写成 `idx + key.length + 1` 后直接找冒号 —— 停在右引号上，
            // 每次 `src[i] != ':'` 都成立，于是**所有**字段都取不到（测试全红才发现）。
            var i = skipWs(idx + key.length + 2)
            if (i >= end || src[i] != ':') return null
            i = skipWs(i + 1)
            if (i >= end || src[i] != '{') return null
            val close = matchBrace(i, '{', '}')
            return if (close < 0) null else JsonCursor(src, i + 1, close)
        }

        fun array(key: String): List<JsonCursor>? {
            val idx = keyIndex(key) ?: return null
            var i = skipWs(idx + key.length + 2)
            if (i >= end || src[i] != ':') return null
            i = skipWs(i + 1)
            if (i >= end || src[i] != '[') return null
            val close = matchBrace(i, '[', ']')
            if (close < 0) return null
            val out = mutableListOf<JsonCursor>()
            var j = skipWs(i + 1)
            while (j < close) {
                if (src[j] == '{') {
                    val e = matchBrace(j, '{', '}')
                    if (e < 0 || e > close) break
                    out += JsonCursor(src, j + 1, e)
                    j = skipWs(e + 1)
                } else {
                    j++
                }
                if (j < close && src[j] == ',') j = skipWs(j + 1)
            }
            return out
        }

        fun string(key: String): String? {
            val idx = keyIndex(key) ?: return null
            var i = skipWs(idx + key.length + 2)
            if (i >= end || src[i] != ':') return null
            i = skipWs(i + 1)
            if (i >= end || src[i] != '"') return null
            val sb = StringBuilder()
            i++
            while (i < end) {
                val c = src[i]
                when {
                    c == '\\' && i + 1 < end -> {
                        val n = src[i + 1]
                        sb.append(
                            when (n) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                'u' -> {
                                    val hex = src.substring(i + 2, minOf(i + 6, end))
                                    val code = hex.toIntOrNull(16)
                                    i += 4
                                    if (code != null) code.toChar() else ' '
                                }
                                else -> n
                            },
                        )
                        i += 2
                    }
                    c == '"' -> return sb.toString()
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }

        fun int(key: String): Int? = string(key)?.toIntOrNull() ?: number(key)?.toIntOrNull()

        /**
         * 取**不带引号**的数字字段（ESPN 里 `"period":4`、`"clock":720.0` 都是这种形态）。
         *
         * ⚠️ 只走 [string] 会漏掉它们：`string` 要求值以 `"` 开头，
         * 而数字字段没有引号 —— 表现为"字段明明在响应里，解析出来却是 null/0"，
         * 且不报任何错（`period` 恒为 0 时卡片只是少显示一个"第几节"）。
         */
        fun number(key: String): String? {
            val idx = keyIndex(key) ?: return null
            var i = skipWs(idx + key.length + 2)
            if (i >= end || src[i] != ':') return null
            i = skipWs(i + 1)
            val begin = i
            while (i < end && (src[i].isDigit() || src[i] == '-' || src[i] == '.' || src[i] == 'e' || src[i] == 'E' || src[i] == '+')) i++
            return if (i > begin) src.substring(begin, i) else null
        }

        /**
         * 找 `"key":` 的位置。
         *
         * ⚠️ 必须要求 key 前是 `{` 或 `,`（或空白后接这两者）：
         * 否则 `"homeScore"` 会命中 `"awayHomeScore"` 这类更长 key 的后缀，
         * 取到**错的**字段且完全不报错。
         */
        private fun keyIndex(key: String): Int? {
            val needle = "\"$key\""
            var from = start
            while (true) {
                val at = src.indexOf(needle, from)
                if (at < 0 || at >= end) return null
                val after = at + needle.length
                var k = after
                while (k < end && src[k].isWhitespace()) k++
                if (k < end && src[k] == ':') {
                    // 前一个非空白字符必须是 { 或 ,
                    var p = at - 1
                    while (p >= start && src[p].isWhitespace()) p--
                    if (p < start || src[p] == '{' || src[p] == ',') return at
                }
                from = after
            }
        }

        private fun matchBrace(open: Int, o: Char, c: Char): Int {
            var depth = 0
            var inStr = false
            var i = open
            while (i < end) {
                val ch = src[i]
                when {
                    inStr -> {
                        if (ch == '\\') i++ else if (ch == '"') inStr = false
                    }
                    ch == '"' -> inStr = true
                    ch == o -> depth++
                    ch == c -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }

        private fun skipWs(i: Int): Int {
            var k = i
            while (k < end && src[k].isWhitespace()) k++
            return k
        }
    }
}
