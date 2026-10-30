package com.nbalive.app.ui

/**
 * 「嗅探到的直连流失败、退回网页后」该不该换线（2026-10-30 现场：解析播放太慢）。
 *
 * ## 现场
 *
 * 网页里源站播放器发出 m3u8 → 我们嗅探并升级成 ExoPlayer → 该地址返回 404
 * （实测 `InvalidResponseCodeException: 404`）→ 按 [MainViewModel.onPlaybackFailed]
 * 退回网页。**然后就没有然后了**：同一播放页已被 `failedSniffPage` 禁止再次升级，
 * 而网页确实发过媒体请求（`sawMediaRequest=true`），25 秒嗅探看门狗也不会触发
 * —— 界面停在源站播放器自己的「网络错误请试试刷新」上，**永远不换线**。
 * 实测挂 5 分钟没有任何状态变化，用户只能手动重连/换线。
 *
 * ## 规则
 *
 * 退回网页后给一个**有界宽限期**（[GRACE_MS]）：期间如果页面重新发出可用的流
 * （哪怕是同一个播放页的新签名地址），说明它还能播，什么都不做；
 * 超过宽限期仍然"没有流 + 还停在这个失败页 + 不在解析中"→ 这条线判死，换下一条。
 *
 * ## 为什么是 8 秒
 *
 * - 实测正常的网页线路在页面加载后 2~3 秒内就会发出 m3u8；抓不到新的媒体请求
 *   往往就是源站播放器已经报错（它自己画的那句错误我们也读不到）。
 * - 8 秒 > 正常出流时间的 2~3 倍，弱网抖动仍有余量；又远小于原来"永久等待"。
 * - 换线次数仍由 [PlaybackAutoAdvance] 控制（每条线只试一次），不会无限循环。
 */
object SniffRecoveryPolicy {

    /** 退回网页后的宽限期。 */
    const val GRACE_MS: Long = 8_000L

    /**
     * 该不该把当前线路判死并换下一条。
     *
     * @param elapsedSinceRevertMs 从"直连失败、退回网页"那一刻起经过的毫秒数
     * @param hasStream 现在是否已经有可播的流（[MainViewModel.PlayerUiState.streamUrl]）
     * @param stillOnFailedPage 现在是不是还停在那个直连失败的播放页上
     * @param resolving 是否正在解析/切换中（这时不要叠加换线）
     */
    fun shouldAdvance(
        elapsedSinceRevertMs: Long,
        hasStream: Boolean,
        stillOnFailedPage: Boolean,
        resolving: Boolean,
    ): Boolean = !resolving && !hasStream && stillOnFailedPage && elapsedSinceRevertMs >= GRACE_MS

    /**
     * 换线时给用户的说明（拼在"线路 N …，正在切换线路 M"里）。
     *
     * 与"嗅探超时"那条路径的 "无信号" 区分开：用户看到的是一次直连失败后的自动切换，
     * 而不是"这条线本来就没信号"（两者提示措辞相同会让人以为源站没信号）。
     */
    fun advanceNote(): String = "直连失败后网页也没有恢复"
}