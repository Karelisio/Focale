package app.focale.panorama

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Décodage d'une photo 360° en texture, par bandes : taille, position des pixels, pas de couture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SphereTextureTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Image 1200 × 600 dont le rouge suit x et le vert suit y : chaque pixel dit d'où il vient. */
    private fun gradient(): Uri {
        val width = 1200
        val height = 600
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Color.rgb(x * 255 / (width - 1), y * 255 / (height - 1), 80)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = folder.newFile("sphere.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(File(file.path))
    }

    @Test
    fun `texture réduite à GL_MAX_TEXTURE_SIZE, pixels à leur place, sans couture entre les bandes`() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        val texture = SphereTexture.decodeBands(resolver, gradient(), maxTextureSize = 512, maxPixels = 1L shl 25)
        assertNotNull(texture)
        texture!!
        assertEquals(512, texture.width)
        assertEquals(256, texture.height)
        val center = texture.getPixel(256, 128)
        assertEquals(127, Color.red(center), 4)
        assertEquals(128, Color.green(center), 4)
        // Le vert croît régulièrement de haut en bas, y compris d'une bande de 128 lignes à l'autre.
        var previous = -1
        for (y in 0 until texture.height) {
            val green = Color.green(texture.getPixel(100, y))
            if (previous >= 0) assertTrue("ligne $y : $previous puis $green", green >= previous && green - previous <= 3)
            previous = green
        }
        assertTrue(abs(Color.green(texture.getPixel(100, 255)) - 255) <= 3)
        texture.recycle()
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("attendu $expected ± $tolerance, obtenu $actual", abs(expected - actual) <= tolerance)
}
