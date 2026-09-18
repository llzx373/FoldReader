package com.llzx373.foldreader.feature.bookshelf

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BookCoverPalette {
    val palette = listOf(
        Color(0xFF3E5F8A),
        Color(0xFF6B8E5A),
        Color(0xFFA6654E),
        Color(0xFF7A5C8E),
        Color(0xFF4E7A7A),
        Color(0xFFB08A4F),
        Color(0xFF5C6B73),
        Color(0xFF8E5A6B),
    )

    fun colorFor(title: String): Color =
        palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
}

fun formatReadingProgress(charOffset: Long?, totalChars: Long): String =
    if (charOffset == null || totalChars <= 0) {
        "未开始"
    } else {
        "已读 ${(charOffset * 100 / totalChars).coerceIn(0, 100)}%"
    }

/**
 * 漫画进度按页序号算（与文本的字符偏移语义分开）。
 * 页数为 null 表示还没解析出来（rar/tar/7z 待预热），此时只能显示「未开始」。
 */
fun formatComicProgress(comicPage: Int?, pageCount: Int?): String =
    if (comicPage == null || pageCount == null || pageCount <= 0) {
        "未开始"
    } else {
        "已读 ${(comicPage * 100 / pageCount).coerceIn(0, 100)}%"
    }

/** 页式格式（漫画 / PDF）：进度按页序号算，详情页不展示文本类字段。 */
fun isPagedFormat(format: BookFormat): Boolean =
    format == BookFormat.COMIC || format == BookFormat.PDF

/** 页式格式在书架上显示的短标签。 */
fun pagedFormatLabel(book: BookEntity): String = when (book.format) {
    BookFormat.PDF -> "PDF"
    else -> comicContainerLabel(book.comicContainer)
}

/** 漫画容器在书架上的短标签。 */
fun comicContainerLabel(container: ComicContainer?): String = when (container) {
    ComicContainer.ZIP -> "CBZ"
    ComicContainer.RAR -> "CBR"
    ComicContainer.TAR -> "CBT"
    ComicContainer.SEVEN_ZIP -> "CB7"
    ComicContainer.FOLDER -> "文件夹"
    null -> "漫画"
}

fun formatLastRead(timestamp: Long?): String? =
    timestamp?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BookCover(
    title: String,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    bookId: Long = 0L,
    /** 真实封面图片路径（EPUB 导入时提取）；null 或解码失败时用标题占位封面。 */
    coverPath: String? = null,
) {
    val sharedModifier =
        if (sharedTransitionScope != null && animatedVisibilityScope != null && bookId != 0L) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "cover-$bookId"),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        } else {
            Modifier
        }
    Surface(
        modifier = sharedModifier.then(modifier).aspectRatio(3f / 4f),
        shape = MaterialTheme.shapes.largeIncreased,
        color = BookCoverPalette.colorFor(title),
    ) {
        val coverBitmap = coverPath?.let { rememberCoverBitmap(it) }
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(12.dp)
                        .background(Color.Black.copy(alpha = 0.18f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = 16.dp)
                        .width(1.dp)
                        .background(Color.White.copy(alpha = 0.45f)),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 26.dp, top = 14.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    Text(
                        text = title.take(2),
                        color = Color.White,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = title,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberCoverBitmap(path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeCover(path) }
    }
    return bitmap
}

/** 解码封面并按 ~480x640 目标降采样，防止大图占内存；失败返回 null 走占位封面。 */
private fun decodeCover(path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 480 && bounds.outHeight / (sample * 2) >= 640) {
        sample *= 2
    }
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()
