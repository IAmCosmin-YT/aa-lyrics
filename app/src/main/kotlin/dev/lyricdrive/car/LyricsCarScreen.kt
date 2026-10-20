package dev.lyricdrive.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Full-screen lyrics. The template only carries a few controls; the lyrics themselves are drawn
 * on the car surface by [LyricsSurfaceRenderer]. The template is invalidated only when a control
 * changes (play/pause, follow), never per lyric line, so it stays far below host refresh limits.
 */
class LyricsCarScreen(carContext: CarContext) : Screen(carContext) {
    private val nowPlaying = (carContext.applicationContext as LyricDriveApp).graph.nowPlaying
    private val driving = DrivingStateMonitor(carContext)
    private val renderer = LyricsSurfaceRenderer(carContext, nowPlaying, driving) { invalidate() }

    init {
        lifecycle.addObserver(driving)
        lifecycle.addObserver(renderer)
        nowPlaying.refreshSessions()
        lifecycleScope.launch {
            nowPlaying.snapshot.map { it.isPlaying }.distinctUntilChanged().drop(1).collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val playing = nowPlaying.snapshot.value.isPlaying
        val strip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_skip_previous) { nowPlaying.skipToPrevious() })
            .addAction(iconAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play) { nowPlaying.togglePlayPause() })
            .addAction(iconAction(R.drawable.ic_skip_next) { nowPlaying.skipToNext() })
            .apply { if (!renderer.isFollowing) addAction(iconAction(R.drawable.ic_follow) { renderer.resumeFollowing() }) }
            .build()

        // PAN enables surface scroll gestures on hosts that gate them behind pan mode.
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN).build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setPanModeListener { inPanMode -> renderer.onPanModeChanged(inPanMode) }
            .build()
    }

    private fun iconAction(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()
}
