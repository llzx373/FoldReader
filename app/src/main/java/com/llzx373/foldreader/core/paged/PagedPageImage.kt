package com.llzx373.foldreader.core.paged

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * 页式阅读器里「已经可以画的一页」。
 *
 * 静图是绝大多数情况；动画页（漫画里的 GIF / 动态 WebP）没有对应的 Compose 组件，
 * 只能拿着 Drawable 逐帧手绘，所以单独一个分支。PDF 永远只产出 [Still]。
 */
sealed interface PagedPageImage {
    val byteCount: Int
    val width: Int
    val height: Int

    data class Still(val bitmap: Bitmap) : PagedPageImage {
        override val byteCount: Int get() = bitmap.byteCount
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
    }

    class Animated(
        val drawable: Drawable,
        override val width: Int,
        override val height: Int,
    ) : PagedPageImage {
        override val byteCount: Int get() = width * height * 4
    }
}
