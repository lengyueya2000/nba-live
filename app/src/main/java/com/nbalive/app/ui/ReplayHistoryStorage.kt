package com.nbalive.app.ui

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.ReplayLine
import com.nbalive.app.data.remote.EspnScoreboard

/**
 * 录像历史的 JSON 落盘序列化（2026-11-25 用户需求）。
 *
 * 项目没有引入 kotlinx.serialization / Gson，这里手写 encode、用现有的
 * [EspnScoreboard.JsonCursor] 做 decode —— 纯 Kotlin，JVM 单测可跑。
 * 字段都是我们自己的历史数据（不含源站的复杂结构），格式固定可控。
 *
 * 用 `{"items":[...]}` 包一层是因为 JsonCursor.array 需要 key。
 */
object ReplayHistoryStorage {

    fun encode(items: List<ReplayHistoryItem>): String {
        if (items.isEmpty()) return """{"items":[]}"""
        val body = items.joinToString(",") { it ->
            """
            {"matchId":${q(it.matchId)},"league":${q(it.league.name)},
             "leagueRawName":${q(it.leagueRawName)},"homeTeam":${q(it.homeTeam)},
             "awayTeam":${q(it.awayTeam)},"homeScore":${it.homeScore ?: "null"},
             "awayScore":${it.awayScore ?: "null"},
             "homeLogo":${it.homeLogo?.let(::q) ?: "null"},
             "awayLogo":${it.awayLogo?.let(::q) ?: "null"},
             "timeLabel":${q(it.timeLabel)},
             "lines":[${it.lines.joinToString(",") { l ->
                """{"id":${q(l.id)},"label":${q(l.label)},"url":${q(l.url)}}"""
             }}],
             "lastPositionMs":${it.lastPositionMs},"watchedAt":${it.watchedAt}}
            """.trimIndent().replace("\n", "")
        }
        return """{"items":[$body]}"""
    }

    fun decode(json: String): List<ReplayHistoryItem> {
        if (json.isBlank()) return emptyList()
        val nodes = runCatching { EspnScoreboard.JsonCursor(json).array("items") }.getOrNull() ?: return emptyList()
        return nodes.mapNotNull { node ->
            val matchId = node.string("matchId") ?: return@mapNotNull null
            val home = node.string("homeTeam") ?: return@mapNotNull null
            val away = node.string("awayTeam") ?: return@mapNotNull null
            val leagueName = node.string("league").orEmpty()
            val league = runCatching { League.valueOf(leagueName) }.getOrNull() ?: League.OTHER
            val lines = (node.array("lines") ?: emptyList()).mapNotNull { line ->
                val id = line.string("id") ?: return@mapNotNull null
                val label = line.string("label") ?: return@mapNotNull null
                val url = line.string("url") ?: return@mapNotNull null
                ReplayLine(id, label, url)
            }
            ReplayHistoryItem(
                matchId = matchId,
                league = league,
                leagueRawName = node.string("leagueRawName").orEmpty(),
                homeTeam = home,
                awayTeam = away,
                homeScore = node.int("homeScore"),
                awayScore = node.int("awayScore"),
                // 向后兼容：老版本落盘没有 logo 字段 → string() 返回 null
                homeLogo = node.string("homeLogo"),
                awayLogo = node.string("awayLogo"),
                timeLabel = node.string("timeLabel").orEmpty(),
                lines = lines,
                // int() 会先走 string()：数字字段没有引号，number() 兜底
                lastPositionMs = node.number("lastPositionMs")?.toLongOrNull() ?: 0L,
                watchedAt = node.number("watchedAt")?.toLongOrNull() ?: 0L,
            )
        }
    }

    /** JSON 字符串转义（队名可能含引号/反斜杠）。 */
    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
