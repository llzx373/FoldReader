package com.llzx373.foldreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.ui.FoldReaderApp
import com.llzx373.foldreader.ui.theme.FoldReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        stashViewIntent(intent)
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
        stashViewIntent(intent)
    }

    private fun stashViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        (application as FoldReaderApplication).container.pendingImportUri.value = uri
    }
}
