package com.llzx373.foldreader.feature.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.llzx373.foldreader.core.comic.ComicPageOrdering
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.isSupportedBookName
import com.llzx373.foldreader.core.format.saf.SafTree
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
    private val safTree: SafTree?,
    private val importOne: suspend (DocEntry) -> ImportBookUseCase.Result,
    private val importComicDirectory: suspend (DocEntry) -> ImportBookUseCase.Result,
    private val assignGroup: suspend (bookIds: List<Long>, groupName: String?) -> Unit,
) {

    constructor(
        context: Context,
        importBook: ImportBookUseCase,
        bookshelfRepository: BookshelfRepository,
        importComicDirectory: suspend (DocEntry) -> ImportBookUseCase.Result,
        /**
         * 批量导入没有导入对话框，清洗档位跟随设置页——与「浏览」打开同一套规则。
         * 传 [CleanProfile.NONE]（默认）即整批不复制、不清洗。
         */
        profileProvider: suspend () -> CleanProfile = { CleanProfile.NONE },
    ) : this(
        safTree = SafTree(context.applicationContext),
        importOne = { entry ->
            importBook.import(
                uri = Uri.parse(entry.uri),
                profile = profileProvider(),
                source = BookSource.EXTERNAL,
            )
        },
        importComicDirectory = importComicDirectory,
        assignGroup = { ids, name -> bookshelfRepository.updateGroup(ids, name) },
    )

    /** 测试用：纯注入，无 Android 依赖（enumerate 不可用）。 */
    internal constructor(
        importOne: suspend (DocEntry) -> ImportBookUseCase.Result,
        assignGroup: suspend (bookIds: List<Long>, groupName: String?) -> Unit,
        importComicDirectory: suspend (DocEntry) -> ImportBookUseCase.Result = {
            ImportBookUseCase.Result.Failure("目录漫画未接线")
        },
    ) : this(
        safTree = null,
        importOne = importOne,
        importComicDirectory = importComicDirectory,
        assignGroup = assignGroup,
    )

    /**
     * 目录中的一个待导入项：一本电子书文件，或**一个图片目录**（目录漫画 = 一本）。
     * uri 存 String，便于 JVM 测试构造。
     */
    data class DocEntry(
        val name: String,
        val uri: String,
        val documentId: String,
        val isDirectory: Boolean = false,
    )

    /** 遍历产出的候选（纯逻辑段用，不含 uri）。 */
    internal data class Candidate(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
    )

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
     * 纯遍历决策：BFS 展开目录树，收集支持格式的文件与「图片目录」。
     *
     * 目录漫画的判定：目录**直接**含的图片数 ≥ [MIN_COMIC_DIR_IMAGES]。命中即视为一本，
     * 不再往下展开——一卷一个目录正是最常见的漫画存放方式。门槛而不是「有图就算」，
     * 是为了不把文字书库里的零散插图目录当成一本书。
     *
     * [childrenOf] 返回某目录的直接子项（documentId, 名称, MIME；目录 MIME 为 [DIR_MIME]）。
     * 达到 [limit] 即截断并置 truncated。
     */
    internal fun enumerateTree(
        rootId: String,
        childrenOf: (documentId: String) -> List<Triple<String, String, String?>>,
        limit: Int = BATCH_IMPORT_LIMIT,
        rootName: String? = null,
    ): Pair<List<Candidate>, Boolean> {
        val found = ArrayList<Candidate>()
        val pending = ArrayDeque<String>()
        val visited = HashSet<String>()
        // 目录名只在"这个目录本身就是一本书"时才需要（取作书名），随手记下即可
        val names = HashMap<String, String>()
        rootName?.let { names[rootId] = it }
        pending.add(rootId)
        visited.add(rootId)
        var truncated = false
        while (pending.isNotEmpty() && !truncated) {
            val dirId = pending.removeFirst()
            val children = childrenOf(dirId)
            val images = children.count { it.third != DIR_MIME && ComicPageOrdering.isImageName(it.second) }
            if (images >= MIN_COMIC_DIR_IMAGES) {
                found += Candidate(dirId, names[dirId] ?: dirId, isDirectory = true)
                if (found.size >= limit) truncated = true
                continue
            }
            for ((childId, name, mime) in children) {
                if (mime == DIR_MIME) {
                    if (visited.add(childId)) {
                        names[childId] = name
                        pending.add(childId)
                    }
                } else if (isSupportedBookName(name, mime)) {
                    found += Candidate(childId, name, isDirectory = false)
                    if (found.size >= limit) {
                        truncated = true
                        break
                    }
                }
            }
        }
        found.sortWith(compareBy({ it.name.lowercase() }, { it.documentId }))
        return found to truncated
    }

    /** 枚举目录树中全部可导入书籍。[startDocumentId] 默认树根，可传子目录 id。 */
    suspend fun enumerate(
        treeUri: Uri,
        startDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri),
    ): EnumerateResult = withContext(Dispatchers.IO) {
        val tree = checkNotNull(safTree) { "enumerate 需要 Android Context" }
        val (found, truncated) = enumerateTree(
            rootId = startDocumentId,
            childrenOf = { dirId -> tree.childTriples(treeUri, dirId) },
            rootName = tree.displayName(tree.documentUri(treeUri, startDocumentId)),
        )
        EnumerateResult(
            entries = found.map { candidate ->
                DocEntry(
                    name = candidate.name,
                    uri = tree.documentUri(treeUri, candidate.documentId).toString(),
                    documentId = candidate.documentId,
                    isDirectory = candidate.isDirectory,
                )
            },
            truncated = truncated,
        )
    }

    /** 取授权树根（或任意树内文档）的显示名，用于默认分组名。 */
    fun treeDisplayName(treeUri: Uri): String? {
        val tree = safTree ?: return null
        return tree.displayName(tree.documentUri(treeUri, tree.treeDocumentId(treeUri)))
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
                // 目录漫画（一个图片目录 = 一本）不走文本导入那条路，交给漫画登记
                val result = if (entry.isDirectory) importComicDirectory(entry) else importOne(entry)
                when (result) {
                    is ImportBookUseCase.Result.Imported -> {
                        importedIds += result.bookId
                        // 选了清理的书会同时落一行**原版**，归组时不能把它漏在组外
                        result.originalBookId?.let { importedIds += it }
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

        /**
         * 目录直接被判定为一本漫画所需的图片数。
         * 门槛而不是「有图就算」：文字书库里零散的插图目录不该被当成一本书。
         */
        const val MIN_COMIC_DIR_IMAGES = 3

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
