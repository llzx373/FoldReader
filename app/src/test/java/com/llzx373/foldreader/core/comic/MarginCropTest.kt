package com.llzx373.foldreader.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动裁白边（M31）：白/黑边检测、误判钳制、多页并集、裁框编解码。 */
class MarginCropTest {

    /** 造一页：底色 [bg]，内容区 [content] 内填深色。 */
    private fun page(
        width: Int,
        height: Int,
        bg: Int,
        content: ContentBounds,
        ink: Int = 0xFF202020.toInt(),
    ): IntArray {
        val pixels = IntArray(width * height) { bg }
        for (y in content.top until content.bottom) {
            for (x in content.left until content.right) {
                pixels[y * width + x] = ink
            }
        }
        return pixels
    }

    @Test
    fun `白边页检出内容框`() {
        val content = ContentBounds(20, 10, 80, 70)
        val pixels = page(100, 80, 0xFFFFFFFF.toInt(), content)
        val bounds = MarginCrop.detect(pixels, 100, 80)!!
        assertEquals(content, bounds)
    }

    @Test
    fun `黑边页同样检出（底色取四角中位色，不假设白色）`() {
        val content = ContentBounds(15, 20, 85, 60)
        val pixels = page(100, 80, 0xFF101010.toInt(), content, ink = 0xFFF0F0F0.toInt())
        val bounds = MarginCrop.detect(pixels, 100, 80)!!
        assertEquals(content, bounds)
    }

    @Test
    fun `没有白边时返回 null`() {
        val pixels = page(100, 80, 0xFFFFFFFF.toInt(), ContentBounds(0, 0, 100, 80))
        assertNull(MarginCrop.detect(pixels, 100, 80))
    }

    @Test
    fun `白边里零星噪点不挡判定`() {
        val content = ContentBounds(20, 10, 80, 70)
        val pixels = page(100, 80, 0xFFFFFFFF.toInt(), content)
        // 白边区撒几个深色像素（扫描噪点）：占比远低于 0.5% 阈值
        pixels[0] = 0xFF000000.toInt()
        pixels[5 * 100 + 50] = 0xFF000000.toInt()
        val bounds = MarginCrop.detect(pixels, 100, 80)!!
        assertEquals(content, bounds)
    }

    @Test
    fun `超宽白边按 30% 钳制不裁穿`() {
        // 左边 50% 都是空白——超过 30% 上限，钳到 30
        val content = ContentBounds(50, 10, 90, 70)
        val pixels = page(100, 80, 0xFFFFFFFF.toInt(), content)
        val bounds = MarginCrop.detect(pixels, 100, 80)!!
        assertEquals(30, bounds.left) // 100 * 0.30
        assertEquals(10, bounds.top)
        assertEquals(90, bounds.right)
        assertEquals(70, bounds.bottom)
    }

    @Test
    fun `多页并集只裁所有页都空白的边`() {
        val a = ContentBounds(20, 10, 80, 70)
        val b = ContentBounds(10, 30, 90, 60)
        assertEquals(ContentBounds(10, 10, 90, 70), MarginCrop.union(a, b))
        assertEquals(a, MarginCrop.union(a, null))
        assertEquals(b, MarginCrop.union(null, b))
        assertNull(MarginCrop.union(null, null))
    }

    @Test
    fun `归一化裁框编解码往返`() {
        val bounds = ContentBounds(20, 10, 80, 70)
        val normalized = MarginCrop.toNormalized(bounds, 100, 80)
        val encoded = MarginCrop.encodeNormalized(normalized)
        val decoded = MarginCrop.decodeNormalized(encoded)!!
        val restored = MarginCrop.fromNormalized(decoded, 100, 80)!!
        // 4 位小数精度下的量化误差 ≤1px
        assertTrue(kotlin.math.abs(bounds.left - restored.left) <= 1)
        assertTrue(kotlin.math.abs(bounds.top - restored.top) <= 1)
        assertTrue(kotlin.math.abs(bounds.right - restored.right) <= 1)
        assertTrue(kotlin.math.abs(bounds.bottom - restored.bottom) <= 1)
    }

    @Test
    fun `裁框解码容错`() {
        assertNull(MarginCrop.decodeNormalized(""))
        assertNull(MarginCrop.decodeNormalized("0.1,0.2"))
        assertNull(MarginCrop.decodeNormalized("a,b,c,d"))
        assertNull(MarginCrop.decodeNormalized("0.5,0.5,0.4,0.9")) // 空框
        assertNull(MarginCrop.decodeNormalized("-0.1,0.0,1.0,1.0")) // 越界
        assertNull(MarginCrop.fromNormalized(floatArrayOf(0f, 0f, 1.5f, 1f), 100, 80))
    }

    @Test
    fun `归一化并集`() {
        val a = floatArrayOf(0.2f, 0.1f, 0.8f, 0.9f)
        val b = floatArrayOf(0.1f, 0.3f, 0.9f, 0.7f)
        val u = MarginCrop.unionNormalized(a, b)!!
        assertTrue(u[0] == 0.1f && u[1] == 0.1f && u[2] == 0.9f && u[3] == 0.9f)
        assertTrue(MarginCrop.unionNormalized(null, b) === b)
    }
}
