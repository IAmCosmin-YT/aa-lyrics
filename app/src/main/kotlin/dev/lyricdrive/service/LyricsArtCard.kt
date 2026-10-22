package dev.lyricdrive.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.ui.theme.LyricPalette
import kotlin.math.floor

/**
 * Renders the current lyric as an album-art-sized card for Android Auto's media player, which
 * displays whatever artwork the session supplies. Blurred cover background, the current line
 * with a soft word-by-word fill, and the next line underneath. Software-only so it works off the
 * GPU, small enough (RGB_565) to cross the binder in media metadata.
 */
class LyricsArtCard {
    private val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
    private val canvas = Canvas(bitmap)
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 24f
    }
    private val rect = RectF()

    private var tinySource: Bitmap? = null
    private var tiny: Bitmap? = null

    /**
     * Returns a fresh copy each call: media metadata keeps a reference, so the drawing buffer
     * must not be reused underneath it.
     */
    fun render(artwork: Bitmap?, palette: LyricPalette, line: LyricLine?, next: LyricLine?, filled: Float, progress: Float): Bitmap {
        drawBackground(artwork, palette)
        val width = SIZE - 2 * PAD
        val maxHeight = SIZE * 0.6f

        if (line == null || line.isInterlude) {
            drawDots(progress)
        } else {
            val layout = fit(line.text, width, maxHeight)
            val top = SIZE * 0.42f - layout.height / 2f
            canvas.save()
            canvas.translate(PAD.toFloat(), top.coerceAtLeast(PAD.toFloat()))
            drawFilled(layout, filled)
            canvas.restore()
            next?.takeUnless { it.isInterlude }?.let { drawNext(it.text, top + layout.height + 22f, width) }
        }
        return bitmap.copy(Bitmap.Config.RGB_565, false)
    }

    private fun drawBackground(artwork: Bitmap?, palette: LyricPalette) {
        val art = artwork?.takeUnless { it.isRecycled }
        if (art != null) {
            if (art !== tinySource) {
                tinySource = art
                tiny = Bitmap.createScaledBitmap(art, 20, 20, true)
            }
            rect.set(-SIZE * 0.15f, -SIZE * 0.15f, SIZE * 1.15f, SIZE * 1.15f)
            canvas.drawBitmap(tiny!!, null, rect, bgPaint)
        } else {
            fillPaint.shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), intArrayOf(palette.primary, palette.secondary, palette.deep), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), fillPaint)
            fillPaint.shader = null
        }
        // Dark wash for contrast in daylight.
        canvas.drawColor(ColorUtils.setAlphaComponent(Color.BLACK, 150))
    }

    /** Largest text size (≤ 46 px) whose wrapped layout fits the card. */
    private fun fit(text: String, width: Int, maxHeight: Float): StaticLayout {
        var size = 46f
        while (true) {
            linePaint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, linePaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()
            if (layout.height <= maxHeight || size <= 24f) return layout
            size -= 2f
        }
    }

    private fun drawFilled(layout: StaticLayout, filled: Float) {
        linePaint.shader = null
        linePaint.color = Color.WHITE
        linePaint.alpha = 105
        layout.draw(canvas)

        val text = layout.text
        val feather = 28f
        for (li in 0 until layout.lineCount) {
            val start = layout.getLineStart(li)
            var end = layout.getLineEnd(li)
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end <= start) continue
            if (filled <= start) break
            val front = if (filled >= end) Float.NaN else {
                val offset = floor(filled).toInt().coerceIn(start, end)
                val x0 = layout.getPrimaryHorizontal(offset)
                val x1 = if (offset + 1 < end) layout.getPrimaryHorizontal(offset + 1) else layout.getLineRight(li)
                x0 + (x1 - x0) * (filled - offset).coerceIn(0f, 1f)
            }
            linePaint.color = Color.WHITE
            linePaint.shader = if (front.isNaN()) null else LinearGradient(front - feather * 0.6f, 0f, front + feather * 0.4f, 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
            linePaint.alpha = 255
            canvas.drawText(text, start, end, layout.getLineLeft(li), layout.getLineBaseline(li).toFloat(), linePaint)
        }
        linePaint.shader = null
    }

    private fun drawNext(text: String, top: Float, width: Int) {
        if (top > SIZE - PAD - 24f) return
        nextPaint.alpha = 130
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, nextPaint, width)
            .setMaxLines(2)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(PAD.toFloat(), top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawDots(progress: Float) {
        val r = 11f
        val gap = 38f
        val cx = SIZE / 2f - gap
        val cy = SIZE * 0.45f
        for (i in 0..2) {
            val f = (progress * 3f - i).coerceIn(0f, 1f)
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (80 + 175 * f).toInt()
            canvas.drawCircle(cx + gap * i, cy, r * (0.8f + 0.3f * f), fillPaint)
        }
    }

    private companion object {
        const val SIZE = 448
        const val PAD = 30
    }
}
