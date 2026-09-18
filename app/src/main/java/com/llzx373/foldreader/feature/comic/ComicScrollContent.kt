package com.llzx373.foldreader.feature.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.llzx373.foldreader.core.data.settings.ComicFitMode

/** 宽高比未知时的占位比例：按常见漫画页（约 0.7）先占位，探测完成后换成真实高度。 */
private const val PLACEHOLDER_ASPECT = 0.7f

/**
 * 纵向连续滚动（条漫 / Webtoon）。
 *
 * 每条的高度按该页真实宽高比算出（来自后台尺寸探测），而不是等图片解码后再定——
 * 否则滚动过程中条目高度反复变化会把当前视口顶跑。间距为 0 时相邻页严丝合缝，
 * 长条漫就是靠这个连成一整幅。
 */
@Composable
fun ComicScrollContent(
    pageCount: Int,
    viewModel: ComicReaderViewModel,
    background: Color,
    gap: Dp,
    listState: LazyListState,
    host: PageAnchorHost,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.background(background).clipToBounds(),
        verticalArrangement = if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Top,
    ) {
        items(count = pageCount, key = { it }) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(viewModel.aspectOf(index) ?: PLACEHOLDER_ASPECT),
            ) {
                ComicPageView(
                    image = viewModel.images[index],
                    failed = viewModel.failedPages[index] == true,
                    background = background,
                    fitMode = ComicFitMode.FIT_WIDTH,
                    resetKey = index,
                    // 滚动模式下条目内不做缩放：内容与条目等大，没有可平移的余量，
                    // 开了反而会跟 LazyColumn 抢竖向拖动
                    zoomEnabled = false,
                    pageIndex = index,
                    // 条漫里页是竖向连成一条的，「页内」坐标与屏幕坐标不是一套换算，暂不接受锚点手势；
                    // 但已有书签与高亮照常显示（它们用的是页内归一化坐标）
                    anchorsEnabled = false,
                    host = host,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            LaunchedEffect(index) { viewModel.ensurePage(index) }
        }
    }
}
