package com.llzx373.foldreader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHasherTest {

    private fun hashOf(data: ByteArray): String =
        ContentHasher.hash(data.size.toLong()) { offset, length ->
            data.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

    @Test
    fun `相同内容与长度哈希一致`() {
        val data = "第一章 哈希测试内容。".repeat(500).toByteArray()
        assertEquals(hashOf(data), hashOf(data.copyOf()))
    }

    @Test
    fun `内容不同哈希不同`() {
        val a = "甲书内容".repeat(500).toByteArray()
        val b = "乙书内容".repeat(500).toByteArray()
        assertNotEquals(hashOf(a), hashOf(b))
    }

    @Test
    fun `同内容不同长度哈希不同`() {
        val a = "同一本书".repeat(500).toByteArray()
        val b = "同一本书".repeat(600).toByteArray()
        assertNotEquals(hashOf(a), hashOf(b))
    }

    @Test
    fun `尾部差异可被捕获`() {
        val head = "相同的前缀内容".repeat(2000).toByteArray()
        val a = head + "结局甲".toByteArray()
        val b = head + "结局乙".toByteArray()
        assertNotEquals(hashOf(a), hashOf(b))
    }

    @Test
    fun `空文件哈希稳定`() {
        assertEquals(hashOf(ByteArray(0)), hashOf(ByteArray(0)))
        assertEquals(64, hashOf(ByteArray(0)).length)
    }
}
