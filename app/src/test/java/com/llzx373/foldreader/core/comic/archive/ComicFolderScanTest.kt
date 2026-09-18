package com.llzx373.foldreader.core.comic.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicFolderScanTest {

    private fun dir(name: String, key: String = name) = ComicDirChild(name, true, key)
    private fun file(name: String, key: String = name) = ComicDirChild(name, false, key)

    private fun tree(vararg nodes: Pair<String, List<ComicDirChild>>): (ComicDirChild) -> List<ComicDirChild> {
        val map = nodes.toMap()
        return { node -> map[node.key].orEmpty() }
    }

    @Test
    fun `目录直接含图片时只用这些图片`() {
        val children = tree(
            "root" to listOf(file("10.jpg"), file("2.jpg"), file("1.jpg"), file("readme.txt")),
        )

        val pages = ComicFolderScan.selectPages(dir("root"), childrenOf = children)

        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), pages.map { it.name })
    }

    @Test
    fun `没有直接图片时下探子目录并跨目录拼接`() {
        val children = tree(
            "root" to listOf(dir("第02话"), dir("第01话")),
            "第01话" to listOf(file("2.jpg"), file("1.jpg")),
            "第02话" to listOf(file("1.jpg")),
        )

        val pages = ComicFolderScan.selectPages(dir("root"), childrenOf = children)

        assertEquals(
            listOf("第01话/1.jpg", "第01话/2.jpg", "第02话/1.jpg"),
            pages.map { it.relativePath },
        )
    }

    @Test
    fun `有直接图片时不把子目录内容也拼进来`() {
        val children = tree(
            "root" to listOf(file("001.jpg"), dir("thumbs")),
            "thumbs" to listOf(file("t1.jpg")),
        )

        val pages = ComicFolderScan.selectPages(dir("root"), childrenOf = children)

        assertEquals(listOf("001.jpg"), pages.map { it.relativePath })
    }

    @Test
    fun `深度用尽后不再下探`() {
        val children = tree(
            "root" to listOf(dir("a")),
            "a" to listOf(dir("b")),
            "b" to listOf(file("p.jpg")),
        )

        assertEquals(
            emptyList<String>(),
            ComicFolderScan.selectPages(dir("root"), depth = 1, childrenOf = children)
                .map { it.relativePath },
        )
        assertEquals(
            listOf("a/b/p.jpg"),
            ComicFolderScan.selectPages(dir("root"), depth = 2, childrenOf = children)
                .map { it.relativePath },
        )
    }

    @Test
    fun `噪音图片文件被忽略`() {
        val children = tree(
            "root" to listOf(file("._cover.jpg"), file(".hidden.jpg"), file("Thumbs.db"), file("ok.jpg")),
        )

        val pages = ComicFolderScan.selectPages(dir("root"), childrenOf = children)

        assertEquals(listOf("ok.jpg"), pages.map { it.relativePath })
    }
}
