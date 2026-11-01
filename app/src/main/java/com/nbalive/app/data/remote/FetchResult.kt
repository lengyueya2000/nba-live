package com.nbalive.app.data.remote

/**
 * 一次 HTTP 拉取的结果。
 *
 * 为什么要区分"拒绝"和"网络不通"：
 *
 * 早先 [HttpClient.get] 对所有失败都返回 null，仓库层一律按"瞬时抖动"重试 3 次。
 * 但 403/429 是源站**明确拒绝**（限流/防盗链），再打 3 次只会让限流窗口越拖越长，
 * 而且退避只有 400ms/800ms，等于在限流期间自我加压。
 *
 * 现在把失败分开：
 *  - [Rejected]：源站明确拒绝（403 / 429 等）→ 调用方**不要重试**
 *  - [NotFound]：资源不存在（404 / 410）→ 不重试，**也不要把 host 记成"挂了"**（见下）
 *  - [Unreachable]：网络层失败（超时、DNS 抖动、连接被拒）→ 值得重试
 *
 * ## 为什么要单独一类 [NotFound]（review/optimization-review.md D3 / opt/data.md D3）
 *
 * 旧实现把所有非 403/429 的非 2xx 都归进 [Unreachable]，而 [Unreachable] 的定义是
 * "网络层失败，值得重试" —— 仓库层据此把 host 登记进"上一轮失败过"的表，
 * 下一轮直接把**主域名**挪到候选队尾。可是 404 说的是"这个页面没了"，
 * 跟"这个域名挂了"是两回事：实测源站对**已结束场次**的播放页就返回 HTTP 404，
 * 单场详情页 404 一次，就能让整个会话的列表/详情/播放全部先敲镜像
 * （镜像首轮还要跑满 3 次重试，与整体 deadline 叠加就是空列表）。
 * 所以 404/410 必须既能"不重试"，也能"不登记 host 失败"。
 */
sealed interface FetchResult {

    /** 拉取成功（body 可能为空字符串） */
    data class Ok(val body: String) : FetchResult

    /** 源站明确拒绝：403 / 429 等，不应重试 */
    data class Rejected(val code: Int) : FetchResult

    /**
     * 资源不存在：404 / 410。
     *
     * 既不重试（同一地址再打还是 404），也不登记 host 失败
     * （域名是好的，只是这个页面没了）。
     */
    data class NotFound(val code: Int) : FetchResult

    /** 网络层失败：超时、DNS 抖动、连接被重置，可以重试 */
    data class Unreachable(val reason: String?) : FetchResult
}
