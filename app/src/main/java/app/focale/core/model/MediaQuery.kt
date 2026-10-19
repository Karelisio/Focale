package app.focale.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Filtre rapide de type de média (puces de filtre). */
@Serializable
enum class MediaFilter { ALL, PHOTOS, VIDEOS, SCREENSHOTS, GIFS }

/** Champ de tri. */
@Serializable
enum class SortField { DATE_TAKEN, DATE_MODIFIED, NAME, SIZE }

@Serializable
@Immutable
data class MediaSort(
    val field: SortField = SortField.DATE_TAKEN,
    val ascending: Boolean = false,
) {
    /** Les en-têtes de date n'ont de sens que pour un tri par date de prise de vue. */
    val isChronological: Boolean get() = this.field == SortField.DATE_TAKEN
}

/**
 * Terme de recherche résolu : un mot saisi par l'utilisateur et ce à quoi il correspond.
 * Un média satisfait le terme s'il porte l'une des [labels], si son lieu contient
 * [place] (forme normalisée), s'il a été pris dans l'un des [countries] ou si son nom
 * de fichier contient [text].
 */
@Serializable
@Immutable
data class SearchTerm(
    val labels: List<String> = emptyList(),
    val place: String? = null,
    /** Vrai : [place] doit être le nom exact du lieu (choix d'un lieu dans la liste). */
    val exactPlace: Boolean = false,
    val countries: List<String> = emptyList(),
    val text: String? = null,
)

/** Zone géographique en degrés, bornes incluses (regroupement de la carte). */
@Serializable
@Immutable
data class GeoArea(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * Description complète d'une liste de médias : filtres + tri.
 *
 * Toutes les grilles de l'application (timeline, album, favoris, recherche…) sont
 * décrites par une [MediaQuery]. La visionneuse reçoit la même requête que la grille
 * d'origine, ce qui garantit un parcours identique, dans le même ordre.
 */
@Serializable
@Immutable
data class MediaQuery(
    val filter: MediaFilter = MediaFilter.ALL,
    /** Album dossier (BUCKET_ID MediaStore). */
    val bucketId: Long? = null,
    /** Album virtuel créé dans l'application. */
    val albumId: Long? = null,
    val favoritesOnly: Boolean = false,
    /** Termes de recherche, combinés par ET. */
    val terms: List<SearchTerm> = emptyList(),
    /** Borne inférieure incluse (ms). */
    val dateFrom: Long? = null,
    /** Borne supérieure exclue (ms). */
    val dateTo: Long? = null,
    /** Mois de l'année (1-12), toutes années confondues (« juillet »). */
    val month: Int? = null,
    /** Sous-ensemble explicite (groupe de doublons, souvenir…). */
    val mediaIds: List<Long>? = null,
    /** Restreint aux médias géolocalisés (carte). */
    val geotaggedOnly: Boolean = false,
    /** Médias pris dans cette zone (groupe de la carte). */
    val area: GeoArea? = null,
    val sort: MediaSort = MediaSort(),
) {
    companion object {
        val Timeline = MediaQuery()
    }
}
