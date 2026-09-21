package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.db.BookFormat
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.util.UUID

/**
 * 把源文件原样复制进私有目录，返回它的 URI；已经有同一份就直接复用。
 *
 * 外部「打开方式」给的 `content://` 是**临时**授权，任务一结束（或在最近任务里被划掉、
 * 重启手机）就失效——正文若还引用它，这本书之后就会打不开。复制一份进来之后，正文落在
 * 私有目录里，阅读、重洗、撤销清理、切编码都只碰本地文件。
 *
 * 命名用**原文内容的哈希**：同一个文件无论从哪个入口、用哪个档位导入，都复用同一份副本。
 * 名字里的扩展名只为了人看着方便——真实格式认的是库里的 `format` 与头部魔数。
 */
internal fun copySourceToPrivateDir(
    sourceDir: File,
    channel: SeekableByteChannel,
    sourceHash: String,
    format: BookFormat,
    /** 保留源文件的原始扩展名（如漫画的 cbz/cbr）；null 时按格式给默认扩展名。 */
    extension: String? = null,
): String {
    val ext = extension?.lowercase()?.takeIf { it.isNotBlank() } ?: sourceExtensionOf(format)
    val target = File(sourceDir, "$sourceHash.$ext")
    if (target.isFile) return fileUriOf(target)
    sourceDir.mkdirs()
    val tmp = File(sourceDir, ".tmp-${UUID.randomUUID()}.part")
    try {
        channel.position(0)
        // 刻意不关闭这个输入流：它包着调用方的 channel，关了后面就没得读了
        val input = Channels.newInputStream(channel)
        tmp.outputStream().buffered().use { output -> input.copyTo(output) }
        if (!tmp.renameTo(target) && !target.isFile) {
            throw java.io.IOException("源文件副本写入失败: ${target.absolutePath}")
        }
    } finally {
        tmp.delete()
    }
    return fileUriOf(target)
}

/** 私有目录里这一份的 URI 串。不用 `Uri.fromFile`：这条路径要在纯 JVM 单测里跑通。 */
private fun fileUriOf(file: File): String = "file://${file.absolutePath}"

private fun sourceExtensionOf(format: BookFormat): String = when (format) {
    BookFormat.TXT -> "txt"
    BookFormat.EPUB -> "epub"
    BookFormat.FB2 -> "fb2"
    else -> format.name.lowercase()
}
