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
 * 计数 → 繁简 → 字符归一 → 行内空白 → 噪音过滤 → 行中标题提行
 *      → 段落重组 → 标点（省略号/破折号/重复标点） → 章节修复 → 引号规整 → 段首缩进 → 写出
 * ```
 *
 * - 繁简最先，后面的规则（尤其是章节标题）看到的就是统一字形。
 * - 段首缩进**必须最后**：段落重组与章节修复都要靠「有没有缩进」判断行是不是新段落。
 * - 行中标题提行在段落重组**之前**：否则「上一章末行 + 新章标题」会被重组粘成一行，
 *   标题再也不是行尾，就永远切不出来了。
 * - 标点与引号规整都在段落重组**之后**：被硬换行切断的 `…` + `…` 要先拼回 `……` 再规整，
 *   否则每一半都会被各自补成 `……`（多出一倍）；引号内被拆开的空白同理。
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

    /**
     * 组装管线。
     *
     * 各阶段在 [stages] 里**按数据流顺序排列**（读入端在前、写出端在后），再用 `foldRight`
     * 从写出端往读入端折起来。这样这份列表的顺序**就是**真实处理顺序，
     * 不必再把「赋值即向外包一层」在脑子里倒过来读。
     *
     * 之所以要这么写：原先是一行一个 `sink = XxxSink(next = sink)`，赋值顺序与数据流方向
     * **相反**；改顺序时只改注释、没动位置，看上去改了其实等于没改（踩过一次）。
     */
    private fun buildPipeline(
        profile: CleanProfile,
        tsMap: Map<Char, Char>,
        report: CleanReportBuilder,
        writer: Writer,
    ): LineSink {
        val t = profile.toggles
        val stages: List<(LineSink) -> LineSink> = listOf(
            // 繁简最先：后面的规则（尤其章节标题）看到的就是统一字形
            { down ->
                CharSink(
                    enabled = t.unifyChars,
                    convert = t.traditionalToSimplified,
                    tsMap = tsMap,
                    report = report,
                    next = down,
                )
            },
            { down -> LineSinkImpl(t, down) },
            { down -> NoiseSink(t, profile.adPatterns, report, down) },
            // 行中标题必须在段落重组**之前**提出：否则「上一章末行 + 新章标题」会被重组粘成
            // 一行，标题再也不是行尾，就永远切不出来了
            { down -> ChapterSplitSink(t.repairChapters, report, down) },
            { down ->
                ReflowSink(
                    merge = t.reflowParagraphs,
                    collapseBlanks = t.collapseBlankLines,
                    report = report,
                    next = down,
                )
            },
            // 标点必须在段落重组**之后**：被硬换行切断的 `…` + `…` 要先拼回 `……` 再规整，
            // 否则每一半都会被各自补成 `……`，合起来多出一倍
            { down ->
                PunctuationSink(
                    enabled = t.normalizePunctuation,
                    collapseRepeated = t.normalizeRepeatedPunctuation,
                    report = report,
                    next = down,
                )
            },
            { down ->
                ChapterRepairSink(
                    repair = t.repairChapters,
                    dedupe = t.dedupeChapterTitles,
                    report = report,
                    next = down,
                )
            },
            { down -> QuoteSink(t.normalizeQuotes, report, down) },
            // 段首缩进统一必须**最后**：段落重组与章节修复都要靠「有没有缩进」判断段落边界
            { down -> IndentSink(t.canonicalIndent, down) },
        )
        val tail: LineSink = stages.foldRight(WriterSink(writer, report) as LineSink) { stage, down ->
            stage(down)
        }
        return CountingSink(report, tail)
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

    /** 当前这一段是不是**用缩进起头**的（决定「无缩进的下一行」算不算续行）。 */
    private var pendingIndented = false

    override fun accept(line: String) {
        if (line.isBlank()) {
            pendingBlanks++
            return
        }
        val prev = pending
        if (prev == null) {
            flushBlanks(prevLine = null, nextLine = line) // 全文开头的空行
            pending = line
            pendingIndented = WhitespaceRules.hasLeadingWhitespace(line)
            return
        }
        val canMerge = ParagraphRules.shouldMerge(prev, line, pendingIndented, pendingBlanks)
        if (merge && canMerge) {
            val merged = ParagraphRules.join(prev, line)
            report.mergedParagraphs++
            if (pendingBlanks > 0) report.blankLinesRemoved += pendingBlanks
            report.sample(CleanReport.Sample.Kind.MERGED, "$prev ⏎ $line", merged)
            pending = merged
            pendingBlanks = 0
            return
        }
        next.accept(prev)
        flushBlanks(prevLine = prev, nextLine = line)
        pending = line
        pendingIndented = WhitespaceRules.hasLeadingWhitespace(line)
        pendingBlanks = 0
    }

    override fun flush() {
        val last = pending
        pending = null
        last?.let { next.accept(it) }
        // 结尾的空行一律丢弃（nextLine 为 null）
        flushBlanks(prevLine = last, nextLine = null)
        next.flush()
    }

    private fun flushBlanks(prevLine: String? = null, nextLine: String? = null) {
        if (pendingBlanks == 0) return
        val keep = if (!collapseBlanks) {
            pendingBlanks
        } else {
            BlankLineRules.resolve(pendingBlanks, prevLine, nextLine)
        }
        report.blankLinesRemoved += pendingBlanks - keep
        repeat(keep) { next.accept("") }
        pendingBlanks = 0
    }
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
        // 正文侧要带上原文的段首缩进：它是这一段的段首，丢了缩进就等于把段落起点也丢了
        // （下游的段首缩进统一只认「有缩进」这个信号）。
        val lead = line.takeWhile { it.isWhitespace() }
        next.accept(lead + split.first)
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
