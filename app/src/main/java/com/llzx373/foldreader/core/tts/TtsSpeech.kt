package com.llzx373.foldreader.core.tts

/**
 * TTS 语速/音调（M26）的取值域与展示文案（纯 JVM）。
 *
 * 全局默认值存 DataStore（设置页），朗读中面板做的是**当次**临时调整，
 * 不写回设置——下次朗读仍按全局默认起。
 */
object TtsSpeech {

    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f
    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f
    const val DEFAULT_RATE = 1.0f
    const val DEFAULT_PITCH = 1.0f

    fun clampRate(rate: Float): Float =
        if (rate.isNaN()) DEFAULT_RATE else rate.coerceIn(MIN_RATE, MAX_RATE)

    fun clampPitch(pitch: Float): Float =
        if (pitch.isNaN()) DEFAULT_PITCH else pitch.coerceIn(MIN_PITCH, MAX_PITCH)

    /** 滑杆展示文案：1.0× / 1.5×。 */
    fun formatRate(rate: Float): String = "%.1f×".format(clampRate(rate))

    fun formatPitch(pitch: Float): String = "%.1f".format(clampPitch(pitch))
}
