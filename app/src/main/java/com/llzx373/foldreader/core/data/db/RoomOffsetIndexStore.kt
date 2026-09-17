package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.format.OffsetIndex
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
        if (!snapshot.hasUniformBlockStarts()) {
            // 代理对跨块会让索引器产出 4095/8190/… 这类非均匀块起点，而本表只存字节偏移、
            // 恢复时按 i * blockChars 重建起点 —— 存下去读回来就会整体错位（窗口串位甚至越界）。
            // 宁可不落盘，让下次打开重扫一遍。
            dao.clearForBook(bookId)
            return
        }
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
        if (!snapshot.hasUniformBlockStarts()) {
            // 不能只跳过块：meta 标了 completed 而块缺失/错位同样会让恢复失败
            dao.clearForBook(bookId)
            return
        }
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

    override suspend fun appendBlocks(key: String, blocks: List<Pair<Int, Long>>) {
        val bookId = key.toLongOrNull() ?: return
        if (blocks.isEmpty()) return
        dao.upsertAll(
            blocks.map { (chunkIndex, byteOffset) ->
                OffsetIndexEntity(bookId = bookId, chunkIndex = chunkIndex, charOffset = byteOffset)
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
                charOffset = snapshot.blockByteOffsets[block],
            )
        }
    }

    private suspend fun buildSnapshot(bookId: Long, meta: OffsetIndexMetaEntity): OffsetIndexSnapshot? {
        val entries = dao.getForBook(bookId)
        if (entries.isEmpty()) return null
        val blockCount = entries.size
        val blockChars = OffsetIndex.DEFAULT_BLOCK_CHARS
        for (i in entries.indices) {
            if (entries[i].chunkIndex != i) return null
            if (i > 0 && entries[i].charOffset <= entries[i - 1].charOffset) return null
        }
        if (meta.totalChars <= (blockCount - 1L) * blockChars ||
            meta.totalChars > blockCount.toLong() * blockChars
        ) {
            return null
        }
        if (meta.fileLength < entries.last().charOffset) return null
        val charStarts = LongArray(blockCount + 1) { it.toLong() * blockChars }
        charStarts[blockCount] = meta.totalChars
        val byteOffsets = LongArray(blockCount + 1) {
            if (it < blockCount) entries[it].charOffset else meta.fileLength
        }
        return OffsetIndexSnapshot(
            blockChars = blockChars,
            totalChars = meta.totalChars,
            blockCharStarts = charStarts,
            blockByteOffsets = byteOffsets,
        )
    }
}
