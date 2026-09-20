package com.llzx373.foldreader.feature.reader.peel

/**
 * 仿真翻页光影。立体感靠阴影，不靠透视压缩。
 *
 * 下层投影带：`clamp(|AF|/4, 12px, 80dp)`，多档渐变避免切边。
 */
const val PEEL_SHADOW_MIN_PX = 12f
const val PEEL_SHADOW_MAX_DP = 80f
const val PEEL_EDGE_SHADOW_PX = 5f

fun peelCastShadowWidth(touchToCorner: Float, density: Float): Float {
    val maxPx = PEEL_SHADOW_MAX_DP * density.coerceAtLeast(0.5f)
    return (touchToCorner / 4f).coerceIn(PEEL_SHADOW_MIN_PX, maxPx)
}

/** 纸背靠近折痕处的压暗。 */
const val PEEL_BACK_DIM_CREASE_ALPHA = 0x26

/** 纸背靠近自由角处的压暗（更浅，避免整块隔离）。 */
const val PEEL_BACK_DIM_EDGE_ALPHA = 0x0A

/** 下层投影最深处的黑。 */
const val PEEL_CAST_SHADOW_ALPHA = 0x30

/** 折痕接触影最深处。 */
const val PEEL_CREASE_CONTACT_ALPHA = 0x24

/** 纸背轮廓羽化（最外一档，内层更浅）。 */
const val PEEL_FLAP_EDGE_ALPHA = 0x12

/** 自由边纸边高光。 */
const val PEEL_EDGE_HIGHLIGHT_ALPHA = 0x16
