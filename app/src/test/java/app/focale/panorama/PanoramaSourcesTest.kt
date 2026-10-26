package app.focale.panorama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.focale.data.ExternalMedia
import app.focale.vault.VaultCrypto
import app.focale.vault.VaultItem
import app.focale.vault.VaultPanoramas
import app.focale.vault.VaultStorage
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Choix de la vue pour chaque source hors de la galerie, sur de vrais fichiers : fichier reçu
 * d'une autre application (en-têtes lus par accès aléatoire, dimensions et orientation dans
 * l'en-tête de la photo) et élément du coffre (en-têtes lus dans le fichier chiffré, photo
 * déchiffrée en mémoire, jamais en clair sur le disque).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PanoramaSourcesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = PanoramaRepository(context.contentResolver, Dispatchers.Unconfined)

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/spherical/$name")) { name }.use { it.readBytes() }

    private fun received(name: String, bytes: ByteArray, mimeType: String): ExternalMedia {
        val file = folder.newFile(name).apply { writeBytes(bytes) }
        return ExternalMedia(Uri.fromFile(file), mimeType, name, file.length())
    }

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 200)) }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    @Test
    fun `photo 360° reçue d'une autre application, GPano et dimensions lus dans le fichier`() = runBlocking {
        val panorama = requireNotNull(repository.find(received("IMG_360.jpg", fixture("photo-pose.jpg"), "image/jpeg")))
        assertEquals(ExternalMedia.ID, panorama.mediaId)
        assertEquals(PanoramaKind.SPHERE, panorama.kind)
        assertEquals(SphereArea.FULL, panorama.area)
        assertEquals(SpherePose(12.0, -7.0), panorama.pose)
    }

    @Test
    fun `autres fichiers reçus, vidéos 360°, panorama sans GPano, médias ordinaires`() = runBlocking {
        val cube = repository.find(received("cube.mp4", fixture("v2-cubemap.mp4"), "video/mp4"))
        assertEquals(SphereProjection.Cubemap(0, 96, 64), cube?.projection)
        val mesh = repository.find(received("eac.mp4", fixture("v2-mesh-eac.mp4"), "video/mp4"))
        assertTrue(mesh?.projection is SphereProjection.Mesh)
        assertEquals(ExternalMedia.ID, mesh?.mediaId)
        assertNull(repository.find(received("plain.mp4", fixture("plain.mp4"), "video/mp4")))
        // Matroska : en-têtes pas lus.
        assertNull(repository.find(received("eac.mkv", fixture("v2-mesh-eac.mp4"), "video/x-matroska")))
        // Sans GPano : à plat dès 2,5:1, d'après les dimensions de l'en-tête ; une photo ordinaire, rien.
        assertEquals(PanoramaKind.FLAT, repository.find(received("wide.png", image(900, 300, Bitmap.CompressFormat.PNG), "image/png"))?.kind)
        assertNull(repository.find(received("photo.jpg", image(400, 300, Bitmap.CompressFormat.JPEG), "image/jpeg")))
        // Fichier disparu entre-temps : rien, sans erreur.
        val gone = received("gone.jpg", fixture("photo-pose.jpg"), "image/jpeg").also { File(requireNotNull(it.uri.path)).delete() }
        assertNull(repository.find(gone))
    }

    @Test
    fun `photo et vidéos du coffre lues chiffrées, photo déchiffrée en mémoire, rien en clair sur le disque`() = runBlocking {
        val crypto = VaultCrypto {
            StreamingAeadConfig.register()
            // Petits segments : l'en-tête d'une vidéo en traverse plusieurs.
            KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_4KB")).getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        }
        val storage = VaultStorage(context)
        val vault = VaultPanoramas(crypto, storage, Dispatchers.Unconfined)
        fun stored(id: Long, name: String, mimeType: String): VaultItem {
            val fileName = "item-$id.bin"
            javaClass.getResourceAsStream("/spherical/$name")!!.use { crypto.encrypt(it, storage.file(fileName)) }
            return VaultItem(
                id = id, displayName = name, mimeType = mimeType, isVideo = mimeType.startsWith("video/"), originalPath = "DCIM/Camera/",
                dateTaken = 0, size = 0, durationMs = 0, fileName = fileName, thumbFileName = null, addedAt = 0,
            )
        }
        val photo = stored(41, "photo-pose.jpg", "image/jpeg")
        val video = stored(42, "v2-mesh-eac.mp4", "video/mp4")
        val cube = stored(43, "v2-cubemap-padded-ffmpeg.mp4", "video/mp4")
        val plain = stored(44, "plain.mp4", "video/mp4")

        val sphere = requireNotNull(vault.find(photo))
        assertEquals(41L, sphere.mediaId)
        assertEquals(PanoramaKind.SPHERE, sphere.kind)
        assertEquals(SpherePose(12.0, -7.0), sphere.pose)
        assertTrue(vault.find(video)?.projection is SphereProjection.Mesh)
        val padded = requireNotNull(vault.find(cube))
        assertEquals(SphereProjection.Cubemap(4, 96, 64), padded.projection)
        assertEquals(SpherePose(10.0, -5.0), padded.pose)
        assertNull(vault.find(plain))
        // Fichier chiffré renommé (lié à son nom) : déchiffrement refusé, rien.
        storage.file(photo.fileName).copyTo(storage.file("renamed.bin"))
        assertNull(vault.find(photo.copy(fileName = "renamed.bin")))

        // Texture de la sphère : par bandes, puis entière (orientation EXIF), depuis le flux déchiffré.
        for (orientation in listOf(0, 90)) {
            val texture = requireNotNull(SphereTexture.decode(vault.photoStreams(photo), orientation, maxTextureSize = 512, maxPixels = 1L shl 25))
            if (orientation == 0) {
                assertEquals(256, texture.width)
                assertEquals(128, texture.height)
                // Centre de l'image : droit devant, couleur (128, 128, 1).
                val center = texture.getPixel(128, 64)
                assertTrue("centre $center", abs(Color.red(center) - 128) < 12 && abs(Color.green(center) - 128) < 12 && Color.blue(center) < 16)
            }
            texture.recycle()
        }

        // Rien d'autre sur le disque que les fichiers chiffrés : aucun ne contient le XMP de la photo.
        val signature = "http://ns.adobe.com/xap/1.0/".toByteArray()
        val written = context.dataDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(written.isNotEmpty())
        for (file in written) assertTrue(file.path, !contains(file.readBytes(), signature))
        assertEquals(setOf("item-41.bin", "item-42.bin", "item-43.bin", "item-44.bin", "renamed.bin"), storage.directory.listFiles()!!.map { it.name }.toSet())
    }

    private fun contains(data: ByteArray, pattern: ByteArray): Boolean =
        (0..data.size - pattern.size).any { start -> pattern.indices.all { data[start + it] == pattern[it] } }
}
