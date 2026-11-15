package com.nbalive.app.data.remote

/**
 * 进程内**唯一**的 Base64 解码实现（2026-11-15 第三源接入时收敛）。
 *
 * ## 为什么要有它
 *
 * 原来只有 [com.nbalive.app.ui.screens.PlayerPageResolver] 里一份私有实现
 * （解 `playerUrlBase64`）。接入 88看球后，`K88Parser` 也要解 base64
 * （它的线路接口把 JSON 藏在一个带噪声的 base64 串里）——
 * 同一份解码规则写两遍，改一处漏一处（本仓库 review/optimization-review.md D15
 * 给 JSON 取值器立的同一条纪律）。所以把它抽到 `data/remote` 共享：
 * `data` 层不依赖 `ui`，方向也是对的。
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

    /**
     * **容错搜索**解码：在 [payload] 的所有 `(起偏移, 尾裁剪)` 组合里找第一个
     * 能严格解码且以 [mustStartWith] 开头的结果。
     *
     * 88看球把 JSON 编成 base64 后在**头尾各加了几个噪声字符**（实测头 6 位数字 +
     * 长度随响应变化，尾部 1~2 位），补 `=` 也不规范，所以不能整串直接解。
     * 搜索范围有上界（前 [MAX_HEAD_NOISE] 个偏移 × 后 [MAX_TAIL_NOISE] 个裁剪），
     * 且 base64 解码是 O(n) —— 单次调用的最坏开销可控，不会随响应大小退化。
     *
     * @param mustStartWith 期望明文的首字符（JSON 用 `{`）：用它筛掉"碰巧能解码
     *   但不是目标内容"的假命中。
     * @return 解出的明文；任何组合都不成立时返回 null（调用方按"解析失败"处理）。
     */
    fun decodeWithNoise(
        payload: String,
        mustStartWith: Char,
        maxHeadNoise: Int = MAX_HEAD_NOISE,
        maxTailNoise: Int = MAX_TAIL_NOISE,
    ): String? {
        val text = payload.trim()
        if (text.isEmpty()) return null
        for (head in 0..minOf(maxHeadNoise, text.length - 1)) {
            for (tail in 0..minOf(maxTailNoise, text.length - head - 1)) {
                val slice = if (tail == 0) text.substring(head) else text.substring(head, text.length - tail)
                if (slice.isEmpty()) continue
                // padding：噪声导致长度不合 4 倍时补齐（= 只是补位，不参与内容）
                val padded = slice + "=".repeat((4 - slice.length % 4) % 4)
                val decoded = decode(padded) ?: continue
                if (decoded.firstOrNull() == mustStartWith) return decoded
            }
        }
        return null
    }

    /** 头噪声搜索上界：8 留一倍余量。 */
    const val MAX_HEAD_NOISE = 8

    /** 尾噪声搜索上界：4 留一倍余量。 */
    const val MAX_TAIL_NOISE = 4
}
