package com.llzx373.foldreader.core.reader

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FontManagerTest {

    private lateinit var context: android.content.Context
    private lateinit var manager: FontManager
    private lateinit var fontsDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fontsDir = File(context.filesDir, "fonts").apply {
            deleteRecursively()
            mkdirs()
        }
        manager = FontManager(context)
    }

    @Test
    fun `导入字体按文件名列举并显示去扩展名名称`() {
        File(fontsDir, "LXGW WenKai GB.ttf").writeBytes(byteArrayOf(1))
        File(fontsDir, "CharisSIL.otf").writeBytes(byteArrayOf(1))

        assertEquals(listOf("CharisSIL.otf", "LXGW WenKai GB.ttf"), manager.listImported())
        assertEquals("LXGW WenKai GB", FontManager.displayNameOf("file:LXGW WenKai GB.ttf"))
    }

    @Test
    fun `已导入字体可以删除`() = runTest {
        val font = File(fontsDir, "paper.ttf").apply { writeBytes(byteArrayOf(1)) }

        assertTrue(manager.delete("file:paper.ttf"))
        assertFalse(font.exists())
        assertTrue(manager.listImported().isEmpty())
    }

    @Test
    fun `系统字体和目录外路径不能删除`() = runTest {
        val font = File(fontsDir, "paper.ttf").apply { writeBytes(byteArrayOf(1)) }

        assertFalse(manager.delete("serif"))
        assertFalse(manager.delete("file:../fonts/paper.ttf"))
        assertTrue(font.exists())
    }
}
