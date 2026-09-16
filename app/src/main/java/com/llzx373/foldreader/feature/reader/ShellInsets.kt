package com.llzx373.foldreader.feature.reader

import android.view.View
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
