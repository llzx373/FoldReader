package com.llzx373.foldreader.feature.reader.peel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.max

/**
 * 把一帧仿真翻页画到 [canvas]（叶内局部坐标，原点在叶的左上）。
 *
 * 纸背轮廓 = 页矩形经 Householder 反射后与折痕 A 侧的交集，
 * 因此镜像页边、自由边、折痕交在同一条几何边上。
 */
object PeelRenderer {

    fun draw(
        canvas: Canvas,
        frame: PeelFrame,
        leafWidth: Float,
        leafHeight: Float,
        current: Bitmap,
        next: Bitmap,
        backgroundArgb: Int,
        density: Float = 3f,
        back: Bitmap? = null,
        extendLeft: Float = 0f,
        extendRight: Float = 0f,
    ) {
        val w = leafWidth
        val h = leafHeight
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipRect(-extendLeft, 0f, w + extendRight, h)

        val extent = hypot(w, h) * 4f + w + extendLeft + extendRight
        val n = creaseNormal(frame)
        val aSide = halfPlanePath(frame.mid, n.x, n.y, extent)
        val fSide = halfPlanePath(frame.mid, -n.x, -n.y, extent)
        val reflected = reflectedPagePath(frame, w, h)
        val flap = intersectOrFallback(reflected, aSide, frame)

        drawUnder(canvas, next, w, h, fSide, flap)
        drawFront(canvas, current, w, h, aSide, flap)
        drawBack(canvas, current, back, frame, w, h, reflected, aSide, backgroundArgb)
        drawShadows(canvas, frame, w, h, fSide, flap, density)

        canvas.restore()
    }

    fun reflectedPagePath(frame: PeelFrame, width: Float, height: Float): Path {
        val path = Path().apply {
            addRect(0f, 0f, width, height, Path.Direction.CW)
        }
        path.transform(Matrix().apply { setValues(peelReflectionMatrixValues(frame)) })
        return path
    }

    fun flapPath(frame: PeelFrame, width: Float, height: Float): Path {
        val n = creaseNormal(frame)
        val extent = hypot(width, height) * 4f + width
        return intersectOrFallback(
            reflectedPagePath(frame, width, height),
            halfPlanePath(frame.mid, n.x, n.y, extent),
            frame,
        )
    }

    private fun drawUnder(
        canvas: Canvas,
        next: Bitmap,
        w: Float,
        h: Float,
        fSide: Path,
        flap: Path,
    ) {
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.clipPath(fSide)
        canvas.clipOutPath(flap)
        drawBitmapFitted(canvas, next, w, h)
        canvas.restore()
    }

    private fun drawFront(
        canvas: Canvas,
        current: Bitmap,
        w: Float,
        h: Float,
        aSide: Path,
        flap: Path,
    ) {
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.clipPath(aSide)
        canvas.clipOutPath(flap)
        drawBitmapFitted(canvas, current, w, h)
        canvas.restore()
    }

    private fun drawBack(
        canvas: Canvas,
        current: Bitmap,
        back: Bitmap?,
        frame: PeelFrame,
        w: Float,
        h: Float,
        reflected: Path,
        aSide: Path,
        backgroundArgb: Int,
    ) {
        canvas.save()
        canvas.clipPath(reflected)
        canvas.clipPath(aSide)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb }
        canvas.drawRect(-w, -h, w * 2f, h * 2f, fill)
        canvas.save()
        canvas.concat(Matrix().apply { setValues(peelReflectionMatrixValues(frame)) })
        val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (back != null && !back.isRecycled) {
            canvas.scale(-1f, 1f, w / 2f, 0f)
            drawBitmapFitted(canvas, back, w, h, pagePaint)
        } else {
            drawBitmapFitted(canvas, current, w, h, pagePaint)
        }
        canvas.restore()
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                frame.mid.x,
                frame.mid.y,
                frame.touch.x,
                frame.touch.y,
                intArrayOf(
                    PEEL_BACK_DIM_CREASE_ALPHA shl 24,
                    PEEL_BACK_DIM_EDGE_ALPHA shl 24,
                    0x00000000,
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(-w, -h, w * 2f, h * 2f, dim)
        canvas.restore()
    }

    private fun drawShadows(
        canvas: Canvas,
        frame: PeelFrame,
        w: Float,
        h: Float,
        fSide: Path,
        flap: Path,
        density: Float,
    ) {
        val d = density.coerceAtLeast(0.5f)
        val unit = d / 3f
        val c1 = frame.bezierControl1
        val c2 = frame.bezierControl2

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.clipPath(fSide)
        canvas.clipOutPath(flap)
        val cast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                frame.mid.x,
                frame.mid.y,
                frame.cornerPoint.x,
                frame.cornerPoint.y,
                intArrayOf(PEEL_CAST_SHADOW_ALPHA shl 24, 0x12000000, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, cast)
        canvas.restore()

        val crease = Path().apply {
            moveTo(c1.x, c1.y)
            lineTo(c2.x, c2.y)
        }
        strokeSoft(
            canvas,
            crease,
            unit,
            widths = floatArrayOf(36f, 22f, 12f, 5f),
            alphas = intArrayOf(0x08, 0x0E, 0x16, PEEL_CREASE_CONTACT_ALPHA),
        )

        canvas.save()
        canvas.clipPath(flap)
        val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                frame.mid.x,
                frame.mid.y,
                frame.touch.x,
                frame.touch.y,
                intArrayOf(0x1C000000, 0x0A000000, 0x00000000),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(-w, -h, w * 2f, h * 2f, creasePaint)
        canvas.restore()

        canvas.save()
        canvas.clipOutPath(flap)
        strokeSoft(
            canvas,
            flap,
            unit,
            widths = floatArrayOf(42f, 26f, 14f, 6f),
            alphas = intArrayOf(0x06, 0x0A, 0x0E, PEEL_FLAP_EDGE_ALPHA),
        )
        canvas.restore()

        val edge = PEEL_EDGE_SHADOW_PX * unit
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (PEEL_EDGE_HIGHLIGHT_ALPHA shl 24) or 0x00FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = edge
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(frame.touch.x, frame.touch.y, c1.x, c1.y, highlight)
        canvas.drawLine(frame.touch.x, frame.touch.y, c2.x, c2.y, highlight)
    }

    private fun strokeSoft(
        canvas: Canvas,
        path: Path,
        unit: Float,
        widths: FloatArray,
        alphas: IntArray,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (i in widths.indices) {
            paint.strokeWidth = widths[i] * unit
            paint.color = alphas[i] shl 24
            canvas.drawPath(path, paint)
        }
    }

    private fun creaseNormal(frame: PeelFrame): Offset {
        val nx = frame.touch.x - frame.cornerPoint.x
        val ny = frame.touch.y - frame.cornerPoint.y
        val len = hypot(nx, ny).coerceAtLeast(1e-3f)
        return Offset(nx / len, ny / len)
    }

    private fun halfPlanePath(origin: Offset, nx: Float, ny: Float, extent: Float): Path {
        val px = -ny
        val py = nx
        val e = extent.coerceAtLeast(1f)
        return Path().apply {
            moveTo(origin.x + px * e, origin.y + py * e)
            lineTo(origin.x + px * e + nx * e, origin.y + py * e + ny * e)
            lineTo(origin.x - px * e + nx * e, origin.y - py * e + ny * e)
            lineTo(origin.x - px * e, origin.y - py * e)
            close()
        }
    }

    private fun intersectOrFallback(a: Path, b: Path, frame: PeelFrame): Path {
        val out = Path(a)
        if (out.op(b, Path.Op.INTERSECT) && !out.isEmpty) return out
        return Path().apply {
            moveTo(frame.touch.x, frame.touch.y)
            lineTo(frame.bezierControl1.x, frame.bezierControl1.y)
            lineTo(frame.bezierControl2.x, frame.bezierControl2.y)
            close()
        }
    }

    private fun drawBitmapFitted(
        canvas: Canvas,
        bitmap: Bitmap,
        w: Float,
        h: Float,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    ) {
        if (bitmap.isRecycled) return
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dw = max(1, w.toInt())
        val dh = max(1, h.toInt())
        val dst = android.graphics.RectF(0f, 0f, dw.toFloat(), dh.toFloat())
        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}
