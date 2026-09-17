package com.llzx373.foldreader.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 行几何缓存：键的构成决定正确性 —— 少一个参数就会把别的版式的行宽串到当前页上。
 */
class LineBoxCacheTest {

    private val config = LayoutConfig(
        fontSizeSp = 10f,
        lineSpacingMultiplier = 1f,
        marginLeftDp = 0f,
        marginTopDp = 0f,
        marginRightDp = 0f,
        marginBottomDp = 0f,
        firstLineIndentChars = 0,
    )

    private fun page(charStart: Long = 0L, text: String = "甲乙丙丁"): Page = Page(
        charStart = charStart,
        charEnd = charStart + text.length,
        lines = listOf(
            PageLine(charStart, charStart + text.length, text, isParagraphStart = true, isParagraphEnd = true),
        ),
        paddingLeft = 0f,
        paddingRight = 0f,
    )

    /** 记录 build 次数的缓存调用。 */
    private class Subject(private val cache: LineBoxCache) {
        var builds = 0
        fun request(
            page: Page,
            config: LayoutConfig,
            widthPx: Float = 100f,
            innerPaddingPx: Float = 0f,
            innerOnRight: Boolean = true,
            extraTopPadPx: Float = 0f,
            density: Float = 2f,
            scaledDensity: Float = 2f,
        ): List<LineBox> = cache.getOrBuild(
            page = page,
            widthPx = widthPx,
            innerPaddingPx = innerPaddingPx,
            innerOnRight = innerOnRight,
            extraTopPadPx = extraTopPadPx,
            density = density,
            scaledDensity = scaledDensity,
            config = config,
        ) {
            builds++
            buildLineBoxes(
                page = page,
                lineHeightPx = 20f,
                paragraphSpacingPx = 0f,
                indentPx = 0f,
                topPadPx = extraTopPadPx,
                leftPadPx = 0f,
                textWidthPx = widthPx,
                justify = false,
                measure = { it.length * 10f },
            )
        }
    }

    @Test
    fun `同一页同一参数只度量一次`() {
        val cache = LineBoxCache()
        val subject = Subject(cache)
        val p = page()

        val first = subject.request(p, config)
        val second = subject.request(p, config)

        assertEquals(1, subject.builds)
        assertSame(first, second)
    }

    @Test
    fun `版式参数变化各自重新度量`() {
        val cache = LineBoxCache()
        val subject = Subject(cache)
        val p = page()

        subject.request(p, config)
        subject.request(p, config.copy(fontSizeSp = 12f))
        subject.request(p, config, widthPx = 120f)
        subject.request(p, config, innerPaddingPx = 8f)
        subject.request(p, config, innerOnRight = false)
        subject.request(p, config, extraTopPadPx = 6f)
        subject.request(p, config, density = 3f)
        subject.request(p, config, scaledDensity = 3f)

        assertEquals(8, subject.builds)
    }

    @Test
    fun `换页实例即使字符区间相同也不复用`() {
        val cache = LineBoxCache()
        val subject = Subject(cache)

        // 重排后重新构造的同区间 Page：内容可能已不同，必须重新度量
        val a = subject.request(page(0L, "甲乙丙丁"), config)
        val b = subject.request(page(0L, "戊己庚辛"), config)

        assertEquals(2, subject.builds)
        assertNotSame(a, b)
        assertEquals("戊己庚辛", b[0].line.text)
    }

    @Test
    fun `超出容量后淘汰最久未用项`() {
        val cache = LineBoxCache(maxSize = 2)
        val subject = Subject(cache)
        val p0 = page(0L)
        val p1 = page(10L)
        val p2 = page(20L)

        subject.request(p0, config)
        subject.request(p1, config)
        subject.request(p2, config) // p0 被淘汰
        assertEquals(3, subject.builds)

        subject.request(p0, config) // 重新度量
        assertEquals(4, subject.builds)
        assertEquals(2, cache.size)

        subject.request(p2, config) // 仍在缓存
        assertEquals(4, subject.builds)
    }

    @Test
    fun `命中会把条目提到最近使用`() {
        val cache = LineBoxCache(maxSize = 2)
        val subject = Subject(cache)
        val p0 = page(0L)
        val p1 = page(10L)
        val p2 = page(20L)

        subject.request(p0, config)
        subject.request(p1, config)
        subject.request(p0, config) // p0 提为最近使用 → 下一个淘汰 p1
        subject.request(p2, config)
        assertEquals(3, subject.builds)

        subject.request(p1, config) // 已被淘汰
        assertEquals(4, subject.builds)
        subject.request(p2, config) // 仍在
        assertEquals(4, subject.builds)
    }

    @Test
    fun `clear 后重新度量`() {
        val cache = LineBoxCache()
        val subject = Subject(cache)
        val p = page()

        subject.request(p, config)
        cache.clear()
        assertEquals(0, cache.size)
        subject.request(p, config)

        assertEquals(2, subject.builds)
    }
}
