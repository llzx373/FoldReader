package com.llzx373.foldreader.core.comic

/**
 * 页序与图片条目筛选（纯函数，无 Android 依赖）。
 *
 * 漫画页序错一页就整本错位，所以排序规则固定在这里并配单测：
 * 逐路径段比较，段内数字串按数值比（`1.jpg < 2.jpg < 10.jpg`，`f_0002 < f_0010`）。
 */
object ComicPageOrdering {

    /** 可当作漫画页的图片扩展名（含 Android 能解的和常见容器里会出现的）。 */
    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "jfif",
        "png", "webp", "gif", "bmp",
        "avif", "avifs", "heic", "heif",
    )

    fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /**
     * 归档/目录里的噪音条目：macOS 资源叉（`._x.jpg`、`__MACOSX/`）、缩略图缓存、
     * 隐藏文件。这些混进页列表会凭空多出几页黑图。
     */
    fun isJunkPath(path: String): Boolean {
        val segments = path.split('/')
        if (segments.any { it.isEmpty() }) return true
        val name = segments.last()
        if (name.startsWith(".")) return true
        if (segments.any { it.equals("__MACOSX", ignoreCase = true) }) return true
        return name.equals("Thumbs.db", ignoreCase = true) ||
            name.equals("desktop.ini", ignoreCase = true)
    }

    /** 从条目路径集合里挑出页路径，按自然序排序（不改写路径本身）。 */
    fun orderPaths(paths: Collection<String>): List<String> =
        paths.asSequence()
            .filter { !isJunkPath(it) && isImageName(it) }
            .sortedWith { a, b -> compareNatural(a, b) }
            .toList()

    /** 路径自然序比较：先逐段比较，段数少的靠前。 */
    fun compareNatural(a: String, b: String): Int {
        val sa = a.split('/')
        val sb = b.split('/')
        for (i in 0 until minOf(sa.size, sb.size)) {
            val c = compareSegment(sa[i], sb[i])
            if (c != 0) return c
        }
        return sa.size - sb.size
    }

    private fun compareSegment(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val c = compareNumericRun(
                    a.substring(startA, i).trimStart('0'),
                    b.substring(startB, j).trimStart('0'),
                )
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** 两个已去前导零的数字串：位数多的更大，位数相同按字典序。 */
    private fun compareNumericRun(a: String, b: String): Int =
        if (a.length != b.length) a.length - b.length else a.compareTo(b)
}
