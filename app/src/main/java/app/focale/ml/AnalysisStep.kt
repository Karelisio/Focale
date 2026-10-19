package app.focale.ml

import app.focale.core.model.MediaItem

/**
 * Étape d'analyse complémentaire (texte, visages, empreintes sémantiques), exécutée par
 * `AnalysisWorker` une fois l'analyse principale à jour. Chaque étape tient son propre
 * avancement (table et version dédiées) : elle reprend là où elle s'était arrêtée et
 * recommence quand sa version change ou qu'un média est modifié.
 *
 * Les étapes sont fournies par multiliaison (`@IntoSet`), chacune dans son module.
 */
interface AnalysisStep {
    /** Nom court, pour les journaux. */
    val name: String

    /** Ordre d'exécution, croissant. */
    val order: Int get() = 0

    /** Prochain lot de médias à traiter ; vide quand l'étape est à jour. */
    suspend fun pending(limit: Int): List<MediaItem>

    /**
     * Traite [items] et enregistre le résultat de chacun, échec compris : un média en
     * échec est marqué comme traité pour ne pas être retenté en boucle.
     */
    suspend fun process(items: List<MediaItem>)

    /** Libère les ressources (modèles chargés) à la fin d'un passage. */
    fun release() {}
}
