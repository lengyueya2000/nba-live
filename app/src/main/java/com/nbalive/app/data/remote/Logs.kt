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
