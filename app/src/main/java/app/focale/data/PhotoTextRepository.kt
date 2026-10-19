package app.focale.data

import app.focale.core.database.TextDao
import app.focale.core.model.MediaItem
import app.focale.ml.text.PhotoTextReader
import app.focale.ml.text.TextRecognitionStep
import javax.inject.Inject
import javax.inject.Singleton

/** Texte reconnu dans les photos : celui de l'analyse de fond, ou lu à la demande. */
@Singleton
class PhotoTextRepository @Inject constructor(
    private val dao: TextDao,
    private val reader: PhotoTextReader,
) {

    /**
     * Texte de [item] ("" s'il n'en contient pas) : celui de l'analyse s'il est à jour,
     * sinon lu immédiatement puis enregistré. Null si l'image ne peut pas être décodée ;
     * une exception si la reconnaissance échoue.
     */
    suspend fun textOf(item: MediaItem): String? {
        dao.get(item.id)?.takeIf { TextRecognitionStep.isCurrent(it, item) }?.let { return it.text }
        val text = reader.read(item) ?: return null
        dao.upsert(listOf(TextRecognitionStep.entity(item, text)))
        return text
    }

    /** Libère le modèle, sauf lecture en cours (fermeture de la visionneuse). */
    fun release() = reader.release()
}
