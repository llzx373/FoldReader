package com.llzx373.foldreader.core.reader

class PageCache<K, V>(private val maxSize: Int) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size
}

interface PageDiskCache {
    fun load(bookId: Long, pageIndex: Int): Page?
    fun save(bookId: Long, pageIndex: Int, page: Page)
}
