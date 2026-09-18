package com.llzx373.foldreader.core.format.clean

import android.content.Context

/**
 * 繁体 → 简体（单字级映射）。
 *
 * 映射表来自 OpenCC 的字表，打包在 `assets/ts_map.txt`（每两个字符一组：繁、简）。
 * 单字映射不做词组级消歧（「后面」的「後」与「皇后」的「后」会一并处理），
 * 所以它是**可选开关**而不是默认行为。
 */
object TsCharMap {

    const val ASSET_NAME = "ts_map.txt"

    @Volatile
    private var cached: Map<Char, Char>? = null

    fun load(context: Context): Map<Char, Char> =
        cached ?: synchronized(this) {
            cached ?: parse(context.assets.open(ASSET_NAME).use { it.readBytes() }.toString(Charsets.UTF_8))
                .also { cached = it }
        }

    internal fun parse(text: String): Map<Char, Char> {
        val map = HashMap<Char, Char>(text.length / 2)
        var i = 0
        while (i < text.length) {
            val from = Character.codePointAt(text, i)
            i += Character.charCount(from)
            if (i >= text.length) break
            val to = Character.codePointAt(text, i)
            i += Character.charCount(to)
            if (from <= 0xFFFF && to <= 0xFFFF) map[from.toChar()] = to.toChar()
        }
        return map
    }
}
