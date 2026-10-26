package app.focale.panorama

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.data.ExternalMedia
import app.focale.data.readAt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Détection des photos panoramiques, à l'ouverture dans la visionneuse : rien n'est analysé en
 * arrière-plan ni enregistré dans la base. Les propriétés GPano lues (absence comprise) sont
 * gardées en mémoire par média et date de modification, comme pour les photos animées : une
 * photo n'est relue que si elle change.
 */
object Panoramas {

    private const val TAG = "Panoramas"
    private const val CACHE_SIZE = 1024

    /** Formats dont l'en-tête peut porter un XMP lisible sans décoder l'image. */
    private val headerTypes = setOf("image/jpeg", "image/webp", "image/heic", "image/heif", "image/avif")

    private class Detection(val gpano: GPano?)

    private val cache = LruCache<String, Detection>(CACHE_SIZE)

    /** Photos fixes seulement : ni vidéo ni GIF animé. */
    fun isCandidate(item: MediaItem): Boolean = !item.isVideo && !item.isGif

    /** Bloquant : lit au besoin l'en-tête du fichier. À appeler hors du fil principal. */
    fun detect(resolver: ContentResolver, item: MediaItem): Panorama? {
        if (!isCandidate(item)) return null
        val gpano = if (item.mimeType.lowercase() in headerTypes) gpano(resolver, item) else null
        return PanoramaRules.classify(item.id, gpano, item.displayWidth, item.displayHeight)
    }

    /**
     * Bloquant : vue d'une photo hors de l'index (autre application, coffre), de type [mimeType] :
     * GPano lu dans l'en-tête par [read] ([length] octets à [offset], null au-delà de la fin),
     * dimensions et orientation dans celui de [streams]. Rien n'est gardé en mémoire : la
     * visionneuse garde le résultat.
     */
    internal fun detect(mediaId: Long, mimeType: String, streams: PhotoStreams, read: (offset: Long, length: Int) -> ByteArray?): Panorama? {
        val type = mimeType.lowercase()
        if (!type.startsWith("image/") || type == "image/gif") return null
        val gpano = if (type in headerTypes) PanoramaHeaders.gpano(read) else null
        val (width, height) = PhotoHeader.displaySize(streams) ?: return null
        return PanoramaRules.classify(mediaId, gpano, width, height)
    }

    private fun gpano(resolver: ContentResolver, item: MediaItem): GPano? {
        val key = "${item.id}:${item.dateModified}"
        cache.get(key)?.let { return it.gpano }
        val gpano = try {
            resolver.openFileDescriptor(item.uri, "r")?.let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val channel = stream.channel
                    PanoramaHeaders.gpano { offset, length -> channel.readAt(offset, length) }
                }
            }
        } catch (error: Exception) {
            // Fichier illisible pour l'instant (permission, suppression…) : rien n'est mémorisé.
            Log.d(TAG, "Lecture impossible : ${item.displayName}", error)
            return null
        }
        cache.put(key, Detection(gpano))
        return gpano
    }
}

/**
 * Accès aux panoramas pour les ViewModels (lecture sur le dispatcher d'entrées/sorties) : photos
 * panoramiques et 360° ([Panoramas]), vidéos 360° ([SphericalVideos]), de la galerie ou ouvertes
 * depuis une autre application.
 */
@Singleton
class PanoramaRepository @Inject constructor(
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun find(item: MediaItem): Panorama? = when {
        SphericalVideos.isCandidate(item) -> withContext(io) { SphericalVideos.detect(resolver, item) }
        Panoramas.isCandidate(item) -> withContext(io) { Panoramas.detect(resolver, item) }
        else -> null
    }

    /**
     * Vue d'un fichier reçu d'une autre application, hors de l'index : en-têtes lus par accès
     * aléatoire à son descripteur de fichier ; un fournisseur qui n'en donne pas, ou refuse la
     * lecture, ne propose rien.
     */
    suspend fun find(media: ExternalMedia): Panorama? = withContext(io) {
        try {
            if (media.isVideo) {
                if (SphericalVideos.isCandidate(media.mimeType)) SphericalVideoRules.classify(ExternalMedia.ID, SphericalVideos.read(resolver, media.uri)) else null
            } else {
                resolver.openFileDescriptor(media.uri, "r")?.let { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                        val channel = stream.channel
                        Panoramas.detect(ExternalMedia.ID, media.mimeType, UriPhotoStreams(resolver, media.uri)) { offset, length -> channel.readAt(offset, length) }
                    }
                }
            }
        } catch (error: Exception) {
            Log.d(TAG, "Lecture impossible : ${media.displayName}", error)
            null
        }
    }

    private companion object {
        const val TAG = "PanoramaRepository"
    }
}
