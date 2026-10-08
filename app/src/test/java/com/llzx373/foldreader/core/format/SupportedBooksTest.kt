package com.llzx373.foldreader.core.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedBooksTest {

    @Test
    fun `Markdown 扩展名与 MIME 视为可导入`() {
        assertTrue(isSupportedBookName("notes.md", null))
        assertTrue(isSupportedBookName("notes.markdown", null))
        assertTrue(isSupportedBookName("NOTES.MD", null))
        assertTrue(isSupportedBookName("readme", "text/markdown"))
        assertTrue(isSupportedBookName("readme", "text/x-markdown"))
        // 不误伤
        assertFalse(isSupportedBookName("notes.mdown", null))
        assertFalse(isSupportedBookName("notes.mdx", null))
    }
}
