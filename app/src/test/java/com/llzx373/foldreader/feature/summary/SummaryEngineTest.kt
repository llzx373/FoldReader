package com.llzx373.foldreader.feature.summary

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.data.db.BookOutlineDao
import com.llzx373.foldreader.core.data.db.BookOutlineEntity
import com.llzx373.foldreader.core.data.db.ChapterSummaryDao
import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.UnitKind
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 摘要引擎状态机单测：fake AiProvider + 内存 DAO + 临时目录台账，纯 JVM。
 */
class SummaryEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeProvider(
        var reply: String = "这是摘要。",
        var error: Exception? = null,
    ) : AiProvider {
        val requests = mutableListOf<Pair<List<AiMessage>, String>>()

        override fun chat(messages: List<AiMessage>, model: String): Flow<String> {
            requests += messages to model
            return flow {
                error?.let { throw it }
                emit(reply)
            }
        }
    }

    private class FakeSummaryDao : ChapterSummaryDao {
        val rows = LinkedHashMap<Triple<Long, String, Int>, ChapterSummaryEntity>()

        override fun observeForBook(bookId: Long, lang: String): Flow<List<ChapterSummaryEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.lang == lang })

        override suspend fun getForBook(bookId: Long, lang: String): List<ChapterSummaryEntity> =
            rows.values.filter { it.bookId == bookId && it.lang == lang }

        override suspend fun getDoneForBook(bookId: Long, lang: String): List<ChapterSummaryEntity> =
            rows.values
                .filter { it.bookId == bookId && it.lang == lang && it.status == ChapterSummaryEntity.STATUS_DONE }
                .sortedBy { it.unitIndex }

        override suspend fun getAllDone(): List<ChapterSummaryEntity> =
            rows.values.filter { it.status == ChapterSummaryEntity.STATUS_DONE }

        override suspend fun upsert(unit: ChapterSummaryEntity) {
            rows[Triple(unit.bookId, unit.lang, unit.unitIndex)] = unit
        }

        override suspend fun updateStatus(
            bookId: Long,
            lang: String,
            unitIndex: Int,
            status: String,
            summary: String,
            model: String,
            updatedAt: Long,
        ) {
            val key = Triple(bookId, lang, unitIndex)
            rows[key] = rows.getValue(key).copy(
                status = status, summary = summary, model = model, updatedAt = updatedAt,
            )
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private class FakeOutlineDao : BookOutlineDao {
        val rows = LinkedHashMap<Pair<Long, String>, BookOutlineEntity>()

        override fun observe(bookId: Long, lang: String): Flow<BookOutlineEntity?> =
            flowOf(rows[bookId to lang])

        override suspend fun get(bookId: Long, lang: String): BookOutlineEntity? = rows[bookId to lang]

        override suspend fun getAll(): List<BookOutlineEntity> = rows.values.toList()

        override suspend fun upsert(outline: BookOutlineEntity) {
            rows[outline.bookId to outline.lang] = outline
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private fun engine(
        provider: AiProvider?,
        gate: AiContentGate,
        summaryDao: FakeSummaryDao,
        outlineDao: FakeOutlineDao = FakeOutlineDao(),
    ) = SummaryEngine(
        provider = provider,
        contentGate = gate,
        summaryDao = summaryDao,
        outlineDao = outlineDao,
        preferences = { ReadingPreferences(aiModelGeneral = "test-model") },
    )

    private val unit = TranslationUnit(0, UnitKind.CHAPTER, "第一章", 0, 100)

    @Test
    fun `摘要成功落 done 并记台账`() = runBlocking {
        val provider = FakeProvider(reply = "```\n本章讲了主角出发。\n```")
        val gate = AiContentGate(File(tempFolder.root, "gate.json"))
        val dao = FakeSummaryDao()
        val e = engine(provider, gate, dao)

        val result = e.summarizeUnit(7L, "某书", unit, "正文……", AiTargetLang.ZH_HANS)

        assertTrue(result.isSuccess)
        val row = dao.rows.getValue(Triple(7L, "ZH_HANS", 0))
        assertEquals(ChapterSummaryEntity.STATUS_DONE, row.status)
        assertEquals("本章讲了主角出发。", row.summary) // 围栏已剥离
        assertEquals("chapter", row.unitKind)
        assertEquals("第一章", row.unitTitle)
        assertEquals("test-model", row.model)
        val records = gate.history()
        assertEquals(1, records.size)
        assertEquals(SummaryEngine.FEATURE_CHAPTER_SUMMARY, records[0].feature)
        assertEquals("某书", records[0].scope)
    }

    @Test
    fun `空输出重试一次后仍空记 failed`() = runBlocking {
        val provider = FakeProvider(reply = "   ")
        val dao = FakeSummaryDao()
        val e = engine(provider, AiContentGate(File(tempFolder.root, "gate.json")), dao)

        val result = e.summarizeUnit(7L, "某书", unit, "正文……", AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertEquals(2, provider.requests.size) // 首次 + 整体重试 1 次
        val row = dao.rows.getValue(Triple(7L, "ZH_HANS", 0))
        assertEquals(ChapterSummaryEntity.STATUS_FAILED, row.status)
        assertEquals("", row.summary)
    }

    @Test
    fun `未配置 AI 直接失败且零外发零台账`() = runBlocking {
        val gate = AiContentGate(File(tempFolder.root, "gate.json"))
        val dao = FakeSummaryDao()
        val e = engine(null, gate, dao)

        val result = e.summarizeUnit(7L, "某书", unit, "正文……", AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertTrue(dao.rows.isEmpty())
        assertTrue(gate.history().isEmpty())
    }

    @Test
    fun `大纲聚合全部已完成摘要并按单位号排序`() = runBlocking {
        val provider = FakeProvider(reply = "大纲正文")
        val gate = AiContentGate(File(tempFolder.root, "gate.json"))
        val summaryDao = FakeSummaryDao()
        val outlineDao = FakeOutlineDao()
        // 乱序插入 + 一条 failed：聚合只取 done 且按单位号升序
        listOf(2, 0, 1).forEach { index ->
            summaryDao.upsert(
                ChapterSummaryEntity(
                    bookId = 7L, lang = "ZH_HANS", unitIndex = index, unitKind = "chapter",
                    unitTitle = "第${index + 1}章", status = ChapterSummaryEntity.STATUS_DONE,
                    summary = "摘要$index", model = "m", updatedAt = 1L,
                ),
            )
        }
        summaryDao.upsert(
            ChapterSummaryEntity(
                bookId = 7L, lang = "ZH_HANS", unitIndex = 3, unitKind = "chapter",
                unitTitle = "第四章", status = ChapterSummaryEntity.STATUS_FAILED,
                summary = "", model = "m", updatedAt = 1L,
            ),
        )
        val e = engine(provider, gate, summaryDao, outlineDao)

        val result = e.generateOutline(7L, "某书", AiTargetLang.ZH_HANS)

        assertEquals(3, result.getOrNull())
        val userText = (
            provider.requests.single().first.last().content.single()
                as com.llzx373.foldreader.core.ai.AiContent.Text
            ).text
        assertTrue(userText.indexOf("摘要0") < userText.indexOf("摘要1"))
        assertTrue(userText.indexOf("摘要1") < userText.indexOf("摘要2"))
        assertTrue(!userText.contains("第四章"))
        val outline = outlineDao.rows.getValue(7L to "ZH_HANS")
        assertEquals("大纲正文", outline.outline)
        assertEquals(3, outline.summaryCount)
        assertEquals(SummaryEngine.FEATURE_BOOK_OUTLINE, gate.history().single().feature)
    }

    @Test
    fun `没有已完成摘要时大纲直接失败且不外发`() = runBlocking {
        val provider = FakeProvider()
        val gate = AiContentGate(File(tempFolder.root, "gate.json"))
        val e = engine(provider, gate, FakeSummaryDao())

        val result = e.generateOutline(7L, "某书", AiTargetLang.ZH_HANS)

        assertTrue(result.isFailure)
        assertTrue(provider.requests.isEmpty())
        assertTrue(gate.history().isEmpty())
    }
}
