package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlShaderTest {

    private val pageSize = Size(1000f, 2000f)

    // ---------- 共用 ----------

    @Test
    fun `crease bend scales with aspect ratio`() {
        assertEquals(0.24f * 1000f / 2000f, creaseBend(pageSize), 0.0001f)
    }

    @Test
    fun `bend fade is zero at rest and full mid flight`() {
        assertEquals(0f, hingeBendFade(0f), 0.001f)
        assertEquals(1f, hingeBendFade(0.5f), 0.001f)
        // 弯曲尾部同样淡出：双页 p=1 时自由边必须精确贴合对侧页（落账无残留）
        assertEquals(0f, hingeBendFade(1f), 0.001f)
    }

    // ---------- 双页铰链 ----------

    @Test
    fun `hinge sheet spine sits midway between page inner edges`() {
        // 2000 宽，铰链缝 900..1100：页宽 900，左页 [0,900)，右页 [1100,2000)
        val fwd = hingeSheetFor(
            forward = true, pageWidthPx = 900f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 0f,
        )
        assertEquals(1000f, fwd.spineX, 0.001f) // 两页内缘 900/1100 的中点
        assertEquals(100f, fwd.inner, 0.001f) // 铰链缝半宽
        assertEquals(900f, fwd.width, 0.001f)
        assertEquals(1f, fwd.side, 0.001f)
        assertEquals(1000f, fwd.reach, 0.001f)

        val bwd = hingeSheetFor(
            forward = false, pageWidthPx = 900f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 0f,
        )
        assertEquals(1000f, bwd.spineX, 0.001f)
        assertEquals(-1f, bwd.side, 0.001f)
    }

    @Test
    fun `hinge sheet at rest covers exactly its own page`() {
        // 有边距的非对称布局：页宽取窄侧，左右边距不同
        val fwd = hingeSheetFor(
            forward = true, pageWidthPx = 600f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 100f,
        )
        // 右页 [1200, 1800)，左页 [0, 600)：轴心 = (600+1200)/2 = 900
        assertEquals(900f, fwd.spineX, 0.001f)
        assertEquals(300f, fwd.inner, 0.001f)
        // p=0 纸张覆盖 [spine+inner, spine+reach] = 右页区间
        assertEquals(1200f, fwd.spineX + fwd.inner, 0.001f)
        assertEquals(1800f, fwd.spineX + fwd.reach, 0.001f)
        // p=1 页背覆盖 [spine-reach, spine-inner] = 左页区间（页背落到左页）
        assertEquals(0f, fwd.spineX - fwd.reach, 0.001f)
        assertEquals(600f, fwd.spineX - fwd.inner, 0.001f)

        val bwd = hingeSheetFor(
            forward = false, pageWidthPx = 600f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 100f,
        )
        // p=0 覆盖左页，p=1 页背落到右页
        assertEquals(600f, bwd.spineX - bwd.inner, 0.001f)
        assertEquals(0f, bwd.spineX - bwd.reach, 0.001f)
        assertEquals(1200f, bwd.spineX + bwd.inner, 0.001f)
        assertEquals(1800f, bwd.spineX + bwd.reach, 0.001f)
    }

    @Test
    fun `hinge progress follows free edge and inverts it`() {
        val sheet = hingeSheetFor(
            forward = true, pageWidthPx = 900f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 0f,
        )
        // 自由边在纸张外缘 -> p=0；到轴心正上方 -> p=0.5；落到对侧外缘 -> p=1
        assertEquals(0f, hingeProgressFor(sheet.spineX + sheet.reach, sheet), 0.001f)
        assertEquals(0.5f, hingeProgressFor(sheet.spineX, sheet), 0.001f)
        assertEquals(1f, hingeProgressFor(sheet.spineX - sheet.reach, sheet), 0.001f)
        // 越过端点钳制
        assertEquals(0f, hingeProgressFor(sheet.spineX + 5000f, sheet), 0.001f)
        assertEquals(1f, hingeProgressFor(sheet.spineX - 5000f, sheet), 0.001f)
        // 与 hingeFreeEdgeX 互逆
        for (p in listOf(0f, 0.13f, 0.5f, 0.77f, 1f)) {
            assertEquals(p, hingeProgressFor(hingeFreeEdgeX(sheet, p), sheet), 0.001f)
        }
        // 后退翻（side=-1）同一套互逆关系
        val bwd = hingeSheetFor(
            forward = false, pageWidthPx = 900f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 0f,
        )
        for (p in listOf(0f, 0.33f, 0.5f, 0.92f, 1f)) {
            assertEquals(p, hingeProgressFor(hingeFreeEdgeX(bwd, p), bwd), 0.001f)
        }
    }

    @Test
    fun `hinge anchor puts free edge under finger at grab`() {
        val sheet = hingeSheetFor(
            forward = true, pageWidthPx = 900f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 0f,
        )
        for (grabX in listOf(1200f, 1600f, 1950f)) {
            val anchor = hingeAnchorOffsetX(sheet, grabX)
            // 锚定后手指位置映射回 p=0（自由边=纸张外缘）
            assertEquals(0f, hingeProgressFor(grabX + anchor, sheet), 0.001f)
        }
    }

    @Test
    fun `hinge free edge sweeps monotonically across both pages`() {
        val sheet = hingeSheetFor(
            forward = true, pageWidthPx = 900f,
            leftInsetPx = 0f, splitRightPx = 1100f, rightInsetPx = 0f,
        )
        var prev = Float.MAX_VALUE
        for (i in 0..20) {
            val x = hingeFreeEdgeX(sheet, i / 20f)
            assertTrue("自由边应单调左移", x <= prev + 0.001f)
            prev = x
        }
        assertEquals(sheet.spineX + sheet.reach, hingeFreeEdgeX(sheet, 0f), 0.01f)
        assertEquals(sheet.spineX - sheet.reach, hingeFreeEdgeX(sheet, 1f), 0.01f)
    }

    @Test
    fun `hinge angle is pi times progress and clamps`() {
        assertEquals(0f, hingeAngle(0f), 0.001f)
        assertEquals(Math.PI.toFloat() / 2f, hingeAngle(0.5f), 0.001f)
        assertEquals(Math.PI.toFloat(), hingeAngle(1f), 0.001f)
        assertEquals(Math.PI.toFloat(), hingeAngle(2f), 0.001f)
        assertEquals(0f, hingeAngle(-1f), 0.001f)
    }

    // ---------- 单页铰链（前半程） ----------

    @Test
    fun `single hinge sheet rotates around left page edge`() {
        val sheet = singleHingeSheet(1000f)
        assertEquals(0f, sheet.spineX, 0.001f)
        assertEquals(0f, sheet.inner, 0.001f)
        assertEquals(1000f, sheet.width, 0.001f)
        assertEquals(1f, sheet.side, 0.001f)
        // p=0 纸张 1:1 覆盖整页；p=0.5（垂直位）自由边收回轴心，纸张消失
        assertEquals(1000f, hingeFreeEdgeX(sheet, 0f), 0.01f)
        assertEquals(0f, hingeFreeEdgeX(sheet, 0.5f), 0.01f)
    }

    @Test
    fun `single hinge free edge spans full width during first half`() {
        // 自由边全程随 p 从页右缘单调移到轴心——拖拽始终跟手（无后半程出屏问题）
        val sheet = singleHingeSheet(1000f)
        var prev = Float.MAX_VALUE
        for (i in 0..10) {
            val x = hingeFreeEdgeX(sheet, i / 20f) // p ∈ [0, 0.5]
            assertTrue("自由边应单调左移", x <= prev + 0.001f)
            assertTrue("自由边应在页内", x in -0.01f..1000.01f)
            prev = x
        }
    }

    @Test
    fun `single hinge progress clamps to half turn`() {
        val sheet = singleHingeSheet(1000f)
        // maxProgress=0.5：手指拖过轴心后钳在垂直位
        assertEquals(0f, hingeProgressFor(1000f, sheet, maxProgress = 0.5f), 0.001f)
        assertEquals(0.5f, hingeProgressFor(0f, sheet, maxProgress = 0.5f), 0.001f)
        assertEquals(0.5f, hingeProgressFor(-500f, sheet, maxProgress = 0.5f), 0.001f)
        assertEquals(0f, hingeProgressFor(3000f, sheet, maxProgress = 0.5f), 0.001f)
        // 与 hingeFreeEdgeX 在前半程互逆
        for (p in listOf(0f, 0.1f, 0.25f, 0.4f, 0.5f)) {
            assertEquals(
                p,
                hingeProgressFor(hingeFreeEdgeX(sheet, p), sheet, maxProgress = 0.5f),
                0.001f,
            )
        }
    }

    @Test
    fun `single backward anchors at vertical position`() {
        // 后退起手：自由边在轴心（p=0.5），手指右拖逐渐落回 p=0
        val sheet = singleHingeSheet(1000f)
        val grabX = 300f
        val anchor = hingeFreeEdgeX(sheet, 0.5f) - grabX
        assertEquals(0.5f, hingeProgressFor(grabX + anchor, sheet, maxProgress = 0.5f), 0.001f)
        // 拖到页右缘 -> p=0（纸张完全落下）；向左反拖 -> 钳在 0.5
        assertEquals(
            0f,
            hingeProgressFor(grabX + anchor + 1000f, sheet, maxProgress = 0.5f),
            0.001f,
        )
        assertEquals(
            0.5f,
            hingeProgressFor(grabX + anchor - 500f, sheet, maxProgress = 0.5f),
            0.001f,
        )
    }

    @Test
    fun `catch-up anchor decays to zero within 35 percent of reach`() {
        assertEquals(400f, hingeCatchUpAnchor(400f, 0f, 1000f), 0.001f)
        // 反向拖拽不衰减
        assertEquals(400f, hingeCatchUpAnchor(400f, -100f, 1000f), 0.001f)
        // 中途线性衰减
        val mid = hingeCatchUpAnchor(400f, 175f, 1000f)
        assertTrue(mid in 100f..300f)
        // 到达 35% 行程后归零（自由边精确贴手指）
        assertEquals(0f, hingeCatchUpAnchor(400f, 350f, 1000f), 0.001f)
        assertEquals(0f, hingeCatchUpAnchor(400f, 900f, 1000f), 0.001f)
    }

    @Test
    fun `free edge catches up to finger then sticks to it`() {
        // 单页前进：页中部（x=600）起手，向左拖到轴心
        val sheet = singleHingeSheet(1000f)
        val grabX = 600f
        val anchor = hingeFreeEdgeX(sheet, 0f) - grabX // = 400
        var prevP = -1f
        var fingerX = grabX
        // 起手瞬间：自由边仍在页缘（无跳变）
        val p0 = hingeProgressFor(fingerX + anchor, sheet, maxProgress = 0.5f)
        assertEquals(0f, p0, 0.001f)
        while (fingerX >= 0f) {
            val traveled = grabX - fingerX
            val effAnchor = hingeCatchUpAnchor(anchor, traveled, sheet.reach)
            val p = hingeProgressFor(fingerX + effAnchor, sheet, maxProgress = 0.5f)
            assertTrue("进度应单调不减", p >= prevP - 0.001f)
            prevP = p
            if (traveled >= sheet.reach * 0.35f) {
                // 追上后：渲染出的自由边 x 恰好是触点 x
                assertEquals(fingerX, hingeFreeEdgeX(sheet, p), 0.5f)
            }
            fingerX -= 25f
        }
    }

    @Test
    fun `free edge catches up to finger on single backward drag`() {
        // 单页后退：从垂直位起手，向右拖，自由边追上手指后贴在触点下
        val sheet = singleHingeSheet(1000f)
        val grabX = 300f
        val anchor = hingeFreeEdgeX(sheet, 0.5f) - grabX // = -300
        var prevP = Float.MAX_VALUE
        var fingerX = grabX
        while (fingerX <= 1000f) {
            val traveled = fingerX - grabX
            val effAnchor = hingeCatchUpAnchor(anchor, traveled, sheet.reach)
            val p = hingeProgressFor(fingerX + effAnchor, sheet, maxProgress = 0.5f)
            assertTrue("进度应单调不增", p <= prevP + 0.001f)
            prevP = p
            if (traveled >= sheet.reach * 0.35f) {
                assertEquals(fingerX, hingeFreeEdgeX(sheet, p), 0.5f)
            }
            fingerX += 25f
        }
    }

    @Test
    fun `sliver fade is full mid flight and zero at vertical`() {
        assertEquals(1f, hingeSliverFade(0f), 0.001f)
        assertEquals(1f, hingeSliverFade(0.3f), 0.001f)
        assertEquals(0f, hingeSliverFade(0.5f), 0.001f)
        // 单调不增
        var prev = Float.MAX_VALUE
        for (i in 0..20) {
            val f = hingeSliverFade(i / 40f) // p ∈ [0, 0.5]
            assertTrue(f <= prev + 0.001f)
            prev = f
        }
    }

    @Test
    fun `zero tilt gives vertical axis aligned with spine divider`() {
        val axis = hingeAxis(0f)
        assertEquals(0f, axis.x, 0.001f)
        assertEquals(1f, axis.y, 0.001f)
    }
}
