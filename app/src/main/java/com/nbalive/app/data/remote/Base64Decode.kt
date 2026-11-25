package com.nbalive.app.data.remote

/**
 * 进程内**唯一**的 Base64 解码实现（2026-11-15 收敛，2026-11-25 去掉噪声分支）。
 *
 * ## 为什么要有它
 *
 * 原来只有 [com.nbalive.app.ui.screens.PlayerPageResolver] 里一份私有实现
 * （解 `playerUrlBase64`）。抽到 `data/remote` 后，`data` 层不依赖 `ui`，方向是对的。
 * 曾为 88看球的"带噪声 base64"加过 [decodeWithNoise]；该源移除后已删除，
 * 现在只保留严格解码 —— 不留没有消费者的死代码。
 *
 * ## 两条刻意的约束（与抽取前逐字一致）
 *
 *  - **不用** `java.util.Base64`：API 26+，本项目 minSdk 24；
 *  - **不用** `android.util.Base64`：JVM 单测里没有 Android 运行时，无法覆盖。
 */
object Base64Decode {

    /**
     * 严格解码：非法字符返回 null，空白被容忍，`=` 之前收尾。
     *
     * @return 解码后的 UTF-8 文本；输入含非法字符或长度不合 4 倍时返回 null。
     *   空输入返回空串（与抽取前的 `PlayerPageResolver.decodeBase64` 一致）。
     */
    fun decode(input: String): String? {
        val cleaned = input.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.length % 4 != 0) return null
        val bytes = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
            if (c == '=') break
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes.write((buffer shr bits) and 0xFF)
            }
        }
        return runCatching { bytes.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
    }
}
