package com.llzx373.foldreader.core.backup.webdav

import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.backup.BackupManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser

class WebDavBackupManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var gate: AiContentGate
    private lateinit var gateFile: java.io.File
    private lateinit var tempDir: java.io.File

    /** 收到的导入文件内容（验证恢复链路把下载文件原样交给编解码）。 */
    private var importedContent: String? = null

    private val backupJson = """{"app":"FoldReader","version":7,"exportedAt":1759900000000,"books":[{},{}]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateFile = java.io.File.createTempFile("gate", ".json")
        gate = AiContentGate(gateFile)
        tempDir = java.nio.file.Files.createTempDirectory("webdav-backup-test").toFile()
        importedContent = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        gateFile.delete()
        tempDir.deleteRecursively()
    }

    private fun newManager(configured: Boolean = true): WebDavBackupManager = WebDavBackupManager(
        exportToFile = { file -> file.writeText(backupJson) },
        importFromFile = { file ->
            importedContent = file.readText()
            BackupManager.ImportResult(restoredBooks = 1, missingBookTitles = emptyList(), 0, 0)
        },
        previewFile = { file -> BackupManager.preview(file.readText()) },
        tempDir = { tempDir },
        clientFor = {
            if (!configured) {
                null
            } else {
                WebDavClient(
                    baseUrl = server.url("/dav/FoldReader/").toString(),
                    username = "u",
                    password = "p",
                    client = OkHttpClient(),
                    xmlParserFactory = { KXmlParser() },
                )
            }
        },
        gate = gate,
    )

    @Test
    fun `上传全链路——MKCOL+PUT 且台账记一条上传`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405)) // MKCOL：目录已存在
        server.enqueue(MockResponse().setResponseCode(201)) // PUT

        val name = newManager().upload()

        assertTrue(name, name.matches(Regex("foldreader-backup-\\d{8}-\\d{6}\\.zip")))
        val records = gate.history()
        assertEquals(1, records.size)
        assertEquals("WebDAV 备份", records[0].feature)
        assertTrue(records[0].scope.startsWith("上传 $name"))
        assertTrue(records[0].scope.contains("KB").or(records[0].scope.contains("B）")))
        // 上传用的临时文件已清理
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `未配置时上传直接失败且零网络请求`() = runTest {
        try {
            newManager(configured = false).upload()
            org.junit.Assert.fail("未配置应抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 预期
        }
        assertEquals(0, server.requestCount)
        assertTrue(gate.history().isEmpty())
    }

    @Test
    fun `列表只保留本应用备份且最新在前`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response><D:href>/dav/FoldReader/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/FoldReader/foldreader-backup-20261001-100000.zip</D:href>
    <D:propstat><D:prop><D:getcontentlength>10</D:getcontentlength><D:resourcetype/></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/FoldReader/readme.txt</D:href>
    <D:propstat><D:prop><D:getcontentlength>5</D:getcontentlength><D:resourcetype/></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/FoldReader/foldreader-backup-20261008-143000.zip</D:href>
    <D:propstat><D:prop><D:getcontentlength>20</D:getcontentlength><D:resourcetype/></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>""",
            ),
        )

        val entries = newManager().listBackups()

        assertEquals(
            listOf(
                "foldreader-backup-20261008-143000.zip",
                "foldreader-backup-20261001-100000.zip",
            ),
            entries.map { it.name },
        )
    }

    @Test
    fun `预览解析版本与条目数且台账记下载`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(backupJson))

        val (preview, file) = newManager().downloadForPreview("foldreader-backup-20261008-143000.zip")

        assertEquals(7, preview.version)
        assertEquals(2, preview.bookCount)
        assertEquals(1759900000000L, preview.exportedAt)
        assertEquals(backupJson, file.readText())
        val records = gate.history()
        assertEquals(1, records.size)
        assertTrue(records[0].scope.startsWith("下载 foldreader-backup-20261008-143000.zip"))
    }

    @Test
    fun `恢复把下载文件交给既有导入链路并清理临时文件`() = runTest {
        val file = java.io.File(tempDir, "webdav-restore-test.zip").apply { writeText(backupJson) }

        newManager().restore(file)

        assertEquals(backupJson, importedContent)
        assertFalse(file.exists())
    }
}
