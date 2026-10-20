package dev.lyricdrive.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricdrive.R
import dev.lyricdrive.ui.components.FluidBackground
import dev.lyricdrive.ui.theme.LyricPalette

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val requestAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Box(Modifier.fillMaxSize()) {
        FluidBackground(LyricPalette.DEFAULT, energy = 0.3f, playing = true)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.onboarding_title), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.onboarding_body), color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp)
            Spacer(Modifier.size(28.dp))
            Button(
                onClick = { context.startActivity(NotificationAccess.settingsIntent(context)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.onboarding_grant), fontSize = 17.sp) }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(
                onClick = { requestAudio.launch(audioPermission) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.onboarding_local_files), color = Color.White) }
            Spacer(Modifier.size(20.dp))
            Text(stringResource(R.string.onboarding_restricted_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}
