package app.focale.core.mediastore

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalUrisTest {

    private fun classify(uri: String) = ExternalUris.classify(Uri.parse(uri))

    @Test
    fun `les URI MediaStore donnent l'identifiant de la table Files`() {
        assertEquals(ExternalSource.MediaStoreId(42), classify("content://media/external/images/media/42"))
        assertEquals(ExternalSource.MediaStoreId(7), classify("content://media/external_primary/video/media/7"))
        assertEquals(ExternalSource.MediaStoreId(9), classify("content://media/3a1b-0f2c/file/9"))
        assertEquals(ExternalSource.MediaStoreId(5), classify("content://media/external/downloads/5"))
    }

    @Test
    fun `les autres URI MediaStore ne désignent pas un média de l'index`() {
        // Sélecteur de photos : l'identifiant peut être celui d'un média du cloud.
        assertEquals(ExternalSource.Foreign, classify("content://media/picker/0/com.android.providers.media.photopicker/media/42"))
        assertEquals(ExternalSource.Foreign, classify("content://media/external/images/media"))
        assertEquals(ExternalSource.Foreign, classify("content://media/external/images/thumbnails/42"))
        assertEquals(ExternalSource.Foreign, classify("content://media/external/images/media/abc"))
    }

    @Test
    fun `les documents du stockage partagé passent par MediaStore`() {
        val storage = "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_1.jpg"
        val media = "content://com.android.providers.media.documents/document/image%3A42"
        assertEquals(ExternalSource.Document(Uri.parse(storage)), classify(storage))
        assertEquals(ExternalSource.Document(Uri.parse(media)), classify(media))
    }

    @Test
    fun `les téléchargements sont lus d'après leur identifiant de document`() {
        val downloads = "content://com.android.providers.downloads.documents/document/"
        assertEquals(ExternalSource.MediaStoreId(1000000123), classify(downloads + "msf%3A1000000123"))
        assertEquals(
            ExternalSource.FilePath("/storage/emulated/0/Download/IMG_2.jpg"),
            classify(downloads + "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FIMG_2.jpg"),
        )
        assertEquals(ExternalSource.Document(Uri.parse(downloads + "31")), classify(downloads + "31"))
        assertEquals(ExternalSource.Foreign, classify("content://com.android.providers.downloads.documents/root/downloads"))
    }

    @Test
    fun `un chemin file est retrouvé par sa colonne DATA`() {
        assertEquals(ExternalSource.FilePath("/storage/emulated/0/DCIM/Camera/IMG_3.jpg"), classify("file:///storage/emulated/0/DCIM/Camera/IMG_3.jpg"))
    }

    @Test
    fun `un fournisseur inconnu reste hors index`() {
        assertEquals(ExternalSource.Foreign, classify("content://com.whatsapp.provider.media/item/123"))
        assertEquals(ExternalSource.Foreign, classify("content://org.telegram.messenger.provider/media/photo.jpg"))
        assertEquals(ExternalSource.Foreign, classify("https://example.com/photo.jpg"))
    }

    @Test
    fun `mediaStoreId ignore les autres fournisseurs`() {
        assertEquals(42L, ExternalUris.mediaStoreId(Uri.parse("content://media/external/images/media/42")))
        assertNull(ExternalUris.mediaStoreId(Uri.parse("content://com.example.media/external/images/media/42")))
        assertNull(ExternalUris.mediaStoreId(Uri.parse("file:///storage/emulated/0/DCIM/42")))
    }
}
