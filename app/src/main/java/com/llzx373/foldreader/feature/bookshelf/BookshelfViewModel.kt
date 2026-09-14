package com.llzx373.foldreader.feature.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.feature.importer.ImportBookUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Importing : ImportUiState
    data class Imported(val bookId: Long, val title: String, val openAfter: Boolean) : ImportUiState
    data class Duplicate(
        val bookId: Long,
        val title: String,
        val sameFile: Boolean,
        val openAfter: Boolean,
    ) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class BookshelfViewModel(
    private val importBook: ImportBookUseCase,
    bookshelfRepository: BookshelfRepository,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = bookshelfRepository.observeBookshelf()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    fun import(uri: Uri, openAfterImport: Boolean) {
        if (_importState.value is ImportUiState.Importing) return
        _importState.value = ImportUiState.Importing
        viewModelScope.launch {
            _importState.value = when (val result = importBook.import(uri)) {
                is ImportBookUseCase.Result.Imported ->
                    ImportUiState.Imported(result.bookId, result.title, openAfterImport)
                is ImportBookUseCase.Result.DuplicateSameUri ->
                    ImportUiState.Duplicate(result.bookId, result.title, sameFile = true, openAfter = openAfterImport)
                is ImportBookUseCase.Result.DuplicateSameHash ->
                    ImportUiState.Duplicate(result.bookId, result.title, sameFile = false, openAfter = false)
                is ImportBookUseCase.Result.Failure ->
                    ImportUiState.Error(result.message ?: "未知错误")
            }
        }
    }

    fun consumeImportState() {
        _importState.value = ImportUiState.Idle
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BookshelfViewModel(container.importBookUseCase, container.bookshelfRepository) }
        }
    }
}
