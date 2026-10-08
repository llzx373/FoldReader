package com.llzx373.foldreader.core.export

import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import kotlinx.coroutines.flow.first

/**
 * 批注导出的装配层（M27）：从仓储取书、章节与批注，交给 [AnnotationMarkdown] 渲染。
 * 无批注返回 null——调用方据此提示「没有可导出的批注」，不落一个空文件。
 */
object AnnotationExport {

    data class Rendered(
        val fileName: String,
        val markdown: String,
        val count: Int,
    )

    suspend fun render(
        repository: BookshelfRepository,
        bookId: Long,
        options: AnnotationExportOptions,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): Rendered? {
        val book = repository.getBook(bookId) ?: return null
        val annotations = repository.observeAnnotations(bookId).first()
        if (annotations.isEmpty()) return null
        val chapters = repository.getChapters(bookId)
        return Rendered(
            fileName = AnnotationMarkdown.fileName(book.title, exportedAtMs),
            markdown = AnnotationMarkdown.render(
                bookTitle = book.title,
                author = book.author,
                annotations = annotations,
                chapters = chapters,
                options = options,
                exportedAtMs = exportedAtMs,
                totalChars = book.totalChars,
            ),
            count = annotations.size,
        )
    }
}
