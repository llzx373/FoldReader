package com.llzx373.foldreader.core.reader

import java.io.File

/**
 * 页边界缓存回收。
 *
 * 页边界文件名含版式指纹，用户每改一次字号/行距/页宽就会多出一份；删书也只会删掉 DB 记录。
 * 两件事都要做，否则 `page_bounds/` 只增不减：
 *  1. 删掉 DB 中已不存在的 bookId 对应的孤儿文件；
 *  2. 同一本书只保留最近修改的 [keepVariantsPerBook] 份版式。
 *
 * 纯文件操作，不依赖 Android，可直接 JVM 单测。
 */
object PageBoundsGc {

    /** 每本书保留的版式份数：够覆盖「常用字号 + 折叠/展开 + 横竖持」的来回切换。 */
    const val KEEP_VARIANTS_PER_BOOK = 4

    /**
     * 扫描 [dir] 并删除多余文件，返回删除数量。
     * [liveBookIds] 为当前书架上的书号集合；目录不存在或为空时返回 0。
     */
    fun sweep(
        dir: File,
        liveBookIds: Set<Long>,
        keepVariantsPerBook: Int = KEEP_VARIANTS_PER_BOOK,
    ): Int {
        val files = dir.listFiles { f -> f.isFile && boundsBookIdOf(f.name) != null } ?: return 0
        var deleted = 0
        for ((bookId, group) in files.groupBy { boundsBookIdOf(it.name) }) {
            if (bookId == null || bookId !in liveBookIds) {
                group.forEach { if (it.delete()) deleted++ }
                continue
            }
            if (group.size <= keepVariantsPerBook) continue
            group.sortedByDescending { it.lastModified() }
                .drop(keepVariantsPerBook)
                .forEach { if (it.delete()) deleted++ }
        }
        return deleted
    }
}
