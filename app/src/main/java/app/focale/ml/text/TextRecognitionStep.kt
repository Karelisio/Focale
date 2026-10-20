package app.focale.ml.text

import android.util.Log
import app.focale.core.database.MediaTextEntity
import app.focale.core.database.StepCounts
import app.focale.core.database.TextDao
import app.focale.core.model.MediaItem
import app.focale.data.toModel
import app.focale.ml.AnalysisKind
import app.focale.ml.AnalysisStep
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/**
 * Étape « texte » : lit le texte des photos (captures d'écran d'abord, voir
 * [TextDao.pending]) et l'enregistre avec sa clé de recherche, même vide.
 *
 * Une photo illisible ou en échec est marquée comme lue. Si la reconnaissance échoue sur
 * toutes les photos d'un lot, le modèle est jugé indisponible : rien n'est enregistré et
 * l'étape s'interrompt, pour ne pas marquer toute la bibliothèque comme vide.
 */
class TextRecognitionStep @Inject constructor(
    private val dao: TextDao,
    private val reader: PhotoTextReader,
) : AnalysisStep {

    override val name: String = "texte"

    override val order: Int = 10

    override val kind: AnalysisKind = AnalysisKind.TEXT

    override fun observeProgress(): Flow<StepCounts> = dao.observeProgress(VERSION)

    override suspend fun pending(limit: Int): List<MediaItem> = dao.pending(VERSION, limit).map { it.toModel() }

    override suspend fun process(items: List<MediaItem>) {
        val texts = ArrayList<MediaTextEntity>(items.size)
        var recognized = 0
        var failure: Exception? = null
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val text = try {
                reader.read(item)?.also { recognized++ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Lecture du texte impossible pour ${item.id}", error)
                failure = error
                null
            }
            texts += entity(item, text.orEmpty())
        }
        failure?.let { if (recognized == 0) throw IllegalStateException("Reconnaissance de texte indisponible", it) }
        dao.upsert(texts)
    }

    override fun release() = reader.release()

    companion object {
        private const val TAG = "TextRecognitionStep"

        /** Version de la lecture : l'incrémenter relance la lecture de toutes les photos. */
        const val VERSION = 1

        /** Ligne de `media_text` pour [item] : texte brut et clé de recherche. */
        fun entity(item: MediaItem, text: String) = MediaTextEntity(
            mediaId = item.id,
            analyzedModified = item.dateModified,
            version = VERSION,
            text = text,
            textKey = RecognizedText.searchKey(text),
        )

        /** Vrai si [entity] décrit [item] tel qu'il est, lu par la version actuelle. */
        fun isCurrent(entity: MediaTextEntity, item: MediaItem): Boolean =
            entity.analyzedModified == item.dateModified && entity.version >= VERSION
    }
}
