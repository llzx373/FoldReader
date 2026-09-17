package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 单页卷曲翻页的离线出图与收敛性断言。
 *
 * 输出到 `build/curlFrames/curl/`：单帧 + 两条对比图（水平起手 / 斜向起手）。
 * 两条硬断言对应计划里的收敛要求：**起手帧逐像素等于当前页**、**终点帧逐像素等于目标页**。
 */
class CurlFrameRenderTest {

    private val tileW = 600
    private val tileH = 540
    private val bg = 0xFFC7EDCC.toInt()
    private val ink = 0xFF2B3A2E.toInt()
    private val outDir = File("build/curlFrames/curl")

    private fun makePage(label: String): IntArray {
        val img = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = AwtColor(bg, true)
        g.fillRect(0, 0, tileW, tileH)
        g.color = AwtColor(ink, true)
        g.font = g.font.deriveFont(tileW * 0.055f)
        g.drawString(label, (tileW * 0.06f).toInt(), (tileH * 0.09f).toInt())
        val lineH = (tileH * 0.030f).toInt()
        var y = (tileH * 0.16f).toInt()
        var row = 0
        while (y < tileH * 0.90f) {
            val blocks = 2 + (row % 3)
            var x = (tileW * 0.08f).toInt() + if (row % 4 == 0) (tileW * 0.12f).toInt() else 0
            for (b in 0 until blocks) {
                val bw = (tileW * (0.18f + 0.07f * ((row + b) % 3))).toInt()
                if (x + bw > tileW * 0.92f) break
                g.fillRoundRect(x, y, bw, lineH / 2, 4, 4)
                x += bw + (tileW * 0.05f).toInt()
            }
            y += (lineH * 2.0f).toInt()
            row++
        }
        g.font = g.font.deriveFont(tileW * 0.05f)
        g.drawString(label, (tileW * 0.06f).toInt(), (tileH * 0.96f).toInt())
        g.dispose()
        return img.getRGB(0, 0, tileW, tileH, null, 0, tileW)
    }

    private fun save(pixels: IntArray, name: String) {
        outDir.mkdirs()
        val img = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, tileW, tileH, pixels, 0, tileW)
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    private fun saveSheet(rows: List<List<Pair<String, IntArray>>>, name: String) {
        outDir.mkdirs()
        val caption = 26
        val cols = rows.first().size
        val img = BufferedImage(
            tileW * cols,
            (tileH + caption) * rows.size,
            BufferedImage.TYPE_INT_ARGB,
        )
        val g = img.createGraphics()
        g.color = AwtColor(24, 26, 24)
        g.fillRect(0, 0, img.width, img.height)
        g.font = g.font.deriveFont(14f)
        for ((ri, row) in rows.withIndex()) {
            for ((ci, cell) in row.withIndex()) {
                val (cap, px) = cell
                val ox = ci * tileW
                val oy = ri * (tileH + caption)
                val tile = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
                tile.setRGB(0, 0, tileW, tileH, px, 0, tileW)
                g.drawImage(tile, ox, oy, null)
                g.color = AwtColor(232, 232, 232)
                g.drawString(cap, ox + 8, oy + tileH + 18)
            }
        }
        g.dispose()
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    private fun frame() = CurlFrame(0f, 1f, tileW.toFloat(), tileH.toFloat())

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        var diff = 0
        for (i in a.indices) {
            if (((a[i] shr 16) and 0xFF) != ((b[i] shr 16) and 0xFF) ||
                ((a[i] shr 8) and 0xFF) != ((b[i] shr 8) and 0xFF) ||
                (a[i] and 0xFF) != (b[i] and 0xFF)
            ) {
                diff++
            }
        }
        return diff.toDouble() / a.size
    }

    @Test
    fun `single page curl converges at both ends`() {
        val leaf = makePage("P2")
        val under = makePage("P3")
        val f = frame()
        val renderer = CurlSoftwareRenderer(
            width = tileW,
            height = tileH,
            leaf = leaf,
            under = under,
            // 纸背走实心纸色（不传 back 即用 pageBackArgb）
            pageBackArgb = 0xFFDCE8D8.toInt(),
            palette = curlShadowPaletteFor(Color(bg)),
        )

        val horizontal = Offset(1f, 0f)
        val diagonal = Offset(0.62f, 0.78f)

        assertTrue("起手帧必须逐像素等于当前页", diffRatio(renderer.render(curlRollForProgress(f, horizontal, 0f), f), leaf) == 0.0)
        assertTrue("终点帧必须逐像素等于目标页", diffRatio(renderer.render(curlRollForProgress(f, horizontal, 1f), f), under) == 0.0)
        assertTrue("斜向起手同样收敛", diffRatio(renderer.render(curlRollForProgress(f, diagonal, 1f), f), under) == 0.0)
    }

    @Test
    fun `render single page curl sheets`() {
        val leaf = makePage("P2")
        val under = makePage("P3")
        val f = frame()
        val renderer = CurlSoftwareRenderer(
            width = tileW,
            height = tileH,
            leaf = leaf,
            under = under,
            // 纸背走实心纸色（不传 back 即用 pageBackArgb）
            pageBackArgb = 0xFFDCE8D8.toInt(),
            palette = curlShadowPaletteFor(Color(bg)),
        )

        val dir = Offset(1f, 0f)
        val all = listOf(
            0f, 0.08f, 0.16f, 0.25f, 0.35f, 0.45f,
            0.55f, 0.65f, 0.75f, 0.85f, 0.93f, 1f,
        )
        all.chunked(6).forEachIndexed { block, ps ->
            val frames = ps.map { p ->
                val roll = curlRollForProgress(f, dir, p)
                val px = renderer.render(roll, f)
                save(px, "seq_%02d".format((p * 100).toInt()))
                "p=%.2f  θ=%.0f°  bend=%.0f  翻起=%.0f".format(
                    p,
                    roll.angle * 180f / Math.PI.toFloat(),
                    roll.bendRadius,
                    roll.length,
                ) to px
            }
            saveSheet(listOf(frames.take(3), frames.drop(3)), "curl_seq_$block")
        }

        // 斜向起手：折痕方向与参考录屏一致（从右上往左下抓）
        val diag = Offset(0.80f, -0.60f)
        all.chunked(6).forEachIndexed { block, ps ->
            val frames = ps.map { p ->
                val roll = curlRollForProgress(f, diag, p)
                val px = renderer.render(roll, f)
                save(px, "seqdiag_%02d".format((p * 100).toInt()))
                "斜向 p=%.2f  θ=%.0f°  翻起=%.0f".format(
                    p,
                    roll.angle * 180f / Math.PI.toFloat(),
                    roll.length,
                ) to px
            }
            saveSheet(listOf(frames.take(3), frames.drop(3)), "curl_diag_$block")
        }

        // 卷曲带随进度成形、末态消失
        var seen = 0
        for (p in all) {
            val roll = curlRollForProgress(f, dir, p)
            if (polygonArea(curlBandPolygon(roll, f, roll.flatEdge, roll.rollEdge)) > 0f) seen++
        }
        assertTrue("中间过程应看得到卷曲带", seen >= 3)
    }

    /** 出一张"术语解剖图"：把折痕、自由边、平铺页、翻起部分、下层页逐一点出来。 */
    @Test
    fun `render annotated anatomy sheet`() {
        val leaf = makePage("P2")
        val under = makePage("P3")
        val f = frame()
        val renderer = CurlSoftwareRenderer(
            width = tileW,
            height = tileH,
            leaf = leaf,
            under = under,
            pageBackArgb = 0xFFDCE8D8.toInt(),
            palette = curlShadowPaletteFor(Color(bg)),
        )
        val dir = Offset(1f, 0f)
        val p = 0.35f
        val roll = curlRollForProgress(f, dir, p)
        val px = renderer.render(roll, f)

        val img = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, tileW, tileH, px, 0, tileW)
        val g = img.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
        )
        g.font = g.font.deriveFont(13f)

        // 折痕：纸上"折"出来的那条线
        val creaseX = roll.crease.x
        // 自由边：纸的外缘，透视后会向外撑开
        val m = curlScale(roll, roll.length)
        val freeX = roll.viewPoint.x +
            ((creaseX + roll.frontMax) - roll.viewPoint.x) * m
        // 折痕圆角的外缘（纸边白带的外侧）
        val bendEdgeX = roll.viewPoint.x +
            ((creaseX + roll.bendRadius) - roll.viewPoint.x) * curlScale(roll, roll.bendLength)

        fun dashed(x: Float, color: java.awt.Color, label: String, y: Int) {
            g.color = color
            g.stroke = java.awt.BasicStroke(
                2f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
                10f, floatArrayOf(7f, 5f), 0f,
            )
            g.drawLine(x.toInt(), 0, x.toInt(), tileH)
            g.stroke = java.awt.BasicStroke(1f)
            val tx = (x + 6).toInt().coerceIn(4, tileW - 250)
            g.drawString(label, tx, y)
        }

        dashed(creaseX, java.awt.Color(0xE5, 0x39, 0x35), "① 折痕（折线）", 22)
        dashed(bendEdgeX, java.awt.Color(0xF9, 0xA8, 0x25), "② 圆角外缘（纸边白带在这条线内侧）", 44)
        dashed(freeX, java.awt.Color(0x1E, 0x88, 0xE5), "③ 自由边（整张纸的外缘）", 66)

        // 区域说明
        fun region(x: Int, y: Int, color: java.awt.Color, text: String) {
            g.color = java.awt.Color(255, 255, 255, 220)
            g.fillRoundRect(x - 4, y - 15, g.fontMetrics.stringWidth(text) + 10, 20, 6, 6)
            g.color = color
            g.drawString(text, x, y)
        }
        region(8, tileH - 54, java.awt.Color(0x15, 0x65, 0xC0), "④ 平铺页：还没掀起的当前页（逐像素不动）")
        region(creaseX.toInt() + 8, tileH - 88, java.awt.Color(0x2E, 0x7D, 0x32), "⑤ 翻起部分 = 折片")
        region(freeX.toInt() + 8, tileH - 122, java.awt.Color(0x6A, 0x1B, 0x9A), "⑥ 下层页（露出的下一页）")

        // 方向箭头
        g.color = java.awt.Color(0xD8, 0x1B, 0x60)
        val y0 = 130
        g.drawLine(120, y0, 260, y0)
        g.drawLine(260, y0, 250, y0 - 6)
        g.drawLine(260, y0, 250, y0 + 6)
        g.drawString("拖拽方向 dir（折痕 ⟂ dir）", 120, y0 - 10)

        saveImage(img, "curl_anatomy")
    }

    private fun saveImage(img: BufferedImage, name: String) {
        outDir.mkdirs()
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }
}
