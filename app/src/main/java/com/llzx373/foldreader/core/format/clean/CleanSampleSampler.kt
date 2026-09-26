package com.llzx373.foldreader.core.format.clean

/**
 * 脏文本采样器（M16）：从完整 TXT 文本中截取一份「给 AI 看」的样本，供其推荐清洗配方。
 *
 * 纯 JVM 组件，不依赖 Android；输入是原始源文件读出的完整文本。
 * 采样偏重**头尾**——下载站广告、防盗声明、推广行集中在开头与结尾，
 * 中段再补一刀让 AI 能看到正文的真实排版（缩进习惯、硬换行、遮蔽符号）。
 *
 * 输出确定性：同一份文本永远采出同一份样本（AI 外发台账审计可复现）。
 */
object CleanSampleSampler {

    /** 头部采样字符数：开头是站点信息/简介/广告高发区。 */
    const val HEAD_CHARS = 2048

    /** 中段采样字符数：看正文排版习惯（缩进、硬换行、行内噪音）。 */
    const val MIDDLE_CHARS = 2048

    /** 尾部采样字符数：结尾是「完本宣传/下一本书推广」高发区。 */
    const val TAIL_CHARS = 2048

    /** 三段拼接处的省略标记。 */
    private const val SECTION_SEPARATOR = "\n……（中间省略）……\n"

    /**
     * 采样。
     *
     * 文本不超过三段预算之和时原样返回；否则取「头 + 中 + 尾」三段拼出。
     * 各段按整行对齐（丢掉被截断的半行），避免 AI 拿半行误判规则。
     *
     * @return 采样文本；空文本返回空串
     */
    fun sample(
        text: String,
        headChars: Int = HEAD_CHARS,
        middleChars: Int = MIDDLE_CHARS,
        tailChars: Int = TAIL_CHARS,
    ): String {
        if (text.isEmpty()) return ""
        if (text.length <= headChars + middleChars + tailChars) return text

        val head = text.substring(0, headChars).dropLastPartialLine()
        val tail = text.substring(text.length - tailChars).dropFirstPartialLine()

        // 中段以全文正中为锚，不跟头尾重叠（文本长于三段预算之和，区间必有解）
        val middleStart = (text.length / 2 - middleChars / 2)
            .coerceIn(headChars, text.length - tailChars - middleChars)
        val middle = text.substring(middleStart, middleStart + middleChars)
            .dropFirstPartialLine()
            .dropLastPartialLine()

        return head + SECTION_SEPARATOR + middle + SECTION_SEPARATOR + tail
    }

    /** 丢掉末尾被截断的半行（没有换行符则原样保留）。 */
    private fun String.dropLastPartialLine(): String {
        val cut = lastIndexOf('\n')
        return if (cut > 0) substring(0, cut) else this
    }

    /** 丢掉开头被截断的半行（没有换行符则原样保留）。 */
    private fun String.dropFirstPartialLine(): String {
        val cut = indexOf('\n')
        return if (cut in 1 until length - 1) substring(cut + 1) else this
    }
}
