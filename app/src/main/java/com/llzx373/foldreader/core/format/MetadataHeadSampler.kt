package com.llzx373.foldreader.core.format

/**
 * 元数据采样器（M17）：从解码后的书籍开头文本中截取一份「给 AI 看」的头部样本，
 * 供其归纳作者/简介/题材标签。
 *
 * 纯 JVM 组件，不依赖 Android；输入是源文件头部字节按书籍编码解码后的文本
 * （字节级读取与解码在调用侧——VM factory 用 `UriChannels.readHead` 只读前几 KB，
 * 不像 M15/M16 那样整本加载）。
 * 只取**开头**：TXT 网文的书名/作者/站点信息集中在卷首，题材由开篇文风判断。
 *
 * 输出确定性：同一份文本永远采出同一份样本（AI 外发台账审计可复现）。
 */
object MetadataHeadSampler {

    /** 头部采样字符数上限：够看到书名页/简介/第一章开头，又不至于把正文大块外发。 */
    const val HEAD_CHARS = 4096

    /**
     * 采样：文本不超过上限时原样返回（trim 后）；否则截到上限并丢掉末尾被截断的半行，
     * 避免 AI 拿半行误判。
     *
     * @return 采样文本；空文本返回空串
     */
    fun sample(decodedHead: String, maxChars: Int = HEAD_CHARS): String {
        val trimmed = decodedHead.trim()
        if (trimmed.length <= maxChars) return trimmed
        val cut = trimmed.substring(0, maxChars)
        val lastNewline = cut.lastIndexOf('\n')
        return if (lastNewline > 0) cut.substring(0, lastNewline) else cut
    }
}
