package com.llzx373.foldreader.core.format.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** SAF 目录中的一个条目。 */
data class SafEntry(
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val isDirectory: Boolean,
    val uri: Uri,
)

/**
 * SAF 目录访问的统一入口：文件浏览器、目录批量导入、漫画目录容器三处共用，
 * 避免列目录规则与查询方式各写一份后漂移。
 *
 * 一律走 `buildChildDocumentsUriUsingTree` + 单次 query 拿全目录元数据：
 * `DocumentFile` 路线每个子项每个属性都是一次独立 IPC，大目录会卡数秒。
 */
class SafTree(private val context: Context) {

    fun treeDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /** 列出某目录的直接子项（不做过滤，调用方按需筛选）。 */
    fun listChildren(treeUri: Uri, documentId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val entries = ArrayList<SafEntry>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                entries += SafEntry(
                    documentId = childId,
                    name = name,
                    mimeType = mime,
                    isDirectory = mime == DIR_MIME,
                    uri = documentUri(treeUri, childId),
                )
            }
        }
        return entries
    }

    /** 纯遍历逻辑要的三元组视图（documentId, 名称, MIME），避免把 Android 类型带进纯函数。 */
    fun childTriples(treeUri: Uri, documentId: String): List<Triple<String, String, String?>> =
        listChildren(treeUri, documentId).map { Triple(it.documentId, it.name, it.mimeType) }

    /**
     * 从「树内文档 Uri」（`buildDocumentUriUsingTree` 的产物）列出其直接子项。
     * 拿到的是文档而不是树时，靠 Uri 里的 tree 段反推树根，再按文档 id 列子项。
     */
    fun listChildrenOfDocument(documentUri: Uri): List<SafEntry> {
        val authority = documentUri.authority ?: return emptyList()
        val treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(documentUri)
        }.getOrNull() ?: return emptyList()
        val documentId = runCatching {
            DocumentsContract.getDocumentId(documentUri)
        }.getOrNull() ?: return emptyList()
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)
        return listChildren(treeUri, documentId)
    }

    /**
     * 列出**与某文档同级**的条目（即它所在目录的全部子项，含它自己）。
     *
     * SAF 没有「取父目录」的 API，只能从 documentId 反推：外部存储类提供方的 documentId 形如
     * `primary:comics/Foo/01.cbz`，去掉最后一段就是父目录 id。形如 `1234` 这种不透明的 id
     * （Downloads 提供方）反推不出来，此时返回空列表——调用方据此关掉依赖同目录的功能，
     * 而不是去猜一个可能错误的目标。
     *
     * [documentUri] 既可以是文件也可以是目录：目录型漫画要的正是它所在父目录的同级项。
     */
    fun listSiblingsOfDocument(documentUri: Uri): List<SafEntry> {
        val authority = documentUri.authority ?: return emptyList()
        val treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(documentUri)
        }.getOrNull() ?: return emptyList()
        val documentId = runCatching {
            DocumentsContract.getDocumentId(documentUri)
        }.getOrNull() ?: return emptyList()

        val parentDocumentId = parentDocumentIdOf(documentId) ?: return emptyList()
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)
        return runCatching { listChildren(treeUri, parentDocumentId) }.getOrDefault(emptyList())
    }

    fun displayName(documentUri: Uri): String? =
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    companion object {
        const val DIR_MIME = "vnd.android.document/directory"

        /**
         * 从 documentId 反推父目录 id：`primary:comics/Foo/01.cbz` → `primary:comics/Foo`。
         *
         * 不透明 id（没有 `:` 或没有路径段）返回 null——宁可不提供同目录功能，也不猜。
         */
        internal fun parentDocumentIdOf(documentId: String): String? {
            val colon = documentId.indexOf(':')
            if (colon <= 0) return null
            val path = documentId.substring(colon + 1)
            val slash = path.lastIndexOf('/')
            if (slash <= 0) return null
            return documentId.substring(0, colon + 1) + path.substring(0, slash)
        }
    }
}
