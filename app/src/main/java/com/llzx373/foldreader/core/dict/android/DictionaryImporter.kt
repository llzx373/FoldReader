package com.llzx373.foldreader.core.dict.android

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.dict.DictInfo
import com.llzx373.foldreader.core.dict.DictionaryStore
import com.llzx373.foldreader.core.format.saf.SafTree

/**
 * SAF 目录导入词典（M28）：词典是三件套（同名 .ifo + .idx + .dict/.dict.dz），
 * 单文件选择器拿不齐，所以让用户选**词典文件所在目录**——列出目录直接子项，
 * 凑齐三件套的每个词干各导入一部。文件当场复制进 filesDir/dicts/，不需要持久授权。
 *
 * 薄胶层：只负责「列目录 + 开流」，格式与落盘规则全在 [DictionaryStore]（纯 JVM 可测）。
 */
class DictionaryImporter(
    private val context: Context,
    private val safTree: SafTree,
    private val store: DictionaryStore,
) {

    data class Summary(
        val imported: List<DictInfo>,
        /** 词干 → 失败原因；单部失败不中断其他。 */
        val failures: List<Pair<String, String>>,
    )

    fun importFromTree(treeUri: Uri): Summary {
        val children = safTree.listChildren(treeUri, safTree.treeDocumentId(treeUri))
        val files = children.filter { !it.isDirectory }
        val stems = DictionaryStore.findStems(files.map { it.name })
        if (stems.isEmpty()) {
            if (files.any { it.name.lowercase().endsWith(".mdx") }) {
                throw DictionaryStore.ImportException(
                    "MDict 格式暂不支持（格式多变体且可能加密），请改用 StarDict 格式（.ifo + .idx + .dict）",
                )
            }
            throw DictionaryStore.ImportException(
                "所选目录里没有完整的 StarDict 词典——需要同名的 .ifo + .idx + .dict（或 .dict.dz）三件套",
            )
        }
        val byLowerName = files.associateBy { it.name.lowercase() }
        val imported = ArrayList<DictInfo>()
        val failures = ArrayList<Pair<String, String>>()
        stems.forEach { stem ->
            try {
                imported += store.import(stem) { fileName ->
                    byLowerName[fileName.lowercase()]?.let { entry ->
                        runCatching { context.contentResolver.openInputStream(entry.uri) }.getOrNull()
                    }
                }
            } catch (e: DictionaryStore.ImportException) {
                failures += stem to (e.message ?: "导入失败")
            } catch (e: Exception) {
                failures += stem to "导入失败：${e.message ?: "文件读取异常"}"
            }
        }
        return Summary(imported = imported, failures = failures)
    }
}
