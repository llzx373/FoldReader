package com.llzx373.foldreader.core.tts

/** 一段待朗读文本；[charOffset] 是该段在全书中的起始字符偏移（翻页联动的锚点）。 */
data class TtsSegment(val text: String, val charOffset: Long)

/**
 * TTS 听书（M13.1）的整段播放状态。进程级单例（AppContainer 持有控制器），
 * 阅读器按 bookId 过滤后做翻页联动。
 */
data class TtsState(
    val bookId: Long? = null,
    /** 当前朗读到的字符偏移（下一句的起点；播完保持最后一句的起点）。 */
    val charOffset: Long = 0L,
    val playing: Boolean = false,
    /** 一次性错误文案（引擎不可用/不支持中文等）；下次 speak/stop 清空。 */
    val error: String? = null,
)

/**
 * 把一段正文切成适合逐句 TTS 的小段，每段保留全书字符偏移。
 *
 * 规则：
 * 1. 按句末标点（。！？；… 及半角 !?;）与换行切句；连续标点算一句，
 *    紧随的闭引号/闭括号归前一句（”。」）等）；
 * 2. 单句超过 [MAX_SEGMENT_CHARS] 字再按逗号/顿号/冒号二次切；
 * 3. 仍超则硬切。
 * 纯空白段丢弃（没有可读内容），偏移不受影响。
 */
object TtsSentenceSplitter {

    const val MAX_SEGMENT_CHARS = 150

    private val SENTENCE_END = setOf('。', '！', '？', '；', '…', '\n', '!', '?', ';')
    private val CLOSERS = setOf('”', '’', '」', '』', '）', ')', '】', '〉', '》', '"', '\'')
    private val SECONDARY_BREAK = setOf('，', '、', ',', '：', ':')

    fun split(text: String, baseOffset: Long): List<TtsSegment> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<TtsSegment>()
        var segStart = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in SENTENCE_END) {
                var j = i + 1
                // 连续句末标点（？！……）与紧随的闭引号都归这一句
                while (j < text.length && (text[j] in SENTENCE_END || text[j] in CLOSERS)) j++
                emitRange(result, text, segStart, j, baseOffset)
                segStart = j
                i = j
            } else {
                i++
            }
        }
        if (segStart < text.length) emitRange(result, text, segStart, text.length, baseOffset)
        return result
    }

    /** 发一段 [start, end)；超过上限先按次级标点切，切不动再硬切。 */
    private fun emitRange(
        out: MutableList<TtsSegment>,
        text: String,
        start: Int,
        end: Int,
        baseOffset: Long,
    ) {
        var s = start
        while (end - s > MAX_SEGMENT_CHARS) {
            val limit = s + MAX_SEGMENT_CHARS
            var cut = -1
            // 从上限往回找最近的次级断点（逗号/顿号/冒号之后断开）
            for (k in limit - 1 downTo s) {
                if (text[k] in SECONDARY_BREAK) {
                    cut = k + 1
                    break
                }
            }
            if (cut <= s) cut = limit // 没有断点：硬切
            out.addSegment(text, s, cut, baseOffset)
            s = cut
        }
        if (s < end) out.addSegment(text, s, end, baseOffset)
    }

    private fun MutableList<TtsSegment>.addSegment(text: String, start: Int, end: Int, baseOffset: Long) {
        val piece = text.substring(start, end)
        if (piece.isBlank()) return
        add(TtsSegment(piece, baseOffset + start))
    }
}
