package com.nbalive.app.data.model

import com.nbalive.app.data.remote.EspnScoreboard
import com.nbalive.app.ui.UpdateCheckPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队名表合并与中文别名的判据（task-6 / fix-data）。
 *
 * **data.md D6**：两份队名表互相不一致 ——
 *  - ① `League.NBA_TEAM_KEYWORDS` 缺雷霆/猛龙/奇才，导致
 *    "雷霆 vs 猛龙""奇才 vs 雷霆"这类**双方都认不出**的对阵在联赛名为空时落 `OTHER`，
 *    被 `League.supported` 过滤整场丢弃；
 *  - ② `TeamNameBridge` 每个昵称只映射一个中文名，源站写「小牛」时
 *    `toChinese(ESPN Mavericks) == "小牛"` 永不成立 → 卡片静默不画比分。
 */
class TeamKeywordFixesTest {

    @Test
    fun `雷霆 猛龙 奇才 的对阵也要能判出 NBA`() {
        assertEquals("雷霆 vs 猛龙 两支原先都在表外", League.NBA, League.resolve("", "雷霆", "猛龙"))
        assertEquals("奇才 vs 雷霆", League.NBA, League.resolve("", "奇才", "雷霆"))
        // 非空但认不出的联赛名仍然不许靠队名硬猜（防止足球赛被塞进篮球列表）
        assertEquals(League.OTHER, League.resolve("英超", "雷霆", "猛龙"))
    }

    @Test
    fun `NBA 全 30 队都能被兜底表认出`() {
        // 与 TeamNameBridge 的覆盖面对齐：任一队在兜底表里认不出，就是一次静默丢场次
        val teams = listOf(
            "老鹰", "凯尔特人", "篮网", "黄蜂", "公牛", "骑士", "独行侠", "掘金", "活塞", "勇士",
            "火箭", "步行者", "快船", "湖人", "灰熊", "热火", "雄鹿", "森林狼", "鹈鹕", "尼克斯",
            "雷霆", "魔术", "76人", "太阳", "开拓者", "国王", "马刺", "猛龙", "爵士", "奇才",
        )
        val unresolved = teams.filter { League.resolve("", it, it) != League.NBA }
        assertTrue("以下球队在兜底表里认不出（会被整场丢弃）：$unresolved", unresolved.isEmpty())
    }

    @Test
    fun `英文昵称映射到中文别名集合`() {
        assertTrue(
            "Mavericks 必须同时认得「独行侠」与「小牛」两个源站写法",
            TeamNameBridge.toChineseAliases("Dallas Mavericks").containsAll(listOf("独行侠", "小牛")),
        )
        assertEquals("规范名仍是第一个别名（既有断言不变）", "独行侠", TeamNameBridge.toChinese("Dallas Mavericks"))
        assertTrue("认不出来必须给空集合", TeamNameBridge.toChineseAliases("Guangdong Southern Tigers").isEmpty())
        assertNull(TeamNameBridge.toChinese("Guangdong Southern Tigers"))
    }

    @Test
    fun `ESPN 达拉斯与源站小牛能配上 且主客方向仍校验`() {
        val games = listOf(
            EspnScoreboard.GameScore("Dallas Mavericks", "Golden State Warriors", 99, 88, "post", "Final", 4),
        )
        assertNotNull("源站写「小牛」必须能配上", TeamNameBridge.findGame(games, "小牛", "勇士"))
        assertNotNull("规范写法「独行侠」同样能配上", TeamNameBridge.findGame(games, "独行侠", "勇士"))
        assertNull(
            "方向反了不能匹配（比分会被画到对面去）—— 别名集合不许削弱这条校验",
            TeamNameBridge.findGame(games, "勇士", "小牛"),
        )
    }

    @Test
    fun `别名集合不改变认不出的返回值`() {
        val games = listOf(
            EspnScoreboard.GameScore("Los Angeles Lakers", "Golden State Warriors", 1, 2, "post", "Final", 4),
        )
        assertNull("太阳认不出来，必须不匹配", TeamNameBridge.findGame(games, "勇士", "太阳"))
        assertEquals(setOf("湖人"), TeamNameBridge.toChineseAliases("Lakers"))
        assertEquals(setOf("勇士"), TeamNameBridge.toChineseAliases("warriors"))
    }
}

/**
 * 版本号比较语义的判据（task-6 / fix-data）。
 *
 * **data.md D17**：`-`/`+` 之后的后缀必须视为**低于**同数字版本 ——
 * 旧实现只捞数字段，于是 `1.2.24-hotfix.1` 被判成比 `1.2.24` 新
 * （提示升级到"同名版本"，用户点了没变化）、`1.2.24-hotfix` 被判成相等
 * （真有新版却不提示）。
 */
class VersionCompareFixesTest {

    @Test
    fun `后缀版本低于同数字的正式版`() {
        assertFalse(
            "1.2.24-hotfix 不许被判成比 1.2.24 新（旧实现把它判成相等）",
            UpdateCheckPolicy.isRemoteNewer("1.2.24-hotfix", "1.2.24"),
        )
        assertTrue(
            "反向必须成立：1.2.24 比 1.2.24-hotfix 新",
            UpdateCheckPolicy.isRemoteNewer("1.2.24", "1.2.24-hotfix"),
        )
        assertFalse(
            "1.2.24-rc.1 不许被判成比 1.2.24 新（旧实现把后缀里的 1 当成第 4 段）",
            UpdateCheckPolicy.isRemoteNewer("1.2.24-rc.1", "1.2.24"),
        )
        assertFalse(
            "`+build` 同样低于同数字版本",
            UpdateCheckPolicy.isRemoteNewer("1.2.24+1", "1.2.24"),
        )
    }

    @Test
    fun `两边都带后缀时后缀里的数字仍可比较`() {
        assertTrue(
            "rc.2 比 rc.1 新（后缀数字段仍逐段比较）",
            UpdateCheckPolicy.isRemoteNewer("1.2.24-rc.2", "1.2.24-rc.1"),
        )
        assertFalse(
            "rc.1 不比 rc.2 新",
            UpdateCheckPolicy.isRemoteNewer("1.2.24-rc.1", "1.2.24-rc.2"),
        )
    }

    @Test
    fun `数字段优先的既有语义完全不变`() {
        assertTrue("大数字段仍然赢", UpdateCheckPolicy.isRemoteNewer("1.2.24-hotfix", "1.2.23"))
        assertFalse("小数字段仍然不赢", UpdateCheckPolicy.isRemoteNewer("1.2.23", "1.2.24-beta.1"))
        assertFalse(UpdateCheckPolicy.isRemoteNewer("1.2", "1.2.0"))
        assertTrue(UpdateCheckPolicy.isRemoteNewer("v1.2.24", "1.2.23"))
        assertEquals(
            "normalize 的行为有意保持（既有用例钉着 1.2.24.1）",
            "1.2.24.1",
            UpdateCheckPolicy.normalize("1.2.24-beta.1"),
        )
        assertEquals("1.2.24", UpdateCheckPolicy.normalize("v1.2.24"))
    }

    @Test
    fun `解析失败仍然静默不提示`() {
        assertFalse(UpdateCheckPolicy.isRemoteNewer(null, "1.2.23"))
        assertFalse(UpdateCheckPolicy.isRemoteNewer("", "1.2.23"))
        assertFalse(UpdateCheckPolicy.isRemoteNewer("1.2.24", null))
    }
}