package com.nbalive.app.data.repository

import com.nbalive.app.data.model.League
import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.model.Provider
import com.nbalive.app.data.model.StreamSource
import com.nbalive.app.data.remote.FetchResult
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 跨源合并后的信号 id 唯一性。
 *
 * ⚠️ 这个仓库已经因为 LazyColumn 的 key 重复崩过两次。
 * 播放器 `itemsIndexed(signals, key = { _, s -> s.id })`，
 * 信号 id 重复 → IllegalStateException → 直接闪退。
 *
 * 风险点：跨源合并后 `match.id` 取自 **JRKAN**（先合并），
 * 于是
 *  - JRS 详情页信号 id = "${match.id}-s0" = "jrkan-<eventId>-s0"
 *  - JRKAN 列表页信号 id = "jrkan-<eventId>-s0"
 * 两者**完全相同**，且两者 pageUrl 不同（不同 host），
 * 所以 distinctBy { pageUrl } 去不掉，重复 id 直接进播放器 → 崩。
 */
class SignalIdUniquenessTest {

    private val repo = MatchRepository()

    @Test
    fun `跨源合并后所有信号 id 唯一`() = runBlocking {
        val jrsDetailHtml = """
            <div class="match-info">
              <a class="toLive" data-url="https://jrszbt.com/play/steam999.html">信号1</a>
              <a class="toLive" href="https://jrszbt.com/play/steam888.html">信号2</a>
            </div>
        """.trimIndent()

        // JRKAN 列表页给的播放页（线路在内层页，parseChannels 拿不到 data-play）
        val jrkanPage = "<html><body><div class='wrap'><div class='video'></div></div></body></html>"

        val detail = repo.loadSignals(
            Match(
                id = "jrkan-824885",
                league = League.NBA,
                leagueRawName = "NBA",
                homeTeam = "湖人",
                awayTeam = "凯尔特人",
                homeLogo = null,
                awayLogo = null,
                timeLabel = "07:00",
                startAtMillis = null,
                status = MatchStatus.LIVE,
                signalCount = 0,
                sources = listOf(
                    StreamSource(
                        id = "jrkan-824885-s0",
                        label = "信号1",
                        provider = Provider.JRKAN,
                        pageUrl = "http://play.sportsteam53.com/play/steam824885.html",
                    ),
                ),
                jrsDetailId = "555",
            ),
        ) { url, _ ->
            if (url.contains("/live/")) {
                FetchResult.Ok(jrsDetailHtml)
            } else {
                FetchResult.Ok(jrkanPage)
            }
        }

        val ids = detail.signals.map { it.id }
        assertEquals(
            "信号 id 重复会让播放器 LazyColumn 的 key 冲突而闪退（这个仓库崩过两次）。实际: $ids",
            ids.size,
            ids.distinct().size,
        )
    }
}
