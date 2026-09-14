package com.llzx373.foldreader.core.format

class OffsetIndexSnapshot(
    val blockChars: Int,
    val totalChars: Long,
    val blockCharStarts: LongArray,
    val blockByteOffsets: LongArray,
)

interface OffsetIndexStore {
    suspend fun load(key: String): OffsetIndexSnapshot?
    suspend fun save(key: String, snapshot: OffsetIndexSnapshot)
}

class OffsetIndex(
    val blockChars: Int,
    initialByteOffset: Long = 0L,
) {
    private val blockCharStarts = arrayListOf(0L)
    private val blockByteOffsets = arrayListOf(initialByteOffset)

    var totalChars = 0L
        private set
    var isComplete = false
        private set

    val blockCount: Int get() = blockCharStarts.size - 1

    fun appendBlock(charCount: Int, endByteOffset: Long) {
        check(!isComplete) { "索引已封口，不能再追加" }
        totalChars += charCount
        blockCharStarts += totalChars
        blockByteOffsets += endByteOffset
    }

    fun markComplete() {
        isComplete = true
    }

    fun blockOfChar(charOffset: Long): Int {
        if (blockCount == 0) return 0
        var index = blockCharStarts.binarySearch(charOffset)
        if (index < 0) index = -index - 2
        return index.coerceIn(0, blockCount - 1)
    }

    fun charStartOfBlock(block: Int): Long = blockCharStarts[block]

    fun byteOffsetOfBlock(block: Int): Long = blockByteOffsets[block]

    fun byteOffsetOfChar(charOffset: Long): Long = blockByteOffsets[blockOfChar(charOffset)]

    fun snapshot(): OffsetIndexSnapshot = OffsetIndexSnapshot(
        blockChars = blockChars,
        totalChars = totalChars,
        blockCharStarts = blockCharStarts.toLongArray(),
        blockByteOffsets = blockByteOffsets.toLongArray(),
    )

    companion object {
        const val DEFAULT_BLOCK_CHARS = 4096

        fun restore(snapshot: OffsetIndexSnapshot): OffsetIndex {
            val index = OffsetIndex(snapshot.blockChars)
            index.blockCharStarts.clear()
            index.blockCharStarts += snapshot.blockCharStarts.toList()
            index.blockByteOffsets.clear()
            index.blockByteOffsets += snapshot.blockByteOffsets.toList()
            index.totalChars = snapshot.totalChars
            index.isComplete = true
            return index
        }
    }
}
