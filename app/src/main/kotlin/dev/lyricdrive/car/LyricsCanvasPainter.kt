package dev.lyricdrive.car

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.ui.theme.LyricPalette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pure drawing for the car surface. Holds caches only; all state comes in through [Frame]. */
class LyricsCanvasPainter(private val density: Float) {

    data class Frame(
        val area: Rect,
        val width: Int,
        val height: Int,
        val palette: LyricPalette,
        val backgroundTime: Float,
        val lines: List<LyricLine>,
        val activeIndex: Int,
        val filledCharacters: Float,
        val lineProgress: Float,
        val scrollY: Float,
        val glanceable: Boolean,
        /** Remaining fraction of the manual-scroll hold, or null when following. */
        val resumeCountdown: Float?,
        val header: String?,
        /** Centered message for non-synced states (loading, not found, instrumental...). */
        val message: String?,
        val subMessage: String?,
    )

    /** Rows drawn in the last frame, for tap hit-testing. */
    val hitRows = mutableListOf<Pair<Int, RectF>>()
    val resumeButton = RectF()

    private val rowPadding = 18f * density
    private val sidePadding = 32f * density
    private val minRowHeight = 64f * density

    private val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 30f * density
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blurFilters = HashMap<Int, BlurMaskFilter>()

    private var layoutLines: List<LyricLine>? = null
    private var layoutWidth = -1
    private var layouts: List<StaticLayout> = emptyList()
    private var rowTops = FloatArray(0)
    private var rowHeights = FloatArray(0)

    private var shaderPalette: LyricPalette? = null
    private var blobShaders: List<Shader> = emptyList()
    private val shaderMatrix = Matrix()

    private var blurNode: RenderNode? = null
    private var blurNodeSize = 0L

    // ------------------------------------------------------------------ geometry

    /** Lays out [lines] for [width] (cached) and returns the scroll offset centering line [index]. */
    fun scrollTargetFor(lines: List<LyricLine>, index: Int, area: Rect): Float {
        ensureLayouts(lines, area.width())
        if (rowTops.isEmpty()) return 0f
        val i = index.coerceIn(0, rowTops.lastIndex)
        return rowTops[i] + rowHeights[i] / 2f - area.height() / 2f
    }

    fun scrollRange(lines: List<LyricLine>, area: Rect): ClosedFloatingPointRange<Float> {
        val first = scrollTargetFor(lines, 0, area)
        val last = scrollTargetFor(lines, lines.lastIndex, area)
        return first..max(first, last)
    }

    private fun ensureLayouts(lines: List<LyricLine>, areaWidth: Int) {
        val width = (areaWidth - 2 * sidePadding).toInt().coerceAtLeast(1)
        if (lines === layoutLines && width == layoutWidth) return
        layoutLines = lines
        layoutWidth = width
        layouts = lines.map { line ->
            val text = if (line.isInterlude) "• • •" else line.text
            StaticLayout.Builder.obtain(text, 0, text.length, lyricPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()
        }
        rowHeights = FloatArray(layouts.size) { max(minRowHeight, layouts[it].height + 2 * rowPadding) }
        rowTops = FloatArray(layouts.size)
        var y = 0f
        for (i in layouts.indices) {
            rowTops[i] = y
            y += rowHeights[i]
        }
    }

    // ------------------------------------------------------------------ drawing

    fun draw(canvas: Canvas, frame: Frame) {
        drawBackground(canvas, frame)
        hitRows.clear()
        resumeButton.setEmpty()

        frame.header?.let { header ->
            labelPaint.alpha = 170
            val text = android.text.TextUtils.ellipsize(header, labelPaint, frame.area.width() - 2 * sidePadding - 200 * density, android.text.TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, frame.area.left + sidePadding, frame.area.top + 28f * density, labelPaint)
        }

        if (frame.message != null) {
            drawMessage(canvas, frame)
        } else {
            drawLyrics(canvas, frame)
        }
        frame.resumeCountdown?.let { drawResumePill(canvas, frame, it) }
    }

    private fun drawBackground(canvas: Canvas, frame: Frame) {
        val palette = frame.palette
        if (palette != shaderPalette) {
            shaderPalette = palette
            blobShaders = listOf(palette.primary, palette.secondary, palette.accent, palette.primary).map { color ->
                RadialGradient(0f, 0f, 1f, intArrayOf(ColorUtils.setAlphaComponent(color, 220), ColorUtils.setAlphaComponent(color, 0)), null, Shader.TileMode.CLAMP)
            }
        }

        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
            blurCanvas(frame.width, frame.height)
        } else null
        val c = target ?: canvas

        c.drawColor(palette.deep)
        val t = frame.backgroundTime
        for (i in blobShaders.indices) {
            val cx = w * (0.5f + 0.38f * sin(t * SPEED_X[i] + PHASE[i]))
            val cy = h * (0.5f + 0.38f * cos(t * SPEED_Y[i] + PHASE[i] * 1.7f))
            val r = max(w, h) * (0.55f + 0.08f * sin(t * 0.7f + i))
            shaderMatrix.setScale(r, r)
            shaderMatrix.postTranslate(cx, cy)
            blobShaders[i].setLocalMatrix(shaderMatrix)
            blobPaint.shader = blobShaders[i]
            c.drawCircle(cx, cy, r, blobPaint)
        }

        if (target != null) {
            val node = blurNode!!
            node.endRecording()
            canvas.drawRenderNode(node)
        }
        // Scrim keeps white text legible in sunlight regardless of album colors.
        canvas.drawColor(ColorUtils.setAlphaComponent(Color.BLACK, 96))
    }

    /** Returns a recording canvas whose content gets a hardware Gaussian blur (API 31+). */
    private fun blurCanvas(width: Int, height: Int): Canvas {
        val node = blurNode ?: RenderNode("lyrics-bg").also { blurNode = it }
        val size = (width.toLong() shl 32) or height.toLong()
        if (size != blurNodeSize) {
            blurNodeSize = size
            node.setPosition(0, 0, width, height)
            val radius = 60f * density
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        return node.beginRecording(width, height)
    }

    private fun drawLyrics(canvas: Canvas, frame: Frame) {
        if (frame.lines.isEmpty()) return
        ensureLayouts(frame.lines, frame.area.width())
        val area = frame.area
        val left = area.left + sidePadding
        val active = frame.activeIndex

        for (i in layouts.indices) {
            val distance = if (active < 0) i + 1 else i - active
            if (frame.glanceable && abs(distance) > 1) continue
            val top = area.top + rowTops[i] - frame.scrollY
            val bottom = top + rowHeights[i]
            if (bottom < area.top || top > area.bottom) continue

            val isActive = distance == 0
            val scale = if (isActive) 1f else 0.85f
            val alpha = when {
                isActive -> 255
                abs(distance) == 1 -> 128
                else -> 102
            }
            val blurLevel = if (isActive || frame.glanceable) 0 else min(abs(distance), 3)
            val layout = layouts[i]
            val textTop = top + (rowHeights[i] - layout.height) / 2f

            canvas.save()
            canvas.scale(scale, scale, left, top + rowHeights[i] / 2f)
            canvas.translate(left, textTop)
            lyricPaint.maskFilter = if (blurLevel == 0) null else blurFilter(blurLevel)
            if (isActive && !frame.lines[i].isInterlude) {
                drawKaraoke(canvas, layout, frame.filledCharacters)
            } else if (isActive) {
                // Interlude: dots breathe with the progress of the break.
                lyricPaint.alpha = (140 + 115 * sin(frame.lineProgress * Math.PI.toFloat() * 6f).let { it * it }).toInt()
                layout.draw(canvas)
            } else {
                lyricPaint.alpha = alpha
                layout.draw(canvas)
            }
            canvas.restore()
            lyricPaint.maskFilter = null

            hitRows += i to RectF(area.left.toFloat(), top, area.right.toFloat(), bottom)
        }
    }

    /** Dim base text, then the same text bright + glowing, clipped to the sung portion. */
    private fun drawKaraoke(canvas: Canvas, layout: StaticLayout, filled: Float) {
        lyricPaint.alpha = 115
        layout.draw(canvas)

        lyricPaint.alpha = 255
        lyricPaint.setShadowLayer(14f * density, 0f, 0f, ColorUtils.setAlphaComponent(Color.WHITE, 150))
        val length = layout.text.length
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            if (filled <= start) break
            val right = if (filled >= end) layout.width.toFloat() else xAt(layout, line, filled, length)
            canvas.save()
            canvas.clipRect(0f, layout.getLineTop(line).toFloat(), right, layout.getLineBottom(line).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
        lyricPaint.clearShadowLayer()
    }

    private fun xAt(layout: StaticLayout, line: Int, filled: Float, length: Int): Float {
        val offset = floor(filled).toInt().coerceIn(0, length)
        val fraction = filled - offset
        val x0 = layout.getPrimaryHorizontal(offset)
        val x1 = if (offset + 1 < layout.getLineEnd(line)) layout.getPrimaryHorizontal(offset + 1) else layout.getLineRight(line)
        return x0 + (x1 - x0) * fraction
    }

    private fun drawMessage(canvas: Canvas, frame: Frame) {
        val cx = frame.area.exactCenterX()
        val cy = frame.area.exactCenterY()
        messagePaint.alpha = 255
        messagePaint.textSize = 34f * density
        canvas.drawText(ellipsize(frame.message!!, messagePaint, frame.area.width()), cx, cy, messagePaint)
        frame.subMessage?.let {
            messagePaint.alpha = 170
            messagePaint.textSize = 20f * density
            canvas.drawText(ellipsize(it, messagePaint, frame.area.width()), cx, cy + 36f * density, messagePaint)
        }
    }

    private fun drawResumePill(canvas: Canvas, frame: Frame, remaining: Float) {
        val text = "Resume auto-scroll"
        val textWidth = labelPaint.measureText(text)
        val w = textWidth + 48f * density
        val h = 48f * density
        val cx = frame.area.exactCenterX()
        val bottom = frame.area.bottom - 20f * density
        resumeButton.set(cx - w / 2, bottom - h, cx + w / 2, bottom)

        pillPaint.color = ColorUtils.setAlphaComponent(Color.BLACK, 150)
        canvas.drawRoundRect(resumeButton, h / 2, h / 2, pillPaint)
        // Countdown underline shows when auto-scroll resumes by itself.
        pillPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 200)
        val barInset = h / 2
        canvas.drawRect(resumeButton.left + barInset, resumeButton.bottom - 4f * density, resumeButton.left + barInset + (w - 2 * barInset) * remaining, resumeButton.bottom - 2f * density, pillPaint)
        labelPaint.alpha = 255
        canvas.drawText(text, cx - textWidth / 2, resumeButton.centerY() + labelPaint.textSize / 3, labelPaint)
    }

    private fun blurFilter(level: Int) =
        blurFilters.getOrPut(level) { BlurMaskFilter(level * 1.6f * density, BlurMaskFilter.Blur.NORMAL) }

    private fun ellipsize(text: String, paint: TextPaint, width: Int): String =
        android.text.TextUtils.ellipsize(text, paint, width - 2 * sidePadding, android.text.TextUtils.TruncateAt.END).toString()

    private companion object {
        val SPEED_X = floatArrayOf(0.31f, 0.23f, 0.41f, 0.19f)
        val SPEED_Y = floatArrayOf(0.27f, 0.37f, 0.21f, 0.33f)
        val PHASE = floatArrayOf(0f, 1.9f, 3.7f, 5.1f)
    }
}
