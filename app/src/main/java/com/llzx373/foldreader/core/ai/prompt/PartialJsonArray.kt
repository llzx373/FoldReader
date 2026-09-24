package com.llzx373.foldreader.core.ai.prompt

/**
 * 流式 JSON 数组的增量提取（M19 按页翻译流式渲染用）。
 *
 * 模型按 `{"paragraphs":["a","b","c...` 流式吐出，每到一个增量就调一次
 * [extractCompleteStrings]，拿到**已闭合**的字符串元素边生成边显示；
 * 末尾尚未闭合的半截元素（含半截转义序列）一律忽略，等下一轮增量。
 *
 * 纯解析、不校验键名：从第一个 `[` 起按 JSON 字符串语法扫描，
 * 转义（`\"` `\\` `\/` `\n` `\t` `\r` `\b` `\f` `\uXXXX`）正确解码。
 */
object PartialJsonArray {

    fun extractCompleteStrings(partialJsonPrefix: String): List<String> {
        val out = ArrayList<String>()
        var i = partialJsonPrefix.indexOf('[')
        if (i < 0) return out
        i += 1
        val n = partialJsonPrefix.length

        while (i < n) {
            // 元素间：只允许空白、逗号或下一个字符串的开头
            when (partialJsonPrefix[i]) {
                ' ', '\t', '\n', '\r', ',' -> {
                    i += 1
                    continue
                }
                '"' -> Unit
                else -> return out // 数组结束（']' '}'）或畸形前缀：已拿到的完整元素照返
            }
            // 扫描一个字符串元素
            i += 1
            val element = StringBuilder()
            var closed = false
            while (i < n) {
                val ch = partialJsonPrefix[i]
                when {
                    ch == '"' -> {
                        closed = true
                        i += 1
                        break
                    }
                    ch == '\\' -> {
                        if (i + 1 >= n) return out // 半截转义：元素作废
                        when (val esc = partialJsonPrefix[i + 1]) {
                            '"' -> element.append('"')
                            '\\' -> element.append('\\')
                            '/' -> element.append('/')
                            'n' -> element.append('\n')
                            't' -> element.append('\t')
                            'r' -> element.append('\r')
                            'b' -> element.append('\b')
                            'f' -> element.append('\u000C')
                            'u' -> {
                                if (i + 5 >= n) return out // 半截 \uXXXX：元素作废
                                val hex = partialJsonPrefix.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: return out
                                element.append(code.toChar())
                                i += 4
                            }
                            else -> element.append(esc) // 未知转义按字面保留
                        }
                        i += 2
                    }
                    else -> {
                        element.append(ch)
                        i += 1
                    }
                }
            }
            if (!closed) return out // 末尾半截元素：忽略
            out += element.toString()
        }
        return out
    }
}
