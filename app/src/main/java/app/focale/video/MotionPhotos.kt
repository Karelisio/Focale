package app.focale.video

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as ExoMediaItem

/** Photo animée de la galerie : média d'origine et emplacement de sa vidéo. */
data class MotionPhoto(val mediaId: Long, val uri: Uri, val video: MotionPhotoVideo)

/**
 * Détection des photos animées, partagée par la visionneuse et les miniatures de la grille.
 *
 * Les résultats (négatifs compris) sont gardés en mémoire par média et date de modification :
 * une photo n'est lue qu'une fois, et de nouveau seulement si elle change.
 */
object MotionPhotos {

    private const val TAG = "MotionPhotos"
    private const val CACHE_SIZE = 4096

    /** Résultat de détection mis en cache ([video] null : pas une photo animée). */
    class Detection(val video: MotionPhotoVideo?)

    private val cache = LruCache<String, Detection>(CACHE_SIZE)
    private val notMotion = Detection(null)

    /** Lectures de détection pour la grille : peu nombreuses, pour ne pas gêner les miniatures. */
    val gridDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /** Seuls les JPEG peuvent porter une vidéo dans les formats reconnus. */
    fun isCandidate(item: MediaItem): Boolean =
        !item.isVideo && !item.isGif && item.mimeType.equals("image/jpeg", ignoreCase = true)

    /** Résultat déjà connu, sans lecture ; null si la photo n'a pas encore été examinée. */
    fun cached(item: MediaItem): Detection? = if (isCandidate(item)) cache.get(key(item)) else notMotion

    /** Bloquant : lit au besoin les en-têtes du fichier. À appeler hors du thread principal. */
    fun detect(resolver: ContentResolver, item: MediaItem): MotionPhotoVideo? {
        cached(item)?.let { return it.video }
        val video = try {
            resolver.openFileDescriptor(item.uri, "r")?.let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { MotionPhotoParser.parse(it.channel) }
            }
        } catch (error: Exception) {
            // Fichier illisible pour l'instant (permission, suppression…) : rien n'est mémorisé.
            Log.d(TAG, "Lecture impossible : ${item.displayName}", error)
            return null
        }
        cache.put(key(item), Detection(video))
        return video
    }

    /**
     * Lecteur de la vidéo intégrée : le JPEG est lu directement, à partir du début de la vidéo
     * ([ByteRangeDataSource]), sans copie. Aucune donnée de diagnostic n'est transmise.
     */
    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context, motionPhoto: MotionPhoto): ExoPlayer {
        val video = motionPhoto.video
        val dataSource = ByteRangeDataSource.Factory(DefaultDataSource.Factory(context), video.offset, video.length)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSource))
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                setMediaItem(ExoMediaItem.Builder().setUri(motionPhoto.uri).setMimeType(MimeTypes.VIDEO_MP4).build())
            }
    }

    private fun key(item: MediaItem): String = "${item.id}:${item.dateModified}"
}

/** Accès aux photos animées pour les ViewModels (lecture sur le dispatcher d'entrées/sorties). */
@Singleton
class MotionPhotoRepository @Inject constructor(
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun find(item: MediaItem): MotionPhoto? {
        if (!MotionPhotos.isCandidate(item)) return null
        val video = withContext(io) { MotionPhotos.detect(resolver, item) } ?: return null
        return MotionPhoto(item.id, item.uri, video)
    }
}
