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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.reader.Page
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FoldReaderApplication
    val viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.factory(app.container, bookId),
    )
    val uiState by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val colors = readerColors(prefs.themeId)
    val scope = rememberCoroutineScope()

    var menuVisible by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    val rawMode = prefs.pageTurnMode
    val scrollMode = rawMode == PageTurnMode.SCROLL
    val effectiveMode = if (rawMode == PageTurnMode.SIMULATION) PageTurnMode.COVER else rawMode

    var animPage by remember { mutableStateOf<Page?>(null) }
    val animX = remember { Animatable(0f) }

    fun turn(forward: Boolean) {
        scope.launch {
            if (animPage != null) return@launch
            val target = viewModel.adjacentPage(forward) ?: return@launch
            if (effectiveMode == PageTurnMode.NONE || size.width <= 0) {
                viewModel.showPage(target)
                return@launch
            }
            animPage = target
            animX.snapTo(if (forward) size.width.toFloat() else -size.width.toFloat())
            animX.animateTo(0f, tween(220))
            viewModel.showPage(target)
            animPage = null
        }
    }

    SystemBarEffects(menuVisible = menuVisible, keepScreenOn = prefs.keepScreenOn)
    BrightnessEffect(prefs.readerBrightness)

    LaunchedEffect(rawMode) {
        if (rawMode == PageTurnMode.SIMULATION) {
            Toast.makeText(context, "仿真翻页将在后续版本提供，已使用覆盖滑动", Toast.LENGTH_SHORT).show()
        }
        if (scrollMode) viewModel.enterScrollMode() else viewModel.relocate()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged {
                size = it
                viewModel.setViewport(
                    widthPx = it.width,
                    heightPx = it.height,
                    density = density.density,
                    scaledDensity = density.density * density.fontScale,
                )
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
                val page = uiState.page
                if (page != null) {
                    PageView(
                        page = page,
                        config = uiState.layoutConfig,
                        colors = colors,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val overlay = animPage
                    if (overlay != null) {
                        PageView(
                            page = overlay,
                            config = uiState.layoutConfig,
                            colors = colors,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = animX.value },
                        )
                    }
                }
            }
        }

        if (!uiState.loading && uiState.error == null) {
            if (prefs.showChapterTitle) {
                ReaderHeader(
                    chapterTitle = uiState.chapterTitle,
                    colors = colors,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                )
            }
            val battery by rememberBatteryPercent()
            val time by rememberClock()
            ReaderFooter(
                progressText = if (prefs.showPageProgress) formatPercent(uiState.progressFraction) else null,
                batteryText = if (prefs.showBattery && battery >= 0) "电量 $battery%" else null,
                timeText = if (prefs.showTime) time else null,
                colors = colors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
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
                onSetBrightness = viewModel::setReaderBrightness,
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
