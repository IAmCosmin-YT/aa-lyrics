package dev.lyricdrive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.render.BackgroundPainter
import dev.lyricdrive.render.palette
import dev.lyricdrive.render.theme
import dev.lyricdrive.ui.theme.LyricPalette

/** A background theme on its own (onboarding, settings thumbnails). Static unless [animate]. */
@Composable
fun ThemeBackground(mode: BackgroundMode, albumPalette: LyricPalette, modifier: Modifier = Modifier, animate: Boolean = true, staticTime: Float = 3f) {
    val density = LocalDensity.current.density
    val painter = remember(density) { BackgroundPainter(density) }
    val time = remember { mutableFloatStateOf(staticTime) }
    if (animate) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) withFrameNanos { now ->
                if (last != 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(0.1f) * 0.6f
                last = now
            }
        }
    }
    Canvas(modifier) {
        val t = time.floatValue
        drawIntoCanvas {
            painter.draw(
                it.nativeCanvas,
                BackgroundPainter.Input(
                    mode = mode,
                    palette = mode.palette(albumPalette),
                    artwork = null,
                    time = t,
                    width = size.width.toInt(),
                    height = size.height.toInt(),
                    pulse = 0f,
                    activity = 0.5f,
                    beatIndex = 0,
                    partyAllowed = true,
                    scrim = mode.theme().scrim * 0.6f,
                ),
            )
        }
    }
}
