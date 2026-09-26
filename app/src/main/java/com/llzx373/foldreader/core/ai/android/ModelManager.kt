package com.llzx373.foldreader.core.ai.android

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.BuildConfig
import com.llzx373.foldreader.core.ocr.ModelCatalog
import com.llzx373.foldreader.core.ocr.OcrModelSpec
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR/气泡检测模型管理（M21/M24，R7/R8/R9）。
 *
 * 三种来源，就绪判定一视同仁：
 * 1. **官方模型**（M21）：用户自行下载 → SAF 导入 → 按 [ModelCatalog] 清单匹配
 *    文件名 → 流式全量 SHA-256 校验 → 落 `filesDir/models/`；
 * 2. **内置模型**（M24 full 变体）：5 个官方模型打进 assets，首次启动
 *    [seedBundledModels] 铺到 `filesDir/models/`（同样过一遍 SHA-256），
 *    铺过即写标记文件，用户之后删除不重铺（尊重用户选择）；
 * 3. **自定义模型**（M24）：私有微调/其他来源的 .onnx，经 [importCustom] 落到
 *    `<id>.custom.onnx`——无法校验（清单外），导入即生效且**优先于官方文件**。
 *    rec 槽位注意：识别词典仍是内置的，自定义 rec 改了字符集输出即乱码。
 */
class ModelManager(private val context: Context) {

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    sealed interface ImportResult {
        data class Success(val spec: OcrModelSpec) : ImportResult

        /** 自定义模型导入成功（清单外无法校验，仅提示）。 */
        data class CustomSuccess(val spec: OcrModelSpec) : ImportResult

        /** 文件名不在清单内：不是我们能用的模型。 */
        data object UnknownFile : ImportResult

        /** 文件名匹配但哈希不符：下错版本 / 文件损坏 / 被篡改。 */
        data class HashMismatch(val spec: OcrModelSpec) : ImportResult

        data object IoError : ImportResult
    }

    /** 一个槽位的落位情况：[official] 官方文件在；[custom] 自定义文件在（优先生效）。 */
    data class ModelSlot(
        val spec: OcrModelSpec,
        val official: Boolean,
        val custom: Boolean,
    ) {
        val ready: Boolean get() = official || custom
    }

    /** SAF 导入入口（设置页）：[displayName] 来自 DocumentFile 查询。 */
    suspend fun import(uri: Uri, displayName: String?): ImportResult =
        import(displayName) { context.contentResolver.openInputStream(uri) }

    /** 可注入流的实现（单测直接喂字节）。 */
    suspend fun import(
        displayName: String?,
        openStream: () -> InputStream?,
    ): ImportResult = withContext(Dispatchers.IO) {
        val fileName = displayName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: return@withContext ImportResult.UnknownFile
        val spec = ModelCatalog.byFileName(fileName) ?: return@withContext ImportResult.UnknownFile
        val tmp = File(modelsDir, "$fileName.importing")
        val target = File(modelsDir, spec.fileName)
        try {
            openStream()?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.IoError
            val actual = sha256(tmp)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                tmp.delete()
                return@withContext ImportResult.HashMismatch(spec)
            }
            // 校验通过才落位（重复导入=覆盖旧文件）
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            ImportResult.Success(spec)
        } catch (_: Exception) {
            tmp.delete()
            ImportResult.IoError
        }
    }

    /**
     * 自定义模型导入（M24）：任何 .onnx 都可以进 [spec] 槽位，不做清单校验——
     * 私有微调模型的哈希我们无从知道。落 `<id>.custom.onnx`，与官方文件互不覆盖。
     */
    suspend fun importCustom(spec: OcrModelSpec, uri: Uri): ImportResult =
        importCustom(spec) { context.contentResolver.openInputStream(uri) }

    /** 可注入流的实现（单测直接喂字节）。 */
    suspend fun importCustom(
        spec: OcrModelSpec,
        openStream: () -> InputStream?,
    ): ImportResult = withContext(Dispatchers.IO) {
        val tmp = File(modelsDir, "${spec.id}.custom.importing")
        val target = customFileOf(spec)
        try {
            openStream()?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.IoError
            if (tmp.length() <= 0L) {
                tmp.delete()
                return@withContext ImportResult.IoError
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            ImportResult.CustomSuccess(spec)
        } catch (_: Exception) {
            tmp.delete()
            ImportResult.IoError
        }
    }

    fun fileOf(spec: OcrModelSpec): File = File(modelsDir, spec.fileName)

    fun customFileOf(spec: OcrModelSpec): File = File(modelsDir, "${spec.id}.custom.onnx")

    /** 实际生效的模型文件：自定义优先，其次官方。引擎与就绪判定一律走这里。 */
    fun resolvedFileOf(spec: OcrModelSpec): File =
        customFileOf(spec).takeIf { it.isFile } ?: fileOf(spec)

    fun isReady(spec: OcrModelSpec): Boolean = resolvedFileOf(spec).isFile

    /** 设置页清单：每个槽位的官方/自定义落位情况。 */
    fun slots(): List<ModelSlot> = ModelCatalog.ALL.map { spec ->
        ModelSlot(spec, official = fileOf(spec).isFile, custom = customFileOf(spec).isFile)
    }

    fun delete(modelId: String): Boolean =
        ModelCatalog.byId(modelId)?.let { fileOf(it).delete() } ?: false

    fun deleteCustom(modelId: String): Boolean =
        ModelCatalog.byId(modelId)?.let { customFileOf(it).delete() } ?: false

    /** OCR 文本层就绪 = det + 至少一个语言 rec。 */
    fun ocrReady(): Boolean =
        isReady(ModelCatalog.DET) && ModelCatalog.RECS.any { isReady(it) }

    /** 漫画翻译（气泡检测）就绪 = OCR 就绪 + 气泡模型。 */
    fun bubbleReady(): Boolean = ocrReady() && isReady(ModelCatalog.BUBBLE)

    /** 已导入的 rec 语言列表（OCR 时按内容选词典/模型）。 */
    fun importedRecs(): List<OcrModelSpec> = ModelCatalog.RECS.filter { isReady(it) }

    /**
     * full 变体的内置模型铺底（M24）：把 assets 里的官方模型复制到 filesDir/models/，
     * 复制后过一遍清单 SHA-256（防打包/读取损坏）。只在首次启动执行——
     * 铺过写 `.bundled_seeded` 标记；用户之后手动删除的模型**不重铺**（尊重删除）。
     * lite 变体（[BuildConfig.BUNDLED_MODELS] = false）与铺过后调用都是零成本空转。
     * 返回本次铺了几个（首次没铺全通常是 assets 缺失，记诊断日志用）。
     */
    suspend fun seedBundledModels(): Int = withContext(Dispatchers.IO) {
        if (!BuildConfig.BUNDLED_MODELS) return@withContext 0
        val marker = File(modelsDir, BUNDLED_SEED_MARKER)
        if (marker.exists()) return@withContext 0
        var seeded = 0
        ModelCatalog.ALL.forEach { spec ->
            if (fileOf(spec).isFile) return@forEach
            runCatching {
                context.assets.open(spec.fileName).use { input ->
                    val tmp = File(modelsDir, "${spec.fileName}.seeding")
                    tmp.outputStream().use { output -> input.copyTo(output) }
                    if (sha256(tmp).equals(spec.sha256, ignoreCase = true)) {
                        val target = fileOf(spec)
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        seeded++
                    } else {
                        tmp.delete()
                    }
                }
            }
        }
        marker.writeText("${System.currentTimeMillis()}")
        seeded
    }

    companion object {
        /** 内置模型铺底的一次性标记文件（删模型不重铺的判据）。 */
        const val BUNDLED_SEED_MARKER = ".bundled_seeded"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
