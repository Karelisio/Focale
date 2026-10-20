package app.focale.people

import androidx.compose.runtime.Immutable

/** Visage proposé à la revue d'une personne, et sa ressemblance (cosinus) au reste de la personne. */
@Immutable
data class ReviewFace(val face: FaceThumbnail, val similarity: Float)

/** Visages retirés d'une personne ou déplacés vers une autre, avec de quoi annuler. */
class FaceTransfer internal constructor(
    internal val removal: PersonRemoval,
    /** Personne créée pour accueillir les visages, supprimée par l'annulation. */
    internal val created: Long?,
) {
    val count: Int get() = removal.count
}

/**
 * Ordre de la revue des visages d'une personne (logique pure) : du moins au plus
 * ressemblant au centroïde (moyenne normalisée des empreintes), pour que les erreurs du
 * regroupement apparaissent en premier.
 */
object FaceReview {

    /**
     * Similarité de chaque empreinte (de norme 1) au centroïde du groupe : proche de 1 pour
     * un visage typique, faible pour un intrus. Une empreinte d'une autre dimension compte
     * pour 0, sans peser sur le centroïde.
     */
    fun similarities(embeddings: List<FloatArray>): FloatArray {
        val sum = FloatArray(FaceEmbeddings.DIMENSION)
        for (embedding in embeddings) {
            if (embedding.size == sum.size) for (i in sum.indices) sum[i] += embedding[i]
        }
        val centroid = FaceEmbeddings.normalize(sum)
        return FloatArray(embeddings.size) { index ->
            val embedding = embeddings[index]
            if (embedding.size == centroid.size) FaceEmbeddings.dot(centroid, embedding) else 0f
        }
    }

    /** Indices de [embeddings] du moins au plus ressemblant, avec leur similarité ; ordre d'origine à égalité. */
    fun leastSimilarFirst(embeddings: List<FloatArray>): List<IndexedValue<Float>> =
        similarities(embeddings).withIndex().sortedBy { it.value }
}
