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
 * 线路准入判据：源站播放页里的线路，哪些最终进入界面。
 *
 * ## 只有一条规则：名字涉及主播/解说/美女/聊天的线路 —— **整条过滤掉**
 *
 * 这一类是主播个人推流。产品要求是"既不呈现其名义，也不出现在列表里"
 * （2026-10-29 用户明确要求：「过滤掉涉及到主播的线路」）。
 * 关键词表见 [EXCLUDED_KEYWORDS]，判据是纯函数，可单测
 * （见 `JrkanChannelAdmissionTest`）。
 *
 * ## 被删除的「补偿规则」，以及为什么它绝不能再加回来
 *
 * `007ad88`（2026-10-29 13:08）曾加过一条规则 2：**若按名字过滤会把「可直连
 * (`/play/sm.html`)」线路清零，就把这些敏感线路以中性名（"线路N"）补回列表**。
 *
 * 那条规则是**误诊的产物**，已删除：
 *
 *  - 当时的现象是"点比赛视频区纯黑"，证据是"网页线路一条媒体请求都不发、
 *    页内 JS 拼出的播放器页面没跑起来"，被解释成"这些页面只能直连"。
 *  - 真实根因是 v1.2.13 的明文策略误禁了**源站创建播放器的脚本主机**
 *    （`net::ERR_CLEARTEXT_NOT_PERMITTED url=http://im-imgs-bucket.oss-accelerate.aliyuncs.com/pjs.js`），
 *    已在 v1.2.16（`45fdb89`）修掉 —— 网页线路本身能正常起播。
 *  - 补偿生效后 `MainViewModel` 的"默认线路优先选可直连"就会选中被补回来的主播线路，
 *    用户看到的就是「只有主播线路能看」，这与他"要其他线路"的要求**直接冲突**。
 *
 * ## 2026-10-29 21:00 实测（23 个场次、95 条线路）
 *
 * | 类别 | 条数 | 路径 |
 * |---|---|---|
 * | 敏感（主播/解说…） | 48 | **全部** `/play/sm.html`（可直连 HLS） |
 * | 非敏感 | 47 | **全部** `/play/pao\|wen\|mgxl\|wlive/`（只能走 WebView） |
 *
 * 所以"过滤后一条可直连线路都不剩"是**正常结果**，不是故障；过滤结果为空
 * 也是合法结果 —— 调用方（`MatchRepository.loadSignals` / 界面）按
 * "该赛事暂无可用线路"处理，**不许**退回源站入口页（那等于把主播线路播回来）。
 *
 * ⚠️ 因此 [admit] 里不许出现任何"补回 / 降级 / 例外"分支：
 * `JrkanChannelAdmissionTest` 用函数体扫描钉住（`admit` 内出现 `isDirectPath`
 * 即回退变红）。
 */
object ChannelAdmission {

    /**
     * 触发"整条过滤"的关键词。
     *
     * 表只许有这一份（`JrkanChannelAdmissionTest` 扫生产源码钉住），别处不许再抄。
     */
    val EXCLUDED_KEYWORDS: List<String> = listOf("主播", "解说", "美女", "聊天")

    /** 线路名是否涉及主播/解说等（命中即整条不进入列表）。 */
    fun isSensitive(label: String): Boolean = EXCLUDED_KEYWORDS.any { label.contains(it) }

    /**
     * 这条线路能否被静态解析成直连 m3u8。
     * ⚠️ 委托给 [isStaticallyResolvablePlayPage]（判据唯一来源），不在这里另写正则。
     */
    fun isDirectPath(path: String): Boolean = isStaticallyResolvablePlayPage(path)

    /**
     * 决定最终线路列表（顺序未定，排序与编号由调用方做）。
     *
     * **可能返回空列表**（源站所有线路都是主播线路），这是合法结果，不是异常。
     * 只有名字过滤这一条规则 —— 见类 KDoc 里"被删除的补偿规则"。
     */
    fun admit(raw: List<RawChannel>): List<RawChannel> {
        return raw.filterNot { isSensitive(it.rawLabel) }
    }
}