package com.nbalive.app.data.remote

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 统一网络客户端：注入源站所需的 Referer / UA，避免被拒绝访问。
 */
class HttpClient(
    /** 暴露给播放器复用，共享连接池与 DNS 缓存，显著降低首帧耗时 */
    val client: OkHttpClient = defaultClient(),
) {
    /**
     * 试探用客户端：连接超时更短。
     *
     * 播放地址有多个候选（反混淆版 / 原文版），要挨个试。
     * 第一个候选若是坏域名，用默认 15s 连接超时会把"换个地址"拖成十几秒等待。
     * newBuilder() 会复用同一个连接池与调度器，只是把超时改短。
     */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 挂起 GET。协程取消时同步取消底层 Call——
     * 否则用户快速切线路时，被放弃的解析请求会占着 IO 线程和 socket 到超时为止。
     *
     * @param fastFail 候选地址试探用：连接/读取超时更短
     * @param onStatus 非 2xx 时把状态码回调出去（用于区分 403 限流 / 404 下线 / 5xx 故障）
     */
    suspend fun get(
        url: String,
        referer: String? = null,
        fastFail: Boolean = false,
        onStatus: (Int) -> Unit = {},
    ): String? = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder().url(url).get()
        builder.header("User-Agent", USER_AGENT)
        builder.header("Accept", "*/*")
        builder.header("Accept-Language", "zh-CN,zh;q=0.9")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        val call = (if (fastFail) probeClient else client).newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "GET failed: $url (${e.message})")
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        // 状态码要留在日志里：403（Referer 被拒/限流）和 404（线路下线）处置完全不同
                        Log.w(TAG, "GET ${resp.code}: $url")
                        runCatching { onStatus(resp.code) }
                        if (cont.isActive) cont.resume(null)
                        return
                    }
                    val body = runCatching { resp.body?.string() }.getOrNull()
                    if (cont.isActive) cont.resume(body)
                }
            }
        })
    }

    /**
     * 带状态码的 GET：调用方能区分"源站限流（403/429）"和"网络不通"，
     * 从而决定要不要重试。详见 [FetchResult]。
     */
    suspend fun fetch(
        url: String,
        referer: String? = null,
        fastFail: Boolean = false,
    ): FetchResult {
        var status = 0
        val body = get(url, referer, fastFail) { code -> status = code }
        return when {
            body != null -> FetchResult.Ok(body)
            // 403/429 是源站明确拒绝：再打只会加剧限流
            status == 403 || status == 429 -> FetchResult.Rejected(status)
            status > 0 -> FetchResult.Unreachable("HTTP $status")
            else -> FetchResult.Unreachable(null)
        }
    }

    /** 同源 Referer：从 url 推出 scheme://host[:port]/ */
    fun refererOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        val port = if (u.port > 0 && !isDefaultPort(scheme, u.port)) ":${u.port}" else ""
        "$scheme://$host$port/"
    }.getOrNull()

    private fun isDefaultPort(scheme: String?, port: Int): Boolean =
        (scheme == "http" && port == 80) || (scheme == "https" && port == 443)

    companion object {
        private const val TAG = "HttpClient"

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 全局共享实例：解析与播放共用同一连接池 */
        val shared: HttpClient by lazy { HttpClient() }

        /**
         * 把相对地址补成绝对地址（纯函数，不需要实例）。
         *
         * 队徽这类资源当前源站给的是绝对 URL，但上游随时可能改成相对路径——
         * 那时 Coil 直接拿 "/upload/x.png" 去加载会失败，图标全空。
         * 这里按源站 origin 补齐。
         */
        fun absUrl(raw: String, origin: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            if (value.startsWith("//")) return "https:$value"
            if (value.startsWith("data:")) return value
            val base = origin.trimEnd('/')
            return if (value.startsWith("/")) "$base$value" else "$base/$value"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
