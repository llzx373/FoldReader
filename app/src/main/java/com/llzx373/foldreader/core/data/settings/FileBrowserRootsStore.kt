package com.llzx373.foldreader.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fileBrowserRootsStore: DataStore<Preferences> by preferencesDataStore(
    name = "file_browser_roots",
)

/** "浏览"页已授权的 SAF 目录（OpenDocumentTree tree Uri 字符串集合）。 */
class FileBrowserRootsStore(
    private val context: Context,
) {
    private object Keys {
        val ROOTS = stringSetPreferencesKey("roots")
    }

    val roots: Flow<Set<String>> =
        context.fileBrowserRootsStore.data.map { it[Keys.ROOTS] ?: emptySet() }

    suspend fun addRoot(treeUri: String) {
        context.fileBrowserRootsStore.edit { prefs ->
            prefs[Keys.ROOTS] = (prefs[Keys.ROOTS] ?: emptySet()) + treeUri
        }
    }

    suspend fun removeRoot(treeUri: String) {
        context.fileBrowserRootsStore.edit { prefs ->
            prefs[Keys.ROOTS] = (prefs[Keys.ROOTS] ?: emptySet()) - treeUri
        }
    }
}
