package dev.lyricdrive.ui

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.R
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.render.LiveSceneSource
import dev.lyricdrive.render.LyricsController
import dev.lyricdrive.ui.components.LyricsCanvas

@Composable
fun LyricsScreen(vm: LyricsViewModel, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = (context.applicationContext as LyricDriveApp).graph
    val density = LocalDensity.current.density
    val darkTheme by rememberUpdatedState(isSystemInDarkTheme())

    val controller = remember(density) {
        LyricsController(
            context = context,
            source = LiveSceneSource(graph.nowPlaying),
            settings = { vm.settings.value },
            density = density,
            nightSignal = { darkTheme },
            ambientSensor = { graph.ambientLight.level.value },
        )
    }
    DisposableEffect(controller) {
        graph.ambientLight.acquire()
        onDispose {
            graph.ambientLight.release()
            controller.release()
        }
    }
    val area = remember { Rect() }

    Box(Modifier.fillMaxSize()) {
        LyricsCanvas(controller, Modifier.fillMaxSize(), lyricsArea = { area })
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(ui, timingBadge(ui.lyrics, settings.wordHighlight), onSettings = onOpenSettings)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        area.set(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                    }
                    .pointerInput(controller) {
                        detectTapGestures { offset -> controller.onTap(offset.x + area.left, offset.y + area.top) }
                    }
                    .pointerInput(controller) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = {
                                controller.onFling(tracker.calculateVelocity().y)
                                controller.onDragEnd()
                            },
                            onDragCancel = { controller.onDragEnd() },
                        ) { change, dragAmount ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            controller.onScroll(-dragAmount)
                            change.consume()
                        }
                    },
            )
            Controls(ui.snapshot.isPlaying, vm::skipToPrevious, vm::togglePlayPause, vm::skipToNext)
        }
    }
}

/** Tells the user whether the fill follows real word timing, an estimate, or whole lines. */
@Composable
private fun timingBadge(state: LyricsState, mode: WordHighlight): String? {
    val lyrics = (state as? LyricsState.Ready)?.lyrics as? Lyrics.Synced ?: return null
    return stringResource(
        when {
            mode == WordHighlight.OFF -> R.string.badge_line_synced
            lyrics.hasWordTiming -> R.string.badge_word_synced
            mode == WordHighlight.AUTO -> R.string.badge_word_estimated
            else -> R.string.badge_line_synced
        },
    ) + " · " + lyrics.source
}

@Composable
private fun Header(ui: NowPlayingUi, badge: String?, onSettings: () -> Unit) {
    val track = ui.track
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (track != null) {
            ui.artwork?.let { art ->
                Image(
                    bitmap = remember(art) { art.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            if (track != null) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                badge?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1) }
            }
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings), tint = Color.White)
        }
    }
}

@Composable
private fun Controls(playing: Boolean, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (playing) R.string.pause else R.string.play),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next), tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
