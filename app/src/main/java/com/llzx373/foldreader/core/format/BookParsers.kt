package com.llzx373.foldreader.core.format

import com.llzx373.foldreader.core.data.db.BookFormat

/** 按书籍格式分发解析器；未注册的格式回退 TXT（保持既有行为）。 */
class BookParsers(parsers: Map<BookFormat, BookParser>) {
    private val parsers = parsers.toMap()

    fun parserFor(format: BookFormat): BookParser =
        parsers[format] ?: requireNotNull(parsers[BookFormat.TXT]) { "TXT parser 未注册" }
}
