package com.nbalive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片内嵌按钮的**无障碍语义**（review/03-ui.md P2-25）。
 *
 * ## 要解决什么
 *
 * 直播卡片整体 `.clickable(onClick)`，"立即观看"按钮**又** `.clickable(onClick)`
 * 且是同一个回调。TalkBack 会把它读成两个独立可点节点：
 * 用户滑到这里要停两次、听到两遍相同动作，且两次触发的行为完全一样。
 *
 * ## 正确做法（二选一，这里选后者）
 *
 * 1. 内层按钮不设 clickable，只做视觉强调（但那样它看着能点却点不动）；
 * 2. 内层按钮保留可点（视觉上确实是按钮），但把它从无障碍树里**合并**掉：
 *    对父节点 `mergeDescendants`，或对内层设 `clearAndSetSemantics`。
 *
 * 选 2 并配 `onClickLabel`：TalkBack 仍能读到一个节点、一个动作词。
 */
class CardButtonSemanticsTest {

    @Test
    fun `卡片内嵌按钮不产生第二个可点节点`() {
        // 规则：内层按钮要么不参与无障碍，要么整卡合并成一个节点
        assertTrue(
            "内层按钮必须被合并进父节点的无障碍语义，否则 TalkBack 会停两次",
            CardSemantics.shouldMergeIntoParent(hasInnerButton = true),
        )
    }

    @Test
    fun `没有内嵌按钮时不需要合并`() {
        // 合并语义本身有代价（子节点内容会被拼成一句话），别滥用
        assertFalse(CardSemantics.shouldMergeIntoParent(hasInnerButton = false))
    }

    @Test
    fun `卡片必须给动作词 让读屏用户知道点了会发生什么`() {
        assertEquals(
            "播放比赛",
            CardSemantics.actionLabel(hasInnerButton = true),
        )
        assertEquals(
            "播放比赛",
            CardSemantics.actionLabel(hasInnerButton = false),
        )
    }

    @Test
    fun `动作词不能是空串`() {
        // onClickLabel = "" 会让 TalkBack 只读"双击以激活"，等于没给信息
        assertTrue(CardSemantics.actionLabel(true).isNotBlank())
    }
}