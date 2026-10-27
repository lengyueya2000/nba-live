package com.nbalive.app.data.repository

import com.nbalive.app.data.remote.JrkanParser

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
 * ## 消费点（⚠️ 声明了就必须有人用）
 *
 * 候选列表最怕的是"声明得漂亮但没人消费"—— 测试只能证明列表本身合法，
 * 证明不了有人真的按它换域名，那只剩「已经有兜底」的错觉。
 * 本对象的三个候选各自有明确消费点，由 `SourceEndpointsWiringTest` 扫源码钉住：
 *  - [JRS_LIST] / [JRKAN_LIST_JS] → `MatchRepository.runSource`
 *  - [PLAY_HOSTS] → `MatchRepository.expandJrkanPlayPage`
 *  - [jrsDetailUrls] → `MatchRepository.fetchJrsDetail`
 *
 * 列表页与详情页、播放页共享同一份"哪个 host 上一轮失败过"的记忆
 * （`MatchRepository.listFailedHosts`），失败 host 会被挪到候选队尾。
 *
 * ## 为什么不永久拉黑
 *
 * `attemptOrder` 只是**本轮**跳过失效 host：全部失效时从头再来。
 * 限流窗口（403/429）是会过去的，永久黑名单只是把暂时故障变成长期故障 ——
 * 那是 review/01-P1-5「15 秒看门狗永久拉黑健康线路」的同款错误。
 *
 * ## PLAY_HOST 的特殊性
 *
 * JRKAN 播放域名放在 [PLAY_HOSTS]，**首位直接取自
 * `JrkanParser.PLAY_HOST`**（而不是两处各写一份字符串）：
 * 两处各写一份迟早在改动时漂移 —— 候选列表更新了、解析器还在拼旧域名，
 * 展开出的线路地址指向一个没人用的 host，且不报错。
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

    /**
     * JRKAN 播放主机候选。
     *
     * 首位**[同源](https://en.wikipedia.org/wiki/Single_source_of_truth)
     * 取自 `JrkanParser.PLAY_HOST`**：解析器拼播放页时用的就是它，
     * 两处引用同一个值才不会漂移。
     * 历史已换过两次域名，换的时候只需在这里补一个候选，
     * 解析器与展开逻辑都跟着走。
     */
    val PLAY_HOSTS: List<String> = listOf(
        JrkanParser.PLAY_HOST,
        "http://play.sportsteam368.com",
    )

    /**
     * JRS 详情页 URL 候选（`https://<host>/live/<id>.html`）。
     *
     * 详情页挂在**同一个站点的不同域名**下，所以直接由 [JRS_LIST] 派生，
     * 而不是另写一份 URL 模板：写死模板的旧实现有两处副本
     * （`fetchJrsDetail` 与 `invalidateDetailCache`，review/02-data.md P2-6），
     * 改一处漏一处时「刷新线路」会静默失效 —— 清了 A 的缓存、
     * 读的是 B 的缓存，用户点刷新却拿到旧正文。
     *
     * @param detailId 详情页数字 id（如 `555`）
     */
    fun jrsDetailUrls(detailId: String): List<String> =
        JRS_LIST.map { origin -> origin.trimEnd('/') + "/live/$detailId.html" }

    /**
     * 某个 JRKAN 播放页在当前主机不可达时，可以改试的候选 URL。
     *
     * 路径与 query 原样保留，只换 host —— 播放页结构在各镜像上一致，
     * 换的只是域名。
     *
     * @param failedHosts 本轮已失效的 host（小写），命中的候选挪到队尾
     */
    fun playPageCandidates(pageUrl: String, failedHosts: Set<String> = emptySet()): List<String> {
        val path = pathOf(pageUrl) ?: return listOf(pageUrl)
        // 先保留原始地址（列表页给的 host 可能是历史遗留的可用域名），
        // 再补上候选主机 —— 两处去重，避免同一个 host 打两次。
        val urls = (listOf(pageUrl) + PLAY_HOSTS.map { it.trimEnd('/') + path }).distinct()
        return attemptOrder(urls, failedHosts)
    }

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

    /**
     * URL 的 origin（`scheme://host[:port]`），用于给解析器拼播放页地址。
     *
     * 展开出来的线路必须指向**真正取到正文的那个 host**：
     * 若固定拼候选首位，主域名死了的时候线路地址还是死域名，
     * 播放器打开就是错误页 —— 换域名这件事等于没做。
     */
    fun originOf(url: String): String = runCatching {
        val u = java.net.URI(url)
        val scheme = u.scheme ?: return@runCatching ""
        val host = u.host ?: return@runCatching ""
        val port = if (u.port > 0) ":${u.port}" else ""
        "$scheme://$host$port"
    }.getOrDefault("")

    /** 路径 + query（换 host 时保留的部分）；无法解析时返回 null。 */
    private fun pathOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val path = u.path ?: return@runCatching null
        if (path.isEmpty()) null else path + (u.query?.let { "?$it" } ?: "")
    }.getOrNull()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
}