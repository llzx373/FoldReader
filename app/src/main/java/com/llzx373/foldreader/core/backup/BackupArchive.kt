package com.llzx373.foldreader.core.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * zip 备份容器（v11 起）的纯流式读写：整包不读进内存，纯 JVM 零 android import。
 *
 * 布局：
 * - `backup.json`：书架数据与阅读偏好（BackupCodec 的输出，书记录附 archiveFile/archiveCover）；
 * - `files/<contentHash>.<ext>`：每本书的文件本体；
 * - `covers/<contentHash>.<ext>`：封面图片。
 */
object BackupArchive {

    const val MANIFEST = "backup.json"
    const val FILES_PREFIX = "files/"
    const val COVERS_PREFIX = "covers/"

    /** zip 魔数嗅探：本地文件头固定以 "PK" 开头。 */
    fun isZipHeader(first: Int, second: Int): Boolean = first == 0x50 && second == 0x4B

    /** 一个待写入条目；[open] 返回 null（源在写入时已不可读）则该条目跳过。 */
    data class PendingEntry(val name: String, val open: () -> InputStream?)

    /** 流式写出：先 manifest 再逐个文件条目。调用方负责关闭 [output]。 */
    fun writeZip(output: OutputStream, manifest: JSONObject, entries: List<PendingEntry>) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { entry ->
                val input = entry.open() ?: return@forEach
                zip.putNextEntry(ZipEntry(entry.name))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** 解压结果：[files]/[covers] 以 zip 内条目名为键，值为落盘后的本地文件。 */
    data class Extracted(
        val manifestText: String,
        val files: Map<String, File>,
        val covers: Map<String, File>,
    )

    /**
     * 单趟流式读包：抓出 manifest，同时把 files/ 解压到 [filesDir]、covers/ 解压到 [coversDir]。
     * 目标文件已存在则跳过（内容按哈希命名，同名即同内容——幂等，重复导入不重复写盘）。
     */
    fun readZip(input: InputStream, filesDir: File, coversDir: File): Extracted {
        var manifest: String? = null
        val files = mutableMapOf<String, File>()
        val covers = mutableMapOf<String, File>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == MANIFEST -> manifest = zip.readBytes().toString(Charsets.UTF_8)
                    !entry.isDirectory && name.startsWith(FILES_PREFIX) ->
                        files[name] = extractEntry(zip, name, filesDir)
                    !entry.isDirectory && name.startsWith(COVERS_PREFIX) ->
                        covers[name] = extractEntry(zip, name, coversDir)
                }
                zip.closeEntry()
            }
        }
        return Extracted(
            manifestText = manifest ?: throw IllegalArgumentException("备份文件缺少 $MANIFEST"),
            files = files,
            covers = covers,
        )
    }

    /** 只读 manifest（恢复前预览用），找到即停，不解压任何文件。 */
    fun readManifestText(input: InputStream): String {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        throw IllegalArgumentException("备份文件缺少 $MANIFEST")
    }

    /**
     * 条目名只取最后一段作为落盘文件名：备份包是我们自己产出的扁平结构，
     * 丢弃路径段同时挡掉 "../" 之类的目录逃逸。
     */
    private fun extractEntry(zip: ZipInputStream, entryName: String, targetDir: File): File {
        val fileName = entryName.substringAfterLast('/')
        require(fileName.isNotEmpty() && fileName != "." && fileName != "..") { "非法条目名: $entryName" }
        val target = File(targetDir, fileName)
        if (target.isFile) return target
        targetDir.mkdirs()
        target.outputStream().buffered().use { zip.copyTo(it) }
        return target
    }
}
