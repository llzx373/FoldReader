package com.llzx373.foldreader.feature.comic

import androidx.compose.runtime.Immutable
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity

/**
 * 归一化页内矩形（0..1，页图片左上为原点）。
 *
 * 页的渲染尺寸随适配模式、缩放、窗口与分屏变化，所以锚点一律存归一化：
 * 画的时候乘当前绘制尺寸、命中时除回去，缩放与换适配模式都不会错位。
 */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        /** 两点围成的矩形：自动排序上下左右并夹进页内。 */
        fun between(ax: Float, ay: Float, bx: Float, by: Float) = PageRect(
            left = minOf(ax, bx).coerceIn(0f, 1f),
            top = minOf(ay, by).coerceIn(0f, 1f),
            right = maxOf(ax, bx).coerceIn(0f, 1f),
            bottom = maxOf(ay, by).coerceIn(0f, 1f),
        )

        fun of(x: Float, y: Float, w: Float, h: Float) =
            PageRect(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), (x + w).coerceIn(0f, 1f), (y + h).coerceIn(0f, 1f))
    }
}

/** 页上一个书签锚点。[w]/[h] 为 null 表示点书签（画一个点标记），否则画区域描边。 */
data class PageAnchor(val x: Float, val y: Float, val w: Float?, val h: Float?)

/** 页上一块高亮/下划线。 */
data class PageRegionMark(val rect: PageRect, val color: Long, val underline: Boolean)

/** 页内选区。 */
data class PageSelection(
    /** 选区所在页：动作条要知道把改动落到哪一页。 */
    val pageIndex: Int,
    val rect: PageRect,
    val text: String? = null,
)

/**
 * 页内锚点的显示与交互上下文。
 *
 * 打包成一个对象是为了让 [ComicPageView] 的调用点只多两个参数——单页、双页左右半屏、
 * 跨页大图、滚动条目一共六个调用点，逐个传十来个参数很快就会传错。
 */
@Immutable
data class PageAnchorHost(
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    /** 当前选区（阅读器持有，取消或翻页时清空）。 */
    val selection: PageSelection? = null,
    /** 长按不动：在该点加/删书签。 */
    val onAnchorPoint: (pageIndex: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    /** 长按拖动中：选区实时变化。 */
    val onSelectionUpdate: (pageIndex: Int, selection: PageSelection) -> Unit = { _, _ -> },
    /** 松手且区域够大：选区定型，界面弹动作条（选字由阅读器随后向文档换取）。 */
    val onSelectionCommit: (pageIndex: Int, selection: PageSelection) -> Unit = { _, _ -> },
)

/** 选区小到这个尺寸以下就当作「点」处理：长按时手指指腹的抖动不该被当成框选。 */
const val MIN_SELECTION_SIZE = 0.02f

fun isPointLikeSelection(rect: PageRect): Boolean =
    rect.width < MIN_SELECTION_SIZE || rect.height < MIN_SELECTION_SIZE
