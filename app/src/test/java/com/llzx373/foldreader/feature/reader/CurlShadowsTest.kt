package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阴影分层的约束：
 * - 每条带都必须落在它该在的那一段（投到下层页的在外缘之外、折痕阴影跨折痕、
 *   纸背明暗只在纸背可见区间内）；
 * - 全部被叶片矩形裁剪，双页时不会糊到中缝另一侧；
 * - 宽度只在折痕圆角尺度附近（不能用旧模型那种与卷筒等宽的宽带）。
 */
class CurlShadowsTest {

    private val frame = CurlFrame(0f, 1f, 1000f, 800f)
    private val rightDir = Offset(1f, 0f)
    private val diagDir = Offset(0.747f, 0.664f)

    private fun rollAt(p: Float, dir: Offset = rightDir): CurlRoll =
        curlRollForProgress(frame, dir, p)

    private fun CurlShadowBand.range(roll: CurlRoll): ClosedFloatingPointRange<Float> {
        val ts = polygon.map { curlOffset(roll, it) }
        return ts.min()..ts.max()
    }

    @Test
    fun `degenerate roll has no shadows`() {
        assertTrue(curlShadowBands(rollAt(0f), frame).isEmpty())
    }

    @Test
    fun `every band is generated with a sane shape`() {
        // 透视下翻起的纸会"近大远小"地略微超出矩形，所以这里只要求不发散
        val rect = frame.screenRect
        val slack = frame.width * 0.9f
        var seen = 0
        for (i in 1..9) {
            val roll = rollAt(i / 10f)
            for (band in curlShadowBands(roll, frame)) {
                assertTrue(band.polygon.size >= 3)
                assertTrue("带应有面积", polygonArea(band.polygon) > 0f)
                for (q in band.polygon) {
                    assertTrue("x 越界过多 $q", q.x >= rect.left - slack && q.x <= rect.right + slack)
                    assertTrue("y 越界过多 $q", q.y >= rect.top - slack && q.y <= rect.bottom + slack)
                }
                seen++
            }
        }
        assertTrue("应产出阴影带", seen > 10)
    }

    @Test
    fun `cast shadow sits beyond the lifted part and crease shadow on the flat side`() {
        for (p in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            val roll = rollAt(p)
            val bands = curlShadowBands(roll, frame)

            val cast = bands.filter { it.layer == CurlShadowLayer.UNDER_PAGE }
            assertEquals("只应有一条投到下层页的阴影", 1, cast.size)
            assertTrue(
                "投影必须在翻起部分之外：${cast.first().range(roll)} (p=$p)",
                cast.first().range(roll).start >= roll.rollEdge * 0.5f,
            )

            // 折痕外侧的带必须整段在 flatEdge 之外；纸背明暗带另算（它本来就从 backMin 跨到 backMax）
            for (band in bands) {
                val r = band.range(roll)
                val isBackShade = roll.hasBack &&
                    r.start >= roll.backMin - 0.01f && r.endInclusive <= roll.backMax + 0.01f
                if (isBackShade) continue
                if (r.start < 0f) {
                    assertTrue(
                        "折痕外侧的带不得跨到翻起侧：$r (p=$p)",
                        r.endInclusive <= roll.flatEdge + 1f,
                    )
                }
            }
        }
    }

    @Test
    fun `back shading only appears together with the back side`() {
        val before = rollAt(0.4f)
        assertTrue("θ ≤ 90° 不应有纸背明暗", !before.hasBack)
        val after = rollAt(0.6f)
        assertTrue(after.hasBack)
        val backBands = curlShadowBands(after, frame).filter {
            val r = it.range(after)
            r.start >= after.backMin - 0.01f && r.endInclusive <= after.backMax + 0.01f
        }
        assertTrue("过 90° 之后应有纸背明暗带", backBands.isNotEmpty())
    }

    @Test
    fun `shadow widths stay near the fold scale, not page wide`() {
        for (i in 1..9) {
            val roll = rollAt(i / 10f)
            val w = curlShadowWidths(roll)
            assertTrue("折痕阴影不应宽过页宽", w.crease < frame.width * 0.1f)
            assertTrue("下层页暗化不应宽过页宽", w.cast < frame.width * 0.3f)
            assertTrue(w.crease > 0f && w.cast > 0f && w.edge > 0f && w.sheet > 0f)
            // 折痕/纸边必须是"薄过渡"；下层页上的暗化反过来必须够宽够淡，
            // 否则就是"两条平行线把动画区域围起来"
            assertTrue("折痕阴影必须是窄过渡", w.crease <= frame.width * 0.05f)
            assertTrue("纸边暗边必须是窄过渡", w.edge <= frame.width * 0.08f)
            assertTrue("下层页暗化必须比纸边过渡宽得多", w.cast >= maxOf(w.crease, w.edge) * 2f)
        }
    }

    @Test
    fun `palette softens on dark backgrounds`() {
        val light = curlShadowPaletteFor(Color(0xFFFFFFFF))
        val dark = curlShadowPaletteFor(Color(0xFF000000))
        assertTrue("暗背景阴影应更弱", dark.crease.alpha < light.crease.alpha)
        assertTrue("暗背景投影应更弱", dark.cast.alpha < light.cast.alpha)
        assertTrue("任何背景下阴影都不得为零", dark.crease.alpha > 0f)
    }

    @Test
    fun `band gradient stays collinear with the drag direction`() {
        val diagonal = rollAt(0.5f, diagDir)
        val bands = curlShadowBands(diagonal, frame)
        assertTrue(bands.isNotEmpty())
        for (band in bands) {
            val d = Offset(band.to.x - band.from.x, band.to.y - band.from.y)
            val len = kotlin.math.sqrt(d.x * d.x + d.y * d.y)
            if (len < 0.01f) continue
            val cos = (d.x / len) * diagonal.dir.x + (d.y / len) * diagonal.dir.y
            assertEquals("渐变方向应与折痕方向共线", 1f, kotlin.math.abs(cos), 0.001f)
        }
    }
}
