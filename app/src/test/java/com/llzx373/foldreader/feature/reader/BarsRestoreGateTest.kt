package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回书架跳动的回归测试：系统栏从隐藏恢复常驻时，四边 inset 逐帧派发且
 * 可能先后到位。门控必须等整组快照到达目标值（完全可见时的 inset）并连续
 * 两帧稳定才放行导航，否则目标页会先按 inset=0 布局再被挤压（竖屏 FAB 下跳、
 * 横屏右侧图标/封面右跳的来源）。
 */
class BarsRestoreGateTest {

    private fun BarsRestoreGate.feedAll(vararg frames: InsetsSnapshot): List<Boolean> =
        frames.map { onFrame(it) }

    @Test
    fun `无系统栏设备第一帧即放行`() {
        val gate = BarsRestoreGate(InsetsSnapshot(0, 0, 0, 0))
        assertTrue(gate.onFrame(InsetsSnapshot(0, 0, 0, 0)))
    }

    @Test
    fun `竖屏三键导航 top 和 bottom 同步渐变 到位后两帧才放行`() {
        val expected = InsetsSnapshot(0, 24, 0, 48)
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 0, 0, 0),
            InsetsSnapshot(0, 12, 0, 24),
            InsetsSnapshot(0, 24, 0, 48),
            // 刚到达目标值的第一帧仍不放行（防止抖动）
            InsetsSnapshot(0, 24, 0, 48),
        )
        assertEquals(listOf(false, false, false, true), results)
    }

    @Test
    fun `横屏导航栏在右缘 right 晚到时 bottom 先稳定也不放行`() {
        // 回归：旧逻辑只看 right/bottom 且 "非零即放行"，bottom 先到顶就导航，
        // right 随后才到 → 书架右缘被晚到的 inset 挤压（图标/封面右跳）
        val expected = InsetsSnapshot(0, 24, 96, 48)
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 0, 0, 0),
            InsetsSnapshot(0, 24, 0, 48), // bottom/top 到位，right 还没来
            InsetsSnapshot(0, 24, 0, 48), // 部分稳定两帧，仍不得放行
            InsetsSnapshot(0, 24, 48, 48),
            InsetsSnapshot(0, 24, 96, 48), // 全部到位第一帧
            InsetsSnapshot(0, 24, 96, 48), // 连续第二帧才放行
        )
        assertEquals(listOf(false, false, false, false, false, true), results)
    }

    @Test
    fun `状态栏 top 晚到时其余边到齐也不放行`() {
        // 回归：旧逻辑不看 top，状态栏晚到导致 LargeTopAppBar 长高、内容下移
        // （竖屏 FAB 向下跳一下的来源）
        val expected = InsetsSnapshot(0, 24, 0, 48)
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 0, 0, 48),
            InsetsSnapshot(0, 0, 0, 48), // bottom 稳定但 top 缺失
            InsetsSnapshot(0, 12, 0, 48),
            InsetsSnapshot(0, 24, 0, 48),
            InsetsSnapshot(0, 24, 0, 48),
        )
        assertEquals(listOf(false, false, false, false, true), results)
    }

    @Test
    fun `系统栏本就没隐藏时第二帧即放行`() {
        // 阅读页菜单打开等场景系统栏一直可见：首帧已是目标值，下一帧放行
        val expected = InsetsSnapshot(0, 24, 0, 48)
        val gate = BarsRestoreGate(expected)
        assertFalse(gate.onFrame(expected))
        assertTrue(gate.onFrame(expected))
    }

    @Test
    fun `到达目标值一帧后跳走需重新连续两帧`() {
        val expected = InsetsSnapshot(0, 24, 0, 48)
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 24, 0, 48), // 到达
            InsetsSnapshot(0, 20, 0, 48), // 又跳走（动画回弹）
            InsetsSnapshot(0, 24, 0, 48),
            InsetsSnapshot(0, 24, 0, 48),
        )
        assertEquals(listOf(false, false, false, true), results)
    }

    @Test
    fun `动画异常永远到不了目标值时恒不放行 由超时兜底`() {
        val expected = InsetsSnapshot(0, 24, 0, 48)
        val gate = BarsRestoreGate(expected)
        repeat(20) { frame ->
            assertFalse(gate.onFrame(InsetsSnapshot(0, 0, 0, frame % 3)))
        }
    }

    @Test
    fun `全零快照连续出现不放行`() {
        // show() 刚发出、inset 还没开始派发：全零稳定两帧也不许放行
        val expected = InsetsSnapshot(0, 24, 0, 48)
        val gate = BarsRestoreGate(expected)
        assertFalse(gate.onFrame(InsetsSnapshot(0, 0, 0, 0)))
        assertFalse(gate.onFrame(InsetsSnapshot(0, 0, 0, 0)))
    }

    @Test
    fun `挖孔侧边 inset 晚消失时不放行（真机返回右跳的根因序列）`() {
        // 真机 Xiaomi 阔折叠内屏横屏实测：
        //   沉浸中      systemBars=(0,0,0,0)   displayCutout=(0,0,140,0)
        //   show 之后   systemBars=(0,140,0,0) displayCutout=(0,0,140,0)  ← 停留约 500ms
        //   ~500ms 后   displayCutout=(0,0,0,0)
        // 门控比较的是 systemBars ∪ displayCutout（外壳布局的依据），所以这 500ms 必须等满；
        // 旧实现只看 systemBars，会在第二帧就放行 → 书架首帧带 140px 右 inset，随后右边缘
        // 外扩（content 2004→2144），即"封面与右上/右下按钮整体向右跳"。
        val expected = InsetsSnapshot(0, 140, 0, 0) // 沉浸前实测的"系统栏可见"快照
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 0, 0, 0),
            InsetsSnapshot(0, 140, 0, 140),
            InsetsSnapshot(0, 140, 0, 140), // 稳定但 ≠ 参考值：不得放行
            InsetsSnapshot(0, 140, 0, 140),
            InsetsSnapshot(0, 140, 0, 0),
            InsetsSnapshot(0, 140, 0, 0), // 连续第二帧才放行
        )
        assertEquals(listOf(false, false, false, false, false, true), results)
    }

    @Test
    fun `左侧导航栏布局 left 到位才算完成`() {        // RTL / 部分设备导航栏在左
        val expected = InsetsSnapshot(96, 24, 0, 0)
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 24, 0, 0),
            InsetsSnapshot(48, 24, 0, 0),
            InsetsSnapshot(96, 24, 0, 0),
            InsetsSnapshot(96, 24, 0, 0),
        )
        assertEquals(listOf(false, false, false, true), results)
    }

    @Test
    fun `手势导航小 inset 也需精确到位`() {
        // 手势导航 bottom 很小，但同样要精确匹配目标值
        val expected = InsetsSnapshot(0, 24, 0, 16)
        val gate = BarsRestoreGate(expected)
        val results = gate.feedAll(
            InsetsSnapshot(0, 24, 0, 24), // 经过比目标大的中间值（先回弹再稳定）
            InsetsSnapshot(0, 24, 0, 16),
            InsetsSnapshot(0, 24, 0, 16),
        )
        assertEquals(listOf(false, false, true), results)
    }
}
