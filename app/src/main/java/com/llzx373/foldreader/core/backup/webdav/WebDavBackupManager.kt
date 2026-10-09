package com.llzx373.foldreader.core.backup.webdav

import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.backup.BackupFileNames
import com.llzx373.foldreader.core.backup.BackupManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 备份编排（M25）：上传 / 列表 / 下载恢复 + 恢复前预览。
 * 编解码完全复用 [BackupManager]（格式零改动），这一层只做「字节搬运 + 台账记账」——
 * 依赖以函数注入，纯 JVM 可测（MockWebServer + 假编解码）。
 *
 * v11 起备份是含书文件的 zip：上传先导出到 [tempDir] 临时文件再流式 PUT，
 * 下载也先落临时文件再交给本地导入链路（旧 JSON / 新 zip 都可恢复），全程不整读进内存。
 *
 * 合规：每次上传/下载记入外发历史台账（feature=WebDAV 备份，scope=文件名与大小）；
 * 未配置（[clientFor] 返回 null）时抛 [IllegalStateException]，调用方 UI 层保证入口只在
 * 已配置 + 已完成首次确认后出现。
 */
class WebDavBackupManager(
    private val exportToFile: suspend (File) -> Unit,
    private val importFromFile: suspend (File) -> BackupManager.ImportResult,
    private val previewFile: (File) -> BackupManager.BackupPreview,
    private val tempDir: () -> File,
    private val clientFor: suspend () -> WebDavClient?,
    private val gate: AiContentGate,
) {

    /** 导出并上传，返回远端文件名。 */
    suspend fun upload(): String = withContext(Dispatchers.IO) {
        val client = requireClient()
        val tmp = File(tempDir(), "webdav-upload-${UUID.randomUUID()}.zip")
        try {
            exportToFile(tmp)
            val name = BackupFileNames.timestamped()
            client.upload(name, tmp)
            gate.record(FEATURE, "上传 $name（${formatSize(tmp.length())}）", 0)
            name
        } finally {
            tmp.delete()
        }
    }

    /** 远端备份列表（只保留本应用命名的备份，最新在前）。 */
    suspend fun listBackups(): List<WebDavEntry> = withContext(Dispatchers.IO) {
        requireClient().list()
            .filter { BackupFileNames.isBackupFile(it.name) }
            .sortedByDescending { it.name }
    }

    /**
     * 恢复前预览：下载到临时文件 + 解析元信息，不落库。
     * 返回预览与临时文件（确认后 [restore] 复用并负责删除，避免二次下载）。
     */
    suspend fun downloadForPreview(name: String): Pair<BackupManager.BackupPreview, File> =
        withContext(Dispatchers.IO) {
            val client = requireClient()
            val tmp = File(tempDir(), "webdav-restore-${UUID.randomUUID()}.zip")
            try {
                client.downloadTo(name, tmp)
                gate.record(FEATURE, "下载 $name（${formatSize(tmp.length())}）", 0)
                previewFile(tmp) to tmp
            } catch (t: Throwable) {
                // 下载或预览解析（坏 zip）任一失败都要清掉临时文件；
                // 成功则交 restore 复用并负责删除
                tmp.delete()
                throw t
            }
        }

    /** 确认恢复：走与本地导入完全相同的链路（旧 JSON / 新 zip 都可），用完删除临时文件。 */
    suspend fun restore(backupFile: File): BackupManager.ImportResult = try {
        importFromFile(backupFile)
    } finally {
        backupFile.delete()
    }

    private suspend fun requireClient(): WebDavClient =
        clientFor() ?: throw IllegalStateException("请先完成 WebDAV 配置")

    private companion object {
        const val FEATURE = "WebDAV 备份"

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
