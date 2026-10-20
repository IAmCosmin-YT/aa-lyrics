package dev.lyricdrive.car

import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.lyricdrive.AppGraph
import dev.lyricdrive.render.LiveSceneSource
import dev.lyricdrive.render.LyricsController

/**
 * Car-surface plumbing: VSync frame loop and touch forwarding. All drawing, scrolling and
 * tap-to-seek logic lives in the shared [LyricsController], so the car shows exactly what the
 * phone shows.
 *
 * - While moving: glanceable layout, browsing disabled, auto-follow forced on.
 * - When parked (and "Full screen when parked" is on): the cover panel slides in and all lines show.
 */
class LyricsSurfaceRenderer(
    private val carContext: CarContext,
    private val graph: AppGraph,
    private val driving: DrivingStateMonitor,
    private val onControlsChanged: () -> Unit,
) : DefaultLifecycleObserver {

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visibleArea = Rect()
    private var controller: LyricsController? = null

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var frameCount = 0L

    val isFollowing: Boolean get() = controller?.isFollowing ?: true

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container.surface
            width = container.width
            height = container.height
            if (visibleArea.isEmpty) visibleArea = Rect(0, 0, width, height)
            if (controller == null) {
                controller = LyricsController(
                    context = carContext,
                    source = LiveSceneSource(graph.nowPlaying),
                    settings = { graph.settings.settings.value },
                    density = container.dpi / 160f,
                    isMoving = { driving.isMoving },
                    // The host flips isDarkMode from the car's headlight / light-sensor night signal.
                    nightSignal = { carContext.isDarkMode },
                    ambientSensor = { graph.ambientLight.level.value },
                    supportsFullScreen = true,
                    drawHeader = true,
                    onFollowChanged = onControlsChanged,
                )
            }
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
            controller?.onScroll(distanceY)
            controller?.onDragEnd()
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            controller?.onFling(velocityY)
        }

        override fun onClick(x: Float, y: Float) {
            controller?.onTap(x, y)
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        graph.ambientLight.acquire()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopLoop()
        controller?.release()
        graph.ambientLight.release()
    }

    fun onPanModeChanged(panning: Boolean) = controller?.onPanModeChanged(panning)

    fun resumeFollowing() = controller?.resumeFollowing()

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
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val surface = surface?.takeIf { it.isValid } ?: return
        val controller = controller ?: return
        frameCount++
        // Paused and settled: ~15 fps is plenty for the slow background drift.
        if (!controller.isAnimating() && frameCount % 4L != 0L) return

        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Surface not drawable", e)
            return
        }
        try {
            controller.draw(canvas, width, height, visibleArea, frameTimeNanos)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val TAG = "LyricsSurfaceRenderer"
    }
}
