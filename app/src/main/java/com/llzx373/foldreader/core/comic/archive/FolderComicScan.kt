package com.llzx373.foldreader.core.comic.archive

import com.llzx373.foldreader.core.comic.ComicPageOrdering

/** 目录漫画扫描时的一个子项。[key] 是 SAF 文档 Uri（或本地路径），[relativePath] 用于排序与展示。 */
data class ComicDirChild(
    val name: String,
    val isDirectory: Boolean,
    val key: String,
    val relativePath: String = name,
)

/**
 * 把「一个目录」读成一本书的页列表（纯逻辑，列目录通过 [childrenOf] 注入，便于 JVM 单测）。
 *
 * 规则：目录**直接**含图片就只取这些图片；否则下探子目录（最多 [depth] 层）把各子目录的页按路径拼接。
 * 这样「目录本身就是一堆图」、「目录下按话分文件夹」、「再往里分 part 文件夹」三种结构都能读；
 * 若目录每层都有图片，则以最上层为准——避免把缩略图目录也拼进来。
 */
object ComicFolderScan {

    const val DEFAULT_DEPTH = 2

    fun selectPages(
        root: ComicDirChild,
        depth: Int = DEFAULT_DEPTH,
        childrenOf: (ComicDirChild) -> List<ComicDirChild>,
    ): List<ComicDirChild> {
        val out = ArrayList<ComicDirChild>()
        collect(root, prefix = "", depth = depth, childrenOf = childrenOf, out = out)
        return out.sortedWith { a, b -> ComicPageOrdering.compareNatural(a.relativePath, b.relativePath) }
    }

    private fun collect(
        dir: ComicDirChild,
        prefix: String,
        depth: Int,
        childrenOf: (ComicDirChild) -> List<ComicDirChild>,
        out: MutableList<ComicDirChild>,
    ) {
        val children = childrenOf(dir)
        val pages = children.filter { !it.isDirectory && isPage(it.name) }
        if (pages.isNotEmpty()) {
            pages.forEach { out += it.copy(relativePath = prefix + it.name) }
            return
        }
        if (depth <= 0) return
        children.filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .forEach { sub -> collect(sub, "$prefix${sub.name}/", depth - 1, childrenOf, out) }
    }

    private fun isPage(name: String): Boolean =
        !ComicPageOrdering.isJunkPath(name) && ComicPageOrdering.isImageName(name)
}
