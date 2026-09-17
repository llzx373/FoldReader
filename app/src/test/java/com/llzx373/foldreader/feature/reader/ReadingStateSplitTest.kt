package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态拆分的架构约定守卫。
 *
 * 这些字段每跨一页都会变，一旦被挪回 [ReaderUiState]，读取 uiState 的整棵阅读树
 * 就会在滚动时每页重组一次——正是这次拆分要消除的问题。用反射把约定钉住，
 * 避免后续改动无声地把它改回去。
 */
class ReadingStateSplitTest {

    /** 每跨一页都会变的展示态。 */
    private val perPageFields = listOf(
        "progressFraction",
        "chapterTitle",
        "chapterIndex",
        "chapterCount",
        "pageNumber",
        "inChapterFraction",
        "paperPageLabel",
    )

    /** 用 Java 反射取 data class 的构造属性名（不引入 kotlin-reflect 依赖）。 */
    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields.map { it.name }.toSet()

    @Test
    fun `阅读位置字段不属于 ReaderUiState`() {
        val names = fieldNames(ReaderUiState::class.java)

        for (field in perPageFields) {
            assertFalse("ReaderUiState 不应再包含 $field（应放到 ReadingPosition）", field in names)
        }
        assertFalse("滚动页流不应再挂在 uiState 上（应走 SnapshotStateList）", "scrollPages" in names)
    }

    @Test
    fun `阅读位置字段都在 ReadingPosition 上`() {
        val names = fieldNames(ReadingPosition::class.java)

        for (field in perPageFields) {
            assertTrue("ReadingPosition 应包含 $field", field in names)
        }
    }

    @Test
    fun `ReaderUiState 只保留翻页或改版式才变的结构态`() {
        val names = fieldNames(ReaderUiState::class.java)

        for (field in listOf("loading", "error", "bookTitle", "totalChars", "spread", "dualPage", "layoutConfig", "totalPages", "spreadGeometry")) {
            assertTrue("ReaderUiState 应保留 $field", field in names)
        }
    }
}
