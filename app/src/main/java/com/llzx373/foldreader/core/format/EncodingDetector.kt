package com.llzx373.foldreader.core.format

import java.nio.charset.Charset

data class EncodingDetection(
    val charset: Charset,
    val confidence: Float,
)

object EncodingDetector {

    const val SAMPLE_SIZE = 64 * 1024

    private val GBK = Charset.forName("GBK")
    private val GB18030 = Charset.forName("GB18030")
    private val BIG5 = Charset.forName("Big5")

    private val SIMPLIFIED_MARKERS =
        "这来说们国个没有发现对头时间经过会开关系么将两些于学书长体".toSet()
    private val TRADITIONAL_MARKERS =
        "這來說們國個沒有發現對頭時間經過會開關係麼將兩些於學書長體".toSet()

    fun detect(sample: ByteArray, length: Int = sample.size): EncodingDetection {
        val n = minOf(length, sample.size)
        bomDetection(sample, n)?.let { return it }
        if (n == 0) return EncodingDetection(Charsets.UTF_8, 0.5f)

        var highBytes = 0
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) {
            val x = sample[i].toInt() and 0xFF
            if (x >= 0x80) highBytes++
            if (x == 0) {
                if (i % 2 == 0) evenZeros++ else oddZeros++
            }
        }
        if (highBytes == 0) return EncodingDetection(Charsets.UTF_8, 0.5f)

        val zeros = evenZeros + oddZeros
        if (zeros * 20 > n) {
            if (evenZeros > oddZeros * 9) return EncodingDetection(Charsets.UTF_16LE, 0.8f)
            if (oddZeros > evenZeros * 9) return EncodingDetection(Charsets.UTF_16BE, 0.8f)
        }

        if (isValidUtf8(sample, n)) return EncodingDetection(Charsets.UTF_8, 0.95f)

        val gbkErrors = countLegacyErrors(sample, n, allowFourByte = false, big5 = false)
        val big5Errors = countLegacyErrors(sample, n, allowFourByte = false, big5 = true)
        val gb18030Errors = countLegacyErrors(sample, n, allowFourByte = true, big5 = false)

        if (gb18030Errors == 0 && gbkErrors > 0) return EncodingDetection(GB18030, 0.85f)
        if (gbkErrors == 0 && big5Errors == 0) {
            val simplified = plausibility(sample, n, GBK, SIMPLIFIED_MARKERS)
            val traditional = plausibility(sample, n, BIG5, TRADITIONAL_MARKERS)
            return if (traditional > simplified) {
                EncodingDetection(BIG5, 0.8f)
            } else {
                EncodingDetection(GBK, 0.8f)
            }
        }
        if (minOf(gbkErrors, big5Errors) * 10 < highBytes) {
            val confidence =
                (0.9f * (1f - minOf(gbkErrors, big5Errors).toFloat() / highBytes))
                    .coerceAtLeast(0.3f)
            return EncodingDetection(if (gbkErrors <= big5Errors) GBK else BIG5, confidence)
        }
        return EncodingDetection(GBK, 0.2f)
    }

    fun bomLengthOf(sample: ByteArray, length: Int = sample.size): Int {
        val n = minOf(length, sample.size)
        return when {
            n >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() &&
                sample[2] == 0xBF.toByte() -> 3
            n >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() -> 2
            n >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() -> 2
            else -> 0
        }
    }

    private fun bomDetection(sample: ByteArray, n: Int): EncodingDetection? = when {
        n >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() &&
            sample[2] == 0xBF.toByte() -> EncodingDetection(Charsets.UTF_8, 1.0f)
        n >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() ->
            EncodingDetection(Charsets.UTF_16LE, 1.0f)
        n >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() ->
            EncodingDetection(Charsets.UTF_16BE, 1.0f)
        else -> null
    }

    private fun isTrail(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

    private fun isValidUtf8(b: ByteArray, n: Int): Boolean {
        var i = 0
        var sawMultibyte = false
        while (i < n) {
            val x = b[i].toInt() and 0xFF
            val width = when {
                x < 0x80 -> 1
                x in 0xC2..0xDF -> 2
                x in 0xE0..0xEF -> 3
                x in 0xF0..0xF4 -> 4
                else -> return false
            }
            if (i + width > n) break
            when (width) {
                2 -> if (!isTrail(b[i + 1])) return false
                3 -> {
                    val y = b[i + 1].toInt() and 0xFF
                    val secondOk = when (x) {
                        0xE0 -> y in 0xA0..0xBF
                        0xED -> y in 0x80..0x9F
                        else -> isTrail(b[i + 1])
                    }
                    if (!secondOk || !isTrail(b[i + 2])) return false
                }
                4 -> {
                    val y = b[i + 1].toInt() and 0xFF
                    val secondOk = when (x) {
                        0xF0 -> y in 0x90..0xBF
                        0xF4 -> y in 0x80..0x8F
                        else -> isTrail(b[i + 1])
                    }
                    if (!secondOk || !isTrail(b[i + 2]) || !isTrail(b[i + 3])) return false
                }
            }
            if (width > 1) sawMultibyte = true
            i += width
        }
        return sawMultibyte
    }

    private fun countLegacyErrors(
        b: ByteArray,
        n: Int,
        allowFourByte: Boolean,
        big5: Boolean,
    ): Int {
        var errors = 0
        var i = 0
        while (i < n) {
            val x = b[i].toInt() and 0xFF
            if (x < 0x80) {
                i++
                continue
            }
            if (x !in 0x81..0xFE) {
                errors++
                i++
                continue
            }
            if (i + 1 >= n) break
            val y = b[i + 1].toInt() and 0xFF
            val trailOk = if (big5) {
                y in 0x40..0x7E || y in 0xA1..0xFE
            } else {
                y in 0x40..0xFE && y != 0x7F
            }
            if (trailOk) {
                i += 2
                continue
            }
            if (allowFourByte && y in 0x30..0x39) {
                if (i + 3 >= n) break
                val z = b[i + 2].toInt() and 0xFF
                val w = b[i + 3].toInt() and 0xFF
                if (z in 0x81..0xFE && w in 0x30..0x39) {
                    i += 4
                    continue
                }
            }
            errors++
            i++
        }
        return errors
    }

    private fun plausibility(
        sample: ByteArray,
        n: Int,
        charset: Charset,
        markers: Set<Char>,
    ): Int = String(sample, 0, n, charset).count { it in markers }
}
