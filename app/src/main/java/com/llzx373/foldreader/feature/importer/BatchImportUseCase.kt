package com.llzx373.foldreader.feature.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.TextCleaner
import com.llzx373.foldreader.core.format.isSupportedBookName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 目录成组批量导入：枚举 SAF 目录树中全部支持格式的书籍，逐本导入后一次性赋组。
 * 枚举拆成「纯遍历决策 + DocumentsContract 薄壳」两段，前者不碰 Android 类，可在 JVM 单测。
 */
class BatchImportUseCase private constructor(
    private val context: Context?,
    private val importOne: suspend (DocEntry) -> ImportBookUseCase.Result,
    private val assignGroup: suspend (bookIds: List<Long>, groupName: String?) -> Unit,
) {

    constructor(
        context: Context,
        importBook: ImportBookUseCase,
        bookshelfRepository: BookshelfRepository,
    ) : this(
        context = context.applicationContext,
        importOne = { entry ->
            importBook.import(
                uri = Uri.parse(entry.uri),
                options = TextCleaner.CleanOptions(),
                source = BookSource.EXTERNAL,
            )
        },
        assignGroup = { ids, name -> bookshelfRepository.updateGroup(ids, name) },
    )

    /** 测试用：纯注入，无 Android 依赖（enumerate 不可用）。 */
    internal constructor(
        importOne: suspend (DocEntry) -> ImportBookUseCase.Result,
        assignGroup: suspend (bookIds: List<Long>, groupName: String?) -> Unit,
    ) : this(context = null, importOne = importOne, assignGroup = assignGroup)

    /** 目录中的一个待导入书籍文档。uri 存 String，便于 JVM 测试构造。 */
    data class DocEntry(val name: String, val uri: String, val documentId: String)

    data class EnumerateResult(val entries: List<DocEntry>, val truncated: Boolean)

    data class BatchItemResult(val name: String, val reason: String)

    data class BatchResult(
        val imported: List<Pair<Long, String>>,
        val duplicates: List<BatchItemResult>,
        val failures: List<BatchItemResult>,
        val cancelled: Boolean,
        /** 实际赋组名；未赋组（空组名/取消/无成功导入）时为 null。 */
        val groupName: String?,
    )

    /**
     * 纯遍历决策：BFS 展开目录树，收集支持格式的文件，按名称排序保证确定性。
     * [childrenOf] 返回某目录的直接子项（documentId, 名称, MIME；目录 MIME 为 [DIR_MIME]）。
     * 达到 [limit] 即截断并置 truncated。返回（documentId, 名称）列表。
     */
    internal fun enumerateTree(
        rootId: String,
        childrenOf: (documentId: String) -> List<Triple<String, String, String?>>,
        limit: Int = BATCH_IMPORT_LIMIT,
    ): Pair<List<Pair<String, String>>, Boolean> {
        val found = ArrayList<Pair<String, String>>()
        val pending = ArrayDeque<String>()
        val visited = HashSet<String>()
        pending.add(rootId)
        visited.add(rootId)
        var truncated = false
        while (pending.isNotEmpty() && !truncated) {
            val dirId = pending.removeFirst()
            for ((childId, name, mime) in childrenOf(dirId)) {
                if (mime == DIR_MIME) {
                    if (visited.add(childId)) pending.add(childId)
                } else if (isSupportedBookName(name, mime)) {
                    found += childId to name
                    if (found.size >= limit) {
                        truncated = true
                        break
                    }
                }
            }
        }
        found.sortWith(compareBy({ it.second.lowercase() }, { it.first }))
        return found to truncated
    }

    /** 枚举目录树中全部可导入书籍。[startDocumentId] 默认树根，可传子目录 id。 */
    suspend fun enumerate(
        treeUri: Uri,
        startDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri),
    ): EnumerateResult = withContext(Dispatchers.IO) {
        val ctx = checkNotNull(context) { "enumerate 需要 Android Context" }
        val (found, truncated) = enumerateTree(
            rootId = startDocumentId,
            childrenOf = { dirId -> queryChildren(ctx, treeUri, dirId) },
        )
        EnumerateResult(
            entries = found.map { (docId, name) ->
                DocEntry(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                    documentId = docId,
                )
            },
            truncated = truncated,
        )
    }

    /** 取授权树根（或任意树内文档）的显示名，用于默认分组名。 */
    fun treeDisplayName(treeUri: Uri): String? {
        val ctx = context ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return ctx.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun queryChildren(
        context: Context,
        treeUri: Uri,
        documentId: String,
    ): List<Triple<String, String, String?>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = ArrayList<Triple<String, String, String?>>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                children += Triple(childId, name, cursor.getString(2))
            }
        }
        return children
    }

    /**
     * 串行逐本导入：单本失败/重复不中断，重复书保持原分组不动。
     * 全部完成后对成功导入的书一次性赋组（[groupName] 为空/blank 或协程被取消时不赋组）。
     */
    suspend fun importDirectory(
        entries: List<DocEntry>,
        groupName: String?,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> },
    ): BatchResult = withContext(Dispatchers.IO) {
        val importedIds = ArrayList<Long>()
        val imported = ArrayList<Pair<Long, String>>()
        val duplicates = ArrayList<BatchItemResult>()
        val failures = ArrayList<BatchItemResult>()
        var cancelled = false

        for ((index, entry) in entries.withIndex()) {
            try {
                coroutineContext.ensureActive()
                onProgress(index, entries.size, entry.name)
                when (val result = importOne(entry)) {
                    is ImportBookUseCase.Result.Imported -> {
                        importedIds += result.bookId
                        imported += result.bookId to result.title
                    }
                    is ImportBookUseCase.Result.DuplicateSameUri ->
                        duplicates += BatchItemResult(entry.name, "同一路径已在书架")
                    is ImportBookUseCase.Result.DuplicateSameHash ->
                        duplicates += BatchItemResult(entry.name, "内容相同的书已在书架")
                    is ImportBookUseCase.Result.Failure ->
                        failures += BatchItemResult(entry.name, result.message ?: "未知错误")
                }
            } catch (e: CancellationException) {
                cancelled = true
                break
            } catch (t: Throwable) {
                failures += BatchItemResult(entry.name, t.message ?: "未知错误")
            }
        }
        onProgress(imported.size + duplicates.size + failures.size, entries.size, "")

        // 取消时不赋组：此刻协程上下文已取消，suspend 赋组调用会立刻抛异常
        val trimmedGroup = groupName?.trim()?.takeIf { it.isNotEmpty() }
        if (!cancelled && trimmedGroup != null && importedIds.isNotEmpty()) {
            assignGroup(importedIds, trimmedGroup)
        }
        BatchResult(
            imported = imported,
            duplicates = duplicates,
            failures = failures,
            cancelled = cancelled,
            groupName = if (!cancelled) trimmedGroup else null,
        )
    }

    companion object {
        const val BATCH_IMPORT_LIMIT = 500

        /** DocumentsContract.Document.MIME_TYPE_DIR 的字面值，纯逻辑段不引用 Android 类。 */
        private const val DIR_MIME = "vnd.android.document/directory"

        /** 默认分组名：目录名去扩展名，空则回退。 */
        fun defaultGroupName(dirName: String?): String =
            dirName?.trim()
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotEmpty() }
                ?: "目录导入"
    }
}
