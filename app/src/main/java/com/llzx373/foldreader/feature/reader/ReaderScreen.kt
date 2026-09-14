package com.llzx373.foldreader.feature.reader

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.foldable.FoldableUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val INNER_SPINE_PAD = 12.dp
private val SPINE_OVERLAY_WIDTH = 32.dp

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
    val colors = readerColors(prefs.themeId, prefs.customBackgroundArgb, prefs.customTextArgb)
    val scope = rememberCoroutineScope()

    var menuVisible by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var windowOffsetX by remember { mutableStateOf(0f) }
    var windowOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    val rawMode = prefs.pageTurnMode
    val scrollMode = rawMode == PageTurnMode.SCROLL
    val effectiveMode = if (rawMode == PageTurnMode.SIMULATION) PageTurnMode.COVER else rawMode

    val layoutMode = resolvePageLayoutMode(
        posture = foldableUiState.posture,
        widthCategory = foldableUiState.widthCategory,
        pref = prefs.dualPageMode,
    )
    val dual = layoutMode == PageLayoutMode.DUAL && !scrollMode

    val hingeLocal = foldableUiState.posture.hingeBounds
        ?.takeIf { it.width > 0f }
        ?.let { Rect(it.left - windowOffsetX, it.top - windowOffsetY, it.right - windowOffsetX, it.bottom - windowOffsetY) }
    val splitLeftPx = (hingeLocal?.left ?: size.width / 2f).coerceIn(0f, size.width.toFloat())
    val splitRightPx = (hingeLocal?.right ?: size.width / 2f).coerceIn(splitLeftPx, size.width.toFloat())

    LaunchedEffect(dual, size, splitLeftPx, splitRightPx, density.density, density.fontScale) {
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
            viewModel.setViewports(
                dual = false,
                leftWidthPx = size.width,
                rightWidthPx = 0,
                heightPx = size.height,
                density = density.density,
                scaledDensity = density.density * density.fontScale,
            )
        }
    }

    var animSpread by remember { mutableStateOf<PageSpread?>(null) }
    val animX = remember { Animatable(0f) }

    fun turn(forward: Boolean) {
        scope.launch {
            if (animSpread != null) return@launch
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
    LaunchedEffect(foreground, menuVisible, uiState.loading, uiState.error) {
        viewModel.setReadingActive(
            foreground && !menuVisible && !uiState.loading && uiState.error == null,
        )
    }

    LaunchedEffect(rawMode) {
        if (rawMode == PageTurnMode.SIMULATION) {
            Toast.makeText(context, "仿真翻页将在后续版本提供，已使用覆盖滑动", Toast.LENGTH_SHORT).show()
        }
        if (scrollMode) viewModel.enterScrollMode() else viewModel.relocate()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val innerPadPx = with(density) { INNER_SPINE_PAD.toPx() }
    val spineOverlayPx = with(density) { SPINE_OVERLAY_WIDTH.toPx() }
    val leftDp = with(density) { splitLeftPx.toDp() }
    val hingeDp = with(density) { (splitRightPx - splitLeftPx).toDp() }
    val rightDp = with(density) { (size.width - splitRightPx).toDp() }

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
                if (!prefs.volumeKeyPagingEnabled || native.action != KeyEvent.ACTION_DOWN) {
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
                    if (menuVisible) {
                        menuVisible = false
                        return@detectTapGestures
                    }
                    if (scrollMode) {
                        menuVisible = true
                        return@detectTapGestures
                    }
                    when (tapZoneOf(offset.x, size.width.toFloat(), prefs.pageTurnHotspotRatio)) {
                        TapZone.PREVIOUS -> turn(false)
                        TapZone.NEXT -> turn(true)
                        TapZone.MENU -> menuVisible = true
                    }
                }
            }
            .pointerInput(scrollMode) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> dragged += dragAmount },
                    onDragEnd = {
                        if (!scrollMode) {
                            val threshold = size.width * 0.15f
                            if (dragged < -threshold) turn(true) else if (dragged > threshold) turn(false)
                        }
                        dragged = 0f
                    },
                )
            },
    ) {
        when {
            uiState.loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = colors.accent,
            )
            uiState.error != null -> Text(
                text = uiState.error.orEmpty(),
                modifier = Modifier.align(Alignment.Center),
                color = colors.text,
            )
            scrollMode -> ScrollContent(
                viewModel = viewModel,
                colors = colors,
                pageHeight = size.height,
            )
            else -> {
                val spread = uiState.spread
                if (spread != null) {
                    SpreadContent(
                        spread = spread,
                        config = uiState.layoutConfig,
                        colors = colors,
                        dual = dual,
                        leftDp = leftDp,
                        hingeDp = hingeDp,
                        rightDp = rightDp,
                        innerPadPx = innerPadPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val overlay = animSpread
                    if (overlay != null) {
                        SpreadContent(
                            spread = overlay,
                            config = uiState.layoutConfig,
                            colors = colors,
                            dual = dual,
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
) {
    if (!dual) {
        PageView(
            page = spread.left,
            config = config,
            colors = colors,
            modifier = modifier,
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

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(uiState.scrollPages, key = { it.charStart }) { page ->
            PageView(
                page = page,
                config = uiState.layoutConfig,
                colors = colors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { pageHeight.toDp() }),
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
