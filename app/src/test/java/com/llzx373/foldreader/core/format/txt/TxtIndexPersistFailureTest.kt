package com.llzx373.foldreader.core.format.txt

import android.net.Uri
import com.llzx373.foldreader.core.format.OffsetIndexBlock
import com.llzx373.foldreader.core.format.OffsetIndexSnapshot
import com.llzx373.foldreader.core.format.OffsetIndexStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 实时索引的分段落盘失败时不得把缺块快照封口为完成：
 * 不写 completed 标记、作废磁盘快照（下次打开重建），内存索引完整照常交付。
 */
@RunWith(RobolectricTestRunner::class)
class TxtIndexPersistFailureTest {

    private class RecordingStore(
        private val failOnAppend: Boolean,
    ) : OffsetIndexStore {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun load(key: String): OffsetIndexSnapshot? = null

        override suspend fun save(key: String, snapshot: OffsetIndexSnapshot) = Unit

        override suspend fun loadValid(
            key: String,
            fileLength: Long,
            contentHash: String,
            charsetName: String,
        ): OffsetIndexSnapshot? = null

        override suspend fun saveValid(
            key: String,
            snapshot: OffsetIndexSnapshot,
            fileLength: Long,
            contentHash: String,
            charsetName: String,
        ) = Unit

        override suspend fun begin(key: String) {
            calls += "begin"
        }

        override suspend fun appendBlocks(key: String, blocks: List<OffsetIndexBlock>) {
            if (failOnAppend) throw java.io.IOException("磁盘满")
            calls += "append"
        }

        override suspend fun complete(
            key: String,
            fileLength: Long,
            contentHash: String,
            charsetName: String,
            totalChars: Long,
        ) {
            calls += "complete"
        }

        override suspend fun invalidate(key: String) {
            calls += "invalidate"
        }
    }

    private fun tempBook(): File {
        val file = File.createTempFile("foldreader-persist-fail", ".txt")
        file.deleteOnExit()
        // 多块内容，保证至少一次 appendBlocks。
        file.writeText("第一章 起\n" + "正文内容。\n".repeat(2000), Charsets.UTF_8)
        return file
    }

    private suspend fun liveContent(store: OffsetIndexStore, scope: CoroutineScope, uri: Uri) =
        TxtBookParser(
            context = RuntimeEnvironment.getApplication(),
            offsetIndexStore = store,
            indexScope = scope,
            bookIdResolver = { _, _ -> 1L },
        ).openContent(uri, Charsets.UTF_8, 1L) as TxtBookContent

    /** 最后一块索引回调会把进度推到 1f（早于落盘收尾），终态只能等 store 的 complete/invalidate。 */
    private suspend fun RecordingStore.awaitTerminal() {
        withTimeout(15_000) {
            while (calls.none { it == "complete" || it == "invalidate" }) delay(20)
        }
    }

    @Test
    fun `分段落盘失败时不封口 completed 并作废快照`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = RecordingStore(failOnAppend = true)
            val content = liveContent(store, scope, Uri.fromFile(tempBook()))

            store.awaitTerminal()
            content.close()

            assertTrue("落盘失败必须作废快照", store.calls.contains("invalidate"))
            assertTrue("落盘失败不得封口为完成", store.calls.none { it == "complete" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `分段落盘成功时照常封口 completed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = RecordingStore(failOnAppend = false)
            val content = liveContent(store, scope, Uri.fromFile(tempBook()))

            store.awaitTerminal()
            content.close()

            assertEquals(listOf("begin", "complete"), store.calls.filter { it != "append" })
        } finally {
            scope.cancel()
        }
    }
}
