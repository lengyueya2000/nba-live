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

/**
 * 安全打印异常：只输出「异常类型 + 脱敏后的 message」，不交给 `Log` 打堆栈。
 *
 * ⚠️ 为什么不能直接把 `Throwable` 交给 `Log.w(tag, msg, t)`（review/02-P1-5、04-P1-1）：
 * `Log.w` 带 throwable 时会打印完整堆栈，**而每一帧的异常 message 都是原文**。
 * `HttpClient` 的异常 message 内嵌完整带 `auth_key` 的 URL（它自己的注释就写着这道防线），
 * `UnknownHostException` / `SSLException` / `IOException` 的 message 同样可能带源站 URL 与查询串。
 * 上层一旦把 throwable 透传给 Log，等于把 `redactUrl` 这道防线整体拆掉，
 * 而且 `Log.w` 在 release 包常开、`adb logcat` 零权限可读。
 *
 * 类型足够定位问题（是 `HttpException` 还是 `ParseException`），
 * message 脱敏后仍保留排障线索，签名凭证不外泄。
 */
internal fun safeLogWarn(tag: String, message: String, t: Throwable) {
    Log.w(tag, "$message (${t.javaClass.simpleName}: ${redactText(t.message ?: t.javaClass.simpleName)})")
}

/**
 * 给用户看的异常简述：**脱敏后的 message**，没有 message 时退回异常类型。
 *
 * ⚠️ 为什么错误提示也要脱敏（review/02-P1-5 的延伸）：
 * 源站 URL 的签名不只会进 logcat，还会被直接拼进 `errorMessage` 显示在界面上。
 * 截图分享、录屏、无障碍读屏都会把签名带出去，而且它对人也没有排障价值。
 */
internal fun safeMessage(t: Throwable): String =
    redactText(t.message).ifBlank { t.javaClass.simpleName }

/**
 * 脱敏任意文本中内嵌的 URL query / fragment。
 *
 * 异常 message、源站 href 这类字符串可能整段就是一个 URL，也可能只在中间夹了一段。
 * [redactUrl] 假定输入以 scheme 开头，不适合这种混合文本，这里按子串处理。
 *
 * 分两类处理，且**互不重叠**：
 * - 绝对 URL（`https?://…`）整体走 [redactUrl]，保留 scheme+host+path；
 * - 其余普通文本里的相对路径（源站 href 常见形态 `/live/555.html?auth_key=…`）
 *   把 `?`/`#` 及其后内容换成 `<redacted>`。
 *
 * ⚠️ 不能先替绝对 URL、再对结果全文跑相对路径的规则：
 * [redactUrl] 的输出自带 `?<redacted>`，二次扫描会把占位符再包一层，
 * 变成 `?<redacted><redacted>`。所以按匹配位置切段，每段只处理一次。
 */
internal fun redactText(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    val out = StringBuilder()
    var last = 0
    for (match in URL_IN_TEXT.findAll(text)) {
        // 匹配之前的普通文本：按相对路径规则脱敏
        out.append(redactRelative(text.substring(last, match.range.first)))
        out.append(redactUrl(match.value))
        last = match.range.last + 1
    }
    out.append(redactRelative(text.substring(last)))
    return out.toString()
}

/** 把文本里每个 `?…` / `#…` 段替换成占位符（用于非绝对 URL 的片段）。 */
private fun redactRelative(chunk: String): String =
    QUERY_FRAGMENT_IN_TEXT.replace(chunk) { match -> match.value.substring(0, 1) + "<redacted>" }

private val URL_IN_TEXT = Regex("""https?://[^\s"'<>()\[\]]+""")
private val QUERY_FRAGMENT_IN_TEXT = Regex("""[?#][^\s"'<>]*""")
