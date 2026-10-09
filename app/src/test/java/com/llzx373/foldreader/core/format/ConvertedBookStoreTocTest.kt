package com.llzx373.foldreader.core.format

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录 sidecar 的 writeToc/readToc 往返（FLATTEN_VERSION 4 起）：
 * depth 与页锚点 pageIndex 必须随缓存持久化——PDF 的「双锚点目录」与 EPUB 的层级
 * 在任何「从缓存回读」路径（书架重建目录、章节兜底）上都不得退化。
 */
class ConvertedBookStoreTocTest {

    private fun tempDir(): File = Files.createTempDirectory("converted-store-toc").toFile()

    @Test
    fun `目录往返保留 depth 与 pageIndex`() {
        val dir = tempDir()
        val chapters = listOf(
            Chapter(title = "第一部", charStart = 0, charEnd = 100, depth = 0, pageIndex = 0),
            Chapter(title = "第一章 雪夜", charStart = 0, charEnd = 40, depth = 1, pageIndex = 3),
            Chapter(title = "第二章\t带制表符", charStart = 40, charEnd = 100, depth = 1, pageIndex = 17),
        )

        ConvertedBookStore(dir).store("toc1") { out ->
            out.writeText("正文", Charsets.UTF_8)
            FlattenContent(chapters = chapters)
        }

        // 换新实例绕过备忘，强制走 readToc 磁盘回读路径。
        val read = ConvertedBookStore(dir).cached("toc1")
        assertEquals(chapters, read?.chapters)
    }

    @Test
    fun `目录往返 pageIndex 为空时保持空`() {
        val dir = tempDir()
        val chapters = listOf(
            Chapter(title = "第一章", charStart = 0, charEnd = 50),
            Chapter(title = "第二章", charStart = 50, charEnd = 100, depth = 2),
        )

        ConvertedBookStore(dir).store("toc2") { out ->
            out.writeText("正文", Charsets.UTF_8)
            FlattenContent(chapters = chapters)
        }

        val read = ConvertedBookStore(dir).cached("toc2")
        assertEquals(chapters, read?.chapters)
    }
}
