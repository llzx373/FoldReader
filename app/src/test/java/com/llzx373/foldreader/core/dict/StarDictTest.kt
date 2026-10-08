package com.llzx373.foldreader.core.dict

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarDictTest {

    /** 在内存里构造一本小词典：ifo 文本 + idx 字节 + dict 字节。 */
    private class Fixture(
        sameTypeSequence: String = "m",
        entries: List<Pair<String, String>>,
    ) {
        val dictBytes: ByteArray
        val idxBytes: ByteArray
        val ifoText: String

        init {
            val dictOut = ByteArrayOutputStream()
            val idxOut = ByteArrayOutputStream()
            entries.forEach { (word, def) ->
                val defBytes = def.toByteArray(Charsets.UTF_8)
                val offset = dictOut.size()
                dictOut.write(defBytes)
                idxOut.write(word.toByteArray(Charsets.UTF_8))
                idxOut.write(0)
                fun uint32(v: Long) {
                    idxOut.write(((v ushr 24) and 0xFF).toInt())
                    idxOut.write(((v ushr 16) and 0xFF).toInt())
                    idxOut.write(((v ushr 8) and 0xFF).toInt())
                    idxOut.write((v and 0xFF).toInt())
                }
                uint32(offset.toLong())
                uint32(defBytes.size.toLong())
            }
            dictBytes = dictOut.toByteArray()
            idxBytes = idxOut.toByteArray()
            ifoText = """
                StarDict's dict ifo file
                version=2.4.2
                wordcount=${entries.size}
                bookname=测试词典
                sametypesequence=$sameTypeSequence
            """.trimIndent()
        }

        fun dictionary(): StarDictDictionary {
            val ifo = StarDictIfoParser.parse(ifoText) ?: error("fixture ifo 不合法")
            val idx = StarDictIndex.parse(ByteArrayInputStream(idxBytes))
            return StarDictDictionary(ifo, idx) { offset, size ->
                dictBytes.copyOfRange(offset.toInt(), offset.toInt() + size)
            }
        }
    }

    @Test
    fun `ifo 解析拿到书名与词数`() {
        val ifo = StarDictIfoParser.parse(Fixture(entries = listOf("a" to "b")).ifoText)!!
        assertEquals("测试词典", ifo.bookName)
        assertEquals(1L, ifo.wordCount)
        assertEquals("m", ifo.sameTypeSequence)
        assertEquals(32, ifo.idxFileBits)
    }

    @Test
    fun `ifo 缺 magic 或缺 wordcount 视为非法`() {
        assertNull(StarDictIfoParser.parse("not a dict\nwordcount=1"))
        assertNull(StarDictIfoParser.parse("StarDict's dict ifo file\nbookname=x"))
    }

    @Test
    fun `查词命中与未命中`() {
        val dict = Fixture(
            entries = listOf("apple" to "n. 苹果", "banana" to "n. 香蕉", "汉字" to "中文词目"),
        ).dictionary()
        assertEquals("n. 苹果", dict.lookup("apple"))
        assertEquals("中文词目", dict.lookup("汉字"))
        assertNull(dict.lookup("orange"))
        assertNull(dict.lookup(""))
        assertNull(dict.lookup("   "))
    }

    @Test
    fun `查词大小写回落——小写词典命中大写查询`() {
        val dict = Fixture(entries = listOf("apple" to "n. 苹果")).dictionary()
        assertEquals("n. 苹果", dict.lookup("Apple"))
        assertEquals("n. 苹果", dict.lookup("APPLE"))
    }

    @Test
    fun `查词大小写回落——大写词目命中首字母大写`() {
        val dict = Fixture(entries = listOf("Hello" to "int. 你好")).dictionary()
        assertEquals("int. 你好", dict.lookup("hello"))
    }

    @Test
    fun `同一词目多条释义按序拼接`() {
        val dict = Fixture(
            entries = listOf("run" to "v. 跑", "run" to "n. 运行"),
        ).dictionary()
        assertEquals("v. 跑\n\nn. 运行", dict.lookup("run"))
    }

    @Test
    fun `idx 乱序文件解析后仍能二分命中`() {
        // fixture 的写入顺序即乱序（z 在 a 前），解析后内部排序
        val dict = Fixture(
            entries = listOf("zebra" to "n. 斑马", "apple" to "n. 苹果", "mango" to "n. 芒果"),
        ).dictionary()
        assertEquals("n. 苹果", dict.lookup("apple"))
        assertEquals("n. 斑马", dict.lookup("zebra"))
        assertEquals("n. 芒果", dict.lookup("mango"))
    }

    @Test
    fun `html 释义剥标签并解码实体`() {
        val dict = Fixture(
            sameTypeSequence = "h",
            entries = listOf("tag" to "<b>粗</b> &amp; <i>斜</i>&nbsp;尾"),
        ).dictionary()
        assertEquals("粗 & 斜 尾", dict.lookup("tag"))
    }

    @Test
    fun `dict dz 解压还原原始字节`() {
        val raw = "释义数据".repeat(100).toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw) }
        val out = ByteArrayOutputStream()
        val total = inflateDictDz(ByteArrayInputStream(gz.toByteArray()), out)
        assertEquals(raw.size.toLong(), total)
        assertTrue(raw.contentEquals(out.toByteArray()))
    }

    @Test
    fun `RandomAccessDictData 按区间读取真实文件`() {
        val dir = java.nio.file.Files.createTempDirectory("stardict-test").toFile()
        try {
            val file = File(dir, "t.dict")
            file.writeBytes("0123456789".toByteArray())
            RandomAccessDictData(file).use { data ->
                assertEquals("234", String(data.read(2, 3)))
                val dict = StarDictDictionary(
                    ifo = StarDictIfo(bookName = "t", wordCount = 1),
                    entries = listOf(
                        StarDictEntry("w", "w".toByteArray(), offset = 5, size = 3),
                    ),
                    data = data,
                )
                assertEquals("567", dict.lookup("w"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `idxfilebits 64 的索引解析`() {
        val idxOut = ByteArrayOutputStream()
        fun uint64(v: Long) {
            for (shift in 56 downTo 0 step 8) idxOut.write(((v ushr shift) and 0xFF).toInt())
        }
        idxOut.write("w".toByteArray())
        idxOut.write(0)
        uint64(2)
        uint64(3)
        val entries = StarDictIndex.parse(ByteArrayInputStream(idxOut.toByteArray()), idxFileBits = 64)
        assertEquals(1, entries.size)
        assertEquals(2L, entries[0].offset)
        assertEquals(3, entries[0].size)
    }
}
