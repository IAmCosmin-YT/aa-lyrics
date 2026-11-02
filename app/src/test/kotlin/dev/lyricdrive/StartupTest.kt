package dev.lyricdrive

import android.content.Intent
import dev.lyricdrive.ui.MainActivity
import dev.lyricdrive.ui.RecoveryActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], application = LyricDriveApp::class)
class StartupTest {
    @Test fun `fresh install opens before any permissions are granted`() {
        val app = RuntimeEnvironment.getApplication() as LyricDriveApp
        assertFalse(app.isGraphInitialized)
        Robolectric.buildActivity(MainActivity::class.java).use { activity ->
            activity.create().start().resume()
            assertNull(app.startupFailure)
            assertTrue(app.isGraphInitialized)
            assertNotNull(activity.get().window.decorView)
        }
    }

    @Test fun `previous crash redirects before the playback graph is initialized`() {
        val app = RuntimeEnvironment.getApplication() as LyricDriveApp
        CrashReporter.record(app, "test startup", IllegalStateException("test failure"))
        Robolectric.buildActivity(MainActivity::class.java).use { activity ->
            activity.create()
            assertEquals(RecoveryActivity::class.java.name, shadowOf(activity.get()).nextStartedActivity.component?.className)
            assertFalse(app.isGraphInitialized)
        }
    }

    @Test fun `recovery can open independently of playback and Compose`() {
        val app = RuntimeEnvironment.getApplication() as LyricDriveApp
        CrashReporter.record(app, "test startup", IllegalStateException("test failure"))
        Robolectric.buildActivity(RecoveryActivity::class.java).use { activity ->
            activity.setup()
            val root = activity.get().findViewById<android.view.ViewGroup>(android.R.id.content)
            assertTrue(root.getChildAt(0) is android.widget.ScrollView)
            assertFalse(app.isGraphInitialized)
            assertTrue(CrashReporter.report(app).contains("test failure"))
        }
    }

    @Test fun `aborted startup survives without a Java exception`() {
        val app = RuntimeEnvironment.getApplication() as LyricDriveApp
        File(app.filesDir, "startup-pending").writeText("-1")
        assertTrue(CrashReporter.needsRecovery(app))
        CrashReporter.clearPending(app)
        assertFalse(CrashReporter.needsRecovery(app))
    }
}
