package com.llzx373.foldreader.core.format

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingDetectorTest {

    private val gbk = Charset.forName("GBK")
    private val gb18030 = Charset.forName("GB18030")
    private val big5 = Charset.forName("Big5")

    @Test
    fun `UTF-8 BOM 直接识别`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "第一章 测试".toByteArray(Charsets.UTF_8)
        val detection = EncodingDetector.detect(bytes)

        assertEquals(Charsets.UTF_8, detection.charset)
        assertEquals(1.0f, detection.confidence, 0.0001f)
    }

    @Test
    fun `UTF-8 无 BOM 中文文本识别为 UTF-8`() {
        val text = "第一章 天外飞仙。这是一段用于编码检测的中文文本，包含标点和数字123。".repeat(5)
        val detection = EncodingDetector.detect(text.toByteArray(Charsets.UTF_8))

        assertEquals(Charsets.UTF_8, detection.charset)
        assertTrue(detection.confidence >= 0.9f)
    }

    @Test
    fun `纯 ASCII 文本识别为 UTF-8`() {
        val detection = EncodingDetector.detect("hello world, plain ascii 12345.".toByteArray())
        assertEquals(Charsets.UTF_8, detection.charset)
    }

    @Test
    fun `UTF-16LE BOM 识别`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "第一章 测试文本".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, EncodingDetector.detect(bytes).charset)
    }

    @Test
    fun `UTF-16BE BOM 识别`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "第一章 测试文本".toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, EncodingDetector.detect(bytes).charset)
    }

    @Test
    fun `GBK 简体中文识别并可无损解码`() {
        val text = "第一章 这来说们国个没有发现对头时间经过，会开关系么将两些于学书长体。".repeat(20)
        val bytes = text.toByteArray(gbk)
        val detection = EncodingDetector.detect(bytes)

        assertEquals(gbk, detection.charset)
        assertEquals(text, String(bytes, detection.charset))
    }

    @Test
    fun `Big5 繁体中文识别并可无损解码`() {
        val text = "第一章 這來說們國個沒有發現對頭時間經過，會開關係麼將兩些於學書長體。".repeat(20)
        val bytes = text.toByteArray(big5)
        val detection = EncodingDetector.detect(bytes)

        assertEquals(big5, detection.charset)
        assertEquals(text, String(bytes, detection.charset))
    }

    @Test
    fun `GB18030 四字节字符识别为 GB18030`() {
        val text = "测试𠀀字符 GB18030 四字节编码，𠀁𠀂𠀃。".repeat(10)
        val bytes = text.toByteArray(gb18030)
        val detection = EncodingDetector.detect(bytes)

        assertEquals(gb18030, detection.charset)
        assertEquals(text, String(bytes, detection.charset))
    }

    @Test
    fun `UTF-16LE 无 BOM 纯 ASCII 内容识别为 UTF-16LE`() {
        val bytes = "Chapter 1 plain ascii text only, no bom at all. 1234567890."
            .toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, EncodingDetector.detect(bytes).charset)
    }

    @Test
    fun `UTF-16BE 无 BOM 纯 ASCII 内容识别为 UTF-16BE`() {
        val bytes = "Chapter 1 plain ascii text only, no bom at all. 1234567890."
            .toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, EncodingDetector.detect(bytes).charset)
    }

    @Test
    fun `BOM 长度识别`() {
        assertEquals(3, EncodingDetector.bomLengthOf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41)))
        assertEquals(2, EncodingDetector.bomLengthOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)))
        assertEquals(2, EncodingDetector.bomLengthOf(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x41)))
        assertEquals(0, EncodingDetector.bomLengthOf("abc".toByteArray()))
    }
}
