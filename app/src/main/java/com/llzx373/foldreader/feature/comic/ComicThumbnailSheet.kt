package com.llzx373.foldreader.feature.comic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** 缩略图网格每列最小宽度；自适应列数，展开态一屏能看到更多页。 */
private val THUMB_MIN_WIDTH = 76.dp

/**
 * 页缩略图网格：漫画最常用的「快速跳到某一页」入口——页码滑杆只能靠猜，
 * 缩略图能直接认出那一页画面。
 *
 * 打开时滚到当前页；图片按需加载（内存 → 磁盘 → 容器），离开视口的条目不会触发解码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicThumbnailSheet(
    pageCount: Int,
    currentPage: Int,
    viewModel: ComicReaderViewModel,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gridState = rememberLazyGridState()
    LaunchedEffect(pageCount) {
        if (currentPage in 0 until pageCount) {
            gridState.scrollToItem(currentPage)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(THUMB_MIN_WIDTH),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            items(count = pageCount, key = { it }) { index ->
                ThumbnailItem(
                    index = index,
                    selected = index == currentPage,
                    viewModel = viewModel,
                    onClick = { onJump(index) },
                )
                LaunchedEffect(index) { viewModel.ensureThumbnail(index) }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    index: Int,
    selected: Boolean,
    viewModel: ComicReaderViewModel,
    onClick: () -> Unit,
) {
    val image = viewModel.thumbnails[index]
    Surface(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (selected) 4.dp else 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 页高比页面宽高比略高（约 0.7），网格里高度一致、不随图片尺寸跳
                    .aspectRatio(0.7f)
                    .then(
                        if (selected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.small,
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "第 ${index + 1} 页",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}
