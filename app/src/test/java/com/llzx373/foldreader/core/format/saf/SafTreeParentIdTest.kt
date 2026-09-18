package com.llzx373.foldreader.core.format.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 父目录 id 反推。SAF 没有「取父目录」API，同目录兄弟枚举只能靠这个；
 * 反推不出来时必须返回 null（宁可不提供功能，也不能猜一个可能错误的目标）。
 */
class SafTreeParentIdTest {

    @Test
    fun `去掉路径最后一段得到父目录 id`() {
        assertEquals(
            "primary:comics/Foo",
            SafTree.parentDocumentIdOf("primary:comics/Foo/01.cbz"),
        )
        assertEquals(
            "primary:comics",
            SafTree.parentDocumentIdOf("primary:comics/Foo"),
        )
        assertEquals(
            "home:Documents/漫画/第03卷",
            SafTree.parentDocumentIdOf("home:Documents/漫画/第03卷/1.jpg"),
        )
    }

    @Test
    fun `不透明 id 与根目录反推不出父目录`() {
        // Downloads 提供方的 id 是不透明数字：没有路径可反推
        assertNull(SafTree.parentDocumentIdOf("1234"))
        // 存储根的直接子项：父目录是根，但我们拿不到可用的根 id，交给调用方降级
        assertNull(SafTree.parentDocumentIdOf("primary:01.cbz"))
        assertNull(SafTree.parentDocumentIdOf(""))
    }
}
