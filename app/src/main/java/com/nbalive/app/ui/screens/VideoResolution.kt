package com.nbalive.app.ui.screens

/**
 * 画质（分辨率）显示的取值逻辑。
 *
 * 两条播放路径的画质来源完全不同：
 *  - **ExoPlayer 直连**：拿得到真实视频宽高（`onVideoSizeChanged`）→ 最准
 *  - **WebView 承载**：App 侧看不到画面，只能从嗅探到的 m3u8 地址里
 *    抠源站标注的分辨率参数（很多源站 URL 带 `1080p`/`720p` 这类标记）
 *
 * 解析不到就返回 null —— 界面上不显示空的画质药丸。
 */
object VideoResolution {

    /**
     * 常见档位（按短边像素）。
     *
     * 播放器一律按**短边**算清晰度：竖屏的 1080×1920 和横屏的 1920×1080
     * 是同一个档位（都是 1080P）。
     */
    private val TIERS = intArrayOf(240, 360, 480, 720, 1080, 1440, 2160, 4320)

    /** 低于这个短边就算"太小"，不显示画质标签 */
    private const val MIN_SHORT_SIDE = 400

    /**
     * 接近档位的容差（比例）。
     *
     * 容器尺寸和实际画面尺寸常常差一点（1280×720 的容器里放 1278×718），
     * 这时显示 "718P" 很难看，应该显示 "720P"。
     * 容差取 5%：718 距 720 只差 0.3%（吸附），而 540 距 480 差 12.5%（不吸附）。
     */
    private const val TIER_TOLERANCE = 0.05

    /**
     * 真实视频宽高 -> 画质标签。
     *
     * @param width 视频宽（横屏时是长边）
     * @param height 视频高
     * @return 如 "1080P"；尺寸无效或太小返回 null
     */
    fun labelOf(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val shortSide = minOf(width, height)
        if (shortSide < MIN_SHORT_SIDE) return null
        // 先看是不是某个常见档位（含容差内吸附）
        TIERS.firstOrNull { withinTolerance(shortSide, it) }?.let { return "${it}P" }
        // 不是常见档位就直接显示实际短边（540P 这种也是真实存在的档位）
        return "${shortSide}P"
    }

    /** 实际值是否落在目标档位的容差内 */
    private fun withinTolerance(value: Int, tier: Int): Boolean {
        val diff = kotlin.math.abs(value - tier)
        return diff <= tier * TIER_TOLERANCE
    }

    /**
     * 从 m3u8 地址里解析画质（WebView 路径专用）。
     *
     * 源站习惯把分辨率写进地址：`live/xxx_1080p.m3u8`、`?quality=720p`、
     * `hd/xxx_480p/playlist.m3u8` 等。
     *
     * ⚠️ 两条路径的判定规则不一样，因为歧义程度不一样：
     *  - **路径**里只认带 `p` 后缀的（`abc_1080p.m3u8`）。
     *    裸数字在路径里更可能是 id 或日期（`live/1080.m3u8`、`live/20241024/`），
     *    认错就会给用户报一个假的画质。
     *  - **query 参数值**可以直接是裸数字（`?hd=720`）——参数名已经说明了它是画质。
     *
     * @return 如 "1080P"；解析不到返回 null
     */
    fun fromStreamUrl(url: String): String? {
        if (url.isBlank()) return null
        // 去掉协议与主机名：主机名里的端口（:1080）不是分辨率
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        val pathAndQuery = afterScheme.substring(slash)
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "")

        // 路径：只认带 p 后缀的
        findTier(path, requireP = true)?.let { return it }

        // query：参数值可以是裸数字
        if (query.isNotEmpty()) {
            for (param in query.split('&')) {
                val value = param.substringAfter('=', "")
                if (value.isEmpty()) continue
                tierOf(value, requireP = false)?.let { return it }
            }
        }
        return null
    }

    /** 在一段文本里找第一个已知档位 */
    private fun findTier(text: String, requireP: Boolean): String? =
        Regex("""(?<![0-9])(2160|1440|1080|720|480|360|240)(p|P)?(?![0-9])""")
            .find(text)
            ?.let { tierOf(it.value, requireP) }

    /** 把匹配到的片段转成档位标签；requireP 时必须带 p 后缀 */
    private fun tierOf(raw: String, requireP: Boolean): String? {
        val hasP = raw.endsWith("p") || raw.endsWith("P")
        if (requireP && !hasP) return null
        val num = raw.trimEnd('p', 'P')
        if (num.toIntOrNull() == null) return null
        if (num !in TIERS.map { it.toString() }) return null
        return "${num}P"
    }
}
