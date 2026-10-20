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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws lyrics for every [LayoutKind] onto a plain [Canvas]; used by both the car surface and the
 * phone. Holds only caches; all state arrives in [Frame].
 */
class LyricsPainter(private val density: Float) {

    data class Frame(
        /** Region available for lyrics (excludes phone chrome / car action strip). */
        val area: Rect,
        val lines: List<LyricLine>,
        val synced: Boolean,
        val activeIndex: Int,
        val positionMs: Long,
        val filledCharacters: Float,
        val lineProgress: Float,
        val scrollY: Float,
        /** Driving: list layouts show only previous / current / next. */
        val compact: Boolean,
        val style: LyricStyleSpec,
        val theme: ThemeSpec,
        val highlight: Int,
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
    private val path = Path()
    private val tmpRect = RectF()

    // ------------------------------------------------------------------ layout cache

    /** Wrapped layouts for one (lines, width, size, typeface, alignment) combination. */
    class LayoutSet(val paint: TextPaint, val layouts: List<StaticLayout>, val rowTops: FloatArray, val rowHeights: FloatArray)

    private data class LayoutKey(val lines: List<LyricLine>, val width: Int, val size: Float, val typeface: Typeface, val center: Boolean)

    private val layoutCache = object : LinkedHashMap<LayoutKey, LayoutSet>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, LayoutSet>?) = size > 6
    }

    private fun layoutSet(lines: List<LyricLine>, width: Int, size: Float, typeface: Typeface, center: Boolean): LayoutSet {
        val key = LayoutKey(lines, width.coerceAtLeast(1), size, typeface, center)
        layoutCache[key]?.let { return it }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            this.typeface = typeface
        }
        val layouts = lines.map { line ->
            val text = if (line.isInterlude) "• • •" else line.text
            StaticLayout.Builder.obtain(text, 0, text.length, paint, key.width)
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()
        }
        val heights = FloatArray(layouts.size) { max(minRowHeight, layouts[it].height + 2 * rowPadding) }
        val tops = FloatArray(layouts.size)
        var y = 0f
        for (i in layouts.indices) {
            tops[i] = y
            y += heights[i]
        }
        return LayoutSet(paint, layouts, tops, heights).also { layoutCache[key] = it }
    }

    /** Where lyrics go: full area, or right of the cover panel when expanded. */
    fun contentRect(area: Rect, expanded: Boolean): Rect =
        if (!expanded) area else Rect(area.left + panelWidth(area).toInt(), area.top, area.right, area.bottom)

    private fun panelWidth(area: Rect) = area.width() * 0.36f

    private fun listSet(lines: List<LyricLine>, rect: Rect, frameTextSize: Float, typeface: Typeface) =
        layoutSet(lines, (rect.width() - 2 * sidePadding).toInt(), frameTextSize, typeface, center = false)

    /** Scroll offset that centers line [index] in [rect] (list layouts). */
    fun scrollTargetFor(lines: List<LyricLine>, index: Int, rect: Rect, textSize: Float, typeface: Typeface): Float {
        if (lines.isEmpty()) return 0f
        val set = listSet(lines, rect, textSize, typeface)
        val i = index.coerceIn(0, set.rowTops.lastIndex)
        return set.rowTops[i] + set.rowHeights[i] / 2f - rect.height() / 2f
    }

    fun scrollRange(lines: List<LyricLine>, rect: Rect, textSize: Float, typeface: Typeface): ClosedFloatingPointRange<Float> {
        val first = scrollTargetFor(lines, 0, rect, textSize, typeface)
        val last = scrollTargetFor(lines, lines.lastIndex, rect, textSize, typeface)
        return first..max(first, last)
    }

    // ------------------------------------------------------------------ frame

    fun draw(canvas: Canvas, frame: Frame) {
        hitRows.clear()
        resumeButton.setEmpty()
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
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (70 * (1f - frame.tapProgress)).toInt()
            canvas.drawRoundRect(r, 16f * density, 16f * density, fillPaint)
        }
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
        val scroll = scrollY ?: scrollTargetFor(frame.lines, active.coerceAtLeast(0), rect, frame.textSize, frame.theme.typeface)
        val left = rect.left + sidePadding

        canvas.save()
        canvas.clipRect(rect)
        for (i in set.layouts.indices) {
            val distance = if (active < 0) i + 1 else i - active
            if (frame.compact && frame.synced && abs(distance) > 1) continue
            val top = rect.top + set.rowTops[i] - scroll
            val bottom = top + set.rowHeights[i]
            if (bottom < rect.top || top > rect.bottom) continue

            val isActive = distance == 0 && frame.synced
            val scale = if (isActive) style.activeScale else if (frame.synced) style.inactiveScale else 1f
            val lineAlpha = alpha * frame.textAlpha * when {
                !frame.synced -> 0.85f
                isActive -> 1f
                abs(distance) == 1 -> style.nearAlpha
                else -> style.farAlpha
            }
            val blurLevel = if (isActive || frame.compact || style.blurPerStepDp == 0f || !frame.synced) 0 else min(abs(distance), 3)
            val layout = set.layouts[i]
            val textTop = top + (set.rowHeights[i] - layout.height) / 2f

            canvas.save()
            canvas.scale(scale, scale, left, top + set.rowHeights[i] / 2f)
            canvas.translate(left, textTop)
            set.paint.maskFilter = if (blurLevel == 0) null else blurFilter(blurLevel, style.blurPerStepDp)
            if (isActive) {
                drawActiveLine(canvas, set.paint, layout, frame.lines[i], frame, lineAlpha)
            } else {
                drawPlain(canvas, set.paint, layout, lineAlpha, frame)
            }
            set.paint.maskFilter = null
            canvas.restore()
            hitRows += i to RectF(rect.left.toFloat(), top, rect.right.toFloat(), bottom)
        }
        canvas.restore()
    }

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
        val drift = 40f * density

        fun height(i: Int) = set.layouts.getOrNull(i)?.height?.toFloat() ?: 0f

        // Outgoing line.
        if (active > 0 && t < 1f) {
            set.paint.maskFilter = blurFilter(2, 1.5f * t)
            drawAt(canvas, left, activeY - drift * t) { drawPlain(canvas, set.paint, set.layouts[active - 1], alpha * frame.textAlpha * (1f - t), frame) }
            set.paint.maskFilter = null
        }
        if (active >= 0) {
            val fromY = activeY + height(active - 1) + gap
            val y = if (active > 0) fromY + (activeY - fromY) * t else activeY
            drawAt(canvas, left, y) { drawActiveLine(canvas, set.paint, set.layouts[active], frame.lines[active], frame, alpha * frame.textAlpha) }
            hitRows += active to RectF(rect.left.toFloat(), y, rect.right.toFloat(), y + height(active))
        }
        val next = active + 1
        if (next < set.layouts.size) {
            val y = activeY + height(active.coerceAtLeast(0)) + gap
            drawAt(canvas, left, y) { drawPlain(canvas, set.paint, set.layouts[next], alpha * frame.textAlpha * 0.5f * t, frame) }
            hitRows += next to RectF(rect.left.toFloat(), y, rect.right.toFloat(), y + height(next))
        }
    }

    /** Big centered current line, small grey next line, near-instant crossfade. */
    private fun drawGlance(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float) {
        val big = focusedSet(frame, rect, 1.2f)
        val small = focusedSet(frame, rect, 0.6f)
        val active = frame.activeIndex.coerceAtLeast(0)
        val t = transition(frame)
        val left = rect.left + sidePadding
        val activeH = big.layouts[active].height.toFloat()
        val nextH = small.layouts.getOrNull(active + 1)?.height?.toFloat() ?: 0f
        val gap = 14f * density
        val top = rect.exactCenterY() - (activeH + gap + nextH) / 2f

        big.paint.setShadowLayer(4f * density, 0f, 2f * density, ColorUtils.setAlphaComponent(Color.BLACK, 170))
        drawAt(canvas, left, top) {
            if (frame.activeIndex >= 0) drawActiveLine(canvas, big.paint, big.layouts[active], frame.lines[active], frame, alpha * frame.textAlpha * t)
            else drawPlain(canvas, big.paint, big.layouts[active], alpha * frame.textAlpha * 0.6f, frame)
        }
        big.paint.clearShadowLayer()
        hitRows += active to RectF(rect.left.toFloat(), top, rect.right.toFloat(), top + activeH)
        small.layouts.getOrNull(active + 1)?.let { layout ->
            val y = top + activeH + gap
            small.paint.color = 0xFF9AA0A6.toInt()
            drawAt(canvas, left, y) { drawPlain(canvas, small.paint, layout, alpha * frame.textAlpha * t, frame, keepColor = true) }
            small.paint.color = Color.WHITE
            hitRows += (active + 1) to RectF(rect.left.toFloat(), y, rect.right.toFloat(), y + layout.height)
        }
    }

    /** Only the current line, centered, with a thin progress bar. */
    private fun drawSingleLine(canvas: Canvas, frame: Frame, rect: Rect, alpha: Float) {
        val set = focusedSet(frame, rect, 1.1f)
        val active = frame.activeIndex
        val t = transition(frame)
        val left = rect.left + sidePadding
        val index = active.coerceAtLeast(0)
        val layout = set.layouts[index]
        val barGap = 18f * density
        val top = rect.exactCenterY() - (layout.height + barGap) / 2f + (1f - t) * 12f * density
        drawAt(canvas, left, top) {
            if (active >= 0) drawActiveLine(canvas, set.paint, layout, frame.lines[index], frame, alpha * frame.textAlpha * t)
            else drawPlain(canvas, set.paint, layout, alpha * frame.textAlpha * 0.6f, frame)
        }
        hitRows += index to RectF(rect.left.toFloat(), top, rect.right.toFloat(), top + layout.height)

        val line = frame.lines[index]
        val progress = if (active < 0) 0f else if (line.hasWordTiming && line.text.isNotEmpty()) frame.filledCharacters / line.text.length else frame.lineProgress
        val barW = rect.width() * 0.4f
        val barX = rect.exactCenterX() - barW / 2f
        val barY = top + layout.height + barGap
        val barH = 3f * density
        fillPaint.color = Color.WHITE
        fillPaint.alpha = (60 * alpha).toInt()
        tmpRect.set(barX, barY, barX + barW, barY + barH)
        canvas.drawRoundRect(tmpRect, barH, barH, fillPaint)
        fillPaint.color = frame.highlight
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

    private fun drawPlain(canvas: Canvas, paint: TextPaint, layout: StaticLayout, alpha: Float, frame: Frame, keepColor: Boolean = false) {
        if (alpha <= 0.01f) return
        if (frame.style.outline) drawOutline(canvas, paint, layout, alpha)
        if (!keepColor) paint.color = Color.WHITE
        paint.alpha = (255 * alpha).toInt()
        layout.draw(canvas)
    }

    private fun drawOutline(canvas: Canvas, paint: TextPaint, layout: StaticLayout, alpha: Float) {
        val mask = paint.maskFilter
        paint.maskFilter = null
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 3.5f * density
        paint.color = Color.BLACK
        paint.alpha = (200 * alpha).toInt()
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.maskFilter = mask
    }

    private fun drawActiveLine(canvas: Canvas, paint: TextPaint, layout: StaticLayout, line: LyricLine, frame: Frame, alpha: Float) {
        if (alpha <= 0.01f) return
        if (line.isInterlude) {
            // Dots breathe through the instrumental break.
            val pulse = sin(frame.lineProgress * Math.PI.toFloat() * 6f).let { it * it }
            paint.color = Color.WHITE
            paint.alpha = ((140 + 115 * pulse) * alpha).toInt()
            layout.draw(canvas)
            return
        }
        val effect = when {
            frame.style.wordEffect != WordEffect.NONE -> frame.style.wordEffect
            frame.theme.flare -> WordEffect.FLARE
            else -> WordEffect.NONE
        }
        if (frame.style.outline) drawOutline(canvas, paint, layout, alpha)
        if (effect != WordEffect.NONE && line.hasWordTiming) {
            drawWords(canvas, paint, layout, line, frame, alpha, effect)
        } else {
            drawFilled(canvas, paint, layout, frame, alpha)
        }
    }

    /** Dim base, then each wrapped line's sung part with a soft gradient front. */
    private fun drawFilled(canvas: Canvas, paint: TextPaint, layout: StaticLayout, frame: Frame, alpha: Float) {
        val dim = frame.style.activeDimAlpha
        if (dim > 0f) {
            paint.color = Color.WHITE
            paint.alpha = (255 * dim * alpha).toInt()
            layout.draw(canvas)
        }
        val filled = frame.filledCharacters
        val text = layout.text
        for (li in 0 until layout.lineCount) {
            val start = layout.getLineStart(li)
            val end = trimmedLineEnd(layout, li)
            if (end <= start) continue
            if (filled <= start) break
            val x = layout.getLineLeft(li)
            val front = if (filled >= end) Float.NaN else xAt(layout, li, filled)
            drawLitRun(canvas, paint, text, start, end, x, layout.getLineBaseline(li).toFloat(), front, frame, alpha, glowBoost = 0f)
        }
    }

    /**
     * Draws [text] `start..end` lit with the highlight color. [front] is the fill position in the
     * same coordinates (NaN = fully lit): opaque before it, fading to transparent across the
     * feather. The glow is a blurred copy using the same gradient, so it follows the fill.
     */
    private fun drawLitRun(
        canvas: Canvas, paint: TextPaint, text: CharSequence, start: Int, end: Int,
        x: Float, baseline: Float, front: Float, frame: Frame, alpha: Float, glowBoost: Float,
        featherDp: Float = frame.style.featherDp,
    ) {
        val color = frame.highlight
        val feather = featherDp * density
        paint.shader = if (front.isNaN()) null else LinearGradient(
            front - feather * 0.6f, 0f, front + feather * 0.4f, 0f,
            color, ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP,
        )
        paint.color = color
        val glowStrength = (if (frame.style.glow) 0.55f else 0f) + glowBoost
        if (glowStrength > 0.01f) {
            val mask = paint.maskFilter
            paint.maskFilter = glowFilter((12f + 14f * glowBoost) * density)
            paint.alpha = (255 * alpha * glowStrength.coerceAtMost(1f)).toInt()
            canvas.drawText(text, start, end, x, baseline, paint)
            paint.maskFilter = mask
        }
        paint.alpha = (255 * alpha).toInt()
        canvas.drawText(text, start, end, x, baseline, paint)
        paint.shader = null
        paint.color = Color.WHITE
    }

    /** Per-word drawing: each word gets its own lift / scale / reveal, still with soft fronts. */
    private fun drawWords(canvas: Canvas, paint: TextPaint, layout: StaticLayout, line: LyricLine, frame: Frame, alpha: Float, effect: WordEffect) {
        val p = frame.positionMs
        val text = layout.text
        val dim = frame.style.activeDimAlpha
        var offset = 0
        var cursor: Pair<Float, Int>? = null
        // For the bouncing ball: centers of word tops in layout coordinates.
        val ballStops = if (effect == WordEffect.BALL) ArrayList<FloatArray>(line.words.size) else null

        for (word in line.words) {
            val start = offset
            val end = (offset + word.text.trimEnd().length).coerceAtMost(text.length)
            offset += word.text.length
            if (end <= start) continue
            val li = layout.getLineForOffset(start)
            val x0 = layout.getPrimaryHorizontal(start)
            val x1 = if (end < layout.getLineEnd(li)) layout.getPrimaryHorizontal(end) else layout.getLineRight(li)
            val baseline = layout.getLineBaseline(li).toFloat()
            ballStops?.add(floatArrayOf((x0 + x1) / 2f, layout.getLineTop(li).toFloat(), word.startMs.toFloat()))

            val started = p >= word.startMs
            val duration = (word.endMs - word.startMs).coerceAtLeast(1)
            val fx = if (!started) 0f else ((p - word.startMs).toFloat() / duration).coerceIn(0f, 1f)

            var dy = 0f
            var scale = 1f
            var glowBoost = 0f
            when (effect) {
                WordEffect.LIFT -> {
                    dy = -3f * density * WordEffects.lift(p, word)
                    // Held notes (long words) swell and glow while they're sustained.
                    val held = WordEffects.held(p, word)
                    scale = 1f + 0.05f * held
                    glowBoost = 0.6f * held
                }
                WordEffect.FLARE -> {
                    val flare = WordEffects.flare(p, word)
                    scale = 1f + 0.06f * flare
                    glowBoost = flare
                }
                else -> Unit
            }

            canvas.save()
            canvas.translate(0f, dy)
            if (scale != 1f) canvas.scale(scale, scale, (x0 + x1) / 2f, baseline - paint.textSize * 0.35f)

            if (effect == WordEffect.TYPEWRITER) {
                // Letters appear one by one; the next letter fades in.
                if (started) {
                    val shown = (end - start) * fx
                    val whole = shown.toInt()
                    paint.color = frame.highlight
                    paint.alpha = (255 * alpha).toInt()
                    if (whole > 0) canvas.drawText(text, start, start + whole, x0, baseline, paint)
                    if (start + whole < end) {
                        paint.alpha = (255 * alpha * (shown - whole)).toInt()
                        canvas.drawText(text, start + whole, start + whole + 1, layout.getPrimaryHorizontal(start + whole), baseline, paint)
                    }
                    val cx = if (start + whole < end) layout.getPrimaryHorizontal(start + whole) else x1
                    cursor = cx to li
                    paint.color = Color.WHITE
                }
            } else {
                if (dim > 0f && fx < 1f) {
                    paint.color = Color.WHITE
                    paint.alpha = (255 * dim * alpha).toInt()
                    canvas.drawText(text, start, end, x0, baseline, paint)
                }
                if (fx > 0f) {
                    val front = if (fx >= 1f) Float.NaN else x0 + (x1 - x0) * fx
                    drawLitRun(canvas, paint, text, start, end, x0, baseline, front, frame, alpha, glowBoost, featherDp = minOf(frame.style.featherDp, 12f))
                }
            }
            canvas.restore()
        }

        cursor?.let { (cx, li) ->
            if ((p / 450) % 2 == 0L || frame.positionMs - (line.words.lastOrNull { it.startMs <= p }?.startMs ?: 0) < 450) {
                fillPaint.color = frame.highlight
                fillPaint.alpha = (220 * alpha).toInt()
                val top = layout.getLineTop(li) + 6f * density
                val bottom = layout.getLineBottom(li) - 4f * density
                tmpRect.set(cx + 2f * density, top, cx + 2f * density + paint.textSize * 0.3f, bottom)
                canvas.drawRoundRect(tmpRect, 2f * density, 2f * density, fillPaint)
            }
        }
        if (ballStops != null && ballStops.isNotEmpty()) drawBall(canvas, ballStops, p, frame, alpha)
        paint.color = Color.WHITE
    }

    /** Classic bouncing ball: arcs from word to word, landing on each one as it starts. */
    private fun drawBall(canvas: Canvas, stops: List<FloatArray>, p: Long, frame: Frame, alpha: Float) {
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
        fillPaint.color = frame.highlight
        fillPaint.maskFilter = glowFilter(8f * density)
        fillPaint.alpha = (150 * alpha).toInt()
        canvas.drawCircle(x, y, radius * 1.4f, fillPaint)
        fillPaint.maskFilter = null
        fillPaint.alpha = (255 * alpha).toInt()
        // Squash on landing.
        tmpRect.set(x - radius * (1f + 0.3f * squash), y - radius * (1f - 0.3f * squash), x + radius * (1f + 0.3f * squash), y + radius * (1f - 0.3f * squash))
        canvas.drawOval(tmpRect, fillPaint)
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

    private val glowFilters = HashMap<Int, BlurMaskFilter>()

    private fun glowFilter(radius: Float): BlurMaskFilter =
        glowFilters.getOrPut(radius.toInt().coerceAtLeast(1)) { BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }

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
