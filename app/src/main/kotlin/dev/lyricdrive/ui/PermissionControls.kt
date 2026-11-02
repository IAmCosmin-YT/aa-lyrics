package dev.lyricdrive.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.lyricdrive.R

/** Available after onboarding too: local-file access is optional and can be granted later. */
@Composable
fun PermissionControls() {
    val context = LocalContext.current
    val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasAudio() = ContextCompat.checkSelfPermission(context, audio) == PackageManager.PERMISSION_GRANTED
    var notificationGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    var audioGranted by remember { mutableStateOf(hasAudio()) }
    val requestAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioGranted = it }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationGranted = NotificationAccess.isGranted(context)
        audioGranted = hasAudio()
    }
    Text(stringResource(if (notificationGranted) R.string.permission_notification_on else R.string.permission_notification_off), color = Color.White)
    TextButton(onClick = { NotificationAccess.openSettings(context) }) {
        Text(stringResource(R.string.onboarding_grant), color = Color.White)
    }
    Text(stringResource(if (audioGranted) R.string.permission_audio_on else R.string.permission_audio_off), color = Color.White)
    if (!audioGranted) TextButton(onClick = { requestAudio.launch(audio) }) {
        Text(stringResource(R.string.onboarding_local_files), color = Color.White)
    }
    TextButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }) { Text(stringResource(R.string.permission_app_settings), color = Color.White) }
}
