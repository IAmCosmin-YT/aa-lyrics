package dev.lyricdrive.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import dev.lyricdrive.core.domain.LyricsRepository
import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.core.sync.withWordHighlight
import dev.lyricdrive.core.ttml.TtmlConverter
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.data.settings.HighlightColor
import dev.lyricdrive.data.settings.LyricSettings
import dev.lyricdrive.ui.theme.LyricPalette
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Draws the lyrics scene (phone and car sizes) for every animation style and theme, over many
 * kinds of lyrics (duets, backing vocals, parentheses, line-synced, unsynced, random input) and
 * playback positions. Any exception here would crash the app while showing lyrics.
 *
 * All lyric text below is invented for the tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class RenderSmokeTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val track = TrackMetadata("Test song", "Test artist", durationMs = 60_000)

    private class FakeSource(
        override val track: TrackMetadata?,
        override var lyrics: LyricsState,
        override var timeline: LyricsTimeline,
    ) : SceneSource {
        var position = 0L
        override val isPlaying = true
        override val palette = LyricPalette.DEFAULT
        override val artwork: Bitmap? = null
        override val energy = 0.6f
        override fun positionMs(nowElapsedMs: Long) = position
        override fun seekTo(positionMs: Long) { position = positionMs }
    }

    private val duet = """
        [00:00.50]v1: <00:00.50>Paper <00:01.00>lan<00:01.30>terns <00:01.80>drift <00:02.40>
        [bg: <00:01.20>(drift <00:01.70>away)<00:02.60>]
        [00:02.20]v2: <00:02.20>Over <00:02.70>the <00:02.90>harbor <00:03.60>waaaay <00:05.40>
        [00:04.80]v1000: <00:04.80>We <00:05.00>sing <00:05.30>(we <00:05.60>sing) <00:05.90>together <00:06.80>
        [00:07.00]<00:07.00>(Whole <00:07.40>line <00:07.80>in <00:08.00>parens)<00:08.60>
        [00:09.00]v2: <00:09.00>Stay <00:09.40>(stay <00:09.80>with <00:10.00>me) <00:10.40>tonight <00:11.20>
        [00:16.00]v1: <00:16.00>After <00:16.40>a <00:16.60>long <00:17.00>break <00:17.80>
        [00:16.00]v2: <00:16.00>Same <00:16.40>time <00:16.80>other <00:17.20>voice <00:17.90>
        [00:19.00]
    """.trimIndent()

    private val lineSynced = """
        [offset:+200]
        [00:01.00]Morning (morning) comes so slow
        [00:04.00](Only the echo answers)
        [00:04.00][00:30.00]Repeated chorus line here
        [00:08.00]A really long line that keeps going and going so it has to wrap across several rows on a narrow screen without stopping
        [00:12.00]()
        [00:13.00]( )
        [00:14.00]Ends with (open paren
        [00:15.00]close) paren starts
        [00:20.00]日本語の歌詞の行
        [00:24.00]שורה בעברית לבדיקה
        [00:26.00]
        [00:40.00]Last one
    """.trimIndent()

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body dur="20.000"><div>
        <p begin="1.000" end="3.000" ttm:agent="v1"><span begin="1.000" end="1.400">Sil</span><span begin="1.400" end="1.900">ver</span> <span begin="1.900" end="2.900">rain</span><span ttm:role="x-bg"><span begin="2.000" end="2.500">(rain,</span> <span begin="2.500" end="3.200">rain)</span></span></p>
        <p begin="3.200" end="6.000" ttm:agent="v2"><span begin="3.200" end="3.600">Falling</span> <span begin="3.600" end="5.900">doooown</span></p>
        <p begin="6.500" end="8.000">Plain line no spans</p>
        <p begin="8.000" end="9.000" ttm:agent="v2"><span begin="8.000" end="9.000"> </span></p>
        </div></body></tt>
    """.trimIndent()

    private fun synced(lrc: String): Lyrics.Synced =
        LyricsRepository.toLyrics(RawLyrics(lrc, null, false, "test"), track) as Lyrics.Synced

    /** Same transformation as NowPlayingSession. */
    private fun timelineFor(lyrics: Lyrics.Synced, mode: WordHighlight) =
        LyricsTimeline(lyrics.lines).withWordHighlight(mode).withInterludes()

    private var frameNanos = 0L

    private fun render(
        source: FakeSource,
        settings: LyricSettings,
        positions: List<Long>,
        width: Int,
        height: Int,
        car: Boolean,
        label: String,
    ) {
        val controller = LyricsController(
            context, source, { settings }, density = 2f,
            supportsFullScreen = car, drawHeader = car,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val area = Rect(0, 0, width, height)
        try {
            for (p in positions) {
                source.position = p
                frameNanos += 16_000_000
                controller.draw(canvas, width, height, area, frameNanos)
                // The controller catches drawing errors so the app survives them; here they fail.
                controller.lastRenderError?.let { throw it }
            }
        } catch (e: Throwable) {
            throw AssertionError("render failed: $label, style=${settings.animationStyle}, theme=${settings.backgroundMode}, at ${source.position} ms", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sweep(endMs: Long, step: Long) = (-500L..endMs step step).toList() + listOf(0L, endMs * 3)

    @Test
    fun `every style draws every kind of lyrics`() {
        val inputs = listOf(
            "duet" to synced(duet),
            "line-synced" to synced(lineSynced),
            "ttml" to synced(TtmlConverter.toEnhancedLrc(ttml)!!),
        )
        for ((name, lyrics) in inputs) {
            for (mode in WordHighlight.entries) {
                val source = FakeSource(track, LyricsState.Ready(track, lyrics), timelineFor(lyrics, mode))
                for (style in AnimationStyle.entries) {
                    val settings = LyricSettings(wordHighlight = mode, animationStyle = style)
                    render(source, settings, sweep(45_000, 230), 540, 1100, car = false, label = "$name/$mode phone")
                    render(source, settings, sweep(45_000, 410), 1280, 720, car = true, label = "$name/$mode car")
                    render(source, settings, sweep(45_000, 410), 600, 720, car = true, label = "$name/$mode car beside maps")
                }
            }
        }
    }

    @Test
    fun `every theme draws`() {
        val lyrics = synced(duet)
        val source = FakeSource(track, LyricsState.Ready(track, lyrics), timelineFor(lyrics, WordHighlight.AUTO))
        for (theme in BackgroundMode.entries) {
            for (highlight in listOf(HighlightColor.AUTO, HighlightColor.COMPLEMENTARY, HighlightColor.entries.last())) {
                for (style in listOf(AnimationStyle.EXPRESSIVE, AnimationStyle.KARAOKE, AnimationStyle.GLANCE)) {
                    val settings = LyricSettings(animationStyle = style, backgroundMode = theme, highlightColor = highlight)
                    render(source, settings, sweep(20_000, 700), 540, 1100, car = false, label = "themes phone")
                    render(source, settings, sweep(20_000, 900), 1280, 720, car = true, label = "themes car")
                }
            }
        }
    }

    @Test
    fun `message states and unsynced lyrics draw`() {
        val states = listOf(
            LyricsState.Idle,
            LyricsState.Loading(track),
            LyricsState.NotFound(track),
            LyricsState.Ready(track, Lyrics.Instrumental("test")),
            LyricsState.Ready(track, Lyrics.Unsynced(listOf("First plain line", "", "(backing in plain)", "Last"), "test")),
            // A lookup that just finished: Ready, but the session's timeline hasn't caught up yet.
            LyricsState.Ready(track, synced(duet)),
        )
        for (state in states) {
            val source = FakeSource(track, state, LyricsTimeline.EMPTY)
            for (style in AnimationStyle.entries) {
                render(source, LyricSettings(animationStyle = style), sweep(10_000, 1_000), 540, 1100, car = false, label = "state $state")
                render(source, LyricSettings(animationStyle = style), sweep(10_000, 1_000), 1280, 720, car = true, label = "state $state car")
            }
        }
    }

    @Test
    fun `timeline and lyrics out of step do not crash`() {
        // The lyrics state and the timeline are separate flows; for a frame they can disagree.
        val old = synced(lineSynced)
        val new = synced(duet)
        for (style in AnimationStyle.entries) {
            val a = FakeSource(track, LyricsState.Ready(track, old), timelineFor(new, WordHighlight.AUTO))
            render(a, LyricSettings(animationStyle = style), sweep(45_000, 500), 540, 1100, car = false, label = "old lyrics, new timeline")
            val b = FakeSource(track, LyricsState.Ready(track, new), timelineFor(old, WordHighlight.AUTO))
            render(b, LyricSettings(animationStyle = style), sweep(45_000, 500), 540, 1100, car = false, label = "new lyrics, old timeline")
        }
    }

    @Test
    fun `random lyrics draw`() {
        val r = Random(99)
        val pieces = listOf("la", "ooh", "(", ")", "(ooh)", "( yeah )", "me)", "(be", " ", "-", "é", "日本", "...", "()", "word")
        repeat(150) { n ->
            val sb = StringBuilder()
            var t = r.nextLong(0, 3000)
            repeat(r.nextInt(1, 10)) {
                t += r.nextLong(-1000, 5000).coerceAtLeast(-t)
                val tag = "[${LrcParser.formatTimestamp(t)}]"
                val kind = r.nextInt(8)
                when (kind) {
                    0 -> sb.append("[bg: ")
                    1 -> sb.append(tag).append("{bg}")
                    else -> sb.append(tag).append(listOf("", "v1: ", "v2: ", "v1000: ").random(r))
                }
                var wt = t
                repeat(r.nextInt(0, 7)) {
                    if (r.nextBoolean()) {
                        wt += r.nextLong(-100, 1500)
                        sb.append('<').append(LrcParser.formatTimestamp(wt.coerceAtLeast(0))).append('>')
                    }
                    sb.append(pieces.random(r)).append(if (r.nextBoolean()) " " else "")
                }
                if (r.nextBoolean()) sb.append('<').append(LrcParser.formatTimestamp(wt + 800)).append('>')
                if (kind == 0) sb.append(']')
                sb.append('\n')
            }
            val lyrics = LyricsRepository.toLyrics(RawLyrics(sb.toString(), null, false, "fuzz"), track) as? Lyrics.Synced ?: return@repeat
            val mode = WordHighlight.entries[n % WordHighlight.entries.size]
            val style = AnimationStyle.entries[n % AnimationStyle.entries.size]
            val source = FakeSource(track, LyricsState.Ready(track, lyrics), timelineFor(lyrics, mode))
            render(source, LyricSettings(wordHighlight = mode, animationStyle = style), sweep(t + 3000, 300), 540, 1100, car = false, label = "fuzz #$n:\n$sb")
            render(source, LyricSettings(wordHighlight = mode, animationStyle = style), sweep(t + 3000, 600), 1280, 720, car = true, label = "fuzz car #$n:\n$sb")
        }
    }
}
