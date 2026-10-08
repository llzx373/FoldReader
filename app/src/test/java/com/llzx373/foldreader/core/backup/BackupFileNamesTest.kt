package com.llzx373.foldreader.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileNamesTest {

    @Test
    fun `时间戳命名符合 foldreader-backup 前缀约定`() {
        val name = BackupFileNames.timestamped(0L)
        assertTrue(BackupFileNames.isBackupFile(name))
        assertTrue(name, name.matches(Regex("foldreader-backup-\\d{8}-\\d{6}\\.json")))
    }

    @Test
    fun `isBackupFile 排除用户其他文件`() {
        assertFalse(BackupFileNames.isBackupFile("notes.json"))
        assertFalse(BackupFileNames.isBackupFile("foldreader-backup-20261008.bak"))
        assertTrue(BackupFileNames.isBackupFile("foldreader-backup-20261008-143000.json"))
    }

    @Test
    fun `轮转删除最旧的超额份`() {
        val names = listOf(
            "foldreader-backup-20261001-100000.json",
            "foldreader-backup-20261003-100000.json",
            "foldreader-backup-20261002-100000.json",
            "其他文件.json",
        )
        assertEquals(
            listOf(
                "foldreader-backup-20261001-100000.json",
                "foldreader-backup-20261002-100000.json",
            ),
            BackupFileNames.rotationDeletes(names, keep = 1),
        )
    }

    @Test
    fun `未超额不删除`() {
        val names = listOf(
            "foldreader-backup-20261001-100000.json",
            "foldreader-backup-20261002-100000.json",
        )
        assertEquals(emptyList<String>(), BackupFileNames.rotationDeletes(names, keep = 5))
    }

    @Test
    fun `keep 非正数时不删任何文件`() {
        val names = listOf("foldreader-backup-20261001-100000.json")
        assertEquals(emptyList<String>(), BackupFileNames.rotationDeletes(names, keep = 0))
    }
}
