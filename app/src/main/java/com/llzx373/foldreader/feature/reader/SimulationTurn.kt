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
    private val badFrameMs: Float = 24f,
    private val windowLimit: Int = 10,
    private val badFrameLimit: Int = 5,
) {
    private var frames = 0
    private var badFrames = 0

    fun noteFrame(frameMs: Float) {
        frames++
        if (frameMs > badFrameMs) badFrames++
    }

    fun shouldDegrade(): Boolean = frames >= windowLimit && badFrames >= badFrameLimit

    fun reset() {
        frames = 0
        badFrames = 0
    }
}

fun curlShadowAlpha(progress: Float): Float =
    (0.22f * (1f - progress.coerceIn(0f, 1f) * 0.5f))

fun curlScaleY(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return 1f - 0.04f * kotlin.math.sin(p * Math.PI.toFloat())
}
