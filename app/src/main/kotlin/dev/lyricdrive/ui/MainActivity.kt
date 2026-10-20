package dev.lyricdrive.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.lyricdrive.ui.theme.LyricDriveTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LyricsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LyricDriveTheme {
                val context = LocalContext.current
                var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
                // Re-check when returning from the system settings screen.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    granted = NotificationAccess.isGranted(context)
                    if (granted) viewModel.refreshSessions()
                }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }
                when {
                    !granted -> OnboardingScreen()
                    showSettings -> SettingsScreen(viewModel, onBack = { showSettings = false })
                    else -> LyricsScreen(viewModel, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
