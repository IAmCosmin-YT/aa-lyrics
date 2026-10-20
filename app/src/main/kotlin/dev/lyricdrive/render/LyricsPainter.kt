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

        val activeTop = rect.top + (set.rowTops.getOrNull(active.coerceAtLeast(0)) ?: 0f) - scroll
        val activeBottom = activeTop + (set.rowHeights.getOrNull(active.coerceAtLeast(0)) ?: 0f)
        if (style.spotlight && active >= 0) drawSpotlightCone(canvas, rect, activeTop, activeBottom, alpha)

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
                drawPlain(canvas, set.paint, layout, lineAlpha, frame.theme.chromatic && distance == -1, frame)
            }
            set.paint.maskFilter = null
            canvas.restore()
            hitRows += i to RectF(rect.left.toFloat(), top, rect.right.toFloat(), bottom)
        }
        canvas.restore()

        if (style.spotlight && active >= 0) drawSpotlightShadow(canvas, rect, activeTop, activeBottom, alpha)
    }

    private fun drawSpotlightCone(canvas: Canvas, rect: Rect, top: Float, bottom: Float, alpha: Float) {
        val cx = rect.exactCenterX()
        val narrow = rect.width() * 0.08f
        path.reset()
        path.moveTo(cx - narrow, rect.top.toFloat())
        path.lineTo(cx + narrow, rect.top.toFloat())
        path.lineTo(rect.right.toFloat(), bottom)
        path.lineTo(rect.left.toFloat(), bottom)
        path.close()
        fillPaint.shader = LinearGradient(0f, rect.top.toFloat(), 0f, bottom, 0x00FFFFFF, ColorUtils.setAlphaComponent(Color.WHITE, (36 * alpha).toInt()), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null
    }

    private fun drawSpotlightShadow(canvas: Canvas, rect: Rect, top: Float, bottom: Float, alpha: Float) {
        val shade = ColorUtils.setAlphaComponent(Color.BLACK, (150 * alpha).toInt())
        if (top > rect.top) {
            fillPaint.shader = LinearGradient(0f, rect.top.toFloat(), 0f, top, shade, 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), top, fillPaint)
        }
        if (bottom < rect.bottom) {
            fillPaint.shader = LinearGradient(0f, bottom, 0f, rect.bottom.toFloat(), 0x00000000, shade, Shader.TileMode.CLAMP)
            canvas.drawRect(rect.left.toFloat(), bottom, rect.right.toFloat(), rect.bottom.toFloat(), fillPaint)
        }
        fillPaint.shader = null
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
            drawAt(canvas, left, activeY - drift * t) { drawPlain(canvas, set.paint, set.layouts[active - 1], alpha * frame.textAlpha * (1f - t), frame.theme.chromatic, frame) }
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
            drawAt(canvas, left, y) { drawPlain(canvas, set.paint, set.layouts[next], alpha * frame.textAlpha * 0.5f * t, false, frame) }
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
            else drawPlain(canvas, big.paint, big.layouts[active], alpha * frame.textAlpha * 0.6f, false, frame)
        }
        big.paint.clearShadowLayer()
        hitRows += active to RectF(rect.left.toFloat(), top, rect.right.toFloat(), top + activeH)
        small.layouts.getOrNull(active + 1)?.let { layout ->
            val y = top + activeH + gap
            small.paint.color = 0xFF9AA0A6.toInt()
            drawAt(canvas, left, y) { drawPlain(canvas, small.paint, layout, alpha * frame.textAlpha * t, false, frame, keepColor = true) }
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
            else drawPlain(canvas, set.paint, layout, alpha * frame.textAlpha * 0.6f, false, frame)
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

    private fun drawPlain(canvas: Canvas, paint: TextPaint, layout: StaticLayout, alpha: Float, chromatic: Boolean, frame: Frame, keepColor: Boolean = false) {
        if (alpha <= 0.01f) return
        if (chromatic) drawChromatic(canvas, paint, layout, alpha * 0.5f, null)
        if (!keepColor) paint.color = Color.WHITE
        paint.alpha = (255 * alpha).toInt()
        layout.draw(canvas)
    }

    /** RGB split (synthwave): red and cyan ghosts offset either side of the text. */
    private fun drawChromatic(canvas: Canvas, paint: TextPaint, layout: StaticLayout, alpha: Float, clipRight: ((Int) -> Float)?) {
        val offset = 1.6f * density
        for ((color, dx) in listOf(0xFFFF2E5E.toInt() to -offset, 0xFF2EE6FF.toInt() to offset)) {
            paint.color = color
            paint.alpha = (110 * alpha).toInt()
            canvas.save()
            canvas.translate(dx, 0f)
            if (clipRight == null) {
                layout.draw(canvas)
            } else {
                for (line in 0 until layout.lineCount) {
                    val right = clipRight(line)
                    if (right <= 0f) break
                    canvas.save()
                    canvas.clipRect(0f, layout.getLineTop(line).toFloat(), right, layout.getLineBottom(line).toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
            canvas.restore()
        }
        paint.color = Color.WHITE
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
        if (effect != WordEffect.NONE && line.hasWordTiming) {
            drawWords(canvas, paint, layout, line, frame, alpha, effect)
        } else {
            drawFilled(canvas, paint, layout, frame, alpha)
        }
    }

    /** Dim base text, then the same text bright, clipped to the sung portion across wrapped lines. */
    private fun drawFilled(canvas: Canvas, paint: TextPaint, layout: StaticLayout, frame: Frame, alpha: Float) {
        val filled = frame.filledCharacters
        val length = layout.text.length
        val clipRight = { line: Int ->
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            when {
                filled <= start -> 0f
                filled >= end -> layout.width.toFloat()
                else -> xAt(layout, line, filled, length)
            }
        }
        if (frame.style.activeDimAlpha > 0f) {
            paint.color = Color.WHITE
            paint.alpha = (255 * frame.style.activeDimAlpha * alpha).toInt()
            layout.draw(canvas)
        }
        if (frame.theme.chromatic) drawChromatic(canvas, paint, layout, alpha, clipRight)

        paint.color = frame.highlight
        paint.alpha = (255 * alpha).toInt()
        if (frame.style.glow) paint.setShadowLayer(14f * density, 0f, 0f, ColorUtils.setAlphaComponent(frame.highlight, 150))
        for (line in 0 until layout.lineCount) {
            val right = clipRight(line)
            if (right <= 0f) break
            canvas.save()
            canvas.clipRect(-sidePadding, layout.getLineTop(line).toFloat() - 8 * density, right, layout.getLineBottom(line).toFloat() + 8 * density)
            layout.draw(canvas)
            canvas.restore()
        }
        paint.clearShadowLayer()
        paint.color = Color.WHITE
    }

    /** Per-word drawing for wave / typewriter / flare: each word gets its own transform. */
    private fun drawWords(canvas: Canvas, paint: TextPaint, layout: StaticLayout, line: LyricLine, frame: Frame, alpha: Float, effect: WordEffect) {
        val p = frame.positionMs
        val padX = 3f * density
        val padY = 10f * density
        var offset = 0
        var cursorX = Float.NaN
        var cursorLine = 0

        for (word in line.words) {
            val start = offset
            val end = (offset + word.text.trimEnd().length).coerceAtMost(layout.text.length)
            offset += word.text.length
            if (end <= start) continue
            val li = layout.getLineForOffset(start)
            val x0 = layout.getPrimaryHorizontal(start)
            val x1 = if (end < layout.getLineEnd(li)) layout.getPrimaryHorizontal(end) else layout.getLineRight(li)
            val top = layout.getLineTop(li).toFloat()
            val bottom = layout.getLineBottom(li).toFloat()

            val started = p >= word.startMs
            val fx = when {
                effect == WordEffect.TYPEWRITER -> if (started) 1f else 0f
                !started -> 0f
                p >= word.endMs -> 1f
                else -> (p - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
            }
            val dy = when (effect) {
                WordEffect.WAVE -> -4f * density * WordEffects.lift(p, word)
                else -> 0f
            }
            val scale = when (effect) {
                WordEffect.TYPEWRITER -> 1f + 0.12f * WordEffects.pop(p, word)
                WordEffect.FLARE -> 1f + 0.06f * WordEffects.flare(p, word)
                else -> 1f
            }
            val cx = (x0 + x1) / 2f
            val cy = (top + bottom) / 2f

            // Dim layer.
            val dim = frame.style.activeDimAlpha
            if (dim > 0f && fx < 1f) {
                drawWordPass(canvas, layout, x0 - padX, top - padY, x1 + padX, bottom + padY, dy, scale, cx, cy) {
                    paint.color = Color.WHITE
                    paint.alpha = (255 * dim * alpha).toInt()
                }
            }
            // Lit layer.
            if (fx > 0f) {
                val right = if (fx >= 1f) x1 + padX else x0 + (x1 - x0) * fx
                val flare = if (effect == WordEffect.FLARE) WordEffects.flare(p, word) else 0f
                drawWordPass(canvas, layout, x0 - padX, top - padY, right, bottom + padY, dy, scale, cx, cy) {
                    paint.color = frame.highlight
                    paint.alpha = (255 * alpha).toInt()
                    if (frame.style.glow || flare > 0f) {
                        paint.setShadowLayer((14f + 18f * flare) * density, 0f, 0f, ColorUtils.setAlphaComponent(frame.highlight, (150 + 100 * flare).toInt()))
                    }
                }
                paint.clearShadowLayer()
                if (started) {
                    cursorX = right
                    cursorLine = li
                }
            }
        }

        if (effect == WordEffect.TYPEWRITER && !cursorX.isNaN() && (p / 500) % 2 == 0L) {
            val top = layout.getLineTop(cursorLine) + 4f * density
            val bottom = layout.getLineBottom(cursorLine) - 2f * density
            fillPaint.color = frame.highlight
            fillPaint.alpha = (220 * alpha).toInt()
            canvas.drawRect(cursorX + 3f * density, top, cursorX + 3f * density + paint.textSize * 0.32f, bottom, fillPaint)
        }
        paint.color = Color.WHITE
    }

    private inline fun drawWordPass(
        canvas: Canvas, layout: StaticLayout,
        l: Float, t: Float, r: Float, b: Float,
        dy: Float, scale: Float, cx: Float, cy: Float,
        setup: () -> Unit,
    ) {
        setup()
        canvas.save()
        canvas.translate(0f, dy)
        if (scale != 1f) canvas.scale(scale, scale, cx, cy)
        canvas.clipRect(l, t, r, b)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun xAt(layout: StaticLayout, line: Int, filled: Float, length: Int): Float {
        val offset = floor(filled).toInt().coerceIn(0, length)
        val fraction = filled - offset
        val x0 = layout.getPrimaryHorizontal(offset)
        val x1 = if (offset + 1 < layout.getLineEnd(line)) layout.getPrimaryHorizontal(offset + 1) else layout.getLineRight(line)
        return x0 + (x1 - x0) * fraction
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
