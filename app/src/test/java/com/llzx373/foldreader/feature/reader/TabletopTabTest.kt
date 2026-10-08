package com.llzx373.foldreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletopTabTest {

    @Test
    fun `tab order and labels`() {
        assertEquals(
            listOf("控制", "目录", "批注", "术语表"),
            TabletopTab.entries.map { it.label },
        )
    }

    @Test
    fun `next tab cycles and wraps to controls`() {
        var tab = TabletopTab.CONTROLS
        val visited = mutableListOf(tab)
        repeat(TabletopTab.entries.size) {
            tab = nextTabletopTab(tab)
            visited.add(tab)
        }
        // 转一整圈回到控制页签，中途不重复
        assertEquals(TabletopTab.CONTROLS, visited.last())
        assertEquals(TabletopTab.entries.toSet(), visited.dropLast(1).toSet())
    }

    @Test
    fun `ordinal fallback is controls`() {
        assertEquals(TabletopTab.CATALOG, tabletopTabOrDefault(TabletopTab.CATALOG.ordinal))
        assertEquals(TabletopTab.CONTROLS, tabletopTabOrDefault(-1))
        assertEquals(TabletopTab.CONTROLS, tabletopTabOrDefault(99))
    }
}
