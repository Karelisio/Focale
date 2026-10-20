package app.focale.ml.semantic

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import app.focale.core.database.EmbeddingDao
import app.focale.core.database.MediaEmbeddingEntity
import app.focale.core.database.StepCounts
import app.focale.core.di.DefaultDispatcher
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.data.toModel
import app.focale.ml.AnalysisKind
import app.focale.ml.AnalysisStep
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Étape d'analyse « empreintes » : empreinte TinyCLIP de chaque photo, comparée ensuite au
 * texte des recherches en langage naturel ([SemanticSearch]).
 *
 * La photo est lue par sa miniature MediaStore (orientation appliquée, comme
 * `ImageAnalyzer`), réduite et normalisée par [ClipPreprocessor].
 */
class EmbeddingStep @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val dao: EmbeddingDao,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val compute: CoroutineDispatcher,
) : AnalysisStep {

    override val name: String = "empreintes"

    override val order: Int = 30

    override val kind: AnalysisKind = AnalysisKind.SEMANTIC

    override fun observeProgress(): Flow<StepCounts> = dao.observeProgress(ClipModels.VERSION)

    private var encoder: ClipImageEncoder? = null

    override suspend fun pending(limit: Int): List<MediaItem> =
        dao.pending(ClipModels.VERSION, limit).map { it.toModel() }

    override suspend fun process(items: List<MediaItem>) {
        // Un modèle impossible à charger interrompt l'étape sans marquer les photos.
        val encoder = encoder ?: withContext(io) {
            ClipImageEncoder(ClipModels.openAsset(context, ClipModels.IMAGE_ASSET, THREADS))
        }.also { encoder = it }
        val embeddings = items.map { item ->
            val vector = try {
                embed(encoder, item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Empreinte impossible pour ${item.id}", error)
                null
            }
            MediaEmbeddingEntity(
                mediaId = item.id,
                analyzedModified = item.dateModified,
                version = ClipModels.VERSION,
                vector = vector?.let(EmbeddingCodec::encode),
            )
        }
        dao.upsert(embeddings)
    }

    private suspend fun embed(encoder: ClipImageEncoder, item: MediaItem): FloatArray? {
        val bitmap = withContext(io) { loadBitmap(item) } ?: return null
        return withContext(compute) {
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                encoder.embed(ClipPreprocessor.preprocess(pixels, bitmap.width, bitmap.height))
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Miniature MediaStore (cache système), en ARGB_8888 lisible pixel par pixel. */
    private fun loadBitmap(item: MediaItem): Bitmap? = runCatching {
        val thumbnail = resolver.loadThumbnail(item.uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
        if (thumbnail.config == Bitmap.Config.ARGB_8888) {
            thumbnail
        } else {
            thumbnail.copy(Bitmap.Config.ARGB_8888, false).also { thumbnail.recycle() }
        }
    }.getOrNull()

    override fun release() {
        encoder?.close()
        encoder = null
    }

    private companion object {
        const val TAG = "EmbeddingStep"

        /** Même taille que l'analyse principale : la miniature est déjà dans le cache système. */
        const val THUMBNAIL_SIZE = 512

        /** Deux fils de calcul : l'analyse reste discrète en arrière-plan. */
        const val THREADS = 2
    }
}
