package com.llzx373.foldreader.feature.comic

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.comic.ComicSeriesCandidate
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.data.settings.PdfReadingMode
import com.llzx373.foldreader.core.foldable.FoldableUiState
import com.llzx373.foldreader.feature.bookshelf.BookCover
import com.llzx373.foldreader.feature.reader.AnnotationEditDialog
import com.llzx373.foldreader.feature.reader.AnnotationListDialog
import com.llzx373.foldreader.feature.reader.BookmarkListDialog
import com.llzx373.foldreader.feature.reader.BrightnessEffect
import com.llzx373.foldreader.feature.reader.ChapterListDialog
import com.llzx373.foldreader.feature.reader.MiddleTapLayer
import com.llzx373.foldreader.feature.reader.PageLayoutMode
import com.llzx373.foldreader.feature.reader.SelectionActionBar
import com.llzx373.foldreader.feature.reader.SpineOverlay
import com.llzx373.foldreader.feature.reader.SystemBarEffects
import com.llzx373.foldreader.feature.reader.VolumeKeyDispatch
import com.llzx373.foldreader.feature.reader.annotationColorPalette
import com.llzx373.foldreader.feature.reader.contentRectFor
import com.llzx373.foldreader.feature.reader.dualSplit
import com.llzx373.foldreader.feature.reader.pageLabelOf
import com.llzx373.foldreader.feature.reader.readerColors
import com.llzx373.foldreader.feature.reader.rememberReaderExit
import com.llzx373.foldreader.feature.reader.resolvePageLayoutMode
import com.llzx373.foldreader.feature.reader.resolveMiddleTap
import com.llzx373.foldreader.feature.reader.resolveTabletopLayout
import com.llzx373.foldreader.feature.reader.supportsTapAction
import com.llzx373.foldreader.feature.reader.tapZoneOf
import com.llzx373.foldreader.feature.reader.volumeKeyDispatch
import com.llzx373.foldreader.feature.reader.peel.PeelBitmapFit
import com.llzx373.foldreader.feature.reader.peel.PeelController
import com.llzx373.foldreader.feature.reader.peel.PeelLeaf
import com.llzx373.foldreader.feature.reader.peel.PeelOverlay
import com.llzx373.foldreader.feature.reader.peel.PeelPhase
import com.llzx373.foldreader.feature.reader.peel.activePeelLeaf
import com.llzx373.foldreader.feature.reader.peel.peelCornerFor
import com.llzx373.foldreader.feature.reader.peel.peelFlapExtend
import com.llzx373.foldreader.feature.reader.peel.peelOppositeWidth
import com.llzx373.foldreader.feature.reader.peel.screenToLeafLocal
import com.llzx373.foldreader.ui.EmptyState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val ANIM_MS = 220

/**
 * 漫画阅读页。与文本阅读器同构：全窗口覆盖层里渲染，沉浸、折叠适配、主题、计时全部沿用
 * 同一套共享件（[SystemBarEffects] / [BrightnessEffect] / [rememberReaderExit] / ReaderLogic）。
 */
@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ComicReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    foldableUiState: FoldableUiState,
    initialPage: Int = -1,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    coverTitle: String? = null,
    /** 跳到同系列的其它卷（由导航层替换当前阅读页）。 */
    onOpenBook: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: ComicReaderViewModel = viewModel(
        key = "comic-$bookId",
        factory = ComicReaderViewModel.factory(app.container, bookId, initialPage),
    )
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val prefsLoaded by viewModel.preferencesLoaded.collectAsState()
    val position by viewModel.readingPosition.collectAsState()
    val spreadIndex by viewModel.spreadIndex.collectAsState()
    val features by viewModel.features.collectAsState()
    val outline by viewModel.outline.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val series by viewModel.series.collectAsState()
    val searchState by viewModel.search.collectAsState()
    val colors = readerColors(prefs.themeId, prefs.customBackgroundArgb, prefs.customTextArgb)
    // 日漫从右往左：点左侧是「下一页」，同一对页里低序号页放右边，
    // 横滑与翻页动画一并左右镜像（下一页从左滑入）——三处方向翻译见 ComicLogic 的纯函数，
    // 任何一处单独「不翻」都会让同一个动作在不同输入下方向相反。
    // PDF 没有这个语义，features.rtl 为 false 时整片关闭。
    val rtl = features.rtl && prefs.comicDirection == ComicDirection.RTL
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current

    var menuVisible by remember { mutableStateOf(false) }
    var thumbnailsVisible by remember { mutableStateOf(false) }
    var outlineVisible by remember { mutableStateOf(false) }
    var bookmarksVisible by remember { mutableStateOf(false) }
    var annotationsVisible by remember { mutableStateOf(false) }
    var seriesVisible by remember { mutableStateOf(false) }
    var jumpVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var editingAnnotation by remember { mutableStateOf<AnnotationEntity?>(null) }

    // 同系列的前后卷：直接从状态列表推，避免在组合期调用 ViewModel 函数（那样系列载入后不会重组）
    val seriesIndex = series.indexOfFirst { it.bookId == bookId }
    val prevVolume = if (seriesIndex > 0) series[seriesIndex - 1] else null
    val nextVolume = if (seriesIndex >= 0 && seriesIndex < series.lastIndex) {
        series[seriesIndex + 1]
    } else {
        null
    }
    val seriesPosition = if (seriesIndex >= 0 && series.size > 1) {
        "${seriesIndex + 1}/${series.size}"
    } else {
        null
    }

    /** 打开系列里的某一卷：已入库直接跳；未入库先按需导入，成功后再跳。 */
    val openSeriesEntry: (ComicSeriesCandidate) -> Unit = { candidate ->
        seriesVisible = false
        val existing = candidate.bookId
        if (existing != null) {
            onOpenBook(existing)
        } else {
            scope.launch {
                viewModel.resolveSeriesEntry(candidate)?.let(onOpenBook)
            }
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var windowOffsetX by remember { mutableStateOf(0f) }
    var windowOffsetY by remember { mutableStateOf(0f) }
    var brightnessHint by remember { mutableStateOf<Float?>(null) }

    val layoutMode = resolvePageLayoutMode(
        posture = foldableUiState.posture,
        widthCategory = foldableUiState.widthCategory,
        windowPortrait = foldableUiState.windowPortrait,
        pref = prefs.dualPageMode,
        wideScreenDualPage = prefs.wideScreenDualPage,
    )
    val hingeLocal = foldableUiState.posture.hingeBounds
        ?.takeIf { it.width > 0f || it.height > 0f }
        ?.let {
            Rect(
                it.left - windowOffsetX,
                it.top - windowOffsetY,
                it.right - windowOffsetX,
                it.bottom - windowOffsetY,
            )
        }
    val tabletop = resolveTabletopLayout(
        posture = foldableUiState.posture,
        hingeLocal = hingeLocal,
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
    )
    val scrollMode = prefs.pageTurnMode == PageTurnMode.SCROLL
    // 纵向连续滚动没有"左右两页"可言，双页一律退回单栏
    val dual = layoutMode == PageLayoutMode.DUAL && tabletop == null && !scrollMode
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

    // 解码目标 = 单页实际可用的槽位尺寸；折叠/旋转改变尺寸时重解码
    val decodeTargetW = (
        if (dual) maxOf(splitLeftPx, size.width - splitRightPx) else contentRect.width
        ).roundToInt()
    val decodeTargetH = contentRect.height.roundToInt()
    // 偏好就绪前不回填 dual：否则按默认值推一版给 ViewModel，真实偏好落地时又翻回来
    LaunchedEffect(dual, prefsLoaded) {
        if (prefsLoaded) viewModel.setDualPage(dual)
    }
    LaunchedEffect(decodeTargetW, decodeTargetH) {
        if (decodeTargetW < 64 || decodeTargetH < 64) return@LaunchedEffect
        viewModel.setDecodeTarget(decodeTargetW, decodeTargetH)
    }

    val exit = rememberReaderExit()
    BackHandler { exit.leaveTo(onBack) }

    val readerTransition = animatedVisibilityScope?.transition
    val readerExiting = readerTransition != null &&
        readerTransition.targetState != EnterExitState.Visible
    val readerSettled = readerTransition == null ||
        (readerTransition.currentState == EnterExitState.Visible &&
            readerTransition.targetState == EnterExitState.Visible &&
            !readerTransition.isRunning)

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
    LaunchedEffect(foreground, menuVisible, uiState.loading, uiState.error) {
        viewModel.setReadingActive(
            foreground && !menuVisible && !uiState.loading && uiState.error == null,
        )
    }

    // 内存吃紧时丢掉已解码的页；可见页随即重新解码（否则当前页会一直停在占位上）
    DisposableEffect(viewModel, context) {
        val callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = viewModel.clearImageBitmaps()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) viewModel.clearImageBitmaps()
            }
        }
        context.registerComponentCallbacks(callback)
        onDispose { context.unregisterComponentCallbacks(callback) }
    }

    LaunchedEffect(brightnessHint) {
        if (brightnessHint != null) {
            delay(1200)
            brightnessHint = null
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val scrollListState = rememberLazyListState()

    fun scrollByScreen(direction: Int) {
        scope.launch {
            val info = scrollListState.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (viewport > 0f) scrollListState.animateScrollBy(viewport * 0.9f * direction)
        }
    }

    // 进入滚动模式（或首次加载完成）时落到保存的页；之后滚动位置反过来写进度
    LaunchedEffect(scrollMode, uiState.loading) {
        if (scrollMode && !uiState.loading && uiState.pageCount > 0) {
            scrollListState.scrollToItem(uiState.pageIndex.coerceIn(0, uiState.pageCount - 1))
        }
    }
    LaunchedEffect(scrollMode) {
        if (!scrollMode) return@LaunchedEffect
        snapshotFlow { scrollListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.onScrollAnchor(it) }
    }

    // 覆盖滑动：正在滑入的页组，底下仍画当前页
    val animX = remember { Animatable(0f) }
    var animPages by remember { mutableStateOf<List<Int>?>(null) }
    val peel = remember { PeelController() }
    var peelTarget by remember { mutableStateOf<Int?>(null) }
    var peelCurrentBmp by remember { mutableStateOf<Bitmap?>(null) }
    var peelNextBmp by remember { mutableStateOf<Bitmap?>(null) }
    var peelBackBmp by remember { mutableStateOf<Bitmap?>(null) }
    var peelCurrentFit by remember { mutableStateOf<PeelBitmapFit?>(null) }
    var peelNextFit by remember { mutableStateOf<PeelBitmapFit?>(null) }
    var peelBackFit by remember { mutableStateOf<PeelBitmapFit?>(null) }
    /** true = 这次烘了离屏拷贝，结束时才 recycle；引用解码缓存时绝不能 recycle。 */
    var peelOwnsBitmaps by remember { mutableStateOf(false) }
    var peelGeneration by remember { mutableLongStateOf(0L) }
    var lastTapOffset by remember { mutableStateOf<Offset?>(null) }
    val lastPeelCancelGeom = remember { intArrayOf(-1, -1, -1, -1, -1) }
    /** 掀页开始时锁死的左右叶。覆盖层只用这份，避免配对在动画中途翻转。 */
    var peelLeafSession by remember { mutableStateOf<Pair<PeelLeaf, PeelLeaf?>?>(null) }

    fun recyclePeelBitmaps() {
        if (peelOwnsBitmaps) {
            peelCurrentBmp?.recycle()
            peelNextBmp?.recycle()
            peelBackBmp?.recycle()
        }
        peelOwnsBitmaps = false
        peelCurrentBmp = null
        peelNextBmp = null
        peelBackBmp = null
        peelCurrentFit = null
        peelNextFit = null
        peelBackFit = null
    }

    fun adoptPeelBitmaps(
        current: Bitmap,
        next: Bitmap?,
        back: Bitmap?,
        currentFit: PeelBitmapFit?,
        nextFit: PeelBitmapFit?,
        backFit: PeelBitmapFit?,
        owns: Boolean,
    ) {
        recyclePeelBitmaps()
        peelOwnsBitmaps = owns
        peelCurrentBmp = current
        peelNextBmp = next
        peelBackBmp = back
        peelCurrentFit = currentFit
        peelNextFit = nextFit
        peelBackFit = backFit
    }

    fun finishPeel() {
        peel.reset()
        peelTarget = null
        peelLeafSession = null
        recyclePeelBitmaps()
    }

    LaunchedEffect(
        dual,
        contentRect.width.roundToInt(),
        contentRect.height.roundToInt(),
        splitLeftPx.roundToInt(),
        splitRightPx.roundToInt(),
    ) {
        val dualBit = if (dual) 1 else 0
        val w = contentRect.width.roundToInt()
        val h = contentRect.height.roundToInt()
        val sl = splitLeftPx.roundToInt()
        val sr = splitRightPx.roundToInt()
        val slop = 8
        val prev = lastPeelCancelGeom
        if (prev[0] >= 0 &&
            prev[0] == dualBit &&
            abs(prev[1] - w) <= slop &&
            abs(prev[2] - h) <= slop &&
            abs(prev[3] - sl) <= slop &&
            abs(prev[4] - sr) <= slop
        ) {
            return@LaunchedEffect
        }
        lastPeelCancelGeom[0] = dualBit
        lastPeelCancelGeom[1] = w
        lastPeelCancelGeom[2] = h
        lastPeelCancelGeom[3] = sl
        lastPeelCancelGeom[4] = sr
        animPages = null
        peelGeneration += 1
        finishPeel()
    }

    fun currentPeelLeaves(dualLeaves: Boolean): Pair<PeelLeaf, PeelLeaf?> = comicPeelLeaves(
        dualLeaves = dualLeaves,
        contentWidth = contentRect.width,
        contentHeight = contentRect.height,
        splitLeft = splitLeftPx - contentRect.left,
        splitRight = splitRightPx - contentRect.left,
    )

    fun lockPeelLeaves(currentPages: List<Int>, targetPages: List<Int>): Pair<Boolean, Pair<PeelLeaf, PeelLeaf?>> {
        val targetWide = targetPages.singleOrNull()?.let { viewModel.isWideSpan(it) } == true
        val dualLeaves = dual && uiState.dual && comicPeelUsesDualLeaves(currentPages, targetWide)
        val leaves = currentPeelLeaves(dualLeaves)
        peelLeafSession = leaves
        return dualLeaves to leaves
    }

    suspend fun preparePeelBitmaps(
        peelForward: Boolean,
        currentPages: List<Int>,
        targetPages: List<Int>,
        leaf: PeelLeaf,
        dualLeaves: Boolean,
    ): Boolean {
        val ids = if (dualLeaves) {
            comicPeelPageIds(peelForward, currentPages, targetPages, rtl)
        } else {
            null
        }
        val needed = buildList {
            addAll(currentPages)
            addAll(targetPages)
            ids?.current?.let { add(it) }
            ids?.next?.let { add(it) }
            ids?.back?.let { add(it) }
        }.distinct()
        kotlinx.coroutines.coroutineScope {
            for (index in needed) {
                launch { viewModel.awaitPage(index, timeoutMs = 800L) }
            }
        }
        val snapshot = needed.mapNotNull { idx -> viewModel.images[idx]?.let { idx to it } }.toMap()
        if (currentPages.isNotEmpty() && currentPages.none { snapshot.containsKey(it) }) return false
        if (dualLeaves && (ids == null || !snapshot.containsKey(ids.current))) return false
        val w = leaf.width.roundToInt().coerceAtLeast(1)
        val h = leaf.height.roundToInt().coerceAtLeast(1)
        val bg = colors.background.toArgb()
        val fit = prefs.comicFitMode
        val currentWide = currentPages.singleOrNull()?.let { viewModel.isWideSpan(it) } == true
        val targetWide = targetPages.singleOrNull()?.let { viewModel.isWideSpan(it) } == true
        val splitL = splitLeftPx - contentRect.left
        val splitR = splitRightPx - contentRect.left
        if (dualLeaves && ids != null) {
            val curImg = snapshot[ids.current]
            val nxtImg = ids.next?.let { snapshot[it] }
            val bakImg = ids.back?.let { snapshot[it] }
            val curBmp = pagedStillBitmap(curImg)
            if (curBmp != null &&
                canReusePagedStill(curImg) &&
                canReusePagedStill(nxtImg) &&
                canReusePagedStill(bakImg)
            ) {
                adoptPeelBitmaps(
                    current = curBmp,
                    next = pagedStillBitmap(nxtImg),
                    back = pagedStillBitmap(bakImg),
                    currentFit = comicPeelPageFit(curImg, leaf.width, leaf.height, fit),
                    nextFit = nxtImg?.let { comicPeelPageFit(it, leaf.width, leaf.height, fit) },
                    backFit = bakImg?.let { comicPeelPageFit(it, leaf.width, leaf.height, fit) },
                    owns = false,
                )
                return true
            }
        } else if (currentPages.size <= 1 && targetPages.size <= 1) {
            val curImg = currentPages.firstOrNull()?.let { snapshot[it] }
            val nxtImg = targetPages.firstOrNull()?.let { snapshot[it] }
            val curBmp = pagedStillBitmap(curImg)
            if (curBmp != null && canReusePagedStill(curImg) && canReusePagedStill(nxtImg)) {
                adoptPeelBitmaps(
                    current = curBmp,
                    next = pagedStillBitmap(nxtImg),
                    back = null,
                    currentFit = comicPeelSpreadPageFit(
                        image = curImg,
                        pages = currentPages,
                        dual = dual,
                        rtl = rtl,
                        wideSpan = currentWide,
                        leafWidth = leaf.width,
                        leafHeight = leaf.height,
                        splitLeft = splitL,
                        splitRight = splitR,
                        fitMode = fit,
                    ),
                    nextFit = nxtImg?.let {
                        comicPeelSpreadPageFit(
                            image = it,
                            pages = targetPages,
                            dual = dual,
                            rtl = rtl,
                            wideSpan = targetWide,
                            leafWidth = leaf.width,
                            leafHeight = leaf.height,
                            splitLeft = splitL,
                            splitRight = splitR,
                            fitMode = fit,
                        )
                    },
                    backFit = null,
                    owns = false,
                )
                return true
            }
        }
        val triple = withContext(Dispatchers.Default) {
            if (!dualLeaves || ids == null) {
                Triple(
                    renderComicSpreadBitmap(
                        pages = currentPages,
                        images = snapshot,
                        dual = dual,
                        rtl = rtl,
                        wideSpan = currentWide,
                        widthPx = w,
                        heightPx = h,
                        splitLeft = splitL,
                        splitRight = splitR,
                        backgroundArgb = bg,
                        fitMode = fit,
                    ),
                    renderComicSpreadBitmap(
                        pages = targetPages,
                        images = snapshot,
                        dual = dual,
                        rtl = rtl,
                        wideSpan = targetWide,
                        widthPx = w,
                        heightPx = h,
                        splitLeft = splitL,
                        splitRight = splitR,
                        backgroundArgb = bg,
                        fitMode = fit,
                    ),
                    null as Bitmap?,
                )
            } else {
                Triple(
                    renderComicPageBitmap(snapshot[ids.current], w, h, bg, fit),
                    renderComicPageBitmap(ids.next?.let { snapshot[it] }, w, h, bg, fit),
                    renderComicPageBitmap(ids.back?.let { snapshot[it] }, w, h, bg, fit),
                )
            }
        }
        adoptPeelBitmaps(
            current = triple.first,
            next = triple.second,
            back = triple.third,
            currentFit = null,
            nextFit = null,
            backFit = null,
            owns = true,
        )
        return true
    }

    /**
     * 翻页。[slideFromRight] 只决定覆盖动画的滑入侧（true = 新跨页从右侧外滑入、画面左移）。
     *
     * 滑入侧跟阅读方向镜像：LTR 的「下一页」从右滑入，日漫（RTL）从左滑入。这不是可有可无的
     * 观感——横滑翻页与动画必须同向，否则滑动手势会把下一页从手指的反方向推进来。
     * 仿真掀页走同一条物理轴：左滑永远掀右叶，日漫下逻辑前进因此掀左叶。
     */
    fun turn(forward: Boolean, slideFromRight: Boolean = comicSlideFromRight(forward, rtl)) {
        scope.launch {
            if (animPages != null || peel.busy) return@launch
            val state = uiState
            val target = if (forward) spreadIndex.next(state.pageIndex)
            else spreadIndex.previous(state.pageIndex)
            if (target == null) return@launch
            if (prefs.pageTurnMode == PageTurnMode.NONE || size.width <= 0) {
                viewModel.goToPage(target, countRead = true)
                return@launch
            }
            if (prefs.pageTurnMode == PageTurnMode.SIMULATION) {
                val gen = peelGeneration + 1
                peelGeneration = gen
                val currentPages = spreadIndex.pagesOf(state.pageIndex)
                val targetPages = spreadIndex.pagesOfSpread(spreadIndex.spreadOf(target))
                val (dualLeaves, leaves) = lockPeelLeaves(currentPages, targetPages)
                val (left, right) = leaves
                val peelFwd = comicPeelForward(forward, rtl)
                val leaf = activePeelLeaf(peelFwd, left, right)
                val tap = lastTapOffset
                val contentLocal = if (tap != null) {
                    Offset(tap.x - contentRect.left, tap.y - contentRect.top)
                } else {
                    Offset(
                        leaf.originX + if (peelFwd) leaf.width * 0.92f else leaf.width * 0.08f,
                        leaf.originY + leaf.height * 0.85f,
                    )
                }
                val local = screenToLeafLocal(contentLocal, leaf)
                val corner = peelCornerFor(local, leaf.width, leaf.height, peelFwd)
                val opposite = peelOppositeWidth(corner, leaf, left, right)
                peelTarget = target
                if (!preparePeelBitmaps(peelFwd, currentPages, targetPages, leaf, dualLeaves)) {
                    finishPeel()
                    viewModel.goToPage(target, countRead = true)
                    return@launch
                }
                if (peelGeneration != gen) return@launch
                peel.autoPlay(peelFwd, corner, leaf, opposite)
                if (peelGeneration != gen) return@launch
                viewModel.goToPage(target, countRead = true)
                // goToPage 走 StateFlow 是异步的，等下一帧新跨页上屏后再撤覆盖层，避免闪回旧页
                withFrameNanos { }
                finishPeel()
                return@launch
            }
            animPages = spreadIndex.pagesOfSpread(spreadIndex.spreadOf(target))
            animX.snapTo(if (slideFromRight) size.width.toFloat() else -size.width.toFloat())
            animX.animateTo(0f, tween(ANIM_MS))
            viewModel.goToPage(target, countRead = true)
            animPages = null
        }
    }
    val latestTurn by rememberUpdatedState<(Boolean) -> Unit> { forward -> turn(forward) }

    // 与文本阅读器同一条坑：pointerInput 抓死开书时的单页叶矩形，拖动就变成整幅掀。
    val latestBeginPeelDrag by rememberUpdatedState<(Boolean, Offset) -> Unit> { peelFwd, pos ->
        scope.launch {
            if (peel.busy || animPages != null) return@launch
            val logicalFwd = peelFwd != rtl
            val state = uiState
            val target = if (logicalFwd) {
                spreadIndex.next(state.pageIndex)
            } else {
                spreadIndex.previous(state.pageIndex)
            } ?: return@launch
            val currentPages = spreadIndex.pagesOf(state.pageIndex)
            val targetPages = spreadIndex.pagesOfSpread(spreadIndex.spreadOf(target))
            val (dualLeaves, leaves) = lockPeelLeaves(currentPages, targetPages)
            val (left, right) = leaves
            val leaf = activePeelLeaf(peelFwd, left, right)
            val contentLocal = Offset(pos.x - contentRect.left, pos.y - contentRect.top)
            val local = screenToLeafLocal(contentLocal, leaf)
            val corner = peelCornerFor(local, leaf.width, leaf.height, peelFwd)
            val opposite = peelOppositeWidth(corner, leaf, left, right)
            val gen = peelGeneration + 1
            peelGeneration = gen
            peelTarget = target
            if (!preparePeelBitmaps(peelFwd, currentPages, targetPages, leaf, dualLeaves)) {
                finishPeel()
                return@launch
            }
            if (peelGeneration != gen) return@launch
            peel.beginDrag(peelFwd, corner, leaf, local, opposite)
        }
    }
    val latestPeelDragLocal by rememberUpdatedState<(Offset) -> Offset> { pos ->
        screenToLeafLocal(
            Offset(pos.x - contentRect.left, pos.y - contentRect.top),
            peel.leaf,
        )
    }

    // 自动翻页·间隔模式：到点由 ViewModel 发请求。滚动模式下翻页要顺手把列表滚过去，
    // 否则页号变了而画面还在原处（列表只在进入滚动模式时对过一次页号）。
    LaunchedEffect(viewModel, scrollMode) {
        viewModel.autoPageTurns.collect { forward ->
            if (scrollMode) {
                val next = spreadIndex.next(uiState.pageIndex) ?: return@collect
                scope.launch { scrollListState.animateScrollToItem(next) }
                viewModel.goToPage(next)
            } else {
                latestTurn(forward)
            }
        }
    }

    // 自动翻页·滚动模式：条漫按 px/s 匀速推进。用户自己拖动时（isScrollInProgress）让位。
    LaunchedEffect(
        scrollMode,
        prefs.autoPageEnabled,
        prefs.autoPageMode,
        prefs.autoPageSpeedPx,
        menuVisible,
    ) {
        if (!scrollMode || !prefs.autoPageEnabled || prefs.autoPageMode != AutoPageMode.SCROLL) {
            return@LaunchedEffect
        }
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { now ->
                if (menuVisible || scrollListState.isScrollInProgress) {
                    lastFrameNs = 0L
                } else if (lastFrameNs != 0L) {
                    val dtSec = (now - lastFrameNs) / 1_000_000_000f
                    // dispatchRawDelta 是同步的：在帧回调里直接用，不必为每一帧起一个协程
                    scrollListState.dispatchRawDelta(prefs.autoPageSpeedPx * dtSec)
                    lastFrameNs = now
                } else {
                    lastFrameNs = now
                }
            }
        }
    }

    // 页内选区（动作条据此出现）。翻页即清空：选区是页内坐标，跨页没有意义。
    var pageSelection by remember { mutableStateOf<PageSelection?>(null) }
    LaunchedEffect(uiState.pageIndex) { pageSelection = null }

    /**
     * 页内锚点上下文。
     *
     * `onSelectionUpdate` 刻意不接：拖框过程中页面自己按本地状态绘制（见 ComicPageView），
     * 每帧回写阅读器状态会让整棵界面跟着重组；只有松手定型才回写一次。
     */
    val anchorHost = PageAnchorHost(
        bookmarks = bookmarks,
        annotations = annotations,
        selection = pageSelection,
        onAnchorPoint = { page, x, y -> viewModel.toggleBookmark(page, x, y) },
        onSelectionCommit = { page, selection ->
            // 先按手指画的框亮出来（动作条立刻可用），有文字层时再换成文档自己的选区
            pageSelection = selection
            scope.launch {
                val snapped = viewModel.snapSelectionToText(page, selection.rect)
                // 期间用户可能已经取消或改了选区：只替换仍然属于这一页的那一个
                if (snapped != null && pageSelection?.pageIndex == page) pageSelection = snapped
            }
        },
    )

    /** 中间点击区可分配的动作。翻页档用的是**逻辑**上一页/下一页，与 RTL 无关（标签是显式的）。 */
    val runTapAction: (TapAction) -> Unit = { action ->
        when (action) {
            TapAction.TOGGLE_MENU -> menuVisible = !menuVisible
            TapAction.PREVIOUS_PAGE -> latestTurn(false)
            TapAction.NEXT_PAGE -> latestTurn(true)
            TapAction.TOGGLE_BOOKMARK -> viewModel.toggleBookmarkOnCurrentPage()
            TapAction.TOGGLE_ZOOM -> viewModel.toggleZoomFit()
            TapAction.NONE -> Unit
        }
    }

    /**
     * 点击处理。
     *
     * 根手势层与中间点击层共用它——中间区那层要能把双击传进来，其余分支必须完全一致，
     * 否则「配了双击之后点击行为变了」会变成很难查的差异。
     */
    val handleTap: (Offset, Boolean) -> Unit = { offset, isDouble ->
        viewModel.noteManualInteraction()
        lastTapOffset = offset
        if (pageSelection != null) {
            // 有选区时点一下先收起选区，与文本阅读器的「点一下退选」一致
            pageSelection = null
        } else if (menuVisible) {
            menuVisible = false
        } else if (scrollMode) {
            // 连续滚动里左右热区没有意义（页面是竖向连成一条的），点哪都呼出菜单
            menuVisible = true
        } else {
            // 中间区是可配置动作，左右热区才翻页；方向翻译（日漫点左=下一页）见 comicTapForward。
            // 底边不再特殊：它就相当于把左右热区一直延伸到屏幕底，日漫下右下角=上一页。
            val forward = comicTapForward(
                tapZoneOf(offset.x, size.width.toFloat(), prefs.pageTurnHotspotRatio),
                rtl,
            )
            if (forward == null) {
                runTapAction(
                    resolveMiddleTap(prefs.middleTapAction, prefs.middleDoubleTapAction, isDouble),
                )
            } else {
                latestTurn(forward)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged { size = it }
            .onGloballyPositioned {
                // 进出书滑移期间整页在窗口里逐帧平移，偏移每帧都在变；双页的铰链局部坐标
                // （和解码目标宽度）由它推导，跟着漂移会反复触发 setDecodeTarget 清表重解码，
                // 表现为打开时的闪烁抖动。阅读页静止时铺满窗口、偏移即 (0,0)，转场期间冻结即可
                if (!readerSettled) return@onGloballyPositioned
                val bounds = it.boundsInWindow()
                windowOffsetX = bounds.left
                windowOffsetY = bounds.top
            }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                // 外接键盘 / 桌面模式的常规翻页键（音量键那档是可选项，见下）
                when (native.keyCode) {
                    // 方向键是「物理左右」：日漫下镜像，与左右热区同一口径
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (scrollMode) scrollByScreen(1) else latestTurn(!rtl)
                        return@onKeyEvent true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (scrollMode) scrollByScreen(-1) else latestTurn(rtl)
                        return@onKeyEvent true
                    }

                    // 翻页 / 媒体键是「逻辑前进后退」：与音量键同口径，不随阅读方向翻转
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    -> {
                        if (scrollMode) scrollByScreen(1) else latestTurn(true)
                        return@onKeyEvent true
                    }

                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (scrollMode) scrollByScreen(-1) else latestTurn(false)
                        return@onKeyEvent true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        scrollByScreen(1)
                        return@onKeyEvent true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        scrollByScreen(-1)
                        return@onKeyEvent true
                    }
                }
                if (!prefs.volumeKeyPagingEnabled) return@onKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        val up = native.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        when (volumeKeyDispatch(up, scrollMode = scrollMode)) {
                            VolumeKeyDispatch.SCROLL_BACK -> scrollByScreen(-1)
                            VolumeKeyDispatch.SCROLL_FORTH -> scrollByScreen(1)
                            VolumeKeyDispatch.PAGE_PREV -> latestTurn(false)
                            VolumeKeyDispatch.PAGE_NEXT -> latestTurn(true)
                        }
                        true
                    }
                    else -> false
                }
            }
            // 鼠标滚轮：桌面模式与外接鼠标上最顺手的翻页方式。
            // 纵向连续滚动里滚轮就是滚动本身；分页模式下它也是带方向的输入，随阅读方向上下镜像
            // （日漫上滚=下一页），与热区/横滑同一口径
            .pointerInput(scrollMode, rtl) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy == 0f) continue
                        event.changes.forEach { it.consume() }
                        if (scrollMode) {
                            scrollByScreen(if (dy > 0f) 1 else -1)
                        } else {
                            latestTurn(comicWheelForward(dy, rtl))
                        }
                    }
                }
            }
            // rtl 是普通 val（非快照状态），必须列进 key：否则切方向后旧的处理器还在跑，
            // 点击与横滑都会继续按旧方向判定
            .pointerInput(uiState.pageCount, prefs.pageTurnHotspotRatio, scrollMode, rtl) {
                detectTapGestures { offset -> handleTap(offset, false) }
            }
            .pointerInput(
                prefs.swipeGestureEnabled,
                prefs.swipeDistanceDp,
                prefs.swipeFlingVelocityDpPerSec,
                prefs.pageTurnMode,
                rtl,
                dual,
                scrollMode,
                density.density,
            ) {
                if (!prefs.swipeGestureEnabled || scrollMode) return@pointerInput
                val distanceThreshold = prefs.swipeDistanceDp * density.density
                val flingThreshold = prefs.swipeFlingVelocityDpPerSec * density.density
                var dragged = 0f
                var tracker: VelocityTracker? = null
                if (prefs.pageTurnMode == PageTurnMode.SIMULATION) {
                    var started = false
                    var peelFwd = true
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragged = 0f
                            tracker = VelocityTracker()
                            started = false
                        },
                        onHorizontalDrag = { change, delta ->
                            dragged += delta
                            tracker?.addPosition(change.uptimeMillis, change.position)
                            if (!started) {
                                started = true
                                peelFwd = dragged < 0f
                                latestBeginPeelDrag(peelFwd, change.position)
                            } else if (peel.phase == PeelPhase.Drag) {
                                peel.updateDrag(latestPeelDragLocal(change.position))
                            }
                        },
                        onDragEnd = {
                            val vx = tracker?.calculateVelocity()?.x ?: 0f
                            val vy = tracker?.calculateVelocity()?.y ?: 0f
                            tracker = null
                            dragged = 0f
                            viewModel.noteManualInteraction()
                            scope.launch {
                                if (peel.phase != PeelPhase.Drag) {
                                    started = false
                                    return@launch
                                }
                                val gen = peelGeneration
                                val committed = peel.endDrag(vx, vy, distanceThreshold)
                                if (peelGeneration != gen) return@launch
                                if (committed) {
                                    peelTarget?.let { viewModel.goToPage(it, countRead = true) }
                                    withFrameNanos { }
                                }
                                finishPeel()
                                started = false
                            }
                        },
                        onDragCancel = {
                            dragged = 0f
                            tracker = null
                            started = false
                            scope.launch {
                                if (peel.phase == PeelPhase.Drag) {
                                    peel.endDrag(0f, 0f)
                                }
                                finishPeel()
                            }
                        },
                    )
                    return@pointerInput
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragged = 0f
                        tracker = VelocityTracker()
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragged += delta
                        tracker?.addPosition(change.uptimeMillis, change.position)
                    },
                    onDragEnd = {
                        comicSwipeForward(
                            draggedPx = dragged,
                            velocityXPxPerSec = tracker?.calculateVelocity()?.x ?: 0f,
                            distanceThresholdPx = distanceThreshold,
                            flingVelocityPxPerSec = flingThreshold,
                            rtl = rtl,
                        )?.let { latestTurn(it) }
                        dragged = 0f
                        tracker = null
                    },
                    onDragCancel = {
                        dragged = 0f
                        tracker = null
                    },
                )
            }
            .pointerInput(prefs.brightnessGestureEnabled) {
                if (!prefs.brightnessGestureEnabled) return@pointerInput
                var current = prefs.readerBrightness
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        current = prefs.readerBrightness
                        if (offset.x < size.width / 3f) brightnessHint = current
                    },
                    onVerticalDrag = { change, delta ->
                        if (change.position.x >= size.width / 3f) return@detectVerticalDragGestures
                        change.consume()
                        val step = -delta / (size.height.coerceAtLeast(1).toFloat())
                        val next = (current + step * 2f).coerceIn(0f, 1f)
                        current = next
                        brightnessHint = next
                        viewModel.setReaderBrightness(next)
                    },
                    onDragEnd = { brightnessHint = null },
                    onDragCancel = { brightnessHint = null },
                )
            },
    ) {
        when {
            uiState.error != null -> EmptyState(
                title = "无法打开这本漫画",
                description = uiState.error ?: "",
                actionLabel = "返回",
                onAction = { exit.leaveTo(onBack) },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> {
                // 偏好就绪前不渲内容：滚动/双页/方向/适配方式都依赖偏好，
                // 先按默认值渲一版再跳变就是打开时那一闪
                if (!prefsLoaded) return@Box
                if (scrollMode) {
                    ComicScrollContent(
                        pageCount = uiState.pageCount,
                        viewModel = viewModel,
                        background = colors.background,
                        gap = prefs.comicScrollGapDp.dp,
                        listState = scrollListState,
                        host = anchorHost,
                        modifier = Modifier.fillMaxSize(),
                    )
                    return@Box
                }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(contentRect.left.roundToInt(), contentRect.top.roundToInt()) }
                        .width(with(density) { contentRect.width.toDp() })
                        .height(with(density) { contentRect.height.toDp() })
                        .clipToBounds(),
                ) {
                    val currentPages = viewModel.currentPages()
                    ComicSpread(
                        pages = currentPages,
                        viewModel = viewModel,
                        background = colors.background,
                        dual = dual && uiState.dual,
                        rtl = rtl,
                        fitMode = prefs.comicFitMode,
                        splitLeftPx = splitLeftPx,
                        splitRightPx = splitRightPx,
                        windowWidthPx = contentRect.width,
                        host = anchorHost,
                    )
                    val sliding = animPages
                    if (sliding != null) {
                        ComicSpread(
                            pages = sliding,
                            viewModel = viewModel,
                            background = colors.background,
                            dual = dual && uiState.dual,
                            rtl = rtl,
                            fitMode = prefs.comicFitMode,
                            splitLeftPx = splitLeftPx,
                            splitRightPx = splitRightPx,
                            windowWidthPx = contentRect.width,
                            host = anchorHost,
                            modifier = Modifier.graphicsLayer { translationX = animX.value },
                        )
                    }
                    val peelTick = peel.progress.value + peel.touchX.value + peel.touchY.value
                    val peelFrameNow = if (peel.busy) {
                        peelTick
                        peel.frame()
                    } else {
                        null
                    }
                    val curBmp = peelCurrentBmp
                    val nxtBmp = peelNextBmp
                    val backBmp = peelBackBmp
                    if (peelFrameNow != null &&
                        curBmp != null &&
                        !curBmp.isRecycled &&
                        (nxtBmp == null || !nxtBmp.isRecycled)
                    ) {
                        val (leftLeaf, rightLeaf) = peelLeafSession ?: (peel.leaf to null)
                        val (extL, extR) = peelFlapExtend(peel.corner, peel.leaf, leftLeaf, rightLeaf)
                        PeelOverlay(
                            frame = peelFrameNow,
                            leaf = peel.leaf,
                            current = curBmp,
                            next = nxtBmp,
                            background = colors.background,
                            back = backBmp?.takeUnless { it.isRecycled },
                            extendLeft = extL,
                            extendRight = extR,
                            currentFit = peelCurrentFit,
                            nextFit = peelNextFit,
                            backFit = peelBackFit,
                        )
                    }
                }
            }
        }

        // 中间点击层：只有配了本阅读器能执行的双击动作时才铺。
        // 它只盖住「判定为中间区」的那块矩形，左右翻页保持抬手即响应；
        // 漫画没有底边翻页条（底边随左右热区分区），所以中间区是整高。
        // 连续滚动模式下没有左右热区的概念（上面已提前 return），所以这里也不用铺。
        if (uiState.error == null) {
            val doubleAction = prefs.middleDoubleTapAction
            if (supportsTapAction(doubleAction, paged = true)) {
                MiddleTapLayer(
                    hotspotRatio = prefs.pageTurnHotspotRatio,
                    doubleTapAction = doubleAction,
                    onTap = { offset, isDouble -> handleTap(offset, isDouble) },
                    bottomStripEnabled = false,
                )
            }
        }

        // 页内选区的动作条：色点即高亮，另有下划线 / 书签 / 取消。
        // 页式没有文字层，所以不给「笔记」；「复制」等 N5 接上页内选字后再出现。
        pageSelection?.let { sel ->
            SelectionActionBar(
                colors = colors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                onPickColor = { argb ->
                    viewModel.addPageAnnotation(
                        sel.pageIndex,
                        sel.rect,
                        AnnotationEntity.STYLE_HIGHLIGHT,
                        argb,
                        sel.text.orEmpty(),
                    )
                    pageSelection = null
                },
                onUnderline = {
                    viewModel.addPageAnnotation(
                        sel.pageIndex,
                        sel.rect,
                        AnnotationEntity.STYLE_UNDERLINE,
                        annotationColorPalette.first().toArgb().toLong() and 0xFFFFFFFFL,
                        sel.text.orEmpty(),
                    )
                    pageSelection = null
                },
                onBookmark = {
                    viewModel.toggleBookmark(
                        pageIndex = sel.pageIndex,
                        x = sel.rect.left,
                        y = sel.rect.top,
                        w = sel.rect.width,
                        h = sel.rect.height,
                    )
                    pageSelection = null
                },
                onCopy = sel.text?.takeIf { it.isNotBlank() }?.let { text ->
                    {
                        clipboard.setText(AnnotatedString(text))
                        pageSelection = null
                    }
                },
                onCancel = { pageSelection = null },
            )
        }

        // 封面共享元素只用于"进书"：加载时承接书架飞入，加载完成后淡出并摘掉共享注册
        val sharedScope = sharedTransitionScope
        val animScope = animatedVisibilityScope
        if (sharedScope != null && animScope != null) {
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
                    Box(modifier = Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                        if (uiState.loading) LoadingIndicator(color = colors.accent)
                    }
                }
            }
        }

        brightnessHint?.let { value ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "亮度 ${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (!uiState.loading && uiState.error == null) {
            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(tween(ANIM_MS)) + slideInVertically { -it / 3 },
                exit = fadeOut(tween(ANIM_MS)) + slideOutVertically { -it / 3 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ComicTopBar(
                    title = uiState.bookTitle,
                    pageText = comicPageNumberText(viewModel.currentPages(), uiState.pageCount),
                    colors = colors,
                    onBack = { exit.leaveTo(onBack) },
                    onOpenSettings = { exit.leaveTo(onOpenSettings) },
                    onOpenThumbnails = {
                        menuVisible = false
                        thumbnailsVisible = true
                    },
                    bookmarked = viewModel.isPageBookmarked(uiState.pageIndex),
                    onToggleBookmark = { viewModel.toggleBookmarkOnCurrentPage() },
                    onOpenBookmarks = {
                        menuVisible = false
                        bookmarksVisible = true
                    },
                )
            }
            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(tween(ANIM_MS)) + slideInVertically { it / 3 },
                exit = fadeOut(tween(ANIM_MS)) + slideOutVertically { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ComicMenuPanel(
                    prefs = prefs,
                    colors = colors,
                    features = features,
                    progressFraction = position.progressFraction,
                    pageText = comicPageNumberText(viewModel.currentPages(), uiState.pageCount),
                    onSeekFraction = viewModel::seekToFraction,
                    onOpenJumpToPage = {
                        // 关掉菜单再开输入框：两者同时出现会互相抢焦点
                        menuVisible = false
                        jumpVisible = true
                    },
                    largeFileHintMb = uiState.largeFileMb,
                    onOpenThumbnails = {
                        menuVisible = false
                        thumbnailsVisible = true
                    },
                    hasOutline = outline.size > 1,
                    onOpenOutline = {
                        menuVisible = false
                        outlineVisible = true
                    },
                    onOpenAnnotations = {
                        menuVisible = false
                        annotationsVisible = true
                    },
                    onOpenSearch = {
                        menuVisible = false
                        searchVisible = true
                    },
                    onPrevVolume = prevVolume?.let { { menuVisible = false; openSeriesEntry(it) } },
                    onNextVolume = nextVolume?.let { { menuVisible = false; openSeriesEntry(it) } },
                    seriesPosition = seriesPosition,
                    onOpenSeries = {
                        menuVisible = false
                        seriesVisible = true
                    },
                    onToggleAutoPage = viewModel::setAutoPageEnabled,
                    onCycleAutoPageSetting = viewModel::cycleAutoPageSetting,
                    onCycleAutoPageMode = viewModel::cycleAutoPageMode,
                    onSwitchToTextMode = if (features.textLayer) {
                        {
                            menuVisible = false
                            viewModel.setPdfReadingMode(PdfReadingMode.TEXT)
                        }
                    } else {
                        null
                    },
                    onSelectPageTurnMode = viewModel::setPageTurnMode,
                    onSelectDirection = viewModel::setComicDirection,
                    onSelectFitMode = viewModel::setComicFitMode,
                    onSelectScrollGap = viewModel::setComicScrollGapDp,
                    onToggleCoverAlone = viewModel::setComicDualPageCoverAlone,
                    onToggleSpreadAutoDetect = viewModel::setComicSpreadAutoDetect,
                    onSetBrightness = viewModel::setReaderBrightness,
                    onToggleKeepScreenOn = viewModel::setKeepScreenOn,
                    onSelectTheme = viewModel::setTheme,
                    onPickCustomBackground = { viewModel.setCustomColors(it, prefs.customTextArgb) },
                    onPickCustomText = { viewModel.setCustomColors(prefs.customBackgroundArgb, it) },
                    onOpenSettings = { exit.leaveTo(onOpenSettings) },
                )
            }
        }

        if (uiState.passwordRequired) {
            PasswordDialog(
                onSubmit = viewModel::submitPassword,
                onDismiss = viewModel::cancelPassword,
            )
        }

        if (outlineVisible) {
            // 复用文本阅读器那套目录对话框（同样的层级缩进与高亮），只是锚点换成页序号
            ChapterListDialog(
                chapters = outline,
                currentIndex = viewModel.outlineIndexFor(uiState.pageIndex),
                remainingText = null,
                colors = colors,
                onSelect = { index ->
                    outlineVisible = false
                    viewModel.jumpToOutline(index)
                },
                onDismiss = { outlineVisible = false },
            )
        }

        if (bookmarksVisible) {
            BookmarkListDialog(
                bookmarks = bookmarks,
                chapters = outline,
                colors = colors,
                onJump = { bookmark ->
                    bookmarksVisible = false
                    viewModel.jumpToBookmark(bookmark)
                },
                onRename = { bookmark, label -> viewModel.renameBookmark(bookmark, label) },
                onDelete = { bookmark -> viewModel.deleteBookmark(bookmark.id) },
                onDismiss = { bookmarksVisible = false },
            )
        }

        if (searchVisible) {
            ComicSearchDialog(
                state = searchState,
                colors = colors,
                // 扫描件与漫画没有文字层：直接说明，而不是让人搜完一场空
                textSearchable = features.textLayer,
                onSearch = viewModel::search,
                onJump = { page ->
                    if (scrollMode) {
                        scope.launch { scrollListState.scrollToItem(page) }
                    } else {
                        viewModel.goToPage(page)
                    }
                },
                onDismiss = {
                    searchVisible = false
                    viewModel.clearSearch()
                },
            )
        }

        if (jumpVisible) {
            ComicJumpDialog(
                pageCount = uiState.pageCount,
                currentPage = uiState.pageIndex,
                onJump = { page ->
                    if (scrollMode) {
                        scope.launch { scrollListState.scrollToItem(page) }
                    } else {
                        viewModel.goToPage(page)
                    }
                },
                onDismiss = { jumpVisible = false },
            )
        }

        if (seriesVisible) {
            ComicSeriesDialog(
                entries = series,
                currentBookId = bookId,
                colors = colors,
                onOpenBook = { id ->
                    seriesVisible = false
                    onOpenBook(id)
                },
                onImport = openSeriesEntry,
                onDismiss = { seriesVisible = false },
            )
        }

        if (annotationsVisible) {
            AnnotationListDialog(
                annotations = annotations,
                chapters = outline,
                // 页式锚点与正文字符偏移无关，没有「原书内容已变化」这回事
                shiftedIds = emptySet(),
                colors = colors,
                groupByChapter = false,
                emptyText = "还没有高亮。长按页面拖动即可框选，再选颜色或下划线",
                onJump = { annotation ->
                    annotationsVisible = false
                    viewModel.jumpToAnnotation(annotation)
                },
                onEdit = { annotation ->
                    annotationsVisible = false
                    editingAnnotation = annotation
                },
                onDismiss = { annotationsVisible = false },
            )
        }

        editingAnnotation?.let { target ->
            AnnotationEditDialog(
                selectedText = target.selectedText.ifEmpty {
                    target.pageIndex?.let { pageLabelOf(it.toInt()) }.orEmpty()
                },
                initialColorArgb = target.color,
                initialNote = target.note,
                initialStyle = target.style,
                shifted = false,
                colors = colors,
                onSave = { color, note, style ->
                    viewModel.updateAnnotation(target, color, note, style)
                    editingAnnotation = null
                },
                onDelete = {
                    viewModel.deleteAnnotation(target.id)
                    editingAnnotation = null
                },
                onDismiss = { editingAnnotation = null },
            )
        }

        if (thumbnailsVisible && uiState.pageCount > 0) {
            ComicThumbnailSheet(
                pageCount = uiState.pageCount,
                currentPage = uiState.pageIndex,
                viewModel = viewModel,
                onJump = { index ->
                    thumbnailsVisible = false
                    if (scrollMode) {
                        scope.launch { scrollListState.scrollToItem(index) }
                    } else {
                        viewModel.goToPage(index)
                    }
                },
                onDismiss = { thumbnailsVisible = false },
            )
        }
    }
}

/**
 * 一组页在内容区里的摆放。
 *
 * - 单页 / 跨页大图（span）：铺满整宽；
 * - 双页：按铰链安全区左右分栏，中缝留白并叠书脊；
 * - 日漫（rtl）：同一对页里低序号页放在**右边**。
 */
@Composable
private fun BoxScope.ComicSpread(
    pages: List<Int>,
    viewModel: ComicReaderViewModel,
    background: Color,
    dual: Boolean,
    rtl: Boolean,
    fitMode: ComicFitMode,
    splitLeftPx: Float,
    splitRightPx: Float,
    windowWidthPx: Float,
    host: PageAnchorHost,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val images = viewModel.images
    val failed = viewModel.failedPages
    when {
        !dual || pages.isEmpty() -> ComicPageView(
            image = pages.firstOrNull()?.let { images[it] },
            failed = pages.firstOrNull()?.let { failed[it] } == true,
            background = background,
            fitMode = fitMode,
            resetKey = pages.firstOrNull() ?: -1,
            pageIndex = pages.firstOrNull() ?: -1,
            host = host,
            modifier = modifier.fillMaxSize(),
        )

        pages.size == 1 -> {
            if (viewModel.isWideSpan(pages[0])) {
                // 跨页大图：独占整宽，这才是原书的呈现
                ComicPageView(
                    image = images[pages[0]],
                    failed = failed[pages[0]] == true,
                    background = background,
                    fitMode = fitMode,
                    resetKey = pages[0],
                    pageIndex = pages[0],
                    host = host,
                    modifier = modifier.fillMaxSize(),
                )
            } else {
                // 封面（或末页落单）单独一页：占自己那半边，另一半留出"空白页"的观感
                val pageWidth = density.run { splitLeftPx.toDp() }
                val hingeWidth = density.run { (splitRightPx - splitLeftPx).coerceAtLeast(0f).toDp() }
                val rightWidth = density.run { (windowWidthPx - splitRightPx).coerceAtLeast(0f).toDp() }
                Row(modifier = modifier.fillMaxSize()) {
                    if (rtl) {
                        Box(modifier = Modifier.width(pageWidth).fillMaxHeight())
                        Box(modifier = Modifier.width(hingeWidth).fillMaxHeight())
                        Box(modifier = Modifier.width(rightWidth).fillMaxHeight()) {
                            ComicPageView(
                                image = images[pages[0]],
                                failed = failed[pages[0]] == true,
                                background = background,
                                fitMode = fitMode,
                                resetKey = pages[0],
                                pageIndex = pages[0],
                                host = host,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Box(modifier = Modifier.width(pageWidth).fillMaxHeight()) {
                            ComicPageView(
                                image = images[pages[0]],
                                failed = failed[pages[0]] == true,
                                background = background,
                                fitMode = fitMode,
                                resetKey = pages[0],
                                pageIndex = pages[0],
                                host = host,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(modifier = Modifier.width(hingeWidth).fillMaxHeight())
                        Box(modifier = Modifier.width(rightWidth).fillMaxHeight())
                    }
                }
            }
        }

        else -> {
            // 日漫：序号小的页在右侧
            val leftIndex = if (rtl) pages[1] else pages[0]
            val rightIndex = if (rtl) pages[0] else pages[1]
            val leftWidth = density.run { splitLeftPx.toDp() }
            val hingeWidth = density.run { (splitRightPx - splitLeftPx).coerceAtLeast(0f).toDp() }
            val rightWidth = density.run { (windowWidthPx - splitRightPx).coerceAtLeast(0f).toDp() }
            Row(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(leftWidth).fillMaxHeight()) {
                    ComicPageView(
                        image = images[leftIndex],
                        failed = failed[leftIndex] == true,
                        background = background,
                        fitMode = fitMode,
                        resetKey = leftIndex,
                        pageIndex = leftIndex,
                        host = host,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(modifier = Modifier.width(hingeWidth).fillMaxHeight().background(background)) {
                    SpineOverlay(modifier = Modifier.fillMaxSize())
                }
                Box(modifier = Modifier.width(rightWidth).fillMaxHeight()) {
                    ComicPageView(
                        image = images[rightIndex],
                        failed = failed[rightIndex] == true,
                        background = background,
                        fitMode = fitMode,
                        resetKey = rightIndex,
                        pageIndex = rightIndex,
                        host = host,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
