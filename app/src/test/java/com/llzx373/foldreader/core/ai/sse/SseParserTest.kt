package com.llzx373.foldreader.core.ai.sse

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    private fun parse(text: String): List<SseEvent> =
        Buffer().writeUtf8(text).readSseEvents().toList()

    @Test
    fun `多行 data 按规范用换行拼接`() {
        val events = parse("data: hello\ndata: world\n\n")

        assertEquals(1, events.size)
        assertEquals("hello\nworld", events[0].data)
        assertNull(events[0].event)
    }

    @Test
    fun `注释行被忽略`() {
        val events = parse(": this is a comment\ndata: real\n: another\n\n")

        assertEquals(listOf(SseEvent(null, "real")), events)
    }

    @Test
    fun `CRLF 行尾与 LF 等价`() {
        val events = parse("data: a\r\n\r\ndata: b\r\n\r\n")

        assertEquals(listOf(SseEvent(null, "a"), SseEvent(null, "b")), events)
    }

    @Test
    fun `一次写入多个事件全部解析`() {
        val events = parse("data: 一\n\ndata: 二\n\ndata: 三\n\n")

        assertEquals(listOf("一", "二", "三"), events.map { it.data })
    }

    @Test
    fun `流末未完事件被补发`() {
        val events = parse("data: 一\n\ndata: 尾巴没有空行")

        assertEquals(listOf("一", "尾巴没有空行"), events.map { it.data })
    }

    @Test
    fun `空 data 事件被忽略`() {
        // `data:` 与 `data: `（去前导空格后为空）都不派发事件
        val events = parse("data:\n\ndata: \n\ndata: real\n\n")

        assertEquals(listOf(SseEvent(null, "real")), events)
    }

    @Test
    fun `event 字段随事件携带`() {
        val events = parse("event: message_start\ndata: {}\n\ndata: 无事件名\n\n")

        assertEquals(SseEvent("message_start", "{}"), events[0])
        assertEquals(SseEvent(null, "无事件名"), events[1])
    }

    @Test
    fun `data 行只去掉一个前导空格`() {
        val events = parse("data:  两个空格保留一个\n\n")

        assertEquals(" 两个空格保留一个", events[0].data)
    }

    @Test
    fun `空输入没有事件`() {
        assertEquals(emptyList<SseEvent>(), parse(""))
    }
}
