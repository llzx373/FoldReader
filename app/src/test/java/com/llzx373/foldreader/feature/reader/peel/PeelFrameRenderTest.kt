package com.llzx373.foldreader.feature.reader.peel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 用说明书公式把单页、横向双页各 9 帧画成 PNG，供观感审查。
 *
 * 正文按叶宽断行并把满行撑到左右页边（上次 demo 短句靠左、右侧大块留白）。
 *
 * 输出目录：模块的 `build/peel-frames/`（Gradle 在 app 模块下跑时即 `app/build/peel-frames`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PeelFrameRenderTest {

    private val singleW = 1080
    private val singleH = 2340
    private val dualW = 2340
    private val dualH = 1600
    private val dualHinge = 48
    private val dualPageW = (dualW - dualHinge) / 2

    private val currentBg = 0xFFF5EBD7.toInt()
    private val currentRightBg = 0xFFF0E4C8.toInt()
    private val nextLeftBg = 0xFFE4D4B4.toInt()
    private val nextBg = 0xFFC5DCC8.toInt()
    private val currentText = 0xFF3A2F1B.toInt()
    private val nextText = 0xFF1B2A1B.toInt()
    private val boardBg = 0xFF2C2418.toInt()

    private val currentBody =
        "翻起的那片纸必须是摊平的镜像，不能被透视压成一条窄带。折痕是二次贝塞尔曲线，立体感全部交给阴影而不是透视收缩。这一段正文会按叶宽自动断行，并且把每个满行两端撑到左右页边，用来检查镜像之后字距会不会乱、行宽够不够铺满整叶。"
    private val nextBody =
        "下层页从折痕右侧的口袋里露出来。这块底色和当前页不同，翻到位之后整叶都换成这一面。同样按整行铺满，方便对照翻起前后的行宽，也方便看出口袋里到底是哪一页。"
    private val leftBody =
        "左叶前半段停在这里。右叶绕中缝翻过来之后，纸背会盖住这一页，变成下一开的左页。正文铺满整行，用来看后半段扫过左栏时字是否还在、装订边有没有被撕开。"
    private val nextLeftBody =
        "这是下一开的左页，也就是被掀那张纸的背面。翻过中缝之后应该正着出现在左边，而不是当前右页的镜像。用这块略深的底色，方便看出纸背已经盖到左叶上。"

    @Test
    fun `写出单页与横向双页顺序帧 PNG`() {
        val outDir = framesDir().apply { mkdirs() }

        val singleCurrent = fakePage(
            width = singleW,
            height = singleH,
            title = "单页  ·  当前页",
            body = currentBody,
            bg = currentBg,
            textColor = currentText,
            footer = "—  7  —",
        )
        val singleNext = fakePage(
            width = singleW,
            height = singleH,
            title = "单页  ·  下一页",
            body = nextBody,
            bg = nextBg,
            textColor = nextText,
            footer = "—  8  —",
        )
        val singleFiles = writeSequence(
            outDir = outDir,
            prefix = "single",
            alsoAsLegacy = true,
            width = singleW,
            height = singleH,
            current = singleCurrent,
            next = singleNext,
            backgroundArgb = currentBg,
            density = 3f,
        )
        writeDragMid(
            outDir = outDir,
            name = "single-drag-mid-right.png",
            width = singleW,
            height = singleH,
            current = singleCurrent,
            next = singleNext,
            backgroundArgb = currentBg,
            density = 3f,
            rawTouch = Offset(singleW - 8f, singleH * 0.55f),
        )

        val (leftLeaf, rightLeaf) = peelLeaves(
            dual = true,
            contentWidth = dualW.toFloat(),
            contentHeight = dualH.toFloat(),
            pageWidth = dualPageW.toFloat(),
            splitLeft = dualPageW.toFloat(),
            splitRight = (dualPageW + dualHinge).toFloat(),
        )
        checkNotNull(rightLeaf)
        val leftPage = fakePage(
            width = dualPageW,
            height = dualH,
            title = "左页  ·  第 12 页",
            body = leftBody,
            bg = currentBg,
            textColor = currentText,
            footer = "—  12  —",
        )
        val rightCurrent = fakePage(
            width = dualPageW,
            height = dualH,
            title = "右页  ·  第 13 页",
            body = currentBody,
            bg = currentRightBg,
            textColor = currentText,
            footer = "—  13  —",
        )
        val rightNext = fakePage(
            width = dualPageW,
            height = dualH,
            title = "右页  ·  第 15 页",
            body = nextBody,
            bg = nextBg,
            textColor = nextText,
            footer = "—  15  —",
        )
        val leftNext = fakePage(
            width = dualPageW,
            height = dualH,
            title = "左页  ·  第 14 页",
            body = nextLeftBody,
            bg = nextLeftBg,
            textColor = currentText,
            footer = "—  14  —",
        )
        val dualFiles = writeDualSequence(
            outDir = outDir,
            leftLeaf = leftLeaf,
            rightLeaf = rightLeaf,
            leftPage = leftPage,
            rightCurrent = rightCurrent,
            rightNext = rightNext,
            leftNext = leftNext,
        )
        writeDualDragMid(
            outDir = outDir,
            leftLeaf = leftLeaf,
            rightLeaf = rightLeaf,
            leftPage = leftPage,
            rightCurrent = rightCurrent,
            rightNext = rightNext,
            leftNext = leftNext,
        )

        assertEquals(9, singleFiles.size)
        assertEquals(9, dualFiles.size)
        singleFiles.forEach { assertPng(it, singleW, singleH) }
        dualFiles.forEach { assertPng(it, dualW, dualH) }

        assertLineFillsWidth(singleCurrent, yHint = firstBodyBaseline(singleW), bg = currentBg)
        assertLineFillsWidth(leftPage, yHint = firstBodyBaseline(dualPageW), bg = currentBg)
        assertLineFillsWidth(rightCurrent, yHint = firstBodyBaseline(dualPageW), bg = currentRightBg)

        val midSingle = BitmapFactory.decodeFile(singleFiles[4].absolutePath)
        val parchment = midSingle.getPixel(16, 16)
        val revealed = midSingle.getPixel(singleW - 80, singleH - 120)
        assertTrue(
            "top-left should stay current page, was ${Integer.toHexString(parchment)}",
            colorNear(parchment, currentBg, slop = 40),
        )
        assertTrue(
            "bottom-right pocket should show next page, was ${Integer.toHexString(revealed)}",
            colorNear(revealed, nextBg, slop = 50) || colorNear(revealed, currentBg, slop = 80),
        )
        val singleSpan = creaseSpanAlongBottom(midSingle, singleH - 40)
        assertTrue("single crease/flap span was $singleSpan px", singleSpan > 200)
        val flapTouch = autoPlayTouch(0.5f, PeelCorner.BOTTOM_RIGHT, singleW.toFloat(), singleH.toFloat())
        val flapPx = midSingle.getPixel(flapTouch.x.toInt(), flapTouch.y.toInt())
        assertTrue(
            "single-page flap should be blank paper, was ${Integer.toHexString(flapPx)}",
            colorNear(flapPx, currentBg, slop = 70) && !colorNear(flapPx, currentText, slop = 80),
        )
        midSingle.recycle()

        val midDual = BitmapFactory.decodeFile(dualFiles[4].absolutePath)
        val leftStay = midDual.getPixel(16, 16)
        assertTrue(
            "dual left leaf should stay put, was ${Integer.toHexString(leftStay)}",
            colorNear(leftStay, currentBg, slop = 40),
        )
        val justLeftOfHinge = midDual.getPixel(leftLeaf.width.toInt() - 24, dualH - 80)
        assertTrue(
            "crease must not cross the spine, was ${Integer.toHexString(justLeftOfHinge)}",
            colorNear(justLeftOfHinge, currentBg, slop = 50),
        )
        val rightOrigin = rightLeaf.originX.toInt()
        val dualRevealed = midDual.getPixel(rightOrigin + dualPageW - 80, dualH - 80)
        assertTrue(
            "dual right pocket should show next page, was ${Integer.toHexString(dualRevealed)}",
            colorNear(dualRevealed, nextBg, slop = 50) || colorNear(dualRevealed, currentRightBg, slop = 80),
        )
        val dualSpan = creaseSpanAlongBottom(
            bitmap = midDual,
            y = dualH - 40,
            xStart = rightOrigin + 20,
            xEnd = rightOrigin + dualPageW - 10,
        )
        assertTrue("dual right-leaf crease/flap span was $dualSpan px", dualSpan > 160)
        midDual.recycle()

        val endSingle = BitmapFactory.decodeFile(singleFiles[8].absolutePath)
        val pin = endSingle.getPixel(8, singleH - 8)
        assertTrue(
            "single bottom-left should stay pinned, was ${Integer.toHexString(pin)}",
            !colorNear(pin, nextBg, slop = 40),
        )
        endSingle.recycle()

        val endDual = BitmapFactory.decodeFile(dualFiles[8].absolutePath)
        val spineBottom = endDual.getPixel(leftLeaf.width.toInt() + 8, dualH - 12)
        assertTrue(
            "dual spine-bottom must not tear off to next page, was ${Integer.toHexString(spineBottom)}",
            !colorNear(spineBottom, nextBg, slop = 40),
        )
        val coveredLeft = endDual.getPixel((leftLeaf.width * 0.55f).toInt(), (dualH * 0.62f).toInt())
        assertTrue(
            "late dual flap should cover part of the left leaf, was ${Integer.toHexString(coveredLeft)}",
            !colorNear(coveredLeft, currentBg, slop = 28),
        )
        val coveredFar = endDual.getPixel(24, 24)
        assertTrue(
            "dual end frame should merge onto the far left, was ${Integer.toHexString(coveredFar)}",
            !colorNear(coveredFar, currentBg, slop = 28),
        )
        endDual.recycle()

        println("peel frames written to ${outDir.absolutePath}")
    }

    @Test
    fun `letterbox dest 露出纸色而不是把图拉满`() {
        val page = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        page.eraseColor(0xFF00AA00.toInt())
        val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val frame = checkNotNull(
            peelFrame(
                touchLocal = Offset(188f, 188f),
                corner = PeelCorner.BOTTOM_RIGHT,
                width = 200f,
                height = 200f,
                bindingOnly = false,
            ),
        )
        val paper = 0xFFFFEEDD.toInt()
        PeelRenderer.draw(
            canvas = canvas,
            frame = frame,
            leafWidth = 200f,
            leafHeight = 200f,
            current = page,
            next = page,
            backgroundArgb = paper,
            density = 1f,
            currentFit = PeelBitmapFit(50f, 50f, 150f, 150f),
            nextFit = PeelBitmapFit(50f, 50f, 150f, 150f),
        )
        val margin = out.getPixel(8, 8)
        assertTrue(
            "leaf margin should stay paper, was ${Integer.toHexString(margin)}",
            colorNear(margin, paper, slop = 8),
        )
        val content = out.getPixel(100, 80)
        assertTrue(
            "letterboxed page should keep source color, was ${Integer.toHexString(content)}",
            colorNear(content, 0xFF00AA00.toInt(), slop = 8),
        )
        page.recycle()
        out.recycle()
    }

    @Test
    fun `右翻中段纸背还不盖住左右靠书脊的内缘`() {
        val (leftLeaf, rightLeaf) = peelLeaves(
            dual = true,
            contentWidth = dualW.toFloat(),
            contentHeight = dualH.toFloat(),
            pageWidth = dualPageW.toFloat(),
            splitLeft = dualPageW.toFloat(),
            splitRight = (dualPageW + dualHinge).toFloat(),
        )
        checkNotNull(rightLeaf)
        val touch = autoPlayTouch(0.45f, PeelCorner.BOTTOM_RIGHT, rightLeaf.width, rightLeaf.height)
        val frame = checkNotNull(
            peelFrame(
                touchLocal = touch,
                corner = PeelCorner.BOTTOM_RIGHT,
                width = rightLeaf.width,
                height = rightLeaf.height,
                bindingOnly = false,
            ),
        )
        val flap = PeelRenderer.flapPathInContent(frame, rightLeaf)
        val y = rightLeaf.height * 0.35f
        assertFalse(
            "turning-leaf gutter should stay outside flap at mid peel",
            pathContains(flap, rightLeaf.originX + 10f, y),
        )
        assertFalse(
            "opposite-leaf gutter should stay outside flap at mid peel",
            pathContains(flap, leftLeaf.width - 10f, y),
        )
    }

    private fun writeSequence(
        outDir: File,
        prefix: String,
        alsoAsLegacy: Boolean,
        width: Int,
        height: Int,
        current: Bitmap,
        next: Bitmap,
        backgroundArgb: Int,
        density: Float,
    ): List<File> {
        val files = mutableListOf<File>()
        for (i in 0..8) {
            val t = i / 8f
            val bmp = renderSingleFrame(t, width, height, current, next, backgroundArgb, density)
            val name = "$prefix-t$i-p${(t * 1000).toInt()}.png"
            val file = File(outDir, name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (alsoAsLegacy) {
                File(outDir, "peel-t$i-p${(t * 1000).toInt()}.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            files += file
            bmp.recycle()
        }
        return files
    }

    private fun renderSingleFrame(
        t: Float,
        width: Int,
        height: Int,
        current: Bitmap,
        next: Bitmap,
        backgroundArgb: Int,
        density: Float,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawBitmap(current, 0f, 0f, null)
        val touch = autoPlayTouch(t, PeelCorner.BOTTOM_RIGHT, width.toFloat(), height.toFloat())
        val frame = peelFrame(
            touchLocal = touch,
            corner = PeelCorner.BOTTOM_RIGHT,
            width = width.toFloat(),
            height = height.toFloat(),
            bindingOnly = autoPlayBindingOnly(t),
        )
        if (frame != null) {
            PeelRenderer.draw(
                canvas = canvas,
                frame = frame,
                leafWidth = width.toFloat(),
                leafHeight = height.toFloat(),
                current = current,
                next = next,
                backgroundArgb = backgroundArgb,
                density = density,
            )
        }
        return bmp
    }

    private fun writeDragMid(
        outDir: File,
        name: String,
        width: Int,
        height: Int,
        current: Bitmap,
        next: Bitmap,
        backgroundArgb: Int,
        density: Float,
        rawTouch: Offset,
    ) {
        val frame = peelFrame(
            rawTouch,
            PeelCorner.BOTTOM_RIGHT,
            width.toFloat(),
            height.toFloat(),
            bindingOnly = false,
        ) ?: return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        PeelRenderer.draw(
            canvas = Canvas(bmp),
            frame = frame,
            leafWidth = width.toFloat(),
            leafHeight = height.toFloat(),
            current = current,
            next = next,
            backgroundArgb = backgroundArgb,
            density = density,
        )
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun writeDualSequence(
        outDir: File,
        leftLeaf: PeelLeaf,
        rightLeaf: PeelLeaf,
        leftPage: Bitmap,
        rightCurrent: Bitmap,
        rightNext: Bitmap,
        leftNext: Bitmap,
    ): List<File> {
        val files = mutableListOf<File>()
        for (i in 0..8) {
            val t = i / 8f
            val bmp = renderDualFrame(t, leftLeaf, rightLeaf, leftPage, rightCurrent, rightNext, leftNext)
            val file = File(outDir, "dual-t$i-p${(t * 1000).toInt()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            files += file
            bmp.recycle()
        }
        return files
    }

    private fun writeDualDragMid(
        outDir: File,
        leftLeaf: PeelLeaf,
        rightLeaf: PeelLeaf,
        leftPage: Bitmap,
        rightCurrent: Bitmap,
        rightNext: Bitmap,
        leftNext: Bitmap,
    ) {
        val raw = Offset(rightLeaf.width - 8f, rightLeaf.height * 0.55f)
        val frame = peelFrame(
            raw,
            PeelCorner.BOTTOM_RIGHT,
            rightLeaf.width,
            rightLeaf.height,
            bindingOnly = false,
        ) ?: return
        val bmp = Bitmap.createBitmap(dualW, dualH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDualBase(canvas, leftLeaf, rightLeaf, leftPage, rightCurrent)
        drawSpine(canvas, leftLeaf, rightLeaf)
        canvas.save()
        canvas.translate(rightLeaf.originX, rightLeaf.originY)
        PeelRenderer.draw(
            canvas = canvas,
            frame = frame,
            leafWidth = rightLeaf.width,
            leafHeight = rightLeaf.height,
            current = rightCurrent,
            next = rightNext,
            backgroundArgb = currentRightBg,
            density = 2.5f,
            back = leftNext,
            extendLeft = rightLeaf.originX,
        )
        canvas.restore()
        File(outDir, "dual-drag-mid-right.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bmp.recycle()
    }

    private fun renderDualFrame(
        t: Float,
        leftLeaf: PeelLeaf,
        rightLeaf: PeelLeaf,
        leftPage: Bitmap,
        rightCurrent: Bitmap,
        rightNext: Bitmap,
        leftNext: Bitmap,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(dualW, dualH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDualBase(canvas, leftLeaf, rightLeaf, leftPage, rightCurrent)
        drawSpine(canvas, leftLeaf, rightLeaf)
        val opposite = peelOppositeWidth(PeelCorner.BOTTOM_RIGHT, rightLeaf, leftLeaf, rightLeaf)
        val touch = autoPlayTouch(
            t,
            PeelCorner.BOTTOM_RIGHT,
            rightLeaf.width,
            rightLeaf.height,
            oppositeWidth = opposite,
        )
        val frame = peelFrame(
            touchLocal = touch,
            corner = PeelCorner.BOTTOM_RIGHT,
            width = rightLeaf.width,
            height = rightLeaf.height,
            bindingOnly = autoPlayBindingOnly(t),
            oppositeWidth = opposite,
        )
        if (frame != null) {
            canvas.save()
            canvas.translate(rightLeaf.originX, rightLeaf.originY)
            PeelRenderer.draw(
                canvas = canvas,
                frame = frame,
                leafWidth = rightLeaf.width,
                leafHeight = rightLeaf.height,
                current = rightCurrent,
                next = rightNext,
                backgroundArgb = currentRightBg,
                density = 2.5f,
                back = leftNext,
                extendLeft = rightLeaf.originX,
            )
            canvas.restore()
        }
        return bmp
    }

    private fun drawDualBase(
        canvas: Canvas,
        leftLeaf: PeelLeaf,
        rightLeaf: PeelLeaf,
        leftPage: Bitmap,
        rightCurrent: Bitmap,
    ) {
        canvas.drawColor(boardBg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            leftPage,
            android.graphics.Rect(0, 0, leftPage.width, leftPage.height),
            android.graphics.RectF(leftLeaf.originX, leftLeaf.originY, leftLeaf.right, leftLeaf.bottom),
            paint,
        )
        canvas.drawBitmap(
            rightCurrent,
            android.graphics.Rect(0, 0, rightCurrent.width, rightCurrent.height),
            android.graphics.RectF(rightLeaf.originX, rightLeaf.originY, rightLeaf.right, rightLeaf.bottom),
            paint,
        )
    }

    private fun drawSpine(canvas: Canvas, leftLeaf: PeelLeaf, rightLeaf: PeelLeaf) {
        val left = leftLeaf.right
        val right = rightLeaf.originX
        if (right <= left) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                left,
                0f,
                right,
                0f,
                intArrayOf(0x66000000.toInt(), 0x99000000.toInt(), 0x44000000.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(left, 0f, right, dualH.toFloat(), paint)
    }

    private fun fakePage(
        width: Int,
        height: Int,
        title: String,
        body: String,
        bg: Int,
        textColor: Int,
        footer: String,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bg)
        val side = (width * 0.035f).coerceIn(24f, 44f)
        val maxWidth = width - side * 2f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = (width / 17f).coerceIn(36f, 58f)
            typeface = Typeface.SERIF
            isFakeBoldText = true
        }
        val titleY = side + titlePaint.textSize
        canvas.drawText(title, side, titleY, titlePaint)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (textColor and 0x00FFFFFF) or 0x55000000
            strokeWidth = 2f
        }
        canvas.drawLine(side, titleY + 18f, side + maxWidth, titleY + 18f, rule)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = (width / 21f).coerceIn(30f, 46f)
            typeface = Typeface.SERIF
        }
        val lineH = bodyPaint.textSize * 1.78f
        var y = titleY + 18f + lineH
        val footerReserve = bodyPaint.textSize * 2.6f
        val filled = buildString { repeat(36) { append(body) } }
        val lines = wrapToWidth(filled, bodyPaint, maxWidth)
        for (line in lines) {
            if (y + footerReserve > height - side) break
            drawJustifiedLine(canvas, line, side, y, maxWidth, bodyPaint)
            y += lineH
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = bodyPaint.textSize * 0.82f
            typeface = Typeface.SERIF
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(footer, width / 2f, height - side * 0.55f, footerPaint)
        return bmp
    }

    private fun wrapToWidth(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var width = 0f
            while (end < text.length) {
                val cw = paint.measureText(text, end, end + 1)
                if (end > start && width + cw > maxWidth) break
                width += cw
                end++
            }
            if (end == start) end = (start + 1).coerceAtMost(text.length)
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    private fun drawJustifiedLine(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
    ) {
        if (text.length <= 1) {
            canvas.drawText(text, x, y, paint)
            return
        }
        val n = text.length
        val advances = FloatArray(n) { i -> paint.measureText(text, i, i + 1) }
        var natural = 0f
        for (w in advances) natural += w
        val extra = (maxWidth - natural).coerceAtLeast(0f)
        val gap = extra / (n - 1)
        var cx = x
        for (i in 0 until n) {
            val px = if (i == n - 1) x + maxWidth - advances[i] else cx
            canvas.drawText(text, i, i + 1, px, y, paint)
            cx += advances[i] + gap
        }
    }

    private fun firstBodyBaseline(pageWidth: Int): Int {
        val side = (pageWidth * 0.035f).coerceIn(24f, 44f)
        val titleSize = (pageWidth / 17f).coerceIn(36f, 58f)
        val bodySize = (pageWidth / 21f).coerceIn(30f, 46f)
        val titleY = side + titleSize
        return (titleY + 18f + bodySize * 1.78f).toInt()
    }

    private fun assertLineFillsWidth(page: Bitmap, yHint: Int, bg: Int) {
        val side = (page.width * 0.035f).coerceIn(24f, 44f).toInt()
        var y = yHint.coerceIn(0, page.height - 1)
        var first = -1
        var last = -1
        fun scan(atY: Int): Pair<Int, Int> {
            var f = -1
            var l = -1
            for (x in side until page.width - side) {
                if (!colorNear(page.getPixel(x, atY), bg, slop = 28)) {
                    if (f < 0) f = x
                    l = x
                }
            }
            return f to l
        }
        var found = scan(y)
        first = found.first
        last = found.second
        if (first < 0) {
            val bodySize = (page.width / 21f).coerceIn(30f, 46f).toInt()
            for (delta in 1..bodySize) {
                val up = (yHint - delta).coerceAtLeast(0)
                found = scan(up)
                if (found.first >= 0) {
                    y = up
                    first = found.first
                    last = found.second
                    break
                }
                val down = (yHint + delta).coerceAtMost(page.height - 1)
                found = scan(down)
                if (found.first >= 0) {
                    y = down
                    first = found.first
                    last = found.second
                    break
                }
            }
        }
        val usable = page.width - side * 2
        val span = last - first
        assertTrue(
            "body line should fill the page width: span=$span usable=$usable y=$y first=$first last=$last",
            first >= 0 && span > usable * 0.88f,
        )
        val rightGap = (page.width - side) - last
        assertTrue("right margin leftover $rightGap px at y=$y last=$last", rightGap < usable * 0.08f)
    }

    private fun assertPng(file: File, width: Int, height: Int) {
        assertTrue(file.absolutePath, file.isFile && file.length() > 8_000)
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        decoded.recycle()
    }

    private fun creaseSpanAlongBottom(
        bitmap: Bitmap,
        y: Int,
        xStart: Int = 40,
        xEnd: Int = bitmap.width - 10,
    ): Int {
        var firstChange = -1
        var lastChange = -1
        val origin = bitmap.getPixel(xStart, y)
        for (x in xStart until xEnd) {
            val px = bitmap.getPixel(x, y)
            if (!colorNear(px, origin, slop = 28)) {
                if (firstChange < 0) firstChange = x
                lastChange = x
            }
        }
        return if (firstChange < 0) 0 else lastChange - firstChange
    }

    private fun framesDir(): File {
        val override = System.getProperty("peel.frames.out")
        if (!override.isNullOrBlank()) return File(override)
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return File(cwd, "build/peel-frames")
    }

    private fun pathContains(path: Path, x: Float, y: Float): Boolean {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return false
        val region = Region()
        region.setPath(
            path,
            Region(
                bounds.left.toInt() - 1,
                bounds.top.toInt() - 1,
                bounds.right.toInt() + 1,
                bounds.bottom.toInt() + 1,
            ),
        )
        return region.contains(x.toInt(), y.toInt())
    }

    private fun colorNear(pixel: Int, color: Int, slop: Int): Boolean {
        fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
        return abs(ch(pixel, 16) - ch(color, 16)) <= slop &&
            abs(ch(pixel, 8) - ch(color, 8)) <= slop &&
            abs(ch(pixel, 0) - ch(color, 0)) <= slop
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
