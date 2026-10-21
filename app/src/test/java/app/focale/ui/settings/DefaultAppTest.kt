package app.focale.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAppTest {

    private val own = "app.focale"
    private val candidates = listOf(own, "com.google.android.apps.photos", "com.simplemobiletools.gallery")
    private val labels = mapOf("com.google.android.apps.photos" to "Photos")

    private fun state(resolved: String?, candidates: List<String> = this.candidates) =
        PhotoViewerDefault.of(resolved, candidates, own) { labels[it] ?: it }

    @Test
    fun `Focale retenu par défaut`() {
        assertEquals(PhotoViewerDefault.Focale, state(own))
        // Seule application capable d'ouvrir les photos : Android l'ouvre sans demander.
        assertEquals(PhotoViewerDefault.Focale, state(own, candidates = listOf(own)))
    }

    @Test
    fun `le sélecteur du système signifie aucune valeur par défaut`() {
        assertEquals(PhotoViewerDefault.None, state("android"))
        assertEquals(PhotoViewerDefault.None, state("com.android.intentresolver"))
        assertEquals(PhotoViewerDefault.None, state(null))
    }

    @Test
    fun `une autre application retenue est nommée`() {
        assertEquals(PhotoViewerDefault.Other("com.google.android.apps.photos", "Photos"), state("com.google.android.apps.photos"))
        assertEquals(
            PhotoViewerDefault.Other("com.simplemobiletools.gallery", "com.simplemobiletools.gallery"),
            state("com.simplemobiletools.gallery"),
        )
    }

    @Test
    fun `rien à faire quand Focale ouvre déjà les photos`() {
        assertEquals(DefaultAppAction.Done, DefaultAppAction.of(PhotoViewerDefault.Focale, hasPhoto = true))
        assertEquals(DefaultAppAction.Done, DefaultAppAction.of(PhotoViewerDefault.Focale, hasPhoto = false))
    }

    @Test
    fun `sans valeur par défaut, une photo est ouverte pour proposer Toujours`() {
        assertEquals(DefaultAppAction.OpenPhoto, DefaultAppAction.of(PhotoViewerDefault.None, hasPhoto = true))
        // Bibliothèque vide : il n'y a rien à ouvrir, seule la marche à suivre est donnée.
        assertEquals(DefaultAppAction.Explain, DefaultAppAction.of(PhotoViewerDefault.None, hasPhoto = false))
    }

    @Test
    fun `une autre application doit d'abord perdre ses valeurs par défaut`() {
        val other = PhotoViewerDefault.Other("com.google.android.apps.photos", "Photos")

        assertEquals(DefaultAppAction.ClearDefaults("com.google.android.apps.photos", "Photos"), DefaultAppAction.of(other, hasPhoto = true))
        assertEquals(DefaultAppAction.ClearDefaults("com.google.android.apps.photos", "Photos"), DefaultAppAction.of(other, hasPhoto = false))
    }
}
