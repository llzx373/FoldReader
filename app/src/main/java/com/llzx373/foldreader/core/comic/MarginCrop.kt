package com.llzx373.foldreader.core.comic

import kotlin.math.abs

/**
 * 一页图中内容区域的像素矩形（right/bottom 为排他边界）。
 */
data class ContentBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 自动裁白边（M31）：扫描漫画页的白边检测与裁框计算。纯图像处理、零网络、不改原图——
 * 裁剪是渲染期行为（解码后按裁框取子图）。
 *
 * 算法：四角采样取逐通道中位色当「底色」（白边未必纯白，也可能是黑边/灰边）；
 * 从四边向内扫描行/列，一行（列）里接近底色的采样点比例 ≥ [BLANK_RATIO] 视为白边。
 * 单边裁量钳制在 [MAX_CROP_FRACTION] 以内——检测误判的代价是裁掉内容，
 * 宁可少裁也不能裁穿。多页合并用 [union]（并集：只裁所有采样页都空白的边）。
 */
object MarginCrop {

    /** 与底色的单通道差在此以内视为「空白点」。 */
    const val CHANNEL_TOLERANCE = 24

    /** 一行/列里至少这么多采样点接近底色才算白边（容忍扫描噪点伸到边缘的零星像素，约 2%）。 */
    const val BLANK_RATIO = 0.98f

    /** 单边最多裁掉的比例。 */
    const val MAX_CROP_FRACTION = 0.30f

    /**
     * 检测一页的内容框（ARGB 像素行优先）。没有可裁的边（或检测像误判）返回 null。
     * [stride] 采样步长（像素），0 = 自动（短边 /256，大图不至于逐点扫）。
     */
    fun detect(pixels: IntArray, width: Int, height: Int, stride: Int = 0): ContentBounds? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val step = if (stride > 0) stride else (minOf(width, height) / 256).coerceAtLeast(1)
        val background = cornerColor(pixels, width, height) ?: return null

        val maxCropX = (width * MAX_CROP_FRACTION).toInt()
        val maxCropY = (height * MAX_CROP_FRACTION).toInt()

        var top = 0
        while (top < maxCropY && rowIsBlank(pixels, width, top, 0, width, step, background)) top++
        var bottom = height
        while (height - bottom < maxCropY && rowIsBlank(pixels, width, bottom - 1, 0, width, step, background)) bottom--
        var left = 0
        while (left < maxCropX && columnIsBlank(pixels, width, left, 0, height, step, background)) left++
        var right = width
        while (width - right < maxCropX && columnIsBlank(pixels, width, right - 1, 0, height, step, background)) right--

        if (left == 0 && top == 0 && right == width && bottom == height) return null
        // 四面都顶到钳制上限 = 整页近似一个颜色（纯色扉页/跨页彩图），
        // 没有可识别的内容边界，裁了就是把内容裁掉 30%
        if (left == maxCropX && top == maxCropY &&
            width - right == maxCropX && height - bottom == maxCropY
        ) {
            return null
        }
        return ContentBounds(left, top, right, bottom)
    }

    /** 多页裁框合并：取并集（只裁所有采样页都空白的边，避免任何一页内容被裁掉）。 */
    fun union(a: ContentBounds?, b: ContentBounds?): ContentBounds? = when {
        a == null -> b
        b == null -> a
        else -> ContentBounds(
            left = minOf(a.left, b.left),
            top = minOf(a.top, b.top),
            right = maxOf(a.right, b.right),
            bottom = maxOf(a.bottom, b.bottom),
        )
    }

    /** 归一化裁框的并集（各页缩略图尺寸不同时先归一化再合并）。 */
    fun unionNormalized(a: FloatArray?, b: FloatArray?): FloatArray? = when {
        a == null -> b
        b == null -> a
        else -> floatArrayOf(
            minOf(a[0], b[0]),
            minOf(a[1], b[1]),
            maxOf(a[2], b[2]),
            maxOf(a[3], b[3]),
        )
    }

    /** 像素裁框 → 归一化（0..1，相对页宽高），供按书存储。 */
    fun toNormalized(bounds: ContentBounds, width: Int, height: Int): FloatArray = floatArrayOf(
        bounds.left.toFloat() / width,
        bounds.top.toFloat() / height,
        bounds.right.toFloat() / width,
        bounds.bottom.toFloat() / height,
    )

    /** 归一化裁框 → 像素裁框；输入非法（越界/空框）返回 null。 */
    fun fromNormalized(values: FloatArray, width: Int, height: Int): ContentBounds? {
        if (values.size != 4) return null
        val (l, t, r, b) = values
        if (l < 0f || t < 0f || r > 1f || b > 1f || r - l < 0.05f || b - t < 0.05f) return null
        return ContentBounds(
            left = (l * width).toInt().coerceIn(0, width - 1),
            top = (t * height).toInt().coerceIn(0, height - 1),
            right = (r * width).toInt().coerceIn(1, width),
            bottom = (b * height).toInt().coerceIn(1, height),
        )
    }

    /** 归一化裁框编码为存储串（"l,t,r,b"，4 位小数）；空串 = 未检测。 */
    fun encodeNormalized(values: FloatArray): String {
        require(values.size == 4)
        return values.joinToString(",") { "%.4f".format(it) }
    }

    /** 解码存储串；空串/畸形返回 null。 */
    fun decodeNormalized(text: String): FloatArray? {
        if (text.isBlank()) return null
        val parts = text.split(',')
        if (parts.size != 4) return null
        val values = parts.map { it.trim().toFloatOrNull() ?: return null }
        return values.toFloatArray().takeIf {
            it[0] >= 0f && it[1] >= 0f && it[2] <= 1f && it[3] <= 1f &&
                it[2] > it[0] && it[3] > it[1]
        }
    }

    /** 四角小块（≤16px 见方）的逐通道中位色：白边/黑边/灰边都按它当底色。 */
    private fun cornerColor(pixels: IntArray, width: Int, height: Int): Int? {
        val patch = minOf(16, width / 8, height / 8)
        if (patch <= 0) return null
        val samples = ArrayList<Int>(patch * patch * 4)
        val corners = listOf(0 to 0, width - patch to 0, 0 to height - patch, width - patch to height - patch)
        for ((cx, cy) in corners) {
            for (y in cy until cy + patch) {
                for (x in cx until cx + patch) {
                    samples += pixels[y * width + x]
                }
            }
        }
        fun channel(selector: (Int) -> Int): Int =
            samples.map(selector).sorted().let { it[it.size / 2] }
        val r = channel { (it ushr 16) and 0xFF }
        val g = channel { (it ushr 8) and 0xFF }
        val b = channel { it and 0xFF }
        return (r shl 16) or (g shl 8) or b
    }

    private fun near(pixel: Int, background: Int): Boolean {
        if (abs(((pixel ushr 16) and 0xFF) - ((background ushr 16) and 0xFF)) > CHANNEL_TOLERANCE) return false
        if (abs(((pixel ushr 8) and 0xFF) - ((background ushr 8) and 0xFF)) > CHANNEL_TOLERANCE) return false
        if (abs((pixel and 0xFF) - (background and 0xFF)) > CHANNEL_TOLERANCE) return false
        return true
    }

    private fun rowIsBlank(
        pixels: IntArray,
        width: Int,
        y: Int,
        x0: Int,
        x1: Int,
        step: Int,
        background: Int,
    ): Boolean {
        var total = 0
        var blank = 0
        var x = x0
        while (x < x1) {
            total++
            if (near(pixels[y * width + x], background)) blank++
            x += step
        }
        return total > 0 && blank >= total * BLANK_RATIO
    }

    private fun columnIsBlank(
        pixels: IntArray,
        width: Int,
        x: Int,
        y0: Int,
        y1: Int,
        step: Int,
        background: Int,
    ): Boolean {
        var total = 0
        var blank = 0
        var y = y0
        while (y < y1) {
            total++
            if (near(pixels[y * width + x], background)) blank++
            y += step
        }
        return total > 0 && blank >= total * BLANK_RATIO
    }
}
