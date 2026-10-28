package com.nbalive.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源站域名候选列表的选取（review/02-data.md P1-9）。
 *
 * ## 要解决什么
 *
 * 免费盗播站换域名是常态 —— `.forensics/` 抓包里页面自身引用的是
 * `www.jrszb444.com` / `www.jrszb222.com`，而 App 写死的是 `jrszbt.com`，
 * **同一个站点的多个域名并存**，轮换是真实发生的，不是假想。
 * 代码里域名全是单值常量：`jrszbt.com` 失效后重试 3 次就放弃，
 * 用户只看到"场次变少了"（叠加单源失败静默，完全不知情）。
 * JRS 与 JRKAN 同时挂时才报"两个数据源都没有返回赛事"。
 *
 * ## 规则
 *
 * 1. 候选按声明顺序尝试，主域名永远第一；
 * 2. 本轮失效的 host 跳过，直接试下一个；
 * 3. **全部失效时从头再来** —— 不能把所有候选永久拉黑，
 *    那是"15 秒看门狗永久拉黑健康线路"（review/01-P1-5）的同款错误：
 *    限流窗口是会过去的，永久黑名单只是把暂时故障变成长期故障。
 */
class SourceEndpointsTest {

    @Test
    fun `候选按声明顺序 主域名第一`() {
        assertEquals(
            "https://jrszbt.com/",
            SourceEndpoints.attemptOrder(
                SourceEndpoints.JRS_LIST,
                failedHosts = emptySet(),
            ).first(),
        )
        assertEquals(
            "无失败记录时顺序就是声明顺序",
            SourceEndpoints.JRS_LIST,
            SourceEndpoints.attemptOrder(SourceEndpoints.JRS_LIST, failedHosts = emptySet()),
        )
    }

    @Test
    fun `失效的 host 被跳过`() {
        val order = SourceEndpoints.attemptOrder(
            SourceEndpoints.JRS_LIST,
            failedHosts = setOf("jrszbt.com"),
        )
        val first = SourceEndpoints.JRS_LIST.first()
        // 失效的不是删除，而是挪到队尾：限流窗口过去后它还能再被尝试
        assertEquals(SourceEndpoints.JRS_LIST.drop(1) + first, order)
        assertEquals(
            "主域名失效后镜像域名要顶上，而不是整源放弃",
            SourceEndpoints.JRS_LIST.size,
            order.size,
        )
        assertEquals("www.jrszb444.com", hostOf(order.first()))
    }

    @Test
    fun `全部失效时从头再来 不永久拉黑`() {
        val all = SourceEndpoints.JRS_LIST.map { hostOf(it) }.toSet()
        val order = SourceEndpoints.attemptOrder(SourceEndpoints.JRS_LIST, failedHosts = all)
        assertEquals(
            "全部拉黑后必须重新尝试（限流窗口会过去，永久黑名单只是把暂时故障变长期）",
            SourceEndpoints.JRS_LIST,
            order,
        )
    }

    @Test
    fun `候选列表不能为空`() {
        // 主域名写死成单值常量时这里就会踩空：候选列表就是这道改版防线
        //
        // ⚠️ 不能用 Kotlin 的 `assert(...)`（审计「未验证的怀疑 #2」）：
        // 它只在 JVM `-ea` 打开时才生效，一旦构建里出现
        // `enableAssertions = false`（或换执行器），这 3 行会**静默消失**，
        // 而"全绿"的报告看不出区别。JUnit 断言才是硬失败。
        assertTrue("JRS 候选列表不能为空", SourceEndpoints.JRS_LIST.isNotEmpty())
        assertTrue("JRKAN 候选列表不能为空", SourceEndpoints.JRKAN_LIST_JS.isNotEmpty())
        assertTrue("播放域名列表不能为空", SourceEndpoints.PLAY_HOSTS.isNotEmpty())
        assertEquals("主域名必须排第一", "jrszbt.com", hostOf(SourceEndpoints.JRS_LIST.first()))
    }

    @Test
    fun `候选 URL 都是合法 http(s) 地址`() {
        val all = SourceEndpoints.JRS_LIST + SourceEndpoints.JRKAN_LIST_JS + SourceEndpoints.PLAY_HOSTS
        for (url in all) {
            assertTrue(
                "候选必须是合法 http(s) URL：$url",
                url.startsWith("https://") || url.startsWith("http://"),
            )
            assertNotEquals(hostOf(url), "")
        }
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host.orEmpty() }
        .getOrDefault("")
        .lowercase()
}
