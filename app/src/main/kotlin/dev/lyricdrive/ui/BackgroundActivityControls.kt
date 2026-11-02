package dev.lyricdrive.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.lyricdrive.R

@Composable
fun BackgroundActivityControls() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    fun unrestricted() = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    var allowed by remember { mutableStateOf(unrestricted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = unrestricted() }
    Text(stringResource(if (allowed) R.string.battery_unrestricted else R.string.battery_optimized), color = Color.White)
    Text(stringResource(R.string.battery_help), color = Color.White.copy(alpha = 0.65f))
    TextButton(onClick = {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (error: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }) { Text(stringResource(R.string.battery_open), color = Color.White) }
    TextButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }) { Text(stringResource(R.string.battery_app_info), color = Color.White) }
}
