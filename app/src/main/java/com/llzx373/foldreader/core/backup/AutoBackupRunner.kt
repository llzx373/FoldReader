package com.llzx373.foldreader.core.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.debug.DiagnosticLog
import com.llzx373.foldreader.core.format.saf.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本地自动备份（M25）：每日一次把备份 zip（v11 起含书籍文件本体）导出到用户在 SAF 选定的目录，
 * 按时间戳命名并轮转保留最近 N 份（[BackupFileNames.rotationDeletes]）。
 *
 * 「每日一次」的触发口径：进程启动后的维护协程里检查（AppContainer.init），
 * 距上次成功导出超过 [INTERVAL_MS] 才执行。「退出时导出」在 Android 上不可靠
 * （进程随时可能被回收，没有可靠的退出钩子），故采用启动检查制——
 * 对「每天至少开一次阅读器」的使用习惯效果等价。
 *
 * 失败（SAF 授权被收回、目录不可写等）只记诊断日志、不更新上次成功时间，
 * 下次启动自然重试；绝不打扰用户。
 */
class AutoBackupRunner(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val exportTo: suspend (Uri) -> BackupManager.ExportResult,
    private val safTree: SafTree,
) {

    /** 到期检查；到期则导出 + 轮转。返回是否执行了导出。 */
    suspend fun runIfDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = settingsRepository.preferences.first()
        if (!prefs.autoBackupEnabled || prefs.autoBackupDirUri.isBlank()) return false
        if (nowMs - prefs.autoBackupLastRunAt < INTERVAL_MS) return false
        return runCatching {
            runNow(prefs.autoBackupDirUri, prefs.autoBackupKeepCount, nowMs)
        }.onFailure {
            DiagnosticLog.line("自动备份失败: ${it.javaClass.simpleName} ${it.message}")
        }.isSuccess
    }

    /** 设置页「立即备份一次」：无条件导出 + 轮转，返回生成的文件名。 */
    suspend fun runNow(
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val prefs = settingsRepository.preferences.first()
        require(prefs.autoBackupDirUri.isNotBlank()) { "请先选择备份目录" }
        return runNow(prefs.autoBackupDirUri, prefs.autoBackupKeepCount, nowMs)
    }

    private suspend fun runNow(
        treeUriString: String,
        keep: Int,
        nowMs: Long,
    ): String = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(treeUriString)
        val name = BackupFileNames.timestamped(nowMs)

        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val docUri = DocumentsContract.createDocument(
            context.contentResolver,
            dirUri,
            "application/zip",
            name,
        ) ?: error("无法在备份目录创建文件（授权可能已失效，请重新选择目录）")
        val result = exportTo(docUri)
        if (result.skippedBookTitles.isNotEmpty()) {
            DiagnosticLog.line("自动备份：${result.skippedBookTitles.size} 本书的文件未打包（${result.skippedBookTitles.joinToString("、")}）")
        }

        // 轮转：删最旧的超额份（只动本应用命名的备份，同目录其他文件不碰）
        val children = runCatching { safTree.listChildren(treeUri, treeDocId) }.getOrDefault(emptyList())
        val byName = children.associateBy { it.name }
        BackupFileNames.rotationDeletes(children.map { it.name }, keep).forEach { stale ->
            byName[stale]?.let {
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, it.uri)
                }
            }
        }

        settingsRepository.setAutoBackupLastRunAt(nowMs)
        name
    }

    companion object {
        /** 每日一次。 */
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
