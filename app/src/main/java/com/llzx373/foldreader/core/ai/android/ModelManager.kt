package com.llzx373.foldreader.core.ai.android

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.ocr.ModelCatalog
import com.llzx373.foldreader.core.ocr.OcrModelSpec
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR/气泡检测模型管理（M21，R7/R8/R9）。
 *
 * 模型不进 APK：用户自行下载（设置页只给官方/镜像地址指引，应用不联网下载），
 * 经 SAF 导入 → 按 [ModelCatalog] 清单匹配文件名 → 流式全量 SHA-256 校验 →
 * 落 `filesDir/models/`。校验失败一律删除临时/目标文件并给出明确错误。
 */
class ModelManager(private val context: Context) {

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    sealed interface ImportResult {
        data class Success(val spec: OcrModelSpec) : ImportResult

        /** 文件名不在清单内：不是我们能用的模型。 */
        data object UnknownFile : ImportResult

        /** 文件名匹配但哈希不符：下错版本 / 文件损坏 / 被篡改。 */
        data class HashMismatch(val spec: OcrModelSpec) : ImportResult

        data object IoError : ImportResult
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

    fun fileOf(spec: OcrModelSpec): File = File(modelsDir, spec.fileName)

    fun isReady(spec: OcrModelSpec): Boolean = fileOf(spec).isFile

    /** 设置页清单：每个条目 → 是否已导入。 */
    fun status(): List<Pair<OcrModelSpec, Boolean>> = ModelCatalog.ALL.map { it to isReady(it) }

    fun delete(modelId: String): Boolean =
        ModelCatalog.byId(modelId)?.let { fileOf(it).delete() } ?: false

    /** OCR 文本层就绪 = det + 至少一个语言 rec。 */
    fun ocrReady(): Boolean =
        isReady(ModelCatalog.DET) && ModelCatalog.RECS.any { isReady(it) }

    /** 漫画翻译（气泡检测）就绪 = OCR 就绪 + 气泡模型。 */
    fun bubbleReady(): Boolean = ocrReady() && isReady(ModelCatalog.BUBBLE)

    /** 已导入的 rec 语言列表（OCR 时按内容选词典/模型）。 */
    fun importedRecs(): List<OcrModelSpec> = ModelCatalog.RECS.filter { isReady(it) }

    companion object {
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
