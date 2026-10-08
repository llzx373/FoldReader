package com.llzx373.foldreader.core.backup.webdav

import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.backup.BackupManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser

class WebDavBackupManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var gate: AiContentGate
    private lateinit var gateFile: java.io.File

    /** 收到的导入文本（验证恢复链路把下载原文原样交给编解码）。 */
    private var importedText: String? = null

    private val backupJson = """{"app":"FoldReader","version":7,"exportedAt":1759900000000,"books":[{},{}]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateFile = java.io.File.createTempFile("gate", ".json")
        gate = AiContentGate(gateFile)
        importedText = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        gateFile.delete()
    }

    private fun newManager(configured: Boolean = true): WebDavBackupManager = WebDavBackupManager(
        exportJsonText = { backupJson },
        importJsonText = { text ->
            importedText = text
            BackupManager.ImportResult(restoredBooks = 1, missingBookTitles = emptyList(), 0, 0)
        },
        preview = { text -> BackupManager.preview(text) },
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

        assertTrue(name, name.matches(Regex("foldreader-backup-\\d{8}-\\d{6}\\.json")))
        val records = gate.history()
        assertEquals(1, records.size)
        assertEquals("WebDAV 备份", records[0].feature)
        assertTrue(records[0].scope.startsWith("上传 $name"))
        assertTrue(records[0].scope.contains("KB").or(records[0].scope.contains("B）")))
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
  <D:response><D:href>/dav/FoldReader/foldreader-backup-20261001-100000.json</D:href>
    <D:propstat><D:prop><D:getcontentlength>10</D:getcontentlength><D:resourcetype/></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/FoldReader/readme.txt</D:href>
    <D:propstat><D:prop><D:getcontentlength>5</D:getcontentlength><D:resourcetype/></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/FoldReader/foldreader-backup-20261008-143000.json</D:href>
    <D:propstat><D:prop><D:getcontentlength>20</D:getcontentlength><D:resourcetype/></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>""",
            ),
        )

        val entries = newManager().listBackups()

        assertEquals(
            listOf(
                "foldreader-backup-20261008-143000.json",
                "foldreader-backup-20261001-100000.json",
            ),
            entries.map { it.name },
        )
    }

    @Test
    fun `预览解析版本与条目数且台账记下载`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(backupJson))

        val (preview, text) = newManager().downloadForPreview("foldreader-backup-20261008-143000.json")

        assertEquals(7, preview.version)
        assertEquals(2, preview.bookCount)
        assertEquals(1759900000000L, preview.exportedAt)
        assertEquals(backupJson, text)
        val records = gate.history()
        assertEquals(1, records.size)
        assertTrue(records[0].scope.startsWith("下载 foldreader-backup-20261008-143000.json"))
    }

    @Test
    fun `恢复把原文交给既有导入链路`() = runTest {
        newManager().restore(backupJson)

        assertEquals(backupJson, importedText)
    }
}
