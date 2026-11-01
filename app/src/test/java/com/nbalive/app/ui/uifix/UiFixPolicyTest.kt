package com.nbalive.app.ui.uifix

import androidx.lifecycle.Lifecycle
import com.nbalive.app.data.model.MatchStatus
import com.nbalive.app.data.remote.FetchResult
import com.nbalive.app.data.remote.ReleaseRepository
import com.nbalive.app.ui.NavTab
import com.nbalive.app.ui.Route
import com.nbalive.app.ui.StatusLabel
import com.nbalive.app.ui.navStateKeyFor
import com.nbalive.app.ui.screens.RefreshingFeedback
import com.nbalive.app.ui.screens.refreshingFeedbackFor
import com.nbalive.app.ui.screens.shouldShowRefreshing
import com.nbalive.app.ui.shouldRunScoresTicker
import com.nbalive.app.ui.shouldRunTicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态/界面层修复的**纯判据**测试（review/fix/ui-state.md）。
 *
 * 这些判据都是"能失败"的：它们描述的正是修复前那段代码会给出的**相反**答案
 * （H7 的 ticker 在后台仍跑、H9 的空列表不给反馈、U15 的 LIVE 文案不走 StatusLabel…）。
 *
 * 只测纯函数/纯解析，不做源码扫描（扫描在 `UiFixWiringTest`）。
 */
class UiFixPolicyTest {

    // ---------------- H7 / U3：定时轮询的可见性判据 ----------------

    @Test
    fun `H7 只有 RESUMED 且停在列表页时才该 tick`() {
        // 前台 + 赛事页：该跑
        assertTrue(
            shouldRunTicker(Route.LIST, NavTab.MATCHES, Lifecycle.State.RESUMED),
        )
        // ⚠️ 切后台（STARTED/CREATED/…）**必须停**：旧实现用 LaunchedEffect，
        // 这里照样为 true —— 那正是"后台每 45 秒真发一次 ESPN 请求"的根因。
        for (state in listOf(
            Lifecycle.State.STARTED,
            Lifecycle.State.CREATED,
            Lifecycle.State.INITIALIZED,
            Lifecycle.State.DESTROYED,
        )) {
            assertFalse(
                "H7：lifecycle=$state 时 ticker 必须停（切后台/小窗期间不许再轮询）",
                shouldRunTicker(Route.LIST, NavTab.MATCHES, state),
            )
        }
        // 离开列表层（详情/播放）；「我的」页不消费列表数据
        assertFalse(shouldRunTicker(Route.DETAIL, NavTab.MATCHES, Lifecycle.State.RESUMED))
        assertFalse(shouldRunTicker(Route.PLAYER, NavTab.MATCHES, Lifecycle.State.RESUMED))
        assertFalse(shouldRunTicker(Route.LIST, NavTab.PROFILE, Lifecycle.State.RESUMED))
        // 「直播」页要跑状态老化（它列的就是 LIVE 场次）
        assertTrue(shouldRunTicker(Route.LIST, NavTab.LIVE, Lifecycle.State.RESUMED))
    }

    @Test
    fun `U3 比分轮询只该在赛事页跑 直播页不渲染比分`() {
        assertTrue(
            "赛事页消费 scores，必须轮询",
            shouldRunScoresTicker(Route.LIST, NavTab.MATCHES, Lifecycle.State.RESUMED),
        )
        // ⚠️ U3：LiveNowScreen 的签名里没有 scores —— 在「直播」页轮询
        // 等于每 45 秒白发一次请求（旧判据只挡了 PROFILE）
        assertFalse(
            "U3：「直播」页不消费比分，不许轮询 ESPN",
            shouldRunScoresTicker(Route.LIST, NavTab.LIVE, Lifecycle.State.RESUMED),
        )
        assertFalse(shouldRunScoresTicker(Route.LIST, NavTab.PROFILE, Lifecycle.State.RESUMED))
        assertFalse(
            "H7：后台连赛事页也不许轮询比分",
            shouldRunScoresTicker(Route.LIST, NavTab.MATCHES, Lifecycle.State.STARTED),
        )
        assertFalse(shouldRunScoresTicker(Route.DETAIL, NavTab.MATCHES, Lifecycle.State.RESUMED))
    }

    // ---------------- H9：空列表也要有刷新反馈 ----------------

    @Test
    fun `H9 页内 0 场时刷新也必须给反馈`() {
        // ⚠️ 这条就是 H9 的回归判据：旧门槛 `refreshing && visibleCount > 0`
        // 在 visibleCount == 0 时返回 false —— 用户点刷新整屏毫无变化。
        assertTrue(
            "H9：联赛筛选到 0 场（或直播页非比赛时段）时，刷新在跑就必须给反馈",
            shouldShowRefreshing(refreshing = true, loading = false, visibleCount = 0),
        )
        assertEquals(
            "页内没有列表可挂细条 → 反馈画在空态里",
            RefreshingFeedback.EmptyPlaceholder,
            refreshingFeedbackFor(refreshing = true, loading = false, visibleCount = 0),
        )
    }

    @Test
    fun `H9 有旧列表时挂细进度条 首载整屏 loading 时不叠加`() {
        assertEquals(
            RefreshingFeedback.Bar,
            refreshingFeedbackFor(refreshing = true, loading = false, visibleCount = 7),
        )
        // 首载：整屏 loading 已经在转，再叠一个指示器观感像卡住
        assertEquals(
            RefreshingFeedback.None,
            refreshingFeedbackFor(refreshing = true, loading = true, visibleCount = 0),
        )
        assertFalse(shouldShowRefreshing(refreshing = true, loading = true, visibleCount = 3))
        // 没在刷新：不给任何反馈
        assertEquals(
            RefreshingFeedback.None,
            refreshingFeedbackFor(refreshing = false, loading = false, visibleCount = 0),
        )
        assertFalse(shouldShowRefreshing(refreshing = false, loading = false, visibleCount = 5))
    }

    /** 判据自检：把"旧实现"写出来，证明上面的断言真的能区分新旧（不是永真断言）。 */
    @Test
    fun `H9 旧门槛在 0 场时确实为假`() {
        fun oldGate(refreshing: Boolean, visibleCount: Int): Boolean = refreshing && visibleCount > 0
        assertFalse(
            "旧门槛在 visibleCount=0 时为假 —— 这就是 H9 的现场",
            oldGate(refreshing = true, visibleCount = 0),
        )
        assertTrue(
            "而新判据为真（否则上面那条测不出东西）",
            shouldShowRefreshing(refreshing = true, loading = false, visibleCount = 0),
        )
    }

    // ---------------- U15：LIVE 文案只有一个来源 ----------------

    @Test
    fun `U15 StatusLabel 的 LIVE 分支可覆盖且默认走唯一来源`() {
        assertEquals("直播中", StatusLabel.of(MatchStatus.LIVE, isPostponed = false))
        assertEquals(
            "覆盖参数生效（调用方仍可自定义直播中文案）",
            "正在直播",
            StatusLabel.of(MatchStatus.LIVE, isPostponed = false, liveLabel = "正在直播"),
        )
        // 覆盖只对 LIVE 生效：延期/已结束的文案不许被覆盖
        assertEquals("延期", StatusLabel.of(MatchStatus.UPCOMING, isPostponed = true, liveLabel = "X"))
        assertEquals("已结束", StatusLabel.of(MatchStatus.FINISHED, isPostponed = false, liveLabel = "X"))
        // 唯一来源常量与默认文案一致
        assertEquals("直播中", StatusLabel.LIVE_TEXT)
    }

    // ---------------- U17：存档字段名不再有兜底 ----------------

    @Test
    fun `U17 未知的存档字段名必须当场报错 而不是静默取错字段`() {
        assertEquals("nav.tab", navStateKeyFor("tab"))
        assertEquals("nav.route", navStateKeyFor("route"))
        assertEquals("nav.match", navStateKeyFor("match"))
        // ⚠️ beforePlayer 此前靠 `else` 兜底命中；现在必须是显式分支
        assertEquals("nav.beforePlayer", navStateKeyFor("beforePlayer"))

        val thrown = runCatching { navStateKeyFor("macth") }.exceptionOrNull()
        assertNotNull(
            "U17：字段名写错必须抛错 —— 旧实现会安静返回 KEY_BEFORE_PLAYER，" +
                "重建时读到另一个字段的值（导航恢复成错的比赛/丢详情）",
            thrown,
        )
        assertTrue(
            "抛的是 IllegalStateException（error() 的形态），实际=${thrown!!::class.simpleName}",
            thrown is IllegalStateException,
        )
    }

    // ---------------- U12 / D15：取值下沉到 ReleaseRepository ----------------

    @Test
    fun `U12 ReleaseRepository 用 JsonCursor 取 tag 与 html_url`() = runBlocking {
        val body = """
            {"tag_name":"v1.2.24","html_url":"https://github.com/o/r/releases/tag/v1.2.24",
             "body":"这一行很长很长${"长".repeat(500)}","draft":false}
        """.trimIndent()
        val release = ReleaseRepository(fetch = { FetchResult.Ok(body) }).latest("https://api.example/latest")
        assertNotNull("正常响应必须解析出 tag", release)
        assertEquals("v1.2.24", release!!.tag)
        assertEquals("https://github.com/o/r/releases/tag/v1.2.24", release.htmlUrl)
    }

    @Test
    fun `U12 D15 更长的 key 不能命中 缺 tag 视为查不到`() = runBlocking {
        // ⚠️ D15：旧 `jsonString` 没有"key 前必须是 { 或 ,"的边界校验。
        // JsonCursor 有：`"tag_nameX"` 这种更长的 key 不许顶替 `"tag_name"`。
        val tricky = """{"tag_nameX":"1999","tag_name":"v2.0.0","html_url":"","body":"x"}"""
        val release = ReleaseRepository(fetch = { FetchResult.Ok(tricky) }).latest("u")
        assertEquals("必须取到真正的 tag_name", "v2.0.0", release?.tag)

        // 没有 tag_name：静默 null（宁可不提示更新）
        assertNull(ReleaseRepository(fetch = { FetchResult.Ok("""{"html_url":"u"}""") }).latest("u"))
        assertNull(ReleaseRepository(fetch = { FetchResult.Ok("""{"tag_name":""}""") }).latest("u"))
        // 非 2xx / 超时：FetchResult 非 Ok → null
        assertNull(ReleaseRepository(fetch = { FetchResult.Unreachable("offline") }).latest("u"))
        // 垃圾正文（<html>403</html>）：null 而不是抛异常
        assertNull(ReleaseRepository(fetch = { FetchResult.Ok("<html>403</html>") }).latest("u"))
        assertNull(ReleaseRepository(fetch = { FetchResult.Ok("") }).latest("u"))
    }
}