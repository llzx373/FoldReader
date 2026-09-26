package com.llzx373.foldreader.core.ai.android

import com.llzx373.foldreader.core.ocr.ModelCatalog
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.test.runTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 模型导入/校验/删除（M21，R7）：文件名匹配、全量 SHA-256 校验、
 * 错误文件明确报错、临时文件清理、就绪判据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelManagerTest {

    private lateinit var context: android.content.Context
    private lateinit var manager: ModelManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "models").deleteRecursively()
        manager = ModelManager(context)
    }

    /**
     * 成功导入分支需要与清单哈希一致的真实模型文件（数 MB 二进制，不进仓库），
     * 单测改覆盖：sha256 工具正确性 + 失败分支（未知文件/哈希不符/流失败）+
     * 就绪判据（文件落位后的 status/ocrReady/bubbleReady/delete）。
     * 成功分支的 rename 落位与失败分支共用同一段校验代码，真机导入时验证。
     */
    private fun placeModel(specId: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        val spec = ModelCatalog.byId(specId)!!
        val file = File(File(context.filesDir, "models").apply { mkdirs() }, spec.fileName)
        file.writeBytes(bytes)
    }

    @Test
    fun `sha256 工具对已知内容算对`() = runTest {
        val file = File(context.filesDir, "hash.bin")
        file.writeBytes("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelManager.sha256(file),
        )
    }

    @Test
    fun `文件名不在清单内报 UnknownFile`() = runTest {
        val result = manager.import("something_else.onnx") { ByteArrayInputStream(byteArrayOf(1)) }
        assertEquals(ModelManager.ImportResult.UnknownFile, result)
    }

    @Test
    fun `无文件名报 UnknownFile`() = runTest {
        val result = manager.import(null) { ByteArrayInputStream(byteArrayOf(1)) }
        assertEquals(ModelManager.ImportResult.UnknownFile, result)
    }

    @Test
    fun `文件名匹配但哈希不符报 HashMismatch 且不留残留文件`() = runTest {
        val spec = ModelCatalog.DET
        val result = manager.import(spec.fileName) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        assertTrue(result is ModelManager.ImportResult.HashMismatch)
        assertEquals(spec, (result as ModelManager.ImportResult.HashMismatch).spec)
        val dir = File(context.filesDir, "models")
        assertFalse(File(dir, spec.fileName).exists())
        assertFalse(File(dir, "${spec.fileName}.importing").exists())
        assertFalse(manager.isReady(spec))
    }

    @Test
    fun `流打开失败报 IoError`() = runTest {
        val result = manager.import(ModelCatalog.DET.fileName) { null }
        assertEquals(ModelManager.ImportResult.IoError, result)
    }

    @Test
    fun `就绪判据 ocrReady 与 bubbleReady`() {
        assertFalse(manager.ocrReady())
        assertFalse(manager.bubbleReady())
        placeModel("det")
        assertFalse(manager.ocrReady()) // 只有 det 没有 rec
        placeModel("rec_ja")
        assertTrue(manager.ocrReady()) // det + 任一 rec
        assertFalse(manager.bubbleReady())
        placeModel("bubble")
        assertTrue(manager.bubbleReady())
        assertEquals(listOf("rec_ja"), manager.importedRecs().map { it.id })
    }

    @Test
    fun `slots 列出全部条目就绪态`() {
        placeModel("rec_ch")
        val slots = manager.slots().associateBy { it.spec }
        assertEquals(ModelCatalog.ALL.size, slots.size)
        assertTrue(slots[ModelCatalog.REC_CH]!!.ready)
        assertFalse(slots[ModelCatalog.REC_CH]!!.custom)
        assertFalse(slots[ModelCatalog.DET]!!.ready)
    }

    @Test
    fun `删除后状态回落`() {
        placeModel("det")
        placeModel("rec_en")
        assertTrue(manager.ocrReady())
        assertTrue(manager.delete("det"))
        assertFalse(manager.ocrReady())
        assertFalse(manager.delete("nonexistent"))
    }

    // ---- M24：自定义模型槽位 + 内置模型铺底 ----

    @Test
    fun `自定义模型任意字节导入成功且不校验哈希`() = runTest {
        val spec = ModelCatalog.DET
        val result = manager.importCustom(spec) { ByteArrayInputStream(byteArrayOf(9, 9, 9)) }
        assertEquals(ModelManager.ImportResult.CustomSuccess(spec), result)
        assertTrue(manager.customFileOf(spec).isFile)
        assertTrue(manager.isReady(spec))
        assertFalse(manager.fileOf(spec).exists()) // 不动官方文件
    }

    @Test
    fun `自定义模型空文件报 IoError 且不留残留`() = runTest {
        val spec = ModelCatalog.BUBBLE
        val result = manager.importCustom(spec) { ByteArrayInputStream(ByteArray(0)) }
        assertEquals(ModelManager.ImportResult.IoError, result)
        assertFalse(manager.customFileOf(spec).exists())
        assertFalse(File(File(context.filesDir, "models"), "${spec.id}.custom.importing").exists())
    }

    @Test
    fun `自定义模型流打开失败报 IoError`() = runTest {
        val result = manager.importCustom(ModelCatalog.DET) { null }
        assertEquals(ModelManager.ImportResult.IoError, result)
    }

    @Test
    fun `resolvedFileOf 自定义优先于官方`() = runTest {
        val spec = ModelCatalog.REC_CH
        placeModel("rec_ch", byteArrayOf(1, 1, 1))
        assertEquals(manager.fileOf(spec), manager.resolvedFileOf(spec))
        manager.importCustom(spec) { ByteArrayInputStream(byteArrayOf(2, 2, 2)) }
        assertEquals(manager.customFileOf(spec), manager.resolvedFileOf(spec))
    }

    @Test
    fun `deleteCustom 只删自定义且状态回落到官方`() = runTest {
        val spec = ModelCatalog.REC_EN
        placeModel("rec_en", byteArrayOf(1, 1, 1))
        manager.importCustom(spec) { ByteArrayInputStream(byteArrayOf(2, 2, 2)) }
        assertTrue(manager.slots().first { it.spec == spec }.custom)
        assertTrue(manager.deleteCustom("rec_en"))
        val slot = manager.slots().first { it.spec == spec }
        assertFalse(slot.custom)
        assertTrue(slot.official)
        assertTrue(slot.ready)
        assertEquals(manager.fileOf(spec), manager.resolvedFileOf(spec))
    }

    @Test
    fun `ocrReady 接受仅有自定义模型的槽位`() = runTest {
        manager.importCustom(ModelCatalog.DET) { ByteArrayInputStream(byteArrayOf(1)) }
        manager.importCustom(ModelCatalog.REC_JA) { ByteArrayInputStream(byteArrayOf(1)) }
        assertTrue(manager.ocrReady())
        assertEquals(listOf("rec_ja"), manager.importedRecs().map { it.id })
    }

    @Test
    fun `lite 变体 seedBundledModels 恒为空转`() = runTest {
        // 单测跑在 lite 变体下，BuildConfig.BUNDLED_MODELS = false：
        // 铺底必须零成本返回 0 且不写标记文件
        assertEquals(0, manager.seedBundledModels())
        assertFalse(File(File(context.filesDir, "models"), ModelManager.BUNDLED_SEED_MARKER).exists())
    }
}
