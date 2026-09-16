package com.llzx373.foldreader.feature.reader

/** 系统栏四边 inset 快照（px）：left/top/right/bottom。 */
data class InsetsSnapshot(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isZero: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0
}

/**
 * 判断隐藏的系统栏恢复常驻是否已完全落地（此时导航，目标页首帧即最终布局，
 * 不会被晚到的 inset 挤压跳动）。
 *
 * 各边 inset 可能先后到位——竖屏 bottom 先到、横屏 right 后到、状态栏 top 也可能
 * 晚一帧——所以不能只盯某一边或"非零即放行"，必须等整组快照到达 [expected]
 * （系统栏完全可见时的值）且连续两帧不变。
 *
 * 调用方对每一帧调用一次 [onFrame]；返回 true 表示可以导航。
 * [expected] 全零（无系统栏设备）时第一帧即放行；动画异常永远到不了 [expected]
 * 时恒返回 false，由调用方超时兜底。
 */
class BarsRestoreGate(private val expected: InsetsSnapshot) {
    private var prev: InsetsSnapshot? = null

    fun onFrame(current: InsetsSnapshot): Boolean {
        if (expected.isZero) return true
        val done = current == expected && prev == expected
        prev = current
        return done
    }
}
