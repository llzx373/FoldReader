package com.llzx373.foldreader.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceLicensesTest {

    @Test
    fun `清单里每个条目都有名字和许可`() {
        assertTrue("开源许可清单不能为空", openSourceLicenses.isNotEmpty())
        openSourceLicenses.forEach { (name, license) ->
            assertTrue("组件名不能为空：$name", name.isNotBlank())
            assertTrue("$name 缺少许可类型", license.isNotBlank())
        }
    }

    /**
     * 这些是漫画容器（zip / tar / 7z / rar）与 PDF 渲染路径上的依赖，
     * 曾经整批漏在弹窗之外；junrar 走 UnRAR License，署名是硬要求。
     */
    @Test
    fun `实际依赖的第三方组件都要署名`() {
        val required = listOf(
            "Compose",
            "AndroidX",
            "Room",
            "kotlinx-coroutines",
            "OpenCC",
            "Commons Compress",
            "XZ",
            "junrar",
            "PDFBox",
            "androidx.pdf",
        )
        required.forEach { keyword ->
            assertTrue(
                "开源许可清单缺少 $keyword 的署名",
                openSourceLicenses.any { it.first.contains(keyword, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `junrar 与 OpenCC 的许可类型正确`() {
        assertEquals("UnRAR License", licenseOf("junrar"))
        assertEquals("Apache License 2.0", licenseOf("OpenCC"))
    }

    @Test
    fun `清单没有重复条目`() {
        assertEquals(openSourceLicenses.size, openSourceLicenses.distinct().size)
    }

    private fun licenseOf(keyword: String): String =
        openSourceLicenses.first { it.first.contains(keyword, ignoreCase = true) }.second
}
