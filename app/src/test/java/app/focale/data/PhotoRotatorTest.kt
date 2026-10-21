package app.focale.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/** Écriture réelle de l'orientation EXIF et XMP (encodage natif Robolectric, ExifInterface). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhotoRotatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Données après la fin de l'image : carte de gain Ultra HDR, vidéo d'une photo animée… */
    private val trailer = "gainmap + motion video".toByteArray(Charsets.US_ASCII)

    private val tiff = """xmlns:tiff="http://ns.adobe.com/tiff/1.0/""""

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

    /** Valeurs de `Orientation` écrites en XMP (attribut ou élément), dans l'ordre du fichier. */
    private fun File.xmpOrientations(): List<String> =
        Regex("""Orientation\s*=\s*['"](\d)['"]|Orientation(?:\s[^<>]*)?>\s*(\d)\s*<""")
            .findAll(String(readBytes(), Charsets.ISO_8859_1))
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
            .toList()

    /** Comme [PhotoRotator] : emplacements EXIF et XMP trouvés dans le fichier, puis écriture sur place. */
    private fun patch(file: File, quarterTurns: Int): Boolean {
        val exif = file.inputStream().use { ExifInterface(it) }
        val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val target = ExifOrientation.rotate(current, quarterTurns)
        RandomAccessFile(file, "rw").use { access ->
            val channel = access.channel
            val slot = OrientationSlot.locate(exif, current, channel::readAt) ?: return false
            val xmp = XmpOrientationSlot.locate(channel::readAt)
            slot.write(channel, target)
            xmp?.write(channel, target)
        }
        return true
    }

    /** Paquet XMP : une description de premier niveau, avec [attributes] et le contenu [elements]. */
    private fun xmp(attributes: String, elements: String = "") = """
        <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" $attributes>$elements</rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    private fun withXmp(file: File, packet: String): File = file.apply { writeBytes(insertXmp(readBytes(), packet)) }

    /** [jpeg] avec un segment APP1 XMP, juste après SOI et l'éventuel segment EXIF. */
    private fun insertXmp(jpeg: ByteArray, packet: String): ByteArray {
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) + packet.toByteArray(Charsets.UTF_8)
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        val exif = jpeg[3] == 0xE1.toByte() && String(jpeg, 6, 4, Charsets.US_ASCII) == "Exif"
        val position = if (exif) 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)) else 2
        return jpeg.copyOfRange(0, position) + segment + jpeg.copyOfRange(position, jpeg.size)
    }

    /** WebP étendu suivi d'un bloc « XMP » : drapeau XMP du bloc VP8X levé, taille RIFF à jour. */
    private fun withWebpXmp(file: File, packet: String): File = file.apply {
        val webp = readBytes()
        check(String(webp, 12, 4, Charsets.US_ASCII) == "VP8X") { "WebP étendu attendu" }
        val payload = packet.toByteArray(Charsets.UTF_8)
        val chunk = "XMP ".toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload + ByteArray(payload.size % 2)
        val out = webp + chunk
        out[20] = (out[20].toInt() or 0x04).toByte()
        le32(out.size - 8).copyInto(out, 4)
        writeBytes(out)
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

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

    @Test
    fun `l'orientation XMP en attribut suit l'EXIF, d'un seul octet`() {
        val file = withXmp(
            withExif(image("xmp.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_NORMAL),
            xmp("""$tiff tiff:Orientation="1""""),
        )
        file.appendBytes(trailer)
        val before = file.readBytes()

        assertTrue(patch(file, 1))

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        // L'octet utile de la valeur EXIF et le chiffre XMP, rien d'autre.
        assertEquals(2, before.indices.count { before[it] != after[it] })
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, file.orientation())
        assertEquals(listOf("6"), file.xmpOrientations())
        assertArrayEquals(trailer, after.copyOfRange(after.size - trailer.size, after.size))
    }

    @Test
    fun `l'orientation XMP en élément, sous un autre préfixe`() {
        val file = withXmp(
            withExif(image("xmp-element.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_270),
            xmp("xmlns:t='http://ns.adobe.com/tiff/1.0/'", "\n   <t:Orientation>8</t:Orientation>\n  "),
        )

        assertTrue(patch(file, 1))

        assertEquals(ExifInterface.ORIENTATION_NORMAL, file.orientation())
        assertEquals(listOf("1"), file.xmpOrientations())
    }

    @Test
    fun `sans orientation XMP, seule l'orientation EXIF change`() {
        val plain = withExif(image("sans-xmp.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_NORMAL)
        RandomAccessFile(plain, "r").use { assertNull(XmpOrientationSlot.locate(it.channel::readAt)) }
        // XMP sans tiff:Orientation : rien n'est ajouté, l'EXIF fait foi.
        val file = withXmp(
            withExif(image("xmp-sans-orientation.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_NORMAL),
            xmp("""xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="5""""),
        )
        val before = file.readBytes()

        assertTrue(patch(file, 1))

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        assertEquals(1, before.indices.count { before[it] != after[it] })
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, file.orientation())
    }

    @Test
    fun `Ultra HDR, la carte de gain, son XMP et les données qui suivent restent intacts`() {
        val hdr = """xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0""""
        val file = withXmp(
            withExif(image("ultrahdr.jpg", Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_NORMAL),
            xmp("""$tiff $hdr tiff:Orientation="1""""),
        )
        // Carte de gain : second JPEG accolé à la fin de l'image principale, avec son propre XMP ;
        // puis la vidéo d'une photo animée.
        val tail = insertXmp(image("gainmap.jpg", Bitmap.CompressFormat.JPEG).readBytes(), xmp("""$tiff $hdr tiff:Orientation="1"""")) + trailer
        file.appendBytes(tail)
        val before = file.readBytes()

        assertTrue(patch(file, 2))

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        assertArrayEquals(tail, after.copyOfRange(after.size - tail.size, after.size))
        assertEquals(2, before.indices.count { before[it] != after[it] })
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, file.orientation())
        assertEquals(listOf("3", "1"), file.xmpOrientations())
    }

    @Test
    fun `dans un WebP, le bloc XMP suit l'EXIF sur place`() {
        val file = withWebpXmp(
            withExif(image("xmp.webp", Bitmap.CompressFormat.WEBP_LOSSY), ExifInterface.ORIENTATION_NORMAL),
            xmp("""$tiff tiff:Orientation="1""""),
        )
        val before = file.readBytes()

        assertTrue(patch(file, -1))

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        assertEquals(2, before.indices.count { before[it] != after[it] })
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, file.orientation())
        assertEquals(listOf("8"), file.xmpOrientations())
        assertNotNull(BitmapFactory.decodeFile(file.path))
    }

    @Test
    fun `quand le segment EXIF est refait, l'orientation XMP suit aussi`() {
        val file = withXmp(
            image("xmp-sans-exif.jpg", Bitmap.CompressFormat.JPEG),
            xmp("", """<tiff:Orientation xmlns:tiff="http://ns.adobe.com/tiff/1.0/">1</tiff:Orientation>"""),
        )
        file.appendBytes(trailer)
        val scan = file.readBytes().let { it.copyOfRange(startOfScan(it), it.size) }
        val exif = file.inputStream().use { ExifInterface(it) }
        val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        assertNull(OrientationSlot.locate(exif, current) { offset, length -> file.readAt(offset, length) })

        PhotoRotator.rewriteOrientation(file, ExifOrientation.rotate(current, 1))

        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, file.orientation())
        assertEquals(listOf("6"), file.xmpOrientations())
        val bytes = file.readBytes()
        assertArrayEquals(scan, bytes.copyOfRange(startOfScan(bytes), bytes.size))
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
