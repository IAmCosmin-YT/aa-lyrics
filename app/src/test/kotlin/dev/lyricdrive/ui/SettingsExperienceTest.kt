package dev.lyricdrive.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.data.settings.SettingsRepository
import dev.lyricdrive.data.settings.galleryChoice
import dev.lyricdrive.ui.theme.LyricDriveTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.provider.Settings
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = LyricDriveApp::class)
class SettingsExperienceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `settings categories expose battery options without appearance clutter`() {
        compose.mainClock.autoAdvance = true
        val app = RuntimeEnvironment.getApplication() as LyricDriveApp
        compose.setContent { LyricDriveTheme { SettingsScreen(LyricsViewModel(app), {}) } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("App", useUnmergedTree = true).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Background activity").assertIsDisplayed()
        compose.onNodeWithText("Open battery optimization settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Theme").assertDoesNotExist()
        compose.onNodeWithText("Open battery optimization settings").performClick()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun `onboarding has clear required and optional actions`() {
        compose.mainClock.autoAdvance = true
        compose.setContent { LyricDriveTheme { OnboardingScreen() } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("1. Connect your music").assertExists()
        compose.onNodeWithText("Grant notification access").assertExists()
        compose.onNodeWithText("Customize appearance and set up Android Auto").performScrollTo().assertIsDisplayed()
    }

    @Test fun `old background selections migrate to a visible gallery choice`() {
        val app = RuntimeEnvironment.getApplication() as LyricDriveApp
        for (old in listOf(BackgroundMode.ALBUM_FLUID, BackgroundMode.ALBUM_ART, BackgroundMode.ALBUM_GRADIENT, BackgroundMode.PULSE, BackgroundMode.BLACK)) {
            app.getSharedPreferences("settings", 0).edit().putString("background_mode", old.name).commit()
            val settings = SettingsRepository(app)
            assertEquals(old.galleryChoice(), settings.settings.value.backgroundMode)
        }
    }
}
