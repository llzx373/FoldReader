package com.llzx373.foldreader.core.tts.android

import android.os.Looper
import com.llzx373.foldreader.core.tts.TtsSegment
import com.llzx373.foldreader.core.tts.TtsSleepOption
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 睡眠定时与新会话的关系（B5）：换章直接 speak 开新会话时，旧会话的睡眠定时
 * 必须一并清掉（与 stop 路径同口径）——否则旧定时到点停掉的是新会话。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderTtsControllerSleepTimerTest {

    private fun newController() = ReaderTtsController(RuntimeEnvironment.getApplication())

    private fun segments() = listOf(TtsSegment("第一句。", 0), TtsSegment("第二句。", 4))

    @Test
    fun `新朗读会话开始时清除旧睡眠定时，旧定时到点不停新会话`() {
        val controller = newController()
        controller.speak(1L, segments(), bookTitle = "书")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state.value.playing)

        controller.setSleepTimer(TtsSleepOption.MIN_15)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TtsSleepOption.MIN_15, controller.state.value.sleepOption)

        // 换章直接 speak（未走 stop）：新会话开始，旧定时应被清掉
        controller.speak(1L, segments(), bookTitle = "书")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state.value.playing)
        assertNull("新会话不应继承旧睡眠定时", controller.state.value.sleepOption)

        // 推进主线程时钟越过旧定时到点：新会话仍在播，没有被误停
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20))
        assertTrue("旧睡眠定时到点不得停掉新会话", controller.state.value.playing)
        assertNull(controller.state.value.error)
    }
}
