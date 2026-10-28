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
 *
 * ## 为什么这里只有一个常量（review/audit-guards.md B6）
 *
 * 原来这个对象里有两个函数：
 *  - `shouldMergeIntoParent(hasInnerButton)` 是**恒等函数**，生产唯一调用传的是
 *    字面量 `true`（`LiveNowScreen` 的 LiveNowCard），而单测却断言 `false → false`
 *    —— 断的是生产永远走不到的那条路径；
 *  - `actionLabel(hasInnerButton)` 的形参**被实现完全忽略**，两处调用分别传
 *    `true`/`false` 返回同一串 —— 参数挡在签名里制造"两处已区分"的假象。
 *
 * 两个函数都删掉了：
 *  - "要不要合并"是**渲染处的事实**（那张卡里到底有没有内嵌可点按钮），
 *    就该原地写成 `mergeDescendants = true`，而不是绕一层永远返回实参的函数；
 *  - 动作词只剩下面这一个常量，两个生产调用点都消费它。
 */
object CardSemantics {

    /**
     * 卡片的动作词（`Modifier.clickable(onClickLabel = ...)`）。
     *
     * ⚠️ **绝不能是空串**：`onClickLabel = ""` 会让 TalkBack 只读"双击以激活"，
     * 等于没告诉用户会发生什么 —— 用户只听到一连串队名/时间，不知道点了要干什么。
     *
     * 生产消费点（两条，由 `CardButtonSemanticsTest` 扫源码守住）：
     * `LiveNowScreen.kt` 的 LiveNowCard、`MatchListScreen.kt` 的 LiveHeroCard。
     * 两处必须共用这一个常量，不许各写一份字面量。
     */
    const val ACTION_LABEL = "播放比赛"
}