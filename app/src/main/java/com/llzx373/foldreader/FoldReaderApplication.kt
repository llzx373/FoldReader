package com.llzx373.foldreader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.repository.BookshelfRepositoryImpl
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.SettingsRepositoryImpl
import com.llzx373.foldreader.core.foldable.FoldableStateProvider
import com.llzx373.foldreader.core.format.txt.TxtBookParser
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(context: Context) {
    val pendingImportUri = MutableStateFlow<Uri?>(null)
    val foldableStateProvider = FoldableStateProvider(
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val database: FoldReaderDatabase =
        Room.databaseBuilder(context, FoldReaderDatabase::class.java, "foldreader.db").build()
    val bookshelfRepository: BookshelfRepository = BookshelfRepositoryImpl(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
    )
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context)
    val txtBookParser = TxtBookParser(context)
    val importBookUseCase = ImportBookUseCase(
        context = context,
        parser = txtBookParser,
        bookshelfRepository = bookshelfRepository,
    )
}

class FoldReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foldableStateProvider.activityLifecycleCallbacks)
    }
}
