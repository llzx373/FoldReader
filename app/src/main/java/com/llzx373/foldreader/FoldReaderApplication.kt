package com.llzx373.foldreader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.db.RoomOffsetIndexStore
import com.llzx373.foldreader.core.data.repository.BookPrefsRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepositoryImpl
import com.llzx373.foldreader.core.data.settings.FileBrowserRootsStore
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.SettingsRepositoryImpl
import com.llzx373.foldreader.core.foldable.FoldableStateProvider
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.comic.ComicContainers
import com.llzx373.foldreader.core.format.BookParsers
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.epub.EpubBookParser
import com.llzx373.foldreader.core.format.fb2.Fb2BookParser
import com.llzx373.foldreader.core.format.txt.TxtBookParser
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import com.llzx373.foldreader.feature.importer.toImportResult
import com.llzx373.foldreader.feature.importer.writeCoverFile
import com.llzx373.foldreader.core.pdf.PdfBoxReader
import com.llzx373.foldreader.core.pdf.PdfDocumentInfo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    val pendingImportUri = MutableStateFlow<Uri?>(null)
    val activeReaderBookId = MutableStateFlow<Long?>(null)
    val appContext: Context = context.applicationContext
    val foldableStateProvider = FoldableStateProvider(
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val database: FoldReaderDatabase =
        Room.databaseBuilder(context, FoldReaderDatabase::class.java, "foldreader.db").build()
    /** 非 TXT 格式的压平缓存目录（<contentHash>.txt + .toc sidecar）。 */
    val convertedDir = File(context.filesDir, "converted").apply { mkdirs() }
    /** 封面图片目录（<contentHash>.<ext>），与 converted/ 同生命周期。 */
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    /** 页边界缓存目录（改版式会生成多份文件，删书时按 bookId 清理）。 */
    val pageBoundsDir = File(context.filesDir, "page_bounds")
    /** 漫画目录：`cache/<hash>/` 是可回收的解压缓存与缩略图，`local/<hash>/` 是用户选择的本地副本。 */
    val comicExtractionStore = com.llzx373.foldreader.core.comic.ComicExtractionStore(
        File(context.filesDir, "comics"),
    )
    val pageDiskCache: com.llzx373.foldreader.core.reader.PageDiskCache =
        com.llzx373.foldreader.core.reader.FilePageDiskCache(pageBoundsDir)
    val bookshelfRepository: BookshelfRepository = BookshelfRepositoryImpl(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        chapterDao = database.chapterDao(),
        bookmarkDao = database.bookmarkDao(),
        annotationDao = database.annotationDao(),
        sessionDao = database.readingSessionDao(),
        convertedDir = convertedDir,
        coversDir = coversDir,
        pageDiskCache = pageDiskCache,
        comicStore = comicExtractionStore,
    )
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context)
    val fileBrowserRootsStore = FileBrowserRootsStore(context)
    /** SAF 目录访问（文件浏览器 / 目录批量导入 / 漫画目录容器共用）。 */
    val safTree = com.llzx373.foldreader.core.format.saf.SafTree(appContext)
    val bookPrefsRepository = BookPrefsRepository(
        bookPrefsDao = database.bookPrefsDao(),
        settingsRepository = settingsRepository,
    )
    val offsetIndexDao = database.offsetIndexDao()
    val offsetIndexStore = RoomOffsetIndexStore(offsetIndexDao)
    val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onBookIndexed: suspend (Long, Long) -> Unit = { bookId, totalChars ->
        bookshelfRepository.getBook(bookId)
            ?.takeIf { it.totalChars != totalChars }
            ?.let { bookshelfRepository.upsertBook(it.copy(totalChars = totalChars)) }
    }
    private val chapterRules: suspend () -> List<Regex> = {
        ChapterRules.merge(settingsRepository.preferences.first().customChapterRules)
    }
    val txtBookParser = TxtBookParser(
        context = context,
        offsetIndexStore = offsetIndexStore,
        indexScope = parserScope,
        bookIdResolver = { uri -> bookshelfRepository.findByFileUri(uri.toString())?.id },
        contentUriResolver = { uri ->
            bookshelfRepository.findByFileUri(uri.toString())
                ?.cleanedFilePath
                ?.let { Uri.fromFile(java.io.File(it)) }
                ?: uri
        },
        onBookIndexed = onBookIndexed,
        onChaptersIndexed = { bookId, chapters ->
            bookshelfRepository.saveChapters(bookId, chapters)
        },
        chapterRules = chapterRules,
    )
    // 内部 TXT 管线（EPUB/FB2 共用）：压平文件 uri → 文件名即原书 contentHash → 反查 bookId，
    // 使偏移索引缓存落在原书 bookId 上；启发式章节回填在此禁用（真实章节由各格式 parser 回填）
    private val flattenedTxtParser = TxtBookParser(
        context = context,
        offsetIndexStore = offsetIndexStore,
        indexScope = parserScope,
        bookIdResolver = { uri ->
            uri.lastPathSegment
                ?.substringBeforeLast('.')
                ?.let { bookshelfRepository.findByContentHash(it)?.id }
        },
        contentUriResolver = { it },
        onBookIndexed = onBookIndexed,
        // 压平文件的章节来自 EPUB/FB2 的 .toc sidecar，这里扫出来的结果由下面那行空回调丢弃。
        // 用空规则集跳过索引扫描期间的逐行正则匹配——那次扫描只剩纯解码，没有白做的活。
        onChaptersIndexed = { _, _ -> },
        chapterRules = { emptyList() },
    )
    private val openFlattenedContent: suspend (File) -> com.llzx373.foldreader.core.format.BookContent =
        { file -> flattenedTxtParser.openContent(Uri.fromFile(file), Charsets.UTF_8) }
    val epubBookParser = EpubBookParser(
        convertedDir = convertedDir,
        openFlattenedContent = openFlattenedContent,
        openChannel = { uri -> UriChannels.open(context, uri) },
        displayNameOf = { uri -> UriChannels.displayName(context, uri) },
        bookIdResolver = { uri -> bookshelfRepository.findByFileUri(uri.toString())?.id },
        onChaptersIndexed = { bookId, chapters ->
            bookshelfRepository.saveChapters(bookId, chapters)
        },
    )
    val fb2BookParser = Fb2BookParser(
        convertedDir = convertedDir,
        openFlattenedContent = openFlattenedContent,
        openChannel = { uri -> UriChannels.open(context, uri) },
        displayNameOf = { uri -> UriChannels.displayName(context, uri) },
        bookIdResolver = { uri -> bookshelfRepository.findByFileUri(uri.toString())?.id },
        onChaptersIndexed = { bookId, chapters ->
            bookshelfRepository.saveChapters(bookId, chapters)
        },
    )
    /**
     * 文本型 PDF 的「当电子书读」入口：抽正文压平后进 TXT 管线。
     * 扫描件不会产出压平产物，所以这个解析器对扫描件是"存在但用不上"。
     */
    val pdfBookParser = com.llzx373.foldreader.core.pdf.PdfBookParser(
        convertedDir = convertedDir,
        appContext = appContext,
        openFlattenedContent = openFlattenedContent,
        openChannel = { uri -> UriChannels.open(context, uri) },
        displayNameOf = { uri -> UriChannels.displayName(context, uri) },
        bookIdResolver = { uri -> bookshelfRepository.findByFileUri(uri.toString())?.id },
        onChaptersIndexed = { bookId, chapters ->
            bookshelfRepository.saveChapters(bookId, chapters)
        },
    )
    val bookParsers = BookParsers(
        mapOf(
            BookFormat.TXT to txtBookParser,
            BookFormat.EPUB to epubBookParser,
            BookFormat.FB2 to fb2BookParser,
            // PDF 只在文本模式下走这里（扫描件没有压平产物，会走页式阅读器）
            BookFormat.PDF to pdfBookParser,
        ),
    )
    val fontManager = com.llzx373.foldreader.core.reader.FontManager(appContext)
    val backupManager = com.llzx373.foldreader.core.backup.BackupManager(
        context = appContext,
        bookshelfRepository = bookshelfRepository,
        settingsRepository = settingsRepository,
        bookPrefsDao = database.bookPrefsDao(),
        readingSessionDao = database.readingSessionDao(),
    )
    /** 漫画容器读取（zip 随机访问 / tar·7z·rar 解压缓存 / SAF 目录）。 */
    val comicArchiveFactory = com.llzx373.foldreader.core.comic.ComicArchiveFactory(
        extractionStore = comicExtractionStore,
        openChannel = { uri -> UriChannels.open(context, uri) },
        openDocumentStream = { key ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(key)) }.getOrNull()
        },
        listDocumentChildren = { uri ->
            safTree.listChildrenOfDocument(uri).map { entry ->
                com.llzx373.foldreader.core.comic.archive.ComicDirChild(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    key = entry.uri.toString(),
                )
            }
        },
    )
    /**
     * 打开页式阅读的内容来源。漫画容器与 PDF 文档在这里分流，
     * 阅读器（PagedReader）对格式完全无感——它只认「第 N 页、要多大、给我一张图」。
     *
     * [password] 只对加密 PDF 有意义；密码错/缺失时抛 `PagedSourcePasswordRequired`，
     * 由阅读器弹密码框重试（密码不落库）。
     */
    suspend fun openPagedSource(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
        password: String? = null,
    ): com.llzx373.foldreader.core.paged.PagedImageSource =
        if (book.format == BookFormat.PDF) {
            com.llzx373.foldreader.core.pdf.PdfPagedSource.open(
                context = appContext,
                uri = Uri.parse(book.fileUri),
                password = password,
            )
        } else {
            val container = book.comicContainer
                ?: throw java.io.IOException("缺少漫画容器信息")
            com.llzx373.foldreader.core.comic.ComicPagedSource(
                comicArchiveFactory.open(Uri.parse(book.fileUri), container, book.contentHash),
            )
        }

    val comicImportUseCase = com.llzx373.foldreader.feature.importer.ComicImportUseCase(
        bookshelfRepository = bookshelfRepository,
        archiveFactory = comicArchiveFactory,
        extractionStore = comicExtractionStore,
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        displayNameOf = { key -> UriChannels.displayName(context, Uri.parse(key)) },
        coversDir = coversDir,
    )
    /**
     * 同系列候选（前后卷切换用）：
     * 1. 当前文件**所在目录**下能认出来的漫画（可能还没导入）；
     * 2. **库内**同分组或同目录的书（跨目录整理过的也认得出来）。
     *
     * 同目录那条依赖持久化的 SAF 授权，且需要能从 documentId 反推父目录；拿不到就只靠库内那条。
     * 纯匹配与合并逻辑在 [com.llzx373.foldreader.core.comic.mergeComicSeries]（纯函数，有单测）。
     */
    suspend fun comicSeriesCandidates(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
    ): List<com.llzx373.foldreader.core.comic.ComicSeriesCandidate> {
        val current = com.llzx373.foldreader.core.comic.ComicSeriesCandidate(
            name = UriChannels.displayName(appContext, Uri.parse(book.fileUri)) ?: book.title,
            uri = book.fileUri,
            bookId = book.id,
            isDirectory = book.comicContainer == ComicContainer.FOLDER,
        )

        // 目录来源：同目录里所有「漫画容器或目录」的同级条目
        val directory = withContext(Dispatchers.IO) {
            runCatching {
                safTree.listSiblingsOfDocument(Uri.parse(book.fileUri))
                    .filter { it.uri.toString() != book.fileUri }
                    .filter { it.isDirectory || ComicContainers.fromExtension(it.name) != null }
                    .map {
                        com.llzx373.foldreader.core.comic.ComicSeriesCandidate(
                            name = it.name,
                            uri = it.uri.toString(),
                            isDirectory = it.isDirectory,
                        )
                    }
            }.getOrDefault(emptyList())
        }

        // 库内来源：优先同分组，其次同目录（fileUri 的父路径相同）
        val all = runCatching { bookshelfRepository.observeBookshelf().first() }.getOrDefault(emptyList())
        val parent = book.fileUri.substringBeforeLast('/', "")
        val library = all
            .filter { it.id != book.id && it.format == BookFormat.COMIC }
            .filter { candidate ->
                (book.groupName != null && candidate.groupName == book.groupName) ||
                    (parent.isNotEmpty() && candidate.fileUri.startsWith(parent))
            }
            .map {
                com.llzx373.foldreader.core.comic.ComicSeriesCandidate(
                    name = it.title,
                    uri = it.fileUri,
                    bookId = it.id,
                    isDirectory = it.comicContainer == ComicContainer.FOLDER,
                )
            }

        return com.llzx373.foldreader.core.comic.mergeComicSeries(current, directory, library)
    }

    /**
     * 按需导入同系列里的一个文件（点未导入的那一卷时用）。
     *
     * 复用正常导入链路（`source = EXTERNAL` + 已持久化的 SAF 授权），
     * 返回书籍 id；重复导入返回已有那本。
     */
    suspend fun importSeriesComic(uri: Uri, isDirectory: Boolean): Long? {
        val container = if (isDirectory) {
            ComicContainer.FOLDER
        } else {
            ComicContainers.fromExtension(UriChannels.displayName(appContext, uri) ?: uri.toString())
                ?: ComicContainer.ZIP
        }
        return when (
            val outcome = comicImportUseCase.register(uri, container, BookSource.EXTERNAL)
        ) {
            is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Registered ->
                outcome.bookId
            is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Duplicate ->
                outcome.bookId
            is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Failure -> null
        }
    }

    /**
     * 文件大小（MB），拿不到返回 null。
     *
     * 只用来在菜单里提示「文件较大，翻页可能偏慢」——`androidx.pdf` 对超大文档有已知的
     * 性能问题，先说清楚比让人以为卡死要好。
     */
    suspend fun fileSizeMb(book: com.llzx373.foldreader.core.data.db.BookEntity): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openAssetFileDescriptor(Uri.parse(book.fileUri), "r")
                    ?.use { it.length }
            }.getOrNull()?.takeIf { it > 0 }?.let { (it / (1024L * 1024L)).toInt() }
        }

    /**
     * 导入后后台预热：把 EPUB/FB2 的整本压平、漫画的顺序容器解压提前做掉，
     * 让首次打开通常直接命中缓存。复用 parserScope；失败静默（预热只是加速）。
     */
    val bookPrewarmQueue = com.llzx373.foldreader.core.format.BookPrewarmQueue(
        scope = parserScope,
        parserFor = { format -> bookParsers.parserFor(format) },
        onPrepared = { bookId -> bookshelfRepository.markContentPrepared(bookId) },
        comicPrepare = { bookId -> comicImportUseCase.prepare(bookId) },
        pdfPrepare = { bookId -> preparePdf(bookId) },
    )
    /**
     * PDF 的后台准备：用 PdfBox 取元数据 / 目录 / 封面 / 页数并回填。
     *
     * PdfBox 解析不了不代表读不了（加密、非常规结构都可能），所以失败时退回"只用沙箱拿页数"：
     * 拿得到就算准备完成，拿不到才让「待解析」角标继续留着等用户输密码。
     */
    private suspend fun preparePdf(bookId: Long) {
        val book = bookshelfRepository.getBook(bookId) ?: return
        // 第一趟：元数据 / 目录 / 封面（不抽正文，省掉一次全量文本抽取）
        val info = withContext(Dispatchers.IO) {
            PdfBoxReader.read(appContext, book.fileUri)
        }
        if (info == null) {
            preparePdfPageCountOnly(book)
            return
        }
        applyPdfInfo(book, info)
        // 第二趟：文本型才抽正文压平（扫描件在这里安静返回 null，页式阅读不受影响）。
        // 压平与章节回填都在 PdfBookParser 里，和 EPUB/FB2 走同一条管线。
        val charCount = withContext(Dispatchers.IO) {
            runCatching { pdfBookParser.prewarmAndCharCount(Uri.parse(book.fileUri)) }.getOrNull()
        }
        bookshelfRepository.updateConvertedFile(
            bookId = book.id,
            cleanedFilePath = charCount?.let { convertedDir.resolve("${book.contentHash}.txt").absolutePath },
            totalChars = charCount ?: 0,
        )
    }

    private suspend fun applyPdfInfo(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
        info: com.llzx373.foldreader.core.pdf.PdfDocumentInfo,
    ) {
        // 标题只在还停留在"文件名"时替换：用户看得懂的标题不该被文档里的脏标题顶掉
        val nameTitle = UriChannels.displayName(appContext, Uri.parse(book.fileUri))
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        val title = info.title.takeIf { it != null && (nameTitle == null || book.title == nameTitle) }
        bookshelfRepository.backfillPdfMetadata(
            bookId = book.id,
            title = title,
            author = info.author,
            description = info.subject,
            subjects = info.keywords,
        )
        if (info.pageCount > 0 && info.pageCount != book.comicPageCount) {
            bookshelfRepository.updateComicPageCount(book.id, info.pageCount)
        }
        info.cover?.let { cover ->
            val path = runCatching {
                writeCoverFile(coversDir, book.contentHash, cover)
            }.getOrNull()
            if (path != null) bookshelfRepository.updateCoverPath(book.id, path)
        }
        if (info.outline.isNotEmpty()) {
            // PDF 目录的锚点是页序号；charStart/charEnd 写 0，绝不拿页序号冒充字符偏移
            bookshelfRepository.saveChapters(
                book.id,
                info.outline.map { entry ->
                    Chapter(
                        title = entry.title,
                        charStart = 0L,
                        charEnd = 0L,
                        depth = entry.depth,
                        pageIndex = entry.pageIndex?.toLong(),
                    )
                },
            )
        }
    }

    /** 只用沙箱拿页数（PdfBox 解析不了时的退路）。开不出来就抛，交给队列保持「待解析」。 */
    private suspend fun preparePdfPageCountOnly(
        book: com.llzx373.foldreader.core.data.db.BookEntity,
    ) {
        if (book.comicPageCount != null) return
        val source = openPagedSource(book, null)
        try {
            bookshelfRepository.updateComicPageCount(book.id, source.pageCount)
        } finally {
            source.close()
        }
    }
    val pdfImportUseCase = com.llzx373.foldreader.feature.importer.PdfImportUseCase(
        bookshelfRepository = bookshelfRepository,
        openChannel = { key -> UriChannels.open(context, Uri.parse(key)) },
        displayNameOf = { key -> UriChannels.displayName(context, Uri.parse(key)) },
        enqueuePrewarm = { bookId, uriKey, format -> bookPrewarmQueue.enqueue(bookId, uriKey, format) },
    )
    val importBookUseCase = ImportBookUseCase(
        context = context,
        bookshelfRepository = bookshelfRepository,
        convertedParsers = mapOf(
            BookFormat.EPUB to epubBookParser,
            BookFormat.FB2 to fb2BookParser,
        ),
        coversDir = coversDir,
        enqueuePrewarm = bookPrewarmQueue::enqueue,
        comicImport = comicImportUseCase,
        pdfImport = pdfImportUseCase,
    )
    val batchImportUseCase = com.llzx373.foldreader.feature.importer.BatchImportUseCase(
        context = context,
        importBook = importBookUseCase,
        bookshelfRepository = bookshelfRepository,
        // 目录树里的「图片目录」按目录漫画登记（引用外部，不复制）
        importComicDirectory = { entry ->
            val outcome = comicImportUseCase.register(
                uri = Uri.parse(entry.uri),
                container = com.llzx373.foldreader.core.comic.ComicContainer.FOLDER,
                source = com.llzx373.foldreader.core.data.db.BookSource.EXTERNAL,
            )
            if (outcome is com.llzx373.foldreader.feature.importer.ComicImportUseCase.Outcome.Registered &&
                outcome.needsPreparation
            ) {
                bookPrewarmQueue.enqueue(outcome.bookId, entry.uri, BookFormat.COMIC)
            }
            outcome.toImportResult()
        },
    )

    /**
     * 闲时回收缓存：页边界（孤儿文件与同书过多版式副本会让 page_bounds/ 只增不减）
     * 与漫画解压缓存。放 IO 线程，不占冷启动主线程。
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        maintenanceScope.launch {
            runCatching {
                val books = bookshelfRepository.observeBookshelf().first()
                com.llzx373.foldreader.core.reader.PageBoundsGc.sweep(
                    pageBoundsDir,
                    books.map { it.id }.toSet(),
                )
                // 本地副本（local/）是用户显式选择的持久数据，sweep 只清可回收的解压缓存
                comicExtractionStore.sweep(books.map { it.contentHash }.toSet())
            }
        }
    }
}

class FoldReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 隔离进程（`android:isolatedProcess="true"`，例如 androidx.pdf 的 PDF 沙箱服务）
        // 会用**同一个 Application 类**再跑一遍 onCreate，但那里没有 UserManager、没有任何权限：
        // 碰 SharedPreferences / 文件 / DB 会直接抛
        // "SharedPreferences cannot be accessed if UserManager is not available"，
        // 进程当场死掉，沙箱服务于是永远连不上（表现为 openDocument 永久挂起）。
        //
        // 所以这里必须在做任何初始化之前退出。**以后往 onCreate 里加东西也要留在这一行后面。**
        if (com.llzx373.foldreader.core.debug.isIsolatedProcess()) return
        // 诊断日志先初始化（崩溃处理器 + return-trace.log 落盘），再装容器
        com.llzx373.foldreader.core.debug.DiagnosticLog.init(this)
        // PdfBox 的字体度量/CMap 等资源随 AAR 放在 assets/ 下，不初始化就取不到
        // （典型症状：PDType1Font 初始化时 "resource ...Times-Roman.afm not found"）
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foldableStateProvider.activityLifecycleCallbacks)
    }
}
