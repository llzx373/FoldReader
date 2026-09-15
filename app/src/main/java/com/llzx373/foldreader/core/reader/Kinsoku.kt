package com.llzx373.foldreader.core.reader

object Kinsoku {

    private val lineStartForbidden = "，。、；：？！…—·％）】》」』”’".toSet()
    private val lineEndForbidden = "（【《「『“‘".toSet()

    fun adjust(text: CharSequence, breaks: IntArray): IntArray {
        val n = breaks.size
        if (n == 0) return breaks
        val out = breaks.copyOf()
        for (i in 0 until n - 1) {
            var b = maxOf(out[i], out.getOrElse(i - 1) { 0 })
            while (b < text.length && text[b] in lineStartForbidden) {
                b++
            }
            while (b > out.getOrElse(i - 1) { 0 } + 1 && b - 1 < text.length && text[b - 1] in lineEndForbidden) {
                b--
            }
            out[i] = b
        }
        return out
    }
}
