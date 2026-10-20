package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.core.model.TextCriteria
import app.focale.ml.LabelCategory
import app.focale.ml.LabelDictionary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/** Album automatique de l'onglet Albums, dans l'ordre d'affichage. */
enum class SmartAlbumKind { DOCUMENTS, RECEIPTS, ANIMALS, FOOD, LANDSCAPES, CELEBRATIONS, VEHICLES, SPORT }

/** Album automatique : requête de sa grille, nombre de médias et couverture. */
@Immutable
data class SmartAlbum(val kind: SmartAlbumKind, val query: MediaQuery, val count: Int, val cover: MediaItem?)

/**
 * Critères des albums automatiques : un terme de recherche sur les catégories du dictionnaire
 * des étiquettes (sous-catégories comprises, comme dans la recherche) ou sur le texte reconnu.
 */
object SmartAlbums {

    /** Une catégorie n'apparaît qu'à partir de ce nombre de médias. */
    const val MIN_COUNT = 3

    /**
     * Longueur du texte d'un document (forme normalisée) : un ticket de caisse en compte
     * quelques centaines de caractères, une page 1 500 à 3 000, alors qu'une enseigne, une
     * plaque ou le devant d'un emballage dépassent rarement 100.
     */
    const val DOCUMENT_MIN_LENGTH = 200

    /** Indices de reçu requis : « TOTAL » seul (station-service) ou « € » seul (menu, étal) ne suffisent pas. */
    const val RECEIPT_MIN_KEYWORDS = 2

    private const val RECEIPT = "Receipt"

    /** Étiquettes ML Kit des documents papier. */
    val documentLabels = listOf("Paper", RECEIPT, "Newspaper", "Passport")

    /** Végétation, souvent en gros plan (fleur, plante d'intérieur) : exclue des paysages. */
    val vegetation = setOf("Flower", "Petal", "Plant", "Flora", "Garden")

    /**
     * Indices de reçu dans le texte : noms et synonymes de l'étiquette « Receipt » (reçu,
     * ticket, facture), montants et taxes, symboles monétaires.
     */
    val receiptKeywords: List<String> =
        LabelDictionary.entry(RECEIPT)?.let { listOf(it.en, it.fr) + it.synonyms }.orEmpty() +
            listOf("total", "subtotal", "montant", "tva", "ttc", "vat", "tax", "eur", "€", "$", "£")

    fun term(kind: SmartAlbumKind): SearchTerm = when (kind) {
        SmartAlbumKind.DOCUMENTS -> SearchTerm(
            labels = documentLabels,
            recognizedText = TextCriteria(minLength = DOCUMENT_MIN_LENGTH),
        )
        SmartAlbumKind.RECEIPTS -> SearchTerm(
            labels = listOf(RECEIPT),
            recognizedText = TextCriteria(keywords = receiptKeywords, minKeywords = RECEIPT_MIN_KEYWORDS),
        )
        SmartAlbumKind.ANIMALS -> category(LabelCategory.ANIMAL)
        SmartAlbumKind.FOOD -> category(LabelCategory.FOOD)
        SmartAlbumKind.LANDSCAPES -> SearchTerm(labels = LabelDictionary.labelsOf(LabelCategory.NATURE) - vegetation)
        SmartAlbumKind.CELEBRATIONS -> category(LabelCategory.CELEBRATION)
        SmartAlbumKind.VEHICLES -> category(LabelCategory.VEHICLE)
        SmartAlbumKind.SPORT -> category(LabelCategory.SPORT)
    }

    /** Requête de l'album, hors [excludedBuckets] (dossiers masqués de la timeline) et corbeille. */
    fun query(kind: SmartAlbumKind, excludedBuckets: List<Long> = emptyList()): MediaQuery =
        MediaQuery(terms = listOf(term(kind)), excludedBuckets = excludedBuckets)

    private fun category(category: LabelCategory) = SearchTerm(labels = LabelDictionary.labelsOf(category))
}

/** Albums automatiques : nombres et couvertures calculés en SQL, en une requête suivie par Room. */
@Singleton
class SmartAlbumRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val mediaRepository: MediaRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Albums d'au moins [SmartAlbums.MIN_COUNT] médias, hors corbeille et [excludedBuckets] ;
     * réémis quand l'analyse ajoute des étiquettes ou du texte, ou que la bibliothèque change.
     */
    fun observe(excludedBuckets: List<Long> = emptyList()): Flow<List<SmartAlbum>> = flow {
        // Hors du thread principal : le premier appel construit le dictionnaire des étiquettes.
        val kinds = SmartAlbumKind.entries
        val queries = kinds.map { SmartAlbums.query(it, excludedBuckets) }
        val albums = mediaDao.observeSummaries(MediaQueryBuilder.summaries(queries))
            .distinctUntilChanged()
            .mapLatest { rows ->
                val shown = rows.filter { it.count >= SmartAlbums.MIN_COUNT }.sortedBy { it.position }
                val covers = mediaRepository.getByIds(shown.mapNotNull { it.coverId }).associateBy { it.id }
                shown.map { row ->
                    SmartAlbum(kinds[row.position], queries[row.position], row.count, row.coverId?.let(covers::get))
                }
            }
        emitAll(albums)
    }.flowOn(io)
}
