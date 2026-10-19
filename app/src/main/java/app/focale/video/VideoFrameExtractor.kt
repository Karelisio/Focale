package app.focale.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.focale.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Vignettes régulièrement espacées d'une vidéo, pour la frise de l'éditeur. */
@Singleton
class VideoFrameExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Émet, au fur et à mesure, l'image du milieu de chacune des [count] tranches égales de la
     * vidéo (côté le plus long ≤ [maxSide]). Un seul lecteur de métadonnées sert pour toute la
     * frise ; les vidéos courtes sont lues à l'image près, les longues aux images clés, bien plus
     * rapides à extraire et assez proches à cette échelle.
     */
    fun frames(uri: Uri, durationMs: Long, count: Int, maxSide: Int): Flow<IndexedValue<Bitmap>> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val option = if (durationMs <= PRECISE_MAX_MS) {
                MediaMetadataRetriever.OPTION_CLOSEST
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            }
            for (index in 0 until count) {
                val timeUs = durationMs * 1_000 * (2 * index + 1) / (2 * count)
                val frame = runCatching { retriever.getScaledFrameAtTime(timeUs, option, maxSide, maxSide) }.getOrNull()
                    ?: continue
                emit(IndexedValue(index, frame))
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.flowOn(io)

    private companion object {
        /** Jusqu'à cette durée, les vignettes sont extraites à l'image exacte. */
        const val PRECISE_MAX_MS = 15_000L
    }
}
