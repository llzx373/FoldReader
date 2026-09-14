package com.llzx373.foldreader

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer {
    val pendingImportUri = MutableStateFlow<Uri?>(null)
}

class FoldReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
