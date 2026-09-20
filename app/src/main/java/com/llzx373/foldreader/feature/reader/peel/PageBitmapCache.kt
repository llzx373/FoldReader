package com.llzx373.foldreader.feature.reader.peel

import android.graphics.Bitmap
import android.graphics.Canvas
import com.llzx373.foldreader.core.reader.LayoutConfig
import com.llzx373.foldreader.core.reader.Page
import com.llzx373.foldreader.core.reader.drawPageInto
import com.llzx373.foldreader.feature.reader.ReaderColors
import androidx.compose.ui.graphics.toArgb

/**
 * 把一页正文画进离屏位图。页眉页脚不烘进去（由阅读器静止层自己画）。
 */
fun renderPageBitmap(
    page: Page,
    config: LayoutConfig,
    colors: ReaderColors,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    scaledDensity: Float,
    innerPaddingPx: Float = 0f,
    innerOnRight: Boolean = true,
    extraTopPadPx: Float = 0f,
    imageProvider: ((String) -> Bitmap?)? = null,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(colors.background.toArgb())
    drawPageInto(
        canvas = Canvas(bitmap),
        page = page,
        config = config,
        textColorArgb = colors.text.toArgb(),
        density = density,
        scaledDensity = scaledDensity,
        widthPx = w.toFloat(),
        innerPaddingPx = innerPaddingPx,
        innerOnRight = innerOnRight,
        extraTopPadPx = extraTopPadPx,
        imageProvider = imageProvider,
    )
    return bitmap
}
