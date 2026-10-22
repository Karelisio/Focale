package app.focale.data

import app.focale.edit.ExifOrientation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rotation sans perte des HEIF / AVIF sur de vrais fichiers (`resources/heif`, écrits par libheif
 * et FFmpeg : voir tools/heif/make_fixtures.py). Image 64 × 48 ; dans les HEIC, élément 1 :
 * l'image principale (une grille dans grid-6.heic), élément 4 (8 dans la grille) : sa miniature,
 * qui partage sa propriété `irot`. EXIF et XMP y disent l'orientation du nom du fichier.
 */
class HeifRotationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/heif/$name")!!.use { it.readBytes() }

    private fun reader(bytes: ByteArray): (Long, Int) -> ByteArray? = { offset, length ->
        if (offset < 0 || length < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun plan(bytes: ByteArray, turns: Int): HeifRotation? = HeifRotation.plan(reader(bytes), bytes.size.toLong(), turns)

    /** Comme [PhotoRotator] : sur place dans un fichier, ou recopie complète ; null si la rotation est refusée. */
    private fun rotate(bytes: ByteArray, turns: Int): ByteArray? {
        val plan = plan(bytes, turns) ?: return null
        if (!plan.inPlace) return ByteArrayOutputStream().also { plan.copy(bytes.inputStream(), it) }.toByteArray()
        val file = File.createTempFile("heif", null, folder.root).apply { writeBytes(bytes) }
        RandomAccessFile(file, "rw").use { plan.write(it.channel) }
        return file.readBytes()
    }

    private fun orientation(bytes: ByteArray, item: Long? = null): Int? = HeifRotation.orientation(reader(bytes), bytes.size.toLong(), item)

    /** Valeur de l'orientation EXIF (entrée IFD gros-boutiste 0x0112), null sans balise. */
    private fun exif(bytes: ByteArray): Int? {
        val entry = byteArrayOf(0x01, 0x12, 0, 3, 0, 0, 0, 1)
        val at = indexOf(bytes, entry) ?: return null
        return ((bytes[at + 8].toInt() and 0xFF) shl 8) or (bytes[at + 9].toInt() and 0xFF)
    }

    private fun xmp(bytes: ByteArray): String? =
        Regex("""Orientation="(\d)"""").find(String(bytes, Charsets.ISO_8859_1))?.groupValues?.get(1)

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int = 0): Int? =
        (from..bytes.size - pattern.size).firstOrNull { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }

    /** [bytes] où [pattern], présent une seule fois, est remplacé par [replacement] (même longueur). */
    private fun replaceOnce(bytes: ByteArray, pattern: ByteArray, replacement: ByteArray): ByteArray {
        val at = indexOf(bytes, pattern) ?: error("Motif absent")
        assertNull("Motif en double", indexOf(bytes, pattern, at + 1))
        return bytes.copyOf().also { replacement.copyInto(it, at) }
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun differences(a: ByteArray, b: ByteArray): Int = a.indices.count { a[it] != b[it] }

    @Test
    fun `0, 90, 180 et 270 degrés, chaque quart de tour, irot, miniature, EXIF et XMP suivent`() {
        for (initial in listOf(1, 6, 3, 8)) {
            val original = fixture("orientation-$initial.heic")
            assertEquals(initial, orientation(original))
            for (turns in 1..3) {
                val rotated = rotate(original, turns)
                assertNotNull("orientation $initial, $turns quart(s) de tour", rotated)
                val expected = ExifOrientation.rotate(initial, turns)
                assertEquals("orientation $initial + $turns", expected, orientation(rotated!!))
                assertEquals(expected, orientation(rotated, item = 4))
                assertEquals(expected, exif(rotated))
                assertEquals(expected.toString(), xmp(rotated))
            }
        }
    }

    @Test
    fun `irot existante, réécrite sur place, d'un octet, plus l'EXIF et le XMP`() {
        val original = fixture("orientation-6.heic")
        val plan = plan(original, 1)!!
        assertTrue(plan.inPlace)
        assertEquals(3, plan.orientation)
        val rotated = rotate(original, 1)!!
        assertEquals(original.size, rotated.size)
        // Angle de irot, octet faible de l'orientation EXIF, chiffre du XMP.
        assertEquals(3, differences(original, rotated))
        // Quatre quarts de tour : le fichier d'origine, à l'octet près.
        var bytes = original
        repeat(4) { bytes = rotate(bytes, 1)!! }
        assertArrayEquals(original, bytes)
    }

    @Test
    fun `sans irot, la propriété est ajoutée et les données qui suivent la boîte meta sont décalées`() {
        val original = fixture("orientation-1.heic")
        val plan = plan(original, 1)!!
        assertFalse(plan.inPlace)
        assertTrue(plan.hasMetadata)
        val rotated = rotate(original, 1)!!
        // Une propriété de 9 octets, et une association d'un octet pour l'image et pour la miniature.
        assertEquals(original.size + 11, rotated.size)
        assertEquals(6, orientation(rotated))
        assertEquals(6, orientation(rotated, item = 4))
        // Avant la boîte meta, rien ne change ; après, tout est décalé, EXIF et XMP mis à jour.
        val metaEnd = 28 + 737
        assertArrayEquals(original.copyOfRange(0, 28), rotated.copyOfRange(0, 28))
        val tail = original.copyOfRange(metaEnd, original.size)
        val shifted = rotated.copyOfRange(metaEnd + 11, rotated.size)
        assertEquals(2, differences(tail, shifted))
        assertEquals(6, exif(rotated))
        assertEquals("6", xmp(rotated))
        // La propriété ajoutée se réécrit ensuite sur place ; quatre quarts de tour : irot à 0°.
        var bytes = rotated
        repeat(3) {
            assertTrue(plan(bytes, 1)!!.inPlace)
            bytes = rotate(bytes, 1)!!
        }
        assertEquals(1, orientation(bytes))
        assertEquals(rotated.size, bytes.size)
        assertEquals(1, exif(bytes))
    }

    @Test
    fun `HEIC sans EXIF ni XMP ni miniature, et AVIF de FFmpeg`() {
        val heic = fixture("plain.heic")
        val rotatedHeic = rotate(heic, 2)!!
        assertEquals(heic.size + 10, rotatedHeic.size)
        assertEquals(3, orientation(rotatedHeic))
        assertFalse(plan(heic, 2)!!.hasMetadata)

        val avif = fixture("plain.avif")
        assertEquals(1, orientation(avif))
        val rotatedAvif = rotate(avif, 3)!!
        assertEquals(avif.size + 10, rotatedAvif.size)
        assertEquals(8, orientation(rotatedAvif))
        // Données AV1 intactes, juste décalées.
        assertArrayEquals(avif.copyOfRange(32 + 249, avif.size), rotatedAvif.copyOfRange(32 + 249 + 10, rotatedAvif.size))
    }

    @Test
    fun `avec miroir, la rotation garde le miroir, et l'image tourne dans le bon sens`() {
        // Orientation 7 : irot 270° antihoraire puis imir haut-bas.
        val original = fixture("orientation-7.heic")
        assertEquals(7, orientation(original))
        for (turns in 1..3) {
            val rotated = rotate(original, turns)!!
            assertEquals(ExifOrientation.rotate(7, turns), orientation(rotated))
            assertEquals(ExifOrientation.rotate(7, turns), exif(rotated))
        }
    }

    @Test
    fun `grille de tuiles, la grille et sa miniature tournent, les tuiles ne bougent pas`() {
        val original = fixture("grid-6.heic")
        val rotated = rotate(original, 1)!!
        assertEquals(original.size, rotated.size)
        assertEquals(3, orientation(rotated))
        assertEquals(3, orientation(rotated, item = 8))
        for (tile in 2L..5L) assertEquals(1, orientation(rotated, item = tile))
    }

    @Test
    fun `irot partagée avec une tuile, la grille et la miniature en reçoivent une nouvelle`() {
        // Tuile 2 associée à la propriété irot (7) de la grille à la place de pixi (6).
        val shared = replaceOnce(
            fixture("grid-6.heic"),
            byteArrayOf(0, 2, 5, 0x82.toByte(), 3, 4, 6, 0x85.toByte()),
            byteArrayOf(0, 2, 5, 0x82.toByte(), 3, 4, 0x87.toByte(), 0x85.toByte()),
        )
        assertEquals(6, orientation(shared, item = 2))
        val plan = plan(shared, 1)!!
        assertFalse(plan.inPlace)
        val rotated = rotate(shared, 1)!!
        assertEquals(shared.size + 9, rotated.size)
        assertEquals(3, orientation(rotated))
        assertEquals(3, orientation(rotated, item = 8))
        assertEquals(6, orientation(rotated, item = 2))
    }

    @Test
    fun `fichier inattendu, rotation refusée, fichier intact`() {
        val six = fixture("orientation-6.heic")
        val one = fixture("orientation-1.heic")
        val moov = byteArrayOf(0, 0, 0, 8) + ascii("moov")
        val cases = mapOf(
            // Rotation suivie d'un recadrage : changer l'angle changerait le cadre.
            "irot avant clap" to replaceOnce(
                six,
                byteArrayOf(0x81.toByte(), 2, 3, 5, 0x84.toByte(), 0x86.toByte()),
                byteArrayOf(0x81.toByte(), 2, 3, 5, 0x86.toByte(), 0x84.toByte()),
            ),
            // Carte de gain ISO 21496-1.
            "élément tmap" to replaceOnce(six, byteArrayOf(0, 4, 0, 0) + ascii("hvc1"), byteArrayOf(0, 4, 0, 0) + ascii("tmap")),
            // Boîte qui pourrait pointer dans mdat : la boîte meta ne peut pas grandir.
            "moov à décaler" to one + moov,
            "données après la dernière boîte" to one + ascii("SEFT"),
            "boîte meta tronquée" to six.copyOf(400),
            "JPEG" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + ByteArray(64),
            "ftyp seul" to (byteArrayOf(0, 0, 0, 16) + ascii("ftypheic") + ByteArray(4)),
        )
        for ((name, bytes) in cases) {
            assertNull(name, plan(bytes, 1))
            val file = folder.newFile().apply { writeBytes(bytes) }
            // Comme PhotoRotator : sans plan, rien n'est écrit (il réencode alors une copie).
            HeifRotation.plan({ offset, length -> RandomAccessFile(file, "r").use { it.channel.readAt(offset, length) } }, file.length(), 1)
                ?.let { error("$name : rotation acceptée") }
            assertArrayEquals(name, bytes, file.readBytes())
        }
        // Une modification sur place, elle, ne décale rien : moov ne gêne pas.
        assertTrue(plan(six + moov, 1)!!.inPlace)
    }

    @Test
    fun `une recopie depuis un fichier d'une autre longueur échoue`() {
        val original = fixture("orientation-1.heic")
        val plan = plan(original, 1)!!
        assertTrue(runCatching { plan.copy(original.inputStream(), ByteArrayOutputStream()) }.isSuccess)
        for (changed in listOf(original.copyOf(500), original.copyOf(900), original + ByteArray(1))) {
            assertTrue(runCatching { plan.copy(changed.inputStream(), ByteArrayOutputStream()) }.isFailure)
        }
    }
}
