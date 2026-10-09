package com.llzx373.foldreader.core.backup.webdav

import java.io.IOException
import java.io.StringReader
import java.net.SocketTimeoutException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

/**
 * WebDAV 操作的失败。kind 给界面分流文案用（401 提示核对凭据，404 提示核对目录等）。
 *
 * 红线与 CredentialStore 相同：message 只含 HTTP 状态与网络错误类型，
 * 永不携带用户名/密码/请求体内容。
 */
class WebDavException(
    val kind: Kind,
    val httpCode: Int? = null,
    message: String,
) : IOException(message) {

    enum class Kind { INVALID_URL, AUTH, NOT_FOUND, HTTP, NETWORK, TIMEOUT }
}

/** PROPFIND 列表里的一个条目；[lastModified] 保留服务器原文（RFC1123，各服务端格式不一）。 */
data class WebDavEntry(
    val name: String,
    val sizeBytes: Long,
    val isCollection: Boolean,
    val lastModified: String?,
)

/**
 * WebDAV 协议层（M25）：PROPFIND / MKCOL / PUT / GET / DELETE，纯 JVM 零 android import，
 * MockWebServer 可端到端锁定。备份编解码不在这一层——它只搬运字节。
 *
 * - [baseUrl] 是用户配置的**备份目录**完整地址（如 `https://dav.jianguoyun.com/dav/FoldReader/`），
 *   备份文件直接放该目录下；目录不存在时 [ensureCollection] 创建。
 * - 鉴权走每次请求预置 Basic 头（UTF-8 编码，坚果云/Nextcloud 均接受）。
 * - 重定向一律不自动跟随：OkHttp 对 301/302 的跟随不限方法，PROPFIND/PUT 会被带往新主机
 *   （凭据头随请求发出）；统一改为抛出含 Location 的可行动提示，由用户改配置。
 * - XML 解析器由调用方注入：生产给 `android.util.Xml.newPullParser()`，JVM 单测给 kxml2——
 *   本类只依赖 org.xmlpull.v1 接口。
 * - 所有方法是阻塞式的，调用方（WebDavBackupManager）负责切到 IO 调度器。
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
    client: OkHttpClient,
    private val xmlParserFactory: () -> XmlPullParser,
) {

    /** 派生一个关闭重定向跟随的客户端（共享连接池/调度器，新建成本低）。 */
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val root: HttpUrl = run {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        normalized.toHttpUrlOrNull() ?: throw WebDavException(
            WebDavException.Kind.INVALID_URL,
            message = "服务器地址不是合法的 http(s) URL",
        )
    }

    private val authHeader: String = Credentials.basic(username, password, Charsets.UTF_8)

    /** 连接测试：PROPFIND 目标目录；目录不存在（404）时尝试创建后视为成功。 */
    fun testConnection(): TestResult = try {
        propfind(depth = 0)
        TestResult(createdDirectory = false)
    } catch (e: WebDavException) {
        if (e.kind != WebDavException.Kind.NOT_FOUND) throw e
        ensureCollection()
        TestResult(createdDirectory = true)
    }

    data class TestResult(val createdDirectory: Boolean)

    /** 建目录；405（已存在）视为成功，409（父目录缺失）如实抛 HTTP。 */
    fun ensureCollection() {
        executeForBody(Request.Builder().url(root).method("MKCOL", null).build()).use { response ->
            when (response.code) {
                201, 405 -> Unit
                else -> throw httpFailure(response.code, response.header("Location"))
            }
        }
    }

    /** 列出目录直接子项（depth:1），过滤掉目录本身；只保留普通文件。 */
    fun list(): List<WebDavEntry> {
        val response = propfind(depth = 1)
        return response
            .filter { !it.isCollection }
            .filter { it.name.isNotEmpty() }
    }

    /** 上传（覆盖式，流式）：先确保目录在，再 PUT。远端同名冲突由调用方按时间戳命名规避。 */
    fun upload(name: String, file: java.io.File) {
        ensureCollection()
        val body = file.asRequestBody("application/zip".toMediaType())
        executeForBody(Request.Builder().url(childUrl(name)).put(body).build()).use { response ->
            if (response.code !in 200..299) {
                throw httpFailure(response.code, response.header("Location"))
            }
        }
    }

    /** 下载到本地文件（流式，不整读进内存）；404 映射为「备份不存在」。 */
    fun downloadTo(name: String, target: java.io.File) {
        val response = executeForBody(Request.Builder().url(childUrl(name)).get().build())
        if (response.code == 404) {
            response.close()
            throw WebDavException(WebDavException.Kind.NOT_FOUND, 404, "备份不存在（HTTP 404）")
        }
        if (response.code !in 200..299) {
            val code = response.code
            val location = response.header("Location")
            response.close()
            throw httpFailure(code, location)
        }
        response.body!!.use { body ->
            target.outputStream().buffered().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** 删除远端备份（轮转清理用）；404 视为已不存在，不算失败。 */
    fun delete(name: String) {
        executeForBody(Request.Builder().url(childUrl(name)).delete().build()).use { response ->
            if (response.code !in 200..299 && response.code != 404) {
                throw httpFailure(response.code, response.header("Location"))
            }
        }
    }

    private fun childUrl(name: String): HttpUrl =
        root.newBuilder().addPathSegment(name).build()

    private fun propfind(depth: Int): List<WebDavEntry> {
        val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(root)
            .method("PROPFIND", body)
            .header("Depth", depth.toString())
            .build()
        val response = executeForBody(request)
        if (response.code == 207) {
            return response.body!!.use { parseMultistatus(it.string()) }
        }
        val code = response.code
        val location = response.header("Location")
        response.close()
        throw httpFailure(code, location)
    }

    private fun executeForBody(request: Request): okhttp3.Response = try {
        client.newCall(request.newBuilder().header("Authorization", authHeader).build()).execute()
    } catch (e: SocketTimeoutException) {
        throw WebDavException(WebDavException.Kind.TIMEOUT, message = "连接超时")
    } catch (e: IOException) {
        // 连接被拒/DNS 失败/中断等；e.message 可能含主机名但绝不含凭据
        throw WebDavException(WebDavException.Kind.NETWORK, message = "网络不可达或连接中断")
    }

    private fun httpFailure(code: Int, location: String? = null): WebDavException = when {
        code == 401 || code == 403 ->
            WebDavException(WebDavException.Kind.AUTH, code, "认证失败（HTTP $code），请核对账号与密码")
        code == 404 -> WebDavException(WebDavException.Kind.NOT_FOUND, code, "目录或文件不存在（HTTP 404）")
        // 重定向不自动跟随（PROPFIND/PUT 跟随会带着凭据去新主机）：给出可行动提示
        code in REDIRECT_CODES ->
            WebDavException(WebDavException.Kind.HTTP, code, redirectMessage(code, location))
        else -> WebDavException(WebDavException.Kind.HTTP, code, "服务器返回 HTTP $code")
    }

    private fun redirectMessage(code: Int, location: String?): String {
        val target = location?.trim()?.takeIf { it.isNotEmpty() }
        val httpsHint = if (target != null && target.startsWith("https://") && root.isHttps.not()) {
            "服务已迁移到 https，请把服务器地址改为 $target"
        } else if (target != null) {
            "请把服务器地址改为 $target"
        } else {
            "请联系服务商确认新地址（常见原因：http 已停用，改用 https）"
        }
        return "服务器要求重定向（HTTP $code），$httpsHint"
    }

    /**
     * 解析 multistatus。解析器不开命名空间（各服务端前缀不一：D: / d: / 无前缀），
     * 一律按 `:` 之后的本地名比对。href 末段即文件名（OkHttp 已做百分号解码）。
     */
    private fun parseMultistatus(xml: String): List<WebDavEntry> {
        val parser = xmlParserFactory()
        parser.setInput(StringReader(xml))
        val entries = mutableListOf<WebDavEntry>()
        var href: String? = null
        var displayName: String? = null
        var contentLength = 0L
        var lastModified: String? = null
        var isCollection = false
        var inResponse = false

        fun flush() {
            val name = href?.let { rawHref ->
                root.resolve(rawHref)?.pathSegments?.lastOrNull()
            }?.takeIf { it.isNotEmpty() }
                ?: displayName?.takeIf { it.isNotEmpty() }
            if (name != null) {
                entries += WebDavEntry(
                    name = name,
                    sizeBytes = contentLength,
                    isCollection = isCollection,
                    lastModified = lastModified,
                )
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "response" -> {
                        inResponse = true
                        href = null
                        displayName = null
                        contentLength = 0L
                        lastModified = null
                        isCollection = false
                    }
                    "href" -> if (inResponse && href == null) href = parser.nextText()
                    "displayname" -> if (inResponse) displayName = parser.nextText()
                    "getcontentlength" -> if (inResponse) {
                        contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                    }
                    "getlastmodified" -> if (inResponse) lastModified = parser.nextText()
                    "collection" -> if (inResponse) isCollection = true
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "response") {
                    flush()
                    inResponse = false
                }
            }
            event = parser.next()
        }
        return entries
    }

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:">
  <prop><displayname/><getcontentlength/><getlastmodified/><resourcetype/></prop>
</propfind>"""

        val REDIRECT_CODES = setOf(301, 302, 307, 308)
    }
}
