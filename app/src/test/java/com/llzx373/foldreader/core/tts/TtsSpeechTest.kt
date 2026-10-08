package com.llzx373.foldreader.core.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeechTest {

    @Test
    fun `语速钳制在 0_5 到 2_0`() {
        assertEquals(0.5f, TtsSpeech.clampRate(0.1f), 0.001f)
        assertEquals(2.0f, TtsSpeech.clampRate(3.0f), 0.001f)
        assertEquals(1.3f, TtsSpeech.clampRate(1.3f), 0.001f)
        assertEquals(1.0f, TtsSpeech.clampRate(Float.NaN), 0.001f)
    }

    @Test
    fun `音调钳制在 0_5 到 2_0`() {
        assertEquals(0.5f, TtsSpeech.clampPitch(-1f), 0.001f)
        assertEquals(2.0f, TtsSpeech.clampPitch(9f), 0.001f)
        assertEquals(1.0f, TtsSpeech.clampPitch(Float.NaN), 0.001f)
    }

    @Test
    fun `展示文案`() {
        assertEquals("1.0×", TtsSpeech.formatRate(1.0f))
        assertEquals("1.5×", TtsSpeech.formatRate(1.5f))
        assertEquals("0.8", TtsSpeech.formatPitch(0.8f))
    }
}
