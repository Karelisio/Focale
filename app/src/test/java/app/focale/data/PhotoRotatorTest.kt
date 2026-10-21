package app.focale.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import app.focale.edit.ExifOrientation
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Écriture réelle de l'orientation EXIF (encodage natif Robolectric, ExifInterface). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoRotatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Données après la fin de l'image : carte de gain Ultra HDR, vidéo d'une photo animée… */
    private val trailer = "gainmap + motion video".toByteArray(Charsets.US_ASCII)

    private fun image(name: String, format: Bitmap.CompressFormat): File {
        val file = folder.newFile(name)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        file.outputStream().use { bitmap.compress(format, 90, it) }
        return file
    }

    private fun withExif(file: File, orientation: Int): File = file.apply {
        ExifInterface(this).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_MAKE, "Focale Cam")
            setLatLong(48.8566, 2.3522)
            saveAttributes()
        }
    }

    private fun File.readAt(offset: Long, length: Int = 2): ByteArray? = RandomAccessFile(this, "r").use { file ->
        if (offset < 0 || offset + length > file.length()) {
            null
        } else {
            ByteArray(length).also {
                file.seek(offset)
                file.readFully(it)
            }
        }
    }

    private fun File.orientation(): Int = ExifInterface(this).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)

    /** Comme [PhotoRotator] : emplacement trouvé dans le fichier, puis écriture sur place. */
    private fun patch(file: File, quarterTurns: Int): Boolean {
        val exif = file.inputStream().use { ExifInterface(it) }
        val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val slot = OrientationSlot.locate(exif, current) { offset, length -> file.readAt(offset, length) } ?: return false
        RandomAccessFile(file, "rw").use { slot.write(it.channel, ExifOrientation.rotate(current, quarterTurns)) }
        return true
    }

    @Test
    fun `un quart de tour ne change que les octets de l'orientation`() {
        val file = withExif(image("photo.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_NORMAL)
        file.appendBytes(trailer)
        val before = file.readBytes()

        assertTrue(patch(file, 1))

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue("octets modifiés : $changed", changed.isNotEmpty() && changed.last() - changed.first() <= 1)
        val exif = ExifInterface(file)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("Focale Cam", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNotNull(exif.latLong)
    }

    @Test
    fun `quarts de tour successifs et miroir`() {
        val file = withExif(image("photo.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_90)
        assertTrue(patch(file, 1))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, file.orientation())
        assertTrue(patch(file, -3))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, file.orientation())

        val mirrored = withExif(image("selfie.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertTrue(patch(mirrored, 1))
        assertEquals(ExifInterface.ORIENTATION_TRANSVERSE, mirrored.orientation())
    }

    @Test
    fun `EXIF en ordre Intel`() {
        val jpeg = image("intel.jpg", Bitmap.CompressFormat.JPEG).readBytes()
        // APP1 minimal juste après SOI : en-tête TIFF « II », IFD0 avec la seule orientation (6).
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val file = folder.newFile("intel-exif.jpg").apply { writeBytes(jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)) }
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, file.orientation())

        assertTrue(patch(file, 1))

        assertArrayEquals(byteArrayOf(0x03, 0x00), file.readAt(30))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, file.orientation())
    }

    @Test
    fun `WebP tourné sur place`() {
        val file = withExif(image("photo.webp", Bitmap.CompressFormat.WEBP_LOSSY), ExifInterface.ORIENTATION_NORMAL)
        val size = file.length()

        assertTrue(patch(file, -1))

        assertEquals(size, file.length())
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, file.orientation())
    }

    @Test
    fun `sans balise d'orientation le segment EXIF est réécrit sans toucher à l'image`() {
        val file = image("sans-exif.jpg", Bitmap.CompressFormat.JPEG)
        file.appendBytes(trailer)
        val scan = file.readBytes().let { it.copyOfRange(startOfScan(it), it.size) }
        val exif = file.inputStream().use { ExifInterface(it) }
        val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        assertNull(OrientationSlot.locate(exif, current) { offset, length -> file.readAt(offset, length) })

        PhotoRotator.rewriteOrientation(file, ExifOrientation.rotate(current, 1))

        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, file.orientation())
        val bytes = file.readBytes()
        assertArrayEquals(scan, bytes.copyOfRange(startOfScan(bytes), bytes.size))
    }

    @Test
    fun `des octets qui ne correspondent pas à l'orientation lue sont refusés`() {
        val file = withExif(image("photo.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_180)
        val exif = file.inputStream().use { ExifInterface(it) }
        assertNull(OrientationSlot.locate(exif, ExifInterface.ORIENTATION_ROTATE_90) { offset, length -> file.readAt(offset, length) })
        assertNull(OrientationSlot.locate(exif, ExifInterface.ORIENTATION_ROTATE_180) { _, _ -> null })
    }

    /** Position du marqueur SOS : tout ce qui suit (données compressées, fin du fichier) doit rester intact. */
    private fun startOfScan(bytes: ByteArray): Int {
        var index = 2
        while (index + 3 < bytes.size) {
            val marker = bytes[index + 1].toInt() and 0xFF
            if (marker == 0xDA) return index
            index += 2 + (((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF))
        }
        error("SOS introuvable")
    }
}
