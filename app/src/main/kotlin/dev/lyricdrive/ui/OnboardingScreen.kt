package dev.lyricdrive.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.lyricdrive.R

@Composable
fun OnboardingScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var audioGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, audio) == PackageManager.PERMISSION_GRANTED) }
    val requestAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioGranted = it }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF251343), Color(0xFF0E0820))))
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("LyricDrive", color = Color.White.copy(alpha = 0.65f), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.welcome_title), color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.welcome_subtitle), color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
        Surface(color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.welcome_step_one), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.welcome_permission), color = Color.White.copy(alpha = 0.75f))
                Button(onClick = { NotificationAccess.openSettings(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.onboarding_grant))
                }
                Text(stringResource(R.string.onboarding_restricted_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
        Text(stringResource(R.string.welcome_step_two), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.welcome_play), color = Color.White.copy(alpha = 0.75f))
        if (!audioGranted) TextButton(onClick = { requestAudio.launch(audio) }) { Text(stringResource(R.string.welcome_audio_optional), color = Color.White) }
        else Text(stringResource(R.string.permission_audio_on), color = Color.White.copy(alpha = 0.7f))
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.welcome_customize), color = Color.White)
        }
    }
}
