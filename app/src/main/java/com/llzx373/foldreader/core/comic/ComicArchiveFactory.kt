package com.llzx373.foldreader.core.comic

import android.net.Uri
import com.llzx373.foldreader.core.comic.archive.ComicArchiveExtractor
import com.llzx373.foldreader.core.comic.archive.ComicDirChild
import com.llzx373.foldreader.core.comic.archive.ComicFolderScan
import com.llzx373.foldreader.core.comic.archive.openDocumentComicArchive
import com.llzx373.foldreader.core.comic.archive.openLocalComicArchive
import com.llzx373.foldreader.core.comic.archive.openZipComicArchive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * 按容器类型打开漫画。
 *
 * 读取策略：
 * - ZIP：读中央目录按需解压单页，不复制不整体解压；
 * - TAR / SEVEN_ZIP / RAR：顺序容器，首开一次性解压到 `cache/<hash>/pages`（可回收）；
 * - FOLDER：SAF 列目录，逐页按需读，不落盘；
 * - 任何容器：若已存在「复制到本地」的持久副本（`local/<hash>/pages`），优先用它。
 */
class ComicArchiveFactory(
    private val extractionStore: ComicExtractionStore,
    private val openChannel: (Uri) -> SeekableByteChannel,
    private val openDocumentStream: (String) -> InputStream?,
    private val listDocumentChildren: (Uri) -> List<ComicDirChild>,
) {

    suspend fun open(
        uri: Uri,
        container: ComicContainer,
        contentHash: String,
    ): ComicArchive = withContext(Dispatchers.IO) {
        extractionStore.localPages(contentHash)?.let { files ->
            return@withContext openLocalComicArchive(container, files)
        }
        when (container) {
            ComicContainer.ZIP -> openZipComicArchive(openChannel(uri))
            ComicContainer.FOLDER -> openSafFolder(uri)
            ComicContainer.TAR, ComicContainer.SEVEN_ZIP, ComicContainer.RAR -> {
                val files = extractionStore.ensureExtracted(contentHash) { dir ->
                    extractInto(container, uri, dir)
                }
                openLocalComicArchive(container, files)
            }
        }
    }

    /** 只数页数（导入/预热用），不做封面、不保留句柄。 */
    suspend fun pageCount(uri: Uri, container: ComicContainer, contentHash: String): Int =
        open(uri, container, contentHash).use { it.pages.size }

    /**
     * 读容器里的 ComicInfo.xml 原始字节（M31）；没有或读不出来返回 null。
     *
     * - ZIP：中央目录里按条目名找（任意深度，大小写不敏感），单条解压；
     * - FOLDER：只看根目录一级（ComicInfo.xml 事实标准位置）；
     * - TAR/7z/RAR：没有随机访问，只能顺序扫一遍源（预热时顺带做，别为首屏跑这个）。
     */
    suspend fun readComicInfo(
        uri: Uri,
        container: ComicContainer,
        contentHash: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            when (container) {
                ComicContainer.ZIP -> readComicInfoZip(uri)
                ComicContainer.FOLDER -> readComicInfoFolder(uri)
                ComicContainer.TAR -> openChannel(uri).use { channel ->
                    ComicArchiveExtractor.readComicInfoTar(Channels.newInputStream(channel))
                }
                ComicContainer.SEVEN_ZIP -> openChannel(uri).use { channel ->
                    ComicArchiveExtractor.readComicInfoSevenZip(channel)
                }
                ComicContainer.RAR -> openChannel(uri).use { channel ->
                    ComicArchiveExtractor.readComicInfoRar(Channels.newInputStream(channel))
                }
            }
        }.getOrNull()
    }

    private fun readComicInfoZip(uri: Uri): ByteArray? =
        openChannel(uri).use { channel ->
            ZipFile.Builder().setSeekableByteChannel(channel).get().use { zip ->
                val entry = zip.entries.asSequence().firstOrNull { entry ->
                    !entry.isDirectory &&
                        com.llzx373.foldreader.core.comic.ComicInfoParser.isComicInfoPath(entry.name)
                } ?: return@use null
                if (entry.size > com.llzx373.foldreader.core.comic.ComicInfoParser.MAX_BYTES) {
                    return@use null
                }
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }

    private fun readComicInfoFolder(uri: Uri): ByteArray? {
        val child = listDocumentChildren(uri).firstOrNull {
            !it.isDirectory &&
                com.llzx373.foldreader.core.comic.ComicInfoParser.isComicInfoPath(it.name)
        } ?: return null
        return openDocumentStream(child.key)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (total < com.llzx373.foldreader.core.comic.ComicInfoParser.MAX_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                val take = minOf(
                    n,
                    com.llzx373.foldreader.core.comic.ComicInfoParser.MAX_BYTES - total,
                )
                out.write(buffer, 0, take)
                total += take
            }
            out.toByteArray()
        }
    }

    /**
     * 生成「复制到本地」的持久副本；已存在则直接复用。
     * ZIP 也走复制：用户显式选择脱离 SAF，就必须把数据搬进来。
     */
    suspend fun copyLocal(uri: Uri, container: ComicContainer, contentHash: String) =
        withContext(Dispatchers.IO) {
            extractionStore.ensureLocalCopy(contentHash) { dir ->
                when (container) {
                    ComicContainer.ZIP -> extractZipInto(uri, dir)
                    ComicContainer.FOLDER -> extractFolderInto(uri, dir)
                    else -> extractInto(container, uri, dir)
                }
            }
        }

    private fun openSafFolder(uri: Uri): ComicArchive {
        val selected = scanFolderChildren(uri)
        val pages = selected.mapIndexed { index, child ->
            ComicPage(index, child.name, ComicPageSource.Document(child.key))
        }
        return openDocumentComicArchive(ComicContainer.FOLDER, pages, openDocumentStream)
    }

    /**
     * 目录漫画的有序页相对路径。
     * 导入时的内容哈希与页数都用它——目录没有单一文件可采样，
     * 用「有序页路径表」做哈希既能去重（同内容不同路径的副本）又不受目录改名影响。
     */
    fun scanFolderPagePaths(uri: Uri): List<String> =
        scanFolderChildren(uri).map { it.relativePath }

    private fun scanFolderChildren(uri: Uri): List<ComicDirChild> =
        ComicFolderScan.selectPages(ComicDirChild(name = "", isDirectory = true, key = uri.toString())) { child ->
            listDocumentChildren(Uri.parse(child.key))
        }

    private fun extractInto(container: ComicContainer, uri: Uri, dir: File) =
        when (container) {
            ComicContainer.TAR -> openChannel(uri).use { channel ->
                ComicArchiveExtractor.extractTar(Channels.newInputStream(channel), dir)
            }
            ComicContainer.SEVEN_ZIP -> openChannel(uri).use { channel ->
                ComicArchiveExtractor.extractSevenZip(channel, dir)
            }
            ComicContainer.RAR -> openChannel(uri).use { channel ->
                ComicArchiveExtractor.extractRar(Channels.newInputStream(channel), dir)
            }
            else -> throw IOException("容器 $container 不需要解压")
        }

    /** zip 复制到本地：解压中央目录指到的每个页条目。 */
    private fun extractZipInto(uri: Uri, dir: File) =
        openChannel(uri).use { channel ->
            val archive = openZipComicArchive(channel)
            try {
                archive.pages.map { page ->
                    val target = File(dir, ComicArchiveExtractor.pageTempName(page.index))
                    target.writeBytes(archive.readPage(page.index))
                    page.name to target
                }
            } finally {
                archive.close()
            }
        }

    /** 目录漫画复制到本地：逐页读文档流写文件。 */
    private fun extractFolderInto(uri: Uri, dir: File) =
        openSafFolder(uri).use { archive ->
            archive.pages.map { page ->
                val target = File(dir, ComicArchiveExtractor.pageTempName(page.index))
                target.writeBytes(archive.readPage(page.index))
                page.name to target
            }
        }
}
