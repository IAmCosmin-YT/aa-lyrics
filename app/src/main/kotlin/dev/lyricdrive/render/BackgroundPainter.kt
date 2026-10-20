package dev.lyricdrive.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import androidx.core.graphics.ColorUtils
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.ui.theme.LyricPalette
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws every background theme onto a plain [Canvas]. Soft layers (blobs, clouds, artwork, EQ
 * silhouette) go through a GPU Gaussian blur on API 31+ hardware canvases; crisp layers
 * (spotlights, scanlines, grain, vinyl) are drawn on top unblurred.
 */
class BackgroundPainter(private val density: Float) {

    data class Input(
        val mode: BackgroundMode,
        val palette: LyricPalette,
        val artwork: Bitmap?,
        /** Monotonic drift time in seconds (already scaled by tempo / pause). */
        val time: Float,
        val width: Int,
        val height: Int,
        /** 0..1, spikes on each sung word and decays (rate-limited upstream). */
        val pulse: Float,
        /** 0..1 envelope of recent vocal activity. */
        val activity: Float,
        /** Increments on each new line; drives the disco color cycle. */
        val beatIndex: Int,
        /** Party effects (strong pulses) only when parked / on the phone. */
        val partyAllowed: Boolean,
        val scrim: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val path = Path()
    private val rect = RectF()

    private var blurNode: RenderNode? = null
    private var blurKey = 0L

    fun draw(canvas: Canvas, input: Input) {
        val w = input.width.toFloat()
        val h = input.height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(input.palette.deep)

        // Soft layer (blurred).
        softLayer(canvas, input) { c ->
            when (input.mode) {
                BackgroundMode.BLACK -> Unit
                BackgroundMode.ALBUM_GRADIENT -> gradient(c, w, h, input.palette)
                BackgroundMode.ALBUM_ART -> if (!artwork(c, w, h, input)) blobs(c, w, h, input.time, input.palette.blobColors(), 220)
                BackgroundMode.COVER_BLEND -> if (!coverBlend(c, w, h, input)) blobs(c, w, h, input.time, input.palette.blobColors(), 220)
                BackgroundMode.LOFI -> blobs(c, w, h, input.time * 0.6f, input.palette.blobColors(), 150)
                BackgroundMode.DISCO -> discoBlobs(c, w, h, input)
                BackgroundMode.SPECTRUM -> {
                    blobs(c, w, h, input.time, input.palette.blobColors(), 160)
                    spectrum(c, w, h, input)
                }
                else -> blobs(c, w, h, input.time, input.palette.blobColors(), 220)
            }
        }

        // Crisp layer.
        when (input.mode) {
            BackgroundMode.LOFI -> lofi(canvas, w, h, input)
            BackgroundMode.DISCO -> discoOverlay(canvas, w, h, input)
            else -> Unit
        }

        if (input.scrim > 0f) canvas.drawColor(ColorUtils.setAlphaComponent(Color.BLACK, (input.scrim * 255).toInt()))
    }

    private inline fun softLayer(canvas: Canvas, input: Input, block: (Canvas) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
            val node = blurNode ?: RenderNode("lyrics-bg").also { blurNode = it }
            val key = (input.width.toLong() shl 32) or input.height.toLong()
            if (key != blurKey) {
                blurKey = key
                node.setPosition(0, 0, input.width, input.height)
                val r = 60f * density
                node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
            val c = node.beginRecording(input.width, input.height)
            try {
                block(c)
            } finally {
                node.endRecording()
            }
            canvas.drawRenderNode(node)
        } else {
            block(canvas)
        }
    }

    private fun LyricPalette.blobColors() = intArrayOf(primary, secondary, accent, primary)

    // ------------------------------------------------------------------ soft layers

    private var blobShaders: Array<Shader>? = null
    private var blobShaderKey: List<Int>? = null

    private fun blobs(c: Canvas, w: Float, h: Float, t: Float, colors: IntArray, alpha: Int) {
        val key = colors.toList() + alpha
        if (key != blobShaderKey) {
            blobShaderKey = key
            blobShaders = Array(colors.size) { i ->
                RadialGradient(0f, 0f, 1f, intArrayOf(ColorUtils.setAlphaComponent(colors[i], alpha), ColorUtils.setAlphaComponent(colors[i], 0)), null, Shader.TileMode.CLAMP)
            }
        }
        val shaders = blobShaders!!
        for (i in shaders.indices) {
            val cx = w * (0.5f + 0.38f * sin(t * SPEED_X[i] + PHASE[i]))
            val cy = h * (0.5f + 0.38f * cos(t * SPEED_Y[i] + PHASE[i] * 1.7f))
            val r = max(w, h) * (0.55f + 0.08f * sin(t * 0.7f + i))
            matrix.setScale(r, r)
            matrix.postTranslate(cx, cy)
            shaders[i].setLocalMatrix(matrix)
            paint.shader = shaders[i]
            c.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null
    }

    private fun gradient(c: Canvas, w: Float, h: Float, p: LyricPalette) {
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(p.primary, p.secondary, p.deep), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private var tinySource: Bitmap? = null
    private var tiny: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** 32 px copy stretched with bilinear filtering: already a soft blur on any API level. */
    private fun artwork(c: Canvas, w: Float, h: Float, input: Input): Boolean {
        val source = input.artwork?.takeUnless { it.isRecycled } ?: return false
        if (source !== tinySource) {
            tinySource = source
            tiny = Bitmap.createScaledBitmap(source, 32, 32, true)
        }
        val t = input.time
        val side = max(w, h) * (1.25f + 0.08f * sin(t * 0.4f))
        val left = (w - side) / 2f + w * 0.06f * sin(t * 0.23f)
        val top = (h - side) / 2f + h * 0.06f * cos(t * 0.19f)
        rect.set(left, top, left + side, top + side)
        c.drawBitmap(tiny!!, null, rect, bitmapPaint)
        return true
    }

    private var discSource: Bitmap? = null
    private var discShader: BitmapShader? = null
    private var discSize = 1
    private val saturate = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.5f) })
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = saturate }

    /**
     * Cover blend: the cover art drawn as several large discs, each rotating at its own pace and
     * drifting slightly, then blurred together by the soft layer — the colors of the artwork keep
     * flowing into each other without ever looking like a picture.
     */
    private fun coverBlend(c: Canvas, w: Float, h: Float, input: Input): Boolean {
        val source = input.artwork?.takeUnless { it.isRecycled } ?: return false
        if (source !== discSource) {
            discSource = source
            val small = Bitmap.createScaledBitmap(source, 96, 96, true)
            discSize = small.width
            discShader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val shader = discShader ?: return false
        val span = max(w, h)
        val t = input.time
        // (center x, center y, radius, degrees per time unit, phase)
        for (d in DISCS) {
            val cx = w * (d[0] + 0.05f * sin(t * 0.13f + d[4]))
            val cy = h * (d[1] + 0.05f * cos(t * 0.11f + d[4]))
            val r = span * d[2]
            matrix.setScale(2 * r / discSize, 2 * r / discSize)
            matrix.postTranslate(cx - r, cy - r)
            matrix.postRotate((t * d[3] + d[4] * 57f) % 360f, cx, cy)
            shader.setLocalMatrix(matrix)
            discPaint.shader = shader
            c.drawCircle(cx, cy, r, discPaint)
        }
        discPaint.shader = null
        return true
    }

    private fun discoBlobs(c: Canvas, w: Float, h: Float, input: Input) {
        val neon = PresetPalettes.NEON
        val a = neon[input.beatIndex.mod(neon.size)]
        val b = neon[(input.beatIndex + 1).mod(neon.size)]
        val boost = if (input.partyAllowed) 1f + 0.6f * input.pulse else 1f
        blobs(c, w, h, input.time * 1.4f, intArrayOf(a, b, a, PresetPalettes.DISCO.secondary), (150 * boost).toInt().coerceAtMost(255))
    }

    /** Equalizer silhouette along the bottom; heights follow vocal activity (players don't share audio). */
    private fun spectrum(c: Canvas, w: Float, h: Float, input: Input) {
        val bars = 40
        val barW = w / bars
        val maxH = h * 0.32f
        paint.shader = LinearGradient(0f, h - maxH, 0f, h, input.palette.accent, input.palette.primary, Shader.TileMode.CLAMP)
        val energy = 0.25f + 0.75f * input.activity
        for (i in 0 until bars) {
            val n = 0.5f + 0.5f * sin(input.time * (1.3f + (i % 7) * 0.21f) + i * 0.9f) * cos(input.time * 0.7f + i * 0.37f)
            val bh = maxH * (0.15f + 0.85f * n * energy)
            c.drawRect(i * barW + barW * 0.15f, h - bh, (i + 1) * barW - barW * 0.15f, h, paint)
        }
        paint.shader = null
    }

    // ------------------------------------------------------------------ crisp layers

    private var grainShader: BitmapShader? = null
    private var vinylShader: SweepGradient? = null

    private fun lofi(c: Canvas, w: Float, h: Float, input: Input) {
        // Spinning record, bottom right, mostly off screen.
        val r = h * 0.55f
        val cx = w * 0.92f
        val cy = h * 0.95f
        paint.color = 0xFF120A06.toInt()
        paint.alpha = 150
        c.drawCircle(cx, cy, r, paint)
        strokePaint.color = 0xFF3A2414.toInt()
        strokePaint.alpha = 120
        strokePaint.strokeWidth = 1f * density
        var ring = r * 0.35f
        while (ring < r) {
            c.drawCircle(cx, cy, ring, strokePaint)
            ring += 5f * density
        }
        val sheen = vinylShader ?: SweepGradient(0f, 0f, intArrayOf(0x00FFFFFF, 0x30FFE3B0, 0x00FFFFFF, 0x00FFFFFF, 0x30FFE3B0, 0x00FFFFFF), null).also { vinylShader = it }
        matrix.setRotate((input.time * 40f) % 360f)
        matrix.postTranslate(cx, cy)
        sheen.setLocalMatrix(matrix)
        paint.shader = sheen
        paint.alpha = 255
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Breathing vignette.
        val breathe = 0.5f + 0.5f * sin(input.time * 1.6f)
        val vr = max(w, h) * (0.75f + 0.05f * breathe)
        paint.shader = RadialGradient(w / 2f, h / 2f, vr, intArrayOf(0x00000000, 0x00000000, ColorUtils.setAlphaComponent(Color.BLACK, (150 + 40 * breathe).toInt())), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Film grain, jittered every few frames.
        val grain = grainShader ?: BitmapShader(noiseBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { grainShader = it }
        val jitter = (input.time * 12f).toInt()
        matrix.setTranslate((jitter * 37 % 97).toFloat(), (jitter * 53 % 89).toFloat())
        grain.setLocalMatrix(matrix)
        paint.shader = grain
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun noiseBitmap(): Bitmap {
        val size = 96
        val r = Random(11)
        val pixels = IntArray(size * size) {
            val v = r.nextInt(256)
            Color.argb(r.nextInt(28), v, v, v)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun discoOverlay(c: Canvas, w: Float, h: Float, input: Input) {
        val neon = PresetPalettes.NEON
        // Sweeping spotlights from the ceiling.
        for (i in 0 until 3) {
            val angle = sin(input.time * (0.5f + i * 0.17f) + i * 2.1f) * 0.45f
            val ox = w * (0.2f + 0.3f * i)
            val len = h * 1.3f
            val spread = 0.12f
            path.reset()
            path.moveTo(ox, 0f)
            path.lineTo(ox + len * sin(angle - spread), len * cos(angle - spread))
            path.lineTo(ox + len * sin(angle + spread), len * cos(angle + spread))
            path.close()
            val color = neon[(input.beatIndex + i).mod(neon.size)]
            paint.shader = LinearGradient(ox, 0f, ox + len * sin(angle), len * cos(angle), ColorUtils.setAlphaComponent(color, 70), 0x00000000, Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
        }
        paint.shader = null
        // Scanlines.
        paint.color = Color.BLACK
        paint.alpha = 40
        val gap = 3f * density
        var y = 0f
        while (y < h) {
            c.drawRect(0f, y, w, y + density, paint)
            y += gap
        }
        // Beat flash (much gentler while driving).
        val flash = input.pulse * if (input.partyAllowed) 0.22f else 0.06f
        if (flash > 0.01f) {
            paint.color = neon[input.beatIndex.mod(neon.size)]
            paint.alpha = (255 * flash).toInt()
            c.drawRect(0f, 0f, w, h, paint)
        }
    }

    private companion object {
        /** Cover-blend discs: back, center, top-left, bottom-right. */
        val DISCS = arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.95f, 6f, 0f),
            floatArrayOf(0.5f, 0.55f, 0.48f, -11f, 1.3f),
            floatArrayOf(0.12f, 0.2f, 0.42f, 15f, 2.6f),
            floatArrayOf(0.88f, 0.82f, 0.46f, -17f, 4.1f),
        )
        val SPEED_X = floatArrayOf(0.31f, 0.23f, 0.41f, 0.19f)
        val SPEED_Y = floatArrayOf(0.27f, 0.37f, 0.21f, 0.33f)
        val PHASE = floatArrayOf(0f, 1.9f, 3.7f, 5.1f)
    }
}
