package com.llzx373.foldreader

import android.app.Application
import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.foldable.FoldableStateProvider
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
