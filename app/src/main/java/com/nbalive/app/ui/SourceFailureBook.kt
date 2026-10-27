package com.nbalive.app.ui

/**
 * 播放线路的**临时**失败记账（review/01-P1-5）。
 *
 * ## 要解决什么
 *
 * 15 秒看门狗把"弱网首帧 >15s"的健康线路判成卡死，然后把它记进
 * `failedSources` —— 而那个集合在会话内**永不清除**。
 * 用户看到的是"线路莫名被跳走且回不来"：那条线其实是好的，
 * 只是第一帧慢了点，之后整场都不会再被自动选中。
 *
 * 这与 `02-P1-9` 的域名候选是同一个错误：**把暂时故障写成永久故障**。
 * 限流窗口、弱网、CDN 抖动都会过去；永久黑名单只是把暂时问题变成长期问题。
 *
 * ## 规则
 *
 * - 拉黑带时间戳，超过 [TTL_MS] 自动失效；
 * - 再次失败会**刷新**时间戳（否则第一次失败的时间会让它提前解封）；
 * - 用户显式点这条线 / 切场 / 刷新列表 -> 立刻解除（原有行为保留）；
 * - 查不到记录 = 可用（不能因为初始状态误伤）。
 *
 * ## 为什么单独一个类
 *
 * 判定逻辑（"这条线现在还能不能自动选"）原来散在 `MainViewModel` 的
 * `pageUrl !in failedSources` 里，只能靠人眼审；抽出来之后
 * "过期解封"这件事可以被 JVM 单测钉住（见 `SourceFailureBookTest`），
 * 而 Compose/Android 环境在纯单测里跑不到。
 *
 * ⚠️ 线程安全：所有方法都是 `@Synchronized`。
 * 原来的 `failedSources` 注释说"只在主线程读写"，但那是**实现细节**
 * 不是契约；以后有人加一个 `withContext(Dispatchers.IO)` 就会炸。
 */
class SourceFailureBook {

    /** 拉黑有效期：弱网首帧、CDN 抖动、限流窗口都在这个量级内会过去。 */
    companion object {
        /**
         * 5 分钟。
         *
         * 取值依据：15s 看门狗的误判来自"首帧慢"，而首帧慢通常是瞬时网络抖动
         * 或 CDN 节点刚切换 —— 几分钟内必然恢复。太短（30s）会让故障切换
         * 反复回到同一条坏线上、来回跳；太长（30 分钟）又退化成"永久拉黑"。
         */
        const val TTL_MS = 5 * 60 * 1000L
    }

    /** pageUrl -> 拉黑时刻（单调递增的毫秒）。 */
    private val failedAt = mutableMapOf<String, Long>()

    /** 记一条失败（重复失败会刷新时间戳）。 */
    @Synchronized
    fun markFailed(pageUrl: String, nowMs: Long) {
        // 顺带清理过期条目：长时间播放会不断累积，不清就是慢性泄漏
        purgeExpired(nowMs)
        failedAt[pageUrl] = nowMs
    }

    /**
     * 这条线现在是否处于"别自动选它"的状态。
     *
     * @param nowMs 当前时刻（与 [markFailed] 用同一个时钟源）
     */
    @Synchronized
    fun isFailed(pageUrl: String, nowMs: Long): Boolean {
        val at = failedAt[pageUrl] ?: return false
        if (nowMs - at >= TTL_MS) {
            // 过期即解封：不是"删除记录再返回 false"的两种写法都行，
            // 这里顺手删掉，免得下次还要再算一遍
            failedAt.remove(pageUrl)
            return false
        }
        return true
    }

    /** 用户显式选了这条线，或这条线播成功了：立刻恢复。 */
    @Synchronized
    fun clear(pageUrl: String) {
        failedAt.remove(pageUrl)
    }

    /** 切场 / 刷新列表：整本清空。 */
    @Synchronized
    fun clearAll() {
        failedAt.clear()
    }

    /** 当前有效条目数（过期的不算），供测试与诊断。 */
    @Synchronized
    fun size(nowMs: Long): Int {
        purgeExpired(nowMs)
        return failedAt.size
    }

    private fun purgeExpired(nowMs: Long) {
        failedAt.entries.removeAll { (_, at) -> nowMs - at >= TTL_MS }
    }
}