package dev.lyricdrive.ui.components

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.lyricdrive.render.LyricsController

/**
 * Hosts the shared [LyricsController] (the same renderer the car uses) in Compose. Redraws on
 * every VSync while something animates, ~15 fps otherwise.
 *
 * @param lyricsArea region for lyrics in this canvas' coordinates; null = whole canvas.
 */
@Composable
fun LyricsCanvas(controller: LyricsController, modifier: Modifier = Modifier, lyricsArea: (() -> Rect?)? = null) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        var frames = 0L
        while (true) {
            withFrameNanos { now ->
                frames++
                if (controller.isAnimating() || frames % 4L == 0L) tick = now
            }
        }
    }
    Canvas(modifier) {
        val frameTime = tick // read in draw phase: invalidates the draw only
        val w = size.width.toInt()
        val h = size.height.toInt()
        val area = lyricsArea?.invoke() ?: Rect(0, 0, w, h)
        drawIntoCanvas { controller.draw(it.nativeCanvas, w, h, area, frameTime) }
    }
}
