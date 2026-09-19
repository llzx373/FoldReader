package com.llzx373.foldreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.debug.DiagnosticLog
import com.llzx373.foldreader.ui.FoldReaderApp
import com.llzx373.foldreader.ui.theme.FoldReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        stashIncomingIntent(intent)
        val settings = (application as FoldReaderApplication).container.settingsRepository
        setContent {
            val prefs by settings.preferences.collectAsState(initial = null)
            val darkTheme = when ((prefs ?: ReadingPreferences()).darkThemeOption) {
                DarkThemeOption.LIGHT -> false
                DarkThemeOption.DARK -> true
                DarkThemeOption.SYSTEM -> isSystemInDarkTheme()
            }
            FoldReaderTheme(darkTheme = darkTheme) {
                FoldReaderApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 不更新的话，后续 getIntent() 拿到的还是第一次那个（例如重建时）
        setIntent(intent)
        stashIncomingIntent(intent)
    }

    /**
     * 「打开方式」与「分享」进来的文件都收在这里。
     *
     * 三个位置都要看：`ACTION_VIEW` 把 URI 放在 `data`，`ACTION_SEND` 放在 `EXTRA_STREAM`，
     * `ACTION_SEND_MULTIPLE` 常见于 `clipData`。少看一处，用户看到的就是「选了 FoldReader
     * 却直接落到书架」——那正是最容易被当成没反应的现象。
     */
    private fun stashIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val supported = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
            else -> false
        }
        if (!supported) return
        val uris = collectFileUris(intent)
        // 只记 scheme/authority 与计数：文件名属于用户的私人内容，诊断日志不该顺手把它带走
        DiagnosticLog.line(
            "open-with: action=${intent.action} type=${intent.type} " +
                "flags=0x${Integer.toHexString(intent.flags)} " +
                "clip=${intent.clipData?.itemCount ?: 0} " +
                "uris=[${uris.joinToString { "${it.scheme}://${it.authority}" }}]",
        )
        if (uris.isEmpty()) return
        takePersistableIfGranted(intent, uris)
        val container = (application as FoldReaderApplication).container
        // 累加而不是覆盖：连续送两个文件时，前一个不该被后一个顶掉
        container.pendingImportUris.value = container.pendingImportUris.value + uris
    }

    private fun collectFileUris(intent: Intent): List<Uri> {
        val found = LinkedHashSet<Uri>()
        intent.data?.takeIf { it.isFileLike() }?.let { found += it }
        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.takeIf { it.isFileLike() }?.let { found += it }
            }
        }
        // EXTRA_STREAM 可能是单个 Uri，也可能是 ArrayList<Uri>；不按类型取会漏掉一半发送方
        when (val stream = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> if (stream.isFileLike()) found += stream
            is ArrayList<*> -> stream.filterIsInstance<Uri>().filterTo(found) { it.isFileLike() }
        }
        return found.toList()
    }

    /** 只接受能读到内容的两种 scheme；其它（http 等）交给浏览器。 */
    private fun Uri.isFileLike(): Boolean = scheme == "content" || scheme == "file"

    /**
     * 外部送来的 `content://` 通常是**临时**授权，硬取持久化权限会抛 `SecurityException`，
     * 所以先看 flags 里有没有申请。
     *
     * 只取**读**权限：本 App 从不回写源文件（清洗产物写在私有目录），而 `take*` 要求所取的
     * 权限是对方实际授予的子集。
     */
    private fun takePersistableIfGranted(intent: Intent, uris: List<Uri>) {
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        uris.filter { it.scheme == "content" }.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
