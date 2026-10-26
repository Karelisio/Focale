package app.focale.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import app.focale.core.settings.AppSettings
import app.focale.data.ExternalMedia
import app.focale.panorama.Panorama
import app.focale.panorama.PanoramaKind
import app.focale.panorama.SphereArea
import app.focale.ui.theme.PhotoTheme
import app.focale.ui.vault.SecureWindow
import app.focale.ui.vault.VaultViewerPages
import app.focale.vault.VaultItem
import me.saket.telephoto.zoomable.ZoomableImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Boutons 360° hors de la galerie, en français : visionneuse d'un fichier reçu d'une autre
 * application et visionneuse du coffre, photo et vidéo ; la vue immersive s'ouvre à la place de
 * l'habillage et se referme au retour ; dans le coffre, la fenêtre et la surface OpenGL restent
 * exclues des captures. (OpenGL n'est pas rendu par Robolectric : la sphère reste en chargement.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w400dp-h800dp")
class PanoramaSourcesUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sphere(mediaId: Long) =
        Panorama(mediaId = mediaId, kind = PanoramaKind.SPHERE, area = SphereArea.FULL, initialYaw = 0.0, initialPitch = 0.0)

    private fun player(context: Context) = FakePlayer(positionMs = 0, durationMs = 60_000)

    private fun external(name: String, mimeType: String, panorama: Panorama?) {
        val media = ExternalMedia(Uri.parse("content://com.example.chat/media/$name"), mimeType, name, size = 2_048)
        compose.setContent {
            PhotoTheme(seed = null, forceDark = true) {
                StandaloneViewer(media, AppSettings(), panorama, onBack = {}, onShare = {}, onOpenWith = {}, playerFactory = ::player)
            }
        }
    }

    @Test
    fun `photo 360° reçue d'une autre application, bouton, vue immersive, retour`() {
        external("IMG_360.jpg", "image/jpeg", sphere(ExternalMedia.ID))
        compose.onNodeWithContentDescription("Partager").assertIsDisplayed()
        compose.onNodeWithText("360°").assertIsDisplayed().performClick()
        compose.onNodeWithText("Photo 360°").assertIsDisplayed()
        compose.onNodeWithText("Vue initiale").assertIsDisplayed()
        // L'habillage de la visionneuse laisse la place à celui de la vue.
        compose.onNodeWithText("IMG_360.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Partager").assertDoesNotExist()
        assertTrue(surfaces().isNotEmpty())
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Photo 360°").assertDoesNotExist()
        compose.onNodeWithText("360°").assertIsDisplayed()
        compose.onNodeWithText("IMG_360.jpg").assertIsDisplayed()
    }

    @Test
    fun `vidéo 360° reçue d'une autre application, le lecteur dessine dans la sphère`() {
        external("VID_360.mp4", "video/mp4", sphere(ExternalMedia.ID))
        compose.onNodeWithText("360°").assertIsDisplayed().performClick()
        compose.onNodeWithText("Vidéo 360°").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vidéo 360°. Faites glisser pour regarder autour, pincez pour zoomer").assertExists()
        compose.onNodeWithText("Vue initiale").assertIsDisplayed()
        assertTrue(surfaces().isNotEmpty())
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Vidéo 360°").assertDoesNotExist()
        compose.onNodeWithText("VID_360.mp4").assertIsDisplayed()
    }

    @Test
    fun `fichier reçu, panorama à plat ou média ordinaire`() {
        var panorama by mutableStateOf<Panorama?>(sphere(ExternalMedia.ID).copy(kind = PanoramaKind.FLAT))
        val media = ExternalMedia(Uri.parse("content://com.example.chat/media/PANO.jpg"), "image/jpeg", "PANO.jpg", size = 2_048)
        compose.setContent {
            PhotoTheme(seed = null, forceDark = true) {
                StandaloneViewer(media, AppSettings(), panorama, onBack = {}, onShare = {}, onOpenWith = {})
            }
        }
        compose.onNodeWithText("Vue panoramique").assertIsDisplayed()
        panorama = null
        compose.waitForIdle()
        compose.onNodeWithText("Vue panoramique").assertDoesNotExist()
        compose.onNodeWithText("360°").assertDoesNotExist()
    }

    private fun vaultItem(id: Long, video: Boolean) = VaultItem(
        id = id, displayName = if (video) "VID_$id.mp4" else "IMG_$id.jpg", mimeType = if (video) "video/mp4" else "image/jpeg",
        isVideo = video, originalPath = "DCIM/Camera/", dateTaken = 1_760_000_000_000, size = 4_096, durationMs = if (video) 60_000 else 0,
        fileName = "item-$id.bin", thumbFileName = null, addedAt = 0,
    )

    /** Photo du coffre factice : rien à décoder (OpenGL ne tourne pas), une image toute faite à plat. */
    private object FakeVaultPhoto : PanoramaPhoto {
        override val key: Any = "vault"
        override val contentDescription: String? = null
        override fun texture(context: Context, maxTextureSize: Int, maxPixels: Long): Bitmap? = null

        @Composable
        override fun flatImage(): ZoomableImageSource = remember { PainterImageSource(fakePanoramaPainter()) }
    }

    private fun vault(items: List<VaultItem>, startId: Long, panorama: Panorama?) {
        compose.setContent {
            SecureWindow()
            PhotoTheme(seed = null, forceDark = true) {
                VaultViewerPages(
                    items = items,
                    startId = startId,
                    settings = AppSettings(),
                    busy = false,
                    panorama = panorama,
                    snackbarHostState = remember { SnackbarHostState() },
                    onPageChanged = {},
                    onRestore = {},
                    onDelete = {},
                    mediaItemFor = { MediaItem.fromUri("file:///vault/${it.fileName}") },
                    playerFactory = ::player,
                    panoramaPhoto = { FakeVaultPhoto },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `photo 360° du coffre, vue immersive exclue des captures`() {
        vault(listOf(vaultItem(1, video = false), vaultItem(2, video = true)), startId = 1, panorama = sphere(1))
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("360°").assertIsDisplayed().performClick()
        compose.onNodeWithText("Photo 360°").assertIsDisplayed()
        compose.onNodeWithText("Restaurer").assertDoesNotExist()
        assertSecure()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Photo 360°").assertDoesNotExist()
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
    }

    @Test
    fun `vidéo 360° du coffre, lue par son lecteur dans la sphère, exclue des captures`() {
        vault(listOf(vaultItem(1, video = false), vaultItem(2, video = true)), startId = 2, panorama = sphere(2))
        compose.onNodeWithText("360°").assertIsDisplayed().performClick()
        compose.onNodeWithText("Vidéo 360°").assertIsDisplayed()
        compose.onNodeWithText("Vue initiale").assertIsDisplayed()
        assertSecure()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Vidéo 360°").assertDoesNotExist()
        compose.onNodeWithText("360°").assertIsDisplayed()
    }

    @Test
    fun `coffre, pas de bouton pour un autre élément que celui affiché`() {
        vault(listOf(vaultItem(1, video = false), vaultItem(2, video = true)), startId = 1, panorama = sphere(2))
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("360°").assertDoesNotExist()
    }

    /** Fenêtre du coffre et surfaces OpenGL de la vue immersive exclues des captures. */
    private fun assertSecure() {
        val window = compose.activity.window
        assertTrue((window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
        val surfaces = surfaces()
        assertEquals(1, surfaces.size)
        // SurfaceView.setSecure : drapeau SurfaceControl.SECURE (0x80) de la surface à créer.
        val flags = SurfaceView::class.java.getDeclaredField("mSurfaceFlags").apply { isAccessible = true }
        assertTrue(surfaces.all { (flags.getInt(it) and SECURE) != 0 })
    }

    private fun surfaces(): List<GLSurfaceView> {
        compose.waitForIdle()
        val found = ArrayList<GLSurfaceView>()
        fun visit(view: View) {
            if (view is GLSurfaceView) found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(compose.activity.window.decorView)
        return found
    }

    private companion object {
        const val SECURE = 0x80
    }
}
