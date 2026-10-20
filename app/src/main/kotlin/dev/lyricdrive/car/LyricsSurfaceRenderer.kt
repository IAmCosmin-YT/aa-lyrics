package dev.lyricdrive.car

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.lyricdrive.R
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.PlaybackClock
import dev.lyricdrive.domain.NowPlayingSession

/**
 * Draws lyrics on the car surface at display rate (Choreographer/VSync) and handles touch.
 *
 * - Auto-follow: the active line is kept at the optical center with a [SpringAnimation], so line
 *   changes and seeks glide instead of snapping.
 * - Manual browse: scroll/fling pause following; it resumes [RESUME_DELAY_MS] after the last touch
 *   (a "Resume auto-scroll" pill resumes immediately).
 * - Tap a line: seek the player to that line.
 * - While the vehicle moves, the layout becomes glanceable (previous/current/next only, no blur,
 *   no manual browsing). Auto-follow and tap-to-seek on the visible rows keep working.
 *
 * Everything runs on the main thread, which DynamicAnimation requires.
 */
class LyricsSurfaceRenderer(
    private val carContext: CarContext,
    private val nowPlaying: NowPlayingSession,
    private val driving: DrivingStateMonitor,
    private val onControlsChanged: () -> Unit,
) : DefaultLifecycleObserver {

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visibleArea = Rect()
    private var painter: LyricsCanvasPainter? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L
    private var frameCount = 0L
    private var backgroundTime = 0f

    private val scroll = FloatValueHolder(0f)
    private val spring = SpringAnimation(scroll).setSpring(
        SpringForce().setDampingRatio(0.82f).setStiffness(SpringForce.STIFFNESS_LOW),
    )
    private var fling: FlingAnimation? = null
    private var shownLines: List<LyricLine>? = null
    private var shownSynced = false
    private var springTarget = Float.NaN

    var isFollowing = true
        private set
    private var inPanMode = false
    private var resumeAtUptime = 0L

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container.surface
            width = container.width
            height = container.height
            if (visibleArea.isEmpty) visibleArea = Rect(0, 0, width, height)
            painter = LyricsCanvasPainter(container.dpi / 160f)
            startLoop()
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = Rect(area)
        }

        override fun onStableAreaChanged(area: Rect) = Unit

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            stopLoop()
            surface = null
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (driving.isMoving) return
            pauseFollowing()
            fling?.cancel()
            scroll.value = clampScroll(scroll.value + distanceY)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            if (driving.isMoving) return
            pauseFollowing()
            val range = currentScrollRange() ?: return
            fling?.cancel()
            fling = FlingAnimation(scroll)
                .setStartVelocity(-velocityY)
                .setFriction(1.2f)
                .setMinValue(range.start)
                .setMaxValue(range.endInclusive)
                .also { it.start() }
        }

        override fun onClick(x: Float, y: Float) = handleTap(x, y)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopLoop()
        spring.cancel()
        fling?.cancel()
    }

    fun onPanModeChanged(panning: Boolean) {
        inPanMode = panning && !driving.isMoving
        if (inPanMode) pauseFollowing() else resumeAtUptime = SystemClock.uptimeMillis() + RESUME_DELAY_MS
    }

    fun resumeFollowing() {
        if (isFollowing) return
        isFollowing = true
        fling?.cancel()
        onControlsChanged()
    }

    private fun pauseFollowing() {
        spring.cancel()
        springTarget = Float.NaN
        resumeAtUptime = SystemClock.uptimeMillis() + RESUME_DELAY_MS
        if (isFollowing) {
            isFollowing = false
            onControlsChanged()
        }
    }

    private fun handleTap(x: Float, y: Float) {
        val p = painter ?: return
        if (!isFollowing && p.resumeButton.contains(x, y)) return resumeFollowing()
        val lines = shownLines?.takeIf { shownSynced } ?: return
        val row = p.hitRows.firstOrNull { (_, rect) -> rect.contains(x, y) } ?: return
        val line = lines.getOrNull(row.first) ?: return
        nowPlaying.seekTo(line.startMs)
        resumeFollowing()
    }

    // ------------------------------------------------------------------ frame loop

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            renderFrame(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val surface = surface?.takeIf { it.isValid } ?: return
        val painter = painter ?: return
        val snapshot = nowPlaying.snapshot.value
        val animating = snapshot.isPlaying || spring.isRunning || fling?.isRunning == true || !isFollowing

        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = frameTimeNanos
        frameCount++
        // Background drifts faster for energetic songs, slows right down when paused.
        val drift = (0.35f + 0.9f * nowPlaying.energy.value) * if (snapshot.isPlaying) 1f else 0.25f
        backgroundTime += dt * drift
        // Paused and settled: redraw at ~15 fps to save power.
        if (!animating && frameCount % 4L != 0L) return

        if (!isFollowing && !inPanMode && SystemClock.uptimeMillis() >= resumeAtUptime) resumeFollowing()
        if (driving.isMoving && !isFollowing) resumeFollowing()

        val frame = buildFrame(painter)
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Surface not drawable", e)
            return
        }
        try {
            painter.draw(canvas, frame)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun buildFrame(painter: LyricsCanvasPainter): LyricsCanvasPainter.Frame {
        val track = nowPlaying.track.value
        val state = nowPlaying.lyrics.value
        val header = track?.let { "${it.title} · ${it.artist}" }
        val now = SystemClock.elapsedRealtime()

        var lines: List<LyricLine> = emptyList()
        var message: String? = null
        var subMessage: String? = track?.artist
        when (state) {
            LyricsState.Idle -> { message = carContext.getString(R.string.nothing_playing); subMessage = carContext.getString(R.string.nothing_playing_hint) }
            is LyricsState.Loading -> { message = track?.title; subMessage = carContext.getString(R.string.searching) }
            is LyricsState.NotFound -> { message = track?.title; subMessage = "${track?.artist.orEmpty()} — ${carContext.getString(R.string.no_lyrics)}" }
            is LyricsState.Unavailable -> { message = track?.title; subMessage = carContext.getString(R.string.offline) }
            is LyricsState.Ready -> when (val lyrics = state.lyrics) {
                is Lyrics.Instrumental -> { message = track?.title; subMessage = carContext.getString(R.string.instrumental) }
                is Lyrics.Synced -> lines = lyrics.lines
                // Unsynced text can't follow playback; show it as a static, browsable list.
                is Lyrics.Unsynced -> lines = unsyncedRows(lyrics)
            }
        }

        val synced = (state as? LyricsState.Ready)?.lyrics is Lyrics.Synced
        val timeline = if (synced) nowPlaying.timeline.value else LyricsTimeline.EMPTY
        val position = PlaybackClock.positionAt(nowPlaying.snapshot.value, now, track?.durationMs)
        val active = if (synced) timeline.activeIndex(position) else -1

        if (lines !== shownLines) {
            // New track: jump to the top of the new lyrics rather than springing across songs.
            shownLines = lines
            shownSynced = synced
            springTarget = Float.NaN
            spring.cancel()
            fling?.cancel()
            scroll.value = if (lines.isEmpty()) 0f else painter.scrollTargetFor(lines, active.coerceAtLeast(0), visibleArea)
        } else if (isFollowing && synced && lines.isNotEmpty()) {
            val target = painter.scrollTargetFor(lines, active.coerceAtLeast(0), visibleArea)
            if (springTarget.isNaN() || kotlin.math.abs(springTarget - target) > 0.5f) {
                springTarget = target
                spring.animateToFinalPosition(target)
            }
        }

        val countdown = if (isFollowing) null else if (inPanMode) 1f else
            ((resumeAtUptime - SystemClock.uptimeMillis()).toFloat() / RESUME_DELAY_MS).coerceIn(0f, 1f)

        return LyricsCanvasPainter.Frame(
            area = visibleArea,
            width = width,
            height = height,
            palette = nowPlaying.palette.value,
            backgroundTime = backgroundTime,
            lines = lines,
            activeIndex = active,
            filledCharacters = if (active >= 0) timeline.filledCharacters(active, position) else 0f,
            lineProgress = if (active >= 0) timeline.lineProgress(active, position) else 0f,
            scrollY = scroll.value,
            glanceable = driving.isMoving && synced,
            resumeCountdown = countdown,
            header = if (message == null) header else null,
            message = message,
            subMessage = subMessage,
        )
    }

    private var unsyncedSource: Lyrics.Unsynced? = null
    private var unsyncedLines: List<LyricLine> = emptyList()

    private fun unsyncedRows(lyrics: Lyrics.Unsynced): List<LyricLine> {
        if (lyrics !== unsyncedSource) {
            unsyncedSource = lyrics
            unsyncedLines = lyrics.lines.map { LyricLine(0, 0, it) }
        }
        return unsyncedLines
    }

    private fun currentScrollRange(): ClosedFloatingPointRange<Float>? {
        val lines = shownLines?.takeIf { it.isNotEmpty() } ?: return null
        return painter?.scrollRange(lines, visibleArea)
    }

    private fun clampScroll(value: Float): Float {
        val range = currentScrollRange() ?: return value
        return value.coerceIn(range.start, range.endInclusive)
    }

    private companion object {
        const val TAG = "LyricsSurfaceRenderer"
        const val RESUME_DELAY_MS = 4_000L
    }
}
