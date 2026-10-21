package dev.lyricdrive.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class LyricsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Lets the Desktop Head Unit and sideloaded hosts connect to debug builds.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        CarDiagnostics.mark(this, CarDiagnostics.Step.SERVICE)
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        CarDiagnostics.mark(this, CarDiagnostics.Step.SESSION)
        return LyricsCarSession()
    }
}

class LyricsCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = LyricsCarScreen(carContext)
}
