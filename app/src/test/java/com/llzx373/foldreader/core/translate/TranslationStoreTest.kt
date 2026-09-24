package com.llzx373.foldreader.core.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 译本副本存储：单位落盘 + 流重写（未译占位、单位对齐）、删除重排、TSV 转义往返。
 */
class TranslationStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: TranslationStore

    private val bookId = 7L
    private val lang = "ZH_HANS"
    private val units = listOf(
        TranslationUnit(0, UnitKind.CHAPTER, "第一章", 0, 100),
        TranslationUnit(1, UnitKind.CHAPTER, "第二章", 100, 250),
        TranslationUnit(2, UnitKind.BLOCK, "第 1 节", 250, 400),
    )

    @Before
    fun setUp() {
        store = TranslationStore(tempFolder.newFolder("translations"))
    }

    private fun contentText(): String =
        File(File(File(tempFolder.root, "translations"), bookId.toString()), lang)
            .resolve("content.txt").readText()

    @Test
    fun `saveUnits 初始化全占位流且目录偏移正确`() {
        store.saveUnits(bookId, lang, units)

        val (text, chapters) = store.assemble(bookId, lang, units)
        val p = UNTRANSLATED_PLACEHOLDER
        assertEquals("$p\n$p\n$p", text)
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].charStart)
        assertEquals(p.length.toLong(), chapters[0].charEnd)
        assertEquals(p.length + 1L, chapters[1].charStart)
        assertEquals((p.length + 1) * 2L + p.length, chapters[2].charEnd)
        // 落盘的 content.txt 与 assemble 一致
        assertEquals(text, contentText())
        assertFalse(store.hasAny(bookId, lang)) // 只有占位，还没有已译单位
    }

    @Test
    fun `saveUnit 后 assemble 的偏移与单位边界正确`() {
        store.saveUnits(bookId, lang, units)
        store.saveUnit(bookId, lang, units[0], listOf("段落一", "段落二"))

        val (text, chapters) = store.assemble(bookId, lang, units)
        val first = "段落一\n段落二"
        assertEquals("$first\n$UNTRANSLATED_PLACEHOLDER\n$UNTRANSLATED_PLACEHOLDER", text)
        // 已译单位用其标题与真实区间
        assertEquals("第一章", chapters[0].title)
        assertEquals(0L, chapters[0].charStart)
        assertEquals(first.length.toLong(), chapters[0].charEnd)
        // 后续单位区间随译长度重排
        assertEquals(first.length + 1L, chapters[1].charStart)
        assertEquals(text.length.toLong(), chapters[2].charEnd)
        assertEquals("第二章", chapters[1].title)
        // 流已重写
        assertEquals(text, contentText())
        assertTrue(store.hasAny(bookId, lang))
        assertTrue(store.hasAny(bookId))
        assertFalse(store.hasAny(bookId, "EN"))
        // loadUnit 往返
        assertEquals(TranslatedUnitContent("第一章", listOf("段落一", "段落二")),
            store.loadUnit(bookId, lang, 0))
        assertNull(store.loadUnit(bookId, lang, 1))
    }

    @Test
    fun `deleteUnit 后该单位回到占位且流重排`() {
        store.saveUnits(bookId, lang, units)
        store.saveUnit(bookId, lang, units[0], listOf("段落一", "段落二"))
        store.saveUnit(bookId, lang, units[1], listOf("second"))

        store.deleteUnit(bookId, lang, 0)

        val (text, chapters) = store.assemble(bookId, lang, units)
        assertEquals("$UNTRANSLATED_PLACEHOLDER\nsecond\n$UNTRANSLATED_PLACEHOLDER", text)
        assertEquals("第一章", chapters[0].title) // 占位单位用单位清单里的标题
        assertEquals(UNTRANSLATED_PLACEHOLDER.length + 1L, chapters[1].charStart)
        assertEquals(text, contentText())
        assertNull(store.loadUnit(bookId, lang, 0))
    }

    @Test
    fun `重译同一单位覆盖旧译文`() {
        store.saveUnits(bookId, lang, units)
        store.saveUnit(bookId, lang, units[0], listOf("旧译文"))
        store.saveUnit(bookId, lang, units[0], listOf("新译文一", "新译文二"))

        assertEquals(TranslatedUnitContent("第一章", listOf("新译文一", "新译文二")),
            store.loadUnit(bookId, lang, 0))
        assertTrue(contentText().startsWith("新译文一\n新译文二"))
    }

    @Test
    fun `toc 转义往返（制表符换行反斜杠）`() {
        val tricky = "第\t一章\n换行\\反斜杠\r回车"
        val trickyUnits = listOf(TranslationUnit(0, UnitKind.CHAPTER, tricky, 0, 100))
        store.saveUnits(bookId, lang, trickyUnits)

        val toc = store.readToc(bookId, lang)
        assertEquals(1, toc?.size)
        assertEquals(tricky, toc!![0].title)
        assertEquals(0L, toc[0].charStart)
        assertEquals(UNTRANSLATED_PLACEHOLDER.length.toLong(), toc[0].charEnd)
    }

    @Test
    fun `deleteBook 清除全部语言目录`() {
        store.saveUnits(bookId, lang, units)
        store.saveUnit(bookId, lang, units[0], listOf("x"))
        store.saveUnits(bookId, "EN", units)

        store.deleteBook(bookId)

        assertFalse(store.hasAny(bookId))
        assertNull(store.readUnits(bookId, lang))
        assertFalse(File(tempFolder.root, "translations/$bookId").exists())
    }
}
