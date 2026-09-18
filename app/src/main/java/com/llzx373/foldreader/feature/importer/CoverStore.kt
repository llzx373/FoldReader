package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.format.CoverImage
import java.io.File

/**
 * 封面落盘：文件名固定 `<contentHash>.<ext>`，同哈希的旧封面（扩展名可能变）先清掉再写。
 * 单独抽出来是因为漫画导入也要用它，而它不该依赖 [ImportBookUseCase]（那会绕成构造环）。
 */
internal fun writeCoverFile(dir: File, contentHash: String, cover: CoverImage): String {
    dir.mkdirs()
    dir.listFiles { f -> f.name.startsWith("$contentHash.") }?.forEach { it.delete() }
    val target = File(dir, "$contentHash.${cover.extension}")
    target.writeBytes(cover.bytes)
    return target.absolutePath
}
