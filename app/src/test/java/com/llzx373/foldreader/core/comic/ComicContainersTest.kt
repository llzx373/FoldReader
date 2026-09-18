package com.llzx373.foldreader.core.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicContainersTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val zipHead = bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    private val rar4Head = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x01)
    private val rar5Head = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val sevenZipHead = bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0x00, 0x04)

    private fun tarHead(): ByteArray = ByteArray(512).also {
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(it, 257)
    }

    @Test
    fun `魔数判定各容器`() {
        assertEquals(ComicContainer.ZIP, ComicContainers.fromMagic(zipHead))
        assertEquals(ComicContainer.RAR, ComicContainers.fromMagic(rar4Head))
        assertEquals(ComicContainer.RAR, ComicContainers.fromMagic(rar5Head))
        assertEquals(ComicContainer.SEVEN_ZIP, ComicContainers.fromMagic(sevenZipHead))
        assertEquals(ComicContainer.TAR, ComicContainers.fromMagic(tarHead()))
        assertNull(ComicContainers.fromMagic("hello world".toByteArray()))
    }

    @Test
    fun `扩展名判定`() {
        assertEquals(ComicContainer.ZIP, ComicContainers.fromExtension("a.CBZ"))
        assertEquals(ComicContainer.RAR, ComicContainers.fromExtension("a.cbr"))
        assertEquals(ComicContainer.TAR, ComicContainers.fromExtension("a.cbt"))
        assertEquals(ComicContainer.SEVEN_ZIP, ComicContainers.fromExtension("a.cb7"))
        assertNull(ComicContainers.fromExtension("a.epub"))
        assertNull(ComicContainers.fromExtension(null))
    }

    @Test
    fun `魔数优先于错标的扩展名`() {
        // 相当常见：后缀写了 cbr，实际是 zip
        assertEquals(
            ComicContainer.ZIP,
            ComicContainers.detect("book.cbr", null, zipHead),
        )
        // 认不出魔数（空头）时退回扩展名
        assertEquals(
            ComicContainer.RAR,
            ComicContainers.detect("book.cbr", null, ByteArray(0)),
        )
    }

    @Test
    fun `mime 兜底`() {
        assertEquals(
            ComicContainer.ZIP,
            ComicContainers.detect("noext", ComicContainers.CBZ_MIME, ByteArray(0)),
        )
        assertEquals(
            ComicContainer.RAR,
            ComicContainers.detect("noext", "application/x-rar-compressed", ByteArray(0)),
        )
        assertNull(ComicContainers.detect("a.bin", "application/octet-stream", ByteArray(0)))
    }
}
