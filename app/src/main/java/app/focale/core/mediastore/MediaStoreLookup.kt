package app.focale.core.mediastore

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Appels à MediaStore pour retrouver un média reçu d'une autre application, derrière une interface pour les tests. */
interface MediaStoreLookup {
    /** Vrai si Focale peut lire la galerie (accès complet ou sélection) ; sinon l'index peut être périmé. */
    fun hasMediaAccess(): Boolean

    /** URI MediaStore d'un document du stockage partagé (`MediaStore.getMediaUri`), null sans équivalent. */
    fun mediaUriOf(document: Uri): Uri?

    /** Identifiant de l'image ou de la vidéo de chemin [path], si MediaStore la connaît. */
    fun idOfPath(path: String): Long?

    /** Vrai si le média [id] est visible avec l'accès accordé à Focale (complet, ou sélection). */
    fun isVisible(id: Long): Boolean

    /** Lance une synchronisation de l'index (média qui vient d'arriver). */
    fun requestSync()
}

@Singleton
class AndroidMediaStoreLookup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: MediaStoreSource,
    private val syncManager: MediaSyncManager,
    private val accessManager: MediaAccessManager,
) : MediaStoreLookup {

    override fun hasMediaAccess(): Boolean {
        accessManager.refresh()
        return accessManager.access.value != MediaAccess.DENIED
    }

    // Permission refusée, fournisseur sans conversion (téléchargements…) : pas d'équivalent.
    override fun mediaUriOf(document: Uri): Uri? = runCatching { MediaStore.getMediaUri(context, document) }.getOrNull()

    // MediaStore enregistre le chemin réel (`/storage/emulated/0/…`), pas un lien comme `/sdcard`.
    override fun idOfPath(path: String): Long? {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        return runCatching { source.idOfPath(listOf(path, canonical).distinct()) }.getOrNull()
    }

    override fun isVisible(id: Long): Boolean = runCatching { source.isVisible(id) }.getOrDefault(false)

    override fun requestSync() = syncManager.requestSync()
}

@Module
@InstallIn(SingletonComponent::class)
object MediaStoreLookupModule {

    @Provides
    fun mediaStoreLookup(lookup: AndroidMediaStoreLookup): MediaStoreLookup = lookup
}
