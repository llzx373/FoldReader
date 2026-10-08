package com.llzx373.foldreader.core.backup.webdav

import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.backup.BackupFileNames
import com.llzx373.foldreader.core.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 备份编排（M25）：上传 / 列表 / 下载恢复 + 恢复前预览。
 * 编解码完全复用 [BackupManager]（格式零改动），这一层只做「字节搬运 + 台账记账」——
 * 依赖以函数注入，纯 JVM 可测（MockWebServer + 假编解码）。
 *
 * 合规：每次上传/下载记入外发历史台账（feature=WebDAV 备份，scope=文件名与大小）；
 * 未配置（[clientFor] 返回 null）时抛 [IllegalStateException]，调用方 UI 层保证入口只在
 * 已配置 + 已完成首次确认后出现。
 */
class WebDavBackupManager(
    private val exportJsonText: suspend () -> String,
    private val importJsonText: suspend (String) -> BackupManager.ImportResult,
    private val preview: (String) -> BackupManager.BackupPreview,
    private val clientFor: suspend () -> WebDavClient?,
    private val gate: AiContentGate,
) {

    /** 导出并上传，返回远端文件名。 */
    suspend fun upload(): String = withContext(Dispatchers.IO) {
        val client = requireClient()
        val bytes = exportJsonText().toByteArray(Charsets.UTF_8)
        val name = BackupFileNames.timestamped()
        client.upload(name, bytes)
        gate.record(FEATURE, "上传 $name（${formatSize(bytes.size.toLong())}）", 0)
        name
    }

    /** 远端备份列表（只保留本应用命名的 JSON，最新在前）。 */
    suspend fun listBackups(): List<WebDavEntry> = withContext(Dispatchers.IO) {
        requireClient().list()
            .filter { BackupFileNames.isBackupFile(it.name) }
            .sortedByDescending { it.name }
    }

    /** 恢复前预览：下载 + 解析元信息，不落库。返回预览与原文（确认后 [restore] 复用，避免二次下载）。 */
    suspend fun downloadForPreview(name: String): Pair<BackupManager.BackupPreview, String> =
        withContext(Dispatchers.IO) {
            val client = requireClient()
            val bytes = client.download(name)
            val text = String(bytes, Charsets.UTF_8)
            gate.record(FEATURE, "下载 $name（${formatSize(bytes.size.toLong())}）", 0)
            preview(text) to text
        }

    /** 确认恢复：走与本地导入完全相同的链路（contentHash 对齐既有书）。 */
    suspend fun restore(backupText: String): BackupManager.ImportResult =
        importJsonText(backupText)

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
