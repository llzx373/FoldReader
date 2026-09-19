package com.llzx373.foldreader.core.format

import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导入后的后台预热队列。
 *
 * 导入本身保持「写完库即返回」——用户此刻不预期等待；压平留给这里串行做掉，
 * 等用户真去点开时通常已经命中缓存。这把「首次打开要等整本压平」压缩成
 * 「导入后马上点开」这一种情况。
 *
 * 串行 + 每本之间让出：批量导入几百本时不能同时开几百个解析。
 * 失败静默：预热只是加速，失败等同于没预热，首次打开会照常重来。
 */
class BookPrewarmQueue(
    scope: CoroutineScope,
    private val parserFor: (BookFormat) -> BookParser?,
    /** 某本预热成功后的回调（例如回写"内容已就绪"标记）。 */
    private val onPrepared: suspend (bookId: Long) -> Unit = {},
    private val betweenJobsDelayMs: Long = BETWEEN_JOBS_DELAY_MS,
    /**
     * 漫画预热：解压需要顺序读的容器（rar/tar/7z）并回填页数与封面。
     * 单独立一个口子而不是塞进 [parserFor]：漫画走的是自己的容器层，不是 `BookParser` 那条管线。
     */
    private val comicPrepare: suspend (bookId: Long) -> Unit = {},
    /**
     * PDF 预热：打开文档拿页数并回填（P4 起还会用 PdfBox 补元数据/目录/封面）。
     * 加密 PDF 在这里会失败——那是正确的，角标继续留着等用户输密码。
     */
    private val pdfPrepare: suspend (bookId: Long) -> Unit = {},
) {

    private class Job(val bookId: Long, val uri: Uri, val format: BookFormat)

    private val pending = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in pending) {
                val prepared = when (job.format) {
                    BookFormat.COMIC -> runCatching { comicPrepare(job.bookId) }.isSuccess
                    BookFormat.PDF -> runCatching { pdfPrepare(job.bookId) }.isSuccess
                    else -> parserFor(job.format)
                        ?.let { runCatching { it.prewarm(job.uri, job.bookId) }.isSuccess }
                        ?: false
                }
                if (prepared) runCatching { onPrepared(job.bookId) }
                if (betweenJobsDelayMs > 0) delay(betweenJobsDelayMs)
            }
        }
    }

    /**
     * 入队。非阻塞，可在导入路径上直接调用。
     * TXT 没有压平步骤，不入队。
     */
    fun enqueue(bookId: Long, uriKey: String, format: BookFormat) {
        if (format == BookFormat.TXT) return
        pending.trySend(Job(bookId, Uri.parse(uriKey), format))
    }

    private companion object {
        /** 每本之间让出一点时间，避免批量导入时把 CPU 与 IO 全占满。 */
        const val BETWEEN_JOBS_DELAY_MS = 200L
    }
}
