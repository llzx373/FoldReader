package com.llzx373.foldreader.core.format.clean

/**
 * 空行规整（问题 3）。
 *
 * 判据也是**局部**的，而且必须与段落重组的边界判据一致——否则会出现「这一步删掉空行、
 * 那一步靠空行判断边界」的自相矛盾，直接破坏幂等（真实文件上踩到过）。
 *
 * 一个空行是**冗余**的，当且仅当下一行**自己就能站稳**、不需要空行来标出「我是新段落」：
 *
 * - 下一行**有缩进** → 缩进已经说明它是段落起点 → 冗余；
 * - 下一行是**章节标题**（含 `第 2 章` 这种要先规范化的写法）→ 标题本身就有结构
 *   → 冗余。但**前提是这本书确实用缩进分段**（上一段是缩进起头的）：空行分段的书里，
 *   作者的空行是排版意图，别去动它（见 `negatives/04`）。
 *
 * 其余情况（下一行顶格、又不是标题）→ 空行是**唯一**的段落边界信号，必须留一个。
 * 这正是合集里「空行 + 顶格分篇标题」的形态：删掉它就等于把分篇标题并进了上一段。
 *
 * 「段落在中间被插了空行」不在这里处理——那属于段落重组：空行两侧的两行若满足合并条件，
 * 空行会被 [NovelCleaner] 的重组阶段直接吞掉。
 */
internal object BlankLineRules {

    /**
     * @param blankCount 这段连续空行的个数
     * @param prevLine 空行之前的那一行（null 表示位于全文开头）
     * @param nextLine 空行之后的那一行（null 表示位于全文结尾）
     * @return 应保留的空行个数
     */
    fun resolve(blankCount: Int, prevLine: String?, nextLine: String?): Int {
        if (blankCount <= 0) return 0
        if (prevLine == null || nextLine == null) return 0 // 全文开头/结尾的空行一律删除
        if (WhitespaceRules.hasLeadingWhitespace(nextLine)) return 0
        if (WhitespaceRules.hasLeadingWhitespace(prevLine) && ChapterRepairRules.looksLikeTitle(nextLine)) {
            return 0
        }
        return 1
    }
}
