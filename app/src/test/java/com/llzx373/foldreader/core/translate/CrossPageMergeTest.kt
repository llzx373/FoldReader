package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.ocr.OcrTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条漫跨页气泡合并（R10）：贴边判定、横向重叠阈值、续段并入与片段移除重排序。
 */
class CrossPageMergeTest {

    private fun bubble(index: Int, rect: OcrRect, text: String) = OcrBubble(
        index = index,
        rect = rect,
        lines = listOf(OcrTextLine(text, rect, 0.9f)),
        confidence = 0.9f,
    )

    @Test
    fun `贴底与贴顶且横向重叠即合并`() {
        val page = listOf(
            bubble(0, OcrRect(0.1f, 0.2f, 0.5f, 0.4f), "上部"),
            bubble(1, OcrRect(0.2f, 0.9f, 0.6f, 0.998f), "被切断的"),
        )
        val next = listOf(
            bubble(0, OcrRect(0.25f, 0.002f, 0.55f, 0.1f), "后半句"),
            bubble(1, OcrRect(0.1f, 0.3f, 0.5f, 0.5f), "整气泡"),
        )

        val plan = CrossPageMerge.plan(page, next)!!

        assertEquals(1, plan.merged)
        // 主气泡：行并入 + 续段矩形
        val owner = plan.owner[1]
        assertEquals("被切断的后半句", owner.text)
        assertEquals(OcrRect(0.25f, 0.002f, 0.55f, 0.1f), owner.continuation)
        // 未涉及的气泡原样
        assertEquals("上部", plan.owner[0].text)
        assertNull(plan.owner[0].continuation)
        // 下一页：片段移除并重排序
        assertEquals(1, plan.next.size)
        assertEquals(0, plan.next[0].index)
        assertEquals("整气泡", plan.next[0].text)
    }

    @Test
    fun `横向重叠不足不配对`() {
        val page = listOf(bubble(0, OcrRect(0.1f, 0.9f, 0.3f, 0.999f), "左"))
        val next = listOf(bubble(0, OcrRect(0.7f, 0.001f, 0.9f, 0.1f), "右"))

        assertNull(CrossPageMerge.plan(page, next))
    }

    @Test
    fun `不贴边不合并`() {
        val page = listOf(bubble(0, OcrRect(0.1f, 0.8f, 0.5f, 0.95f), "没贴底"))
        val next = listOf(bubble(0, OcrRect(0.1f, 0.001f, 0.5f, 0.1f), "贴顶"))

        assertNull(CrossPageMerge.plan(page, next))
    }

    @Test
    fun `一个片段至多被吞一次`() {
        // 两个贴底气泡都对得上同一个贴顶片段：重叠大的赢，另一个保持原样
        val page = listOf(
            bubble(0, OcrRect(0.1f, 0.9f, 0.42f, 0.999f), "甲"),
            bubble(1, OcrRect(0.15f, 0.9f, 0.6f, 0.998f), "乙"),
        )
        val next = listOf(bubble(0, OcrRect(0.2f, 0.001f, 0.55f, 0.1f), "续"))

        val plan = CrossPageMerge.plan(page, next)!!

        assertEquals(1, plan.merged)
        val swallowed = plan.owner.count { it.continuation != null }
        assertEquals(1, swallowed)
        assertTrue(plan.next.isEmpty())
        // 乙（重叠更大）吞掉片段
        assertEquals("乙续", plan.owner[1].text)
        assertEquals("甲", plan.owner[0].text)
    }
}
