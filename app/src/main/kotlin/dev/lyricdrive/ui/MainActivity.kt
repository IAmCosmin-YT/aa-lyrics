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
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.CrashReporter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.BackgroundMode

class MainActivity : ComponentActivity() {
    private val viewModel: LyricsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LyricDriveApp
        val safe = intent.getBooleanExtra(RecoveryActivity.EXTRA_SAFE_GRAPHICS, false)
        val recovery = getSharedPreferences("recovery", MODE_PRIVATE)
        if (!safe && (CrashReporter.needsRecovery(this) || recovery.getBoolean("pending_crash", false))) {
            showRecovery()
            return
        }
        if (safe) {
            CrashReporter.clearPending(this)
            recovery.edit().putBoolean("pending_crash", false).commit()
        }
        CrashReporter.beginLaunch(this)
        try {
            val graph = app.graph
            if (safe) graph.settings.update {
                it.copy(backgroundMode = BackgroundMode.HIGH_CONTRAST, animationStyle = AnimationStyle.GLANCE, wordHighlight = WordHighlight.OFF)
            }
            CrashReporter.stage(this, "Opening phone interface")
            openLyrics()
            lifecycleScope.launch {
                delay(5_000)
                CrashReporter.stage(this@MainActivity, "Phone interface remained open for five seconds")
                CrashReporter.clearPending(this@MainActivity)
            }
        } catch (error: RuntimeException) {
            CrashReporter.record(this, "Phone interface startup", error)
            showRecovery()
        } catch (error: LinkageError) {
            CrashReporter.record(this, "Phone interface linkage", error)
            showRecovery()
        }
    }

    private fun showRecovery() {
        startActivity(android.content.Intent(this, RecoveryActivity::class.java))
        finish()
    }

    private fun openLyrics() {
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
                    showSettings -> SettingsScreen(viewModel, onBack = { showSettings = false })
                    !granted -> OnboardingScreen(onOpenSettings = { showSettings = true })
                    else -> LyricsScreen(viewModel, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
