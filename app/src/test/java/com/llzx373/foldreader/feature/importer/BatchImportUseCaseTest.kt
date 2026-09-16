package com.llzx373.foldreader.feature.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportUseCaseTest {

    private val useCase = BatchImportUseCase(
        importOne = { error("测试不应触达") },
        assignGroup = { _, _ -> error("测试不应触达") },
    )

    private fun treeOf(
        vararg dirs: Pair<String, List<Triple<String, String, String?>>>,
    ): (String) -> List<Triple<String, String, String?>> {
        val map = dirs.toMap()
        return { dirId -> map[dirId].orEmpty() }
    }

    private fun dir(id: String) = Triple(id, id, "vnd.android.document/directory")
    private fun file(id: String, name: String, mime: String? = null) = Triple(id, name, mime)

    @Test
    fun `嵌套目录递归收集支持格式并按名称排序`() {
        val children = treeOf(
            "root" to listOf(
                dir("dirA"),
                file("f1", "b.txt", "text/plain"),
                file("f2", "c.pdf", "application/pdf"),
                file("f3", "note.md"),
            ),
            "dirA" to listOf(
                file("f4", "a.epub", "application/epub+zip"),
                dir("dirB"),
            ),
            "dirB" to listOf(
                file("f5", "y.fb2"),
                file("f6", "x.fb2.zip"),
                file("f7", "z.png", "image/png"),
            ),
        )

        val (found, truncated) = useCase.enumerateTree("root", children)

        assertFalse(truncated)
        assertEquals(
            listOf("a.epub", "b.txt", "x.fb2.zip", "y.fb2"),
            found.map { it.second },
        )
    }

    @Test
    fun `无扩展名但 mime 为 text-plain 的文件也收集`() {
        val children = treeOf(
            "root" to listOf(
                file("f1", "README", "text/plain"),
                file("f2", "cover", "image/jpeg"),
            ),
        )

        val (found, _) = useCase.enumerateTree("root", children)

        assertEquals(listOf("f1" to "README"), found)
    }

    @Test
    fun `达到上限截断并置 truncated`() {
        val children = treeOf(
            "root" to listOf(
                file("f1", "1.txt", "text/plain"),
                file("f2", "2.txt", "text/plain"),
                file("f3", "3.txt", "text/plain"),
                dir("more"),
            ),
            "more" to listOf(file("f4", "4.txt", "text/plain")),
        )

        val (found, truncated) = useCase.enumerateTree("root", children, limit = 2)

        assertTrue(truncated)
        assertEquals(2, found.size)
    }

    @Test
    fun `目录循环引用不会死循环`() {
        val children = treeOf(
            "root" to listOf(dir("dirA"), file("f1", "a.txt", "text/plain")),
            "dirA" to listOf(dir("root"), dir("dirA"), file("f2", "b.txt", "text/plain")),
        )

        val (found, truncated) = useCase.enumerateTree("root", children)

        assertFalse(truncated)
        assertEquals(listOf("a.txt", "b.txt"), found.map { it.second })
    }

    private fun entry(name: String, id: String = name) =
        BatchImportUseCase.DocEntry(name = name, uri = "content://test/$id", documentId = id)

    @Test
    fun `失败与重复不中断且完成后一次性赋组`() = runBlocking {
        val groupCalls = mutableListOf<Pair<List<Long>, String?>>()
        val useCase = BatchImportUseCase(
            importOne = { entry ->
                when (entry.name) {
                    "甲.txt" -> ImportBookUseCase.Result.Imported(1L, "甲", 1f)
                    "乙.txt" -> ImportBookUseCase.Result.DuplicateSameHash(9L, "乙")
                    "丙.txt" -> ImportBookUseCase.Result.Failure("解析失败")
                    else -> ImportBookUseCase.Result.Imported(2L, "丁", 1f)
                }
            },
            assignGroup = { ids, name -> groupCalls += ids to name },
        )
        val entries = listOf(entry("甲.txt"), entry("乙.txt"), entry("丙.txt"), entry("丁.txt"))
        val progresses = mutableListOf<Pair<Int, Int>>()

        val result = useCase.importDirectory(entries, "新组") { done, total, _ ->
            progresses += done to total
        }

        assertFalse(result.cancelled)
        assertEquals("新组", result.groupName)
        assertEquals(listOf(1L to "甲", 2L to "丁"), result.imported)
        assertEquals(listOf("乙.txt"), result.duplicates.map { it.name })
        assertEquals(listOf("丙.txt"), result.failures.map { it.name })
        assertEquals("解析失败", result.failures.single().reason)
        assertEquals(listOf(listOf(1L, 2L) to "新组"), groupCalls)
        assertEquals(0 to 4, progresses.first())
    }

    @Test
    fun `组名为空白时不赋组`() = runBlocking {
        val groupCalls = mutableListOf<Pair<List<Long>, String?>>()
        val useCase = BatchImportUseCase(
            importOne = { ImportBookUseCase.Result.Imported(1L, "甲", 1f) },
            assignGroup = { ids, name -> groupCalls += ids to name },
        )

        val result = useCase.importDirectory(listOf(entry("甲.txt")), "   ")

        assertTrue(groupCalls.isEmpty())
        assertNull(result.groupName)
        assertEquals(1, result.imported.size)
    }

    @Test
    fun `导入单本抛异常记为失败并继续`() = runBlocking {
        val useCase = BatchImportUseCase(
            importOne = { entry ->
                if (entry.name == "坏.txt") throw java.io.IOException("读取失败")
                ImportBookUseCase.Result.Imported(1L, entry.name, 1f)
            },
            assignGroup = { _, _ -> },
        )

        val result = useCase.importDirectory(listOf(entry("坏.txt"), entry("好.txt")), null)

        assertEquals(listOf("坏.txt"), result.failures.map { it.name })
        assertEquals("读取失败", result.failures.single().reason)
        assertEquals(1, result.imported.size)
        assertFalse(result.cancelled)
    }

    @Test
    fun `协程取消返回部分结果且不赋组`() = runBlocking {
        val groupCalls = mutableListOf<Pair<List<Long>, String?>>()
        val useCase = BatchImportUseCase(
            importOne = { entry ->
                if (entry.name == "乙.txt") throw CancellationException("用户取消")
                ImportBookUseCase.Result.Imported(1L, entry.name, 1f)
            },
            assignGroup = { ids, name -> groupCalls += ids to name },
        )

        val result = useCase.importDirectory(
            listOf(entry("甲.txt"), entry("乙.txt"), entry("丙.txt")),
            "新组",
        )

        assertTrue(result.cancelled)
        assertNull(result.groupName)
        assertEquals(listOf(1L to "甲.txt"), result.imported)
        assertTrue(groupCalls.isEmpty())
    }

    @Test
    fun `默认分组名去扩展名且空名回退`() {
        assertEquals("科幻小说", BatchImportUseCase.defaultGroupName("科幻小说.txt"))
        assertEquals("我的书库", BatchImportUseCase.defaultGroupName("我的书库"))
        assertEquals("目录导入", BatchImportUseCase.defaultGroupName(null))
        assertEquals("目录导入", BatchImportUseCase.defaultGroupName("  "))
        assertEquals("目录导入", BatchImportUseCase.defaultGroupName(".hidden"))
    }
}
