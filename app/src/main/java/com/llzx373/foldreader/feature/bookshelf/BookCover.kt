package com.llzx373.foldreader.feature.bookshelf

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
