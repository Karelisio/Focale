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
            // Image tmap sans ses deux images d'entrée.
            "élément tmap seul" to replaceOnce(six, byteArrayOf(0, 4, 0, 0) + ascii("hvc1"), byteArrayOf(0, 4, 0, 0) + ascii("tmap")),
            // Données inconnues après la dernière boîte : la boîte meta ne peut pas grandir.
            "données après la dernière boîte" to one + ascii("SEFT"),
            "boîte meta tronquée" to six.copyOf(400),
            "JPEG" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + ByteArray(64),
            "ftyp seul" to (byteArrayOf(0, 0, 0, 16) + ascii("ftypheic") + ByteArray(4)),
        )
        assertRefused(cases)
        // Une modification sur place, elle, ne décale rien : moov ne gêne pas.
        assertTrue(plan(six + moov, 1)!!.inPlace)
    }

    /** Chaque fichier de [cases] : rotation refusée, fichier intact. */
    private fun assertRefused(cases: Map<String, ByteArray>) {
        for ((name, bytes) in cases) {
            assertNull(name, plan(bytes, 1))
            val file = folder.newFile().apply { writeBytes(bytes) }
            // Comme PhotoRotator : sans plan, rien n'est écrit (il réencode alors une copie).
            HeifRotation.plan({ offset, length -> RandomAccessFile(file, "r").use { it.channel.readAt(offset, length) } }, file.length(), 1)
                ?.let { error("$name : rotation acceptée") }
            assertArrayEquals(name, bytes, file.readBytes())
        }
    }

    /** Traîne Samsung : du dernier bloc avant `SEFH` jusqu'à la fin (`SEFT`). */
    private fun trailer(bytes: ByteArray): ByteArray = bytes.copyOfRange(indexOf(bytes, ascii("Image_UTC_Data"))!! - 8, bytes.size)

    @Test
    fun `traîne Samsung, décalée d'un seul tenant quand la boîte meta grandit, laissée telle quelle sinon`() {
        val original = fixture("samsung-1.heic")
        assertEquals(1, orientation(original))
        val plan = plan(original, 1)!!
        assertFalse(plan.inPlace)
        val rotated = rotate(original, 1)!!
        assertEquals(original.size + 11, rotated.size)
        assertEquals(6, orientation(rotated))
        assertEquals(6, orientation(rotated, item = 4))
        assertEquals(6, exif(rotated))
        assertEquals("6", xmp(rotated))
        // Traîne recopiée à l'octet près : ses blocs (dont la vidéo de la photo animée) restent désignés
        // par leur distance avant le répertoire.
        assertArrayEquals(trailer(original), trailer(rotated))
        assertTrue(plan.verify(reader(rotated), rotated.size.toLong()))
        // Fichier récrit autrement que prévu : refusé à la relecture.
        assertFalse(plan.verify(reader(rotated.copyOf(rotated.size - 1)), rotated.size - 1L))
        assertFalse(plan.verify(reader(original), original.size.toLong()))
        // Puis sur place, la traîne ne bouge plus.
        var bytes = rotated
        repeat(3) {
            assertTrue(plan(bytes, 1)!!.inPlace)
            bytes = rotate(bytes, 1)!!
        }
        assertEquals(1, orientation(bytes))
        assertArrayEquals(trailer(original), trailer(bytes))

        val six = fixture("samsung-6.heic")
        assertTrue(plan(six, 1)!!.inPlace)
        val turned = rotate(six, 1)!!
        assertEquals(3, orientation(turned))
        assertEquals(3, differences(six, turned))
    }

    @Test
    fun `traîne Samsung inattendue, la boîte meta ne grandit pas`() {
        val original = fixture("samsung-1.heic")
        val trailer = trailer(original)
        val boxes = original.copyOfRange(0, original.size - trailer.size)
        // Marqueur du premier bloc différent de celui de son entrée.
        val marker = byteArrayOf(0, 0, 1, 0x0A, 14, 0, 0, 0) + ascii("Image")
        assertRefused(
            mapOf(
                "bloc qui ne commence pas comme son entrée" to replaceOnce(original, marker, byteArrayOf(0, 0, 2, 0x0A, 14, 0, 0, 0) + ascii("Image")),
                "octets entre les boîtes et la traîne" to boxes + ascii("JUNK") + trailer,
                "répertoire trop long" to original.copyOf().also { it[it.size - 8] = (it[it.size - 8] + 1).toByte() },
            ),
        )
        // Sur place, la traîne inattendue ne gêne pas.
        assertTrue(plan(fixture("samsung-6.heic").let { it.copyOf(it.size - 4) + ascii("SEFX") }, 1)!!.inPlace)
    }

    /** Positions des morceaux de la vidéo (`stco` de la boîte `moov`). */
    private fun chunks(bytes: ByteArray): List<Long> {
        val at = indexOf(bytes, ascii("stco"))!! + 4
        val count = ((bytes[at + 4].toInt() and 0xFF) shl 24) or ((bytes[at + 5].toInt() and 0xFF) shl 16) or
            ((bytes[at + 6].toInt() and 0xFF) shl 8) or (bytes[at + 7].toInt() and 0xFF)
        return (0 until count).map { index ->
            (0 until 4).fold(0L) { value, byte -> (value shl 8) or (bytes[at + 8 + 4 * index + byte].toLong() and 0xFF) }
        }
    }

    @Test
    fun `photo animée, moov gardée, seules les positions de ses morceaux suivent les données`() {
        val original = fixture("animated-1.heic")
        val plan = plan(original, 1)!!
        assertFalse(plan.inPlace)
        val rotated = rotate(original, 1)!!
        assertEquals(original.size + 11, rotated.size)
        assertEquals(6, orientation(rotated))
        assertEquals(chunks(original).map { it + 11 }, chunks(rotated))
        // La vidéo n'est ni réencodée ni tournée : moov identique hors de ses positions, images intactes.
        val moov = indexOf(original, ascii("moov"))!! - 4
        val stco = indexOf(original, ascii("stco"))!! + 12
        val entries = chunks(original).size * 4
        val shifted = moov + 11
        assertArrayEquals(original.copyOfRange(moov, stco), rotated.copyOfRange(shifted, stco + 11))
        assertArrayEquals(original.copyOfRange(stco + entries, original.size), rotated.copyOfRange(stco + 11 + entries, rotated.size))
        assertTrue(plan.verify(reader(rotated), rotated.size.toLong()))
        // Quatre quarts de tour : la propriété ajoutée revient à 0°, la vidéo reste à sa place.
        var bytes = rotated
        repeat(3) { bytes = rotate(bytes, 1)!! }
        assertEquals(1, orientation(bytes))
        assertEquals(chunks(rotated), chunks(bytes))
    }

    @Test
    fun `photo animée inattendue, rotation refusée, fichier intact`() {
        val original = fixture("animated-1.heic")
        val one = fixture("orientation-1.heic")
        val stco = indexOf(original, ascii("stco"))!! + 12
        val moov = indexOf(original, ascii("moov"))!! - 4
        assertRefused(
            mapOf(
                // Morceau ailleurs que dans une boîte mdat (ici dans la boîte meta).
                "morceau hors des données" to original.copyOf().also { byteArrayOf(0, 0, 0, 64).copyInto(it, stco) },
                // Données hors du fichier.
                "référence de données externe" to replaceOnce(original, ascii("url ") + byteArrayOf(0, 0, 0, 1), ascii("url ") + byteArrayOf(0, 0, 0, 0)),
                // Positions absolues que le décalage ne suivrait pas.
                "boîte saio" to replaceOnce(original, ascii("udta"), ascii("saio")),
                // Second fichier ajouté à la suite (sa moov compte depuis son propre début).
                "MP4 ajouté" to one + byteArrayOf(0, 0, 0, 16) + ascii("ftypisom") + ByteArray(4) + original.copyOfRange(moov, original.size),
                "deux moov" to original + original.copyOfRange(moov, indexOf(original, ascii("mdat"), moov)!! - 4),
            ),
        )
    }

    @Test
    fun `carte de gain tmap, l'image tmap tourne avec l'image principale, l'image de gain sans orientation reste telle quelle`() {
        // Éléments : 1 image principale, 4 miniature, 5 image de gain (cachée), 6 image tmap.
        val six = fixture("tmap-6.heic")
        assertEquals(6, orientation(six, item = 6))
        assertEquals(1, orientation(six, item = 5))
        val plan = plan(six, 1)!!
        assertTrue("irot partagée par l'image, sa miniature et l'image tmap", plan.inPlace)
        val rotated = rotate(six, 1)!!
        assertEquals(3, differences(six, rotated))
        for (item in listOf(null, 4L, 6L)) assertEquals(3, orientation(rotated, item))
        assertEquals(1, orientation(rotated, item = 5))
        assertEquals(3, exif(rotated))

        val one = fixture("tmap-1.heic")
        assertFalse(plan(one, 1)!!.inPlace)
        val grown = rotate(one, 1)!!
        // Une propriété, et une association pour l'image, sa miniature et l'image tmap.
        assertEquals(one.size + 12, grown.size)
        for (item in listOf(null, 4L, 6L)) assertEquals(6, orientation(grown, item))
        assertEquals(1, orientation(grown, item = 5))
        for (turns in 1..3) {
            val turned = rotate(one, turns)!!
            assertEquals(ExifOrientation.rotate(1, turns), orientation(turned, item = 6))
            assertEquals(1, orientation(turned, item = 5))
        }
    }

    @Test
    fun `carte de gain orientée comme l'image principale, elle tourne aussi`() {
        val original = fixture("tmap-gain-6.heic")
        assertEquals(6, orientation(original, item = 5))
        for (turns in 1..3) {
            val rotated = rotate(original, turns)!!
            val expected = ExifOrientation.rotate(6, turns)
            for (item in listOf(null, 4L, 5L, 6L)) assertEquals("élément $item, $turns quart(s) de tour", expected, orientation(rotated, item))
        }
    }

    @Test
    fun `carte de gain inattendue, rotation refusée, fichier intact`() {
        val gain = fixture("tmap-gain-6.heic")
        val dimg = ascii("dimg") + byteArrayOf(0, 6, 0, 2, 0, 1, 0, 5)
        assertRefused(
            mapOf(
                // Image de gain tournée, image principale non : elles ne sont plus superposées.
                "image de gain orientée autrement" to replaceOnce(
                    gain,
                    byteArrayOf(1, 6, 0x81.toByte(), 2, 3, 5, 0x84.toByte(), 0x86.toByte()),
                    byteArrayOf(1, 6, 0x81.toByte(), 2, 3, 5, 0x84.toByte(), 0x84.toByte()),
                ),
                // Image tmap dérivée d'une autre image que l'image principale (la miniature).
                "tmap d'une autre image" to replaceOnce(gain, dimg, ascii("dimg") + byteArrayOf(0, 6, 0, 2, 0, 4, 0, 5)),
                "tmap sans image de gain" to replaceOnce(gain, dimg, ascii("dimg") + byteArrayOf(0, 6, 0, 1, 0, 1, 0, 5)),
            ),
        )
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
