package app.focale.panorama

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import app.focale.video.MotionPhotoFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Détection à l'ouverture : en-tête lu une fois par version du fichier, formats et types écartés. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanoramasTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    private fun item(file: File, id: Long, mimeType: String = "image/jpeg", dateModified: Long = 1, type: MediaType = MediaType.IMAGE, gif: Boolean = false) =
        MediaItem(
            id = id, uri = Uri.fromFile(file), type = type, mimeType = mimeType, displayName = file.name, relativePath = "DCIM/Camera/",
            bucketId = 1, bucketName = "Camera", dateTaken = 0, dateModified = dateModified, size = file.length(),
            width = 8704, height = 4352, orientation = 0, durationMs = 0, isGif = gif, isScreenshot = false,
        )

    @Test
    fun `photo sphere lue une fois, gardée en mémoire jusqu'à sa modification`() {
        val file = folder.newFile("PANO_20230714.jpg").apply { writeBytes(MotionPhotoFixtures.jpeg(xmp = PanoramaFixtures.photoSphereXmp)) }
        val first = Panoramas.detect(resolver, item(file, id = 1001))
        assertEquals(PanoramaKind.SPHERE, first?.kind)
        assertEquals(1001L, first?.mediaId)
        // Fichier disparu : le résultat connu sert encore pour la même version…
        file.delete()
        assertEquals(PanoramaKind.SPHERE, Panoramas.detect(resolver, item(file, id = 1001))?.kind)
        // …mais une nouvelle version est relue (illisible ici : rien).
        assertNull(Panoramas.detect(resolver, item(file, id = 1001, dateModified = 2)))
    }

    @Test
    fun `formats sans XMP lisible, vidéos et GIF, photo ordinaire`() {
        val sphere = folder.newFile("sphere.jpg").apply { writeBytes(MotionPhotoFixtures.jpeg(xmp = PanoramaFixtures.photoSphereXmp)) }
        // PNG : en-tête non lu, et 2:1 ne suffit pas sans GPano.
        assertNull(Panoramas.detect(resolver, item(sphere, id = 1002, mimeType = "image/png")))
        assertNull(Panoramas.detect(resolver, item(sphere, id = 1003, mimeType = "video/mp4", type = MediaType.VIDEO)))
        assertNull(Panoramas.detect(resolver, item(sphere, id = 1004, mimeType = "image/gif", gif = true)))
        val plain = folder.newFile("plain.jpg").apply { writeBytes(MotionPhotoFixtures.jpeg(xmp = PanoramaFixtures.plainXmp)) }
        assertNull(Panoramas.detect(resolver, item(plain, id = 1005)))
        val heif = folder.newFile("pano.heic").apply { writeBytes(PanoramaFixtures.heif(PanoramaFixtures.photoSphereXmp)) }
        assertEquals(PanoramaKind.SPHERE, Panoramas.detect(resolver, item(heif, id = 1006, mimeType = "image/heic"))?.kind)
    }
}
