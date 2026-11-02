package dev.lyricdrive.domain

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Color
import dev.lyricdrive.ui.theme.AlbumPaletteExtractor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = Application::class)
class ArtworkLoaderTest {
    private val loader = ArtworkLoader(RuntimeEnvironment.getApplication())

    @Test fun `large artwork is bounded without stretching its aspect ratio`() = runBlocking {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val prepared = loader.prepare(source)!!
        assertEquals(512, prepared.width)
        assertEquals(256, prepared.height)
        assertFalse(source.isRecycled)
    }

    @Test fun `decoded artwork can be read by Palette and drawn on a software canvas`() = runBlocking {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val hardware = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
        // Robolectric may fall back to software allocation; real GPU allocation needs a device test.
        val prepared = loader.prepare(hardware)!!
        assertNotEquals(Bitmap.Config.HARDWARE, prepared.config)
        AlbumPaletteExtractor.extract(prepared)
        Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)).drawBitmap(prepared, 0f, 0f, null)
        assertFalse(hardware.isRecycled)
    }
}
