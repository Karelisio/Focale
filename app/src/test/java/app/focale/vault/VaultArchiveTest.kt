package app.focale.vault

import app.focale.vault.VaultArchiveException.Reason
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlin.concurrent.thread
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Format des sauvegardes du coffre : aller-retour, mot de passe faux, archives tronquées ou
 * modifiées, et flux plus gros que la mémoire disponible.
 */
class VaultArchiveTest {

    // Itérations réduites : le coût de la dérivation n'est pas ce qui est testé ici.
    private val iterations = 1_000
    private val password = "cheval correct agrafe".toCharArray()

    private val random = Random(7)
    // Plus de trois segments de 1 Mio : frontières de segment et de morceau traversées.
    private val photo = random.nextBytes(3 * SEGMENT + 123)
    private val video = random.nextBytes(700_000)
    private val tiny = random.nextBytes(17)
    private val contents: List<Pair<ByteArray, ByteArray?>> = listOf(
        photo to random.nextBytes(20_000),
        video to null,
        tiny to random.nextBytes(500),
    )
    private val manifest = ArchiveManifest(
        createdAt = 1_760_000_000_000,
        items = listOf(
            entry("IMG_0001.jpg", photo.size.toLong(), thumbnail = true),
            entry("VID_0002.mp4", video.size.toLong(), video = true, thumbnail = false),
            entry("Capture d'écran.png", tiny.size.toLong(), thumbnail = true),
        ),
    )

    @Test
    fun `aller-retour avec manifeste, médias et miniatures à l'identique`() {
        val bytes = archive()
        val (read, blobs) = readAll(bytes)
        assertEquals(manifest, read)
        contents.forEachIndexed { index, (media, thumbnail) ->
            val (mediaBytes, mediaComplete) = blobs[2 * index]
            val (thumbBytes, thumbComplete) = blobs[2 * index + 1]
            assertArrayEquals(media, mediaBytes)
            assertArrayEquals(thumbnail ?: ByteArray(0), thumbBytes)
            assertTrue(mediaComplete && thumbComplete)
        }
        // Rien en clair : ni les noms du manifeste ni le contenu ne figurent dans le fichier.
        assertFalse(bytes.contains("IMG_0001".toByteArray()))
        assertFalse(bytes.contains(photo.copyOfRange(1_000, 1_032)))
    }

    @Test
    fun `les albums font l'aller-retour, avec l'album de chaque élément`() {
        val withAlbums = manifest.copy(
            albums = listOf(
                ArchiveAlbum(
                    id = 4,
                    name = "WhatsApp Images",
                    createdAt = 1_750_000_000_000,
                    sourceBucketId = -1_313_584_517,
                    sourcePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
                    lastImportAt = 1_755_000_000_000,
                ),
                ArchiveAlbum(id = 9, name = "Vacances", createdAt = 1_751_000_000_000),
            ),
            items = manifest.items.zip(listOf(4L, null, 9L)) { entry, album -> entry.copy(album = album) },
        )
        assertEquals(withAlbums, readAll(archive(withAlbums)).first)
    }

    @Test
    fun `une sauvegarde antérieure aux albums se lit, sans albums`() {
        // Archive écrite par la version d'avant les albums : son manifeste n'en dit rien.
        val bytes = requireNotNull(javaClass.getResourceAsStream(LEGACY_ARCHIVE)).use { it.readBytes() }
        val (read, blobs) = readAll(bytes)
        assertEquals(1_760_000_000_000, read.createdAt)
        assertEquals(listOf("IMG_0001.jpg", "VID_0002.mp4"), read.items.map { it.name })
        assertTrue(read.albums.isEmpty())
        assertTrue(read.items.all { it.album == null })
        assertEquals("Photo du coffre, sauvegardée avant les albums", blobs[0].first.decodeToString())
        assertEquals("Miniature de la photo", blobs[1].first.decodeToString())
        assertEquals("Vidéo du coffre, sans miniature", blobs[2].first.decodeToString())
        assertEquals(0, blobs[3].first.size)
    }

    @Test
    fun `un album sans nom rend le manifeste invalide`() {
        val bytes = archive(manifest.copy(albums = listOf(ArchiveAlbum(id = 1, name = " ", createdAt = 0))))
        assertReason(Reason.CORRUPTED) { readAll(bytes) }
    }

    @Test
    fun `deux archives du même contenu ne se ressemblent pas`() {
        val first = archive()
        val second = archive()
        assertFalse(first.copyOfRange(VaultArchive.MAGIC.size, 200).contentEquals(second.copyOfRange(VaultArchive.MAGIC.size, 200)))
        assertFalse(first.contains(second.copyOfRange(HEADER + 100, HEADER + 132)))
    }

    @Test
    fun `un mot de passe faux est reconnu sans rien déchiffrer`() {
        val bytes = archive()
        assertReason(Reason.WRONG_PASSWORD) { VaultArchive.open(bytes.inputStream(), "cheval correct agrafé".toCharArray()) }
        assertReason(Reason.WRONG_PASSWORD) { VaultArchive.open(bytes.inputStream(), "Cheval correct agrafe".toCharArray()) }
    }

    @Test
    fun `un fichier quelconque n'est pas une archive`() {
        assertReason(Reason.NOT_AN_ARCHIVE) { VaultArchive.open(ByteArray(0).inputStream(), password) }
        assertReason(Reason.NOT_AN_ARCHIVE) { VaultArchive.open("FOCALE".toByteArray().inputStream(), password) }
        assertReason(Reason.NOT_AN_ARCHIVE) { VaultArchive.open(Random(1).nextBytes(4_096).inputStream(), password) }
    }

    @Test
    fun `une archive d'une version plus récente est signalée`() {
        val bytes = archive()
        assertReason(Reason.UNSUPPORTED) { readAll(bytes.copyOf().also { it[VERSION_AT] = 2 }) }
        assertReason(Reason.UNSUPPORTED) { readAll(bytes.copyOf().also { it[HEADER - VaultArchive.WITNESS_BYTES - 1] = 2 }) }
        // Itérations hors bornes : refus immédiat, sans dérivation interminable.
        val excessive = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(ITERATIONS_AT, VaultArchive.MAX_ITERATIONS + 1) }
        assertReason(Reason.UNSUPPORTED) { readAll(excessive) }
    }

    @Test
    fun `toute troncature est refusée`() {
        val bytes = archive()
        val cuts = listOf(
            VERSION_AT + 3,
            HEADER - 1,
            HEADER,
            HEADER + 39,
            HEADER + 300,
            // Coupures aux frontières de segment : seul le marquage du dernier segment les révèle.
            HEADER + SEGMENT,
            HEADER + 2 * SEGMENT,
            bytes.size / 2,
            bytes.size - 17,
            bytes.size - 1,
        )
        for (cut in cuts) assertReason(Reason.CORRUPTED, "coupure à $cut") { readAll(bytes.copyOf(cut)) }
    }

    @Test
    fun `toute modification est refusée`() {
        val bytes = archive()
        // Paramètres de dérivation ou témoin altérés : la clé obtenue ne correspond plus.
        for (position in listOf(ITERATIONS_AT + 3, SALT_AT, HEADER - 1)) {
            assertReason(Reason.WRONG_PASSWORD, "octet $position") { readAll(flipped(bytes, position)) }
        }
        // Flux chiffré : en-tête Tink, manifeste, médias, étiquette du dernier segment.
        val positions = listOf(HEADER, HEADER + 5, HEADER + 120, HEADER + SEGMENT + 4_000, bytes.size / 2, bytes.size - 1)
        for (position in positions) {
            assertReason(Reason.CORRUPTED, "octet $position") { readAll(flipped(bytes, position)) }
        }
        assertReason(Reason.CORRUPTED, "octet ajouté") { readAll(bytes + byteArrayOf(0)) }
        // Deux segments permutés : chacun est intact, mais pas à sa place.
        val swapped = bytes.copyOf()
        bytes.copyInto(swapped, HEADER + SEGMENT, HEADER + 2 * SEGMENT, HEADER + 3 * SEGMENT)
        bytes.copyInto(swapped, HEADER + 2 * SEGMENT, HEADER + SEGMENT, HEADER + 2 * SEGMENT)
        assertReason(Reason.CORRUPTED, "segments permutés") { readAll(swapped) }
    }

    @Test
    fun `une source illisible à l'export donne un blob marqué incomplet`() {
        val readable = random.nextBytes(50_000)
        val failing = object : InputStream() {
            private var served = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 300_000) throw IOException("Segment illisible")
                val count = minOf(len, 100_000)
                served += count
                return count
            }
        }
        val partial = ArchiveManifest(1L, List(3) { entry("élément $it", 1L, thumbnail = false) })
        val output = ByteArrayOutputStream()
        val results = ArrayList<Boolean>()
        VaultArchive.write(output, password, partial, iterations) { writer ->
            results += writer.blob { failing }
            results += writer.blob { null }
            results += writer.blob { throw IOException("Fichier absent") }
            results += writer.blob { null }
            results += writer.blob { readable.inputStream() }
            results += writer.blob { null }
        }
        assertEquals(listOf(false, true, false, true, true, true), results)

        val (_, blobs) = readAll(output.toByteArray())
        assertEquals(listOf(false, true, false, true, true, true), blobs.map { it.second })
        assertArrayEquals(readable, blobs[4].first)
    }

    @Test
    fun `un gros flux traverse l'archive sans jamais tenir en mémoire`() {
        // Plus que le tas de la JVM de test : tout tampon complet échouerait faute de mémoire.
        val size = Runtime.getRuntime().maxMemory().coerceAtMost(1L shl 30) + 64L * 1024 * 1024
        val big =ArchiveManifest(1L, listOf(entry("film.mp4", size, video = true, thumbnail = false)))
        val pipe = Pipe.open()
        var writeError: Throwable? = null
        val writer = thread(isDaemon = true, name = "écriture") {
            try {
                VaultArchive.write(Channels.newOutputStream(pipe.sink()), password, big, iterations) { archive ->
                    archive.blob { GeneratedStream(size, seed = 99) }
                    archive.blob { null }
                }
            } catch (error: Throwable) {
                writeError = error
                pipe.sink().close()
            }
        }
        Channels.newInputStream(pipe.source()).buffered().use { input ->
            val reader = VaultArchive.open(input, password)
            assertEquals(big, reader.manifest)
            val expected = GeneratedStream(size, seed = 99)
            val blob = reader.nextBlob()
            val actualChunk = ByteArray(64 * 1024)
            val expectedChunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = blob.read(actualChunk)
                if (read < 0) break
                assertEquals(read, expected.read(expectedChunk, 0, read))
                if (ByteBuffer.wrap(actualChunk, 0, read) != ByteBuffer.wrap(expectedChunk, 0, read)) {
                    fail("Contenu différent après l'octet $total")
                }
                total += read
            }
            assertTrue(blob.complete)
            reader.finish()
            assertEquals(size, total)
        }
        writer.join()
        writeError?.let { throw it }
    }

    private fun archive(description: ArchiveManifest = manifest): ByteArray {
        val output = ByteArrayOutputStream()
        VaultArchive.write(output, password, description, iterations) { writer ->
            for ((media, thumbnail) in contents) {
                writer.blob { media.inputStream() }
                writer.blob { thumbnail?.inputStream() }
            }
        }
        return output.toByteArray()
    }

    /** Lit toute l'archive, fin comprise : contenu et intégrité de chaque blob. */
    private fun readAll(bytes: ByteArray): Pair<ArchiveManifest, List<Pair<ByteArray, Boolean>>> {
        val reader = VaultArchive.open(bytes.inputStream(), password)
        val blobs = List(reader.manifest.items.size * 2) {
            val blob = reader.nextBlob()
            blob.readBytes() to blob.complete
        }
        reader.finish()
        return reader.manifest to blobs
    }

    private fun assertReason(expected: Reason, context: String = "", block: () -> Unit) {
        try {
            block()
            fail("Archive acceptée à tort $context")
        } catch (error: VaultArchiveException) {
            assertEquals(context, expected, error.reason)
        }
    }

    private fun flipped(bytes: ByteArray, position: Int): ByteArray =
        bytes.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private fun entry(name: String, size: Long, video: Boolean = false, thumbnail: Boolean) = ArchiveEntry(
        name = name,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        video = video,
        originalPath = if (video) "Movies/" else "DCIM/Camera/",
        dateTaken = 1_700_000_000_000,
        addedAt = 1_750_000_000_000,
        size = size,
        width = 4_000,
        height = 3_000,
        durationMs = if (video) 12_345 else 0,
        hasThumbnail = thumbnail,
    )

    /**
     * Flux reproductible de [size] octets, produit à la demande : un motif aléatoire dont la
     * longueur, un nombre premier, ne coïncide ni avec les segments ni avec les morceaux. Un
     * octet déplacé, perdu ou dupliqué ne retombe donc jamais sur la bonne valeur par hasard.
     */
    private class GeneratedStream(private val size: Long, seed: Long) : InputStream() {
        private val pattern = Random(seed).nextBytes(PATTERN)
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val count = minOf(len.toLong(), size - position).toInt()
            var copied = 0
            while (copied < count) {
                val start = ((position + copied) % PATTERN).toInt()
                val run = minOf(count - copied, PATTERN - start)
                System.arraycopy(pattern, start, b, off + copied, run)
                copied += run
            }
            position += count
            return count
        }

        private companion object {
            const val PATTERN = 1_048_583
        }
    }

    private companion object {
        const val SEGMENT = 1 shl 20
        /** Archive de référence au format d'avant les albums, mot de passe « cheval correct agrafe ». */
        const val LEGACY_ARCHIVE = "/vault/sauvegarde-sans-albums.fvault"
        val VERSION_AT = VaultArchive.MAGIC.size
        val ITERATIONS_AT = VERSION_AT + 2
        val SALT_AT = ITERATIONS_AT + 5
        /** En-tête en clair : signature, version, dérivation, itérations, sel, chiffrement, témoin. */
        val HEADER = VaultArchive.MAGIC.size + 1 + 1 + 4 + 1 + VaultArchive.SALT_BYTES + 1 + VaultArchive.WITNESS_BYTES
    }
}
