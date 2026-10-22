package app.focale.panorama

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import java.io.File
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

/**
 * Métadonnées sphériques de vraies vidéos MP4 (encodées par FFmpeg, voir
 * `tools/spherical/make_fixtures.py`) : V1 (XMP GSpherical) et V2 (`st3d`, `sv3d`), `moov` au
 * début ou à la fin, écrites par l'outil d'injection ou par FFmpeg lui-même ; vidéo ordinaire,
 * fichiers tronqués ou mal formés ; choix du mode et détection mise en cache à l'ouverture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SphericalVideoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/spherical/$name")) { name }.use { it.readBytes() }

    /** Lectures demandées par l'analyse : (position, longueur). */
    private val reads = ArrayList<Pair<Long, Int>>()

    private fun metadata(bytes: ByteArray): SphericalMetadata? = SphericalVideoHeaders.read { offset, length ->
        reads += offset to length
        if (offset < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun metadata(name: String) = metadata(fixture(name))

    @Test
    fun `V1, XMP GSpherical dans la piste vidéo, moov en fin de fichier`() {
        val found = requireNotNull(metadata("v1-mono.mp4"))
        assertEquals(1, found.version)
        assertEquals(SphericalProjection.EQUIRECTANGULAR, found.projection)
        assertEquals(StereoMode.MONO, found.stereo)
        assertEquals(SphereArea.FULL, found.area)
        assertEquals(90.0, found.yaw!!, 1e-9)
        assertEquals(-10.0, found.pitch!!, 1e-9)

        val panorama = requireNotNull(SphericalVideoRules.classify(7, found))
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(7L, panorama.mediaId)
        assertEquals(90.0, panorama.initialYaw, 1e-9)
        assertEquals(-10.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `V1 stéréoscopique haut-bas et recadrée`() {
        val found = requireNotNull(metadata("v1-top-bottom.mp4"))
        assertEquals(StereoMode.TOP_BOTTOM, found.stereo)
        val area = requireNotNull(found.area)
        assertEquals(-90.0, area.west, 1e-9)
        assertEquals(90.0, area.east, 1e-9)
        assertEquals(45.0, area.north, 1e-9)
        assertEquals(-45.0, area.south, 1e-9)
        val panorama = requireNotNull(SphericalVideoRules.classify(8, found))
        assertEquals(StereoMode.TOP_BOTTOM, panorama.stereo)
        // Ni cap ni inclinaison : centre de l'image, à l'horizon.
        assertEquals(0.0, panorama.initialYaw, 1e-9)
        assertEquals(0.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `V1 dont Spherical vaut false, et vidéo sans métadonnées`() {
        assertNull(metadata("v1-not-spherical.mp4"))
        assertNull(metadata("plain.mp4"))
        assertNull(SphericalVideoRules.classify(1, null))
    }

    @Test
    fun `V2 injectée, moov au début, bornes et cap de la pose`() {
        val found = requireNotNull(metadata("v2-equi.mp4"))
        assertEquals(2, found.version)
        assertEquals(SphericalProjection.EQUIRECTANGULAR, found.projection)
        assertEquals(StereoMode.MONO, found.stereo)
        val area = requireNotNull(found.area)
        assertEquals(-180.0, area.west, 1e-6)
        assertEquals(180.0, area.east, 1e-6)
        assertEquals(60.0, area.north, 1e-6)
        assertEquals(-60.0, area.south, 1e-6)
        // Cap de 45° : la sphère tournée vers la droite, la vue part de 45° à gauche du centre.
        assertEquals(-45.0, found.yaw!!, 1e-9)
        val panorama = requireNotNull(SphericalVideoRules.classify(9, found))
        assertEquals(-45.0, panorama.initialYaw, 1e-9)
        assertEquals(0.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `V2 écrite par FFmpeg, stéréoscopique gauche-droite`() {
        val found = requireNotNull(metadata("v2-ffmpeg-left-right.mp4"))
        assertEquals(2, found.version)
        assertEquals(StereoMode.LEFT_RIGHT, found.stereo)
        assertEquals(SphereArea.FULL, found.area)
        assertEquals(StereoMode.LEFT_RIGHT, SphericalVideoRules.classify(10, found)?.stereo)
    }

    @Test
    fun `projection cubique, maillage ou inconnue, bornes incohérentes, pas de vue 360°`() {
        val cube = requireNotNull(metadata("v2-cubemap.mp4"))
        assertEquals(SphericalProjection.CUBEMAP, cube.projection)
        assertNull(SphericalVideoRules.classify(11, cube))
        for (projection in listOf(SphericalProjection.MESH, SphericalProjection.OTHER)) {
            assertNull(SphericalVideoRules.classify(12, SphericalMetadata(2, projection, StereoMode.MONO, SphereArea.FULL)))
        }
        assertNull(SphericalVideoRules.classify(13, SphericalMetadata(2, SphericalProjection.EQUIRECTANGULAR, StereoMode.MONO, area = null)))
    }

    @Test
    fun `choix de la vue initiale d'une sphère partielle`() {
        // VR180 vers le haut : l'horizon n'est pas couvert, la vue part du milieu de la zone ; cap ramené dans la zone.
        val area = SphereArea(west = -90.0, east = 90.0, north = 90.0, south = 10.0)
        val panorama = requireNotNull(SphericalVideoRules.classify(14, SphericalMetadata(2, SphericalProjection.EQUIRECTANGULAR, StereoMode.MONO, area, yaw = 150.0)))
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(90.0, panorama.initialYaw, 1e-9)
        assertEquals(50.0, panorama.initialPitch, 1e-9)
    }

    @Test
    fun `les données vidéo ne sont jamais lues, seulement des en-têtes`() {
        for (name in listOf("plain.mp4", "v1-mono.mp4", "v2-equi.mp4", "v2-ffmpeg-left-right.mp4")) {
            val bytes = fixture(name)
            val mdat = topLevel(bytes).first { it.first == "mdat" }
            reads.clear()
            metadata(bytes)
            // Seul l'en-tête de mdat est lu, pour le sauter.
            assertTrue(name, reads.none { (offset, _) -> offset > mdat.second && offset < mdat.third })
            assertTrue(name, reads.all { (_, length) -> length <= 1_024 })
        }
    }

    @Test
    fun `MP4 tronqué, tailles incohérentes ou autre format, rien et sans erreur`() {
        val v2 = fixture("v2-equi.mp4")
        val v1 = fixture("v1-mono.mp4")
        // Coupé au milieu des bornes (moov au début du fichier), dans le document V1 (moov à la fin),
        // ou juste après ftyp.
        assertNull(metadata(v2.copyOf(v2.indexOf("equi") + 10)))
        assertNull(metadata(v1.copyOf(v1.size - 300)))
        assertNull(metadata(v2.copyOf(40)))
        // sv3d qui déborde de son entrée : la lecture s'arrête là.
        assertNull(metadata(v2.copyOf().also { setSize(it, it.indexOf("sv3d") - 4, 0x7FFF_FFF0) }))
        // Taille plus petite que son en-tête.
        assertNull(metadata(v2.copyOf().also { setSize(it, it.indexOf("moov") - 4, 4) }))
        // Taille sur 64 bits démesurée.
        assertNull(metadata(v2.copyOf().also { setSize(it, it.indexOf("moov") - 4, 1) }))
        // Matroska, fichier vide, octets quelconques.
        assertNull(metadata(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 0x01, 0x42)))
        assertNull(metadata(ByteArray(0)))
        assertNull(metadata(ByteArray(4_096) { (it * 31).toByte() }))
        // L'original, lui, se lit toujours.
        assertNotNull(metadata(v2))
    }

    @Test
    fun `détection à l'ouverture, en mémoire jusqu'à la modification de la vidéo`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val file = folder.newFile("VID_360.mp4").apply { writeBytes(fixture("v1-mono.mp4")) }
        val first = SphericalVideos.detect(resolver, video(file, id = 2001))
        assertEquals(PanoramaKind.SPHERE, first?.kind)
        file.delete()
        assertEquals(PanoramaKind.SPHERE, SphericalVideos.detect(resolver, video(file, id = 2001))?.kind)
        assertNull(SphericalVideos.detect(resolver, video(file, id = 2001, dateModified = 2)))
        // Matroska : pas lu ; photo : pas une vidéo.
        val again = folder.newFile("VID_360.mkv").apply { writeBytes(fixture("v1-mono.mp4")) }
        assertNull(SphericalVideos.detect(resolver, video(again, id = 2002, mimeType = "video/x-matroska")))
        assertTrue(!SphericalVideos.isCandidate(video(again, id = 2003).copy(type = MediaType.IMAGE, mimeType = "image/jpeg")))
    }

    @Test
    fun `seule l'image de l'œil gauche va sur la sphère`() {
        fun corner(stereo: StereoMode, u: Float, v: Float): Pair<Float, Float> =
            Mat4.transform(VideoFrame.matrix(stereo), u, v, 0f).let { it[0] to it[1] }
        // Origine de la texture en bas à gauche : le haut du maillage (v = 0) est en t = 1.
        assertEquals(0f to 1f, corner(StereoMode.MONO, 0f, 0f))
        assertEquals(1f to 0f, corner(StereoMode.MONO, 1f, 1f))
        assertEquals(1f to 0.5f, corner(StereoMode.TOP_BOTTOM, 1f, 1f))
        assertEquals(0f to 1f, corner(StereoMode.TOP_BOTTOM, 0f, 0f))
        assertEquals(0.5f to 0f, corner(StereoMode.LEFT_RIGHT, 1f, 1f))
    }

    private fun video(file: File, id: Long, mimeType: String = "video/mp4", dateModified: Long = 1) = MediaItem(
        id = id, uri = Uri.fromFile(file), type = MediaType.VIDEO, mimeType = mimeType, displayName = file.name, relativePath = "DCIM/Camera/",
        bucketId = 1, bucketName = "Camera", dateTaken = 0, dateModified = dateModified, size = file.length(),
        width = 64, height = 32, orientation = 0, durationMs = 300, isGif = false, isScreenshot = false,
    )

    /** Boîtes de premier niveau : (type, début, fin). */
    private fun topLevel(bytes: ByteArray): List<Triple<String, Long, Long>> = buildList {
        var position = 0
        while (position + 8 <= bytes.size) {
            val size = u32(bytes, position)
            add(Triple(String(bytes, position + 4, 4, Charsets.ISO_8859_1), position.toLong(), position + size))
            position += size.toInt()
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    private fun setSize(bytes: ByteArray, at: Int, size: Long) {
        for (index in 0 until 4) bytes[at + index] = (size shr (24 - 8 * index)).toByte()
        if (size == 1L) {
            // Taille 64 bits annoncée : les 8 octets suivants (le début du contenu) la donnent, ici énorme.
            for (index in 8 until 16) bytes[at + index] = 0x7F
        }
    }

    private fun ByteArray.indexOf(type: String): Int {
        val pattern = type.toByteArray(Charsets.ISO_8859_1)
        for (start in 0..size - pattern.size) {
            if ((pattern.indices).all { this[start + it] == pattern[it] }) return start
        }
        error("$type absent")
    }
}
