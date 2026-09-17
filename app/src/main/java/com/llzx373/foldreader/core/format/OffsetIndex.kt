package com.llzx373.foldreader.core.format

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class OffsetIndexSnapshot(
    val blockChars: Int,
    val totalChars: Long,
    val blockCharStarts: LongArray,
    val blockByteOffsets: LongArray,
)

/**
 * 已落盘的一个块起点：块号 + 字节起点 + 字符起点。
 *
 * 字符起点必须显式携带，不能靠 `chunkIndex * blockChars` 推：索引器的输出缓冲只剩 1 个槽位、
 * 而下一个字符是需要 2 槽的增补字符（emoji、CJK 扩展 B 等）时，该块会以不足 blockChars 的
 * 字符数提交，此后所有块起点都相对均匀模型前移。而增补字符在 UTF-8 里是不可分割的 4 字节，
 * 这种边界上**不存在**合法的字节偏移，非均匀是数据模型的必然结果。
 */
data class OffsetIndexBlock(
    val chunkIndex: Int,
    val byteOffset: Long,
    val charStart: Long,
)

interface OffsetIndexStore {
    suspend fun load(key: String): OffsetIndexSnapshot?
    suspend fun save(key: String, snapshot: OffsetIndexSnapshot)
    suspend fun loadValid(
        key: String,
        fileLength: Long,
        contentHash: String,
        charsetName: String,
    ): OffsetIndexSnapshot?
    suspend fun saveValid(
        key: String,
        snapshot: OffsetIndexSnapshot,
        fileLength: Long,
        contentHash: String,
        charsetName: String,
    )
    suspend fun begin(key: String)
    suspend fun appendBlocks(key: String, blocks: List<OffsetIndexBlock>)
    suspend fun complete(
        key: String,
        fileLength: Long,
        contentHash: String,
        charsetName: String,
        totalChars: Long,
    )
    suspend fun invalidate(key: String)
}

class OffsetIndex(
    val blockChars: Int,
    initialByteOffset: Long = 0L,
) {
    private data class State(
        val totalChars: Long,
        val isComplete: Boolean,
        val failure: Throwable?,
    )

    private val lock = Any()
    private val blockCharStarts = arrayListOf(0L)
    private val blockByteOffsets = arrayListOf(initialByteOffset)
    private val updates = MutableStateFlow(State(0L, isComplete = false, failure = null))

    private var _totalChars = 0L
    val totalChars: Long get() = synchronized(lock) { _totalChars }

    private var _isComplete = false
    val isComplete: Boolean get() = synchronized(lock) { _isComplete }

    val blockCount: Int get() = synchronized(lock) { blockCharStarts.size - 1 }

    fun appendBlock(charCount: Int, endByteOffset: Long) {
        synchronized(lock) {
            check(!_isComplete) { "索引已封口，不能再追加" }
            _totalChars += charCount
            blockCharStarts += _totalChars
            blockByteOffsets += endByteOffset
            updates.value = State(_totalChars, isComplete = false, failure = null)
        }
    }

    fun markComplete() {
        synchronized(lock) {
            _isComplete = true
            updates.value = State(_totalChars, isComplete = true, failure = null)
        }
    }

    fun abort(cause: Throwable) {
        synchronized(lock) {
            if (_isComplete) return
            updates.value = State(_totalChars, isComplete = false, failure = cause)
        }
    }

    suspend fun awaitTotalCharsAbove(charOffset: Long) {
        val state = updates.first { it.isComplete || it.failure != null || it.totalChars > charOffset }
        val failure = state.failure
        if (failure != null && !state.isComplete && state.totalChars <= charOffset) throw failure
    }

    fun blockOfChar(charOffset: Long): Int = synchronized(lock) {
        val count = blockCharStarts.size - 1
        if (count == 0) return 0
        var index = blockCharStarts.binarySearch(charOffset)
        if (index < 0) index = -index - 2
        index.coerceIn(0, count - 1)
    }

    fun charStartOfBlock(block: Int): Long = synchronized(lock) { blockCharStarts[block] }

    fun byteOffsetOfBlock(block: Int): Long = synchronized(lock) { blockByteOffsets[block] }

    fun byteOffsetOfChar(charOffset: Long): Long = byteOffsetOfBlock(blockOfChar(charOffset))

    fun snapshot(): OffsetIndexSnapshot = synchronized(lock) {
        OffsetIndexSnapshot(
            blockChars = blockChars,
            totalChars = _totalChars,
            blockCharStarts = blockCharStarts.toLongArray(),
            blockByteOffsets = blockByteOffsets.toLongArray(),
        )
    }

    companion object {
        const val DEFAULT_BLOCK_CHARS = 4096

        fun restore(snapshot: OffsetIndexSnapshot): OffsetIndex {
            val index = OffsetIndex(snapshot.blockChars)
            synchronized(index.lock) {
                index.blockCharStarts.clear()
                index.blockCharStarts += snapshot.blockCharStarts.toList()
                index.blockByteOffsets.clear()
                index.blockByteOffsets += snapshot.blockByteOffsets.toList()
                index._totalChars = snapshot.totalChars
                index._isComplete = true
                index.updates.value = State(snapshot.totalChars, isComplete = true, failure = null)
            }
            return index
        }
    }
}
