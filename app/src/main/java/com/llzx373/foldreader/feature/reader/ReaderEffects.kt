package com.llzx373.foldreader.feature.reader

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.llzx373.foldreader.core.debug.ReturnTrace
import kotlinx.coroutines.launch

/**
 * 沉浸外壳的窗口级副作用：文本阅读器与漫画阅读器共用，避免两处各写一份
 * 「什么时候隐藏系统栏 / 怎么还原亮度」的逻辑后逐渐走偏。
 */

/**
 * 系统栏统一策略：任何转场期间（进/出阅读、去往设置）系统栏保持可见且不变，
 * 对侧页面在转场每一帧拿到的 inset 都是最终值。仅当阅读页完全站稳、菜单关闭时
 * 才隐藏系统栏进入沉浸阅读。
 */
@Composable
internal fun SystemBarEffects(menuVisible: Boolean, keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(menuVisible) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        if (menuVisible) {
            // 不碰 behavior：退出路径由 leaveReader 先复位为 BEHAVIOR_DEFAULT 再 show，
            // 常驻栏 inset 在导航前到位；菜单期保持 transient 覆盖层，菜单布局不被推动
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            // 沉浸之前先把"系统栏可见"的外壳 inset 记账：返回门控以它为期望值
            // （displayCutout 在沉浸期间会上报额外的侧边 inset，平台不会随 show 立即清掉）
            ShellInsets.rememberVisibleReference(view)
            ReturnTrace.log(
                "reader immersive: reference=${ShellInsets.visibleReference()} " +
                    "systemBars=${ShellInsets.systemBars(view)} cutout=${ShellInsets.cutout(view)}",
            )
            // transient 模式：系统栏以覆盖层形式显隐，不改变 app 的 WindowInsets，
            // 阅读期间滑动唤出/隐藏系统栏都不会引发正文重排
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // behavior 是窗口级状态：不复位的话，transient 模式 show 出的系统栏
            // 会在几秒后自动隐藏（书架/设置页系统栏自己消失）
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
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

/** 应用内独立亮度：-1 表示跟随系统。离开阅读页时还原。 */
@Composable
internal fun BrightnessEffect(brightness: Float) {
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

/** [leaveTo] 带着落地门控导航到指定目的地；[requested] 表示已在离开流程中（系统栏需保持可见）。 */
@Stable
internal data class ReaderExit(
    val requested: Boolean,
    val leaveTo: (navigate: () -> Unit) -> Unit,
)

/**
 * 离开阅读页的落地门控（文本与漫画阅读器共用）。
 *
 * 先把系统栏恢复为常驻（BEHAVIOR_DEFAULT + show），再等外壳真正参与布局的 inset
 * （systemBars ∪ displayCutout）回到"系统栏可见时的实测值"并连续两帧稳定，之后才导航。
 *
 * 两个坑（都踩过）：
 *  1) 只看 systemBars 不够：挖孔屏横屏下 displayCutout 的侧边 inset 会晚 ~500ms 才消失，
 *     书架首帧会按带挖孔 inset 的宽度布局、随后右边缘外扩（封面/右上动作整体右跳）；
 *  2) 只看 ignoringVisibility 不够：displayCutout 的值不受显隐影响，平台会返回"沉浸中"
 *     的那个值，门控照样提前放行。所以以沉浸前的实测快照为准（ShellInsets.visibleReference）。
 */
@Composable
internal fun rememberReaderExit(): ReaderExit {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var requested by remember { mutableStateOf(false) }
    val leaveTo: (() -> Unit) -> Unit = remember(view) {
        { navigate ->
            if (!requested) {
                requested = true
                val window = (view.context as? Activity)?.window
                if (window == null) {
                    navigate()
                } else {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    val reference = ShellInsets.visibleReference()
                    val expected = reference ?: ShellInsets.ignoringVisibility(view)
                    val gate = BarsRestoreGate(expected)
                    ReturnTrace.log(
                        "leaveReader: bars shown reference=$reference expected=$expected " +
                            "systemBars=${ShellInsets.systemBars(view)} " +
                            "cutout=${ShellInsets.cutout(view)}",
                    )
                    scope.launch {
                        // 逐帧轮询而非 snapshotFlow：inset 稳定后 snapshotFlow 不再发射（去重），
                        // "连续两帧不变"永远等不到第二帧，门控会退化成盲等超时——慢设备上
                        // inset 动画晚于超时落地，目标页仍被挤压（横屏右缘导航栏即如此）
                        var frames = 0
                        val start = SystemClock.uptimeMillis()
                        var passed = false
                        // 正常 5 帧内（~100ms）即达标；超时只在厂商 inset 派发异常时触发，
                        // 届时宁可多等也不要带着未落地的 inset 导航（那正是书架整体右跳的来源）
                        while (SystemClock.uptimeMillis() - start < 800L) {
                            val snap = ShellInsets.current(view)
                            frames++
                            ReturnTrace.log("leaveReader gate frame#$frames shell=$snap")
                            if (gate.onFrame(snap)) {
                                passed = true
                                break
                            }
                            withFrameNanos { }
                        }
                        ReturnTrace.log(
                            if (passed) {
                                "leaveReader: gate passed after $frames frames, navigate"
                            } else {
                                "leaveReader: gate TIMEOUT($frames frames) " +
                                    "shell=${ShellInsets.current(view)} " +
                                    "systemBars=${ShellInsets.systemBars(view)} " +
                                    "cutout=${ShellInsets.cutout(view)}，navigate——若书架仍有跳动，看这里"
                            },
                        )
                        navigate()
                    }
                }
            }
        }
    }
    return ReaderExit(requested = requested, leaveTo = leaveTo)
}
