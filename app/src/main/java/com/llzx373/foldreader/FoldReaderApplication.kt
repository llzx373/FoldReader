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
import com.llzx373.foldreader.core.format.BookParsers
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.epub.EpubBookParser
import com.llzx373.foldreader.core.format.fb2.Fb2BookParser
import com.llzx373.foldreader.core.format.txt.TxtBookParser
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val pendingImportUri = MutableStateFlow<Uri?>(null)
    val activeReaderBookId = MutableStateFlow<Long?>(null)
    val appContext: Context = context.applicationContext
    val foldableStateProvider = FoldableStateProvider(
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val database: FoldReaderDatabase =
        Room.databaseBuilder(context, FoldReaderDatabase::class.java, "foldreader.db")
            .addMigrations(com.llzx373.foldreader.core.data.db.MIGRATION_10_11)
            .build()
    /** 非 TXT 格式的压平缓存目录（<contentHash>.txt + .toc sidecar）。 */
    val convertedDir = File(context.filesDir, "converted").apply { mkdirs() }
    /** 封面图片目录（<contentHash>.<ext>），与 converted/ 同生命周期。 */
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    /** 页边界缓存目录（改版式会生成多份文件，删书时按 bookId 清理）。 */
    val pageBoundsDir = File(context.filesDir, "page_bounds")
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
    )
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context)
    val fileBrowserRootsStore = FileBrowserRootsStore(context)
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
        onChaptersIndexed = { _, _ -> },
        chapterRules = chapterRules,
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
    val bookParsers = BookParsers(
        mapOf(
            BookFormat.TXT to txtBookParser,
            BookFormat.EPUB to epubBookParser,
            BookFormat.FB2 to fb2BookParser,
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
    val importBookUseCase = ImportBookUseCase(
        context = context,
        bookshelfRepository = bookshelfRepository,
        convertedParsers = mapOf(
            BookFormat.EPUB to epubBookParser,
            BookFormat.FB2 to fb2BookParser,
        ),
        coversDir = coversDir,
    )
    val batchImportUseCase = com.llzx373.foldreader.feature.importer.BatchImportUseCase(
        context = context,
        importBook = importBookUseCase,
        bookshelfRepository = bookshelfRepository,
    )

    /**
     * 闲时回收页边界缓存：孤儿文件（书已不在书架）与同书过多的版式副本
     * 都会让 page_bounds/ 只增不减。放 IO 线程，不占冷启动主线程。
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        maintenanceScope.launch {
            runCatching {
                val live = bookshelfRepository.observeBookshelf().first().map { it.id }.toSet()
                com.llzx373.foldreader.core.reader.PageBoundsGc.sweep(pageBoundsDir, live)
            }
        }
    }
}

class FoldReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 诊断日志先初始化（崩溃处理器 + return-trace.log 落盘），再装容器
        com.llzx373.foldreader.core.debug.DiagnosticLog.init(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foldableStateProvider.activityLifecycleCallbacks)
    }
}
