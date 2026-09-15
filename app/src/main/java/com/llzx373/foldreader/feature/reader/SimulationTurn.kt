package com.llzx373.foldreader.feature.reader

enum class TurnOutcome { COMPLETE, CANCEL }

fun decideTurnOutcome(
    progress: Float,
    directedVelocityPxPerSec: Float,
    progressThreshold: Float = 0.35f,
    velocityThresholdPxPerSec: Float = 700f,
): TurnOutcome = when {
    directedVelocityPxPerSec > velocityThresholdPxPerSec -> TurnOutcome.COMPLETE
    directedVelocityPxPerSec < -velocityThresholdPxPerSec -> TurnOutcome.CANCEL
    progress >= progressThreshold -> TurnOutcome.COMPLETE
    else -> TurnOutcome.CANCEL
}

class FrameHealthMonitor(
    private val badFrameMs: Float = 34f,
    private val windowSize: Int = 30,
    private val warmupFrames: Int = 5,
) {
    private var frames = 0
    private val window = ArrayDeque<Boolean>()

    fun noteFrame(frameMs: Float) {
        frames++
        if (frames <= warmupFrames) return
        window.addLast(frameMs > badFrameMs)
        if (window.size > windowSize) window.removeFirst()
    }

    fun shouldDegrade(): Boolean =
        window.size >= windowSize && window.count { it } * 2 > windowSize

    fun reset() {
        frames = 0
        window.clear()
    }
}
