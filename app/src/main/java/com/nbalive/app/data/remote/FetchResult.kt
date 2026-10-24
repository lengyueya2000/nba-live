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
 * 现在把两类失败分开：
 *  - [Rejected]：源站明确拒绝（403 / 429 等）→ 调用方**不要重试**
 *  - [Unreachable]：网络层失败（超时、DNS 抖动、连接被拒）→ 值得重试
 */
sealed interface FetchResult {

    /** 拉取成功（body 可能为空字符串） */
    data class Ok(val body: String) : FetchResult

    /** 源站明确拒绝：403 / 429 等，不应重试 */
    data class Rejected(val code: Int) : FetchResult

    /** 网络层失败：超时、DNS 抖动、连接被重置，可以重试 */
    data class Unreachable(val reason: String?) : FetchResult
}
