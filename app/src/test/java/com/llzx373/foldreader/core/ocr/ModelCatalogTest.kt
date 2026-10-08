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
            // sha256 = null 的槽位是「暂不钉官方版」的显式决定（如 inpaint），其余一律钉死
            val hash = spec.sha256 ?: continue
            assertTrue("${spec.id} 的 sha256 还是占位符", hex.matches(hash))
            assertTrue("${spec.id} 的 sizeBytes 未填", spec.sizeBytes > 0L)
        }
    }

    @Test
    fun `inpaint 槽位暂不钉官方版`() {
        // 选型评审（M31）落定前，inpaint 只接受「导入自定义」；
        // 这个用例是防呆闸门：官方版钉版后要改回非空哈希 + 真实字节数
        assertNull(ModelCatalog.INPAINT.sha256)
        assertEquals("inpaint", ModelCatalog.INPAINT.id)
        assertTrue(ModelCatalog.ALL.contains(ModelCatalog.INPAINT))
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
