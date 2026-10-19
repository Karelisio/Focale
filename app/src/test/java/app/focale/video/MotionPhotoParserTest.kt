package app.focale.video

import app.focale.video.MotionPhotoFixtures.Item
import java.io.File
import java.nio.channels.FileChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Détection des photos animées sur des JPEG synthétiques (JPEG + XMP + MP4 accolé). */
class MotionPhotoParserTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun parse(vararg parts: ByteArray): MotionPhotoVideo? {
        val file: File = folder.newFile()
        file.writeBytes(parts.fold(ByteArray(0)) { all, part -> all + part })
        return FileChannel.open(file.toPath()).use { MotionPhotoParser.parse(it) }
    }

    private fun MotionPhotoVideo?.found(): MotionPhotoVideo {
        assertNotNull("photo animée non détectée", this)
        return this!!
    }

    @Test
    fun `Motion Photo Google - la vidéo est le dernier élément du répertoire`() {
        val video = MotionPhotoFixtures.mp4()
        val jpeg = MotionPhotoFixtures.jpeg(
            xmp = MotionPhotoFixtures.googleXmp(listOf(Item("video/mp4", "MotionPhoto", video.size.toLong()))),
            exifSize = 20_000,
        )

        val result = parse(jpeg, video).found()

        assertEquals(MotionPhotoFormat.MOTION_PHOTO, result.format)
        assertEquals(jpeg.size.toLong(), result.offset)
        assertEquals(video.size.toLong(), result.length)
        assertEquals(video.size.toLong(), result.offsetFromEnd)
        assertEquals(968_653L, result.presentationTimestampUs)
    }

    @Test
    fun `préfixe Camera et propriétés en éléments`() {
        val video = MotionPhotoFixtures.mp4(payload = 5_000)
        val jpeg = MotionPhotoFixtures.jpeg(xmp = MotionPhotoFixtures.cameraElementXmp(video.size.toLong()))

        val result = parse(jpeg, video).found()

        assertEquals(jpeg.size.toLong(), result.offset)
        assertEquals(video.size.toLong(), result.length)
        // -1 : instant de la photo non précisé.
        assertNull(result.presentationTimestampUs)
    }

    @Test
    fun `Ultra HDR - la gainmap précède la vidéo`() {
        val gainmap = MotionPhotoFixtures.jpeg()
        val video = MotionPhotoFixtures.mp4()
        val jpeg = MotionPhotoFixtures.jpeg(
            xmp = MotionPhotoFixtures.googleXmp(
                listOf(
                    Item("image/jpeg", "GainMap", gainmap.size.toLong()),
                    Item("video/mp4", "MotionPhoto", video.size.toLong()),
                ),
            ),
        )

        val result = parse(jpeg, gainmap, video).found()

        assertEquals((jpeg.size + gainmap.size).toLong(), result.offset)
        assertEquals(video.size.toLong(), result.offsetFromEnd)
    }

    @Test
    fun `un élément qui suit la vidéo est compté depuis la fin du fichier`() {
        val video = MotionPhotoFixtures.mp4()
        val gainmap = MotionPhotoFixtures.jpeg()
        val jpeg = MotionPhotoFixtures.jpeg(
            xmp = MotionPhotoFixtures.googleXmp(
                listOf(
                    Item("video/mp4", "MotionPhoto", video.size.toLong()),
                    Item("image/jpeg", "GainMap", gainmap.size.toLong()),
                ),
            ),
        )

        val result = parse(jpeg, video, gainmap).found()

        assertEquals(jpeg.size.toLong(), result.offset)
        assertEquals((video.size + gainmap.size).toLong(), result.offsetFromEnd)
        assertEquals(video.size.toLong(), result.length)
    }

    @Test
    fun `un remplissage après la vidéo est pris en compte`() {
        val video = MotionPhotoFixtures.mp4()
        val padding = ByteArray(64)
        val jpeg = MotionPhotoFixtures.jpeg(
            xmp = MotionPhotoFixtures.googleXmp(listOf(Item("video/mp4", "MotionPhoto", video.size.toLong(), padding = 64))),
        )

        val result = parse(jpeg, video, padding).found()

        assertEquals(jpeg.size.toLong(), result.offset)
        assertEquals(video.size.toLong() + 64, result.offsetFromEnd)
    }

    @Test
    fun `ancien format MicroVideo - décalage depuis la fin du fichier`() {
        val video = MotionPhotoFixtures.mp4(payload = 3_000)
        val jpeg = MotionPhotoFixtures.jpeg(xmp = MotionPhotoFixtures.microVideoXmp(video.size.toLong()), fillByte = true)

        val result = parse(jpeg, video).found()

        assertEquals(MotionPhotoFormat.MICRO_VIDEO, result.format)
        assertEquals(jpeg.size.toLong(), result.offset)
        assertEquals(video.size.toLong(), result.offsetFromEnd)
        assertEquals(1_342_287L, result.presentationTimestampUs)
    }

    @Test
    fun `Samsung - bloc MotionPhoto_Data répertorié par l'en-tête SEF`() {
        val video = MotionPhotoFixtures.mp4(payload = 4_000)
        val jpeg = MotionPhotoFixtures.jpeg(xmp = MotionPhotoFixtures.plainXmp())
        val (trailer, expectedOffset) = MotionPhotoFixtures.samsungTrailer(jpeg.size.toLong(), video)

        val result = parse(jpeg, trailer).found()

        assertEquals(MotionPhotoFormat.SAMSUNG, result.format)
        assertEquals(expectedOffset, result.offset)
        assertEquals(video.size.toLong(), result.length)
        assertTrue(result.offsetFromEnd > video.size)
    }

    @Test
    fun `une photo ordinaire n'est pas animée`() {
        assertNull(parse(MotionPhotoFixtures.jpeg(xmp = MotionPhotoFixtures.plainXmp())))
        assertNull(parse(MotionPhotoFixtures.jpeg()))
    }

    @Test
    fun `une vidéo ajoutée sans description n'est pas retenue`() {
        assertNull(parse(MotionPhotoFixtures.jpeg(xmp = MotionPhotoFixtures.plainXmp()), MotionPhotoFixtures.mp4()))
    }

    @Test
    fun `une longueur qui ne désigne pas un MP4 est écartée`() {
        val video = MotionPhotoFixtures.mp4()
        val jpeg = MotionPhotoFixtures.jpeg(
            xmp = MotionPhotoFixtures.googleXmp(listOf(Item("video/mp4", "MotionPhoto", video.size.toLong() + 10))),
        )

        assertNull(parse(jpeg, video))
    }

    @Test
    fun `un mouvement désactivé est respecté`() {
        val video = MotionPhotoFixtures.mp4()
        val jpeg = MotionPhotoFixtures.jpeg(
            xmp = MotionPhotoFixtures.googleXmp(listOf(Item("video/mp4", "MotionPhoto", video.size.toLong())), flag = "0"),
        )

        assertNull(parse(jpeg, video))
    }

    @Test
    fun `un fichier qui n'est pas un JPEG est ignoré`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200)
        assertNull(parse(png, MotionPhotoFixtures.mp4()))
        assertNull(parse(ByteArray(10)))
    }

    @Test
    fun `positions candidates depuis la fin du fichier`() {
        val items = listOf(
            ContainerItem("image/jpeg", "Primary", 0, 0),
            ContainerItem("video/mp4", "MotionPhoto", 1_000, 8),
            ContainerItem("image/jpeg", "GainMap", 300, 0),
        )

        assertEquals(
            listOf(10_000L - 1_300 to 1_000L, 10_000L - 1_308 to 1_000L),
            MotionPhotoParser.containerCandidates(items, 10_000),
        )
        assertTrue(MotionPhotoParser.containerCandidates(items.take(1), 10_000).isEmpty())
    }
}
