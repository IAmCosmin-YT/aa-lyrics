package dev.lyricdrive.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lyricdrive.ui.theme.LyricPalette
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Slowly drifting mesh of album-colored radial blobs under a Gaussian blur (API 31+; the blobs
 * are already soft on older devices) and a dark scrim for text contrast. Motion speed follows the
 * song's lyric density and slows when playback pauses.
 */
@Composable
fun FluidBackground(palette: LyricPalette, energy: Float, playing: Boolean, modifier: Modifier = Modifier) {
    val deep by animateColorAsState(Color(palette.deep), tween(1_200), label = "deep")
    val primary by animateColorAsState(Color(palette.primary), tween(1_200), label = "primary")
    val secondary by animateColorAsState(Color(palette.secondary), tween(1_200), label = "secondary")
    val accent by animateColorAsState(Color(palette.accent), tween(1_200), label = "accent")

    val speed by rememberUpdatedState((0.35f + 0.9f * energy) * if (playing) 1f else 0.25f)
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(0.1f) * speed
                last = now
            }
        }
    }

    Box(modifier.fillMaxSize().background(deep)) {
        Canvas(Modifier.fillMaxSize().blur(72.dp, BlurredEdgeTreatment.Rectangle)) {
            val t = time.floatValue // read in draw phase: redraws without recomposing
            val colors = listOf(primary, secondary, accent, primary)
            colors.forEachIndexed { i, color ->
                val center = Offset(
                    size.width * (0.5f + 0.38f * sin(t * SPEED_X[i] + PHASE[i])),
                    size.height * (0.5f + 0.38f * cos(t * SPEED_Y[i] + PHASE[i] * 1.7f)),
                )
                val radius = max(size.width, size.height) * (0.55f + 0.08f * sin(t * 0.7f + i))
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)), center, radius),
                    radius = radius,
                    center = center,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
    }
}

private val SPEED_X = floatArrayOf(0.31f, 0.23f, 0.41f, 0.19f)
private val SPEED_Y = floatArrayOf(0.27f, 0.37f, 0.21f, 0.33f)
private val PHASE = floatArrayOf(0f, 1.9f, 3.7f, 5.1f)
