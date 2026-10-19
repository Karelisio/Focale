package app.focale.ml.semantic

/**
 * Sélection des résultats de la recherche par le sens à partir des similarités cosinus
 * texte-image : un score doit dépasser [minimum] (le texte décrit vraiment la photo) et ne
 * pas s'écarter de plus de [margin] du meilleur score de la requête (les scores absolus
 * varient d'une formulation à l'autre) ; au plus [limit] résultats, du plus proche au moins
 * proche.
 */
data class SimilarityThreshold(val minimum: Float, val margin: Float, val limit: Int) {

    /** Lignes retenues parmi [scores] (les [count] premières), par score décroissant. */
    fun select(scores: FloatArray, count: Int = scores.size): IntArray {
        var best = Float.NEGATIVE_INFINITY
        for (i in 0 until count) if (scores[i] > best) best = scores[i]
        if (best < minimum) return IntArray(0)
        val floor = maxOf(minimum, best - margin)
        val kept = ArrayList<Int>()
        for (i in 0 until count) if (scores[i] >= floor) kept += i
        return kept.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .take(limit)
            .toIntArray()
    }

    companion object {
        /**
         * Seuils du modèle embarqué, calibrés sur les images d'exemple d'ImageNet (voir
         * `tools/clip/README.md`) : 0,37 est le 98e centile des scores sans rapport (deux
         * photos sur cent environ le dépassent) ; avec l'écart de 0,06, la recherche d'un
         * concept large (chien, oiseau, voiture…) a une précision de 0,57 pour un rappel de
         * 0,72. La limite borne la requête transmise à la grille.
         */
        val Default = SimilarityThreshold(minimum = 0.37f, margin = 0.06f, limit = 400)
    }
}
