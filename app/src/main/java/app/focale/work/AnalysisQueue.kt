package app.focale.work

import app.focale.core.database.AnalysisDao
import app.focale.core.database.MediaEntity
import app.focale.ml.ImageAnalyzer

/**
 * File des médias à analyser pendant un passage d'[AnalysisWorker], des plus récents aux plus
 * anciens : chaque lot reprend après le dernier média du précédent ([AnalysisDao.pendingAfter])
 * au lieu de relire depuis le début les médias déjà analysés. Le coût d'un lot reste ainsi
 * constant quel que soit l'avancement ; sans curseur, il croissait avec lui et l'analyse d'une
 * grande bibliothèque coûtait un temps quadratique en sélections.
 *
 * Un média ajouté ou modifié pendant le passage, plus récent que le curseur, attend le passage
 * suivant (le worker en programme un s'il reste du travail).
 */
class AnalysisQueue(
    private val dao: AnalysisDao,
    private val version: Int = ImageAnalyzer.VERSION,
) {
    private var last: MediaEntity? = null

    /** Prochain lot d'au plus [limit] médias ; vide à la fin du passage. */
    suspend fun next(limit: Int): List<MediaEntity> {
        val after = last
        val batch = if (after == null) {
            dao.pending(version, limit)
        } else {
            dao.pendingAfter(version, after.dateTaken, after.id, limit)
        }
        batch.lastOrNull()?.let { last = it }
        return batch
    }
}
