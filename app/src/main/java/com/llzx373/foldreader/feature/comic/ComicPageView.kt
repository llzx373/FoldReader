package com.llzx373.foldreader.feature.comic

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.llzx373.foldreader.core.paged.PagedPageImage
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.translate.BubbleRender
import com.llzx373.foldreader.core.translate.ComicPageTranslation
import com.llzx373.foldreader.feature.reader.pageAnnotationsOf
import com.llzx373.foldreader.feature.reader.pageBookmarksOf
import kotlin.math.roundToInt

/** 缩放上限：再放大也只是插值出的模糊，还容易把人绕晕。 */
private const val MAX_ZOOM = 4f

/**
 * 一页的几何变换（适应模式之外的缩放与平移）。
 *
 * 几何放在普通持有对象里而不是 Compose state：缩放/平移每帧都在变，
 * 走 state 会让整棵子树每帧重组。这里只用一个自增计数触发**绘制**失效
 * （读取发生在 draw 作用域内，Compose 只重画不重组）。
 */
private class PageTransform {
    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f
    var redraw by mutableIntStateOf(0)
}

/**
 * 一页漫画。静图走 `drawImage`；动画页（GIF / 动态 WebP）没有对应的 Compose 组件，按帧手工绘制。
 *
 * 手势只做缩放/平移与页内锚点，点击热区与翻页滑动仍归外层屏幕所有：单指拖动只有在内容确实溢出
 * （或已放大）时才接管并消费事件，否则原样放过，滑动手势照常翻页。
 *
 * 页内锚点（书签 / 高亮 / 选区）刻意放在**页内部**处理：这里天然知道自己的页序号、
 * 容器尺寸与缩放变换，双页模式下也不用去算铰链分区——坐标永远是页内坐标。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComicPageView(
    image: PagedPageImage?,
    failed: Boolean,
    background: Color,
    fitMode: ComicFitMode,
    /** 换页 / 换版式时重置缩放：用页序号当键。 */
    resetKey: Any,
    /** 关闭条目内缩放（纵向连续滚动模式用，避免与列表的竖向拖动抢手势）。 */
    zoomEnabled: Boolean = true,
    /** 该页的页序号：锚点回调要带上它（页自己在双页模式下不知道自己是第几页）。 */
    pageIndex: Int = -1,
    /** 是否接受页内锚点手势（纵向连续滚动模式关掉，那里是另一套坐标）。 */
    anchorsEnabled: Boolean = true,
    /** 页内锚点的显示与交互上下文（书签、高亮、选区与回调）。 */
    host: PageAnchorHost = PageAnchorHost(),
    /** 翻译覆盖层（视角①）：null = 不画。动图页不画覆盖层（无静态位图可取底色）。 */
    translation: ComicPageTranslation? = null,
    /** 覆盖层译文字体（设置页正文字体）；null = 系统默认。 */
    translationTypeface: Typeface? = null,
    /** 对照面板点中的气泡序号（画高亮边框）；-1 = 无。 */
    highlightBubble: Int = -1,
    modifier: Modifier = Modifier,
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    val transform = remember(resetKey, fitMode) { PageTransform() }
    val imageWidth = image?.width ?: 0
    val imageHeight = image?.height ?: 0

    // 拖框过程中的选区（本地持有，松手前不进阅读器状态，避免每帧一次跨组件状态写入）
    var dragging by remember(resetKey, fitMode) { mutableStateOf(false) }
    var dragAnchor by remember(resetKey, fitMode) { mutableStateOf<Pair<Float, Float>?>(null) }
    var localSelection by remember(resetKey, fitMode) { mutableStateOf<PageRect?>(null) }

    val pageBookmarks = remember(host.bookmarks, pageIndex) {
        if (pageIndex < 0) emptyList() else pageBookmarksOf(host.bookmarks, pageIndex.toLong())
    }
    val anchors = remember(pageBookmarks) {
        pageBookmarks.map { b ->
            // 没有页内坐标的是「页级书签」（顶栏按钮加的）：标在页角，而不是伪造一个位置
            PageAnchor(b.anchorX ?: 0.97f, b.anchorY ?: 0.03f, b.anchorW, b.anchorH)
        }
    }
    val highlights = remember(host.annotations, pageIndex) {
        val list = if (pageIndex < 0) emptyList() else pageAnnotationsOf(host.annotations, pageIndex.toLong())
        list.mapNotNull { a ->
            val x = a.regionX ?: return@mapNotNull null
            val y = a.regionY ?: return@mapNotNull null
            val w = a.regionW ?: return@mapNotNull null
            val h = a.regionH ?: return@mapNotNull null
            PageRegionMark(
                rect = PageRect.of(x, y, w, h),
                color = a.color,
                underline = a.style == AnnotationEntity.STYLE_UNDERLINE,
            )
        }
    }
    val effectiveSelection = localSelection ?: host.selection?.takeIf { it.pageIndex == pageIndex }?.rect

    /** 屏幕点 → 归一化页内坐标；落在页外返回 null。 */
    fun normalizedAt(offset: Offset): Pair<Float, Float>? {
        val containerW = container.width.toFloat()
        val containerH = container.height.toFloat()
        val (baseW, baseH) = comicBaseSize(imageWidth, imageHeight, containerW, containerH, fitMode)
        return comicNormalizedAt(
            x = offset.x,
            y = offset.y,
            containerW = containerW,
            containerH = containerH,
            baseW = baseW,
            baseH = baseH,
            scale = transform.scale,
            offsetX = transform.offsetX,
            offsetY = transform.offsetY,
        )
    }

    Box(
        modifier = modifier
            .background(background)
            .onSizeChanged { container = it }
            .pointerInput(zoomEnabled, resetKey, fitMode, imageWidth, imageHeight, container) {
                if (!zoomEnabled) return@pointerInput
                awaitEachGesture {
                    var consuming = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val containerW = container.width.toFloat()
                        val containerH = container.height.toFloat()
                        val (baseW, baseH) = comicBaseSize(
                            imageWidth,
                            imageHeight,
                            containerW,
                            containerH,
                            fitMode,
                        )
                        // 内容溢出（或已放大）才轮到我们接管拖动，否则让外层翻页
                        val zoomable = baseW * transform.scale > containerW + 1f ||
                            baseH * transform.scale > containerH + 1f
                        if (pressed >= 2 && zoom != 1f) {
                            val next = (transform.scale * zoom).coerceIn(1f, MAX_ZOOM)
                            // 以双指中点为锚：中点在页面上对应的位置保持不动
                            val (ox, oy) = comicZoomAnchored(
                                anchorX = centroid.x,
                                anchorY = centroid.y,
                                containerW = containerW,
                                containerH = containerH,
                                baseW = baseW,
                                baseH = baseH,
                                fromScale = transform.scale,
                                fromOffsetX = transform.offsetX,
                                fromOffsetY = transform.offsetY,
                                toScale = next,
                            )
                            transform.offsetX = ox
                            transform.offsetY = oy
                            transform.scale = next
                            clampTransform(transform, baseW, baseH, containerW, containerH)
                            transform.redraw++
                            consuming = true
                        }
                        if ((consuming || zoomable) && pan != Offset.Zero) {
                            transform.offsetX += pan.x
                            transform.offsetY += pan.y
                            clampTransform(transform, baseW, baseH, containerW, containerH)
                            transform.redraw++
                            consuming = true
                        }
                        if (consuming) event.changes.forEach { it.consume() }
                    }
                    if (transform.scale <= 1.001f) {
                        // 回到未放大状态：偏移归零，避免"看不见却还偏着"
                        transform.offsetX = 0f
                        transform.offsetY = 0f
                        transform.redraw++
                    }
                }
            }
            .pointerInput(anchorsEnabled, resetKey, fitMode, imageWidth, imageHeight, container) {
                if (!anchorsEnabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val point = normalizedAt(offset)
                        if (point == null) {
                            // 长按落在页外（比如双页模式下的另一半）：不当锚点
                            dragging = false
                            dragAnchor = null
                            return@detectDragGesturesAfterLongPress
                        }
                        dragging = true
                        dragAnchor = point
                        val rect = PageRect.between(point.first, point.second, point.first, point.second)
                        localSelection = rect
                        host.onSelectionUpdate(pageIndex, PageSelection(pageIndex, rect))
                    },
                    onDrag = { change, _ ->
                        val anchor = dragAnchor
                        if (!dragging || anchor == null) return@detectDragGesturesAfterLongPress
                        // 消费掉：否则放大后这一拖会被上面的平移分支同时吃掉
                        change.consume()
                        val point = normalizedAt(change.position)
                            ?: return@detectDragGesturesAfterLongPress
                        val rect = PageRect.between(anchor.first, anchor.second, point.first, point.second)
                        localSelection = rect
                        host.onSelectionUpdate(pageIndex, PageSelection(pageIndex, rect))
                    },
                    onDragEnd = {
                        val anchor = dragAnchor
                        val rect = localSelection
                        dragging = false
                        dragAnchor = null
                        localSelection = null
                        if (anchor == null) return@detectDragGesturesAfterLongPress
                        if (rect == null || isPointLikeSelection(rect)) {
                            // 几乎没动 = 点书签（这正是不给框选留噪声的那条判定）
                            host.onAnchorPoint(pageIndex, anchor.first, anchor.second)
                        } else {
                            // 先按手指画的框定型；有文字层时阅读器随后会用文档自己的选区替换它
                            host.onSelectionCommit(pageIndex, PageSelection(pageIndex, rect))
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        dragAnchor = null
                        localSelection = null
                    },
                )
            },
    ) {
        when {
            image != null -> PageContent(
                image = image,
                transform = transform,
                fitMode = fitMode,
                anchors = anchors,
                highlights = highlights,
                selection = effectiveSelection,
                selectionCommitted = localSelection == null && effectiveSelection != null,
                translation = translation,
                translationTypeface = translationTypeface,
                highlightBubble = highlightBubble,
            )

            // 解码失败要明确说出来：一直转圈的占位比报错更让人以为是自己没等够
            failed -> Text(
                text = "无法显示此页",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

private fun clampTransform(
    transform: PageTransform,
    baseW: Float,
    baseH: Float,
    containerW: Float,
    containerH: Float,
) {
    transform.offsetX = clampComicOffset(transform.offsetX, baseW * transform.scale, containerW)
    transform.offsetY = clampComicOffset(transform.offsetY, baseH * transform.scale, containerH)
}

@Composable
private fun PageContent(
    image: PagedPageImage,
    transform: PageTransform,
    fitMode: ComicFitMode,
    anchors: List<PageAnchor>,
    highlights: List<PageRegionMark>,
    selection: PageRect?,
    selectionCommitted: Boolean,
    translation: ComicPageTranslation?,
    translationTypeface: Typeface?,
    highlightBubble: Int,
) {
    when (image) {
        is PagedPageImage.Still -> {
            val bitmap = remember(image) { image.bitmap.asImageBitmap() }
            // 气泡底色按页图片现算：键只取几何（流式追加译文不重采样）
            val bubbleRects = translation?.bubbles?.map { it.rect }
            val bubbleBackgrounds = remember(image.bitmap, bubbleRects) {
                sampleBubbleBackgrounds(image.bitmap, translation)
            }
            Box(
                modifier = Modifier.fillMaxSize().drawBehind {
                    if (transform.redraw < 0) return@drawBehind
                    val (baseW, baseH) = comicBaseSize(
                        image.width,
                        image.height,
                        size.width,
                        size.height,
                        fitMode,
                    )
                    drawScaledBitmap(
                        bitmap = bitmap,
                        drawW = baseW * transform.scale,
                        drawH = baseH * transform.scale,
                        offsetX = transform.offsetX,
                        offsetY = transform.offsetY,
                    )
                    if (translation != null) {
                        drawTranslationOverlay(
                            baseW = baseW,
                            baseH = baseH,
                            scale = transform.scale,
                            offsetX = transform.offsetX,
                            offsetY = transform.offsetY,
                            translation = translation,
                            backgrounds = bubbleBackgrounds,
                            typeface = translationTypeface,
                            highlightBubble = highlightBubble,
                        )
                    }
                    drawPageMarks(
                        baseW = baseW,
                        baseH = baseH,
                        scale = transform.scale,
                        offsetX = transform.offsetX,
                        offsetY = transform.offsetY,
                        anchors = anchors,
                        highlights = highlights,
                        selection = selection,
                        selectionCommitted = selectionCommitted,
                    )
                },
            )
        }

        is PagedPageImage.Animated -> {
            var tick by remember(image) { mutableIntStateOf(0) }
            DisposableEffect(image) {
                val animatable = image.drawable as? Animatable
                animatable?.start()
                onDispose { animatable?.stop() }
            }
            LaunchedEffect(image) {
                // AnimatedImageDrawable 自己推进帧，但 Compose 不会因此重绘：按帧驱动绘制失效
                while (true) withFrameNanos { tick++ }
            }
            Box(
                modifier = Modifier.fillMaxSize().drawBehind {
                    if (transform.redraw < 0 || tick < 0) return@drawBehind
                    val (baseW, baseH) = comicBaseSize(
                        image.width,
                        image.height,
                        size.width,
                        size.height,
                        fitMode,
                    )
                    val drawW = baseW * transform.scale
                    val drawH = baseH * transform.scale
                    val left = (size.width - drawW) / 2f + transform.offsetX
                    val top = (size.height - drawH) / 2f + transform.offsetY
                    image.drawable.setBounds(
                        left.roundToInt(),
                        top.roundToInt(),
                        (left + drawW).roundToInt(),
                        (top + drawH).roundToInt(),
                    )
                    image.drawable.draw(drawContext.canvas.nativeCanvas)
                    drawPageMarks(
                        baseW = baseW,
                        baseH = baseH,
                        scale = transform.scale,
                        offsetX = transform.offsetX,
                        offsetY = transform.offsetY,
                        anchors = anchors,
                        highlights = highlights,
                        selection = selection,
                        selectionCommitted = selectionCommitted,
                    )
                },
            )
        }
    }
}

/**
 * 画页内锚点、高亮与选区。
 *
 * 全部走 [comicDrawRect] 的同一套落位公式：归一化坐标乘当前绘制尺寸再叠加落位，
 * 所以缩放、平移、切换适配模式之后锚点仍然贴在原文上——这正是存归一化坐标的意义。
 * 锚点标记与高亮一律以页面像素为线宽的基准（1dp 级别），放大时不会跟着变成粗块。
 */
private fun DrawScope.drawPageMarks(
    baseW: Float,
    baseH: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    anchors: List<PageAnchor>,
    highlights: List<PageRegionMark>,
    selection: PageRect?,
    selectionCommitted: Boolean,
) {
    val rect = comicDrawRect(size.width, size.height, baseW, baseH, scale, offsetX, offsetY)
    if (rect.width <= 0f || rect.height <= 0f) return

    fun toScreen(r: PageRect): Rect = Rect(
        left = rect.left + r.left * rect.width,
        top = rect.top + r.top * rect.height,
        right = rect.left + r.right * rect.width,
        bottom = rect.top + r.bottom * rect.height,
    )

    // 高亮 / 下划线：半透明填充与线宽跟文本阅读器的观感保持一致
    highlights.forEach { mark ->
        val r = toScreen(mark.rect)
        if (mark.underline) {
            drawLine(
                color = Color(mark.color.toInt()),
                start = Offset(r.left, r.bottom),
                end = Offset(r.right, r.bottom),
                strokeWidth = maxOf(2f, r.height * 0.12f),
            )
        } else {
            drawRect(
                color = Color(mark.color.toInt()).copy(alpha = 0.30f),
                topLeft = Offset(r.left, r.top),
                size = Size(r.width, r.height),
            )
        }
    }

    // 选区：拖框中画实边，定型后画一层淡底 + 虚线边（可与高亮区分开）
    if (selection != null) {
        val r = toScreen(selection)
        if (selectionCommitted) {
            drawRect(
                color = selectionColor.copy(alpha = 0.18f),
                topLeft = Offset(r.left, r.top),
                size = Size(r.width, r.height),
            )
        }
        drawRect(
            color = selectionColor,
            topLeft = Offset(r.left, r.top),
            size = Size(r.width, r.height),
            style = Stroke(width = if (selectionCommitted) 1.5f else 2.5f),
        )
    }

    // 书签：点锚点画小圆，区域锚点画描边
    anchors.forEach { anchor ->
        val x = rect.left + anchor.x * rect.width
        val y = rect.top + anchor.y * rect.height
        if (anchor.w == null || anchor.h == null) {
            drawCircle(color = anchorColor, radius = 7f, center = Offset(x, y))
            drawCircle(
                color = Color.White,
                radius = 7f,
                center = Offset(x, y),
                style = Stroke(width = 2f),
            )
        } else {
            val w = anchor.w * rect.width
            val h = anchor.h * rect.height
            drawRect(
                color = anchorColor.copy(alpha = 0.18f),
                topLeft = Offset(x, y),
                size = Size(w, h),
            )
            drawRect(
                color = anchorColor,
                topLeft = Offset(x, y),
                size = Size(w, h),
                style = Stroke(width = 2f),
            )
        }
    }
}

private val anchorColor = Color(0xFFE53935)

private val selectionColor = Color(0xFF3F51B5)

private fun DrawScope.drawScaledBitmap(
    bitmap: ImageBitmap,
    drawW: Float,
    drawH: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val left = (size.width - drawW) / 2f + offsetX
    val top = (size.height - drawH) / 2f + offsetY
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(
            drawW.roundToInt().coerceAtLeast(1),
            drawH.roundToInt().coerceAtLeast(1),
        ),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * 在页图片上采气泡底色：气泡外周一圈像素取逐通道中位数（见 BubbleRender.samplePoints）。
 * 采不到（越界等）回落白底。返回与气泡一一对应的 ARGB 数组；translation 为 null 时为空数组。
 */
private fun sampleBubbleBackgrounds(bitmap: Bitmap, translation: ComicPageTranslation?): IntArray {
    val bubbles = translation?.bubbles ?: return IntArray(0)
    return IntArray(bubbles.size) { i ->
        val colors = BubbleRender.samplePoints(bubbles[i].rect).mapNotNull { (nx, ny) ->
            val x = (nx * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = (ny * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            runCatching { bitmap.getPixel(x, y) }.getOrNull()
        }
        BubbleRender.medianColor(colors) ?: 0xFFFFFFFF.toInt()
    }
}

private val lowConfidenceBorderColor = Color(0xFFFFA000)

private val highlightBorderColor = Color(0xFF1E88E5)

/**
 * 画翻译覆盖层（视角①）：气泡位铺底色圆角块 + 译文（字号自适应收缩、居中多行）。
 *
 * 与 [drawPageMarks] 同一套落位公式，缩放/平移后仍贴在气泡上。
 * 文字走 nativeCanvas（Compose 的 drawText 不接自定义 Typeface）。
 * 低置信气泡画琥珀色边框提醒人工核对；对照面板点中的气泡画蓝色高亮边框。
 */
private fun DrawScope.drawTranslationOverlay(
    baseW: Float,
    baseH: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    translation: ComicPageTranslation,
    backgrounds: IntArray,
    typeface: Typeface?,
    highlightBubble: Int,
) {
    val rect = comicDrawRect(size.width, size.height, baseW, baseH, scale, offsetX, offsetY)
    if (rect.width <= 0f || rect.height <= 0f) return
    val canvas = drawContext.canvas.nativeCanvas
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        this.typeface = typeface
    }
    val rectF = RectF()
    translation.bubbles.forEachIndexed { index, bubble ->
        val text = bubble.text ?: return@forEachIndexed
        val left = rect.left + bubble.rect.left * rect.width
        val top = rect.top + bubble.rect.top * rect.height
        val right = rect.left + bubble.rect.right * rect.width
        val bottom = rect.top + bubble.rect.bottom * rect.height
        val w = right - left
        val h = bottom - top
        if (w <= 4f || h <= 4f) return@forEachIndexed
        rectF.set(left, top, right, bottom)
        val bg = backgrounds.getOrNull(index) ?: 0xFFFFFFFF.toInt()
        fillPaint.color = bg
        fillPaint.alpha = 235
        val corner = minOf(w, h) * 0.12f
        canvas.drawRoundRect(rectF, corner, corner, fillPaint)
        val borderColor = when {
            index == highlightBubble -> highlightBorderColor
            bubble.lowConfidence -> lowConfidenceBorderColor
            else -> null
        }
        if (borderColor != null) {
            borderPaint.color = borderColor.toArgb()
            borderPaint.strokeWidth = if (index == highlightBubble) 3f else 2f
            canvas.drawRoundRect(rectF, corner, corner, borderPaint)
        }
        // 排版：纯逻辑折行与字号收缩，算出来的就是画出来的
        val layout = BubbleRender.layout(
            text = text,
            rectWidth = w,
            rectHeight = h,
            maxFont = h * 0.5f,
        )
        textPaint.color = BubbleRender.textColorFor(bg)
        textPaint.textSize = layout.fontSize
        val blockTop = top + (h - layout.textHeight) / 2f
        layout.lines.forEachIndexed { lineIndex, line ->
            canvas.drawText(
                line,
                (left + right) / 2f,
                blockTop + lineIndex * layout.lineHeight + layout.fontSize * 0.85f,
                textPaint,
            )
        }
    }
}
