package com.llzx373.foldreader.core.format.clean

/**
 * 空行规整（问题 3）。
 *
 * 判据也是**局部**的：看这段空行**两侧的行有没有缩进**。
 * - 有一侧带缩进 → 这本书用缩进标记段落，空行是多余的（排版层本来就有段间距）→ 全删；
 * - 两侧都没有缩进 → 空行是唯一的分段标记 → 连续多个只留一个。
 *
 * 不用「全书缩进占比」那种全局判据：它会随清洗而漂移，破坏幂等（清洗一次删空行、
 * 清洗两次又不删）。局部判据在第二次执行时看到的相邻行完全一样，结论必然一致。
 *
 * 「段落在中间被插了空行」不在这里处理——那属于段落重组：空行两侧的两行若满足合并条件，
 * 空行会被 [NovelCleaner] 的重组阶段直接吞掉。
 */
internal object BlankLineRules {

    /**
     * @param blankCount 这段连续空行的个数
     * @param indentedContext 两侧任意一侧的行带段首缩进
     * @param atStart 位于全文开头
     * @param atEnd 位于全文结尾
     * @return 应保留的空行个数
     */
    fun resolve(blankCount: Int, indentedContext: Boolean, atStart: Boolean, atEnd: Boolean): Int {
        if (blankCount <= 0) return 0
        if (atStart || atEnd) return 0
        return if (indentedContext) 0 else 1
    }
}
