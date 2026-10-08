package com.llzx373.foldreader.core.backup.webdav

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    private val password = "s3cret-口令"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(okClient: OkHttpClient = OkHttpClient()): WebDavClient = WebDavClient(
        baseUrl = server.url("/dav/FoldReader/").toString(),
        username = "tester",
        password = password,
        client = okClient,
        xmlParserFactory = { KXmlParser() },
    )

    private fun multistatus(vararg responses: String) = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(
            """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>""",
        )

    private fun collectionResponse(href: String) = """
  <D:response>
    <D:href>$href</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>"""

    private fun fileResponse(href: String, size: Long, lastModified: String? = null) = """
  <D:response>
    <D:href>$href</D:href>
    <D:propstat><D:prop>
      <D:displayname>${href.substringAfterLast('/')}</D:displayname>
      <D:getcontentlength>$size</D:getcontentlength>
      ${lastModified?.let { "<D:getlastmodified>$it</D:getlastmodified>" } ?: ""}
      <D:resourcetype/>
    </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>"""

    // ---------- testConnection ----------

    @Test
    fun `测试连接成功且携带 Basic 头与 Depth 0`() {
        server.enqueue(multistatus(collectionResponse("/dav/FoldReader/")))

        val result = client.testConnection()

        assertFalse(result.createdDirectory)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        val expected = okhttp3.Credentials.basic("tester", password, Charsets.UTF_8)
        assertEquals(expected, request.getHeader("Authorization"))
    }

    @Test
    fun `测试连接 404 时自动建目录`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))

        val result = client.testConnection()

        assertTrue(result.createdDirectory)
        server.takeRequest(2, TimeUnit.SECONDS) // PROPFIND
        assertEquals("MKCOL", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `测试连接 401 映射为认证失败`() {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            client.testConnection()
            fail("401 应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.AUTH, e.kind)
            assertFalse("异常消息不得携带密码", e.message!!.contains(password))
        }
    }

    @Test
    fun `非法地址映射为 INVALID_URL 且零网络请求`() {
        try {
            WebDavClient(
                baseUrl = "not a url",
                username = "t",
                password = password,
                client = OkHttpClient(),
                xmlParserFactory = { KXmlParser() },
            )
            fail("非法地址应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.INVALID_URL, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    // ---------- list ----------

    @Test
    fun `列表解析 multistatus 并过滤目录`() {
        server.enqueue(
            multistatus(
                collectionResponse("/dav/FoldReader/"),
                fileResponse("/dav/FoldReader/foldreader-backup-20261008-143000.json", 1234, "Thu, 08 Oct 2026 06:30:00 GMT"),
                fileResponse("/dav/FoldReader/foldreader-backup-20261007-090000.json", 987),
            ),
        )

        val entries = client.list()

        assertEquals(2, entries.size)
        assertEquals("foldreader-backup-20261008-143000.json", entries[0].name)
        assertEquals(1234L, entries[0].sizeBytes)
        assertFalse(entries[0].isCollection)
        assertEquals("Thu, 08 Oct 2026 06:30:00 GMT", entries[0].lastModified)
        assertEquals("foldreader-backup-20261007-090000.json", entries[1].name)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("1", request.getHeader("Depth"))
    }

    @Test
    fun `列表支持无命名空间前缀的 multistatus`() {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<multistatus xmlns="DAV:">
  <response>
    <href>/dav/FoldReader/a.json</href>
    <propstat><prop><getcontentlength>42</getcontentlength><resourcetype/></prop>
    <status>HTTP/1.1 200 OK</status></propstat>
  </response>
</multistatus>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(body))

        val entries = client.list()

        assertEquals(listOf("a.json"), entries.map { it.name })
        assertEquals(42L, entries[0].sizeBytes)
    }

    @Test
    fun `列表 404 映射为目录不存在`() {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            client.list()
            fail("404 应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.NOT_FOUND, e.kind)
        }
    }

    // ---------- upload ----------

    @Test
    fun `上传先确保目录再 PUT，已存在目录的 405 被容忍`() {
        server.enqueue(MockResponse().setResponseCode(405)) // MKCOL：目录已存在
        server.enqueue(MockResponse().setResponseCode(201)) // PUT

        client.upload("foldreader-backup-20261008-143000.json", "{}".toByteArray())

        val mkcol = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("MKCOL", mkcol.method)
        val put = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("/dav/FoldReader/foldreader-backup-20261008-143000.json", put.path)
        assertEquals("{}", put.body.readUtf8())
    }

    @Test
    fun `上传 401 映射为认证失败且消息不含密码`() {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            client.upload("a.json", "{}".toByteArray())
            fail("401 应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.AUTH, e.kind)
            assertFalse(e.message!!.contains(password))
        }
    }

    @Test
    fun `上传文件名含空格与中文时正确编码`() {
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        server.enqueue(MockResponse().setResponseCode(201)) // PUT

        client.upload("备份 2026.json", "x".toByteArray())

        server.takeRequest(2, TimeUnit.SECONDS) // MKCOL
        val put = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/dav/FoldReader/%E5%A4%87%E4%BB%BD%202026.json", put.path)
    }

    // ---------- download ----------

    @Test
    fun `下载成功返回字节`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"version\":7}"))

        val bytes = client.download("foldreader-backup-20261008-143000.json")

        assertEquals("{\"version\":7}", String(bytes, Charsets.UTF_8))
        assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `下载 404 映射为备份不存在`() {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            client.download("missing.json")
            fail("404 应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.NOT_FOUND, e.kind)
        }
    }

    // ---------- delete ----------

    @Test
    fun `删除成功与 404 均视为完成`() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.delete("a.json")
        assertEquals("DELETE", server.takeRequest(2, TimeUnit.SECONDS)!!.method)

        server.enqueue(MockResponse().setResponseCode(404))
        client.delete("b.json")
        assertEquals("DELETE", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    // ---------- 网络层异常 ----------

    @Test
    fun `连接被拒映射为网络错误`() {
        server.shutdown()
        val dead = newClient()

        try {
            dead.testConnection()
            fail("连接被拒应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.NETWORK, e.kind)
            assertFalse(e.message!!.contains(password))
        }
    }

    @Test
    fun `读超时映射为超时错误`() {
        val impatient = newClient(
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build(),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        try {
            impatient.testConnection()
            fail("超时应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.TIMEOUT, e.kind)
        }
    }

    @Test
    fun `服务器 500 映射为 HTTP 错误`() {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            client.list()
            fail("500 应抛出 WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavException.Kind.HTTP, e.kind)
            assertEquals(500, e.httpCode)
        }
    }
}
