package app.focale.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import app.focale.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Routage des intentions reçues par `MainActivity.handleIntent` (et au lancement). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenRequestTest {

    private val own = "app.focale"
    private val photo = Uri.parse("content://media/external/images/media/42")

    private fun view(uri: Uri, type: String? = "image/jpeg", action: String = Intent.ACTION_VIEW) =
        Intent(action).setDataAndType(uri, type)

    private fun route(intent: Intent?, launched: Boolean = true) = OpenRequest.of(intent, launched, own)

    @Test
    fun `le lanceur ramène à la galerie`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertEquals(OpenRequest.Home, route(launcher, launched = false))
        assertNull(route(Intent(Intent.ACTION_MAIN)))
        assertNull(route(null))
    }

    @Test
    fun `le widget ouvre son média dans la timeline`() {
        val widget = Intent(MainActivity.ACTION_OPEN_MEDIA).putExtra(MainActivity.EXTRA_MEDIA_ID, 12L)

        assertEquals(OpenRequest.Media(12), route(widget))
        assertNull(route(Intent(MainActivity.ACTION_OPEN_MEDIA)))
    }

    @Test
    fun `une photo d'une autre application ferme l'activité lancée pour elle`() {
        val request = route(view(photo), launched = true) as OpenRequest.External

        assertEquals(OpenRequest.External(photo, "image/jpeg", closeOnBack = true), request)
        assertEquals(ExternalMediaRoute(photo.toString(), "image/jpeg", closeOnBack = true), request.route)
    }

    @Test
    fun `reçue par l'activité déjà ouverte, elle s'ouvre par-dessus l'écran courant`() {
        assertEquals(OpenRequest.External(photo, "image/jpeg", closeOnBack = false), route(view(photo), launched = false))
    }

    @Test
    fun `les revues de l'appareil photo et les vidéos sont acceptées`() {
        val video = Uri.parse("content://media/external/video/media/7")

        assertEquals(OpenRequest.External(photo, "image/jpeg", true), route(view(photo, action = MediaStore.ACTION_REVIEW)))
        assertEquals(OpenRequest.External(photo, "image/jpeg", true), route(view(photo, action = "com.android.camera.action.REVIEW")))
        assertEquals(OpenRequest.External(video, "video/mp4", true), route(view(video, type = "video/mp4")))
        // Type résolu par le système d'après le fournisseur, absent de l'intention.
        assertEquals(OpenRequest.External(photo, null, true), route(Intent(Intent.ACTION_VIEW, photo)))
    }

    @Test
    fun `un fichier du stockage partagé est accepté`() {
        val file = Uri.parse("file:///storage/emulated/0/DCIM/Camera/IMG_1.jpg")

        assertEquals(OpenRequest.External(file, "image/jpeg", true), route(view(file)))
    }

    @Test
    fun `les intentions sans média valable sont ignorées`() {
        assertNull(route(Intent(Intent.ACTION_VIEW)))
        assertNull(route(view(photo, type = "application/pdf")))
        assertNull(route(view(Uri.parse("https://example.com/photo.jpg"))))
        assertNull(route(view(photo, action = "android.provider.action.REVIEW_SECURE")))
    }

    @Test
    fun `les fichiers de Focale ne lui sont jamais redemandés`() {
        assertNull(route(view(Uri.parse("content://app.focale.share/shared/1/IMG_1.jpg"))))
        assertNull(route(view(Uri.parse("content://app.focale/media/1"))))
        assertNull(route(view(Uri.parse("file:///data/data/app.focale/databases/focale.db"))))
        assertNull(route(view(Uri.parse("file:///storage/emulated/0/../../data/data/app.focale/files/x.jpg"))))
    }
}
