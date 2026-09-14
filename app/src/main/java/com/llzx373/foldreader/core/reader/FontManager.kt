package com.llzx373.foldreader.core.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FontManager(private val context: Context) {

    private val fontsDir: File get() = File(context.filesDir, "fonts").apply { mkdirs() }

    suspend fun import(uri: Uri, displayName: String?): String? = withContext(Dispatchers.IO) {
        val safeName = sanitize(displayName ?: "font_${System.currentTimeMillis()}.ttf")
        val target = File(fontsDir, safeName)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            Typeface.createFromFile(target)
            "$FILE_PREFIX$safeName"
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun resolve(fontKey: String): Typeface? = when (fontKey) {
        "serif" -> Typeface.SERIF
        "sans_serif" -> Typeface.SANS_SERIF
        "monospace" -> Typeface.MONOSPACE
        else -> {
            if (fontKey.startsWith(FILE_PREFIX)) {
                val file = File(fontsDir, fontKey.removePrefix(FILE_PREFIX))
                if (file.isFile) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
            } else {
                null
            }
        }
    }

    fun listImported(): List<String> =
        fontsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.map { it.name }
            .orEmpty()

    companion object {
        const val FILE_PREFIX = "file:"

        fun displayNameOf(fontKey: String): String = when (fontKey) {
            "default" -> "默认"
            "serif" -> "衬线"
            "sans_serif" -> "无衬线"
            "monospace" -> "等宽"
            else -> fontKey.removePrefix(FILE_PREFIX).substringBeforeLast('.')
        }

        private fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("[^A-Za-z0-9._\\-一-鿿]"), "_")
            return if (cleaned.contains('.')) cleaned else "$cleaned.ttf"
        }
    }
}
