package com.llzx373.foldreader.feature.bookshelf

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookshelfScreen(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: BookshelfViewModel = viewModel(factory = BookshelfViewModel.factory(app.container))
    val books by viewModel.books.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.import(uri, openAfterImport = false)
        }
    }

    LaunchedEffect(Unit) {
        app.container.pendingImportUri.collect { uri ->
            if (uri != null) {
                app.container.pendingImportUri.value = null
                viewModel.import(uri, openAfterImport = true)
            }
        }
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportUiState.Imported -> {
                if (state.openAfter) onOpenBook(state.bookId)
                else snackbarHostState.showSnackbar("《${state.title}》已加入书架")
                viewModel.consumeImportState()
            }
            is ImportUiState.Duplicate -> {
                if (state.sameFile && state.openAfter) onOpenBook(state.bookId)
                else snackbarHostState.showSnackbar("《${state.title}》已在书架")
                viewModel.consumeImportState()
            }
            is ImportUiState.Error -> {
                snackbarHostState.showSnackbar("导入失败：${state.message}")
                viewModel.consumeImportState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("书架") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    openDocumentLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入书籍") },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("书架空空如也，去导入第一本书吧")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(books, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = { Text(book.title) },
                            supportingContent = {
                                Text(book.author ?: "未知作者")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBook(book.id) },
                        )
                    }
                }
            }
            if (importState is ImportUiState.Importing) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        LoadingIndicator()
                        Text(
                            text = "正在导入…",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
