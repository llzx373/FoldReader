package com.llzx373.foldreader.feature.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.debug.ReturnTrace
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.core.reader.LinkHit
import com.llzx373.foldreader.core.reader.PageAvoidance
import com.llzx373.foldreader.feature.bookshelf.BookCover
import com.llzx373.foldreader.ui.EmptyState
import com.llzx373.foldreader.ui.rememberLocale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val INNER_SPINE_PAD = 12.dp
private val SPINE_OVERLAY_WIDTH = 32.dp

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    foldableUiState: FoldableUiState,
    initialAnchor: Long = -1L,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    coverTitle: String? = null,
    /** PDF 文本模式下的「切到页式」出口（由 ReaderHost 决定给不给）。 */
    onSwitchToPagedMode: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.factory(app.container, bookId, initialAnchor),
    )
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val prefsLoaded by viewModel.preferencesLoaded.collectAsState()
    val autoPageStatus by viewModel.autoPageStatus.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkedOffsets = remember(bookmarks) { bookmarks.map { it.charOffset }.toSet() }
    val annotations by viewModel.annotations.collectAsState()
    // 标注按区间建索引：每个可见页都全量扫标注列表在标注多时是笔固定开销
    val annotationIndex = remember(annotations) { AnnotationIndex(annotations) }
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
    // 长按选择：分页模式支持拖边跨页；滚动模式在 ScrollContent 内按项实现
    var selection by remember { mutableStateOf<SelectionUi?>(null) }
    var noteDraft by remember { mutableStateOf<SelectionUi?>(null) }
    var editingAnnotation by remember {
        mutableStateOf<com.llzx373.foldreader.core.data.db.AnnotationEntity?>(null)
    }
    // 脚注弹注：noteref 点按命中的目标 charOffset；非 null 时弹窗加载并展示注释内容
    var notePopupTarget by remember { mutableStateOf<Long?>(null) }
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

    // 阅读页几何变化（尺寸/窗口偏移/双页判定）：诊断"进书首帧是否就是终局尺寸"，
    // 覆盖层化之后这里应只出现一次（不再先窄后宽重排）
    LaunchedEffect(size, windowOffsetX, windowOffsetY) {
        if (size != IntSize.Zero) {
            ReturnTrace.log(
                "reader: size=$size offset=(${windowOffsetX.toInt()},${windowOffsetY.toInt()})",
            )
        }
    }
    val layoutMode = resolvePageLayoutMode(
        posture = foldableUiState.posture,
        widthCategory = foldableUiState.widthCategory,
        windowPortrait = foldableUiState.windowPortrait,
        pref = prefs.dualPageMode,
        wideScreenDualPage = prefs.wideScreenDualPage,
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
    val pageDual = layoutMode == PageLayoutMode.DUAL && tabletop == null
    val rawMode = prefs.pageTurnMode
    val scrollMode = rawMode == PageTurnMode.SCROLL
    val dual = pageDual && !scrollMode
    // 双页外观（书脊、页眉页脚、页码）与正文排版统一以分页流的 dualPage 为准
    val spreadDual = uiState.dualPage && !scrollMode
    val scrollDual = isDualColumnScroll(layoutMode, scrollMode, tabletop != null)

    val (splitLeftPx, splitRightPx) = dualSplit(
        posture = foldableUiState.posture,
        hingeLocal = hingeLocal,
        widthPx = size.width.toFloat(),
    )
    val contentRect = tabletop?.content ?: contentRectFor(
        posture = foldableUiState.posture,
        hingeLocal = hingeLocal,
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
    )
    val spreadPageWidthPx = minOf(splitLeftPx, size.width - splitRightPx).coerceAtLeast(0f)
    val pageWidthDp = with(density) { spreadPageWidthPx.toDp() }
    val leftInsetPx = (splitLeftPx - spreadPageWidthPx).coerceAtLeast(0f) / 2f
    val rightInsetPx = (size.width - splitRightPx - spreadPageWidthPx).coerceAtLeast(0f) / 2f

    // 版式几何指纹：与 setViewports 入参同源，用于识别"几何已变、分页流未重排完"的窗口期
    val currentGeom = if (pageDual) {
        SpreadGeometry(
            dual = true,
            pageWidthPx = dualPageWidthPx(splitLeftPx.roundToInt(), (size.width - splitRightPx).roundToInt()),
            heightPx = size.height,
        )
    } else {
        SpreadGeometry(
            dual = false,
            pageWidthPx = contentRect.width.roundToInt(),
            heightPx = contentRect.height.roundToInt(),
        )
    }
    // 几何不匹配时禁止用新版式画旧 spread（正文是 Canvas 手绘，字距按绘制宽度算，会爆开/重叠）
    val geomReady = uiState.spreadGeometry == currentGeom

    // 摄像头开孔规避：双页翻页模式下按 WindowInsets.displayCutout 实算——开孔压住
    // 右页顶部则右页（奇数序页）顶部让位，压住左页底部则左页（偶数序页）底部让位。
    // 经 setViewports 触发重分页，与分页器减容严格同源；滚动/单页模式不适用
    val avoidLineHeightPx = uiState.layoutConfig.let {
        it.fontSizeSp * density.density * density.fontScale * it.lineSpacingMultiplier
    }
    val view = LocalView.current
    val cutoutRects = remember(view) {
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
            .map { ContentRect(it.left.toFloat(), it.top.toFloat(), (it.right - it.left).toFloat(), (it.bottom - it.top).toFloat()) }
    }
    val pageAvoidance = if (dual && prefs.avoidCameraCutout) {
        cameraAvoidanceLines(
            cutouts = cutoutRects,
            leftPage = ContentRect(
                left = windowOffsetX + contentRect.left + leftInsetPx,
                top = windowOffsetY + contentRect.top,
                width = spreadPageWidthPx,
                height = contentRect.height,
            ),
            rightPage = ContentRect(
                left = windowOffsetX + contentRect.left + splitRightPx + rightInsetPx,
                top = windowOffsetY + contentRect.top,
                width = spreadPageWidthPx,
                height = contentRect.height,
            ),
            lineHeightPx = avoidLineHeightPx,
        )
    } else {
        PageAvoidance()
    }
    val rightTopPadPx = pageAvoidance.oddTopLines * avoidLineHeightPx

    LaunchedEffect(prefsLoaded, pageDual, size, hingeLocal, splitLeftPx, splitRightPx, contentRect, density.density, density.fontScale, pageAvoidance) {
        if (!prefsLoaded) return@LaunchedEffect
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (pageDual) {
            viewModel.setViewports(
                dual = true,
                leftWidthPx = splitLeftPx.roundToInt(),
                rightWidthPx = (size.width - splitRightPx).roundToInt(),
                heightPx = size.height,
                density = density.density,
                scaledDensity = density.density * density.fontScale,
                avoidance = pageAvoidance,
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

    val battery by rememberBatteryPercent()
    val time by rememberClock()

    fun clearSelection() {
        selection = null
        noteDraft = null
    }

    fun selectionEnd(sel: SelectionUi): Long =
        if (sel.end > sel.start) sel.end else sel.start + 1

    var autoScrollY by remember { mutableStateOf(0f) }

    // 版式几何变化：进行中的覆盖滑动动画作废，防止新旧版式叠画
    LaunchedEffect(currentGeom) {
        animSpread = null
    }

    fun turn(forward: Boolean) {
        clearSelection()
        scope.launch {
            if (animSpread != null) return@launch
            val target = viewModel.adjacentSpread(forward) ?: return@launch
            if (rawMode == PageTurnMode.NONE || size.width <= 0) {
                viewModel.showSpread(target, countCharsRead = true)
                return@launch
            }
            animSpread = target
            animX.snapTo(if (forward) size.width.toFloat() else -size.width.toFloat())
            animX.animateTo(0f, tween(220))
            viewModel.showSpread(target, countCharsRead = true)
            animSpread = null
        }
    }

    // 点按/自动翻页等长驻协程不会随重组重建，直接捕获 turn 会沿用切换前的旧
    // 翻页模式（表现为"切了动画不生效"）；一律经此引用调用，读到最新闭包
    val latestTurn by rememberUpdatedState<(Boolean) -> Unit> { forward ->
        turn(forward)
    }

    // 系统栏统一策略：任何转场期间（进/出阅读、去往设置）系统栏保持可见且不变，
    // 对侧页面（书架/设置）在转场每一帧拿到的 inset 都是最终值——inset 落地是异步的，
    // 若在转场中途才切换系统栏，对侧会先按 inset=0 布局再突变（上/下/横向跳变）。
    // 仅当阅读页完全站稳（转场结束）且菜单关闭时才隐藏系统栏进入沉浸阅读。
    val readerTransition = animatedVisibilityScope?.transition
    val readerExiting = readerTransition != null &&
        readerTransition.targetState != EnterExitState.Visible
    val readerSettled = readerTransition == null ||
        (readerTransition.currentState == EnterExitState.Visible &&
            readerTransition.targetState == EnterExitState.Visible &&
            !readerTransition.isRunning)

    val localView = LocalView.current

    // 进阅读页即记一次"系统栏可见"的外壳 inset：此刻系统栏一定可见（转场期间不隐藏），
    // 比"沉浸前一刻"更早、更稳（避免厂商提前派发沉浸期才有的 displayCutout inset）
    DisposableEffect(localView) {
        ShellInsets.rememberVisibleReference(localView)
        ReturnTrace.log(
            "reader enter: reference=${ShellInsets.visibleReference()} " +
                "systemBars=${ShellInsets.systemBars(localView)} cutout=${ShellInsets.cutout(localView)}",
        )
        onDispose { }
    }

    // 离开阅读页（返回书架/去设置）：先把系统栏恢复常驻，等外壳 inset 落地并连续两帧稳定
    // 再导航，目标页首帧即最终布局。细节见 rememberReaderExit 的注释。
    val exit = rememberReaderExit()

    BackHandler { exit.leaveTo(onBack) }

    SystemBarEffects(
        menuVisible = menuVisible || readerExiting || !readerSettled || exit.requested,
        keepScreenOn = prefs.keepScreenOn,
    )
    BrightnessEffect(prefs.readerBrightness)

    var foreground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> foreground = false
                Lifecycle.Event.ON_STOP -> viewModel.flushReadingSessionNow()
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

    // 定时自动翻页：到点事件走正常翻页动画；菜单/选择/动画进行中丢弃该次事件
    LaunchedEffect(Unit) {
        viewModel.autoPageTurns.collect { forward ->
            if (!menuVisible && selection == null && animSpread == null) {
                latestTurn(forward)
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val scrollListState = rememberLazyListState()
    var brightnessHint by remember { mutableStateOf<Float?>(null) }

    fun scrollByScreen(direction: Int) {
        scope.launch {
            val info = scrollListState.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (viewport > 0f) scrollListState.animateScrollBy(viewport * 0.9f * direction)
        }
    }

    LaunchedEffect(uiState.spread) {
        autoScrollY = 0f
        if (uiState.spread != null) {
            // 等下一帧真正绘制后再放行 VM 的后台重活（全书分页、整页位图预渲染）
            withFrameNanos { }
            viewModel.noteFirstFrameRendered()
        }
    }
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
                latestTurn(true)
            }
        }
    }

    val innerPadPx = with(density) { INNER_SPINE_PAD.toPx() }
    val spineOverlayPx = with(density) { SPINE_OVERLAY_WIDTH.toPx() }
    val leftDp = with(density) { splitLeftPx.toDp() }
    val hingeDp = with(density) { (splitRightPx - splitLeftPx).toDp() }
    val rightDp = with(density) { (size.width - splitRightPx).toDp() }

    // 触摸点 → (行几何, 页内局部坐标)；dual 时按铰链分区，局部坐标扣页偏移
    fun hitBoxes(
        offset: Offset,
    ): Triple<List<com.llzx373.foldreader.core.reader.LineBox>, Float, Float>? {
        if (size.width <= 0) return null
        val relX = offset.x - contentRect.left
        val relY = offset.y - contentRect.top + autoScrollY
        val leftPageRight = splitLeftPx - contentRect.left
        val rightPageX = splitRightPx - contentRect.left
        val isLeft: Boolean
        var localX = relX
        when {
            !spreadDual -> isLeft = true
            relX < leftPageRight -> {
                isLeft = true
                localX = relX - leftInsetPx
            }
            relX > rightPageX -> {
                isLeft = false
                localX = relX - rightPageX - rightInsetPx
            }
            else -> return null // 铰链区
        }
        val boxes = (if (isLeft) leftLineBoxes.value else rightLineBoxes.value) ?: return null
        return Triple(boxes, localX, relY)
    }

    // 触摸点 → 字符光标位
    fun hitCaret(offset: Offset): Long? = hitBoxes(offset)?.let { (boxes, x, y) ->
        com.llzx373.foldreader.core.reader.caretAt(boxes, x, y)
    }

    // 触摸点 → 链接命中（LINK/NOTEREF span）
    fun hitLink(offset: Offset): LinkHit? = hitBoxes(offset)?.let { (boxes, x, y) ->
        com.llzx373.foldreader.core.reader.linkHitAt(boxes, x, y)
    }

    fun spansFor(page: com.llzx373.foldreader.core.reader.Page?): List<TextRangeSpan> {
        if (page == null) return emptyList()
        val spans = annotationIndex.overlapping(page.charStart, page.charEnd).map { ann ->
            TextRangeSpan(
                ann.startCharOffset,
                ann.endCharOffset,
                Color(ann.color.toInt()),
                underline = ann.style ==
                    com.llzx373.foldreader.core.data.db.AnnotationEntity.STYLE_UNDERLINE,
            )
        }
        val hit = searchHighlight
        return if (hit != null && hit.second > page.charStart && hit.first < page.charEnd) {
            spans + TextRangeSpan(hit.first, hit.second, colors.accent.copy(alpha = 0.5f))
        } else {
            spans
        }
    }

    // 系统内存紧张时清空插图位图缓存（未命中的图片行先画占位灰框再按需解码）
    DisposableEffect(viewModel) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = viewModel.clearImageBitmaps()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    viewModel.clearImageBitmaps()
                }
            }
        }
        val appContext = context.applicationContext
        appContext.registerComponentCallbacks(callbacks)
        onDispose { appContext.unregisterComponentCallbacks(callbacks) }
    }

    // 选区拖动越出内容区边缘时向对应方向瞬时翻页，端点落到新页边界，可连续跨多页
    var lastEdgeTurnAt by remember { mutableLongStateOf(0L) }
    val selectionEdgeMarginPx = with(density) { 8.dp.toPx() }

    fun selectionEdgeTurn(forward: Boolean, isStartHandle: Boolean?) {
        val now = System.currentTimeMillis()
        if (now - lastEdgeTurnAt < 350L) return
        lastEdgeTurnAt = now
        scope.launch {
            val target = viewModel.adjacentSpread(forward) ?: return@launch
            viewModel.showSpread(target)
            val boundary = if (forward) {
                (target.right ?: target.left).charEnd
            } else {
                target.left.charStart
            }
            selection = selection?.let { sel ->
                if (isStartHandle == true) sel.copy(anchor = boundary) else sel.copy(caret = boundary)
            }
        }
    }

    /** 选区拖动统一入口：命中内容区 → 更新端点；越出边缘 → 触发跨页翻页。 */
    fun selectionDragTo(point: Offset, isStartHandle: Boolean?) {
        val edge = selectionEdgeAt(
            point.x, point.y,
            contentRect.left, contentRect.top,
            contentRect.left + contentRect.width, contentRect.top + contentRect.height,
            selectionEdgeMarginPx,
        )
        if (edge != SelectionEdge.NONE) {
            selectionEdgeTurn(forward = edge == SelectionEdge.NEXT, isStartHandle = isStartHandle)
            return
        }
        val caret = hitCaret(point) ?: return
        selection = selection?.let { sel ->
            if (isStartHandle == true) sel.copy(anchor = caret) else sel.copy(caret = caret)
        }
    }

    // 普通 HashMap 而非 snapshot state：只在手势命中里读，绘制阶段写 state 会额外调度帧。
    val scrollLineBoxes = remember {
        HashMap<Long, List<com.llzx373.foldreader.core.reader.LineBox>>()
    }

    // 滚动模式：内容区坐标 → (行几何, 页内局部坐标)（跨 LazyColumn 项；双栏按列分区）
    fun scrollBoxesAt(
        relX: Float,
        relY: Float,
    ): Triple<List<com.llzx373.foldreader.core.reader.LineBox>, Float, Float>? {
        val info = scrollListState.layoutInfo
        val item = info.visibleItemsInfo
            .firstOrNull { relY >= it.offset && relY < it.offset + it.size } ?: return null
        val localY = relY - item.offset
        val pages = viewModel.scrollPages
        if (!scrollDual) {
            val page = pages.getOrNull(item.index) ?: return null
            val boxes = scrollLineBoxes[page.charStart] ?: return null
            return Triple(boxes, relX, localY)
        }
        val leftDpPx = with(density) { leftDp.toPx() }
        val hingePx = with(density) { hingeDp.toPx() }
        val rightDpPx = with(density) { rightDp.toPx() }
        val leftPage = pages.getOrNull(item.index * 2) ?: return null
        val rightPage = pages.getOrNull(item.index * 2 + 1)
        return when {
            relX < leftDpPx -> {
                val boxes = scrollLineBoxes[leftPage.charStart] ?: return null
                val inset = (leftDpPx - spreadPageWidthPx).coerceAtLeast(0f) / 2f
                Triple(boxes, relX - inset, localY)
            }
            rightPage != null && relX > leftDpPx + hingePx -> {
                val boxes = scrollLineBoxes[rightPage.charStart] ?: return null
                val inset = (rightDpPx - spreadPageWidthPx).coerceAtLeast(0f) / 2f
                Triple(boxes, relX - leftDpPx - hingePx - inset, localY)
            }
            else -> null
        }
    }

    // 滚动模式：内容区坐标 → 字符光标位
    fun scrollCaretAt(relX: Float, relY: Float): Long? = scrollBoxesAt(relX, relY)
        ?.let { (boxes, x, y) -> com.llzx373.foldreader.core.reader.caretAt(boxes, x, y) }

    // 滚动模式：内容区坐标 → 链接命中
    fun scrollLinkHitAt(relX: Float, relY: Float): LinkHit? = scrollBoxesAt(relX, relY)
        ?.let { (boxes, x, y) -> com.llzx373.foldreader.core.reader.linkHitAt(boxes, x, y) }

    // 链接点按消费：内部跳转 / 外部浏览器 / 脚注弹注。
    // 返回 false = 不消费（锚点不可解析等），点按继续走划线查看/翻页热区。
    fun consumeLink(hit: LinkHit?): Boolean {
        when (hit) {
            null -> return false
            is LinkHit.Internal -> scope.launch {
                viewModel.seekToOffset(hit.targetOffset)
                if (scrollMode) viewModel.enterScrollMode()
            }
            is LinkHit.External -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hit.url)))
            }
            is LinkHit.Note -> {
                // 锚点越界（注释在 linear="no" 等未收录部分）：不消费，按普通点按处理
                if (hit.targetOffset < 0 || hit.targetOffset >= uiState.totalChars) return false
                notePopupTarget = hit.targetOffset
            }
        }
        return true
    }

    /** 中间点击区可分配的动作。翻页档是**逻辑**上一页/下一页，与阅读方向无关（标签是显式的）。 */
    val runTapAction: (TapAction) -> Unit = { action ->
        when (action) {
            TapAction.TOGGLE_MENU -> menuVisible = !menuVisible
            TapAction.PREVIOUS_PAGE -> latestTurn(false)
            TapAction.NEXT_PAGE -> latestTurn(true)
            // 双页时锚左页，与页式格式「锚在跨页首页」的口径一致
            TapAction.TOGGLE_BOOKMARK -> viewModel.toggleBookmark(leftPage = true)
            // 文本阅读器没有页面缩放，这一档对它不生效（中间点击层也不会因此挂载）
            TapAction.TOGGLE_ZOOM -> Unit
            TapAction.NONE -> Unit
        }
    }

    /**
     * 点击处理。
     *
     * 根手势层与中间点击层共用它：中间区那层要能把双击传进来，别的分支必须一字不差，
     * 否则「配了双击之后点击行为变了」会变成很难查的差异。链接/划线的优先级也在这里，
     * 所以中间区的双击不会绕过它们——点在链接上仍然是跳转。
     */
    val handleTap: (Offset, Boolean) -> Unit = tap@{ offset, isDouble ->
        viewModel.noteManualInteraction()
        if (selection != null) {
            clearSelection()
            return@tap
        }
        if (menuVisible) {
            menuVisible = false
            return@tap
        }
        if (scrollMode) {
            val relX = offset.x - contentRect.left
            val relY = offset.y - contentRect.top
            // 链接/脚注引用点按优先（命中即消费，不弹菜单）
            if (consumeLink(scrollLinkHitAt(relX, relY))) return@tap
            val caret = scrollCaretAt(relX, relY)
            if (caret != null) {
                val ann = annotations.firstOrNull {
                    caret >= it.startCharOffset && caret < it.endCharOffset
                }
                if (ann != null) {
                    editingAnnotation = ann
                    return@tap
                }
            }
            menuVisible = true
            return@tap
        }
        // 链接/脚注引用点按优先（先于划线查看与翻页热区）
        if (consumeLink(hitLink(offset))) return@tap
        // 点击已有划线 → 查看/编辑（先于翻页热区）
        val caret = hitCaret(offset)
        if (caret != null) {
            val ann = annotations.firstOrNull {
                caret >= it.startCharOffset && caret < it.endCharOffset
            }
            if (ann != null) {
                editingAnnotation = ann
                return@tap
            }
        }
        when (
            tapZoneOf(
                offset.x,
                size.width.toFloat(),
                prefs.pageTurnHotspotRatio,
                y = offset.y,
                heightPx = size.height.toFloat(),
            )
        ) {
            TapZone.PREVIOUS -> latestTurn(false)
            TapZone.NEXT -> latestTurn(true)
            TapZone.MIDDLE -> runTapAction(
                resolveMiddleTap(prefs.middleTapAction, prefs.middleDoubleTapAction, isDouble),
            )
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
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        when (volumeKeyDispatch(volumeUp = true, scrollMode = scrollMode)) {
                            VolumeKeyDispatch.SCROLL_BACK -> scrollByScreen(-1)
                            else -> turn(false)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        when (volumeKeyDispatch(volumeUp = false, scrollMode = scrollMode)) {
                            VolumeKeyDispatch.SCROLL_FORTH -> scrollByScreen(1)
                            else -> turn(true)
                        }
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(prefs.pageTurnHotspotRatio, scrollMode) {
                detectTapGestures { offset -> handleTap(offset, false) }
            }
            .pointerInput(scrollMode, dual) {
                // 长按进入选择模式，拖动扩展选区，越出页边缘自动翻页继续选择
                if (scrollMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (menuVisible) return@detectDragGesturesAfterLongPress
                        val caret = hitCaret(offset) ?: return@detectDragGesturesAfterLongPress
                        selection = SelectionUi(caret, caret, dragging = true)
                    },
                    onDrag = { change, _ ->
                        if (selection == null) return@detectDragGesturesAfterLongPress
                        change.consume()
                        selectionDragTo(change.position, isStartHandle = null)
                    },
                    onDragEnd = {
                        selection = selection?.copy(dragging = false)
                    },
                    onDragCancel = { selection = null },
                )
            }
            .pointerInput(scrollMode, prefs.swipeGestureEnabled, selection != null) {
                if (scrollMode || !prefs.swipeGestureEnabled || selection != null) return@pointerInput
                var dragStartX = 0f
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragStartX = offset.x
                        dragged = 0f
                    },
                    onHorizontalDrag = { change, _ ->
                        dragged = change.position.x - dragStartX
                    },
                    onDragEnd = {
                        val threshold = size.width * 0.15f
                        if (dragged < -threshold) {
                            viewModel.noteManualInteraction()
                            latestTurn(true)
                        } else if (dragged > threshold) {
                            viewModel.noteManualInteraction()
                            latestTurn(false)
                        }
                        dragged = 0f
                    },
                )
            }
            .pointerInput(scrollMode, prefs.brightnessGestureEnabled, selection != null) {
                if (scrollMode || !prefs.brightnessGestureEnabled || selection != null) {
                    return@pointerInput
                }
                var active = false
                var current = 0.5f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        active = !menuVisible && offset.x <= size.width / 3f
                        current = if (prefs.readerBrightness >= 0f) prefs.readerBrightness else 0.5f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!active) return@detectVerticalDragGestures
                        change.consume()
                        current = (current - dragAmount / size.height).coerceIn(0.05f, 1f)
                        brightnessHint = current
                        viewModel.setReaderBrightness(current)
                    },
                    onDragEnd = {
                        if (active) {
                            scope.launch {
                                delay(600L)
                                brightnessHint = null
                            }
                        }
                        active = false
                    },
                    onDragCancel = {
                        active = false
                        brightnessHint = null
                    },
                )
            },
    ) {
        when {
            uiState.loading -> {
                if (sharedTransitionScope == null || animatedVisibilityScope == null) {
                    LoadingIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = colors.accent,
                    )
                }
            }
            uiState.error != null -> EmptyState(
                title = "无法打开书籍",
                description = uiState.error,
                actionLabel = "重试",
                onAction = viewModel::retry,
                secondaryActionLabel = "返回书架",
                onSecondaryAction = { exit.leaveTo(onBack) },
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Box(
                modifier = Modifier
                    .offset { IntOffset(contentRect.left.roundToInt(), contentRect.top.roundToInt()) }
                    .width(with(density) { contentRect.width.toDp() })
                    .height(with(density) { contentRect.height.toDp() })
                    .clipToBounds(),
            ) {
                if (scrollMode && geomReady) {
                    ScrollContent(
                        viewModel = viewModel,
                        listState = scrollListState,
                        colors = colors,
                        pageHeight = contentRect.height.roundToInt(),
                        dualColumns = scrollDual,
                        leftDp = leftDp,
                        hingeDp = hingeDp,
                        rightDp = rightDp,
                        pageWidthDp = pageWidthDp,
                        selection = selection,
                        onSelectionChange = { selection = it },
                        caretAtContent = ::scrollCaretAt,
                        lineBoxes = scrollLineBoxes,
                        selectionColor = colors.accent.copy(alpha = 0.32f),
                    )
                } else {
                    val spread = uiState.spread
                    if (spread != null && geomReady) {
                        SpreadContent(
                            spread = spread,
                            config = uiState.layoutConfig,
                            colors = colors,
                            dual = uiState.dualPage,
                            leftDp = leftDp,
                            hingeDp = hingeDp,
                            rightDp = rightDp,
                            innerPadPx = innerPadPx,
                            pageWidthDp = pageWidthDp,
                            rightTopPadPx = rightTopPadPx,
                            imageProvider = viewModel.imageProvider,
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
                                pageWidthDp = pageWidthDp,
                                rightTopPadPx = rightTopPadPx,
                                imageProvider = viewModel.imageProvider,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { translationX = animX.value },
                            )
                        }
                        if (spreadDual) {
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

        // 中间点击层：只有配了本阅读器能执行的双击动作时才铺。
        // 它只盖住「判定为中间区」的那块矩形，左右翻页与底边翻页条都保持抬手即响应；
        // 单击分支与下层完全一致，链接/划线的优先级不受影响。
        if (uiState.error == null && !uiState.loading) {
            val doubleAction = prefs.middleDoubleTapAction
            if (supportsTapAction(doubleAction, paged = false)) {
                MiddleTapLayer(
                    hotspotRatio = prefs.pageTurnHotspotRatio,
                    doubleTapAction = doubleAction,
                    onTap = { offset, isDouble -> handleTap(offset, isDouble) },
                )
            }
        }

        val sharedScope = sharedTransitionScope
        val animScope = animatedVisibilityScope
        if (sharedScope != null && animScope != null) {
            // 封面共享元素只用于"进书"：加载时显示并承接书架飞入；加载完成后
            // 淡出并摘掉共享注册。退出阅读时封面保持不可见、不参与共享过渡，
            // 书架封面随 popEnter 正常淡入——避免退出时大封面闪现再缩小的跳变
            val coverAlpha by animateFloatAsState(
                targetValue = if (uiState.loading) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "coverAlpha",
            )
            with(sharedScope) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BookCover(
                        title = coverTitle ?: uiState.bookTitle,
                        modifier = Modifier
                            .then(
                                if (uiState.loading) {
                                    Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState(key = "cover-$bookId"),
                                        animatedVisibilityScope = animScope,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .width(160.dp)
                            .graphicsLayer { alpha = coverAlpha },
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(
                        modifier = Modifier.height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (uiState.loading) {
                            LoadingIndicator(color = colors.accent)
                        }
                    }
                }
            }
        }

        // 选择手柄 + 选区操作条（菜单打开时隐藏；滚动模式略去手柄，仅操作条）
        val activeSelection = selection
        if (activeSelection != null && !menuVisible && !uiState.loading && uiState.error == null) {
            val spread = uiState.spread
            if (!scrollMode && spread != null) {
                val selEnd = selectionEnd(activeSelection)
                listOf(true to activeSelection.start, false to selEnd).forEach { (isStart, offset) ->
                    val left = spread.left
                    val right = spread.right
                    val onLeft = offset >= left.charStart &&
                        (offset < left.charEnd || (right == null && offset == left.charEnd))
                    val onRight = right != null && offset >= right.charStart && offset <= right.charEnd
                    val useRight = if (isStart) !onLeft && onRight else onRight
                    val visible = if (useRight) onRight else onLeft
                    val boxes = if (useRight) rightLineBoxes.value else leftLineBoxes.value
                    if (visible && boxes != null) {
                        val originX = if (spreadDual && useRight) {
                            splitRightPx + rightInsetPx
                        } else if (spreadDual) {
                            contentRect.left + leftInsetPx
                        } else {
                            contentRect.left
                        }
                        SelectionHandle(
                            boxes = boxes,
                            offset = offset,
                            originXPx = originX,
                            originYPx = contentRect.top,
                            scrollYPx = autoScrollY,
                            accent = colors.accent,
                            onDrag = { pageLocal ->
                                selectionDragTo(
                                    Offset(
                                        originX + pageLocal.x,
                                        contentRect.top + pageLocal.y - autoScrollY,
                                    ),
                                    isStartHandle = isStart,
                                )
                            },
                        )
                    }
                }
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
                onBookmark = {
                    viewModel.toggleBookmarkAt(
                        activeSelection.start,
                        selectionEnd(activeSelection),
                    )
                    clearSelection()
                },
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
            // 在可见性判断内订阅：不可见时不读，也就不会被阅读位置变化牵连
            val position by viewModel.readingPosition.collectAsState()
            TabletopDivider(
                colors = colors,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, tabletop.content.height.roundToInt()) },
            )
            TabletopPanel(
                prefs = prefs,
                progressFraction = position.progressFraction,
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
            ReaderCornerChrome(
                viewModel = viewModel,
                bookTitle = uiState.bookTitle,
                spreadDual = spreadDual,
                hasRightPage = uiState.spread?.right != null,
                totalPages = uiState.totalPages,
                leftDp = leftDp,
                rightDp = rightDp,
                colors = colors,
                prefs = prefs,
                battery = battery,
                time = time,
            )
        }

        brightnessHint?.let { value ->
            Text(
                text = "亮度 ${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = colors.text,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(colors.background.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (menuVisible) {
            val position by viewModel.readingPosition.collectAsState()
            ReaderTopBar(
                bookTitle = uiState.bookTitle,
                chapterTitle = position.chapterTitle,
                colors = colors,
                onBack = { exit.leaveTo(onBack) },
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
                progressFraction = position.progressFraction,
                displayPageTurnMode = rawMode,
                chapterProgress = chapterProgressText(
                    index = position.chapterIndex,
                    count = position.chapterCount,
                    inChapter = position.inChapterFraction,
                ),
                colors = colors,
                onSeekFraction = { f ->
                    scope.launch {
                        viewModel.seekToFraction(f)
                        if (scrollMode) viewModel.enterScrollMode()
                    }
                },
                onOpenCatalog = { catalogVisible = true },
                onSwitchToPagedMode = onSwitchToPagedMode,
                onOpenAnnotations = { annotationsVisible = true },
                onCyclePageTurnMode = {
                    viewModel.setPageTurnMode(nextPageTurnMode(rawMode))
                },
                onSelectPageTurnMode = viewModel::setPageTurnMode,
                onCycleDualPageMode = {
                    viewModel.setDualPageMode(nextDualPageMode(prefs.dualPageMode))
                },
                onSetBrightness = viewModel::setReaderBrightness,
                onSetFontSize = viewModel::setFontSize,
                onSetLineSpacing = viewModel::setLineSpacing,
                onSetMarginLevel = viewModel::setMarginLevel,
                onSetMaxLineChars = viewModel::setMaxLineChars,
                onSetParagraphSpacing = viewModel::setParagraphSpacingEm,
                onSetLetterSpacing = viewModel::setLetterSpacingEm,
                onSelectTheme = viewModel::setTheme,
                onPickCustomBackground = { argb ->
                    viewModel.setCustomColors(argb, prefs.customTextArgb)
                },
                onPickCustomText = { argb ->
                    viewModel.setCustomColors(prefs.customBackgroundArgb, argb)
                },
                autoPageStatus = autoPageStatus,
                onToggleAutoIndent = viewModel::setAutoIndentEnabled,
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
                onOpenSettings = { exit.leaveTo(onOpenSettings) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        if (catalogVisible) {
            val position by viewModel.readingPosition.collectAsState()
            ChapterListDialog(
                chapters = viewModel.chapterList(),
                currentIndex = position.chapterIndex,
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
                onSave = { c, n, style ->
                    viewModel.addAnnotation(draft.start, draftEnd, c, n, style)
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
                initialStyle = ann.style,
                shifted = ann.id in shiftedAnnotationIds,
                colors = colors,
                onSave = { c, n, style ->
                    viewModel.updateAnnotation(ann, c, n, style)
                    editingAnnotation = null
                },
                onDelete = {
                    viewModel.deleteAnnotation(ann.id)
                    editingAnnotation = null
                },
                onDismiss = { editingAnnotation = null },
            )
        }

        // 脚注弹注：noteref 点按后展示目标处内容；目标读不到内容时静默降级为直接跳转
        notePopupTarget?.let { target ->
            val excerpt by produceState<String?>(initialValue = null, target) {
                val loaded = viewModel.noteExcerptAt(target)
                if (loaded == null) {
                    notePopupTarget = null
                    viewModel.seekToOffset(target)
                    if (scrollMode) viewModel.enterScrollMode()
                } else {
                    value = loaded
                }
            }
            val noteText = excerpt
            if (noteText != null) {
                AlertDialog(
                    onDismissRequest = { notePopupTarget = null },
                    title = { Text("注释") },
                    text = {
                        Text(
                            text = noteText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            notePopupTarget = null
                            scope.launch {
                                viewModel.seekToOffset(target)
                                if (scrollMode) viewModel.enterScrollMode()
                            }
                        }) { Text("跳转到注释") }
                    },
                    dismissButton = {
                        TextButton(onClick = { notePopupTarget = null }) { Text("关闭") }
                    },
                )
            }
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
    pageWidthDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    rightTopPadPx: Float = 0f,
    leftHighlights: List<TextRangeSpan> = emptyList(),
    rightHighlights: List<TextRangeSpan> = emptyList(),
    selection: SelectionUi? = null,
    selectionColor: Color = Color.Unspecified,
    onLeftGeometry: (List<com.llzx373.foldreader.core.reader.LineBox>) -> Unit = {},
    onRightGeometry: (List<com.llzx373.foldreader.core.reader.LineBox>) -> Unit = {},
    imageProvider: ((String) -> android.graphics.Bitmap?)? = null,
) {
    val selectionSpanFor: (com.llzx373.foldreader.core.reader.Page?) -> TextRangeSpan? = { page ->
        val sel = selection
        if (sel == null || page == null) {
            null
        } else {
            val end = if (sel.end > sel.start) sel.end else sel.start + 1
            intersectRange(sel.start, end, page.charStart, page.charEnd)?.let {
                TextRangeSpan(it.first, it.last + 1, selectionColor)
            }
        }
    }
    if (!dual) {
        PageView(
            page = spread.left,
            config = config,
            colors = colors,
            // 实心底色：COVER 滑入/滑出叠层时不得透出下层页面
            modifier = modifier.background(colors.background),
            highlights = leftHighlights,
            selection = selectionSpanFor(spread.left),
            onGeometry = onLeftGeometry,
            imageProvider = imageProvider,
        )
        return
    }
    Row(modifier = modifier.background(colors.background)) {
        Box(
            modifier = Modifier.width(leftDp).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            PageView(
                page = spread.left,
                config = config,
                colors = colors,
                modifier = Modifier.width(pageWidthDp).fillMaxHeight(),
                innerPaddingPx = innerPadPx,
                innerOnRight = true,
                highlights = leftHighlights,
                selection = selectionSpanFor(spread.left),
                onGeometry = onLeftGeometry,
                imageProvider = imageProvider,
            )
        }
        Box(modifier = Modifier.width(hingeDp).fillMaxHeight())
        Box(
            modifier = Modifier.width(rightDp).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            spread.right?.let { right ->
                PageView(
                    page = right,
                    config = config,
                    colors = colors,
                    modifier = Modifier.width(pageWidthDp).fillMaxHeight(),
                    innerPaddingPx = innerPadPx,
                    innerOnRight = false,
                    extraTopPadPx = rightTopPadPx,
                    highlights = rightHighlights,
                    selection = selectionSpanFor(right),
                    onGeometry = onRightGeometry,
                    imageProvider = imageProvider,
                )
            }
        }
    }
}

/**
 * 常驻页眉页脚：页眉章节名/书名、页脚页码/纸书页/进度/电量/时间。
 *
 * 单独成 Composable 是为了在这里订阅 [ReaderViewModel.readingPosition]：
 * 阅读位置每跨一页都会变，若在 ReaderScreen 顶层读取，整棵阅读树都会跟着重组。
 * 其余参数都是翻页/改版式才会变的值，正常滚动期间保持不变。
 */
@Composable
private fun BoxScope.ReaderCornerChrome(
    viewModel: ReaderViewModel,
    bookTitle: String,
    spreadDual: Boolean,
    hasRightPage: Boolean,
    totalPages: Int,
    leftDp: Dp,
    rightDp: Dp,
    colors: ReaderColors,
    prefs: ReadingPreferences,
    battery: Int,
    time: String,
) {
    val position by viewModel.readingPosition.collectAsState()

    val pageNumberLabel = if (prefs.showPageNumber) {
        pageNumberText(
            leftPage = position.pageNumber,
            hasRightPage = spreadDual && hasRightPage,
            totalPages = totalPages,
        )
    } else {
        null
    }
    val progressText = if (prefs.showPageProgress) formatPercent(position.progressFraction) else null
    val paperPageText = if (prefs.showPageProgress) {
        position.paperPageLabel?.let { "纸书 P.$it" }
    } else {
        null
    }
    val leftFooter = listOfNotNull(pageNumberLabel, paperPageText, progressText)
        .joinToString("  ")
        .ifEmpty { null }
    val rightFooter = listOfNotNull(
        if (prefs.showBattery && battery >= 0) "电量 $battery%" else null,
        if (prefs.showTime) time else null,
    ).joinToString("  ")

    if (spreadDual) {
        if (prefs.showChapterTitle) {
            CornerLabel(
                text = position.chapterTitle,
                colors = colors,
                endAligned = false,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .width(leftDp),
            )
        }
        CornerLabel(
            text = bookTitle,
            colors = colors,
            endAligned = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .width(rightDp),
        )
        if (leftFooter != null) {
            CornerLabel(
                text = leftFooter,
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
                chapterTitle = position.chapterTitle,
                colors = colors,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
        }
        ReaderFooter(
            leftText = leftFooter,
            batteryText = if (prefs.showBattery && battery >= 0) "电量 $battery%" else null,
            timeText = if (prefs.showTime) time else null,
            colors = colors,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
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
    listState: androidx.compose.foundation.lazy.LazyListState,
    colors: ReaderColors,
    pageHeight: Int,
    dualColumns: Boolean,
    leftDp: androidx.compose.ui.unit.Dp,
    hingeDp: androidx.compose.ui.unit.Dp,
    rightDp: androidx.compose.ui.unit.Dp,
    pageWidthDp: androidx.compose.ui.unit.Dp,
    selection: SelectionUi?,
    onSelectionChange: (SelectionUi?) -> Unit,
    caretAtContent: (Float, Float) -> Long?,
    lineBoxes: MutableMap<Long, List<com.llzx373.foldreader.core.reader.LineBox>>,
    selectionColor: Color,
) {
    val uiState by viewModel.uiState.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val annotationIndex = remember(annotations) { AnnotationIndex(annotations) }
    val searchHighlight by viewModel.searchHighlight.collectAsState()
    var extending by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    fun spansFor(page: com.llzx373.foldreader.core.reader.Page): List<TextRangeSpan> {
        val spans = annotationIndex.overlapping(page.charStart, page.charEnd).mapTo(ArrayList(4)) { ann ->
            TextRangeSpan(
                ann.startCharOffset,
                ann.endCharOffset,
                Color(ann.color.toInt()),
                underline = ann.style ==
                    com.llzx373.foldreader.core.data.db.AnnotationEntity.STYLE_UNDERLINE,
            )
        }
        val sel = selection
        if (sel != null) {
            val selEnd = if (sel.end > sel.start) sel.end else sel.start + 1
            intersectRange(sel.start, selEnd, page.charStart, page.charEnd)?.let {
                spans += TextRangeSpan(it.first, it.last + 1, selectionColor)
            }
        }
        val hit = searchHighlight
        return if (hit != null && hit.second > page.charStart && hit.first < page.charEnd) {
            spans + TextRangeSpan(hit.first, hit.second, colors.accent.copy(alpha = 0.5f))
        } else {
            spans
        }
    }

    LaunchedEffect(listState, dualColumns) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val pages = viewModel.scrollPages
            val lastIndex = if (dualColumns) (pages.size + 1) / 2 - 1 else pages.lastIndex
            val page = (if (dualColumns) pages.getOrNull(index * 2) else pages.getOrNull(index))
                ?: return@collect
            viewModel.scrollAnchorTo(page.charStart)
            if (!extending && (index < 2 || index > lastIndex - 2)) {
                extending = true
                try {
                    if (index < 2) viewModel.scrollExtend(forward = false)
                    if (index > lastIndex - 2) viewModel.scrollExtend(forward = true)
                } finally {
                    extending = false
                }
            }
        }
    }

    LaunchedEffect(listState) {
        viewModel.autoScrollTicks.collect { delta -> listState.scroll { scrollBy(delta) } }
    }

    // 长按拖动到视口边缘时连续滚动并把光标推进到新露出的项
    val currentSelection = androidx.compose.runtime.rememberUpdatedState(selection)
    var edgeScrollDir by remember { mutableStateOf(0) }
    var dragViewportPoint by remember { mutableStateOf<Offset?>(null) }
    val edgeZonePx = with(density) { 40.dp.toPx() }
    LaunchedEffect(edgeScrollDir) {
        if (edgeScrollDir == 0) return@LaunchedEffect
        while (true) {
            listState.scroll { scrollBy(edgeScrollDir * 16f) }
            val point = dragViewportPoint
            val sel = currentSelection.value
            if (point != null && sel != null) {
                caretAtContent(point.x, point.y)?.let { onSelectionChange(sel.copy(caret = it)) }
            }
            delay(16L)
        }
    }

    fun Modifier.selectionGesture(itemKey: Long, pageViewLeftPx: Float): Modifier {
        val edge = edgeZonePx
        return this.pointerInput(itemKey) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val itemTop = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == itemKey }?.offset?.toFloat()
                        ?: return@detectDragGesturesAfterLongPress
                    val caret = caretAtContent(pageViewLeftPx + offset.x, itemTop + offset.y)
                        ?: return@detectDragGesturesAfterLongPress
                    edgeScrollDir = 0
                    onSelectionChange(SelectionUi(caret, caret, dragging = true))
                },
                onDrag = { change, _ ->
                    val sel = currentSelection.value ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    val itemTop = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == itemKey }?.offset?.toFloat()
                        ?: return@detectDragGesturesAfterLongPress
                    val vx = pageViewLeftPx + change.position.x
                    val vy = itemTop + change.position.y
                    dragViewportPoint = Offset(vx, vy)
                    val info = listState.layoutInfo
                    val viewportH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                    edgeScrollDir = when {
                        vy < info.viewportStartOffset + edge -> -1
                        vy > info.viewportStartOffset + viewportH - edge -> 1
                        else -> 0
                    }
                    caretAtContent(vx, vy)?.let { onSelectionChange(sel.copy(caret = it)) }
                },
                onDragEnd = {
                    edgeScrollDir = 0
                    dragViewportPoint = null
                    onSelectionChange(currentSelection.value?.copy(dragging = false))
                },
                onDragCancel = {
                    edgeScrollDir = 0
                    dragViewportPoint = null
                    onSelectionChange(null)
                },
            )
        }
    }

    val itemHeight = with(density) { pageHeight.toDp() }
    // derivedStateOf：只在 scrollPages 真正变化时重算，避免每次重组都整表 chunked 拷贝一份。
    // 必须放在 LazyColumn 之外——LazyListScope 不是 Composable 作用域，那里不能调 remember。
    val dualRows by remember { derivedStateOf { viewModel.scrollPages.chunked(2) } }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (dualColumns) {
            val leftDpPx = with(density) { leftDp.toPx() }
            val hingePx = with(density) { hingeDp.toPx() }
            val rightDpPx = with(density) { rightDp.toPx() }
            val pageWidthPx = with(density) { pageWidthDp.toPx() }
            items(
                dualRows,
                key = { it.first().charStart },
            ) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                ) {
                    val leftInset = (leftDpPx - pageWidthPx).coerceAtLeast(0f) / 2f
                    val rightInset = (rightDpPx - pageWidthPx).coerceAtLeast(0f) / 2f
                    Box(
                        modifier = Modifier.width(leftDp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PageView(
                            page = row[0],
                            config = uiState.layoutConfig,
                            colors = colors,
                            modifier = Modifier
                                .width(pageWidthDp)
                                .fillMaxHeight()
                                .selectionGesture(row.first().charStart, leftInset),
                            highlights = spansFor(row[0]),
                            onGeometry = { lineBoxes[row[0].charStart] = it },
                            imageProvider = viewModel.imageProvider,
                        )
                    }
                    Box(modifier = Modifier.width(hingeDp).fillMaxHeight())
                    Box(
                        modifier = Modifier.width(rightDp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        row.getOrNull(1)?.let { right ->
                            PageView(
                                page = right,
                                config = uiState.layoutConfig,
                                colors = colors,
                                modifier = Modifier
                                    .width(pageWidthDp)
                                    .fillMaxHeight()
                                    .selectionGesture(
                                        row.first().charStart,
                                        leftDpPx + hingePx + rightInset,
                                    ),
                                highlights = spansFor(right),
                                onGeometry = { lineBoxes[right.charStart] = it },
                                imageProvider = viewModel.imageProvider,
                            )
                        }
                    }
                }
            }
        } else {
            items(viewModel.scrollPages, key = { it.charStart }) { page ->
                PageView(
                    page = page,
                    config = uiState.layoutConfig,
                    colors = colors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .selectionGesture(page.charStart, 0f),
                    highlights = spansFor(page),
                    onGeometry = { lineBoxes[page.charStart] = it },
                    imageProvider = viewModel.imageProvider,
                )
            }
        }
    }
}

private fun batteryPercentOf(intent: Intent?): Int {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
}

@Composable
private fun rememberBatteryPercent(): androidx.compose.runtime.State<Int> {
    val context = LocalContext.current
    val percent = remember {
        mutableStateOf(
            batteryPercentOf(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))),
        )
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                percent.value = batteryPercentOf(intent)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

@Composable
private fun rememberClock(): androidx.compose.runtime.State<String> {
    val locale = rememberLocale()
    return produceState(initialValue = "", locale) {
        val format = SimpleDateFormat("HH:mm", locale)
        while (true) {
            value = format.format(Date())
            delay(30_000L)
        }
    }
}
