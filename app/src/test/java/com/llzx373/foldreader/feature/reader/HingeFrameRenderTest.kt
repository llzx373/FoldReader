package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 用 HingeSoftwareRenderer 离线渲染铰链翻页各进度帧到 app/build/curlFrames/，
 * 并断言模型级不变量：
 * - 双页（整程）：起点=当前跨页、终点=目标跨页（页背落到对侧页，收尾无缝）；
 *   前半程对侧页保持当前页不被破坏；后半程页背覆盖率单调增至全覆盖；
 *   前进/后退翻页互为水平镜像；转轴固定竖直（不随起手高度倾斜）；
 * - 单页（前半程）：起点=当前页、垂直位（p=0.5）=目标页（sheetFade 收尾淡出，
 *   落账无缝）；揭示覆盖率单调增；后退翻为同一几何逆放（交换纹理）。
 */
class HingeFrameRenderTest {

    private val outDir = File("build/curlFrames")

    private fun makePage(w: Int, h: Int, bg: Int, ink: Int, label: String): IntArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(bg, true)
        g.fillRect(0, 0, w, h)
        g.color = java.awt.Color(ink, true)
        g.font = g.font.deriveFont(w * 0.035f)
        g.drawString(label, (w * 0.04f).toInt(), (h * 0.05f).toInt())
        val lineH = (h * 0.035f).toInt()
        var y = (h * 0.10f).toInt()
        var row = 0
        while (y < h * 0.90f) {
            val blocks = 3 + (row % 3)
            var x = (w * 0.06f).toInt() + if (row % 4 == 0) (w * 0.10f).toInt() else 0
            for (b in 0 until blocks) {
                val bw = (w * (0.12f + 0.05f * ((row + b) % 3))).toInt()
                if (x + bw > w * 0.94f) break
                g.fillRoundRect(x, y, bw, lineH / 2, 4, 4)
                x += bw + (w * 0.03f).toInt()
            }
            y += (lineH * 1.8f).toInt()
            row++
        }
        g.drawString(label, (w * 0.04f).toInt(), (h * 0.97f).toInt())
        g.dispose()
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    /** 双页跨页纹理：左右半页各画一套（标注 L/R 便于观察镜像方向）。 */
    private fun makeSpread(w: Int, h: Int, bg: Int, ink: Int, tag: String): IntArray {
        val half = w / 2
        val left = makePage(half, h, bg, ink, "$tag-L")
        val right = makePage(half, h, bg, ink, "$tag-R")
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until half) {
                out[y * w + x] = left[y * half + x]
                out[y * w + half + x] = right[y * half + x]
            }
        }
        return out
    }

    private fun mirror(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = px[y * w + (w - 1 - x)]
            }
        }
        return out
    }

    private fun save(pixels: IntArray, w: Int, h: Int, name: String) {
        outDir.mkdirs()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(img, "png", File(outDir, "$name.png"))
    }

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        var diff = 0
        for (i in a.indices) {
            val dr = abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF))
            val dg = abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF))
            val db = abs((a[i] and 0xFF) - (b[i] and 0xFF))
            if (dr + dg + db > 24) diff++
        }
        return diff.toDouble() / a.size
    }

    private val bg = 0xFFC7EDCC.toInt()
    private val ink = 0xFF2B3A2E.toInt()

    private val w = 1560
    private val h = 1400

    private fun sheet(forward: Boolean) = hingeSheetFor(
        forward = forward, pageWidthPx = 780f,
        leftInsetPx = 0f, splitRightPx = 780f, rightInsetPx = 0f,
    )

    private fun renderer(front: IntArray, under: IntArray) = HingeSoftwareRenderer(w, h, front, under)

    private fun bendFor(p: Float, sheetW: Float) =
        creaseBend(Size(sheetW, h.toFloat())) * hingeBendFade(p)

    @Test
    fun `render dual forward flip frames`() {
        val front = makeSpread(w, h, bg, ink, "S9")
        val under = makeSpread(w, h, bg, ink, "S10")
        val r = renderer(front, under)
        val sheet = sheet(true)
        val progressList = listOf(0f, 0.03f, 0.15f, 0.35f, 0.5f, 0.65f, 0.85f, 0.97f, 1f)
        var first: IntArray? = null
        var last: IntArray? = null
        var early: IntArray? = null
        for (p in progressList) {
            r.setProgress(p, sheet, h / 2f, 0f, 1f)
            r.bend = bendFor(p, sheet.width)
            val frame = r.render()
            save(frame, w, h, "hinge_fwd_%02d".format((p * 100).toInt()))
            if (first == null) first = frame
            if (p == 0.35f) early = frame
            last = frame
        }
        assertTrue("起手帧应等于当前跨页", diffRatio(first!!, front) < 0.005)
        assertTrue("收尾帧应等于目标跨页（页背落到左页无缝）", diffRatio(last!!, under) < 0.005)
        // 前半程：左页静止不动（纸张还没转过垂直位）
        val half = w / 2
        val earlyLeft = IntArray(half * h) { i -> early!![(i / half) * w + i % half] }
        val frontLeft = IntArray(half * h) { i -> front[(i / half) * w + i % half] }
        assertTrue("前半程左页应保持当前页", diffRatio(earlyLeft, frontLeft) < 0.005)
        // 落页基本完成：p=0.97 左页≈目标左页（落地边前沿阴影带留 2% 容差）
        r.setProgress(0.97f, sheet, h / 2f, 0f, 1f)
        r.bend = bendFor(0.97f, sheet.width)
        val late = r.render()
        val lateLeft = IntArray(half * h) { i -> late[(i / half) * w + i % half] }
        val underLeft = IntArray(half * h) { i -> under[(i / half) * w + i % half] }
        assertTrue("p=0.97 左页应基本被页背覆盖", diffRatio(lateLeft, underLeft) < 0.02)
    }

    /**
     * 落地覆盖率单调性：用纯色纹理消除背景歧义——当前左页蓝色主导、目标左页
     * 绿色主导；明暗/阴影是乘性的，不改变主导通道，按 G>B 判定"已被页背覆盖"。
     */
    @Test
    fun `landing coverage sweeps monotonically across left page`() {
        val half = w / 2
        fun solid(color: Int): IntArray {
            val out = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    out[y * w + x] = if (x < half) color else 0xFFC7EDCC.toInt()
                }
            }
            return out
        }
        val front = solid(0xFF3C3C6E.toInt()) // 当前左页：蓝
        val under = solid(0xFF3C6E3C.toInt()) // 目标左页：绿
        val r = renderer(front, under)
        val sheet = sheet(true)
        fun coverage(frame: IntArray): Double {
            var covered = 0
            for (y in 0 until h) {
                for (x in 0 until half) {
                    val c = frame[y * w + x]
                    if (((c shr 8) and 0xFF) > (c and 0xFF)) covered++
                }
            }
            return covered.toDouble() / (half * h)
        }
        var prev = -1.0
        for (p in listOf(0.5f, 0.55f, 0.65f, 0.75f, 0.85f, 0.97f, 1f)) {
            r.setProgress(p, sheet, h / 2f, 0f, 1f)
            r.bend = bendFor(p, sheet.width)
            val cov = coverage(r.render())
            assertTrue("落地覆盖率应单调增：$prev -> $cov (p=$p)", cov >= prev - 0.01)
            prev = cov
        }
        assertTrue("收尾时左页应被页背全覆盖", prev > 0.98)
    }

    @Test
    fun `render dual backward flip frames`() {
        val front = makeSpread(w, h, bg, ink, "S10")
        val under = makeSpread(w, h, bg, ink, "S9")
        val r = renderer(front, under)
        val sheet = sheet(false)
        var first: IntArray? = null
        var last: IntArray? = null
        for (p in listOf(0f, 0.15f, 0.35f, 0.5f, 0.65f, 0.85f, 0.97f, 1f)) {
            r.setProgress(p, sheet, h / 2f, 0f, 1f)
            r.bend = bendFor(p, sheet.width)
            val frame = r.render()
            save(frame, w, h, "hinge_bwd_%02d".format((p * 100).toInt()))
            if (first == null) first = frame
            last = frame
        }
        assertTrue("后退起手帧应等于当前跨页", diffRatio(first!!, front) < 0.005)
        assertTrue("后退收尾帧应等于目标跨页", diffRatio(last!!, under) < 0.005)
    }

    @Test
    fun `forward and backward flips are mirror images`() {
        val front = makeSpread(w, h, bg, ink, "S9")
        val under = makeSpread(w, h, bg, ink, "S10")
        val mFront = mirror(front, w, h)
        val mUnder = mirror(under, w, h)
        for (p in listOf(0.15f, 0.4f, 0.6f, 0.9f)) {
            val bwd = renderer(front, under)
            bwd.setProgress(p, sheet(false), h / 2f, 0f, 1f)
            bwd.bend = bendFor(p, 780f)
            val bwdFrame = bwd.render()
            val fwdMirrored = renderer(mFront, mUnder)
            fwdMirrored.setProgress(p, sheet(true), h / 2f, 0f, 1f)
            fwdMirrored.bend = bendFor(p, 780f)
            val fwdFrameMirrored = mirror(fwdMirrored.render(), w, h)
            assertTrue(
                "p=$p 时后退翻页应是前进翻页的镜像",
                diffRatio(bwdFrame, fwdFrameMirrored) < 0.01,
            )
        }
    }

    /**
     * 双页转轴必须竖直对准书脊中缝：tilt=0 且无弯曲时，无论起手/点击高度如何，
     * 画面都应完全一致（轴不随 startY 倾斜）。
     */
    @Test
    fun `dual axis stays vertical regardless of start height`() {
        val front = makeSpread(w, h, bg, ink, "S9")
        val under = makeSpread(w, h, bg, ink, "S10")
        val sheet = sheet(true)
        for (p in listOf(0.15f, 0.5f, 0.85f)) {
            val top = renderer(front, under)
            top.setProgress(p, sheet, h * 0.2f, 0f, 1f)
            top.bend = 0f
            val bottom = renderer(front, under)
            bottom.setProgress(p, sheet, h * 0.8f, 0f, 1f)
            bottom.bend = 0f
            assertTrue(
                "p=$p 时不同起手高度的双页画面应一致（竖直轴）",
                diffRatio(top.render(), bottom.render()) < 0.0001,
            )
        }
        // 渲染几帧供目检（含弯曲，轴仍竖直）
        val r = renderer(front, under)
        for (p in listOf(0.15f, 0.5f, 0.85f)) {
            r.setProgress(p, sheet, h * 0.2f, 0f, 1f)
            r.bend = bendFor(p, sheet.width)
            save(r.render(), w, h, "hinge_tap_%02d".format((p * 100).toInt()))
        }
    }

    // ---------- 单页（前半程铰链，垂直位落账） ----------

    @Test
    fun `render single forward flip frames`() {
        val front = makePage(w, h, bg, ink, "P2")
        val under = makePage(w, h, bg, ink, "P3")
        val r = renderer(front, under)
        val sheet = singleHingeSheet(w.toFloat())
        var first: IntArray? = null
        var last: IntArray? = null
        for (p in listOf(0f, 0.05f, 0.15f, 0.3f, 0.45f, 0.5f)) {
            r.setProgress(p, sheet, h / 2f, 0f, hingeSliverFade(p))
            r.bend = bendFor(p, sheet.width)
            val frame = r.render()
            save(frame, w, h, "hinge1_fwd_%02d".format((p * 100).toInt()))
            if (first == null) first = frame
            last = frame
        }
        assertTrue("起手帧应等于当前页", diffRatio(first!!, front) < 0.005)
        assertTrue(
            "垂直位收尾帧应等于目标页（sheetFade 淡出窄条与投影，落账无缝）",
            diffRatio(last!!, under) < 0.005,
        )
    }

    @Test
    fun `render single backward flip frames`() {
        // 后退 = 同一几何逆放：纸张正面=翻入的上一页（front 传 P1），下层=当前页
        val paper = makePage(w, h, bg, ink, "P1")
        val under = makePage(w, h, bg, ink, "P2")
        val r = renderer(paper, under)
        val sheet = singleHingeSheet(w.toFloat())
        var first: IntArray? = null
        var last: IntArray? = null
        for (p in listOf(0.5f, 0.35f, 0.2f, 0.05f, 0f)) {
            r.setProgress(p, sheet, h / 2f, 0f, hingeSliverFade(p))
            r.bend = bendFor(p, sheet.width)
            val frame = r.render()
            save(frame, w, h, "hinge1_bwd_%02d".format((p * 100).toInt()))
            if (first == null) first = frame
            last = frame
        }
        assertTrue("后退起手帧（垂直位）应等于当前页", diffRatio(first!!, under) < 0.005)
        assertTrue("后退收尾帧应等于翻入的上一页", diffRatio(last!!, paper) < 0.005)
    }

    /**
     * 单页前进揭示覆盖率单调性：纯色纹理（当前页蓝、目标页绿），按 G>B 判定
     * "已揭示目标页"；sheetFade 收尾段的淡出只会把像素拉向目标页，不破坏单调性。
     */
    @Test
    fun `single reveal coverage sweeps monotonically`() {
        val front = IntArray(w * h) { 0xFF3C3C6E.toInt() } // 当前页：蓝
        val under = IntArray(w * h) { 0xFF3C6E3C.toInt() } // 目标页：绿
        val r = renderer(front, under)
        val sheet = singleHingeSheet(w.toFloat())
        fun coverage(frame: IntArray): Double {
            var covered = 0
            for (c in frame) {
                if (((c shr 8) and 0xFF) > (c and 0xFF)) covered++
            }
            return covered.toDouble() / frame.size
        }
        var prev = -1.0
        for (p in listOf(0f, 0.05f, 0.15f, 0.3f, 0.4f, 0.5f)) {
            r.setProgress(p, sheet, h / 2f, 0f, hingeSliverFade(p))
            r.bend = bendFor(p, sheet.width)
            val cov = coverage(r.render())
            assertTrue("揭示覆盖率应单调增：$prev -> $cov (p=$p)", cov >= prev - 0.01)
            prev = cov
        }
        assertTrue("垂直位落账时目标页应全揭示", prev > 0.99)
    }

    @Test
    fun `render single tap turn with vertical axis`() {
        // 单页转轴固定竖直、对齐页左缘：无弯曲时不同起手高度画面必须一致
        val front = makePage(w, h, bg, ink, "P2")
        val under = makePage(w, h, bg, ink, "P3")
        val sheet = singleHingeSheet(w.toFloat())
        for (p in listOf(0.1f, 0.25f, 0.4f)) {
            val top = renderer(front, under)
            top.setProgress(p, sheet, h * 0.2f, 0f, hingeSliverFade(p))
            top.bend = 0f
            val bottom = renderer(front, under)
            bottom.setProgress(p, sheet, h * 0.8f, 0f, hingeSliverFade(p))
            bottom.bend = 0f
            assertTrue(
                "p=$p 时不同起手高度的单页画面应一致（竖直轴）",
                diffRatio(top.render(), bottom.render()) < 0.0001,
            )
        }
        // 渲染几帧供目检（含弯曲，轴仍竖直）
        val r = renderer(front, under)
        val startY = h * 0.2f
        for (p in listOf(0.1f, 0.25f, 0.4f)) {
            r.setProgress(p, sheet, startY, 0f, hingeSliverFade(p))
            r.bend = bendFor(p, sheet.width)
            save(r.render(), w, h, "hinge1_tap_%02d".format((p * 100).toInt()))
        }
    }
}
