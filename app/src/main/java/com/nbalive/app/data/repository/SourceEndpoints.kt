package com.nbalive.app.data.repository

/**
 * 源站端点**候选列表**（review/02-data.md P1-9）。
 *
 * ## 要解决什么
 *
 * 免费盗播站换域名是常态 —— `.forensics/` 抓包里页面自身引用的是
 * `www.jrszb444.com` / `www.jrszb222.com`，而 App 写死的是 `jrszbt.com`：
 * **同一个站点的多个域名并存**，轮换是真实发生的，不是假想。
 * 旧实现是单值常量：域名失效后重试 3 次就放弃，
 * 用户只看到"场次变少了"（叠加单源失败静默，完全不知情）。
 *
 * 所以从「一个域名写死」改成「候选列表按顺序试」。
 *
 * ## 为什么不永久拉黑
 *
 * `attemptOrder` 只是**本轮**跳过失效 host：全部失效时从头再来。
 * 限流窗口（403/429）是会过去的，永久黑名单只是把暂时故障变成长期故障 ——
 * 那是 review/01-P1-5「15 秒看门狗永久拉黑健康线路」的同款错误。
 *
 * ## PLAY_HOST 的特殊性
 *
 * JRKAN 播放域名写死在 `JrkanParser.PLAY_HOST`（历史已换过两次），
 * 展开线路时也是单值拼接。这里提供候选列表，供展开时按顺序试
 * （见 `MatchRepository.loadSignals`）。
 * ⚠️ 播放地址有一部分来自源站 JS（`getPlayUrl`），App 无从得知，
 * 只能由这里维护候选。
 */
object SourceEndpoints {

    /**
     * JRS 列表页候选（主域名第一，镜像域名随后）。
     *
     * 镜像域名来自源站自身引用（`.forensics/` 实测），
     * 轮换时按顺序顶上。
     */
    val JRS_LIST: List<String> = listOf(
        "https://jrszbt.com/",
        "https://www.jrszb444.com/",
        "https://www.jrszb222.com/",
    )

    /**
     * JRKAN 列表 `index.js` 候选。
     *
     * 目前是 OSS 加速域名，单一；留成列表是为了将来镜像换源时
     * 不用再改常量类型。
     */
    val JRKAN_LIST_JS: List<String> = listOf(
        "https://im-imgs-bucket.oss-accelerate.aliyuncs.com/index.js?t_5",
    )

    /** JRKAN 播放主机候选（`JrkanParser.PLAY_HOST` 历史已换过两次）。 */
    val PLAY_HOSTS: List<String> = listOf(
        "http://play.sportsteam356.com",
        "http://play.sportsteam368.com",
    )

    /**
     * 本轮该按什么顺序尝试这些候选。
     *
     * @param failedHosts 本轮已失效的 host（小写）。
     *   非空时把命中的候选挪到队尾 —— 不删除：全部失效时从头再来。
     */
    fun attemptOrder(candidates: List<String>, failedHosts: Set<String>): List<String> {
        if (failedHosts.isEmpty()) return candidates
        val (failed, fresh) = candidates.partition { hostOf(it) in failedHosts }
        if (fresh.isEmpty()) {
            // 全部失效：重新从头开始（见类说明「不永久拉黑」）
            return candidates
        }
        return fresh + failed
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
}
