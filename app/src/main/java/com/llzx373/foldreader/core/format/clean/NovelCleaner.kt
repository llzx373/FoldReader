package com.llzx373.foldreader.core.format.clean

import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer

/**
 * 网络小说文本智能清理引擎。
 *
 * 全程**流式**处理：只在需要上下文的阶段保留一行 pushback，绝不把整本书读进内存——
 * FoldReader 支持 100MB+ 的 TXT，清洗不能破坏这条底线。
 *
 * 管线顺序（每一步都有理由，不要随手调换）：
 *
 * ```
 * 计数 → 繁简 → 字符归一 → 行内空白 → 噪音过滤 → 标点（省略号/破折号）
 *      → 段落重组 → 章节修复 → 引号规整 → 段首缩进 → 写出
 * ```
 *
 * - 繁简最先，后面的规则（尤其是章节标题）看到的就是统一字形。
 * - 段首缩进**必须最后**：段落重组与章节修复都要靠「有没有缩进」判断行是不是新段落。
 * - 省略号/破折号在重组之前：`……` 结尾的行本来就该算句子结束，先归位才判得准。
 * - 引号规整在重组之后：被换行拆开的引号要等拼回一句话才有意义。
 *
 * 全部规则都保证**幂等**（对已清理过的文本再跑一遍结果不变），「智能整理」可以放心重复执行。
 */
object NovelCleaner {

    fun clean(
        text: String,
        profile: CleanProfile,
        tsMap: Map<Char, Char> = emptyMap(),
    ): String {
        if (profile.isNoop) return text
        val writer = StringWriter(text.length)
        cleanStream(BufferedReader(StringReader(text)), writer, profile, tsMap)
        return writer.toString()
    }

    /** 只回报告与改动样例（预览用），不产出文本。 */
    fun preview(
        sample: String,
        profile: CleanProfile,
        tsMap: Map<Char, Char> = emptyMap(),
    ): CleanReport {
        if (profile.isNoop) return CleanReport()
        val writer = StringWriter(sample.length)
        return cleanStream(BufferedReader(StringReader(sample)), writer, profile, tsMap)
    }

    fun cleanStream(
        reader: BufferedReader,
        writer: Writer,
        profile: CleanProfile,
        tsMap: Map<Char, Char> = emptyMap(),
    ): CleanReport {
        val report = CleanReportBuilder()
        if (profile.isNoop) {
            reader.copyTo(writer)
            writer.flush()
            return report.build()
        }

        val sink = buildPipeline(profile, tsMap, report, writer)
        reader.forEachLine { sink.accept(it) }
        sink.flush()
        writer.flush()
        return report.build()
    }

    private fun buildPipeline(
        profile: CleanProfile,
        tsMap: Map<Char, Char>,
        report: CleanReportBuilder,
        writer: Writer,
    ): LineSink {
        val toggles = profile.toggles
        var sink: LineSink = WriterSink(writer, report)
        sink = IndentSink(toggles.canonicalIndent, sink)
        sink = QuoteSink(toggles.normalizeQuotes, report, sink)
        sink = ChapterRepairSink(
            repair = toggles.repairChapters,
            dedupe = toggles.dedupeChapterTitles,
            report = report,
            next = sink,
        )
        sink = ReflowSink(
            merge = toggles.reflowParagraphs,
            collapseBlanks = toggles.collapseBlankLines,
            report = report,
            next = sink,
        )
        sink = PunctuationSink(
            enabled = toggles.normalizePunctuation,
            collapseRepeated = toggles.normalizeRepeatedPunctuation,
            report = report,
            next = sink,
        )
        // 行中标题必须**在段落重组之前**提出来：否则「上一章末行 + 新章标题」会被重组粘成一行，
        // 标题再也不是行尾，就永远切不出来了。
        sink = ChapterSplitSink(toggles.repairChapters, report, sink)
        sink = NoiseSink(toggles, profile.adPatterns, report, sink)
        sink = LineSinkImpl(toggles, sink)
        sink = CharSink(
            enabled = toggles.unifyChars,
            convert = toggles.traditionalToSimplified,
            tsMap = tsMap,
            report = report,
            next = sink,
        )
        return CountingSink(report, sink)
    }
}

/** 管线的一段。需要跨行上下文时自行持有一行 pushback，[flush] 时吐出。 */
internal interface LineSink {
    fun accept(line: String)

    fun flush()
}

/** 计数输入侧：行数、字符数。 */
private class CountingSink(
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        report.linesIn++
        report.charsIn += line.length + 1L
        next.accept(line)
    }

    override fun flush() = next.flush()
}

/** 写出。每个逻辑行补一个 `\n`，与旧清洗器一致。 */
private class WriterSink(
    private val writer: Writer,
    private val report: CleanReportBuilder,
) : LineSink {
    override fun accept(line: String) {
        writer.write(line)
        writer.write("\n")
        report.linesOut++
        report.charsOut += line.length + 1L
    }

    override fun flush() = Unit
}

/** 字符归一 + 繁简转换。繁简**由开关决定**，调用方即使把字表传进来也不会擅自转换。 */
private class CharSink(
    private val enabled: Boolean,
    private val convert: Boolean,
    private val tsMap: Map<Char, Char>,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    private val converting = convert && tsMap.isNotEmpty()

    override fun accept(line: String) {
        var out = line
        if (enabled) out = CharRules.normalize(out)
        if (converting) {
            val sb = StringBuilder(out.length)
            var changed = false
            for (c in out) {
                val mapped = tsMap[c]
                if (mapped != null) changed = true
                sb.append(mapped ?: c)
            }
            if (changed) out = sb.toString()
        }
        if (out != line) {
            report.charFixes++
            report.sample(CleanReport.Sample.Kind.CHAR, line, out)
        }
        next.accept(out)
    }

    override fun flush() = next.flush()
}

/** 行尾空白 + 行内连续空白。 */
private class LineSinkImpl(
    private val toggles: CleanToggles,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        var out = line
        if (toggles.collapseSpaces) out = WhitespaceRules.collapseRuns(out)
        if (toggles.trimLines) out = WhitespaceRules.trimTrailing(out)
        next.accept(out)
    }

    override fun flush() = next.flush()
}

/** 整行噪音过滤 + 行内噪音切除 + 遮蔽符号规整。 */
private class NoiseSink(
    private val toggles: CleanToggles,
    private val userPatterns: List<Regex>,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        val rule = NoiseRules.isNoiseLine(
            line = line,
            userPatterns = userPatterns,
            inlineExcise = toggles.exciseInlineNoise,
            builtInRules = toggles.filterNoise,
        )
        if (rule != null) {
            report.removedNoiseLines++
            report.sample(CleanReport.Sample.Kind.REMOVED, line, "（删除：$rule）")
            return
        }
        var out = line
        if (toggles.maskRuns) out = NoiseRules.normalizeMaskRuns(out)
        if (toggles.exciseInlineNoise) {
            val excised = NoiseRules.exciseInline(out)
            if (excised != out) {
                if (excised.isBlank()) {
                    // 整行本来就只是个网址/尾标，切完什么都不剩 → 按噪音整行删除，别留个空行
                    report.removedNoiseLines++
                    report.sample(CleanReport.Sample.Kind.REMOVED, out, "（删除：行内噪音占满整行）")
                    return
                }
                report.excisedInlineNoise++
                report.sample(CleanReport.Sample.Kind.INLINE, out, excised)
            }
            out = excised
        }
        next.accept(out)
    }

    override fun flush() = next.flush()
}

/** 省略号 / 破折号 / 重复标点（重组之前）。 */
private class PunctuationSink(
    private val enabled: Boolean,
    private val collapseRepeated: Boolean,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        if (!enabled && !collapseRepeated) {
            next.accept(line)
            return
        }
        var out = line
        if (enabled) out = PunctuationRules.normalizePre(out)
        // 顺序要紧：省略号先归位成 `……`，重复标点折叠才不敢把它折成一个
        if (collapseRepeated) out = PunctuationRules.collapseRepeated(out)
        if (out != line) {
            report.punctuationFixed++
            report.sample(CleanReport.Sample.Kind.CHAR, line, out)
        }
        next.accept(out)
    }

    override fun flush() = next.flush()
}

/**
 * 段落重组 + 空行规整。持有一行 pushback，并在「空行两侧的两行本就该合并」时把空行吞掉
 * （问题 3 的「段落中间有空行」）。
 */
private class ReflowSink(
    private val merge: Boolean,
    private val collapseBlanks: Boolean,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    private var pending: String? = null
    private var pendingBlanks = 0

    override fun accept(line: String) {
        if (line.isBlank()) {
            pendingBlanks++
            return
        }
        val prev = pending
        if (prev == null) {
            flushBlanks(atStart = true)
            pending = line
            return
        }
        if (merge && pendingBlanks <= 1 && ParagraphRules.shouldMerge(prev, line)) {
            val merged = ParagraphRules.join(prev, line)
            report.mergedParagraphs++
            if (pendingBlanks > 0) report.blankLinesRemoved += pendingBlanks
            report.sample(CleanReport.Sample.Kind.MERGED, "$prev ⏎ $line", merged)
            pending = merged
            pendingBlanks = 0
            return
        }
        next.accept(prev)
        flushBlanks(atStart = false, prevLine = prev, nextLine = line)
        pending = line
        pendingBlanks = 0
    }

    override fun flush() {
        val last = pending
        pending = null
        last?.let { next.accept(it) }
        // 结尾的空行一律丢弃
        flushBlanks(atStart = false, atEnd = true)
        next.flush()
    }

    private fun flushBlanks(
        atStart: Boolean,
        atEnd: Boolean = false,
        prevLine: String? = null,
        nextLine: String? = null,
    ) {
        if (pendingBlanks == 0) return
        val keep = if (!collapseBlanks) {
            pendingBlanks
        } else {
            BlankLineRules.resolve(
                blankCount = pendingBlanks,
                indentedContext = hasIndent(prevLine) || hasIndent(nextLine),
                atStart = atStart,
                atEnd = atEnd,
            )
        }
        report.blankLinesRemoved += pendingBlanks - keep
        repeat(keep) { next.accept("") }
        pendingBlanks = 0
    }

    /** 段首缩进已被 [WhitespaceRules] 统一成半角空格，所以这里只看 `' '`。 */
    private fun hasIndent(line: String?): Boolean = line != null && line.startsWith(" ")
}

/**
 * 行中章节标题提行（问题 5）。放在段落重组**之前**：重组会把「上一章末行 + 新章标题」粘成一行，
 * 而提行需要标题位于行尾。
 */
private class ChapterSplitSink(
    private val enabled: Boolean,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        if (!enabled || line.isEmpty()) {
            next.accept(line)
            return
        }
        val split = ChapterRepairRules.splitMidLineTitle(line)
        if (split == null) {
            next.accept(line)
            return
        }
        report.chapterTitlesRepaired++
        report.sample(CleanReport.Sample.Kind.CHAPTER, line, "${split.first} ⏎ ${split.second}")
        next.accept(split.first)
        next.accept(split.second)
    }

    override fun flush() = next.flush()
}

/**
 * 章节命名规范化、连续重复标题去重、正文开头目录块删除。
 *
 * 目录块的判据是**标题重复**：正文开头的标题串里若出现一个已见过的标题，说明前面的那一段是目录、
 * 从这个重复的标题开始才是正文。比「连续 N 个标题就是目录」可靠得多——后者会误删真章节标题。
 */
private class ChapterRepairSink(
    private val repair: Boolean,
    private val dedupe: Boolean,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    private var started = false
    private val leadingTitles = ArrayList<String>()
    private var leadingBlanks = 0
    private var tocHeaderSeen = false

    /** `leadingTitles[0, tocCut)` 是目录块；-1 表示没识别出目录。 */
    private var tocCut = -1
    private var lastEmitted: String? = null

    override fun accept(line: String) {
        if (!repair && !dedupe) {
            next.accept(line)
            return
        }
        if (!started) {
            handleLeading(line)
            return
        }
        emit(line)
    }

    override fun flush() {
        if (!started) {
            // 全书只有标题（空章）也要吐出来，不能因为「攒不够目录块」把整本丢掉
            started = true
            for (i in firstKeptIndex() until leadingTitles.size) emit(leadingTitles[i])
            leadingTitles.clear()
        }
        next.flush()
    }

    private fun handleLeading(line: String) {
        if (line.isBlank()) {
            // 空行的去留已经由上游（空行规整）定过了，这里只暂存，识别出目录块时才连它一起删
            leadingBlanks++
            return
        }
        if (repair && ChapterRepairRules.isTocHeader(line)) {
            tocHeaderSeen = true
            return
        }
        val title = asTitle(line)
        if (title != null) {
            if (tocCut < 0 &&
                leadingTitles.size >= ChapterRepairRules.MIN_TOC_ENTRIES &&
                leadingTitles.contains(title)
            ) {
                tocCut = leadingTitles.size
            }
            leadingTitles += title
            return
        }
        started = true
        val from = firstKeptIndex()
        if (from > 0) {
            report.tocLinesRemoved += from + leadingBlanks + if (tocHeaderSeen) 1 else 0
            report.sample(
                CleanReport.Sample.Kind.REMOVED,
                leadingTitles.first(),
                "（目录块，共 $from 行）",
            )
        } else if (tocHeaderSeen) {
            report.tocLinesRemoved += 1
        }
        for (i in from until leadingTitles.size) emit(leadingTitles[i])
        leadingTitles.clear()
        tocHeaderSeen = false
        if (from == 0) {
            // 不是目录块：标题与正文之间原本的空行要原样还回去
            repeat(leadingBlanks) { next.accept("") }
        }
        leadingBlanks = 0
        emit(line)
    }

    private fun firstKeptIndex(): Int = if (tocCut >= 0) tocCut else 0

    /** 行是不是章节标题；是则返回规范化后的标题，否则 null。正文行原样返回给调用方。 */
    private fun asTitle(line: String): String? {
        if (!repair) return if (ChapterRepairRules.isTitle(line)) line.trim() else null
        val canonical = ChapterRepairRules.canonicalizeTitle(line)
        return canonical.takeIf { ChapterRepairRules.isTitle(it) }
    }

    private fun emit(raw: String) {
        if (raw.isBlank()) {
            next.accept(raw)
            return
        }
        val title = asTitle(raw)
        var line = raw
        if (title != null) {
            if (title != raw) report.chapterTitlesRepaired++
            line = title
        }
        if (dedupe && title != null && lastEmitted == line) {
            report.chapterTitlesDeduped++
            report.sample(CleanReport.Sample.Kind.REMOVED, line, "（重复标题）")
            return
        }
        lastEmitted = line
        next.accept(line)
    }
}

/** 引号规整（重组之后）。 */
private class QuoteSink(
    private val enabled: Boolean,
    private val report: CleanReportBuilder,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        if (!enabled || line.isEmpty()) {
            next.accept(line)
            return
        }
        val out = PunctuationRules.normalizeQuotes(line)
        if (out != line) {
            report.punctuationFixed++
            report.sample(CleanReport.Sample.Kind.CHAR, line, out)
        }
        next.accept(out)
    }

    override fun flush() = next.flush()
}

/** 段首缩进统一；章节标题顶格。 */
private class IndentSink(
    private val enabled: Boolean,
    private val next: LineSink,
) : LineSink {
    override fun accept(line: String) {
        if (!enabled || line.isEmpty()) {
            next.accept(line)
            return
        }
        val out = if (ChapterRepairRules.isTitle(line)) {
            line.trimStart()
        } else {
            WhitespaceRules.canonicalizeIndent(line)
        }
        next.accept(out)
    }

    override fun flush() = next.flush()
}
