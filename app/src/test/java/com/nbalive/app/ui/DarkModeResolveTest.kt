package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「深色模式偏好为 null 时到底怎么解释」的纯判据（review/03-ui.md P2-16）。
 *
 * ## 修之前是什么样
 *
 * MainActivity 与 NbaLiveApp 各写一遍 `val darkMode = darkPref ?: true`，
 * 而 `AppPrefs` 的注释宣称「null = 跟随系统」—— 注释与两处实现**相反**。
 *
 * ## 修法
 *
 * 规则收敛成 [resolveDarkMode] 一处实现，两个消费方都调它。
 * 本文件的断言钉的是**产品规则本身**，不是实现细节：
 * 本应用是深色优先设计，null（从未手动设置过）一律按**深色**，刻意不跟随系统。
 * 若有人把规则改写成跟随系统，这里必须变红 —— 那是产品行为变更，得走评审，
 * 不能靠"两处实现各自漂移"悄悄发生。
 *
 * ## 顺带钉住 P2-31 的语义前提
 *
 * 「没有那个 key」必须还原成 null（= 从未设置过），**不能**拿
 * `getBoolean(key, true)` 的默认值冒充"用户设置了深色" —— [darkModeFromStorage]。
 */
class DarkModeResolveTest {

    @Test
    fun `手动设置优先于一切`() {
        assertTrue("用户手动选了深色就必须是深色", resolveDarkMode(pref = true))
        assertFalse("用户手动选了浅色就必须是浅色", resolveDarkMode(pref = false))
    }

    @Test
    fun `null 是未手动设置过 一律按深色`() {
        assertTrue(
            "从未手动设置过（null）时必须按深色处理：本应用是深色优先设计。" +
                "这条变红说明有人把默认值改成了「跟随系统」——那是产品行为变更（P2-16）。",
            resolveDarkMode(pref = null),
        )
        assertEquals(
            "深色优先的默认值只允许有一个来源：resolveDarkMode(null) 必须等于 DARK_FIRST_WHEN_UNSET",
            DARK_FIRST_WHEN_UNSET,
            resolveDarkMode(pref = null),
        )
    }

    @Test
    fun `SharedPreferences 里没有 key 时必须还原成 null 而不是默认值`() {
        assertNull(
            "没有 dark_mode 这个 key = 用户从未设置过 = null。" +
                "用 getBoolean(key, true) 的默认值冒充用户设置，会把「没设置过」和「设置了深色」" +
                "混成一谈，进而让「跟随系统」之类的将来语义无从实现（P2-31/P2-16）。",
            darkModeFromStorage(hasValue = false, stored = true),
        )
        assertEquals(
            "key 存在且存的是 true，才是用户真的选了深色",
            true,
            darkModeFromStorage(hasValue = true, stored = true),
        )
        assertEquals(
            "key 存在且存的是 false，是用户真的选了浅色",
            false,
            darkModeFromStorage(hasValue = true, stored = false),
        )
    }
}