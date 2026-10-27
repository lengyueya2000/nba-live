package com.nbalive.app.ui

/**
 * 卡片内嵌按钮的无障碍语义（review/03-ui.md P2-25）。
 *
 * ## 要解决什么
 *
 * 直播卡片整体 `.clickable(onClick)`，"立即观看"按钮**又** `.clickable(onClick)`
 * 且是同一个回调。TalkBack 会把它读成两个独立可点节点：
 * 用户滑到这里要停两次、听到两遍相同动作，两次触发的行为完全一样。
 *
 * ## 做法
 *
 * 内层按钮保留可点（视觉上确实是按钮，点它符合直觉），
 * 但对卡片容器加 `mergeDescendants = true`，把它并进同一个无障碍节点；
 * 再给 `onClickLabel` 一个动作词，读屏用户听到的是
 * "湖人 对 勇士，双击以播放比赛"这样一句话、一个动作。
 */
object CardSemantics {

    /**
     * 卡片容器是否该把子节点的语义合并进来。
     *
     * 只在**确实有内嵌可点按钮**时合并：合并的代价是子节点的文本会被
     * 拼成一整句，没有内嵌按钮时没必要付这个代价。
     */
    fun shouldMergeIntoParent(hasInnerButton: Boolean): Boolean = hasInnerButton

    /**
     * 卡片的动作词（`Modifier.clickable(onClickLabel = ...)`）。
     *
     * ⚠️ 不能是空串：`onClickLabel = ""` 会让 TalkBack 只读"双击以激活"，
     * 等于没告诉用户会发生什么。
     */
    fun actionLabel(hasInnerButton: Boolean): String = "播放比赛"
}