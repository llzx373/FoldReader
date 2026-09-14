package com.llzx373.foldreader.core.reader

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStatsTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val cst: ZoneId = ZoneId.of("Asia/Shanghai")

    // 2024-01-10 是周三
    private val dayMs = 86_400_000L

    @Test
    fun `day start respects timezone`() {
        // 2024-01-10T12:00Z：UTC 当天 0 点 = 10*dayMs；上海已是 20:00，同一本地日
        val t = 10 * dayMs + 12 * 3_600_000L
        assertEquals(10 * dayMs, dayStartMs(t, utc))
        assertEquals(10 * dayMs - 8 * 3_600_000L, dayStartMs(t, cst))
    }

    @Test
    fun `average speed rounds down and guards short sessions`() {
        // 30000 字 / 120 分钟 = 250 字/分钟
        assertEquals(250, averageCharsPerMinute(30_000L, 120 * 60_000L))
        // 不足 1 分钟 → 0；无进度 → 0
        assertEquals(0, averageCharsPerMinute(30_000L, 59_999L))
        assertEquals(0, averageCharsPerMinute(0L, 10 * 60_000L))
    }

    @Test
    fun `reading days span inclusive`() {
        // 周一 0 点 ~ 周三 23 点（UTC）→ 3 天
        val first = 10 * dayMs
        val last = 12 * dayMs + 23 * 3_600_000L
        assertEquals(3L, readingDaysSpan(first, last, utc))
        assertEquals(1L, readingDaysSpan(first, first + 3_600_000L, utc))
        assertEquals(0L, readingDaysSpan(0L, last, utc))
        assertEquals(0L, readingDaysSpan(last, first, utc))
    }

    @Test
    fun `daily buckets fill missing days with zero`() {
        val today = 10 * dayMs
        val sessions = listOf(
            (today - 2 * dayMs) to 30 * 60_000L,
            (today - 2 * dayMs) to 10 * 60_000L, // 同日两本书合并
            today to 5 * 60_000L,
        )
        val buckets = dailyBuckets(sessions, today + 1000L, days = 4, zone = utc)
        assertEquals(4, buckets.size)
        assertEquals(today - 3 * dayMs, buckets[0].first)
        assertEquals(0L, buckets[0].second) // 前天无记录
        assertEquals(40 * 60_000L, buckets[1].second) // 同日聚合
        assertEquals(0L, buckets[2].second)
        assertEquals(5 * 60_000L, buckets[3].second) // 今天
    }

    @Test
    fun `week and month aggregation`() {
        // 2024-01-01 是周一；01-10 周三 = epoch 第 19732 天
        val jan10 = 19732 * dayMs
        val wed = jan10 + 12 * 3_600_000L
        assertEquals(19730 * dayMs, weekStartMs(wed, utc)) // 01-08 周一
        assertEquals(19723 * dayMs, monthStartMs(wed, utc)) // 01-01
        val sessions = listOf(
            (19730 * dayMs) to 60 * 60_000L, // 本周一 1 小时
            (19725 * dayMs) to 30 * 60_000L, // 01-03，本月更早 30 分
        )
        assertEquals(60 * 60_000L, sumSessionsBetween(sessions, weekStartMs(wed, utc), wed))
        assertEquals(90 * 60_000L, sumSessionsBetween(sessions, monthStartMs(wed, utc), wed))
    }

    @Test
    fun `duration formatting`() {
        assertEquals("不足 1 分钟", formatDurationZh(30_000L))
        assertEquals("45 分钟", formatDurationZh(45 * 60_000L))
        assertEquals("3 小时 25 分", formatDurationZh((3 * 60 + 25) * 60_000L))
        assertEquals("2 小时", formatDurationZh(120 * 60_000L))
    }
}
