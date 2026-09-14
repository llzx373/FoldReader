package com.llzx373.foldreader.core.reader

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 当日 0 点的 epoch 毫秒（本地时区）。 */
fun dayStartMs(epochMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()

/** 平均速度（字/分钟）：按已读字符数 / 累计时长估算；时长不足 1 分钟返回 0。 */
fun averageCharsPerMinute(charsRead: Long, totalMillis: Long): Int {
    if (charsRead <= 0L || totalMillis < 60_000L) return 0
    return (charsRead * 60_000L / totalMillis).toInt()
}

/** 阅读天数：首次~最后阅读的日期跨度（含首尾两天）；任一时间为 0 返回 0。 */
fun readingDaysSpan(firstReadAt: Long, lastReadAt: Long, zone: ZoneId): Long {
    if (firstReadAt <= 0L || lastReadAt < firstReadAt) return 0
    val firstDay = dayStartMs(firstReadAt, zone)
    val lastDay = dayStartMs(lastReadAt, zone)
    return (lastDay - firstDay) / 86_400_000L + 1
}

/**
 * 近 [days] 天逐日时长分桶：sessions 为 (dayStartMs, durationMs)，
 * 缺失的日期补 0，按日期升序返回（最后一个元素是今天）。
 */
fun dailyBuckets(
    sessions: List<Pair<Long, Long>>,
    todayMs: Long,
    days: Int,
    zone: ZoneId,
): List<Pair<Long, Long>> {
    val today = dayStartMs(todayMs, zone)
    val byDay = sessions.groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }
    return (days - 1 downTo 0).map { back ->
        val day = today - back * 86_400_000L
        day to (byDay[day] ?: 0L)
    }
}

/** 区间 [startMs, endMs] 内的时长合计。 */
fun sumSessionsBetween(sessions: List<Pair<Long, Long>>, startMs: Long, endMs: Long): Long =
    sessions.filter { it.first in startMs..endMs }.sumOf { it.second }

/** 本周一 0 点（周一为一周起点）。 */
fun weekStartMs(epochMs: Long, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 本月 1 日 0 点。 */
fun monthStartMs(epochMs: Long, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 时长中文格式化："3 小时 25 分" / "45 分钟" / "不足 1 分钟"。 */
fun formatDurationZh(millis: Long): String {
    val minutes = millis / 60_000L
    if (minutes <= 0L) return "不足 1 分钟"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (hours > 0L) {
        if (rest > 0L) "${hours} 小时 ${rest} 分" else "${hours} 小时"
    } else {
        "$rest 分钟"
    }
}

/** dayStartMs → 本地日期（供柱状图标轴）。 */
fun dayOfMonthOf(dayStartMs: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(dayStartMs).atZone(zone).dayOfMonth

fun monthOf(dayStartMs: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(dayStartMs).atZone(zone).monthValue
