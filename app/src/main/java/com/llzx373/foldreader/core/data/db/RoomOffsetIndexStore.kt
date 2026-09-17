package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.format.OffsetIndex
import com.llzx373.foldreader.core.format.OffsetIndexBlock
import com.llzx373.foldreader.core.format.OffsetIndexSnapshot
import com.llzx373.foldreader.core.format.OffsetIndexStore

class RoomOffsetIndexStore(
    private val dao: OffsetIndexDao,
) : OffsetIndexStore {

    override suspend fun load(key: String): OffsetIndexSnapshot? {
        val bookId = key.toLongOrNull() ?: return null
        val meta = dao.getMeta(bookId)?.takeIf { it.completed } ?: return null
        return buildSnapshot(bookId, meta)
    }

    override suspend fun loadValid(
        key: String,
        fileLength: Long,
        contentHash: String,
        charsetName: String,
    ): OffsetIndexSnapshot? {
        val bookId = key.toLongOrNull() ?: return null
        val meta = dao.getMeta(bookId) ?: return null
        if (!meta.completed) return null
        if (meta.fileLength != fileLength ||
            meta.contentHash != contentHash ||
            meta.charsetName != charsetName
        ) {
            return null
        }
        return buildSnapshot(bookId, meta)
    }

    override suspend fun save(key: String, snapshot: OffsetIndexSnapshot) {
        val bookId = key.toLongOrNull() ?: return
        require(snapshot.blockChars == OffsetIndex.DEFAULT_BLOCK_CHARS)
        dao.replaceForBook(bookId, entriesOf(bookId, snapshot))
    }

    override suspend fun saveValid(
        key: String,
        snapshot: OffsetIndexSnapshot,
        fileLength: Long,
        contentHash: String,
        charsetName: String,
    ) {
        val bookId = key.toLongOrNull() ?: return
        save(key, snapshot)
        dao.upsertMeta(
            OffsetIndexMetaEntity(
                bookId = bookId,
                fileLength = fileLength,
                contentHash = contentHash,
                charsetName = charsetName,
                totalChars = snapshot.totalChars,
                completed = true,
            ),
        )
    }

    override suspend fun begin(key: String) {
        val bookId = key.toLongOrNull() ?: return
        dao.clearForBook(bookId)
    }

    override suspend fun appendBlocks(key: String, blocks: List<OffsetIndexBlock>) {
        val bookId = key.toLongOrNull() ?: return
        if (blocks.isEmpty()) return
        dao.upsertAll(
            blocks.map { block ->
                OffsetIndexEntity(
                    bookId = bookId,
                    chunkIndex = block.chunkIndex,
                    byteOffset = block.byteOffset,
                    charStart = block.charStart,
                )
            },
        )
    }

    override suspend fun complete(
        key: String,
        fileLength: Long,
        contentHash: String,
        charsetName: String,
        totalChars: Long,
    ) {
        val bookId = key.toLongOrNull() ?: return
        dao.upsertMeta(
            OffsetIndexMetaEntity(
                bookId = bookId,
                fileLength = fileLength,
                contentHash = contentHash,
                charsetName = charsetName,
                totalChars = totalChars,
                completed = true,
            ),
        )
    }

    override suspend fun invalidate(key: String) {
        val bookId = key.toLongOrNull() ?: return
        dao.clearForBook(bookId)
    }

    private fun entriesOf(bookId: Long, snapshot: OffsetIndexSnapshot): List<OffsetIndexEntity> {
        val blockCount = snapshot.blockCharStarts.size - 1
        return (0 until blockCount).map { block ->
            OffsetIndexEntity(
                bookId = bookId,
                chunkIndex = block,
                byteOffset = snapshot.blockByteOffsets[block],
                charStart = snapshot.blockCharStarts[block],
            )
        }
    }

    /**
     * 由落盘行重建快照。块起点直接取存量值，**不再**按 `i * blockChars` 推算——
     * 代理对跨块时索引器产出的起点就是非均匀的（4095/8190/…），推算会整体错位。
     * 这里的校验因此按「相邻跨度」而非「绝对位置」来做。
     */
    private suspend fun buildSnapshot(bookId: Long, meta: OffsetIndexMetaEntity): OffsetIndexSnapshot? {
        val entries = dao.getForBook(bookId)
        if (entries.isEmpty()) return null
        val blockCount = entries.size
        val blockChars = OffsetIndex.DEFAULT_BLOCK_CHARS
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.chunkIndex != i) return null
            if (entry.byteOffset < 0L || entry.charStart < 0L) return null
            if (i == 0) {
                // 块 0 必从字符 0 起；字节起点是 BOM 长度，可以为 0
                if (entry.charStart != 0L) return null
                continue
            }
            val prev = entries[i - 1]
            if (entry.byteOffset <= prev.byteOffset) return null
            val span = entry.charStart - prev.charStart
            if (span < 1L || span > blockChars) return null
        }
        // 末块由 meta.totalChars 收尾，跨度同样受 blockChars 约束
        val lastSpan = meta.totalChars - entries.last().charStart
        if (lastSpan < 1L || lastSpan > blockChars) return null
        if (meta.fileLength < entries.last().byteOffset) return null

        val charStarts = LongArray(blockCount + 1)
        val byteOffsets = LongArray(blockCount + 1)
        for (i in 0 until blockCount) {
            charStarts[i] = entries[i].charStart
            byteOffsets[i] = entries[i].byteOffset
        }
        charStarts[blockCount] = meta.totalChars
        byteOffsets[blockCount] = meta.fileLength
        return OffsetIndexSnapshot(
            blockChars = blockChars,
            totalChars = meta.totalChars,
            blockCharStarts = charStarts,
            blockByteOffsets = byteOffsets,
        )
    }
}
