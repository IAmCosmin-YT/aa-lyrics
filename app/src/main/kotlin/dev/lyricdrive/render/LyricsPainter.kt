package dev.lyricdrive.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.LyricWord
import dev.lyricdrive.core.model.Voice
import dev.lyricdrive.core.model.filledCharacters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws lyrics for every [LayoutKind] onto a plain [Canvas]; used by both the car surface and the
 * phone. Holds only caches; all state arrives in [Frame].
 *
 * Each row is a line's main vocals plus, optionally, its backing vocals as a smaller sub-line.
 * In duets, singer 1 is aligned left, singer 2 right and "together" lines centered. Several rows
 * can be active at once (overlapping duet lines, backing vocals running past the next line).
 */
class LyricsPainter(private val density: Float) {

    data class Frame(
        /** Region available for lyrics (excludes phone chrome / car action strip). */
        val area: Rect,
        val lines: List<LyricLine>,
        val synced: Boolean,
        /** The most recently started line (scroll anchor); -1 before the first line. */
        val activeIndex: Int,
        /** Every line being sung right now (includes [activeIndex]). */
        val activeSet: Set<Int>,
        val positionMs: Long,
        val filledCharacters: Float,
        val lineProgress: Float,
        val scrollY: Float,
        /** Driving: list layouts show only previous / current / next. */
        val compact: Boolean,
        val style: LyricStyleSpec,
        val theme: ThemeSpec,
        /** Fill color for singer 1 / the default voice. */
        val highlight: Int,
        /** Fill color for singer 2 in duets. */
        val highlight2: Int,
        /** Base color of unsung / inactive text. */
        val textColor: Int,
        val textAlpha: Float,
        /** 0..1 parked full-screen expansion (cover panel slides in from the left). */
        val expansion: Float,
        val artwork: Bitmap?,
        val title: String?,
        val artist: String?,
        /** Small "title · artist" line drawn at the top (car, when not expanded). */
        val header: String?,
        val message: String?,
        val subMessage: String?,
        /** Remaining fraction of the manual-scroll hold, or null when following. */
        val resumeCountdown: Float?,
        val tapRect: RectF?,
        val tapProgress: Float,
        /** Base lyric text size in px. */
        val textSize: Float,
    )

    /** Rows drawn in the last frame, for tap hit-testing. */
    val hitRows = mutableListOf<Pair<Int, RectF>>()
    val resumeButton = RectF()

    private val rowPadding = 16f * density
    private val sidePadding = 28f * density
    private val minRowHeight = 64f * density
    private val backingGap = 4f * density

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val blurFilters = HashMap<Int, BlurMaskFilter>()
    private val glowFilters = HashMap<Int, BlurMaskFilter>()
    private val path = Path()
    private val tmpRect = RectF()

    // ------------------------------------------------------------------ layout cache

    /** A line's main layout and optional backing-vocal layout, with the paints they were built with. */
    class Row(
        val main: StaticLayout,
        val mainPaint: TextPaint,
        val backing: StaticLayout?,
        val alignment: Layout.Alignment,
        private val gap: Float,
    ) {
        val contentHeight: Float get() = main.height + (backing?.let { it.height + gap } ?: 0f)
    }

    /** Wrapped rows for one (lines, width, size, typeface, alignment mode) combination. */
    class LayoutSet(val paint: TextPaint, val backingPaint: TextPaint, val rows: List<Row>, val rowTops: FloatArray, val rowHeights: FloatArray)

    private data class LayoutKey(val lines: List<LyricLine>, val width: Int, val size: Float, val typeface: Typeface, val center: Boolean)

    private val layoutCache = object : LinkedHashMap<LayoutKey, LayoutSet>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, LayoutSet>?) = size > 8
    }

    private fun layoutSet(lines: List<LyricLine>, width: Int, size: Float, typeface: Typeface, center: Boolean): LayoutSet {
        val key = LayoutKey(lines, width.coerceAtLeast(1), size, typeface, center)
        layoutCache[key]?.let { return it }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            this.typeface = typeface
        }
        val backingPaint = TextPaint(paint).apply { textSize = size * BACKING_SCALE }
        val duet = lines.any { it.voice == Voice.SINGER_1 } && lines.any { it.voice == Voice.SINGER_2 }

        fun build(text: String, p: TextPaint, align: Layout.Alignment) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, key.width)
                .setAlignment(align)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()

        val rows = lines.map { line ->
            val align = when {
                center -> Layout.Alignment.ALIGN_CENTER
                !duet -> Layout.Alignment.ALIGN_NORMAL
                line.voice == Voice.SINGER_2 -> Layout.Alignment.ALIGN_OPPOSITE
                line.voice == Voice.TOGETHER -> Layout.Alignment.ALIGN_CENTER
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val mainPaint = if (line.secondary) backingPaint else paint
            val text = if (line.isInterlude) "• • •" else line.text
            Row(
                main = build(text, mainPaint, align),
                mainPaint = mainPaint,
                backing = line.backing?.let { build(it.text, backingPaint, align) },
                alignment = align,
                gap = backingGap,
            )
        }
        val heights = FloatArray(rows.size) { max(minRowHeight, rows[it].contentHeight + 2 * rowPadding) }
        val tops = FloatArray(rows.size)
        var y = 0f
        for (i in rows.indices) {
            tops[i] = y
            y += heights[i]
        }
        return LayoutSet(paint, backingPaint, rows, tops, heights).also { layoutCache[key] = it }
    }

    /** Where lyrics go: full area, or right of the cover panel when expanded. */
    fun contentRect(area: Rect, expanded: Boolean): Rect =
        if (!expanded) area else Rect(area.left + panelWidth(area).toInt(), area.top, area.right, area.bottom)

    private fun panelWidth(area: Rect) = area.width() * 0.36f

    private fun listSet(lines: List<LyricLine>, rect: Rect, frameTextSize: Float, typeface: Typeface) =
        layoutSet(lines, (rect.width() - 2 * sidePadding).toInt(), frameTextSize, typeface, center = false)

    /** Scroll offset that centers rows [first]..[last] (the lines being sung) in [rect]. */
    fun scrollTargetFor(lines: List<LyricLine>, first: Int, last: Int, rect: Rect, textSize: Float, typeface: Typeface): Float {
        if (lines.isEmpty()) return 0f
        val set = listSet(lines, rect, textSize, typeface)
        val a = first.coerceIn(0, set.rowTops.lastIndex)
        val b = last.coerceIn(a, set.rowTops.lastIndex)
        val top = set.rowTops[a]
        val bottom = set.rowTops[b] + set.rowHeights[b]
        return (top + bottom) / 2f - rect.height() / 2f
    }

    fun scrollTargetFor(lines: List<LyricLine>, index: Int, rect: Rect, textSize: Float, typeface: Typeface): Float =
        scrollTargetFor(lines, index, index, rect, textSize, typeface)

    fun scrollRange(lines: List<LyricLine>, rect: Rect, textSize: Float, typeface: Typeface): ClosedFloatingPointRange<Float> {
        val first = scrollTargetFor(lines, 0, rect, textSize, typeface)
        val last = scrollTargetFor(lines, lines.lastIndex, rect, textSize, typeface)
        return first..max(first, last)
    }

    // ------------------------------------------------------------------ frame

    fun draw(canvas: Canvas, frame: Frame) {
        hitRows.clear()
        resumeButton.setEmpty()
        labelPaint.color = frame.textColor
        messagePaint.color = frame.textColor
        val e = frame.expansion

        if (e > 0.01f) drawPanel(canvas, frame, e)
        frame.header?.takeIf { e < 0.99f }?.let { header ->
            labelPaint.alpha = (170 * (1 - e)).toInt()
            val text = TextUtils.ellipsize(header, labelPaint, frame.area.width() - 2 * sidePadding - 200 * density, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, frame.area.left + sidePadding, frame.area.top + 28f * density, labelPaint)
        }

        val compactRect = contentRect(frame.area, expanded = false)
        val expandedRect = contentRect(frame.area, expanded = true)
        when {
            e <= 0.01f -> drawContent(canvas, frame, compactRect, 1f, frame.scrollY, recordHits = true)
            e >= 0.99f -> drawContent(canvas, frame, expandedRect, 1f, frame.scrollY, recordHits = true)
            else -> {
                // Mid-transition: crossfade the two layouts, each centered on the active line.
                drawContent(canvas, frame, compactRect, 1f - e, null, recordHits = e < 0.5f)
                drawContent(canvas, frame, expandedRect, e, null, recordHits = e >= 0.5f)
            }
        }

        frame.tapRect?.let { r ->
            fillPaint.color = frame.textColor
            fillPaint.alpha = (60 * (1f - frame.tapProgress)).toInt()
            canvas.drawRoundRect(r, 16f * density, 16f * density, fillPaint)
        }
        labelPaint.color = Color.WHITE
        frame.resumeCountdown?.let { drawResumePill(canvas, frame, it) }
    }

    private fun drawContent(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float, scrollY: Float?, recordHits: Boolean) {
        if (alpha <= 0.01f) return
        val hitsBefore = hitRows.size
        if (frame.message != null || frame.lines.isEmpty()) {
            drawMessage(canvas, frame, rect, alpha)
        } else {
            when (if (frame.synced) frame.style.layout else LayoutKind.LIST) {
                LayoutKind.LIST -> drawList(canvas, frame, rect, alpha, scrollY)
                LayoutKind.CINEMATIC -> drawCinematic(canvas, frame, rect, alpha)
                LayoutKind.GLANCE -> drawGlance(canvas, frame, rect, alpha)
                LayoutKind.SINGLE_LINE -> drawSingleLine(canvas, frame, rect, alpha)
            }
        }
        if (!recordHits) while (hitRows.size > hitsBefore) hitRows.removeAt(hitRows.lastIndex)
    }

    // ------------------------------------------------------------------ list

    private fun drawList(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float, scrollY: Float?) {
        val style = frame.style
        val set = listSet(frame.lines, rect, frame.textSize, frame.theme.typeface)
        val active = if (frame.synced) frame.activeIndex else -1
        val firstActive = frame.activeSet.minOrNull() ?: active
        val scroll = scrollY ?: scrollTargetFor(frame.lines, firstActive.coerceAtLeast(0), active.coerceAtLeast(0), rect, frame.textSize, frame.theme.typeface)
        val left = rect.left + sidePadding
        val width = rect.width() - 2 * sidePadding

        canvas.save()
        canvas.clipRect(rect)
        for (i in set.rows.indices) {
            val isActive = frame.synced && i in frame.activeSet
            val distance = when {
                isActive -> 0
                active < 0 -> i + 1
                i < firstActive -> i - firstActive
                else -> i - active
            }
            if (frame.compact && frame.synced && abs(distance) > 1) continue
            val top = rect.top + set.rowTops[i] - scroll
            val bottom = top + set.rowHeights[i]
            if (bottom < rect.top || top > rect.bottom) continue

            val row = set.rows[i]
            val line = frame.lines[i]
            val scale = if (isActive) style.activeScale else if (frame.synced) style.inactiveScale else 1f
            var lineAlpha = alpha * frame.textAlpha * when {
                !frame.synced -> 0.85f
                isActive -> 1f
                abs(distance) == 1 -> style.nearAlpha
                else -> style.farAlpha
            }
            if (frame.theme.highContrast && !isActive) lineAlpha = max(lineAlpha, alpha * frame.textAlpha * 0.62f)
            val blurLevel = if (isActive || frame.compact || style.blurPerStepDp == 0f || !frame.synced || frame.theme.highContrast) 0 else min(abs(distance), 3)
            val textTop = top + (set.rowHeights[i] - row.contentHeight) / 2f
            // Scale toward the side the row is aligned to.
            val pivotX = when (row.alignment) {
                Layout.Alignment.ALIGN_OPPOSITE -> left + width
                Layout.Alignment.ALIGN_CENTER -> left + width / 2f
                else -> left
            }

            canvas.save()
            canvas.scale(scale, scale, pivotX, top + set.rowHeights[i] / 2f)
            canvas.translate(left, textTop)
            val mask = if (blurLevel == 0) null else blurFilter(blurLevel, style.blurPerStepDp)
            row.mainPaint.maskFilter = mask
            set.backingPaint.maskFilter = mask
            drawRow(canvas, row, set.backingPaint, line, frame, lineAlpha, isActive)
            row.mainPaint.maskFilter = null
            set.backingPaint.maskFilter = null
            canvas.restore()
            hitRows += i to RectF(rect.left.toFloat(), top, rect.right.toFloat(), bottom)
        }
        canvas.restore()
    }

    /** Draws a row's main line and backing sub-line at the current origin (main line's top-left). */
    private fun drawRow(canvas: Canvas, row: Row, backingPaint: TextPaint, line: LyricLine, frame: Frame, alpha: Float, active: Boolean) {
        val mainAlpha = alpha * if (line.secondary) SECONDARY_ALPHA else 1f
        val color = colorFor(line, frame)
        when {
            line.isInterlude && frame.synced -> drawDots(canvas, row.mainPaint, row.main, line, frame, if (active) alpha else alpha * 0.5f, active)
            active -> drawActiveRun(canvas, row.mainPaint, row.main, line.text, line.words, line.startMs, frame, mainAlpha, color)
            else -> drawPlain(canvas, row.mainPaint, row.main, mainAlpha, frame)
        }
        val backing = line.backing ?: return
        val layout = row.backing ?: return
        canvas.save()
        canvas.translate(0f, row.main.height + backingGap)
        val sung = active && frame.positionMs >= backing.startMs
        if (sung) {
            drawActiveRun(canvas, backingPaint, layout, backing.text, backing.words, backing.startMs, frame, alpha * BACKING_ALPHA_ACTIVE, color)
        } else {
            drawPlain(canvas, backingPaint, layout, alpha * BACKING_ALPHA, frame)
        }
        canvas.restore()
    }

    private fun colorFor(line: LyricLine, frame: Frame): Int =
        if (line.voice == Voice.SINGER_2) frame.highlight2 else frame.highlight

    // ------------------------------------------------------------------ focused layouts

    private fun focusedSet(frame: Frame, rect: Rect, sizeFactor: Float) =
        layoutSet(frame.lines, (rect.width() - 2 * sidePadding).toInt(), frame.textSize * sizeFactor, frame.theme.typeface, center = true)

    private fun transition(frame: Frame): Float {
        val line = frame.lines.getOrNull(frame.activeIndex) ?: return 1f
        val t = ((frame.positionMs - line.startMs).toFloat() / frame.style.transitionMs).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
    }

    /** Two subtitle lines in the lower third; the finished line drifts up and dissolves. */
    private fun drawCinematic(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float) {
        val set = focusedSet(frame, rect, 0.9f)
        val active = frame.activeIndex
        val t = transition(frame)
        val gap = 10f * density
        val activeY = rect.top + rect.height() * 0.58f
        val left = rect.left + sidePadding

        fun height(i: Int) = set.rows.getOrNull(i)?.contentHeight ?: 0f

        // Outgoing line: drifts away, unless it's still being sung (overlap), then it stays lit.
        val prev = active - 1
        if (prev >= 0) {
            val stillSung = prev in frame.activeSet
            val drift = if (stillSung) 0f else 40f * density * t
            val a = if (stillSung) 1f else 1f - t
            if (a > 0.01f) {
                val y = activeY - height(prev) - gap - drift
                set.rows[prev].mainPaint.maskFilter = if (stillSung) null else blurFilter(2, 1.5f * t)
                drawAt(canvas, left, y) { drawRow(canvas, set.rows[prev], set.backingPaint, frame.lines[prev], frame, alpha * frame.textAlpha * a, stillSung) }
                set.rows[prev].mainPaint.maskFilter = null
            }
        }
        if (active >= 0) {
            val fromY = activeY + height(prev) + gap
            val y = if (prev >= 0 && prev !in frame.activeSet) fromY + (activeY - fromY) * t else activeY
            drawAt(canvas, left, y) { drawRow(canvas, set.rows[active], set.backingPaint, frame.lines[active], frame, alpha * frame.textAlpha, true) }
            hitRows += active to RectF(rect.left.toFloat(), y, rect.right.toFloat(), y + height(active))
        }
        val next = active + 1
        if (next < set.rows.size) {
            val y = activeY + height(active.coerceAtLeast(0)) + gap
            drawAt(canvas, left, y) { drawRow(canvas, set.rows[next], set.backingPaint, frame.lines[next], frame, alpha * frame.textAlpha * 0.5f * t, false) }
            hitRows += next to RectF(rect.left.toFloat(), y, rect.right.toFloat(), y + height(next))
        }
    }

    /** Big centered current line (with its backing vocals), small next line, near-instant crossfade. */
    private fun drawGlance(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float) {
        val big = focusedSet(frame, rect, 1.2f)
        val small = focusedSet(frame, rect, 0.6f)
        val active = frame.activeIndex.coerceAtLeast(0)
        val t = transition(frame)
        val left = rect.left + sidePadding
        val activeH = big.rows[active].contentHeight
        val nextH = small.rows.getOrNull(active + 1)?.main?.height?.toFloat() ?: 0f
        val gap = 14f * density
        val top = rect.exactCenterY() - (activeH + gap + nextH) / 2f

        if (!frame.theme.light) big.paint.setShadowLayer(4f * density, 0f, 2f * density, ColorUtils.setAlphaComponent(Color.BLACK, 170))
        drawAt(canvas, left, top) {
            drawRow(canvas, big.rows[active], big.backingPaint, frame.lines[active], frame, alpha * frame.textAlpha * (if (frame.activeIndex >= 0) t else 0.6f), frame.activeIndex >= 0)
        }
        big.paint.clearShadowLayer()
        hitRows += active to RectF(rect.left.toFloat(), top, rect.right.toFloat(), top + activeH)
        small.rows.getOrNull(active + 1)?.let { row ->
            val y = top + activeH + gap
            drawAt(canvas, left, y) { drawPlain(canvas, row.mainPaint, row.main, alpha * frame.textAlpha * t * 0.6f, frame) }
            hitRows += (active + 1) to RectF(rect.left.toFloat(), y, rect.right.toFloat(), y + row.main.height)
        }
    }

    /** Only the current line (and its backing vocals), centered, with a thin progress bar. */
    private fun drawSingleLine(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float) {
        val set = focusedSet(frame, rect, 1.1f)
        val active = frame.activeIndex
        val t = transition(frame)
        val left = rect.left + sidePadding
        val index = active.coerceAtLeast(0)
        val row = set.rows[index]
        val barGap = 18f * density
        val top = rect.exactCenterY() - (row.contentHeight + barGap) / 2f + (1f - t) * 12f * density
        drawAt(canvas, left, top) {
            drawRow(canvas, row, set.backingPaint, frame.lines[index], frame, alpha * frame.textAlpha * (if (active >= 0) t else 0.6f), active >= 0)
        }
        hitRows += index to RectF(rect.left.toFloat(), top, rect.right.toFloat(), top + row.contentHeight)

        val line = frame.lines[index]
        val progress = if (active < 0) 0f else if (line.hasWordTiming && line.text.isNotEmpty()) frame.filledCharacters / line.text.length else frame.lineProgress
        val barW = rect.width() * 0.4f
        val barX = rect.exactCenterX() - barW / 2f
        val barY = top + row.contentHeight + barGap
        val barH = 3f * density
        fillPaint.color = frame.textColor
        fillPaint.alpha = (60 * alpha).toInt()
        tmpRect.set(barX, barY, barX + barW, barY + barH)
        canvas.drawRoundRect(tmpRect, barH, barH, fillPaint)
        fillPaint.color = colorFor(line, frame)
        fillPaint.alpha = (230 * alpha * frame.textAlpha).toInt()
        tmpRect.set(barX, barY, barX + barW * progress.coerceIn(0f, 1f), barY + barH)
        canvas.drawRoundRect(tmpRect, barH, barH, fillPaint)
    }

    private inline fun drawAt(canvas: Canvas, x: Float, y: Float, block: () -> Unit) {
        canvas.save()
        canvas.translate(x, y)
        block()
        canvas.restore()
    }

    // ------------------------------------------------------------------ line drawing
    //
    // Nothing here clips to rectangles: the fill front is a horizontal gradient on the glyphs
    // themselves and the glow is a blurred copy of the same glyphs, so edges stay soft at any
    // size or orientation.

    private fun drawPlain(canvas: Canvas, paint: TextPaint, layout: StaticLayout, alpha: Float, frame: Frame) {
        if (alpha <= 0.01f) return
        if (frame.style.outline) drawOutline(canvas, paint, layout, alpha, frame)
        paint.color = frame.textColor
        paint.alpha = (255 * alpha).toInt()
        layout.draw(canvas)
    }

    private fun drawOutline(canvas: Canvas, paint: TextPaint, layout: StaticLayout, alpha: Float, frame: Frame) {
        val mask = paint.maskFilter
        paint.maskFilter = null
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 3.5f * density
        paint.color = if (frame.theme.light) Color.WHITE else Color.BLACK
        paint.alpha = (200 * alpha).toInt()
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.maskFilter = mask
    }

    /**
     * An active run of sung text (a line's main vocals or its backing vocals). Word-timed text goes
     * through the kinetic letter engine (or the typewriter); line-timed text gets the soft wipe.
     */
    private fun drawActiveRun(
        canvas: Canvas, paint: TextPaint, layout: StaticLayout, text: String, words: List<LyricWord>,
        startMs: Long, frame: Frame, alpha: Float, color: Int,
    ) {
        if (alpha <= 0.01f) return
        if (frame.style.outline) drawOutline(canvas, paint, layout, alpha, frame)
        when {
            words.isEmpty() -> drawFilled(canvas, paint, layout, filledCharacters(text, words, startMs, frame.positionMs), frame, alpha, color)
            frame.style.wordEffect == WordEffect.TYPEWRITER -> drawTypewriter(canvas, paint, layout, words, frame, alpha, color)
            else -> {
                drawKinetic(canvas, paint, layout, words, frame, alpha, color)
                if (frame.style.wordEffect == WordEffect.BALL) drawBall(canvas, ballStops(layout, words), frame.positionMs, frame, alpha, color)
            }
        }
    }

    /** Dim base, then each wrapped line's sung part with a soft gradient front. */
    private fun drawFilled(canvas: Canvas, paint: TextPaint, layout: StaticLayout, filled: Float, frame: Frame, alpha: Float, color: Int) {
        val dim = frame.style.activeDimAlpha
        if (dim > 0f) {
            paint.color = frame.textColor
            paint.alpha = (255 * dim * alpha).toInt()
            layout.draw(canvas)
        }
        val text = layout.text
        for (li in 0 until layout.lineCount) {
            val start = layout.getLineStart(li)
            val end = trimmedLineEnd(layout, li)
            if (end <= start) continue
            if (filled <= start) break
            val x = layout.getLineLeft(li)
            val front = if (filled >= end) Float.NaN else xAt(layout, li, filled)
            drawLitRun(canvas, paint, text, start, end, x, layout.getLineBaseline(li).toFloat(), front, frame, alpha, color, glowBoost = 0f)
        }
    }

    /**
     * Draws [text] `start..end` lit with [color]. [front] is the fill position in the same
     * coordinates (NaN = fully lit): opaque before it, fading to transparent across the feather.
     * The glow is a blurred copy using the same gradient, so it follows the fill.
     */
    private fun drawLitRun(
        canvas: Canvas, paint: TextPaint, text: CharSequence, start: Int, end: Int,
        x: Float, baseline: Float, front: Float, frame: Frame, alpha: Float, color: Int, glowBoost: Float,
        featherPx: Float = frame.style.featherDp * density,
    ) {
        paint.shader = if (front.isNaN()) null else LinearGradient(
            front - featherPx * 0.6f, 0f, front + featherPx * 0.4f, 0f,
            color, ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP,
        )
        paint.color = color
        val glowStrength = if (!frame.theme.glow) 0f else (if (frame.style.glow) 0.55f else 0f) + glowBoost
        if (glowStrength > 0.01f) {
            val mask = paint.maskFilter
            paint.maskFilter = glowFilter((12f + 14f * glowBoost.coerceAtMost(1.5f)) * density)
            paint.alpha = (255 * alpha * glowStrength.coerceAtMost(1f)).toInt()
            canvas.drawText(text, start, end, x, baseline, paint)
            paint.maskFilter = mask
        }
        paint.alpha = (255 * alpha).toInt()
        canvas.drawText(text, start, end, x, baseline, paint)
        paint.shader = null
    }

    // ------------------------------------------------------------------ kinetic letter engine

    private class Syl(val start: Int, val end: Int, val startMs: Long, val endMs: Long, val index: Int) {
        var group = 0
        var pos = 0
    }

    private class Glyph(
        val start: Int, val end: Int, val line: Int, val x0: Float, val x1: Float,
        val sx: Float, val sy: Float, val dy: Float, val rotation: Float, val spacing: Float,
        val fill: Float, val glow: Float,
    )

    /**
     * Letter-by-letter physics shared by every style; [Kinetics] sets how much of each effect a
     * style uses:
     * - **fill:** each letter fills left to right with a soft edge as its share of the syllable is sung;
     * - **wobble + rotation:** a syllable swells and tilts a little on impact, then springs back;
     * - **nudge / lift:** the letter being filled bumps up; fluid styles lift the whole word;
     * - **letter spacing:** the word being sung breathes out a little and relaxes after;
     * - **hold:** sustained notes stretch horizontally, pulse gently and glow, then spring back
     *   to rest after the note ends instead of snapping off;
     * - **crescendo + wave:** multi-syllable words grow with each syllable, ripple, and settle;
     * - scaled and spaced letters push their neighbours, honoring left/center/right alignment.
     */
    private fun drawKinetic(canvas: Canvas, paint: TextPaint, layout: StaticLayout, words: List<LyricWord>, frame: Frame, alpha: Float, color: Int) {
        val k = frame.style.kinetics
        val p = frame.positionMs
        val text = layout.text
        val size = paint.textSize

        val syls = ArrayList<Syl>(words.size)
        var offset = 0
        for (w in words) {
            val s = offset
            val e = (offset + w.text.trimEnd().length).coerceAtMost(text.length)
            offset += w.text.length
            if (e > s) syls += Syl(s, e, w.startMs, w.endMs, syls.size)
        }
        // Group syllables into words: no whitespace between consecutive syllables.
        var group = 0
        var pos = 0
        val groupSize = HashMap<Int, Int>()
        for ((i, s) in syls.withIndex()) {
            s.group = group
            s.pos = pos
            groupSize[group] = pos + 1
            val endsWord = i == syls.lastIndex || s.end >= text.length || text[s.end].isWhitespace() || syls[i + 1].start > s.end
            if (endsWord) { group++; pos = 0 } else pos++
        }
        val groupStart = HashMap<Int, Long>()
        val groupEnd = HashMap<Int, Long>()
        for (s in syls) {
            groupStart[s.group] = min(groupStart[s.group] ?: Long.MAX_VALUE, s.startMs)
            groupEnd[s.group] = max(groupEnd[s.group] ?: Long.MIN_VALUE, s.endMs)
        }

        val glyphs = ArrayList<Glyph>(text.length)
        for (s in syls) {
            val dur = (s.endMs - s.startMs).coerceAtLeast(100)
            val progress = (p - s.startMs).toFloat() / dur
            val sung = p > s.endMs
            val since = (p - s.startMs).toFloat()
            val after = (p - s.endMs).toFloat()

            // Impact: quick swell, slow settle; a damped tilt alternating direction per syllable.
            val wobble = when {
                since < 0f || since > 750f -> 0f
                since < 125f -> since / 125f
                else -> 1f - (since - 125f) / 625f
            }
            val tilt = if (since in 0f..900f) {
                val sign = if (s.index % 2 == 0) 1f else -1f
                sign * k.rotationDeg * exp(-since / 220f) * sin(since / 1000f * 2f * PI.toFloat() * 2.2f)
            } else 0f

            // Sustained note: stretch + pulse while held, then a damped spring back after it ends.
            val holdWeight = ((dur - HOLD_MIN_MS) / 600f).coerceIn(0f, 1f) * k.hold
            var hold = 0f
            var pulse = 0f
            if (holdWeight > 0f) {
                if (!sung && since >= 0f) {
                    hold = smoothstep((progress - 0.15f) / 0.35f)
                    pulse = 0.012f * sin(since / 1000f * 2f * PI.toFloat() * 2.4f) * hold
                } else if (sung && after < 450f) {
                    val t = after / 450f
                    hold = exp(-4f * t) * cos(3f * PI.toFloat() * t) * (1f - t)
                }
                hold *= holdWeight
                pulse *= holdWeight
            }

            // Multi-syllable words: grow per syllable, spring at the last, settle afterwards.
            val gSize = groupSize[s.group] ?: 1
            val gStart = groupStart[s.group] ?: s.startMs
            val gEnd = groupEnd[s.group] ?: s.endMs
            var crescendo = 0f
            if (gSize > 1 && k.crescendo > 0f) {
                val pOut = ((p - gEnd) / 600f).coerceIn(0f, 1f)
                val sp = progress.coerceIn(0f, 1f)
                val isLast = s.pos == gSize - 1
                crescendo = when {
                    pOut > 0f -> (s.pos * 0.012f + 0.06f) * exp(-3.5f * pOut) * cos(5f * pOut * PI.toFloat()) * (1f - pOut)
                    p < gStart -> 0f
                    isLast && sp > 0f -> s.pos * 0.012f + 0.06f * (1f - exp(-3.5f * sp) * cos(5f * sp * PI.toFloat()) * (1f - sp))
                    else -> s.pos * 0.012f + (if (sp > 0f) 0.02f * (1f - sp) else 0f)
                } * k.crescendo
            }
            val waveFade = if (gSize > 1 && k.waveDp > 0f) ((p - gStart) / 200f).coerceIn(0f, 1f) * ((gEnd - p) / 200f).coerceIn(0f, 1f) else 0f

            // Word "breathing" (letter spacing) and lift while the word is being sung.
            val wordIn = smoothstep((p - gStart) / 150f)
            val wordOut = if (p > gEnd) 1f - smoothstep((p - gEnd) / 400f) else 1f
            val wordEnv = wordIn * wordOut

            // Glow for drawn-out syllables (many ms per letter), plus disco-style flare.
            val msPerLetter = dur.toFloat() / (s.end - s.start).coerceAtLeast(1)
            val impact = (((msPerLetter - 100f) / 250f).coerceIn(0f, 1f) * 0.6f + ((dur - 300f) / 1500f).coerceIn(0f, 1f) * 0.4f)
            val held = (progress * 5f).coerceIn(0f, 1f) * ((1f - progress) * 8f).coerceIn(0f, 1f)
            var glow = if (!sung && progress > 0f) impact * held * k.glow else 0f
            glow += hold.coerceAtLeast(0f) * 0.5f * k.glow
            if (frame.theme.flare && since in 0f..260f) glow += (1f - since / 260f).let { it * it }

            val n = s.end - s.start
            for (j in 0 until n) {
                val c0 = s.start + j
                val li = layout.getLineForOffset(c0)
                val x0 = layout.getPrimaryHorizontal(c0)
                val x1 = if (c0 + 1 < layout.getLineEnd(li)) layout.getPrimaryHorizontal(c0 + 1) else layout.getLineRight(li)
                val cp = ((progress - j.toFloat() / n) * n).coerceIn(0f, 1f)
                val nudge = if (!sung && progress > 0f) k.nudge * sin(cp * PI.toFloat()) * exp(-3f * cp) else 0f
                val sx = 1f + wobble * k.wobble + crescendo + nudge * 0.3f + hold * 0.06f + pulse
                val sy = 1f + wobble * k.wobble * 0.6f + crescendo + nudge + pulse
                val wave = if (waveFade > 0.01f) sin(frame.positionMs * 0.006f + c0 * 0.4f) * k.waveDp * density * waveFade else 0f
                val lift = -k.lift * size * wordEnv
                val spacing = (x1 - x0) * k.spacing * wordEnv
                glyphs += Glyph(c0, c0 + 1, li, x0, x1, sx, sy, wave + lift, tilt, spacing, if (sung) 1f else cp, glow)
            }
        }

        // Extra width per wrapped line, so alignment stays put while letters grow.
        val totalPush = FloatArray(layout.lineCount)
        for (g in glyphs) totalPush[g.line] += (g.x1 - g.x0) * (g.sx - 1f) + g.spacing
        val push = FloatArray(layout.lineCount) { li ->
            when (layout.getParagraphAlignment(li)) {
                Layout.Alignment.ALIGN_CENTER -> -totalPush[li] / 2f
                Layout.Alignment.ALIGN_OPPOSITE -> -totalPush[li]
                else -> 0f
            }
        }

        val dim = frame.style.activeDimAlpha
        for (g in glyphs) {
            val baseline = layout.getLineBaseline(g.line).toFloat()
            val w = (g.x1 - g.x0).coerceAtLeast(1f)
            val cx = (g.x0 + g.x1) / 2f
            canvas.save()
            canvas.translate(push[g.line] + g.spacing / 2f, g.dy)
            if (g.rotation != 0f) canvas.rotate(g.rotation, cx, baseline)
            canvas.scale(g.sx, g.sy, cx, baseline)
            if (g.fill < 1f && dim > 0f) {
                paint.color = frame.textColor
                paint.alpha = (255 * dim * alpha).toInt()
                canvas.drawText(text, g.start, g.end, g.x0, baseline, paint)
            }
            if (g.fill > 0f) {
                val front = if (g.fill >= 1f) Float.NaN else g.x0 + w * g.fill
                drawLitRun(canvas, paint, text, g.start, g.end, g.x0, baseline, front, frame, alpha, color, glowBoost = g.glow * 0.6f, featherPx = w * 0.45f)
            }
            canvas.restore()
            push[g.line] += w * (g.sx - 1f) + g.spacing
        }
    }

    /** Letters appear one by one as they're sung, led by a blinking cursor. */
    private fun drawTypewriter(canvas: Canvas, paint: TextPaint, layout: StaticLayout, words: List<LyricWord>, frame: Frame, alpha: Float, color: Int) {
        val p = frame.positionMs
        val text = layout.text
        var offset = 0
        var cursor: Pair<Float, Int>? = null
        for (word in words) {
            val start = offset
            val end = (offset + word.text.trimEnd().length).coerceAtMost(text.length)
            offset += word.text.length
            if (end <= start || p < word.startMs) continue
            val li = layout.getLineForOffset(start)
            val x0 = layout.getPrimaryHorizontal(start)
            val x1 = if (end < layout.getLineEnd(li)) layout.getPrimaryHorizontal(end) else layout.getLineRight(li)
            val baseline = layout.getLineBaseline(li).toFloat()
            val fx = ((p - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            val shown = (end - start) * fx
            val whole = shown.toInt()
            paint.color = color
            paint.alpha = (255 * alpha).toInt()
            if (whole > 0) canvas.drawText(text, start, start + whole, x0, baseline, paint)
            if (start + whole < end) {
                paint.alpha = (255 * alpha * (shown - whole)).toInt()
                canvas.drawText(text, start + whole, start + whole + 1, layout.getPrimaryHorizontal(start + whole), baseline, paint)
            }
            cursor = (if (start + whole < end) layout.getPrimaryHorizontal(start + whole) else x1) to li
        }
        cursor?.let { (cx, li) ->
            if ((p / 450) % 2 == 0L) {
                fillPaint.color = color
                fillPaint.alpha = (220 * alpha).toInt()
                val top = layout.getLineTop(li) + 6f * density
                val bottom = layout.getLineBottom(li) - 4f * density
                tmpRect.set(cx + 2f * density, top, cx + 2f * density + paint.textSize * 0.3f, bottom)
                canvas.drawRoundRect(tmpRect, 2f * density, 2f * density, fillPaint)
            }
        }
    }

    /** Word-top centers and start times, for the bouncing ball. */
    private fun ballStops(layout: StaticLayout, words: List<LyricWord>): List<FloatArray> {
        val stops = ArrayList<FloatArray>(words.size)
        var offset = 0
        for (word in words) {
            val start = offset
            val end = (offset + word.text.trimEnd().length).coerceAtMost(layout.text.length)
            offset += word.text.length
            if (end <= start) continue
            val li = layout.getLineForOffset(start)
            val x0 = layout.getPrimaryHorizontal(start)
            val x1 = if (end < layout.getLineEnd(li)) layout.getPrimaryHorizontal(end) else layout.getLineRight(li)
            stops += floatArrayOf((x0 + x1) / 2f, layout.getLineTop(li).toFloat(), word.startMs.toFloat())
        }
        return stops
    }

    /**
     * Interlude: three dots that fill one after another through the break while the group gently
     * breathes; they grow in at the start and shrink away just before the vocals return.
     */
    private fun drawDots(canvas: Canvas, paint: TextPaint, layout: StaticLayout, line: LyricLine, frame: Frame, alpha: Float, active: Boolean) {
        val radius = paint.textSize * 0.16f
        val gap = radius * 3.3f
        val centerY = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        val left = layout.getLineLeft(0)
        val right = layout.getLineRight(0)
        val groupWidth = gap * 2
        val startX = if (layout.getParagraphAlignment(0) == Layout.Alignment.ALIGN_CENTER) (left + right) / 2f - groupWidth / 2f else radius * 1.2f
        val cx = startX + groupWidth / 2f

        var groupScale = 1f
        var groupAlpha = alpha
        val fills = FloatArray(3)
        if (active) {
            val elapsed = (frame.positionMs - line.startMs).toFloat()
            val total = (line.endMs - line.startMs).toFloat().coerceAtLeast(1f)
            val exitMs = minOf(650f, total * 0.25f)
            val enter = smoothstep(elapsed / 400f)
            val exit = smoothstep((elapsed - (total - exitMs)) / exitMs)
            groupScale = (0.6f + 0.4f * enter) * (1f + 0.06f * sin(elapsed / 2400f * 2f * Math.PI.toFloat())) * (1f - 0.9f * exit)
            groupAlpha = alpha * enter * (1f - exit)
            val fillSpan = (total - exitMs).coerceAtLeast(1f)
            for (i in 0..2) fills[i] = ((elapsed / fillSpan) * 3f - i).coerceIn(0f, 1f)
        }
        if (groupAlpha <= 0.01f) return

        canvas.save()
        canvas.scale(groupScale, groupScale, cx, centerY)
        for (i in 0..2) {
            val f = smoothstep(fills[i])
            val x = startX + gap * i
            val r = radius * (0.8f + 0.25f * f)
            if (f > 0f) {
                fillPaint.color = frame.highlight
                fillPaint.maskFilter = glowFilter(radius * 2f)
                fillPaint.alpha = (140 * f * groupAlpha).toInt()
                canvas.drawCircle(x, centerY, r * 1.6f, fillPaint)
                fillPaint.maskFilter = null
            }
            fillPaint.color = if (f > 0f) frame.highlight else frame.textColor
            fillPaint.alpha = (255 * groupAlpha * (0.3f + 0.7f * f)).toInt()
            canvas.drawCircle(x, centerY, r, fillPaint)
        }
        canvas.restore()
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun trimmedLineEnd(layout: StaticLayout, li: Int): Int {
        var end = layout.getLineEnd(li)
        val start = layout.getLineStart(li)
        while (end > start && layout.text[end - 1].isWhitespace()) end--
        return end
    }

    private fun xAt(layout: StaticLayout, line: Int, filled: Float): Float {
        val lineEnd = trimmedLineEnd(layout, line)
        val offset = floor(filled).toInt().coerceIn(layout.getLineStart(line), lineEnd)
        val fraction = (filled - offset).coerceIn(0f, 1f)
        val x0 = layout.getPrimaryHorizontal(offset)
        val x1 = if (offset + 1 < lineEnd) layout.getPrimaryHorizontal(offset + 1) else layout.getLineRight(line)
        return x0 + (x1 - x0) * fraction
    }

    private fun glowFilter(radius: Float): BlurMaskFilter =
        glowFilters.getOrPut(radius.toInt().coerceAtLeast(1)) { BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }

    private companion object {
        /** Syllables at least this long get the sustained-note hold state. */
        const val HOLD_MIN_MS = 700L
        const val BACKING_SCALE = 0.72f
        const val BACKING_ALPHA = 0.5f
        const val BACKING_ALPHA_ACTIVE = 0.8f
        const val SECONDARY_ALPHA = 0.75f
    }

    /** Classic bouncing ball: arcs from word to word, landing on each one as it starts. */
    private fun drawBall(canvas: Canvas, stops: List<FloatArray>, p: Long, frame: Frame, alpha: Float, color: Int) {
        if (stops.isEmpty()) return
        val next = stops.indexOfFirst { it[2] > p }
        val radius = 6f * density
        val rest = 10f * density
        val (x, y, squash) = when {
            next == 0 -> {
                // Waiting above the first word.
                val s = stops[0]
                Triple(s[0], s[1] - rest - 6f * density, 0f)
            }
            next == -1 -> {
                val s = stops.last()
                Triple(s[0], s[1] - rest, 0f)
            }
            else -> {
                val a = stops[next - 1]
                val b = stops[next]
                val span = (b[2] - a[2]).coerceAtLeast(1f)
                val t = ((p - a[2]) / span).coerceIn(0f, 1f)
                val height = (18f * density + abs(b[0] - a[0]) * 0.12f).coerceAtMost(46f * density)
                val bx = a[0] + (b[0] - a[0]) * t
                val by = a[1] + (b[1] - a[1]) * t - rest - height * 4f * t * (1f - t)
                Triple(bx, by, (1f - t * 8f).coerceAtLeast(0f))
            }
        }
        fillPaint.color = color
        fillPaint.maskFilter = glowFilter(8f * density)
        fillPaint.alpha = (150 * alpha).toInt()
        canvas.drawCircle(x, y, radius * 1.4f, fillPaint)
        fillPaint.maskFilter = null
        fillPaint.alpha = (255 * alpha).toInt()
        // Squash on landing.
        tmpRect.set(x - radius * (1f + 0.3f * squash), y - radius * (1f - 0.3f * squash), x + radius * (1f + 0.3f * squash), y + radius * (1f - 0.3f * squash))
        canvas.drawOval(tmpRect, fillPaint)
    }

    // ------------------------------------------------------------------ chrome

    /** Parked full-screen: cover, title and artist slide in on the left. */
    private fun drawPanel(canvas: Canvas, frame: Frame, e: Float) {
        val area = frame.area
        val panelW = panelWidth(area)
        val pad = 28f * density
        val shift = -panelW * 0.25f * (1f - e)
        val size = min(panelW - 2 * pad, area.height() * 0.58f)
        val left = area.left + pad + shift
        val top = area.exactCenterY() - (size + 70f * density) / 2f

        frame.artwork?.takeUnless { it.isRecycled }?.let { art ->
            tmpRect.set(left, top, left + size, top + size)
            canvas.save()
            path.reset()
            path.addRoundRect(tmpRect, 18f * density, 18f * density, Path.Direction.CW)
            canvas.clipPath(path)
            bitmapPaint.alpha = (255 * e).toInt()
            canvas.drawBitmap(art, null, tmpRect, bitmapPaint)
            canvas.restore()
        }
        messagePaint.textAlign = Paint.Align.LEFT
        messagePaint.textSize = 22f * density
        messagePaint.alpha = (255 * e * frame.textAlpha).toInt()
        frame.title?.let { canvas.drawText(ellipsize(it, messagePaint, size), left, top + size + 34f * density, messagePaint) }
        messagePaint.textSize = 17f * density
        messagePaint.alpha = (180 * e * frame.textAlpha).toInt()
        frame.artist?.let { canvas.drawText(ellipsize(it, messagePaint, size), left, top + size + 60f * density, messagePaint) }
        messagePaint.textAlign = Paint.Align.CENTER
    }

    private fun drawMessage(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float) {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val width = rect.width() - 2 * sidePadding
        messagePaint.typeface = frame.theme.typeface
        messagePaint.textSize = 34f * density
        messagePaint.alpha = (255 * alpha * frame.textAlpha).toInt()
        frame.message?.let { canvas.drawText(ellipsize(it, messagePaint, width), cx, cy, messagePaint) }
        frame.subMessage?.let {
            messagePaint.alpha = (170 * alpha * frame.textAlpha).toInt()
            messagePaint.textSize = 19f * density
            canvas.drawText(ellipsize(it, messagePaint, width), cx, cy + 36f * density, messagePaint)
        }
        messagePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawResumePill(canvas: Canvas, frame: Frame, remaining: Float) {
        val text = "Resume auto-scroll"
        val textWidth = labelPaint.measureText(text)
        val w = textWidth + 48f * density
        val h = 48f * density
        val cx = frame.area.exactCenterX()
        val bottom = frame.area.bottom - 20f * density
        resumeButton.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        fillPaint.color = ColorUtils.setAlphaComponent(Color.BLACK, 160)
        canvas.drawRoundRect(resumeButton, h / 2, h / 2, fillPaint)
        fillPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 200)
        val inset = h / 2
        canvas.drawRect(resumeButton.left + inset, resumeButton.bottom - 4f * density, resumeButton.left + inset + (w - 2 * inset) * remaining, resumeButton.bottom - 2f * density, fillPaint)
        labelPaint.alpha = 255
        canvas.drawText(text, cx - textWidth / 2, resumeButton.centerY() + labelPaint.textSize / 3, labelPaint)
    }

    private fun blurFilter(level: Int, perStepDp: Float): BlurMaskFilter? {
        val radius = level * perStepDp * density
        if (radius < 0.5f) return null
        return blurFilters.getOrPut((radius * 4).toInt()) { BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Number): String =
        TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()
}
