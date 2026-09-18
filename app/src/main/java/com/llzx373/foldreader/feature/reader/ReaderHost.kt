package com.llzx373.foldreader.feature.reader

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.settings.PdfReadingMode
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.feature.bookshelf.isPagedFormat
import com.llzx373.foldreader.feature.comic.ComicReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读页分发：按书籍格式选择阅读器实现。
 *
 * 放在覆盖层与具体阅读器之间，是因为「这本书该用哪个阅读器」在打开前就得知道：
 * 文本阅读器拿漫画当 TXT 去建索引只会得到一堆乱码，而再往里加分支会让那条
 * 已经稳定的分页链路多出漫画专用的判断。
 *
 * 格式查库期间先显示一帧加载态——此时正好与封面的共享元素飞入重叠，不是空窗。
 */
@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ReaderHost(
    bookId: Long,
    initialAnchor: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    foldableUiState: FoldableUiState,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    coverTitle: String? = null,
    /** 页式阅读器切到同系列的其它卷：由导航层替换当前阅读页。 */
    onOpenBook: (Long) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as FoldReaderApplication
    // 书与偏好都要**跟着变化**：PDF 在阅读中切「页式/文本」时，这里要当场换掉读者，
    // 而不是等用户退出去重进（那是最容易被当成"没生效"的交互）
    val book by produceState<BookEntity?>(initialValue = null, bookId) {
        value = withContext(Dispatchers.IO) { app.container.bookshelfRepository.getBook(bookId) }
    }
    val prefs by app.container.bookPrefsRepository.observe(bookId).collectAsState(initial = null)
    val switchScope = rememberCoroutineScope()

    val current = book
    when {
        current == null -> Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator()
        }

        // PDF 的文本模式：正文已抽出压平，走文本阅读器（可重排 / 可搜索 / 有书签划线）
        resolvePdfReadingMode(prefs?.pdfReadingMode, current.cleanedFilePath != null) ==
            PdfReadingMode.TEXT && current.format == BookFormat.PDF -> ReaderScreen(
            bookId = bookId,
            initialAnchor = initialAnchor,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            foldableUiState = foldableUiState,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            coverTitle = coverTitle,
            // PDF 才有"另一种读法"可切；偏好一变这里就换回页式阅读器
            onSwitchToPagedMode = if (current.format == BookFormat.PDF) {
                {
                    switchScope.launch {
                        app.container.bookPrefsRepository.update(bookId) {
                            it.copy(pdfReadingMode = PdfReadingMode.PAGED.name)
                        }
                    }
                }
            } else {
                null
            },
        )

        // 其余页式格式（漫画 + 扫描型 PDF）：共用同一个阅读器，来源不同、交互一致
        isPagedFormat(current.format) -> ComicReaderScreen(
            bookId = bookId,
            // 页式格式的进度按页序号存；文本的字符锚点只在同格式之间有意义，不做换算
            initialPage = if (initialAnchor >= 0L) initialAnchor.toInt() else -1,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            foldableUiState = foldableUiState,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            coverTitle = coverTitle,
            onOpenBook = onOpenBook,
        )

        else -> ReaderScreen(
            bookId = bookId,
            initialAnchor = initialAnchor,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            foldableUiState = foldableUiState,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            coverTitle = coverTitle,
        )
    }
}
