package com.nbalive.app.ui

import com.nbalive.app.GuardSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页「锁定」的纯逻辑。
 *
 * 锁定 = 防误触：浮层全部隐藏、亮度/音量滑动停用，看球时手滑不会误切线路/退出。
 * 视频继续播，屏幕继续常亮。
 *
 * 交互（用户明确要求）：
 *  - 锁定时画面**完全干净** —— 不显示锁按钮、不显示任何提示
 *  - **单击画面**才唤出锁按钮，3 秒后自动收起
 *  - 只有点锁按钮才解锁；返回键在锁定时不退出播放页
 */
class PlayerLockTest {

    @Test
    fun `锁定时浮层全部隐藏`() {
        // 时钟/画质/右上角按钮/底部控制栏都不能再出现
        assertFalse(PlayerLock.showOverlays(locked = true))
    }

    @Test
    fun `未锁定时浮层照常显示`() {
        assertTrue(PlayerLock.showOverlays(locked = false))
    }

    @Test
    fun `锁定时亮度音量手势必须停用`() {
        // 手势不停用的话，滑动还是会触发亮度/音量变化 —— 那就等于没锁
        assertFalse(PlayerLock.gesturesEnabled(locked = true))
    }

    @Test
    fun `未锁定时手势层正常`() {
        assertTrue(PlayerLock.gesturesEnabled(locked = false))
    }

    // ---------- 手势层配置：真实判定（P2-9 / P1-6 / P2-2） ----------
    //
    // 这些断言测的是 PlayerScreen 真正会调用的那个函数。
    // 以前 gesturesEnabled / tapShowsUnlockUi 没有任何生产调用方，
    // 单测测的是死函数，真实判定散在 PlayerScreen 的三元表达式里 ——
    // 改坏了不会有测试红。现在判定收敛到 gestureConfig，生产方必须调它。

    @Test
    fun `全屏未锁定才接管滑动`() {
        // P2-2：非全屏时页面本身要能上下滚（信号列表在视频下面），
        // 手势层如果无条件接管垂直拖动，从视频区起手就滚不动页面。
        assertTrue(
            "全屏未锁定：滑动调亮度/音量",
            PlayerLock.gestureConfig(locked = false, fullscreen = true, inPip = false).dragEnabled,
        )
        assertFalse(
            "非全屏：滑动必须留给父级 verticalScroll",
            PlayerLock.gestureConfig(locked = false, fullscreen = false, inPip = false).dragEnabled,
        )
        assertFalse(
            "锁定时滑动一律不接管（防误触的核心）",
            PlayerLock.gestureConfig(locked = true, fullscreen = true, inPip = false).dragEnabled,
        )
        assertFalse(
            "画中画里没有手势层",
            PlayerLock.gestureConfig(locked = false, fullscreen = true, inPip = true).layerEnabled,
        )
    }

    @Test
    fun `锁定时单击必须消费 否则会透传给源站播放器`() {
        // P1-6：锁定后我们的浮层隐藏了，但单击事件仍会透传给 WebView 里的
        // 源站播放器 —— 手掌蹭一下就点亮源站控制条，等于没锁住。
        assertTrue(
            "锁定态：消费单击，WebView 收不到这次点击",
            PlayerLock.gestureConfig(locked = true, fullscreen = true, inPip = false).consumeTap,
        )
        assertFalse(
            "非锁定态必须透传 —— v1.2.5 明确要求网页线路能单击调出源站控制条",
            PlayerLock.gestureConfig(locked = false, fullscreen = true, inPip = false).consumeTap,
        )
        assertFalse(
            "非全屏同理：网页线路单击要能传给源站播放器",
            PlayerLock.gestureConfig(locked = false, fullscreen = false, inPip = false).consumeTap,
        )
    }

    @Test
    fun `单击动作按锁定和全屏分流`() {
        // 锁定时单击唤出解锁 UI（否则用户找不到解锁入口）；
        // 未锁定全屏时单击切换浮层显隐；
        // 未锁定非全屏时什么都不做（浮层常驻，单击留给源站播放器）。
        assertEquals(
            PlayerLock.TapAction.TOGGLE_UNLOCK_UI,
            PlayerLock.gestureConfig(locked = true, fullscreen = true, inPip = false).tapAction,
        )
        assertEquals(
            PlayerLock.TapAction.TOGGLE_UNLOCK_UI,
            PlayerLock.gestureConfig(locked = true, fullscreen = false, inPip = false).tapAction,
        )
        assertEquals(
            PlayerLock.TapAction.TOGGLE_CONTROLS,
            PlayerLock.gestureConfig(locked = false, fullscreen = true, inPip = false).tapAction,
        )
        assertEquals(
            PlayerLock.TapAction.PASS_THROUGH,
            PlayerLock.gestureConfig(locked = false, fullscreen = false, inPip = false).tapAction,
        )
    }

    @Test
    fun `手势层配置与单项判定保持一致`() {
        // 防止两套规则又走岔：gestureConfig 是生产入口，
        // gesturesEnabled / tapShowsUnlockUi 是它的单项视图。
        listOf(true, false).forEach { locked ->
            val config = PlayerLock.gestureConfig(locked = locked, fullscreen = true, inPip = false)
            assertEquals(
                "dragEnabled 必须等于 gesturesEnabled(locked=$locked)",
                PlayerLock.gesturesEnabled(locked),
                config.dragEnabled,
            )
            assertEquals(
                "tapAction 必须与 tapShowsUnlockUi(locked=$locked) 一致",
                PlayerLock.tapShowsUnlockUi(locked),
                config.tapAction == PlayerLock.TapAction.TOGGLE_UNLOCK_UI,
            )
        }
    }

    @Test
    fun `锁定时底部控制栏不显示`() {
        // 底部栏的完整规则在 PlayerControls（含全屏/画中画/浮层显隐），
        // 这里只钉"锁定"这一个维度：锁定时即使全屏、浮层可见也不显示。
        assertFalse(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = true,
                streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8",
                webFallbackUrl = null,
            ),
        )
        assertTrue(
            PlayerControls.shouldShowBottomBar(
                fullscreen = true,
                inPip = false,
                controlsVisible = true,
                locked = false,
                streamUrl = "https://hlsz.yjjcfw.com/live/1.m3u8",
                webFallbackUrl = null,
            ),
        )
    }

    @Test
    fun `锁定时默认不显示锁按钮`() {
        // 用户要求：锁屏时不要显示按钮，画面保持干净
        assertFalse(
            "锁定且没单击过画面时，锁按钮不该出现",
            PlayerLock.showLockButton(locked = true, unlockUiVisible = false),
        )
    }

    @Test
    fun `锁定后单击画面才显示锁按钮`() {
        assertTrue(
            "锁定态下单击唤出解锁 UI 后，锁按钮必须出现（否则无法解锁）",
            PlayerLock.showLockButton(locked = true, unlockUiVisible = true),
        )
    }

    @Test
    fun `未锁定时锁按钮跟随浮层显示`() {
        assertTrue(PlayerLock.showLockButton(locked = false, unlockUiVisible = false))
        assertTrue(PlayerLock.showLockButton(locked = false, unlockUiVisible = true))
    }

    @Test
    fun `锁定时返回键不退出播放页`() {
        // 返回键在锁定时不能把用户带出播放页
        assertFalse(PlayerLock.backExitsPlayer(locked = true))
        assertTrue(PlayerLock.backExitsPlayer(locked = false))
    }

    @Test
    fun `锁定时默认不显示提示`() {
        // 锁定时画面要干净，提示不能常驻
        assertFalse(
            "锁定且没单击过画面时，提示不该出现",
            PlayerLock.showLockHint(locked = true, unlockUiVisible = false),
        )
    }

    @Test
    fun `单击唤出解锁UI时提示一起出现`() {
        assertTrue(
            "唤出解锁 UI 时要有提示告诉用户点哪里",
            PlayerLock.showLockHint(locked = true, unlockUiVisible = true),
        )
    }

    @Test
    fun `未锁定时不显示锁定提示`() {
        assertFalse(PlayerLock.showLockHint(locked = false, unlockUiVisible = true))
        assertFalse(PlayerLock.showLockHint(locked = false, unlockUiVisible = false))
    }

    @Test
    fun `锁定态下单击应该唤出解锁UI`() {
        assertTrue(PlayerLock.tapShowsUnlockUi(locked = true))
        // 未锁定时单击归调用方处理（切换浮层显隐），不走这条
        assertFalse(PlayerLock.tapShowsUnlockUi(locked = false))
    }

    @Test
    fun `解锁UI的自动收起时间要够点得到`() {
        // 太短来不及点，太长又破坏画面干净
        assertTrue(
            "3 秒是可用性与画面干净的折中，不该短于 2 秒",
            PlayerLock.UNLOCK_UI_TIMEOUT_MS >= 2_000,
        )
        assertTrue(
            "超过 5 秒按钮就一直挂着了，失去自动收起的意义",
            PlayerLock.UNLOCK_UI_TIMEOUT_MS <= 5_000,
        )
    }

    @Test
    fun `切到另一场比赛时锁定自动失效`() {
        // 回归：「同时在播」面板切场时 route 一直是 PLAYER，
        // 裸布尔锁会跟着漏到新的一场 —— 用户看着新比赛却是锁着的、浮层全没了。
        // 锁绑定到比赛 id 后，换场自然不再锁定。
        val lockedMatchId = "match-A"
        assertFalse(
            "锁的是 A、看的是 B，不该处于锁定态",
            PlayerLock.isLocked(lockedMatchId = lockedMatchId, currentMatchId = "match-B"),
        )
        assertTrue(
            "锁的就是当前这场，处于锁定态",
            PlayerLock.isLocked(lockedMatchId = lockedMatchId, currentMatchId = "match-A"),
        )
        assertFalse(
            "没锁过就不该是锁定态",
            PlayerLock.isLocked(lockedMatchId = null, currentMatchId = "match-A"),
        )
    }

    // ---------- A4：锁必须绑在「当前这场」上，而不是一个裸布尔 ----------
    //
    // 只读审计 review/audit-guards.md A4：这里原来写的是
    //     var lockedMatchId: String? = "match-A"
    //     lockedMatchId = "match-B"
    //     assertTrue(PlayerLock.isLocked(lockedMatchId, "match-B"))
    //     assertFalse(PlayerLock.isLocked(lockedMatchId, "match-A"))
    // 输入是测试自己刚赋值的局部变量，两条断言只是上面「切到另一场比赛时锁定自动失效」
    // 那段 isLocked 语义的**复述** —— 没有任何生产切换逻辑参与，
    // 生产改回裸布尔锁照样绿（这正是它守不住的 bug）。
    //
    // ⚠️ 审计建议的「用 MainViewModel 夹具驱动（vm.playMatch(m1/m2)）」在本仓库**不成立**：
    // 锁定状态住在 NbaLiveApp 的 composable 里
    // （`var lockedMatchId by remember { mutableStateOf<String?>(null) }`，NbaLiveApp.kt:187），
    // MainViewModel 里根本没有锁定状态 —— 照建议写只能变成「测试自己造一台状态机」，
    // 又是一次自证。能真正变红的判据是**扫生产源码的接线**：
    // 判定必须传当前比赛 id、状态必须存 id、切/解锁必须作用于当前这场。

    @Test
    fun `切场锁定必须由生产接线保证 而不是测试自己改局部变量`() {
        val files = productionFiles()
        // 非空性锚点：下面那条是「零违规」断言，扫不到源码时会静默全绿
        assertTrue(
            "只扫到 ${files.size} 个生产 kt 文件（应 ≥30，cwd=${System.getProperty("user.dir")}）—— " +
                "锁定接线守卫会静默全绿",
            files.size >= 30,
        )
        val raw = files.firstOrNull { it.name == "NbaLiveApp.kt" }
            ?: error("找不到 NbaLiveApp.kt —— 锁定接线的唯一锚点，守卫会静默全绿")

        val violations = lockWiringViolations(raw.readText())
        assertTrue(
            "锁定接线不合格（review/audit-guards.md A4）：\n" +
                violations.joinToString("\n").prependIndent("  ") +
                "\n\n锁必须绑定「锁住哪一场」并按**当前比赛 id** 判定。退回裸布尔锁或常量比赛 id 时，" +
                "「同时在播」面板切场后新的一场会继续处于锁定态 —— " +
                "用户看着新比赛、画面却全没了且点不动。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `锁定接线判据自检 裸布尔与常量比赛 id 必须能抓到`() {
        // 防「永远全绿的摆设」（同 GuardHygieneTest 的做法）：把内存样本喂给
        // **真正参与生产判定**的那个函数，而不是把判据在测试里复述一遍。
        val compliant = """
            var lockedMatchId by remember { mutableStateOf<String?>(null) }
            val playerLocked = PlayerLock.isLocked(lockedMatchId, playerState.match?.id)
            onToggleLock = {
                val id = playerState.match?.id
                lockedMatchId = if (playerLocked) null else id
            }
        """.trimIndent()
        assertTrue(
            "判据误报了正确的接线：\n" + lockWiringViolations(compliant).joinToString("\n"),
            lockWiringViolations(compliant).isEmpty(),
        )

        // 违规样本 1：裸布尔锁（历史 bug 的形态）
        val boolLock = """
            var locked by remember { mutableStateOf(false) }
            val playerLocked = locked
        """.trimIndent()
        assertTrue(
            "裸布尔锁必须能抓到（否则「切场后新的一场可以被正常锁定」依旧没人守）",
            lockWiringViolations(boolLock).isNotEmpty(),
        )

        // 违规样本 2：判定绑定常量比赛 id
        val constantMatch = """
            var lockedMatchId by remember { mutableStateOf<String?>(null) }
            val playerLocked = PlayerLock.isLocked(lockedMatchId, "match-A")
        """.trimIndent()
        assertTrue(
            "判定绑定常量比赛 id（切场就漏判）必须能抓到",
            lockWiringViolations(constantMatch).isNotEmpty(),
        )

        // 违规样本 3：切/解锁写死场次
        val wrongToggle = """
            var lockedMatchId by remember { mutableStateOf<String?>(null) }
            val playerLocked = PlayerLock.isLocked(lockedMatchId, playerState.match?.id)
            onToggleLock = { lockedMatchId = if (playerLocked) null else "match-B" }
        """.trimIndent()
        assertTrue(
            "切/解锁写死场次必须能抓到",
            lockWiringViolations(wrongToggle).isNotEmpty(),
        )

        // 反向锚点：注释里的正确接线不算命中（剔注释）
        val commentOnly = """
            // var lockedMatchId by remember { mutableStateOf<String?>(null) }
            // val playerLocked = PlayerLock.isLocked(lockedMatchId, playerState.match?.id)
            // lockedMatchId = if (playerLocked) null else id
        """.trimIndent()
        assertTrue(
            "只留注释不算命中（否则删掉实现、留一句说明就照样绿）",
            lockWiringViolations(commentOnly).isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------

    /**
     * 锁定接线判据：生产源码**原始文本** → 违规清单（空 = 达标）。
     *
     * 内部先 [GuardSourceScan.stripNonCode] 剔注释与字符串字面量（铁律①）；
     * 判据全部匹配**调用形状**（铁律②）；找不到锚点直接 [error]（铁律③）。
     */
    private fun lockWiringViolations(rawCode: String): List<String> {
        val code = GuardSourceScan.stripNonCode(rawCode)
        val out = mutableListOf<String>()

        // ① 判定必须把**当前播放的比赛 id** 传进去
        if (!Regex("""PlayerLock\.isLocked\(\s*lockedMatchId\s*,\s*playerState\.match\?\.id\s*\)""")
                .containsMatchIn(code)
        ) {
            out += "① 没有 `PlayerLock.isLocked(lockedMatchId, playerState.match?.id)`：" +
                "锁定判定没有绑定当前播放的比赛（裸布尔或常量 id 都会让切场后继续锁着）"
        }
        // ② 状态本身必须声明成「比赛 id」（String?），不能是布尔
        if (!Regex(
                """\bvar\s+lockedMatchId\s+by\s+remember\s*\{\s*mutableStateOf\s*<\s*String\s*\?\s*>\s*\(\s*null\s*\)""",
            ).containsMatchIn(code)
        ) {
            out += "② 锁定状态不是 `var lockedMatchId by remember { mutableStateOf<String?>(null) }`：" +
                "「锁绑定到比赛而非裸布尔」的核心形态就是存 match id（可空 = 没锁）"
        }
        if (Regex("""\bvar\s+locked\w*\s+by\s+remember\s*\{\s*mutableStateOf\s*(?:<[^>]*>)?\s*\(\s*false\s*\)""")
                .containsMatchIn(code)
        ) {
            out += "②' 锁定状态退回成了裸布尔（`mutableStateOf(false)`）：" +
                "布尔锁会跟着 route 漏到新的一场"
        }
        // ③ 切/解锁作用于**当前这场**：先取 id，再按当前锁定态写回
        if (!Regex("""val\s+id\s*=\s*playerState\.match\?\.id""").containsMatchIn(code)) {
            out += "③ 切/解锁没有先取当前比赛 id（`val id = playerState.match?.id`）"
        }
        if (!Regex("""lockedMatchId\s*=\s*if\s*\(\s*playerLocked\s*\)\s*null\s+else\s+id\b""")
                .containsMatchIn(code)
        ) {
            out += "④ 切/解锁没有写成 `lockedMatchId = if (playerLocked) null else id`"
        }
        return out
    }

    private fun productionFiles(): List<File> {
        val cwd = System.getProperty("user.dir") ?: "."
        val roots = listOf(
            File(cwd, "src/main/java/com/nbalive/app"),
            File(cwd, "app/src/main/java/com/nbalive/app"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到生产源码目录，cwd=$cwd，候选=${roots.map { it.path }} —— 守卫会静默全绿")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
