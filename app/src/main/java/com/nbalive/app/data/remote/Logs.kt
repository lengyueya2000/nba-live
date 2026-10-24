package com.nbalive.app.data.remote

import android.util.Log
import com.nbalive.app.BuildConfig

/**
 * 只在 debug 包里输出的详细日志。
 *
 * 数据层大量日志会打**完整源站 URL**（含 m3u8 的 auth_key 签名、播放页 id）。
 * release 包把这些直接写进 logcat，等于把解析链路的细节白送给任何人
 * （adb logcat 不需要任何权限就能读）。
 *
 * Log.w / Log.e 仍然常开：故障必须能在现场诊断，而它们只打状态码和简短原因。
 */
internal fun debugLog(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.d(tag, message)
}

/**
 * URL 脱敏：保留 scheme + host + path，**抹掉 query 与 fragment**。
 *
 * 源站的播放地址把签名放在 query 里（`auth_key`、`sign`、`token`、`t`…），
 * 那个签名就是"谁能播这条流"的凭证，直接写进 logcat 等于公开它。
 * 保留 path 是为了还能定位是哪个页面/哪条线路出了问题。
 *
 * @return 形如 `https://cdn.example.com/live/abc.m3u8?<redacted>`
 */
internal fun redactUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return "<invalid-url>"
    val prefix = url.substring(0, schemeEnd + 3)
    val rest = url.substring(schemeEnd + 3)
    val queryStart = rest.indexOf('?')
    val fragmentStart = rest.indexOf('#')
    // query 与 fragment 都要抹掉，取最早的那个
    val cut = listOf(queryStart, fragmentStart).filter { it >= 0 }.minOrNull()
        ?: return url
    val base = prefix + rest.substring(0, cut)
    // path 本身也可能带签名（源站把 id 拼在路径里），但没有 query 就没有时间戳签名，
    // 保留它对排障有用。query/fragment 一律抹掉。
    return if (queryStart >= 0 && queryStart == cut) "$base?<redacted>" else "$base#<redacted>"
}
