package com.llzx373.foldreader.core.format

import java.io.Reader
import org.xmlpull.v1.XmlPullParser

/** 统一关闭命名空间处理：属性/标签名按字面量读取（`dc:title`、`epub:type`），生产与 JVM 测试行为一致。 */
internal fun newPullParser(factory: () -> XmlPullParser): XmlPullParser = factory().apply {
    runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
}

/**
 * 把常见命名 HTML 实体（`&nbsp;` 等）改写为对应 Unicode 字符的 Reader。
 * XHTML/FB2 只允许 5 个预定义 XML 实体，但真实电子书常直接使用命名实体，
 * XmlPullParser 遇到未声明实体会抛错；此处按字符流式改写，有界前瞻，未知实体原样保留。
 */
internal class EntityNormalizingReader(private val reader: Reader) : Reader() {

    private val out = ArrayDeque<Int>()
    private val pushback = ArrayDeque<Int>()

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val c = next()
            if (c < 0) return if (n == 0) -1 else n
            cbuf[off + n] = c.toChar()
            n++
        }
        return n
    }

    private fun next(): Int {
        while (out.isEmpty()) {
            if (!produce()) return -1
        }
        return out.removeFirst()
    }

    private fun readRaw(): Int =
        if (pushback.isNotEmpty()) pushback.removeFirst() else reader.read()

    /** 产生至少一个输出字符；返回 false 表示源已耗尽。 */
    private fun produce(): Boolean {
        val c = readRaw()
        if (c < 0) return false
        if (c != '&'.code) {
            out.addLast(c)
            return true
        }
        val name = StringBuilder()
        while (name.length <= MAX_ENTITY_NAME) {
            val d = readRaw()
            when {
                d == ';'.code -> {
                    val replacement = ENTITIES[name.toString()]
                    if (replacement != null) {
                        out.addLast(replacement.code)
                    } else {
                        out.addLast('&'.code)
                        name.forEach { out.addLast(it.code) }
                        out.addLast(';'.code)
                    }
                    return true
                }
                d < 0 || !d.toChar().isLetterOrDigit() -> {
                    out.addLast('&'.code)
                    name.forEach { out.addLast(it.code) }
                    if (d >= 0) pushback.addLast(d)
                    return true
                }
                else -> name.append(d.toChar())
            }
        }
        out.addLast('&'.code)
        name.forEach { out.addLast(it.code) }
        return true
    }

    override fun close() = reader.close()

    private companion object {
        const val MAX_ENTITY_NAME = 10
        val ENTITIES = mapOf(
            "nbsp" to ' ', "middot" to '·', "bull" to '•',
            "copy" to '©', "reg" to '®', "deg" to '°', "plusmn" to '±',
            "times" to '×', "divide" to '÷', "laquo" to '«', "raquo" to '»',
            "trade" to '™',
            "ndash" to '–', "mdash" to '—', "hellip" to '…',
            "lsquo" to '‘', "rsquo" to '’', "ldquo" to '“', "rdquo" to '”',
        )
    }
}
