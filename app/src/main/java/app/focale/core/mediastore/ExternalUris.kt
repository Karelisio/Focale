package app.focale.core.mediastore

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/** Provenance d'une URI reçue d'une autre application (« Ouvrir avec », appareil photo…). */
sealed interface ExternalSource {
    /** Média MediaStore, par son identifiant dans la table Files. */
    data class MediaStoreId(val id: Long) : ExternalSource

    /** Document du stockage partagé, converti en URI MediaStore par `MediaStore.getMediaUri`. */
    data class Document(val uri: Uri) : ExternalSource

    /** Fichier désigné par son chemin, retrouvé dans MediaStore par sa colonne DATA. */
    data class FilePath(val path: String) : ExternalSource

    /** Fournisseur d'une autre application (messagerie, navigateur…) : jamais dans l'index. */
    data object Foreign : ExternalSource
}

/** Classement des URI reçues, sans aucun accès au système. */
object ExternalUris {

    fun classify(uri: Uri): ExternalSource = when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_FILE -> uri.path?.takeIf { it.startsWith("/") }?.let { ExternalSource.FilePath(it) }
            ?: ExternalSource.Foreign
        ContentResolver.SCHEME_CONTENT -> when (uri.authority) {
            MediaStore.AUTHORITY -> mediaStoreId(uri)?.let { ExternalSource.MediaStoreId(it) } ?: ExternalSource.Foreign
            EXTERNAL_STORAGE_DOCUMENTS, MEDIA_DOCUMENTS -> ExternalSource.Document(uri)
            DOWNLOADS_DOCUMENTS -> download(uri)
            else -> ExternalSource.Foreign
        }
        else -> ExternalSource.Foreign
    }

    /**
     * Identifiant d'une URI MediaStore d'image, de vidéo, de fichier ou de téléchargement
     * (`content://media/<volume>/images/media/<id>`…) ; null pour les autres, dont celles du
     * sélecteur de photos, qui peuvent désigner un média du cloud.
     */
    fun mediaStoreId(uri: Uri): Long? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return null
        val segments = uri.pathSegments
        val known = when (segments.size) {
            3 -> segments[1] == "file" || segments[1] == "downloads"
            4 -> (segments[1] == "images" || segments[1] == "video") && segments[2] == "media"
            else -> false
        }
        return if (known) segments.last().toLongOrNull()?.takeIf { it > 0 } else null
    }

    /** Téléchargements : `msf:<id>` désigne un média MediaStore, `raw:<chemin>` un fichier. */
    private fun download(uri: Uri): ExternalSource {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ExternalSource.Foreign
        return when {
            documentId.startsWith(MEDIASTORE_FILE) ->
                documentId.removePrefix(MEDIASTORE_FILE).toLongOrNull()?.let { ExternalSource.MediaStoreId(it) }
                    ?: ExternalSource.Foreign
            documentId.startsWith(RAW_PATH) -> ExternalSource.FilePath(documentId.removePrefix(RAW_PATH))
            else -> ExternalSource.Document(uri)
        }
    }

    private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
    private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
    private const val DOWNLOADS_DOCUMENTS = "com.android.providers.downloads.documents"
    private const val MEDIASTORE_FILE = "msf:"
    private const val RAW_PATH = "raw:"
}
