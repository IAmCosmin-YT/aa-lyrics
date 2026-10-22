package dev.lyricdrive.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.lyricdrive.R
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.data.sensors.AmbientLevel
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.CarLayout
import dev.lyricdrive.data.settings.LyricSettings
import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns a [SceneSource] + settings into frames, and owns everything stateful about the lyrics
 * view: spring auto-follow, manual scroll/fling with a 4 s resume hold, tap-to-seek, beat pulses,
 * and the parked full-screen expansion. Used unchanged by the car surface and the phone.
 *
 * Must be used on the main thread (DynamicAnimation requirement).
 */
class LyricsController(
    private val context: Context,
    private val source: SceneSource,
    private val settings: () -> LyricSettings,
    private val density: Float,
    /** Car is moving: no manual browsing, toned-down party effects, compact layout if chosen. */
    private val isMoving: () -> Boolean = { false },
    /** Car night signal or phone dark theme. */
    private val nightSignal: () -> Boolean = { false },
    private val ambientSensor: () -> AmbientLevel? = { null },
    /** Only the car has a cover panel to expand into. */
    private val supportsFullScreen: Boolean = false,
    /** Car draws a small title line; the phone shows its own header. */
    private val drawHeader: Boolean = false,
    /** Scales lyric text (smaller for the settings preview). */
    private val textScale: Float = 1f,
    private val onFollowChanged: () -> Unit = {},
) {
    private val background = BackgroundPainter(density)
    private val painter = LyricsPainter(density)

    private val scroll = FloatValueHolder(0f)
    private val spring = SpringAnimation(scroll).setSpring(SpringForce().setDampingRatio(0.82f).setStiffness(SpringForce.STIFFNESS_LOW))
    private var fling: FlingAnimation? = null
    private var springTarget = Float.NaN
    private var appliedStyle: AnimationStyle? = null

    private val expansionHolder = FloatValueHolder(0f)
    private val expansionSpring = SpringAnimation(expansionHolder).setSpring(SpringForce().setDampingRatio(1f).setStiffness(SpringForce.STIFFNESS_VERY_LOW))
    private var expandedTarget = false

    var isFollowing = true
        private set
    private var holdUntilUptime = 0L
    private var inPanMode = false

    private var shownLines: List<LyricLine>? = null
    private var shownSynced = false
    private var unsyncedSource: Lyrics.Unsynced? = null
    private var unsyncedLines: List<LyricLine> = emptyList()

    private var lastFrameNanos = 0L
    private var driftTime = 0f
    private var pulse = 0f
    private var activity = 0f
    private var beatIndex = 0
    private var lastWordKey = -1L
    private var lastPulseUptime = 0L
    private var lastActive = -2

    private var tapRect: RectF? = null
    private var tapUptime = 0L

    private var lastContentRect = Rect()
    private var lastTextSize = 30f * density
    private var lastTypeface = dev.lyricdrive.data.settings.BackgroundMode.ALBUM_FLUID.theme().typeface

    /** True while something animates; callers may drop to a low frame rate otherwise. */
    fun isAnimating(): Boolean =
        source.isPlaying || spring.isRunning || fling?.isRunning == true || expansionSpring.isRunning || !isFollowing

    fun draw(canvas: Canvas, width: Int, height: Int, lyricsArea: Rect, frameTimeNanos: Long) {
        val prefs = settings()
        val style = prefs.animationStyle.spec()
        val theme = prefs.backgroundMode.theme()
        applySpring(prefs.animationStyle, style)

        val moving = isMoving()
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos
        driftTime += dt * (0.35f + 0.9f * source.energy) * if (source.isPlaying) 1f else 0.25f

        // Parked full-screen expansion.
        // The cover panel needs a wide area; beside Maps the lyrics get a narrower split pane.
        val wide = lyricsArea.width() > lyricsArea.height() * 1.4f
        val wantExpanded = supportsFullScreen && prefs.fullScreenWhenParked && !moving && wide
        if (wantExpanded != expandedTarget) {
            expandedTarget = wantExpanded
            resumeFollowing()
            expansionSpring.animateToFinalPosition(if (wantExpanded) 1f else 0f)
        }
        val expansion = expansionHolder.value.coerceIn(0f, 1f)

        // Hold / resume of manual browsing.
        if (!isFollowing && !inPanMode && SystemClock.uptimeMillis() >= holdUntilUptime) resumeFollowing()
        if (moving && !isFollowing) resumeFollowing()

        // Lyrics content.
        val track = source.track
        val state = source.lyrics
        var lines: List<LyricLine> = emptyList()
        var message: String? = null
        var subMessage: String? = track?.artist
        when (state) {
            LyricsState.Idle -> { message = context.getString(R.string.nothing_playing); subMessage = context.getString(R.string.nothing_playing_hint) }
            is LyricsState.Loading -> { message = track?.title; subMessage = context.getString(R.string.searching) }
            is LyricsState.NotFound -> { message = track?.title; subMessage = "${track?.artist.orEmpty()} — ${context.getString(R.string.no_lyrics)}" }
            is LyricsState.Unavailable -> { message = track?.title; subMessage = context.getString(R.string.offline) }
            is LyricsState.Ready -> when (val lyrics = state.lyrics) {
                is Lyrics.Instrumental -> { message = track?.title; subMessage = context.getString(R.string.instrumental) }
                is Lyrics.Synced -> lines = source.timeline.lines.ifEmpty { lyrics.lines }
                is Lyrics.Unsynced -> lines = unsyncedRows(lyrics)
            }
        }
        val synced = (state as? LyricsState.Ready)?.lyrics is Lyrics.Synced
        val timeline = source.timeline
        val position = source.positionMs(now)
        val active = if (synced) timeline.activeIndex(position) else -1
        val filled = if (active >= 0) timeline.filledCharacters(active, position) else 0f

        updateBeat(dt, lines, active, position, moving)

        // Scroll.
        val contentRect = painter.contentRect(lyricsArea, expanded = expansion >= 0.5f)
        lastContentRect = contentRect
        lastTextSize = 30f * density * textScale
        lastTypeface = theme.typeface
        if (lines !== shownLines) {
            shownLines = lines
            shownSynced = synced
            springTarget = Float.NaN
            spring.cancel()
            fling?.cancel()
            scroll.value = if (lines.isEmpty()) 0f else painter.scrollTargetFor(lines, active.coerceAtLeast(0), contentRect, lastTextSize, theme.typeface)
        } else if (isFollowing && synced && lines.isNotEmpty()) {
            val target = painter.scrollTargetFor(lines, active.coerceAtLeast(0), contentRect, lastTextSize, theme.typeface)
            if (style.springDamping == null || expansionSpring.isRunning) {
                spring.cancel()
                scroll.value = target
                springTarget = target
            } else if (springTarget.isNaN() || abs(springTarget - target) > 0.5f) {
                springTarget = target
                spring.animateToFinalPosition(target)
            }
        }

        // Colors.
        val ambient = resolveAmbient(prefs.nightMode, nightSignal(), ambientSensor())
        val albumPalette = source.palette
        val palette = prefs.backgroundMode.palette(albumPalette).forAmbient(ambient)
        val highlight = resolveHighlight(prefs.highlightColor, theme, style, palette, albumPalette, beatIndex, prefs.backgroundMode)

        background.draw(
            canvas,
            BackgroundPainter.Input(
                mode = prefs.backgroundMode,
                palette = palette,
                artwork = source.artwork,
                time = driftTime,
                width = width,
                height = height,
                pulse = pulse,
                activity = activity,
                beatIndex = beatIndex,
                partyAllowed = !moving,
                scrim = scrimFor(theme, ambient),
            ),
        )

        val tapAge = SystemClock.uptimeMillis() - tapUptime
        val countdown = if (isFollowing) null else if (inPanMode) 1f else
            ((holdUntilUptime - SystemClock.uptimeMillis()).toFloat() / RESUME_DELAY_MS).coerceIn(0f, 1f)
        painter.draw(
            canvas,
            LyricsPainter.Frame(
                area = lyricsArea,
                lines = lines,
                synced = synced,
                activeIndex = active,
                positionMs = position,
                filledCharacters = filled,
                lineProgress = if (active >= 0) timeline.lineProgress(active, position) else 0f,
                scrollY = scroll.value,
                compact = moving && prefs.carLayout == CarLayout.COMPACT,
                style = style,
                theme = theme,
                highlight = highlight,
                textAlpha = textAlphaFor(ambient),
                expansion = expansion,
                artwork = source.artwork,
                title = track?.title,
                artist = track?.artist,
                header = if (drawHeader && message == null) track?.let { "${it.title} · ${it.artist}" } else null,
                message = message,
                subMessage = subMessage,
                resumeCountdown = countdown,
                tapRect = tapRect?.takeIf { tapAge < TAP_FEEDBACK_MS },
                tapProgress = (tapAge.toFloat() / TAP_FEEDBACK_MS).coerceIn(0f, 1f),
                textSize = lastTextSize,
            ),
        )
    }

    /** Word onsets drive the disco pulse / spectrum activity; rate-limited to 3 Hz (photosensitivity). */
    private fun updateBeat(dt: Float, lines: List<LyricLine>, active: Int, position: Long, moving: Boolean) {
        pulse *= exp(-dt * 6f)
        activity *= exp(-dt * 1.2f)
        if (active != lastActive) {
            if (active >= 0 && lastActive != -2) beatIndex++
            lastActive = active
        }
        val line = lines.getOrNull(active) ?: return
        if (!source.isPlaying) return
        val wordIndex = line.words.indexOfLast { it.startMs <= position }
        val key = (active.toLong() shl 20) or (wordIndex.toLong() and 0xFFFFF)
        if (key != lastWordKey) {
            lastWordKey = key
            activity = (activity + 0.35f).coerceAtMost(1f)
            val nowUp = SystemClock.uptimeMillis()
            if (nowUp - lastPulseUptime >= MIN_PULSE_INTERVAL_MS) {
                lastPulseUptime = nowUp
                pulse = if (moving) 0.4f else 1f
            }
        }
    }

    private fun applySpring(style: AnimationStyle, spec: LyricStyleSpec) {
        if (style == appliedStyle) return
        appliedStyle = style
        spec.springDamping?.let { spring.spring.setDampingRatio(it).setStiffness(spec.springStiffness) }
        springTarget = Float.NaN
    }

    private fun unsyncedRows(lyrics: Lyrics.Unsynced): List<LyricLine> {
        if (lyrics !== unsyncedSource) {
            unsyncedSource = lyrics
            unsyncedLines = lyrics.lines.map { LyricLine(0, 0, it) }
        }
        return unsyncedLines
    }

    // ------------------------------------------------------------------ input

    private fun browsingAllowed() = !isMoving() && settings().animationStyle.spec().layout == LayoutKind.LIST

    /** [dy] > 0 moves content up (finger moving up). */
    fun onScroll(dy: Float) {
        if (!browsingAllowed()) return
        pauseFollowing()
        fling?.cancel()
        scroll.value = clampScroll(scroll.value + dy)
    }

    /** [velocityY] is the finger velocity (positive = downwards), px/s. */
    fun onFling(velocityY: Float) {
        if (!browsingAllowed()) return
        val range = scrollRange() ?: return
        pauseFollowing()
        fling?.cancel()
        fling = FlingAnimation(scroll)
            .setStartVelocity(-velocityY)
            .setFriction(1.2f)
            .setMinValue(range.start)
            .setMaxValue(range.endInclusive)
            .also { it.start() }
    }

    /** Finger lifted after a drag: start the resume countdown. */
    fun onDragEnd() {
        holdUntilUptime = SystemClock.uptimeMillis() + RESUME_DELAY_MS
    }

    fun onPanModeChanged(panning: Boolean) {
        inPanMode = panning && !isMoving()
        if (inPanMode) pauseFollowing() else holdUntilUptime = SystemClock.uptimeMillis() + RESUME_DELAY_MS
    }

    /** Tap-to-resync: seek the player to the tapped line. */
    fun onTap(x: Float, y: Float) {
        if (!isFollowing && painter.resumeButton.contains(x, y)) return resumeFollowing()
        val lines = shownLines?.takeIf { shownSynced } ?: return
        val row = painter.hitRows.firstOrNull { (_, rect) -> rect.contains(x, y) } ?: return
        val line = lines.getOrNull(row.first) ?: return
        tapRect = RectF(row.second)
        tapUptime = SystemClock.uptimeMillis()
        source.seekTo(line.startMs)
        resumeFollowing()
    }

    fun resumeFollowing() {
        inPanMode = false
        if (isFollowing) return
        isFollowing = true
        fling?.cancel()
        springTarget = Float.NaN
        onFollowChanged()
    }

    private fun pauseFollowing() {
        spring.cancel()
        springTarget = Float.NaN
        holdUntilUptime = SystemClock.uptimeMillis() + RESUME_DELAY_MS
        if (isFollowing) {
            isFollowing = false
            onFollowChanged()
        }
    }

    private fun scrollRange(): ClosedFloatingPointRange<Float>? {
        val lines = shownLines?.takeIf { it.isNotEmpty() } ?: return null
        return painter.scrollRange(lines, lastContentRect, lastTextSize, lastTypeface)
    }

    private fun clampScroll(value: Float): Float {
        val range = scrollRange() ?: return value
        return value.coerceIn(range.start, range.endInclusive)
    }

    fun release() {
        spring.cancel()
        fling?.cancel()
        expansionSpring.cancel()
    }

    private companion object {
        const val RESUME_DELAY_MS = 4_000L
        const val TAP_FEEDBACK_MS = 350L
        const val MIN_PULSE_INTERVAL_MS = 333L
    }
}
