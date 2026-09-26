package com.llzx373.foldreader.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单：字段完整、id 唯一、SHA-256 已钉版（64 位 hex）。
 *
 * 「清单哈希已钉版」用例是防呆闸门：占位符（TODO_ 前缀）会让它失败，
 * 防止没填真实哈希就把 M21 收尾。
 */
class ModelCatalogTest {

    @Test
    fun `清单条目字段完整且 id 文件名唯一`() {
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.id }.distinct().size)
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.fileName }.distinct().size)
        for (spec in ModelCatalog.ALL) {
            assertTrue(spec.id.isNotBlank())
            assertTrue(spec.fileName.endsWith(".onnx"))
            assertTrue(spec.purpose.isNotBlank())
            assertTrue(spec.officialUrl.startsWith("https://"))
        }
    }

    @Test
    fun `清单哈希已钉版`() {
        val hex = Regex("[0-9a-f]{64}")
        for (spec in ModelCatalog.ALL) {
            assertTrue("${spec.id} 的 sha256 还是占位符", hex.matches(spec.sha256))
            assertTrue("${spec.id} 的 sizeBytes 未填", spec.sizeBytes > 0L)
        }
    }

    @Test
    fun `按文件名与 id 查找`() {
        assertNotNull(ModelCatalog.byFileName(ModelCatalog.DET.fileName))
        assertNull(ModelCatalog.byFileName("random_model.onnx"))
        assertEquals(ModelCatalog.REC_JA, ModelCatalog.byId("rec_ja"))
        assertNull(ModelCatalog.byId("nope"))
    }

    @Test
    fun `rec 清单含中英日三种语言`() {
        assertEquals(listOf("rec_ch", "rec_en", "rec_ja"), ModelCatalog.RECS.map { it.id })
    }
}
