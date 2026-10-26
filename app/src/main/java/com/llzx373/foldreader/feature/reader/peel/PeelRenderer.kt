package com.llzx373.foldreader.feature.reader.peel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.max

/**
 * 源图在叶内的落位。为 null 时把整张位图拉满叶（电子书离屏页与叶同尺寸）。
 * 漫画静图直接引用解码缓存时，用这一矩形做 contain / letterbox，避免再拷一份叶尺寸位图。
 */
data class PeelBitmapFit(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 把一帧仿真翻页画到 [canvas]（叶内局部坐标，原点在叶的左上）。
 *
 * 纸背轮廓 = 页矩形经 Householder 反射后与折痕 A 侧的交集，
 * 因此镜像页边、自由边、折痕交在同一条几何边上。
 */
class PeelRenderGeometry internal constructor(
    val aSide: Path,
    val fSide: Path,
    val reflected: Path,
    val flap: Path,
    val reflection: Matrix,
    val shadowFade: Float,
)

object PeelRenderer {

    fun renderGeometry(
        frame: PeelFrame,
        width: Float,
        height: Float,
        extendLeft: Float = 0f,
        extendRight: Float = 0f,
    ): PeelRenderGeometry {
        val extent = hypot(width, height) * 4f + width + extendLeft + extendRight
        val n = creaseNormal(frame)
        val aSide = halfPlanePath(frame.mid, n.x, n.y, extent)
        val fSide = halfPlanePath(frame.mid, -n.x, -n.y, extent)
        val reflection = Matrix().apply { setValues(peelReflectionMatrixValues(frame)) }
        val reflected = reflectedPagePath(frame, width, height, reflection)
        val flap = intersectOrFallback(reflected, aSide, frame)
        val shadowFade = peelShadowFade(
            touchLocal = frame.touch,
            corner = frame.corner,
            width = width,
            height = height,
            oppositeWidth = if (frame.corner.isRight) extendLeft else extendRight,
        )
        return PeelRenderGeometry(aSide, fSide, reflected, flap, reflection, shadowFade)
    }

    fun draw(
        canvas: Canvas,
        frame: PeelFrame,
        leafWidth: Float,
        leafHeight: Float,
        current: Bitmap,
        next: Bitmap?,
        backgroundArgb: Int,
        density: Float = 3f,
        back: Bitmap? = null,
        extendLeft: Float = 0f,
        extendRight: Float = 0f,
        currentFit: PeelBitmapFit? = null,
        nextFit: PeelBitmapFit? = null,
        backFit: PeelBitmapFit? = null,
        geometry: PeelRenderGeometry? = null,
    ) {
        val w = leafWidth
        val h = leafHeight
        if (w <= 0f || h <= 0f) return
        val geo = geometry ?: renderGeometry(frame, w, h, extendLeft, extendRight)
        canvas.save()
        canvas.clipRect(-extendLeft, 0f, w + extendRight, h)
        drawUnder(canvas, next, w, h, geo.fSide, geo.flap, backgroundArgb, nextFit)
        drawFront(canvas, current, w, h, geo.aSide, geo.flap, backgroundArgb, currentFit)
        drawBack(canvas, back, frame, w, h, geo, backgroundArgb, backFit)
        drawShadows(canvas, frame, w, h, geo.fSide, geo.flap, density, geo.shadowFade)
        canvas.restore()
    }

    fun reflectedPagePath(frame: PeelFrame, width: Float, height: Float): Path =
        reflectedPagePath(frame, width, height, Matrix().apply {
            setValues(peelReflectionMatrixValues(frame))
        })

    private fun reflectedPagePath(frame: PeelFrame, width: Float, height: Float, matrix: Matrix): Path {
        val path = Path().apply {
            addRect(0f, 0f, width, height, Path.Direction.CW)
        }
        path.transform(matrix)
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

    /** 纸背轮廓平移到内容区坐标，给书脊 overlay 扣掉翻起的纸。 */
    fun flapPathInContent(frame: PeelFrame, leaf: PeelLeaf): Path {
        val path = flapPath(frame, leaf.width, leaf.height)
        path.offset(leaf.originX, leaf.originY)
        return path
    }

    private fun drawUnder(
        canvas: Canvas,
        next: Bitmap?,
        w: Float,
        h: Float,
        fSide: Path,
        flap: Path,
        backgroundArgb: Int,
        fit: PeelBitmapFit?,
    ) {
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.clipPath(fSide)
        canvas.clipOutPath(flap)
        drawPageInLeaf(canvas, next, w, h, backgroundArgb, fit)
        canvas.restore()
    }

    private fun drawFront(
        canvas: Canvas,
        current: Bitmap,
        w: Float,
        h: Float,
        aSide: Path,
        flap: Path,
        backgroundArgb: Int,
        fit: PeelBitmapFit?,
    ) {
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.clipPath(aSide)
        canvas.clipOutPath(flap)
        drawPageInLeaf(canvas, current, w, h, backgroundArgb, fit)
        canvas.restore()
    }

    /**
     * 纸背。双页有对侧叶位图（下一开的左页 / 上一开的右页）；单页只留纸色，
     * 不把当前页镜像画上去——单面印刷，掀起来不该看到反字。
     */
    private fun drawBack(
        canvas: Canvas,
        back: Bitmap?,
        frame: PeelFrame,
        w: Float,
        h: Float,
        geometry: PeelRenderGeometry,
        backgroundArgb: Int,
        fit: PeelBitmapFit?,
    ) {
        canvas.save()
        canvas.clipPath(geometry.reflected)
        canvas.clipPath(geometry.aSide)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb }
        canvas.drawRect(-w, -h, w * 2f, h * 2f, fill)
        if (back != null && !back.isRecycled) {
            canvas.save()
            canvas.concat(geometry.reflection)
            canvas.scale(-1f, 1f, w / 2f, 0f)
            drawBitmapAt(canvas, back, w, h, fit)
            canvas.restore()
        }
        if (geometry.shadowFade > 0f) {
            val fade = geometry.shadowFade
            val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    frame.mid.x,
                    frame.mid.y,
                    frame.touch.x,
                    frame.touch.y,
                    intArrayOf(
                        shadowAlpha(PEEL_BACK_DIM_CREASE_ALPHA, fade),
                        shadowAlpha(PEEL_BACK_DIM_EDGE_ALPHA, fade),
                        0x00000000,
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(-w, -h, w * 2f, h * 2f, dim)
        }
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
        fade: Float,
    ) {
        if (fade <= 0f) return
        val d = density.coerceAtLeast(0.5f)
        val unit = d / 3f
        val c1 = frame.bezierControl1
        val c2 = frame.bezierControl2

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.clipPath(fSide)
        canvas.clipOutPath(flap)
        val castDx = frame.cornerPoint.x - frame.mid.x
        val castDy = frame.cornerPoint.y - frame.mid.y
        val castLength = hypot(castDx, castDy).coerceAtLeast(1f)
        val castWidth = peelCastShadowWidth(frame.touchToCorner, density)
        val lift = (peelProgressRatio(frame.touchToCorner, w, h) / 0.45f).coerceIn(0f, 1f)
        val castStrength = 1f - lift * 0.25f
        val cast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                frame.mid.x,
                frame.mid.y,
                frame.mid.x + castDx / castLength * castWidth,
                frame.mid.y + castDy / castLength * castWidth,
                intArrayOf(
                    shadowAlpha((PEEL_CAST_SHADOW_ALPHA * castStrength).toInt(), fade),
                    shadowAlpha((0x12 * castStrength).toInt(), fade),
                    0x00000000,
                ),
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
            alphas = intArrayOf(0x08, 0x0E, 0x16, PEEL_CREASE_CONTACT_ALPHA).fadeBy(fade),
        )

        canvas.save()
        canvas.clipPath(flap)
        val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                frame.mid.x,
                frame.mid.y,
                frame.touch.x,
                frame.touch.y,
                intArrayOf(shadowAlpha(0x1C, fade), shadowAlpha(0x0A, fade), 0x00000000),
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
            alphas = intArrayOf(0x06, 0x0A, 0x0E, PEEL_FLAP_EDGE_ALPHA).fadeBy(fade),
        )
        canvas.restore()

        val edge = PEEL_EDGE_SHADOW_PX * unit
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shadowAlpha(PEEL_EDGE_HIGHLIGHT_ALPHA, fade) or 0x00FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = edge
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(frame.touch.x, frame.touch.y, c1.x, c1.y, highlight)
        canvas.drawLine(frame.touch.x, frame.touch.y, c2.x, c2.y, highlight)
    }

    /** 尾段渐隐：把 0x00–0xFF 的 alpha 按比例收缩后放回高字节。 */
    private fun shadowAlpha(alpha: Int, fade: Float): Int = (alpha * fade).toInt() shl 24

    private fun IntArray.fadeBy(fade: Float): IntArray =
        IntArray(size) { i -> (this[i] * fade).toInt() }

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

    private fun drawPageInLeaf(
        canvas: Canvas,
        bitmap: Bitmap?,
        w: Float,
        h: Float,
        backgroundArgb: Int,
        fit: PeelBitmapFit?,
    ) {
        if (bitmap == null || bitmap.isRecycled || fit != null) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb }
            canvas.drawRect(0f, 0f, w, h, fill)
        }
        drawBitmapAt(canvas, bitmap, w, h, fit)
    }

    private fun drawBitmapAt(
        canvas: Canvas,
        bitmap: Bitmap?,
        w: Float,
        h: Float,
        fit: PeelBitmapFit?,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    ) {
        if (bitmap == null || bitmap.isRecycled) return
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dst = if (fit != null) {
            RectF(fit.left, fit.top, fit.right, fit.bottom)
        } else {
            RectF(0f, 0f, max(1, w.toInt()).toFloat(), max(1, h.toInt()).toFloat())
        }
        if (dst.width() <= 0f || dst.height() <= 0f) return
        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}
