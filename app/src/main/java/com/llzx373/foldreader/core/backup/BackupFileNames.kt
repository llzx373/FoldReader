package com.llzx373.foldreader.core.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件命名（M25）：WebDAV 上传与本地自动备份共用同一时间戳命名，
 * 天然避免远端同名冲突（同名只在同一秒内发生，届时后者覆盖前者也无损失）。
 */
object BackupFileNames {

    const val PREFIX = "foldreader-backup-"
    const val SUFFIX = ".zip"

    fun timestamped(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "$PREFIX$stamp$SUFFIX"
    }

    fun isBackupFile(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /**
     * 轮转决策：时间戳命名保证字典序即时间序，返回超额的**最旧**若干份（应删除）。
     * 非本应用备份命名的条目不参与轮转（不动用户放在同目录的其他文件）。
     */
    fun rotationDeletes(names: List<String>, keep: Int): List<String> {
        if (keep <= 0) return emptyList()
        val ours = names.filter(::isBackupFile).sorted()
        val excess = ours.size - keep
        return if (excess > 0) ours.take(excess) else emptyList()
    }
}
