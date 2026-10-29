package com.nbalive.app.data.remote

import com.nbalive.app.data.model.isStaticallyResolvablePlayPage

/** 播放页 sub_playlist 里解析出的一条线路（还没决定要不要展示、叫什么名）。 */
data class RawChannel(
    /** 播放页相对路径，如 `/play/sm.html?id=439&id2=` */
    val path: String,
    /** 源站给的原始线路名，如「主播解说①」「高清直播④」 */
    val rawLabel: String,
)

/**
 * 线路准入判据：源站播放页里的线路，哪些最终进入界面、叫什么名。
 *
 * ## 两条规则（缺一不可）
 *
 * **规则 1 —— 产品要求：名字涉及主播/解说等的线路，"界面不呈现其名义"。**
 * 这一类是主播个人推流，早先的实现直接**删除**整条线路（见
 * [EXCLUDED_KEYWORDS]）。
 *
 * **规则 2 —— 不黑屏要求：规则 1 不允许把列表过滤成"一条可直连线路都不剩"。**
 * 2026-10-29 实测的线上黑屏就是规则 1 的副作用：某场 NBA 的 7 条线路里，
 * 只有 3 条 `/play/sm.html` 能被静态解析成直连 HLS，而它们恰好叫
 * 「主播解说①②③」—— 全被删掉，只剩 vxt / kbs / mgxl / pao 四条只能走 WebView
 * 的线路。结果是 App 连一次直连机会都没有：
 *
 *  - 直连（ExoPlayer）是唯一在当前网络下**实测能出画面**的路径
 *    （master/变体/分片全 200，分片 `video/mp2t` 首字节 0x47）；
 *  - WebView 那条链在这些页面上一条媒体请求都不发（页内 JS 拼出的播放器页
 *    没有跑起来），用户看到的就是视频区纯黑、控件还在、没有声音。
 *
 * 所以规则 2 把"被名字删掉的可直连线路"以**中性名**请回来
 * （原始名不落进 [admit] 的返回值 → 界面永远不会出现主播名义）。
 * "请回来"只在**规则 1 真的清零了直连线路**时发生，是补偿而不是放开过滤。
 *
 * ## 为什么规则 2 是"条件生效"而不是"一律保留改名"
 *
 * 一律保留会让源站里的「美女」「聊天」类线路也进列表（用户明确不要）。
 * 条件生效的精确定义是：**只有**当过滤结果里一条可直连线路都没有、
 * 且被删掉的线路里确实有可直连的，才补回来 —— 此时用户本来也没得选。
 *
 * 判据全部是纯函数，可单测（见 `JrkanChannelAdmissionTest`）。
 */
object ChannelAdmission {

    /**
     * 触发"隐去名义"的关键词。
     *
     * 早先这份表在 `JrkanParser` 里，语义是"命中即删除"；现在语义变成
     * "命中即隐去名义（必要时才保留）"，所以放在判据旁边，别处不许再抄一份。
     */
    val EXCLUDED_KEYWORDS: List<String> = listOf("主播", "解说", "美女", "聊天")

    /** 线路名是否涉及主播/解说等（命中即不得在界面显示其名义）。 */
    fun isSensitive(label: String): Boolean = EXCLUDED_KEYWORDS.any { label.contains(it) }

    /**
     * 这条线路能否被静态解析成直连 m3u8。
     * ⚠️ 委托给 [isStaticallyResolvablePlayPage]（判据唯一来源），不在这里另写正则。
     */
    fun isDirectPath(path: String): Boolean = isStaticallyResolvablePlayPage(path)

    /**
     * 决定最终线路列表（顺序未定，排序与编号由调用方做）。
     *
     * @return 进入界面的线路；被补偿回来的敏感线路 `rawLabel` 为空串，
     *   调用方的命名函数会给出中性名（"线路N"）。
     */
    fun admit(raw: List<RawChannel>): List<RawChannel> {
        val kept = raw.filterNot { isSensitive(it.rawLabel) }
        if (kept.any { isDirectPath(it.path) }) return kept
        // 规则 2：可直连线路被名字过滤清零 → 把"可直连的敏感线路"以中性名补回来。
        val rescued = raw
            .filter { isSensitive(it.rawLabel) && isDirectPath(it.path) }
            .map { it.copy(rawLabel = "") }
        return kept + rescued
    }
}