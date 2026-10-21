package app.focale

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Filtres d'intentions du manifeste : ce qu'Android peut confier à `MainActivity`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentFiltersTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photo = Uri.parse("content://media/external/images/media/42")

    /** [flags] : `MATCH_DEFAULT_ONLY` comme `startActivity` ; le lanceur, lui, n'exige pas la catégorie DEFAULT. */
    private fun opensInFocale(intent: Intent, flags: Int = PackageManager.MATCH_DEFAULT_ONLY): Boolean =
        context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), flags)
            .any { it.activityInfo.name == MainActivity::class.java.name }

    @Test
    fun `le lanceur ouvre toujours Focale`() {
        assertTrue(opensInFocale(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags = 0))
    }

    @Test
    fun `photos et vidéos des autres applications`() {
        assertTrue(opensInFocale(Intent(Intent.ACTION_VIEW).setDataAndType(photo, "image/jpeg")))
        assertTrue(opensInFocale(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.whatsapp.provider.media/item/1"), "image/webp")))
        assertTrue(opensInFocale(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/7"), "video/mp4")))
        assertTrue(opensInFocale(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///storage/emulated/0/DCIM/IMG_1.jpg"), "image/jpeg")))
    }

    @Test
    fun `revue des prises de vue de l'appareil photo`() {
        assertTrue(opensInFocale(Intent(MediaStore.ACTION_REVIEW).setDataAndType(photo, "image/jpeg")))
        assertTrue(opensInFocale(Intent("com.android.camera.action.REVIEW").setDataAndType(photo, "image/jpeg")))
        // Écran verrouillé : jamais l'activité unique, qui donne accès à toute la galerie.
        assertFalse(opensInFocale(Intent(MediaStore.ACTION_REVIEW_SECURE).setDataAndType(photo, "image/jpeg")))
    }

    @Test
    fun `ni documents ni liens web`() {
        assertFalse(opensInFocale(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://downloads/1"), "application/pdf")))
        assertFalse(opensInFocale(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/photo.jpg"))))
    }
}
