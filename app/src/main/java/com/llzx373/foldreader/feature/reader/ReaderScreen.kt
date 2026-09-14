package com.llzx373.foldreader.feature.reader

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.ui.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val INNER_SPINE_PAD = 12.dp
private val SPINE_OVERLAY_WIDTH = 32.dp

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    foldableUiState: FoldableUiState,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.factory(app.container, bookId),
    )
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val autoPageStatus by viewModel.autoPageStatus.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkedOffsets = remember(bookmarks) { bookmarks.map { it.charOffset }.toSet() }
    val annotations by viewModel.annotations.collectAsState()
    val shiftedAnnotationIds by viewModel.shiftedAnnotationIds.collectAsState()
    val colors = readerColors(prefs.themeId, prefs.customBackgroundArgb, prefs.customTextArgb)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var menuVisible by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var bookmarksVisible by remember { mutableStateOf(false) }
    var annotationsVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    val searchState by viewModel.searchState.collectAsState()
    val searchHighlight by viewModel.searchHighlight.collectAsState()

    // 命中高亮 5 秒后淡出（或下一次操作清除）
    LaunchedEffect(searchHighlight) {
        if (searchHighlight != null) {
            delay(5000)
            viewModel.clearSearchHighlight()
        }
    }
    // 长按选择（仅翻页模式；滚动模式降级为仅渲染标注，见 TODO 5.2 注记）
    var selection by remember { mutableStateOf<SelectionUi?>(null) }
    var noteDraft by remember { mutableStateOf<SelectionUi?>(null) }
    var editingAnnotation by remember {
        mutableStateOf<com.llzx373.foldreader.core.data.db.AnnotationEntity?>(null)
    }
    val leftLineBoxes = remember {
        mutableStateOf<List<com.llzx373.foldreader.core.reader.LineBox>?>(null)
    }
    val rightLineBoxes = remember {
        mutableStateOf<List<com.llzx373.foldreader.core.reader.LineBox>?>(null)
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var windowOffsetX by remember { mutableStateOf(0f) }
    var windowOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    val rawMode = prefs.pageTurnMode
    val scrollMode = rawMode == PageTurnMode.SCROLL
    val effectiveMode = when (rawMode) {
        PageTurnMode.SIMULATION ->
            if (prefs.simulationDegraded) PageTurnMode.COVER else PageTurnMode.SIMULATION
        else -> rawMode
    }

    val layoutMode = resolvePageLayoutMode(
        posture = foldableUiState.posture,
        widthCategory = foldableUiState.widthCategory,
        pref = prefs.dualPageMode,
    )

    val hingeLocal = foldableUiState.posture.hingeBounds
        ?.takeIf { it.width > 0f || it.height > 0f }
        ?.let { Rect(it.left - windowOffsetX, it.top - windowOffsetY, it.right - windowOffsetX, it.bottom - windowOffsetY) }
    val tabletop = resolveTabletopLayout(
        posture = foldableUiState.posture,
        hingeLocal = hingeLocal,
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
    )
    val dual = layoutMode == PageLayoutMode.DUAL && !scrollMode && tabletop == null

    val splitLeftPx = (hingeLocal?.left ?: size.width / 2f).coerceIn(0f, size.width.toFloat())
    val splitRightPx = (hingeLocal?.right ?: size.width / 2f).coerceIn(splitLeftPx, size.width.toFloat())
    val contentRect = tabletop?.content ?: contentRectFor(
        posture = foldableUiState.posture,
        hingeLocal = hingeLocal,
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
    )

    LaunchedEffect(dual, size, splitLeftPx, splitRightPx, contentRect, density.density, density.fontScale) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (dual) {
            viewModel.setViewports(
                dual = true,
                leftWidthPx = splitLeftPx.roundToInt(),
                rightWidthPx = (size.width - splitRightPx).roundToInt(),
                heightPx = size.height,
                density = density.density,
                scaledDensity = density.density * density.fontScale,
            )
        } else {
            if (contentRect.width <= 0f || contentRect.height <= 0f) return@LaunchedEffect
            viewModel.setViewports(
                dual = false,
                leftWidthPx = contentRect.width.roundToInt(),
                rightWidthPx = 0,
                heightPx = contentRect.height.roundToInt(),
                density = density.density,
                scaledDensity = density.density * density.fontScale,
            )
        }
    }

    var animSpread by remember { mutableStateOf<PageSpread?>(null) }
    val animX = remember { Animatable(0f) }

    val prevSpread by viewModel.prevSpread.collectAsState()
    val nextSpread by viewModel.nextSpread.collectAsState()
    var simTarget by remember { mutableStateOf<PageSpread?>(null) }
    var simForward by remember { mutableStateOf(true) }
    var simProgress by remember { mutableFloatStateOf(0f) }
    var simSettling by remember { mutableStateOf(false) }
    val frameMonitor = remember { FrameHealthMonitor() }

    val simActive = simTarget != null
    LaunchedEffect(simActive) {
        if (!simActive) return@LaunchedEffect
        frameMonitor.reset()
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) frameMonitor.noteFrame((t - last) / 1_000_000f)
                last = t
            }
        }
    }

    fun maybeDegradeSimulation() {
        if (frameMonitor.shouldDegrade()) {
            viewModel.setSimulationDegraded(true)
            Toast.makeText(context, "已切换为流畅模式（覆盖滑动）", Toast.LENGTH_SHORT).show()
        }
        frameMonitor.reset()
    }

    fun clearSelection() {
        selection = null
        noteDraft = null
    }

    fun selectionEnd(sel: SelectionUi): Long =
        if (sel.end > sel.start) sel.end else sel.start + 1

    fun turn(forward: Boolean) {
        clearSelection()
        scope.launch {
            if (animSpread != null || simTarget != null || simSettling) return@launch
            if (effectiveMode == PageTurnMode.SIMULATION && !scrollMode) {
                val target = (if (forward) nextSpread else prevSpread)
                    ?: viewModel.adjacentSpread(forward) ?: return@launch
                simTarget = target
                simForward = forward
                animate(
                    0f, 1f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy,
                    ),
                ) { v, _ -> simProgress = v }
                viewModel.showSpread(target)
                simTarget = null
                simProgress = 0f
                maybeDegradeSimulation()
                return@launch
            }
            val target = viewModel.adjacentSpread(forward) ?: return@launch
            if (effectiveMode == PageTurnMode.NONE || size.width <= 0) {
                viewModel.showSpread(target)
                return@launch
            }
            animSpread = target
            animX.snapTo(if (forward) size.width.toFloat() else -size.width.toFloat())
            animX.animateTo(0f, tween(220))
            viewModel.showSpread(target)
            animSpread = null
        }
    }

    SystemBarEffects(menuVisible = menuVisible, keepScreenOn = prefs.keepScreenOn)
    BrightnessEffect(prefs.readerBrightness)

    var foreground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(foreground, menuVisible, uiState.loading, uiState.error, selection != null) {
        viewModel.setReadingActive(
            foreground && !menuVisible && !uiState.loading && uiState.error == null,
        )
        viewModel.setAutoPageUiPaused(
            !foreground || menuVisible || selection != null || uiState.loading || uiState.error != null,
        )
    }

    LaunchedEffect(rawMode) {
        if (scrollMode) viewModel.enterScrollMode() else viewModel.relocate()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    var autoScrollY by remember { mutableStateOf(0f) }
    LaunchedEffect(uiState.spread) { autoScrollY = 0f }
    LaunchedEffect(scrollMode, uiState.layoutConfig, size.height) {
        if (scrollMode) return@LaunchedEffect
        viewModel.autoScrollTicks.collect { delta ->
            val spread = uiState.spread ?: return@collect
            val config = uiState.layoutConfig
            val scaledDensity = density.density * density.fontScale
            val fontSizePx = config.fontSizeSp * scaledDensity
            val breaks = spread.left.lines.count { it.isParagraphStart } - 1
            val maxScroll = maxAutoScrollPx(
                lineCount = spread.left.lines.size,
                paragraphBreaks = breaks.coerceAtLeast(0),
                lineHeightPx = fontSizePx * config.lineSpacingMultiplier,
                paragraphSpacingPx = fontSizePx * config.paragraphSpacingEm,
                marginTopPx = config.marginTopDp * density.density,
                marginBottomPx = config.marginBottomDp * density.density,
                viewportHeightPx = size.height,
            )
            autoScrollY += delta
            if (autoScrollY >= maxScroll) {
                autoScrollY = 0f
                turn(true)
            }
        }
    }

    val innerPadPx = with(density) { INNER_SPINE_PAD.toPx() }
    val spineOverlayPx = with(density) { SPINE_OVERLAY_WIDTH.toPx() }
    val leftDp = with(density) { splitLeftPx.toDp() }
    val hingeDp = with(density) { (splitRightPx - splitLeftPx).toDp() }
    val rightDp = with(density) { (size.width - splitRightPx).toDp() }

    // 触摸点 → （左/右页, 字符光标位）；dual 时按铰链分区，局部坐标扣页偏移
    fun hitCaret(offset: Offset, constrainToLeft: Boolean? = null): Pair<Boolean, Long>? {
        if (size.width <= 0) return null
        val relX = offset.x - contentRect.left
        val relY = offset.y - contentRect.top + autoScrollY
        val leftPageRight = splitLeftPx - contentRect.left
        val rightPageX = splitRightPx - contentRect.left
        val isLeft: Boolean
        var localX = relX
        when {
            !dual -> isLeft = true
            constrainToLeft == true -> isLeft = true
            constrainToLeft == false -> {
                isLeft = false
                localX = relX - rightPageX
            }
            relX < leftPageRight -> isLeft = true
            relX > rightPageX -> {
                isLeft = false
                localX = relX - rightPageX
            }
            else -> return null // 铰链区
        }
        val boxes = (if (isLeft) leftLineBoxes.value else rightLineBoxes.value) ?: return null
        val caret = com.llzx373.foldreader.core.reader.caretAt(boxes, localX, relY) ?: return null
        return isLeft to caret
    }

    fun spansFor(page: com.llzx373.foldreader.core.reader.Page?): List<TextRangeSpan> {
        if (page == null) return emptyList()
        val spans = annotations.mapNotNull { ann ->
            if (ann.endCharOffset > page.charStart && ann.startCharOffset < page.charEnd) {
                TextRangeSpan(ann.startCharOffset, ann.endCharOffset, Color(ann.color.toInt()))
            } else {
                null
            }
        }
        val hit = searchHighlight
        return if (hit != null && hit.second > page.charStart && hit.first < page.charEnd) {
            spans + TextRangeSpan(hit.first, hit.second, colors.accent.copy(alpha = 0.5f))
        } else {
            spans
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged { size = it }
            .onGloballyPositioned {
                windowOffsetX = it.boundsInWindow().left
                windowOffsetY = it.boundsInWindow().top
            }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (selection != null ||
                    !prefs.volumeKeyPagingEnabled || native.action != KeyEvent.ACTION_DOWN
                ) {
                    return@onKeyEvent false
                }
                when (native.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> { turn(false); true }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { turn(true); true }
                    else -> false
                }
            }
            .pointerInput(prefs.pageTurnHotspotRatio, scrollMode) {
                detectTapGestures { offset ->
                    viewModel.noteManualInteraction()
                    if (selection != null) {
                        clearSelection()
                        return@detectTapGestures
                    }
                    if (menuVisible) {
                        menuVisible = false
                        return@detectTapGestures
                    }
                    if (scrollMode) {
                        menuVisible = true
                        return@detectTapGestures
                    }
                    // 点击已有划线 → 查看/编辑（先于翻页热区）
                    val hit = hitCaret(offset)
                    if (hit != null) {
                        val caret = hit.second
                        val ann = annotations.firstOrNull {
                            caret >= it.startCharOffset && caret < it.endCharOffset
                        }
                        if (ann != null) {
                            editingAnnotation = ann
                            return@detectTapGestures
                        }
                    }
                    when (tapZoneOf(offset.x, size.width.toFloat(), prefs.pageTurnHotspotRatio)) {
                        TapZone.PREVIOUS -> turn(false)
                        TapZone.NEXT -> turn(true)
                        TapZone.MENU -> menuVisible = true
                    }
                }
            }
            .pointerInput(scrollMode, dual) {
                // 长按进入选择模式，拖动扩展选区（跨页降级为当前页内，见 TODO 5.2 注记）
                if (scrollMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (menuVisible || simTarget != null) return@detectDragGesturesAfterLongPress
                        val hit = hitCaret(offset) ?: return@detectDragGesturesAfterLongPress
                        selection = SelectionUi(hit.first, hit.second, hit.second, dragging = true)
                    },
                    onDrag = { change, _ ->
                        val sel = selection ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        val hit = hitCaret(change.position, constrainToLeft = sel.pageLeft)
                            ?: return@detectDragGesturesAfterLongPress
                        selection = sel.copy(caret = hit.second)
                    },
                    onDragEnd = {
                        selection = selection?.copy(dragging = false)
                    },
                    onDragCancel = { selection = null },
                )
            }
            .pointerInput(scrollMode, effectiveMode, selection != null) {
                if (selection != null) return@pointerInput
                var dragged = 0f
                val samples = ArrayDeque<Pair<Long, Float>>()
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragged = 0f
                        samples.clear()
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragged += dragAmount
                        samples.addLast(System.nanoTime() to dragged)
                        while (samples.size > 2 &&
                            System.nanoTime() - samples.first().first > 120_000_000L
                        ) {
                            samples.removeFirst()
                        }
                        if (effectiveMode == PageTurnMode.SIMULATION && !scrollMode &&
                            !simSettling && size.width > 0
                        ) {
                            val forward = dragged < 0
                            val target = if (forward) nextSpread else prevSpread
                            if (target != null) {
                                simTarget = target
                                simForward = forward
                                simProgress = (abs(dragged) / size.width).coerceIn(0f, 1f)
                            }
                        }
                    },
                    onDragCancel = {
                        simTarget = null
                        simProgress = 0f
                        dragged = 0f
                    },
                    onDragEnd = {
                        if (!scrollMode) {
                            val sim = simTarget
                            if (effectiveMode == PageTurnMode.SIMULATION && sim != null) {
                                val now = System.nanoTime()
                                val velocity = if (samples.size >= 2) {
                                    val (t0, d0) = samples.first()
                                    (dragged - d0) / ((now - t0) / 1_000_000_000f)
                                } else {
                                    0f
                                }
                                val directed = if (simForward) -velocity else velocity
                                val outcome = decideTurnOutcome(simProgress, directed)
                                simSettling = true
                                scope.launch {
                                    if (outcome == TurnOutcome.COMPLETE) {
                                        animate(
                                            simProgress, 1f,
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                            ),
                                        ) { v, _ -> simProgress = v }
                                        viewModel.noteManualInteraction()
                                        viewModel.showSpread(sim)
                                    } else {
                                        animate(
                                            simProgress, 0f,
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                            ),
                                        ) { v, _ -> simProgress = v }
                                    }
                                    simTarget = null
                                    simProgress = 0f
                                    simSettling = false
                                    maybeDegradeSimulation()
                                }
                            } else if (effectiveMode != PageTurnMode.SIMULATION) {
                                val threshold = size.width * 0.15f
                                if (dragged < -threshold) {
                                    viewModel.noteManualInteraction()
                                    turn(true)
                                } else if (dragged > threshold) {
                                    viewModel.noteManualInteraction()
                                    turn(false)
                                }
                            }
                        }
                        dragged = 0f
                    },
                )
            },
    ) {
        when {
            uiState.loading -> LoadingIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = colors.accent,
            )
            uiState.error != null -> EmptyState(
                title = "无法打开书籍",
                description = uiState.error,
                actionLabel = "重试",
                onAction = viewModel::retry,
                secondaryActionLabel = "返回书架",
                onSecondaryAction = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Box(
                modifier = Modifier
                    .offset { IntOffset(contentRect.left.roundToInt(), contentRect.top.roundToInt()) }
                    .width(with(density) { contentRect.width.toDp() })
                    .height(with(density) { contentRect.height.toDp() })
                    .clipToBounds(),
            ) {
                if (scrollMode) {
                    ScrollContent(
                        viewModel = viewModel,
                        colors = colors,
                        pageHeight = contentRect.height.roundToInt(),
                    )
                } else {
                    val spread = uiState.spread
                    val sim = simTarget
                    if (spread != null && sim != null) {
                        SpreadContent(
                            spread = sim,
                            config = uiState.layoutConfig,
                            colors = colors,
                            dual = uiState.dualPage,
                            leftDp = leftDp,
                            hingeDp = hingeDp,
                            rightDp = rightDp,
                            innerPadPx = innerPadPx,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = 0.7f + 0.3f * simProgress },
                        )
                        SpreadContent(
                            spread = spread,
                            config = uiState.layoutConfig,
                            colors = colors,
                            dual = uiState.dualPage,
                            leftDp = leftDp,
                            hingeDp = hingeDp,
                            rightDp = rightDp,
                            innerPadPx = innerPadPx,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = if (simForward) {
                                        -simProgress * size.width
                                    } else {
                                        simProgress * size.width
                                    }
                                    scaleY = curlScaleY(simProgress)
                                },
                        )
                        val canvasWidthPx = size.width.toFloat()
                        val canvasHeightPx = size.height.toFloat()
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val edgeX = if (simForward) {
                                canvasWidthPx * (1f - simProgress)
                            } else {
                                canvasWidthPx * simProgress
                            }
                            val band = 40.dp.toPx()
                            val shadow = Color.Black.copy(alpha = curlShadowAlpha(simProgress))
                            if (simForward) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(shadow, Color.Transparent),
                                        startX = edgeX,
                                        endX = edgeX + band,
                                    ),
                                    topLeft = Offset(edgeX, 0f),
                                    size = Size(band, canvasHeightPx),
                                )
                            } else {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color.Transparent, shadow),
                                        startX = edgeX - band,
                                        endX = edgeX,
                                    ),
                                    topLeft = Offset(edgeX - band, 0f),
                                    size = Size(band, canvasHeightPx),
                                )
                            }
                        }
                    } else if (spread != null) {
                        SpreadContent(
                            spread = spread,
                            config = uiState.layoutConfig,
                            colors = colors,
                            dual = uiState.dualPage,
                            leftDp = leftDp,
                            hingeDp = hingeDp,
                            rightDp = rightDp,
                            innerPadPx = innerPadPx,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationY = -autoScrollY },
                            leftHighlights = spansFor(spread.left),
                            rightHighlights = spansFor(spread.right),
                            selection = selection,
                            selectionColor = colors.accent.copy(alpha = 0.32f),
                            onLeftGeometry = { leftLineBoxes.value = it },
                            onRightGeometry = { rightLineBoxes.value = it },
                        )
                        val overlay = animSpread
                        if (overlay != null) {
                            SpreadContent(
                                spread = overlay,
                                config = uiState.layoutConfig,
                                colors = colors,
                                dual = uiState.dualPage,
                                leftDp = leftDp,
                                hingeDp = hingeDp,
                                rightDp = rightDp,
                                innerPadPx = innerPadPx,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { translationX = animX.value },
                            )
                        }
                        if (dual) {
                            val spineCenter = (splitLeftPx + splitRightPx) / 2f
                            SpineOverlay(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(SPINE_OVERLAY_WIDTH)
                                    .offset {
                                        IntOffset((spineCenter - spineOverlayPx / 2f).roundToInt(), 0)
                                    },
                            )
                        }
                    }
                }
            }
        }

        // 选择手柄 + 选区操作条（菜单打开时隐藏）
        val activeSelection = selection
        if (activeSelection != null && !menuVisible && !uiState.loading && uiState.error == null) {
            val selBoxes =
                if (activeSelection.pageLeft) leftLineBoxes.value else rightLineBoxes.value
            if (selBoxes != null) {
                val pageOriginX = if (dual && !activeSelection.pageLeft) {
                    splitRightPx
                } else {
                    contentRect.left
                }
                SelectionHandles(
                    boxes = selBoxes,
                    start = activeSelection.start,
                    end = activeSelection.end,
                    originXPx = pageOriginX,
                    originYPx = contentRect.top,
                    scrollYPx = autoScrollY,
                    accent = colors.accent,
                    onDragHandle = { isStart, pageLocal ->
                        val caret = com.llzx373.foldreader.core.reader.caretAt(
                            selBoxes, pageLocal.x, pageLocal.y,
                        )
                        if (caret != null) {
                            selection = if (isStart) {
                                activeSelection.copy(anchor = caret, dragging = false)
                            } else {
                                activeSelection.copy(caret = caret, dragging = false)
                            }
                        }
                    },
                )
            }
            SelectionActionBar(
                colors = colors,
                onPickColor = { argb ->
                    viewModel.addAnnotation(
                        activeSelection.start,
                        selectionEnd(activeSelection),
                        argb,
                        null,
                    )
                    clearSelection()
                },
                onNote = { noteDraft = activeSelection },
                onCopy = {
                    scope.launch {
                        clipboard.setText(
                            AnnotatedString(
                                viewModel.selectedTextOf(
                                    activeSelection.start,
                                    selectionEnd(activeSelection),
                                ),
                            ),
                        )
                    }
                    clearSelection()
                },
                onCancel = { clearSelection() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }

        if (tabletop != null && !uiState.loading && uiState.error == null) {
            TabletopDivider(
                colors = colors,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, tabletop.content.height.roundToInt()) },
            )
            TabletopPanel(
                prefs = prefs,
                progressFraction = uiState.progressFraction,
                colors = colors,
                autoPageStatus = autoPageStatus,
                onPrevPage = { viewModel.noteManualInteraction(); turn(false) },
                onNextPage = { viewModel.noteManualInteraction(); turn(true) },
                onSeekFraction = { f -> scope.launch { viewModel.seekToFraction(f) } },
                onPrevChapter = { scope.launch { viewModel.seekChapter(-1) } },
                onNextChapter = { scope.launch { viewModel.seekChapter(1) } },
                onSetBrightness = viewModel::setReaderBrightness,
                onFontSizeDelta = { delta ->
                    viewModel.setFontSize((prefs.fontSizeSp + delta).coerceIn(12f, 32f))
                },
                onToggleAutoPage = viewModel::setAutoPageEnabled,
                onCycleAutoPageMode = {
                    viewModel.setAutoPageMode(nextAutoPageMode(prefs.autoPageMode))
                },
                onCycleAutoPageSpeed = {
                    when (prefs.autoPageMode) {
                        AutoPageMode.INTERVAL ->
                            viewModel.setAutoPageIntervalSec(nextAutoPageIntervalSec(prefs.autoPageIntervalSec))
                        AutoPageMode.SCROLL ->
                            viewModel.setAutoPageSpeedPx(nextAutoPageSpeedPx(prefs.autoPageSpeedPx))
                    }
                },
                onTogglePanelOff = viewModel::setPanelScreenOff,
                modifier = Modifier
                    .offset { IntOffset(0, tabletop.panel.top.roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { tabletop.panel.height.toDp() }),
            )
        }

        if (!uiState.loading && uiState.error == null) {
            val battery by rememberBatteryPercent()
            val time by rememberClock()
            val progressText = if (prefs.showPageProgress) formatPercent(uiState.progressFraction) else null
            val rightFooter = listOfNotNull(
                if (prefs.showBattery && battery >= 0) "电量 $battery%" else null,
                if (prefs.showTime) time else null,
            ).joinToString("  ")

            if (dual) {
                if (prefs.showChapterTitle) {
                    CornerLabel(
                        text = uiState.chapterTitle,
                        colors = colors,
                        endAligned = false,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .width(leftDp),
                    )
                }
                CornerLabel(
                    text = uiState.bookTitle,
                    colors = colors,
                    endAligned = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .width(rightDp),
                )
                if (progressText != null) {
                    CornerLabel(
                        text = progressText,
                        colors = colors,
                        endAligned = false,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .width(leftDp),
                    )
                }
                CornerLabel(
                    text = rightFooter,
                    colors = colors,
                    endAligned = true,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .width(rightDp),
                )
            } else {
                if (prefs.showChapterTitle) {
                    ReaderHeader(
                        chapterTitle = uiState.chapterTitle,
                        colors = colors,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding(),
                    )
                }
                ReaderFooter(
                    progressText = progressText,
                    batteryText = if (prefs.showBattery && battery >= 0) "电量 $battery%" else null,
                    timeText = if (prefs.showTime) time else null,
                    colors = colors,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }

        if (menuVisible) {
            ReaderTopBar(
                bookTitle = uiState.bookTitle,
                chapterTitle = uiState.chapterTitle,
                colors = colors,
                onBack = onBack,
                dualPage = uiState.dualPage,
                hasRightPage = uiState.spread?.right != null,
                leftBookmarked = uiState.spread?.left?.charStart in bookmarkedOffsets,
                rightBookmarked = uiState.spread?.right?.charStart in bookmarkedOffsets,
                onToggleBookmark = viewModel::toggleBookmark,
                onOpenBookmarks = { bookmarksVisible = true },
                onOpenSearch = { searchVisible = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderMenuPanel(
                prefs = prefs,
                progressFraction = uiState.progressFraction,
                colors = colors,
                onSeekFraction = { f ->
                    scope.launch {
                        viewModel.seekToFraction(f)
                        if (scrollMode) viewModel.enterScrollMode()
                    }
                },
                onOpenCatalog = { catalogVisible = true },
                onOpenAnnotations = { annotationsVisible = true },
                onCyclePageTurnMode = {
                    viewModel.setPageTurnMode(nextPageTurnMode(prefs.pageTurnMode))
                },
                onCycleDualPageMode = {
                    viewModel.setDualPageMode(nextDualPageMode(prefs.dualPageMode))
                },
                onSetBrightness = viewModel::setReaderBrightness,
                onSetFontSize = viewModel::setFontSize,
                onSetLineSpacing = viewModel::setLineSpacing,
                onSetMarginLevel = viewModel::setMarginLevel,
                onSelectTheme = viewModel::setTheme,
                onPickCustomBackground = { argb ->
                    viewModel.setCustomColors(argb, prefs.customTextArgb)
                },
                onPickCustomText = { argb ->
                    viewModel.setCustomColors(prefs.customBackgroundArgb, argb)
                },
                autoPageStatus = autoPageStatus,
                onToggleAutoPage = viewModel::setAutoPageEnabled,
                onCycleAutoPageMode = {
                    viewModel.setAutoPageMode(nextAutoPageMode(prefs.autoPageMode))
                },
                onCycleAutoPageSpeed = {
                    when (prefs.autoPageMode) {
                        AutoPageMode.INTERVAL ->
                            viewModel.setAutoPageIntervalSec(nextAutoPageIntervalSec(prefs.autoPageIntervalSec))
                        AutoPageMode.SCROLL ->
                            viewModel.setAutoPageSpeedPx(nextAutoPageSpeedPx(prefs.autoPageSpeedPx))
                    }
                },
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        if (catalogVisible) {
            ChapterListDialog(
                chapters = viewModel.chapterList(),
                currentIndex = uiState.chapterIndex,
                remainingText = viewModel.remainingTimeText(),
                colors = colors,
                onSelect = { index ->
                    catalogVisible = false
                    scope.launch {
                        viewModel.chapterAt(index)?.let { viewModel.seekToOffset(it.charStart) }
                        if (scrollMode) viewModel.enterScrollMode()
                    }
                },
                onDismiss = { catalogVisible = false },
            )
        }

        if (bookmarksVisible) {
            BookmarkListDialog(
                bookmarks = bookmarks,
                chapters = viewModel.chapterList(),
                colors = colors,
                onJump = { bookmark ->
                    bookmarksVisible = false
                    scope.launch {
                        viewModel.seekToOffset(bookmark.charOffset)
                        if (scrollMode) viewModel.enterScrollMode()
                    }
                },
                onRename = viewModel::renameBookmark,
                onDelete = { viewModel.deleteBookmark(it.id) },
                onDismiss = { bookmarksVisible = false },
            )
        }

        // 新建带笔记的划线（选区操作条「笔记」入口）
        noteDraft?.let { draft ->
            val draftEnd = selectionEnd(draft)
            val draftText by produceState(initialValue = "", draft) {
                value = viewModel.selectedTextOf(draft.start, draftEnd)
            }
            AnnotationEditDialog(
                selectedText = draftText,
                initialColorArgb = annotationColorPalette.first().toArgb().toLong() and 0xFFFFFFFFL,
                initialNote = null,
                shifted = false,
                colors = colors,
                onSave = { c, n ->
                    viewModel.addAnnotation(draft.start, draftEnd, c, n)
                    clearSelection()
                },
                onDelete = null,
                onDismiss = { clearSelection() },
            )
        }

        // 查看/编辑已有划线（点击正文划线或标注列表长按）
        editingAnnotation?.let { ann ->
            AnnotationEditDialog(
                selectedText = ann.selectedText,
                initialColorArgb = ann.color,
                initialNote = ann.note,
                shifted = ann.id in shiftedAnnotationIds,
                colors = colors,
                onSave = { c, n ->
                    viewModel.updateAnnotation(ann, c, n)
                    editingAnnotation = null
                },
                onDelete = {
                    viewModel.deleteAnnotation(ann.id)
                    editingAnnotation = null
                },
                onDismiss = { editingAnnotation = null },
            )
        }

        if (annotationsVisible) {
            AnnotationListDialog(
                annotations = annotations,
                chapters = viewModel.chapterList(),
                shiftedIds = shiftedAnnotationIds,
                colors = colors,
                onJump = { ann ->
                    annotationsVisible = false
                    scope.launch {
                        viewModel.seekToOffset(ann.startCharOffset)
                        if (scrollMode) viewModel.enterScrollMode()
                    }
                },
                onEdit = { ann -> editingAnnotation = ann },
                onDismiss = { annotationsVisible = false },
            )
        }

        if (searchVisible) {
            ReaderSearchDialog(
                state = searchState,
                chapters = viewModel.chapterList(),
                colors = colors,
                onSearch = viewModel::startSearch,
                onJump = { hit ->
                    searchVisible = false
                    menuVisible = false
                    scope.launch {
                        viewModel.seekToOffset(hit.offset)
                        if (scrollMode) viewModel.enterScrollMode()
                        viewModel.setSearchHighlight(
                            hit.offset,
                            hit.offset + hit.matchLength,
                        )
                    }
                },
                onDismiss = {
                    searchVisible = false
                    viewModel.cancelSearch() // 关闭面板即取消后台扫描
                },
            )
        }
    }
}

@Composable
private fun SpreadContent(
    spread: PageSpread,
    config: com.llzx373.foldreader.core.reader.LayoutConfig,
    colors: ReaderColors,
    dual: Boolean,
    leftDp: androidx.compose.ui.unit.Dp,
    hingeDp: androidx.compose.ui.unit.Dp,
    rightDp: androidx.compose.ui.unit.Dp,
    innerPadPx: Float,
    modifier: Modifier = Modifier,
    leftHighlights: List<TextRangeSpan> = emptyList(),
    rightHighlights: List<TextRangeSpan> = emptyList(),
    selection: SelectionUi? = null,
    selectionColor: Color = Color.Unspecified,
    onLeftGeometry: (List<com.llzx373.foldreader.core.reader.LineBox>) -> Unit = {},
    onRightGeometry: (List<com.llzx373.foldreader.core.reader.LineBox>) -> Unit = {},
) {
    val selectionSpan = selection?.let { sel ->
        val end = if (sel.end > sel.start) sel.end else sel.start + 1
        TextRangeSpan(sel.start, end, selectionColor)
    }
    if (!dual) {
        PageView(
            page = spread.left,
            config = config,
            colors = colors,
            modifier = modifier,
            highlights = leftHighlights,
            selection = selectionSpan?.takeIf { selection?.pageLeft != false },
            onGeometry = onLeftGeometry,
        )
        return
    }
    Row(modifier = modifier) {
        Box(modifier = Modifier.width(leftDp).fillMaxHeight()) {
            PageView(
                page = spread.left,
                config = config,
                colors = colors,
                modifier = Modifier.fillMaxSize(),
                innerPaddingPx = innerPadPx,
                innerOnRight = true,
                highlights = leftHighlights,
                selection = selectionSpan?.takeIf { selection?.pageLeft == true },
                onGeometry = onLeftGeometry,
            )
        }
        Box(modifier = Modifier.width(hingeDp).fillMaxHeight())
        Box(modifier = Modifier.width(rightDp).fillMaxHeight()) {
            spread.right?.let { right ->
                PageView(
                    page = right,
                    config = config,
                    colors = colors,
                    modifier = Modifier.fillMaxSize(),
                    innerPaddingPx = innerPadPx,
                    innerOnRight = false,
                    highlights = rightHighlights,
                    selection = selectionSpan?.takeIf { selection?.pageLeft == false },
                    onGeometry = onRightGeometry,
                )
            }
        }
    }
}

@Composable
private fun CornerLabel(
    text: String,
    colors: ReaderColors,
    endAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (endAligned) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.text.copy(alpha = 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ScrollContent(
    viewModel: ReaderViewModel,
    colors: ReaderColors,
    pageHeight: Int,
) {
    val uiState by viewModel.uiState.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val searchHighlight by viewModel.searchHighlight.collectAsState()
    val listState = rememberLazyListState()
    var extending by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val pages = uiState.scrollPages
            val page = pages.getOrNull(index) ?: return@collect
            viewModel.scrollAnchorTo(page.charStart)
            if (!extending && (index < 2 || index > pages.lastIndex - 2)) {
                extending = true
                try {
                    if (index < 2) viewModel.scrollExtend(forward = false)
                    if (index > uiState.scrollPages.lastIndex - 2) viewModel.scrollExtend(forward = true)
                } finally {
                    extending = false
                }
            }
        }
    }

    LaunchedEffect(listState) {
        viewModel.autoScrollTicks.collect { delta -> listState.scroll { scrollBy(delta) } }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(uiState.scrollPages, key = { it.charStart }) { page ->
            val spans = annotations.mapNotNull { ann ->
                if (ann.endCharOffset > page.charStart && ann.startCharOffset < page.charEnd) {
                    TextRangeSpan(ann.startCharOffset, ann.endCharOffset, Color(ann.color.toInt()))
                } else {
                    null
                }
            }.let { spans ->
                val hit = searchHighlight
                if (hit != null && hit.second > page.charStart && hit.first < page.charEnd) {
                    spans + TextRangeSpan(hit.first, hit.second, colors.accent.copy(alpha = 0.5f))
                } else {
                    spans
                }
            }
            PageView(
                page = page,
                config = uiState.layoutConfig,
                colors = colors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { pageHeight.toDp() }),
                highlights = spans,
            )
        }
    }
}

@Composable
private fun SystemBarEffects(menuVisible: Boolean, keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(menuVisible) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        if (menuVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(keepScreenOn) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
private fun BrightnessEffect(brightness: Float) {
    val view = LocalView.current
    DisposableEffect(brightness) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val previous = window.attributes.screenBrightness
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
        onDispose {
            val restored = window.attributes
            restored.screenBrightness = previous
            window.attributes = restored
        }
    }
}

@Composable
private fun rememberBatteryPercent(): androidx.compose.runtime.State<Int> {
    val context = LocalContext.current
    return produceState(initialValue = -1) {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        value = if (level >= 0 && scale > 0) level * 100 / scale else -1
    }
}

@Composable
private fun rememberClock(): androidx.compose.runtime.State<String> {
    return produceState(initialValue = "") {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            value = format.format(Date())
            delay(30_000L)
        }
    }
}
