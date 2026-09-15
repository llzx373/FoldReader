package com.llzx373.foldreader.core.data.db

class FakeOffsetIndexDao : OffsetIndexDao {

    private val entries = mutableMapOf<Long, MutableMap<Int, OffsetIndexEntity>>()
    private val metas = mutableMapOf<Long, OffsetIndexMetaEntity>()

    override suspend fun getForBook(bookId: Long): List<OffsetIndexEntity> =
        entries[bookId]?.values?.sortedBy { it.chunkIndex } ?: emptyList()

    override suspend fun upsertAll(entries: List<OffsetIndexEntity>) {
        for (entry in entries) {
            this.entries.getOrPut(entry.bookId) { mutableMapOf() }[entry.chunkIndex] = entry
        }
    }

    override suspend fun deleteForBook(bookId: Long) {
        entries.remove(bookId)
    }

    override suspend fun getMeta(bookId: Long): OffsetIndexMetaEntity? = metas[bookId]

    override suspend fun upsertMeta(meta: OffsetIndexMetaEntity) {
        metas[meta.bookId] = meta
    }

    override suspend fun deleteMeta(bookId: Long) {
        metas.remove(bookId)
    }
}
