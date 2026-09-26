package com.llzx373.foldreader.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17 metaSource 编解码与写回决策的单测：
 * 解析容错、AI 只补空且未锁定字段、用户编辑即锁定（含清空）。
 */
class BookMetaSourcesTest {

    @Test
    fun `解析编码往返一致且编码输出按字段名排序稳定`() {
        val encoded = "author:ai,genre:user"
        val parsed = BookMetaSources.parse(encoded)
        assertEquals(mapOf("author" to "ai", "genre" to "user"), parsed)
        assertEquals(encoded, BookMetaSources.encode(parsed))
        // 乱序输入编码后仍稳定（确定性，便于比对）
        assertEquals(encoded, BookMetaSources.encode(mapOf("genre" to "user", "author" to "ai")))
    }

    @Test
    fun `畸形片段与未知来源逐段丢弃不抛异常`() {
        assertEquals(emptyMap<String, String>(), BookMetaSources.parse(""))
        assertEquals(emptyMap<String, String>(), BookMetaSources.parse("author"))
        assertEquals(emptyMap<String, String>(), BookMetaSources.parse("author:robot"))
        assertEquals(emptyMap<String, String>(), BookMetaSources.parse("title:ai"))
        assertEquals(
            mapOf("desc" to "ai"),
            BookMetaSources.parse("bad,desc:ai,genre:mixed:,author:ai:extra"),
        )
    }

    @Test
    fun `来源查询与打标`() {
        var meta = ""
        meta = BookMetaSources.withSource(meta, BookMetaSources.FIELD_AUTHOR, BookMetaSources.SOURCE_AI)
        assertTrue(BookMetaSources.isAiGenerated(meta, BookMetaSources.FIELD_AUTHOR))
        assertFalse(BookMetaSources.isUserOwned(meta, BookMetaSources.FIELD_AUTHOR))
        assertNull(BookMetaSources.sourceOf(meta, BookMetaSources.FIELD_GENRE))
        meta = BookMetaSources.withSource(meta, BookMetaSources.FIELD_AUTHOR, BookMetaSources.SOURCE_USER)
        assertTrue(BookMetaSources.isUserOwned(meta, BookMetaSources.FIELD_AUTHOR))
        assertFalse(BookMetaSources.isAiGenerated(meta, BookMetaSources.FIELD_AUTHOR))
    }

    @Test
    fun `AI 只补空字段并打 ai 标`() {
        val plan = BookMetaSources.planAiMetadataWrite(
            current = BookMetaSnapshot(author = "已有作者", synopsis = null, genreTag = null, metaSource = ""),
            suggestionAuthor = "AI 作者",
            suggestionSynopsis = "AI 简介",
            suggestionGenreTag = "科幻",
        )!!
        // 已有作者不被覆盖；简介与题材补上
        assertNull(plan.author)
        assertEquals("AI 简介", plan.synopsis)
        assertEquals("科幻", plan.genreTag)
        assertEquals(
            listOf(BookMetaSources.FIELD_SYNOPSIS, BookMetaSources.FIELD_GENRE),
            plan.filledFields,
        )
        assertTrue(BookMetaSources.isAiGenerated(plan.metaSource, BookMetaSources.FIELD_SYNOPSIS))
        assertTrue(BookMetaSources.isAiGenerated(plan.metaSource, BookMetaSources.FIELD_GENRE))
        assertNull(BookMetaSources.sourceOf(plan.metaSource, BookMetaSources.FIELD_AUTHOR))
    }

    @Test
    fun `AI 不覆盖任何已有值包括自己之前填的`() {
        val plan = BookMetaSources.planAiMetadataWrite(
            current = BookMetaSnapshot(
                author = null,
                synopsis = "上次 AI 填的简介",
                genreTag = null,
                metaSource = "desc:ai",
            ),
            suggestionAuthor = "新作者",
            suggestionSynopsis = "新的 AI 简介",
            suggestionGenreTag = "玄幻",
        )!!
        assertNull(plan.synopsis) // 上次 AI 填的也不动
        assertEquals("新作者", plan.author)
        assertEquals("玄幻", plan.genreTag)
        // 旧的 ai 标保留，新字段并入
        assertTrue(BookMetaSources.isAiGenerated(plan.metaSource, BookMetaSources.FIELD_SYNOPSIS))
    }

    @Test
    fun `用户锁定的空字段 AI 不补但其余字段照补`() {
        val plan = BookMetaSources.planAiMetadataWrite(
            current = BookMetaSnapshot(
                author = null,
                synopsis = null,
                genreTag = null,
                metaSource = "author:user", // 用户把作者清空了
            ),
            suggestionAuthor = "AI 作者",
            suggestionSynopsis = "AI 简介",
            suggestionGenreTag = "历史",
        )!!
        assertNull(plan.author) // 锁定的字段不补
        assertEquals("AI 简介", plan.synopsis)
        assertEquals("历史", plan.genreTag)
        // user 标不被 AI 写回抹掉
        assertTrue(BookMetaSources.isUserOwned(plan.metaSource, BookMetaSources.FIELD_AUTHOR))
    }

    @Test
    fun `全部字段已完整或都被锁定时 AI 计划为 null`() {
        assertNull(
            BookMetaSources.planAiMetadataWrite(
                current = BookMetaSnapshot("a", "b", "c", ""),
                suggestionAuthor = "x", suggestionSynopsis = "y", suggestionGenreTag = "z",
            ),
        )
        assertNull(
            BookMetaSources.planAiMetadataWrite(
                current = BookMetaSnapshot(null, null, null, "author:user,desc:user,genre:user"),
                suggestionAuthor = "x", suggestionSynopsis = "y", suggestionGenreTag = "z",
            ),
        )
        // 建议本身全空也无可补
        assertNull(
            BookMetaSources.planAiMetadataWrite(
                current = BookMetaSnapshot(null, null, null, ""),
                suggestionAuthor = null, suggestionSynopsis = "  ", suggestionGenreTag = null,
            ),
        )
    }

    @Test
    fun `用户编辑值变化的字段打 user 标且清空也算锁定`() {
        val write = BookMetaSources.planUserEdit(
            current = BookMetaSnapshot(
                author = "AI 作者",
                synopsis = "旧简介",
                genreTag = "科幻",
                metaSource = "author:ai",
            ),
            newAuthor = "AI 作者", // 未动
            newSynopsis = "我改的简介", // 改了
            newGenreTag = "", // 清空
        )
        assertEquals("AI 作者", write.author)
        assertEquals("我改的简介", write.synopsis)
        assertNull(write.genreTag) // 空白归一为 null
        assertTrue(BookMetaSources.isAiGenerated(write.metaSource, BookMetaSources.FIELD_AUTHOR))
        assertTrue(BookMetaSources.isUserOwned(write.metaSource, BookMetaSources.FIELD_SYNOPSIS))
        assertTrue(BookMetaSources.isUserOwned(write.metaSource, BookMetaSources.FIELD_GENRE))
    }

    @Test
    fun `用户清空被锁定的字段后 AI 不再回填`() {
        // 用户在编辑框里把 AI 填的题材清掉 → user 标 → 后续 AI 补全跳过该字段
        val edited = BookMetaSources.planUserEdit(
            current = BookMetaSnapshot(null, null, "玄幻", "genre:ai"),
            newAuthor = null, newSynopsis = null, newGenreTag = "",
        )
        assertTrue(BookMetaSources.isUserOwned(edited.metaSource, BookMetaSources.FIELD_GENRE))
        val plan = BookMetaSources.planAiMetadataWrite(
            current = BookMetaSnapshot(null, null, edited.genreTag, edited.metaSource),
            suggestionAuthor = "AI 作者",
            suggestionSynopsis = null,
            suggestionGenreTag = "都市",
        )!!
        assertNull(plan.genreTag)
        assertEquals(listOf(BookMetaSources.FIELD_AUTHOR), plan.filledFields)
    }

    @Test
    fun `题材标签枚举归一化`() {
        assertEquals("玄幻", GenreTags.normalize("玄幻"))
        assertEquals("其他", GenreTags.normalize(" 其他 "))
        assertNull(GenreTags.normalize("赛博朋克"))
        assertNull(GenreTags.normalize(""))
        assertNull(GenreTags.normalize(null))
        // 清单稳定：分组依赖它，误删会导致存量标签失配
        assertEquals(10, GenreTags.ALL.size)
        assertTrue("其他" in GenreTags.ALL)
    }
}
