package com.llzx373.foldreader.core.pdf

import kotlin.math.roundToInt

/**
 * PDF 裁边（M32）的纯计算：裁框渲染尺寸与两套归一化坐标系的换算。
 *
 * 与漫画裁白边（core.comic.MarginCrop，渲染后位图裁剪）的差异：PDF 的页是
 * androidx.pdf 现渲染出来的位图，裁框可以下沉为**渲染参数**——`BitmapSource.getBitmap`
 * 的 clipRegion 让沙箱只光栅化裁框区域，裁后内容按槽位分辨率出图（而不是先渲整页再裁掉
 * 一圈、有效分辨率跟着缩水）。这里只算尺寸与坐标，不碰任何 Android 类型。
 *
 * 坐标约定：裁框与页内锚点一样存**原页归一化**（"l,t,r,b"，0..1，页左上原点）；
 * 裁边启用后屏幕上看到的是裁后位图，界面坐标是**裁后归一化**。
 * 选字/搜索要回文档文字层（原页坐标系），两个方向各一组换算。
 */
object PdfCrop {

    /** 放大渲染的上限倍数（相对「整页进槽」的基准缩放）：裁框极小时不至于渲出天文数字的整页。 */
    const val MAX_ENLARGE = 4f

    /** 整页渲染的长边像素上限：位图是 ARGB_8888，4096² ≈ 64MB，再大就撞内存预算了。 */
    const val MAX_LONG_SIDE = 4096

    /** 裁框合法性（与 MarginCrop.fromNormalized 同口径：各边在 0..1、宽高至少 5%）。 */
    fun isValidBox(box: FloatArray?): Boolean {
        if (box == null || box.size != 4) return false
        return box[0] >= 0f && box[1] >= 0f && box[2] <= 1f && box[3] <= 1f &&
            box[2] - box[0] >= 0.05f && box[3] - box[1] >= 0.05f
    }

    /**
     * 裁框渲染的整页放大尺寸（返回 [宽, 高] 像素）。
     *
     * 目标：让**裁框区域**恰好铺满目标槽位——等价于把整页按 1/crop 倍放大渲染，
     * 再让沙箱只出裁框那一块。放大被 [MAX_ENLARGE] 与 [MAX_LONG_SIDE] 双重钳制；
     * 输入非法（页面尺寸未知/槽位为零/裁框畸形）返回 null，调用方回退整页渲染。
     */
    fun enlargedPageSize(
        pageWidthPt: Float,
        pageHeightPt: Float,
        targetWidth: Int,
        targetHeight: Int,
        box: FloatArray?,
    ): IntArray? {
        if (pageWidthPt <= 0f || pageHeightPt <= 0f) return null
        if (targetWidth < 1 || targetHeight < 1 || !isValidBox(box)) return null
        box!!
        val cropW = box[2] - box[0]
        val cropH = box[3] - box[1]
        val baseScale = minOf(targetWidth / pageWidthPt, targetHeight / pageHeightPt)
        if (baseScale <= 0f) return null
        // 裁框区域进槽所需缩放，钳在基准缩放的 MAX_ENLARGE 倍以内
        val scale = minOf(
            targetWidth / (pageWidthPt * cropW),
            targetHeight / (pageHeightPt * cropH),
            baseScale * MAX_ENLARGE,
        )
        var w = (pageWidthPt * scale).roundToInt().coerceAtLeast(1)
        var h = (pageHeightPt * scale).roundToInt().coerceAtLeast(1)
        val longSide = maxOf(w, h)
        if (longSide > MAX_LONG_SIDE) {
            val shrink = MAX_LONG_SIDE.toFloat() / longSide
            w = (w * shrink).roundToInt().coerceAtLeast(1)
            h = (h * shrink).roundToInt().coerceAtLeast(1)
        }
        return intArrayOf(w, h)
    }

    /**
     * 归一化裁框 → 放大整页坐标系下的像素裁剪矩形 [l, t, r, b]（供 clipRegion）。
     * 退化（不足 2px）或非法输入返回 null。
     */
    fun clipRect(fullWidth: Int, fullHeight: Int, box: FloatArray?): IntArray? {
        if (fullWidth < 2 || fullHeight < 2 || !isValidBox(box)) return null
        box!!
        val l = (box[0] * fullWidth).roundToInt().coerceIn(0, fullWidth - 2)
        val t = (box[1] * fullHeight).roundToInt().coerceIn(0, fullHeight - 2)
        val r = (box[2] * fullWidth).roundToInt().coerceIn(l + 2, fullWidth)
        val b = (box[3] * fullHeight).roundToInt().coerceIn(t + 2, fullHeight)
        return intArrayOf(l, t, r, b)
    }

    /** 裁后归一化点 → 原页归一化点（不夹取，越界交给调用方判断）。 */
    fun pointToOriginal(x: Float, y: Float, box: FloatArray): Pair<Float, Float> =
        (box[0] + x * (box[2] - box[0])) to (box[1] + y * (box[3] - box[1]))

    /** 裁后归一化矩形 → 原页归一化矩形 [l, t, r, b]（夹进裁框对应的原页范围）。 */
    fun rectToOriginal(left: Float, top: Float, right: Float, bottom: Float, box: FloatArray): FloatArray {
        val cropW = box[2] - box[0]
        val cropH = box[3] - box[1]
        return floatArrayOf(
            (box[0] + left.coerceIn(0f, 1f) * cropW).coerceIn(0f, 1f),
            (box[1] + top.coerceIn(0f, 1f) * cropH).coerceIn(0f, 1f),
            (box[0] + right.coerceIn(0f, 1f) * cropW).coerceIn(0f, 1f),
            (box[1] + bottom.coerceIn(0f, 1f) * cropH).coerceIn(0f, 1f),
        )
    }

    /**
     * 原页归一化矩形 → 裁后归一化矩形 [l, t, r, b]（文档文字层的结果搬回屏幕坐标系）。
     * 与裁框完全不相交返回 null（那块内容已经看不见，选区随之丢弃）；部分相交则夹到裁框边缘。
     */
    fun rectToCrop(left: Float, top: Float, right: Float, bottom: Float, box: FloatArray): FloatArray? {
        if (!isValidBox(box)) return null
        val l = maxOf(left, box[0])
        val t = maxOf(top, box[1])
        val r = minOf(right, box[2])
        val b = minOf(bottom, box[3])
        if (r <= l || b <= t) return null
        val cropW = box[2] - box[0]
        val cropH = box[3] - box[1]
        return floatArrayOf(
            ((l - box[0]) / cropW).coerceIn(0f, 1f),
            ((t - box[1]) / cropH).coerceIn(0f, 1f),
            ((r - box[0]) / cropW).coerceIn(0f, 1f),
            ((b - box[1]) / cropH).coerceIn(0f, 1f),
        )
    }
}
