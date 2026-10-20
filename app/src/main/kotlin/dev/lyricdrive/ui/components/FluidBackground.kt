package dev.lyricdrive.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.ui.theme.LyricPalette
import dev.lyricdrive.ui.theme.forNight
import dev.lyricdrive.ui.theme.isFluid
import dev.lyricdrive.ui.theme.palette
import dev.lyricdrive.ui.theme.scrimAlpha
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Background for the lyrics screen in any [BackgroundMode], with a dark scrim for contrast and an
 * optional night look (darker, desaturated colors, heavier scrim).
 */
@Composable
fun LyricBackground(
    mode: BackgroundMode,
    albumPalette: LyricPalette,
    artwork: Bitmap?,
    energy: Float,
    playing: Boolean,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = mode.palette(albumPalette).forNight(night)
    val deep by animateColorAsState(Color(palette.deep), tween(1_200), label = "deep")
    val primary by animateColorAsState(Color(palette.primary), tween(1_200), label = "primary")
    val secondary by animateColorAsState(Color(palette.secondary), tween(1_200), label = "secondary")
    val accent by animateColorAsState(Color(palette.accent), tween(1_200), label = "accent")
    val scrim by animateFloatAsState(scrimAlpha(night), tween(1_500), label = "scrim")
    val time = rememberDriftTime(energy, playing)

    // A 32 px copy stretched with bilinear filtering is a cheap blur that works on every API level.
    val tinyArt = remember(artwork) { artwork?.takeUnless { it.isRecycled }?.let { Bitmap.createScaledBitmap(it, 32, 32, true).asImageBitmap() } }

    Box(modifier.fillMaxSize().background(deep)) {
        when {
            mode == BackgroundMode.BLACK -> Unit
            mode == BackgroundMode.ALBUM_GRADIENT -> Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(primary, secondary, deep), Offset.Zero, Offset.Infinite)),
            )
            mode == BackgroundMode.ALBUM_ART && tinyArt != null -> Canvas(Modifier.fillMaxSize().blur(40.dp, BlurredEdgeTreatment.Rectangle)) {
                val t = time.floatValue
                val zoom = 1.25f + 0.08f * sin(t * 0.4f)
                val side = max(size.width, size.height) * zoom
                val dx = (size.width - side) / 2f + size.width * 0.06f * sin(t * 0.23f)
                val dy = (size.height - side) / 2f + size.height * 0.06f * cos(t * 0.19f)
                drawImage(
                    tinyArt,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tinyArt.width, tinyArt.height),
                    dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                    dstSize = IntSize(side.toInt(), side.toInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
            mode.isFluid || mode == BackgroundMode.ALBUM_ART -> Blobs(time, listOf(primary, secondary, accent, primary))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
    }
}

/** Fluid background in a fixed palette (onboarding). */
@Composable
fun FluidBackground(palette: LyricPalette, energy: Float, playing: Boolean, modifier: Modifier = Modifier) =
    LyricBackground(BackgroundMode.ALBUM_FLUID, palette, null, energy, playing, night = false, modifier = modifier)

@Composable
private fun Blobs(time: MutableFloatState, colors: List<Color>) {
    Canvas(Modifier.fillMaxSize().blur(72.dp, BlurredEdgeTreatment.Rectangle)) {
        val t = time.floatValue // read in draw phase: redraws without recomposing
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
}

/** Monotonic animation time; runs faster for energetic songs and slows right down when paused. */
@Composable
private fun rememberDriftTime(energy: Float, playing: Boolean): MutableFloatState {
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
    return time
}

private val SPEED_X = floatArrayOf(0.31f, 0.23f, 0.41f, 0.19f)
private val SPEED_Y = floatArrayOf(0.27f, 0.37f, 0.21f, 0.33f)
private val PHASE = floatArrayOf(0f, 1.9f, 3.7f, 5.1f)
