package com.llzx373.foldreader.feature.reader

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 外壳（`NavigationSuiteScaffold` + 各页 `Scaffold`）真正参与布局的系统 inset：
 * `systemBars ∪ displayCutout`，即 material3 的 `systemBarsForVisualComponents`。
 *
 * 为什么不能只看 `systemBars`（真机实测，Xiaomi 阔折叠内屏横屏 ROTATION_270）：
 * ```
 * 沉浸中（系统栏隐藏） systemBars=(0,0,0,0)    displayCutout=(0,0,140,0)   外壳右侧被顶开 140px
 * 系统栏可见         systemBars=(0,140,0,0)  displayCutout=(0,0,0,0)     外壳右侧为 0
 * ```
 * 返回瞬间 `show(systemBars)` 已让 `systemBars` 达标，但 **displayCutout 的 140px 会再停留
 * ~500ms**：书架首帧按 right=140px 布局（content 2004），随后该值消失（content 2144）——
 * 左边缘不动、右边缘外扩 140px（50.9dp），表现为"封面与右上/右下按钮整体向右跳一次"。
 *
 * 因此门控以"系统栏可见时"的实测快照为期望值（沉浸前调用 [rememberVisibleReference]），
 * 而不是平台许诺的 ignoringVisibility 值。
 */
object ShellInsets {

    /** 外壳布局吃到的 inset 类型集合 */
    private val SHELL_TYPES: Int =
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    private var reference: InsetsSnapshot? = null

    /** 当前（按可见性）外壳 inset 快照 */
    fun current(view: View): InsetsSnapshot = snapshot(view, ignoringVisibility = false)

    /** 平台承诺的"系统栏完全可见"快照（无参考值时兜底） */
    fun ignoringVisibility(view: View): InsetsSnapshot = snapshot(view, ignoringVisibility = true)

    /** systemBars 单独一份，便于日志判读 */
    fun systemBars(view: View): InsetsSnapshot = typed(view, WindowInsetsCompat.Type.systemBars())

    /** displayCutout 单独一份，便于日志判读 */
    fun cutout(view: View): InsetsSnapshot = typed(view, WindowInsetsCompat.Type.displayCutout())

    /**
     * 记录"系统栏可见"时的外壳 inset：阅读页进入沉浸（`hide(systemBars)`）之前调用。
     * 返回书架时以它作为期望值——它正好等于书架在系统栏可见状态下的最终布局依据。
     */
    fun rememberVisibleReference(view: View) {
        reference = current(view)
    }

    fun visibleReference(): InsetsSnapshot? = reference

    private fun snapshot(view: View, ignoringVisibility: Boolean): InsetsSnapshot {
        val root = ViewCompat.getRootWindowInsets(view) ?: return InsetsSnapshot(0, 0, 0, 0)
        val insets = if (ignoringVisibility) {
            root.getInsetsIgnoringVisibility(SHELL_TYPES)
        } else {
            root.getInsets(SHELL_TYPES)
        }
        return InsetsSnapshot(insets.left, insets.top, insets.right, insets.bottom)
    }

    private fun typed(view: View, type: Int): InsetsSnapshot {
        val root = ViewCompat.getRootWindowInsets(view) ?: return InsetsSnapshot(0, 0, 0, 0)
        val insets = root.getInsets(type)
        return InsetsSnapshot(insets.left, insets.top, insets.right, insets.bottom)
    }
}

/**
 * "超出参考快照的额外 inset"——按四边取 `max(0, 实时 - 参考)`。
 *
 * 真机（Xiaomi 阔折叠内屏横屏）实测：**每次系统栏显隐变化（`hide`/`show`）之后，
 * 平台都会补报一次挖孔侧边 inset（140px=50.9dp），约 500ms 后才消失**。它出现得比
 * 门控的采样更晚（`show()` 后 ~50ms 才出现），所以"等 inset 到位再导航"堵不住：
 * 书架首帧正是在这段窗口内布局的（content 2004），500ms 后恢复（2144）→ 右边缘外扩、右跳。
 *
 * 做法：把这份"额外量"在**外壳根节点**消费掉（rail 与各页 Scaffold 都按参考态布局），
 * 于是返回窗口内书架的首帧就是终局；额外量归零后本对象自然失效（`max(0, …)`），
 * 因此不需要精确掐时间。只在"刚离开阅读页后的一小段窗口"启用，避免长期影响
 * （例如旋转后合法的新 inset 会被误抑制）。
 */
class ExtraOverReferenceInsets(
    private val live: WindowInsets,
    private val reference: InsetsSnapshot,
) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        (live.getLeft(density, layoutDirection) - reference.left).coerceAtLeast(0)

    override fun getTop(density: Density): Int =
        (live.getTop(density) - reference.top).coerceAtLeast(0)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        (live.getRight(density, layoutDirection) - reference.right).coerceAtLeast(0)

    override fun getBottom(density: Density): Int =
        (live.getBottom(density) - reference.bottom).coerceAtLeast(0)
}
