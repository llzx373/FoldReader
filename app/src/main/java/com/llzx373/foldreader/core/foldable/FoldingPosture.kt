package com.llzx373.foldreader.core.foldable

import androidx.compose.ui.geometry.Rect

enum class Posture { CLOSED, FLAT, HALF_OPENED }

enum class HingeOrientation { VERTICAL, HORIZONTAL }

enum class HingeState { FLAT, HALF_OPENED }

data class HingeInfo(
    val bounds: Rect,
    val orientation: HingeOrientation,
    val state: HingeState,
)

data class FoldingPosture(
    val posture: Posture,
    val hingeBounds: Rect?,
    val hingeOrientation: HingeOrientation?,
) {
    companion object {
        val Closed = FoldingPosture(Posture.CLOSED, null, null)
    }
}

fun HingeInfo?.toFoldingPosture(): FoldingPosture =
    when (this) {
        null -> FoldingPosture.Closed
        else -> FoldingPosture(
            posture = when (state) {
                HingeState.HALF_OPENED -> Posture.HALF_OPENED
                HingeState.FLAT -> Posture.FLAT
            },
            hingeBounds = bounds,
            hingeOrientation = orientation,
        )
    }
