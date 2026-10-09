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
    fun `dictzip 多成员串联逐成员解压拼接`() {
        // 真实 .dict.dz 是多个 gzip 成员串联：单个 GZIPInputStream 只能读到第一块
        val part1 = "第一块释义".repeat(500).toByteArray(Charsets.UTF_8)
        val part2 = "第二块释义".repeat(700).toByteArray(Charsets.UTF_8)
        val part3 = "第三块".toByteArray(Charsets.UTF_8)
        val dz = ByteArrayOutputStream()
        listOf(part1, part2, part3).forEach { part ->
            GZIPOutputStream(dz).use { it.write(part) }
        }
        val out = ByteArrayOutputStream()
        val total = inflateDictDz(ByteArrayInputStream(dz.toByteArray()), out)
        val expect = part1 + part2 + part3
        assertEquals(expect.size.toLong(), total)
        assertTrue(expect.contentEquals(out.toByteArray()))
    }

    @Test
    fun `dict dz 非 gzip 输入报错而不是静默产出空文件`() {
        val out = ByteArrayOutputStream()
        try {
            inflateDictDz(ByteArrayInputStream("not gzip".toByteArray()), out)
            org.junit.Assert.fail("应当抛 IOException")
        } catch (e: java.io.IOException) {
            // 预期
        }
        assertEquals(0, out.size())
    }

    @Test
    fun `空词目不吞 offset 和 size 字段`() {
        // 空词目（只有 \0）也带 offset/size：不消费掉会让后续条目错位
        val idxOut = ByteArrayOutputStream()
        fun uint32(v: Long) {
            for (shift in listOf(24, 16, 8, 0)) idxOut.write(((v ushr shift) and 0xFF).toInt())
        }
        idxOut.write(0) // 空词目
        uint32(0)
        uint32(4)
        idxOut.write("word".toByteArray())
        idxOut.write(0)
        uint32(4)
        uint32(3)
        val entries = StarDictIndex.parse(ByteArrayInputStream(idxOut.toByteArray()))
        assertEquals(1, entries.size)
        assertEquals("word", entries[0].word)
        assertEquals(4L, entries[0].offset)
        assertEquals(3, entries[0].size)
    }

    @Test
    fun `索引项长度非法报错而非静默截断`() {
        val idxOut = ByteArrayOutputStream()
        idxOut.write("bad".toByteArray())
        idxOut.write(0)
        idxOut.write(byteArrayOf(0, 0, 0, 0)) // offset=0
        idxOut.write(byteArrayOf(0, 0, 0, 0)) // size=0 非法
        try {
            StarDictIndex.parse(ByteArrayInputStream(idxOut.toByteArray()))
            org.junit.Assert.fail("应当抛 IOException")
        } catch (e: java.io.IOException) {
            // 预期
        }
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
