package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** v14 = `chapters` 新增 `depth`（目录层级，仅用于目录面板缩进）。 */
class SchemaV14Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/14.json").readText()
    }

    private fun tableBlock(table: String): String {
        val start = schema.indexOf("\"tableName\": \"$table\"")
        require(start >= 0) { "14.json 中缺少表 $table" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v14 schema 导出文件存在且版本为 14`() {
        assertTrue(schema.contains("\"version\": 14"))
    }

    @Test
    fun `chapters 新增 depth 列且非空带默认值`() {
        val block = tableBlock("chapters")
        val idx = block.indexOf("\"columnName\": \"depth\"")
        assertTrue("chapters 表应含 depth 列", idx >= 0)

        val columnBlock = block.substring(idx, idx + 200)
        assertTrue("depth 应非空", columnBlock.contains("\"notNull\": true"))
        // 默认值必须与 MIGRATION_13_14 手写 ALTER 里的 DEFAULT 0 一致，
        // 否则 Room 打开库时的 schema 校验会直接抛异常
        assertTrue("depth 应有默认值 0", columnBlock.contains("\"defaultValue\": \"0\""))
    }

    @Test
    fun `建表语句里的默认值与迁移一致`() {
        assertTrue(
            tableBlock("chapters").contains("`depth` INTEGER NOT NULL DEFAULT 0"),
        )
    }
}
