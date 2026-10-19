package app.focale.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Nettoyage réel d'un JPEG (encodage et décodage natifs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class JpegMetadataStripperTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun jpegWithMetadata(): File {
        val file = folder.newFile("photo.jpg")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply {
            setLatLong(48.8566, 2.3522)
            setAttribute(ExifInterface.TAG_MAKE, "Focale Cam")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 12:00:00")
            saveAttributes()
        }
        return file
    }

    @Test
    fun `retire EXIF et GPS sans altérer l'image`() {
        val source = jpegWithMetadata()
        assertNotNull(ExifInterface(source).latLong)

        val target = folder.newFile("clean.jpg")
        source.inputStream().use { input -> target.outputStream().use { JpegMetadataStripper.strip(input, it) } }

        val exif = ExifInterface(target)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val decoded = BitmapFactory.decodeFile(target.path)
        assertEquals(64, decoded.width)
        assertEquals(48, decoded.height)
    }

    @Test
    fun `ignore ce qui suit la fin de l'image`() {
        val source = jpegWithMetadata()
        source.appendBytes("ftypmp42 video".toByteArray(Charsets.US_ASCII))

        val bytes = ByteArrayOutputStream().also { out -> source.inputStream().use { JpegMetadataStripper.strip(it, out) } }
            .toByteArray()

        assertEquals(0xFF, bytes[bytes.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, bytes.last().toInt() and 0xFF)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("ftypmp42"))
    }

    @Test(expected = IOException::class)
    fun `refuse ce qui n'est pas un JPEG`() {
        JpegMetadataStripper.strip("not a jpeg".byteInputStream(), ByteArrayOutputStream())
    }
}
