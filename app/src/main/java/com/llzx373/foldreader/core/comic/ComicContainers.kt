package com.llzx373.foldreader.core.comic

/**
 * 漫画容器识别：魔数优先，扩展名/MIME 兜底。
 *
 * 只负责「这是不是漫画容器、是哪一种」，EPUB/FB2 的排除由 [com.llzx373.foldreader.core.format.FormatDetector]
 * 在调用本类之前完成（EPUB 也是 zip，顺序反了会把书当漫画）。
 */
object ComicContainers {

    const val CBZ_MIME = "application/vnd.comicbook+zip"
    const val CBR_MIME = "application/vnd.comicbook-rar"

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val RAR4_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val SEVEN_ZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    private val TAR_MAGIC = "ustar".toByteArray(Charsets.US_ASCII)
    private const val TAR_MAGIC_OFFSET = 257

    fun isZip(head: ByteArray): Boolean = startsWith(head, ZIP_MAGIC)

    /** tar 的 `ustar` 魔数在偏移 257（GNU/PAX 都是）。 */
    fun isTar(head: ByteArray): Boolean {
        if (head.size < TAR_MAGIC_OFFSET + TAR_MAGIC.size) return false
        for (i in TAR_MAGIC.indices) {
            if (head[TAR_MAGIC_OFFSET + i] != TAR_MAGIC[i]) return false
        }
        return true
    }

    fun isRar(head: ByteArray): Boolean = startsWith(head, RAR4_MAGIC) || startsWith(head, RAR5_MAGIC)

    fun isSevenZip(head: ByteArray): Boolean = startsWith(head, SEVEN_ZIP_MAGIC)

    /** 仅魔数判定；认不出返回 null。 */
    fun fromMagic(head: ByteArray): ComicContainer? = when {
        isSevenZip(head) -> ComicContainer.SEVEN_ZIP
        isRar(head) -> ComicContainer.RAR
        isZip(head) -> ComicContainer.ZIP
        isTar(head) -> ComicContainer.TAR
        else -> null
    }

    fun fromExtension(displayName: String?): ComicContainer? =
        when (displayName?.substringAfterLast('.', "")?.lowercase()) {
            "cbz", "zip" -> ComicContainer.ZIP
            "cbr", "rar" -> ComicContainer.RAR
            "cbt", "tar" -> ComicContainer.TAR
            "cb7", "7z" -> ComicContainer.SEVEN_ZIP
            else -> null
        }

    fun fromMime(mimeType: String?): ComicContainer? = when (mimeType?.lowercase()) {
        CBZ_MIME, "application/x-cbz", "application/zip", "application/x-zip-compressed" ->
            ComicContainer.ZIP
        CBR_MIME, "application/x-cbr", "application/vnd.rar", "application/x-rar-compressed" ->
            ComicContainer.RAR
        "application/x-cbt", "application/x-tar" -> ComicContainer.TAR
        "application/x-cb7", "application/x-7z-compressed" -> ComicContainer.SEVEN_ZIP
        else -> null
    }

    /**
     * 综合判定。魔数优先：`.cbr` 实为 zip 这类错标相当常见，按真实字节走才解得开。
     *
     * 例外是 tar——它只有偏移 257 的 `ustar` 一处特征，而这段字节在普通文本里也可能偶现，
     * 所以要求扩展名/MIME 印证；zip/rar/7z 的魔数不会误伤，不需要这道校验。
     */
    fun detect(displayName: String?, mimeType: String?, head: ByteArray): ComicContainer? {
        val byName = fromExtension(displayName) ?: fromMime(mimeType)
        val byMagic = fromMagic(head)
        if (byMagic == ComicContainer.TAR && byName != ComicContainer.TAR) return byName
        return byMagic ?: byName
    }

    private fun startsWith(head: ByteArray, magic: ByteArray): Boolean {
        if (head.size < magic.size) return false
        for (i in magic.indices) {
            if (head[i] != magic[i]) return false
        }
        return true
    }
}
