package com.llzx373.foldreader.core.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class KinsokuTest {

    @Test
    fun `line-start forbidden char shifts break right`() {
        val text = "一二三四五六七八九十。续"
        val out = Kinsoku.adjust(text, intArrayOf(10, 12))
        assertArrayEquals(intArrayOf(11, 12), out)
    }

    @Test
    fun `line-end forbidden char shifts break left`() {
        val text = "一二三四五六七八九（十"
        val out = Kinsoku.adjust(text, intArrayOf(10, 11))
        assertArrayEquals(intArrayOf(9, 11), out)
    }

    @Test
    fun `consecutive closing punctuation all shift right`() {
        val text = "一二三四五六七八九。》续"
        val out = Kinsoku.adjust(text, intArrayOf(10, 13))
        assertArrayEquals(intArrayOf(11, 13), out)
    }

    @Test
    fun `last break never moves`() {
        val text = "（一二三四"
        val out = Kinsoku.adjust(text, intArrayOf(5))
        assertArrayEquals(intArrayOf(5), out)
    }

    @Test
    fun `empty breaks stay empty`() {
        assertArrayEquals(intArrayOf(), Kinsoku.adjust("", intArrayOf()))
    }
}
