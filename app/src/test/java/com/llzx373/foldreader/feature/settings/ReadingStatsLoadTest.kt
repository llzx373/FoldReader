package com.llzx373.foldreader.feature.settings

import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingStatsLoadTest {

    @Test
    fun `本月统计查询失败时降级而不是抛出`() = runTest {
        val repository = failingRepository("getReadingSessionsBetween")

        assertNull(readReadingStats(repository, goalMinutes = 20))
    }

    @Test
    fun `全量日期查询失败时降级而不是抛出`() = runTest {
        val repository = failingRepository("getReadingDayStarts")

        assertNull(readReadingStats(repository, goalMinutes = 20))
    }

    @Test
    fun `协程取消继续向上传递`() = runTest {
        val repository = failingRepository("getReadingSessionsBetween", CancellationException("取消"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { readReadingStats(repository, goalMinutes = 20) }
        }
    }

    @Test
    fun `正常统计仍返回目标和阅读数据`() = runTest {
        val now = System.currentTimeMillis()
        val dayStart = com.llzx373.foldreader.core.reader.dayStartMs(now, java.time.ZoneId.systemDefault())
        val repository = Proxy.newProxyInstance(
            BookshelfRepository::class.java.classLoader,
            arrayOf(BookshelfRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getReadingSessionsBetween" -> listOf(ReadingSessionEntity(1L, dayStart, 60_000L))
                "getReadingDayStarts" -> listOf(dayStart)
                else -> error("测试不应调用 ${method.name}")
            }
        } as BookshelfRepository

        val stats = readReadingStats(repository, goalMinutes = 20, now = now)

        assertNotNull(stats)
        assertEquals(60_000L, stats!!.todayMillis)
        assertEquals(20, stats.goalMinutes)
        assertEquals(true, stats.available)
    }

    private fun failingRepository(methodName: String, error: Exception = IOException("disk I/O error")) =
        Proxy.newProxyInstance(
            BookshelfRepository::class.java.classLoader,
            arrayOf(BookshelfRepository::class.java),
        ) { _, method, _ ->
            if (method.name == methodName) throw error
            if (method.name == "getReadingSessionsBetween") emptyList<ReadingSessionEntity>()
            else error("测试不应调用 ${method.name}")
        } as BookshelfRepository
}
