package com.llzx373.foldreader.core.comic

/**
 * 图片头解析：只读头几十字节就拿到像素尺寸，不解码像素。
 *
 * 存在意义：双页配对要判「这张是不是横向跨页图」，若靠解码才知道尺寸，
 * 就得把前后几页全解一遍。纯 Java 实现（不碰 `BitmapFactory`）也让容器层留在 JVM 单测范围内。
 */
object ComicImageSizing {

    /** 返回 `[width, height]`；认不出返回 null。 */
    fun probe(bytes: ByteArray): IntArray? {
        if (bytes.size < 16) return null
        return when {
            isPng(bytes) -> pngSize(bytes)
            isGif(bytes) -> gifSize(bytes)
            isBmp(bytes) -> bmpSize(bytes)
            isJpeg(bytes) -> jpegSize(bytes)
            isWebp(bytes) -> webpSize(bytes)
            else -> null
        }
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    /** PNG：IHDR 紧跟 8 字节签名 + 4 字节长度 + 4 字节类型，宽高各 4 字节大端。 */
    private fun pngSize(b: ByteArray): IntArray? {
        if (b.size < 24) return null
        val w = beInt(b, 16)
        val h = beInt(b, 20)
        return valid(w, h)
    }

    private fun isGif(b: ByteArray): Boolean =
        b.size > 9 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    /** GIF：逻辑屏幕描述符里两个小端 u16。 */
    private fun gifSize(b: ByteArray): IntArray? {
        if (b.size < 10) return null
        return valid(leShort(b, 6), leShort(b, 8))
    }

    private fun isBmp(b: ByteArray): Boolean =
        b.size > 26 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()

    /** BMP：BITMAPINFOHEADER（紧跟在 14 字节文件头后）宽高各为小端 i32，高为负表示自上而下。 */
    private fun bmpSize(b: ByteArray): IntArray? {
        if (b.size < 26) return null
        val w = leInt(b, 18)
        val h = kotlin.math.abs(leInt(b, 22))
        return valid(w, h)
    }

    private fun isJpeg(b: ByteArray): Boolean =
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    /** JPEG：扫到任一 SOFn 段，段内 高度、宽度 各 2 字节大端。 */
    private fun jpegSize(b: ByteArray): IntArray? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = b[i + 1].toInt() and 0xFF
            when {
                // 填充字节
                marker == 0xFF -> i++
                // SOI/EOI/TEM 与 RSTn 无长度字段
                marker == 0xD8 || marker == 0xD9 || marker == 0x01 -> i += 2
                marker in 0xD0..0xD7 -> i += 2
                else -> {
                    val length = beShort(b, i + 2)
                    if (length < 2) return null
                    if (isStartOfFrame(marker)) {
                        if (i + 9 > b.size) return null
                        val h = beShort(b, i + 5)
                        val w = beShort(b, i + 7)
                        return valid(w, h)
                    }
                    i += 2 + length
                }
            }
        }
        return null
    }

    private fun isStartOfFrame(marker: Int): Boolean =
        marker in 0xC0..0xC3 || marker in 0xC5..0xC7 ||
            marker in 0xC9..0xCB || marker in 0xCD..0xCF

    private fun isWebp(b: ByteArray): Boolean =
        b.size > 30 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun webpSize(b: ByteArray): IntArray? {
        if (b.size < 30) return null
        return when {
            // VP8X（扩展格式，含动画/透明）：画布宽高各为 u24 小端，存的是 值-1
            b[12] == 'V'.code.toByte() && b[13] == 'P'.code.toByte() &&
                b[14] == '8'.code.toByte() && b[15] == 'X'.code.toByte() ->
                valid(leInt24(b, 24) + 1, leInt24(b, 27) + 1)

            // VP8L（无损）：签名 0x2F 后 28 位里塞了 宽-1 / 高-1
            b[12] == 'V'.code.toByte() && b[13] == 'P'.code.toByte() &&
                b[14] == '8'.code.toByte() && b[15] == 'L'.code.toByte() -> {
                if (b.size < 25) return null
                val bits = (b[21].toInt() and 0xFF) or
                    ((b[22].toInt() and 0xFF) shl 8) or
                    ((b[23].toInt() and 0xFF) shl 16) or
                    ((b[24].toInt() and 0xFF) shl 24)
                valid((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            }

            // VP8（有损）：帧头起始码后宽高各 2 字节小端，低 14 位有效
            else -> {
                if (b.size < 30) return null
                if (b[23] != 0x9D.toByte() || b[24] != 0x01.toByte() || b[25] != 0x2A.toByte()) {
                    return null
                }
                valid(leShort(b, 26) and 0x3FFF, leShort(b, 28) and 0x3FFF)
            }
        }
    }

    private fun valid(w: Int, h: Int): IntArray? =
        if (w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION) intArrayOf(w, h) else null

    private fun beShort(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun beInt(b: ByteArray, i: Int): Int =
        (beShort(b, i) shl 16) or beShort(b, i + 2)

    private fun leShort(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, i: Int): Int = leShort(b, i) or (leShort(b, i + 2) shl 16)

    private fun leInt24(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16)

    private const val MAX_DIMENSION = 100_000
}
