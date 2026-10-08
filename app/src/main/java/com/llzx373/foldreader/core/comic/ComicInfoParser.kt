package com.llzx373.foldreader.core.comic

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * ComicInfo.xml（漫画容器事实标准元数据，ComicRack 起源）的读取（M31）。
 *
 * 只取回填用得上的字段：系列 / 卷号 / 作者 / 标题（另带出版社与简介备用）。
 * 解析是宽松的一次性扫描——认识的一级标签收文本，其余跳过；
 * 畸形 XML、非 ComicInfo 根、字段缺失都不算错误（返回 null 或部分字段）。
 *
 * 纯 JVM：XmlPullParser 工厂由调用方注入（生产 = `android.util.Xml`，单测 = kxml2），
 * 与 EPUB/FB2 解析同一套路。
 */
data class ComicInfo(
    val title: String? = null,
    val series: String? = null,
    val number: String? = null,
    val writer: String? = null,
    val publisher: String? = null,
    val summary: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && series == null && number == null &&
            writer == null && publisher == null && summary == null
}

object ComicInfoParser {

    /** 条目文件名（容器内任意深度都认——实际几乎都在根目录）。 */
    const val FILE_NAME = "ComicInfo.xml"

    /** 条目路径（含目录前缀）是不是 ComicInfo.xml（大小写不敏感）。 */
    fun isComicInfoPath(path: String): Boolean =
        path.substringAfterLast('/').equals(FILE_NAME, ignoreCase = true)

    /** 文件字节上限：元数据文件超过这个就不是正常 ComicInfo（防解压炸弹式条目）。 */
    const val MAX_BYTES = 1024 * 1024

    /** 解析 ComicInfo.xml 字节；完全解析不了或一个认识的字段都没有时返回 null。 */
    fun parse(bytes: ByteArray, newParser: () -> XmlPullParser): ComicInfo? =
        runCatching { parseUnsafe(bytes, newParser) }.getOrNull()

    private fun parseUnsafe(bytes: ByteArray, newParser: () -> XmlPullParser): ComicInfo? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        // ComicInfo.xml 一律 UTF-8（无 BOM 声明的按 UTF-8 是 XML 默认）
        val text = bytes.toString(Charsets.UTF_8)
        val parser = newParser()
        parser.setInput(StringReader(text))
        var title: String? = null
        var series: String? = null
        var number: String? = null
        var writer: String? = null
        var publisher: String? = null
        var summary: String? = null
        var sawRoot = false
        var depth = 0
        var current: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    depth++
                    when {
                        depth == 1 -> {
                            // 根必须是 ComicInfo：别的 XML（如目录索引）不当元数据读
                            if (!parser.name.equals("ComicInfo", ignoreCase = true)) return null
                            sawRoot = true
                        }
                        depth == 2 -> current = parser.name
                    }
                }
                XmlPullParser.TEXT -> {
                    // 只取一级字段的文本（depth==2），嵌套结构（Pages 等）跳过
                    if (depth != 2) continue
                    val value = parser.text?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    when (current?.lowercase()) {
                        "title" -> title = title ?: value
                        "series" -> series = series ?: value
                        "number" -> number = number ?: value
                        "writer" -> writer = writer ?: value
                        "publisher" -> publisher = publisher ?: value
                        "summary" -> summary = summary ?: value
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (depth == 2) current = null
                    depth--
                }
            }
        }
        if (!sawRoot) return null
        return ComicInfo(
            title = title,
            series = series,
            number = number,
            writer = writer,
            publisher = publisher,
            summary = summary,
        ).takeUnless { it.isEmpty }
    }
}
