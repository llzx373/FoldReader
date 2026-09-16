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
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.SettingsRepositoryImpl
import com.llzx373.foldreader.core.foldable.FoldableStateProvider
import com.llzx373.foldreader.core.format.ChapterRules
import com.llzx373.foldreader.core.format.txt.TxtBookParser
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
    val bookshelfRepository: BookshelfRepository = BookshelfRepositoryImpl(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        chapterDao = database.chapterDao(),
        bookmarkDao = database.bookmarkDao(),
        annotationDao = database.annotationDao(),
        sessionDao = database.readingSessionDao(),
    )
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context)
    val bookPrefsRepository = BookPrefsRepository(
        bookPrefsDao = database.bookPrefsDao(),
        settingsRepository = settingsRepository,
    )
    val offsetIndexDao = database.offsetIndexDao()
    val offsetIndexStore = RoomOffsetIndexStore(offsetIndexDao)
    val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        onBookIndexed = { bookId, totalChars ->
            bookshelfRepository.getBook(bookId)
                ?.takeIf { it.totalChars != totalChars }
                ?.let { bookshelfRepository.upsertBook(it.copy(totalChars = totalChars)) }
        },
        onChaptersIndexed = { bookId, chapters ->
            bookshelfRepository.saveChapters(bookId, chapters)
        },
        chapterRules = {
            ChapterRules.merge(settingsRepository.preferences.first().customChapterRules)
        },
    )
    val fontManager = com.llzx373.foldreader.core.reader.FontManager(appContext)
    val pageDiskCache = com.llzx373.foldreader.core.reader.FilePageDiskCache(
        java.io.File(appContext.filesDir, "page_bounds"),
    )
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
    )
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
