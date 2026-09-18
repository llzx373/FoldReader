package com.llzx373.foldreader.core.comic.archive

import com.llzx373.foldreader.core.comic.ComicPageOrdering
import com.llzx373.foldreader.core.comic.ExtractedPage
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import com.github.junrar.Archive

/**
 * 顺序容器的解压器：tar / 7z / rar 都没有「按条目随机读」的便宜路径。
 *
 * - tar 没有中央目录，只能顺序扫；
 * - `SevenZFile.getEntries()` 返回的只是元数据副本，条目流只对 `getNextEntry()` 的当前条目有效；
 * - rar 同样只能顺序读（junrar 逐 header 推进）。
 *
 * 所以这三类首开一次性解压到本地缓存，之后按文件随机读。zip 不走这里，它读中央目录就够了。
 * 解压出的文件名由本文件决定（唯一即可），页序由 [ComicExtractionStore] 按原始条目名重排。
 */
internal object ComicArchiveExtractor {

    fun extractTar(input: InputStream, targetDir: File): List<ExtractedPage> {
        val extracted = ArrayList<ExtractedPage>()
        var counter = 0
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name ?: continue
                if (!isPageCandidate(name)) continue
                extracted += name to copyToPage(targetDir, counter++, tar)
            }
        }
        return extracted
    }

    fun extractSevenZip(channel: SeekableByteChannel, targetDir: File): List<ExtractedPage> {
        val extracted = ArrayList<ExtractedPage>()
        var counter = 0
        SevenZFile.builder().setSeekableByteChannel(channel).get().use { sevenZ ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name == null || !isPageCandidate(name)) {
                    // 不取的条目也要读干净，否则下一个 nextEntry 拿到的位置是错的
                    while (sevenZ.read(buffer) >= 0) Unit
                    continue
                }
                val target = File(targetDir, pageTempName(counter++))
                target.outputStream().use { out ->
                    while (true) {
                        val n = sevenZ.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                extracted += name to target
            }
        }
        return extracted
    }

    fun extractRar(input: InputStream, targetDir: File): List<ExtractedPage> {
        val extracted = ArrayList<ExtractedPage>()
        var counter = 0
        Archive(input).use { archive ->
            while (true) {
                val header = archive.nextFileHeader() ?: break
                val name = header.fileName
                if (header.isDirectory || name == null || !isPageCandidate(name)) continue
                archive.getInputStream(header).use { entryStream ->
                    extracted += name to copyToPage(targetDir, counter++, entryStream)
                }
            }
        }
        return extracted
    }

    internal fun isPageCandidate(name: String): Boolean =
        !ComicPageOrdering.isJunkPath(name) && ComicPageOrdering.isImageName(name)

    internal fun pageTempName(counter: Int): String = "p%06d".format(counter)

    private fun copyToPage(targetDir: File, counter: Int, input: InputStream): File {
        val target = File(targetDir, pageTempName(counter))
        target.outputStream().use { out -> input.copyTo(out) }
        return target
    }

    private const val COPY_BUFFER = 64 * 1024
}
