package com.llzx373.foldreader.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 超长图分片检测（M31）：判据、切片全覆盖与重叠、坐标换算、按中心归属去重。
 */
class TallPageTilesTest {

    private fun bubble(top: Float, bottom: Float) = OcrBubble(
        index = -1,
        rect = OcrRect(0.2f, top, 0.6f, bottom),
        lines = listOf(OcrTextLine("x", OcrRect(0.3f, top + 0.001f, 0.5f, bottom - 0.001f), 0.9f)),
        confidence = 0.9f,
    )

    @Test
    fun `超长判据按高宽比`() {
        assertTrue(TallPageTiles.isTall(800, 2400))
        assertFalse(TallPageTiles.isTall(800, 2399))
        assertFalse(TallPageTiles.isTall(2000, 1000)) // 横向跨页大图不算
    }

    @Test
    fun `切片全覆盖且有重叠`() {
        // 页高 10000，片高 2000，重叠 500：步进 1500 → 0..2000, 1500..3500, ...
        val ranges = TallPageTiles.tileRanges(10000, 2000, 500)
        assertEquals(0, ranges.first().first)
        // 半开区间：末片终点（last + 1）顶到页底
        assertEquals(10000, ranges.last().last + 1)
        for (i in 1 until ranges.size) {
            // 相邻片起点 = 步进；且与上片有重叠（起点 < 上片终点）
            assertEquals(ranges[i - 1].first + 1500, ranges[i].first)
            assertTrue(ranges[i].first < ranges[i - 1].last)
        }
    }

    @Test
    fun `不足一片返回整页单片`() {
        assertEquals(listOf(0 until 800), TallPageTiles.tileRanges(800, 2000, 500))
    }

    @Test
    fun `极端长图片数封顶`() {
        val ranges = TallPageTiles.tileRanges(1_000_000, 2000, 500)
        assertEquals(TallPageTiles.MAX_TILES, ranges.size)
    }

    @Test
    fun `片坐标换算回页坐标`() {
        // 第二片从页 2000px 起、片高 2000、页高 10000：片内 0.5..0.75 → 页 0.3..0.35
        val r = TallPageTiles.toPageRect(OcrRect(0.2f, 0.5f, 0.6f, 0.75f), 2000, 2000, 10000)
        assertEquals(0.2f, r.left, 1e-6f)
        assertEquals(0.3f, r.top, 1e-6f)
        assertEquals(0.35f, r.bottom, 1e-6f)
    }

    @Test
    fun `跨缝气泡按中心归属只留一份`() {
        // 两片：0..2000 与 1500..3500（页高 10000）；独占区分界在 1500
        val inOverlap = bubble(0.10f, 0.18f) // 中心 0.14 → 1400px，归第一片
        val inSecond = bubble(0.16f, 0.24f) // 中心 0.20 → 2000px，归第二片
        val tiles = listOf(
            (0 until 2000) to listOf(inOverlap, inSecond),
            (1500 until 3500) to listOf(inOverlap, inSecond),
        )
        val merged = TallPageTiles.merge(tiles, 10000)
        assertEquals(2, merged.size)
        // 中心 y 用容差比较（float 中值不与字面量位级相等）
        assertEquals(1, merged.count { abs(it.rect.centerY - 0.14f) < 1e-5f })
        assertEquals(1, merged.count { abs(it.rect.centerY - 0.20f) < 1e-5f })
    }
}
