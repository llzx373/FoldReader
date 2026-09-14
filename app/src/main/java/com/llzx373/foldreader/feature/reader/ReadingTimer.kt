package com.llzx373.foldreader.feature.reader

class ReadingTimer {

    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    val isRunning: Boolean get() = runningSince != null

    @Synchronized
    fun start(nowMs: Long) {
        if (runningSince == null) runningSince = nowMs
    }

    @Synchronized
    fun stop(nowMs: Long) {
        runningSince?.let { accumulatedMs += (nowMs - it).coerceAtLeast(0L) }
        runningSince = null
    }

    @Synchronized
    fun totalMs(nowMs: Long): Long =
        accumulatedMs + (runningSince?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)
}

class ReadingSpeedTracker(
    private val windowMs: Long = 5 * 60_000L,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun feed(offset: Long, nowMs: Long) {
        if (samples.lastOrNull()?.second == offset) return
        samples.addLast(nowMs to offset)
        while (samples.size > 2 && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun charsPerMinute(): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val dt = last.first - first.first
        val dc = last.second - first.second
        if (dt <= 0L || dc <= 0L) return null
        return dc.toDouble() / dt * 60_000.0
    }

    fun remainingMinutes(totalChars: Long, currentOffset: Long): Double? =
        charsPerMinute()?.let { speed ->
            (totalChars - currentOffset).coerceAtLeast(0L).toDouble() / speed
        }

    @Synchronized
    fun reset() = samples.clear()
}

fun formatRemainingTime(minutes: Double): String {
    val total = minutes.toLong().coerceAtLeast(0L)
    val hours = total / 60
    val mins = total % 60
    return when {
        hours > 0 && mins > 0 -> "约剩 $hours 小时 $mins 分钟"
        hours > 0 -> "约剩 $hours 小时"
        else -> "约剩 $mins 分钟"
    }
}
