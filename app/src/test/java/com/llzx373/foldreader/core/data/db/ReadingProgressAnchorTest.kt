package com.llzx373.foldreader.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 双模式（文本 / 页式）进度共用一行的不变量。
 *
 * `reading_progress` 的主键是 bookId 且 upsert 是整行 REPLACE，所以「本次没写的列」会被清空。
 * 文本型 PDF 两种模式都能读，两边各自保存时必须把对方的锚点带过来——否则来回切模式就会
 * 互相抹掉位置。这里把 [ReadingProgressEntity.keepPagedAnchor] / [keepTextAnchor] 钉死。
 */
class ReadingProgressAnchorTest {

    private fun textSave(offset: Long, now: Long = 0L) = ReadingProgressEntity(
        bookId = 1,
        charOffset = offset,
        chapterIndex = 3,
        totalReadingMillis = 10,
        firstReadAt = 1,
        charsReadTotal = 2,
        updatedAt = now,
    )

    private fun pagedSave(page: Int, now: Long = 0L) = ReadingProgressEntity(
        bookId = 1,
        charOffset = 0,
        chapterIndex = 0,
        totalReadingMillis = 10,
        firstReadAt = 1,
        charsReadTotal = 2,
        comicPage = page,
        updatedAt = now,
    )

    @Test
    fun `文本保存保留旧的页式锚点`() {
        val existing = pagedSave(page = 50)

        val saved = textSave(offset = 9000).keepPagedAnchor(existing)

        assertEquals(9000L, saved.charOffset)
        assertEquals(50, saved.comicPage)
    }

    @Test
    fun `页式保存保留旧的文本锚点与章节`() {
        val existing = textSave(offset = 1234)

        val saved = pagedSave(page = 7).keepTextAnchor(existing)

        assertEquals(7, saved.comicPage)
        assertEquals(1234L, saved.charOffset)
        assertEquals(3, saved.chapterIndex)
    }

    @Test
    fun `没有旧行时保持自身`() {
        val text = textSave(offset = 42).keepPagedAnchor(null)
        assertEquals(42L, text.charOffset)
        assertEquals(null, text.comicPage)

        val paged = pagedSave(page = 9).keepTextAnchor(null)
        assertEquals(9, paged.comicPage)
        assertEquals(0L, paged.charOffset)
    }

    @Test
    fun `页式与文本来回切换两个位置都不丢`() {
        // 页式读到第 50 页
        var row: ReadingProgressEntity? = pagedSave(page = 50)
        // 切文本模式读一会儿
        row = textSave(offset = 9000).keepPagedAnchor(row)
        // 再切回页式
        row = pagedSave(page = 51).keepTextAnchor(row)

        assertEquals(51, row.comicPage)
        assertEquals(9000L, row.charOffset)
        assertEquals(3, row.chapterIndex)
    }

    // ---------- M19 译文锚点（三模式对称） ----------

    private fun translationSave(anchor: Long, now: Long = 0L) = ReadingProgressEntity(
        bookId = 1,
        charOffset = 0,
        chapterIndex = 0,
        totalReadingMillis = 10,
        firstReadAt = 1,
        charsReadTotal = 2,
        translationAnchor = anchor,
        updatedAt = now,
    )

    @Test
    fun `译文保存保留旧的文本锚点与页式锚点`() {
        val existing = pagedSave(page = 50).keepTextAnchor(textSave(offset = 1234))

        val saved = translationSave(anchor = 777).keepTranslationAnchor(existing)

        assertEquals(777L, saved.translationAnchor)
        assertEquals(1234L, saved.charOffset)
        assertEquals(3, saved.chapterIndex)
        assertEquals(50, saved.comicPage)
    }

    @Test
    fun `文本与页式保存都保留旧的译文锚点`() {
        val existing = translationSave(anchor = 777)

        val textSaved = textSave(offset = 9000).keepPagedAnchor(existing)
        assertEquals(777L, textSaved.translationAnchor)
        assertEquals(9000L, textSaved.charOffset)

        val pagedSaved = pagedSave(page = 7).keepTextAnchor(existing)
        assertEquals(777L, pagedSaved.translationAnchor)
        assertEquals(7, pagedSaved.comicPage)
    }

    @Test
    fun `译文保存没有旧行时保持自身`() {
        val saved = translationSave(anchor = 42).keepTranslationAnchor(null)

        assertEquals(42L, saved.translationAnchor)
        assertEquals(0L, saved.charOffset)
        assertEquals(null, saved.comicPage)
    }

    @Test
    fun `三种模式来回切换三个位置都不丢`() {
        // 文本读到 9000
        var row: ReadingProgressEntity? = textSave(offset = 9000)
        // 切译文模式读一会儿
        row = translationSave(anchor = 777).keepTranslationAnchor(row)
        // 切页式（漫画/PDF）读到第 50 页
        row = pagedSave(page = 50).keepTextAnchor(row)
        // 再切回文本
        row = textSave(offset = 9100).keepPagedAnchor(row)

        assertEquals(9100L, row.charOffset)
        assertEquals(777L, row.translationAnchor)
        assertEquals(50, row.comicPage)
    }
}
