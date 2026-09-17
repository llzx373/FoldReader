package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标注命中的索引化改写必须与原线性实现逐点等价——错一个区间就是划线画错位置。
 */
class AnnotationIndexTest {

    private fun ann(id: Long, start: Long, end: Long) = AnnotationEntity(
        id = id,
        bookId = 1L,
        startCharOffset = start,
        endCharOffset = end,
        selectedText = "t$id",
        color = 0L,
        note = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** 原实现：全量过滤。 */
    private fun linear(list: List<AnnotationEntity>, start: Long, end: Long): List<AnnotationEntity> =
        list.filter { it.endCharOffset > start && it.startCharOffset < end }
            .sortedBy { it.startCharOffset }

    @Test
    fun `与线性实现逐点等价`() {
        val list = listOf(
            ann(1, 0, 10),
            ann(2, 5, 15),
            ann(3, 20, 30),
            ann(4, 30, 31),
            ann(5, 100, 200),
            ann(6, 150, 160),
            ann(7, 0, 1000),
        )
        val index = AnnotationIndex(list)

        val probes = listOf(
            0L to 1L, 0L to 10L, 0L to 11L, 4L to 6L, 9L to 21L,
            15L to 20L, 29L to 31L, 31L to 99L, 100L to 101L, 199L to 201L,
            250L to 300L, 0L to 1000L, 1000L to 1001L, 5L to 5L,
        )
        for ((start, end) in probes) {
            assertEquals(
                "区间 [$start, $end)",
                linear(list, start, end).map { it.id },
                index.overlapping(start, end).map { it.id },
            )
        }
    }

    @Test
    fun `空标注列表始终返回空`() {
        val empty = AnnotationIndex(emptyList())
        assertEquals(0, empty.size)
        assertTrue(empty.overlapping(0L, 100L).isEmpty())
    }

    /**
     * 退化区间（start == end，例如空页）必须与原谓词过滤一致：
     * 原实现没有空区间守卫，划线跨在空页上时仍然会画出来。
     */
    @Test
    fun `退化区间与线性实现一致`() {
        val list = listOf(ann(1, 0, 10), ann(2, 20, 30))
        val index = AnnotationIndex(list)

        for (point in listOf(0L, 5L, 10L, 15L, 25L, 30L, 100L)) {
            assertEquals(
                "退化区间 [$point, $point)",
                linear(list, point, point).map { it.id },
                index.overlapping(point, point).map { it.id },
            )
        }
    }

    @Test
    fun `乱序输入也能正确命中`() {
        val index = AnnotationIndex(listOf(ann(3, 300, 400), ann(1, 0, 50), ann(2, 120, 130)))

        assertEquals(listOf(1L), index.overlapping(10L, 20L).map { it.id })
        assertEquals(listOf(2L), index.overlapping(125L, 126L).map { it.id })
        assertEquals(listOf(2L, 3L), index.overlapping(129L, 305L).map { it.id })
    }

    @Test
    fun `随机用例与线性实现等价`() {
        val random = java.util.Random(20240918)
        repeat(60) {
            val list = (0 until random.nextInt(60)).map { id ->
                val start = random.nextInt(2000).toLong()
                ann(id.toLong(), start, start + random.nextInt(200) + 1)
            }
            val index = AnnotationIndex(list)
            repeat(120) {
                val start = random.nextInt(2200).toLong()
                val end = start + random.nextInt(300) + 1
                assertEquals(
                    "区间 [$start, $end) 标注数=${list.size}",
                    linear(list, start, end).map { it.id },
                    index.overlapping(start, end).map { it.id },
                )
            }
        }
    }

    @Test
    fun `前缀最大结束偏移能提前终止反向扫描`() {
        // 前 500 个标注都远在区间之前且都很短 → 反向扫描应立即中断
        val list = (0 until 500).map { ann(it.toLong(), it * 2L, it * 2L + 1) } +
            ann(999L, 100_000L, 100_100L)
        val index = AnnotationIndex(list)

        val hits = index.overlapping(100_050L, 100_060L)

        assertEquals(listOf(999L), hits.map { it.id })
    }
}
