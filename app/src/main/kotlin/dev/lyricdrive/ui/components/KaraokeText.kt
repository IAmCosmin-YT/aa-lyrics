package dev.lyricdrive.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import kotlin.math.floor

/**
 * Two stacked copies of the same text: a dim base and a bright, glowing copy clipped to the sung
 * portion. The clip follows wrapped lines and glides through each word because [filled] is a
 * fractional character count read in the draw phase (no recomposition per frame).
 */
@Composable
fun KaraokeText(
    text: String,
    style: TextStyle,
    active: Boolean,
    filled: () -> Float,
    modifier: Modifier = Modifier,
    dimColor: Color = Color.White.copy(alpha = 0.45f),
    litColor: Color = Color.White,
    glow: Boolean = true,
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier) {
        Text(text, style = style, color = dimColor, onTextLayout = { layout = it })
        if (active) {
            Text(
                text,
                style = if (glow) style.copy(shadow = Shadow(litColor.copy(alpha = 0.6f), Offset.Zero, blurRadius = 24f)) else style,
                color = litColor,
                modifier = Modifier.drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val f = filled()
                    if (f >= text.length) return@drawWithContent drawContent()
                    for (line in 0 until l.lineCount) {
                        val start = l.getLineStart(line)
                        val end = l.getLineEnd(line)
                        if (f <= start) break
                        val right = if (f >= end) size.width else xAt(l, line, f, text.length)
                        clipRect(0f, l.getLineTop(line), right, l.getLineBottom(line)) {
                            this@drawWithContent.drawContent()
                        }
                    }
                },
            )
        }
    }
}

private fun xAt(layout: TextLayoutResult, line: Int, filled: Float, length: Int): Float {
    val offset = floor(filled).toInt().coerceIn(0, length)
    val fraction = filled - offset
    val x0 = layout.getHorizontalPosition(offset, usePrimaryDirection = true)
    val x1 = if (offset + 1 < layout.getLineEnd(line)) layout.getHorizontalPosition(offset + 1, true) else layout.getLineRight(line)
    return x0 + (x1 - x0) * fraction
}
