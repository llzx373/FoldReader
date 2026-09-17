package com.llzx373.foldreader.feature.reader

import com.llzx373.foldreader.core.format.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索发布节流：逐条发布会让每个命中都拷贝整份列表（O(n²)），
 * 这里验证发布时机只由「条数阈值」和「时间阈值」决定。
 */
private fun searchHitAt(offset: Long) = SearchHit(
    offset = offset,
    context = "ctx$offset",
    matchStartInContext = 0,
    matchLength = 2,
)

/** 复刻 ReaderViewModel.startSearch 的调用方式：发布后必须回调 onPublished。 */
private class SearchConsumer(
    val batcher: SearchHitBatcher,
) {
    var publishes = 0
        private set

    fun hit(offset: Long, nowMs: Long): Boolean {
        val publish = batcher.onHit(searchHitAt(offset), nowMs)
        if (publish) {
            publishes++
            batcher.onPublished(nowMs)
        }
        return publish
    }

    fun progress(nowMs: Long): Boolean {
        val publish = batcher.onProgress(nowMs)
        if (publish) {
            publishes++
            batcher.onPublished(nowMs)
        }
        return publish
    }
}

class SearchHitBatcherTest {

    @Test
    fun `同一时刻的连续命中只发布一次`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 200, intervalMs = 150))

        val results = (0 until 10).map { consumer.hit(it.toLong(), nowMs = 1_000) }

        assertTrue("首条命中应立刻发布（距初始 lastPublish 远超 150ms）", results.first())
        assertTrue("同刻其余命中不该再发布", results.drop(1).none { it })
        assertEquals(1, consumer.publishes)
        assertEquals(10, consumer.batcher.hits.size)
    }

    @Test
    fun `时间推进满间隔后才再次发布`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 1000, intervalMs = 150))

        assertTrue(consumer.hit(0, nowMs = 1_000))
        assertFalse(consumer.hit(1, nowMs = 1_050))
        assertFalse(consumer.hit(2, nowMs = 1_149))
        assertTrue("距上次发布满 150ms", consumer.hit(3, nowMs = 1_150))
        assertEquals(2, consumer.publishes)
    }

    @Test
    fun `条数阈值先到也会发布`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 3, intervalMs = 1_000_000))

        assertFalse(consumer.hit(0, nowMs = 10))
        assertFalse(consumer.hit(1, nowMs = 10))
        assertTrue(consumer.hit(2, nowMs = 10))

        assertFalse(consumer.hit(3, nowMs = 10))
        assertFalse(consumer.hit(4, nowMs = 10))
        assertTrue(consumer.hit(5, nowMs = 10))
        assertEquals(2, consumer.publishes)
    }

    @Test
    fun `进度事件同样受时间节流`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 1000, intervalMs = 150))

        assertTrue(consumer.progress(nowMs = 1_000))
        assertFalse(consumer.progress(nowMs = 1_100))
        assertTrue(consumer.progress(nowMs = 1_150))
        assertEquals(2, consumer.publishes)
    }

    @Test
    fun `命中累积不丢且保持到达顺序`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 2, intervalMs = 1_000_000))

        repeat(10) { consumer.hit(it.toLong(), nowMs = 5) }

        assertEquals(5, consumer.publishes)
        assertEquals((0L until 10L).toList(), consumer.batcher.hits.map { it.offset })
    }

    @Test
    fun `大命中量下发布次数随条数线性增长`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 200, intervalMs = 1_000_000))

        repeat(10_000) { consumer.hit(it.toLong(), nowMs = 7) }

        assertEquals("10000 条 / 每批 200 条 = 50 次发布，而非逐条 10000 次", 50, consumer.publishes)
        assertEquals(10_000, consumer.batcher.hits.size)
    }

    @Test
    fun `无命中时仅进度事件驱动发布`() {
        val consumer = SearchConsumer(SearchHitBatcher(batchSize = 10, intervalMs = 150))

        assertTrue(consumer.progress(nowMs = 200))
        assertFalse(consumer.progress(nowMs = 300))
        assertTrue(consumer.progress(nowMs = 350))
        assertTrue(consumer.batcher.hits.isEmpty())
        assertEquals(2, consumer.publishes)
    }
}
