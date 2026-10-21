package app.focale.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaDao
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.ExternalSource
import app.focale.core.mediastore.ExternalUris
import app.focale.core.mediastore.MediaStoreLookup
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Fichier reçu d'une autre application, affiché hors index. */
@Immutable
data class ExternalMedia(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
    /** Taille en octets, 0 si inconnue. */
    val size: Long,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)

    /** Seule une URI `content://` se transmet à une autre application (partage, « Ouvrir avec »). */
    val canHandOver: Boolean get() = uri.scheme == ContentResolver.SCHEME_CONTENT

    /** Vue [MediaItem] du fichier, sans identifiant ni dossier, pour le partage (copie sans métadonnées). */
    fun asMediaItem(): MediaItem = MediaItem(
        id = NO_ID,
        uri = uri,
        type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
        mimeType = mimeType,
        displayName = displayName,
        relativePath = "",
        bucketId = 0,
        bucketName = "",
        dateTaken = 0,
        dateModified = 0,
        size = size,
        width = 0,
        height = 0,
        orientation = 0,
        durationMs = 0,
        isGif = mimeType.equals("image/gif", ignoreCase = true),
        isScreenshot = false,
    )

    private companion object {
        const val NO_ID = -1L
    }
}

/**
 * Médias ouverts depuis une autre application : correspondance d'une URI avec l'index (URI
 * MediaStore, document du stockage partagé, chemin `file://`), description des autres fichiers.
 */
@Singleton
class ExternalMediaRepository @Inject constructor(
    private val dao: MediaDao,
    private val lookup: MediaStoreLookup,
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Média de l'index désigné par [uri], ou null : fournisseur d'une autre application, média hors de
     * l'accès accordé ou à la corbeille. Un média visible dans MediaStore mais pas encore indexé (photo
     * qui vient d'être prise) est attendu le temps d'une synchronisation, au plus [ARRIVAL_TIMEOUT_MS].
     * Sans accès à la galerie, seule l'URI reçue, autorisée par l'intention, peut être lue.
     */
    suspend fun find(uri: Uri): MediaItem? = withContext(io) {
        if (!lookup.hasMediaAccess()) return@withContext null
        val id = mediaStoreId(uri) ?: return@withContext null
        val entity = dao.get(id) ?: if (lookup.isVisible(id)) {
            lookup.requestSync()
            withTimeoutOrNull(ARRIVAL_TIMEOUT_MS) { dao.observe(id).filterNotNull().first() }
        } else {
            null
        }
        entity?.takeUnless { it.isTrashed }?.toModel()
    }

    private fun mediaStoreId(uri: Uri): Long? = when (val source = ExternalUris.classify(uri)) {
        is ExternalSource.MediaStoreId -> source.id
        is ExternalSource.Document -> lookup.mediaUriOf(source.uri)?.let(ExternalUris::mediaStoreId)
        is ExternalSource.FilePath -> lookup.idOfPath(source.path)
        ExternalSource.Foreign -> null
    }

    /** Nom, taille et type de [uri] selon son fournisseur ; [declaredType] : celui de l'intention, s'il y en a un. */
    suspend fun describe(uri: Uri, declaredType: String?): ExternalMedia = withContext(io) {
        var name: String? = null
        var size = 0L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) name = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        ExternalMedia(uri, mimeTypeOf(uri, declaredType, displayName), displayName, size)
    }

    /** Type précis (« image/gif » plutôt qu'un type générique) : intention, fournisseur, puis extension du nom. */
    private fun mimeTypeOf(uri: Uri, declared: String?, displayName: String): String {
        val provided = if (uri.scheme == ContentResolver.SCHEME_CONTENT) runCatching { resolver.getType(uri) }.getOrNull() else null
        val fromName = MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase())
        val candidates = listOfNotNull(declared, provided, fromName).filter(::isMediaType)
        return candidates.firstOrNull { !it.endsWith("/*") } ?: candidates.firstOrNull() ?: DEFAULT_TYPE
    }

    private fun isMediaType(type: String): Boolean =
        type.startsWith("image/", ignoreCase = true) || type.startsWith("video/", ignoreCase = true)

    companion object {
        const val ARRIVAL_TIMEOUT_MS = 2_000L
        private const val DEFAULT_TYPE = "image/*"
    }
}
