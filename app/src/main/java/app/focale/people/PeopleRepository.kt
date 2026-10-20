package app.focale.people

import androidx.compose.runtime.Immutable
import app.focale.core.database.FaceDao
import app.focale.core.database.FaceThumbRow
import app.focale.core.database.PersonEntity
import app.focale.core.database.PersonListRow
import app.focale.core.database.PersonRow
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.settings.PeopleSort
import app.focale.data.MediaRepository
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** Personne reconnue : nom éventuel, nombre de photos et vignette du visage. */
@Immutable
data class Person(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    val photoCount: Int,
    val cover: FaceThumbnail?,
    /** Épinglée en tête de la liste ; renseigné par la liste triée (`observePeople(sort)`). */
    val pinned: Boolean = false,
)

/** Photos retirées d'une personne, avec de quoi annuler le retrait. */
class PersonRemoval internal constructor(
    val count: Int,
    internal val person: PersonEntity,
    internal val links: List<Pair<Long, Boolean>>,
)

/** Personnes (visages regroupés sur l'appareil) : lecture et retouches de l'utilisateur. */
@Singleton
class PeopleRepository @Inject constructor(
    private val dao: FaceDao,
    private val editions: PeopleEditions,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Personnes visibles, les plus présentes d'abord ; masquées comprises sur demande. */
    fun observePeople(includeHidden: Boolean = false): Flow<List<Person>> =
        dao.observePeople(includeHidden, MIN_PHOTOS).distinctUntilChanged().mapLatest(::withCovers).flowOn(io)

    fun observePerson(id: Long): Flow<Person?> =
        dao.observePerson(id).distinctUntilChanged().mapLatest { row -> row?.let { withCovers(listOf(it)).firstOrNull() } }.flowOn(io)

    /** Personnes nommées (recherche par nom), masquées comprises, sans vignette. */
    fun observeNamed(): Flow<List<Person>> =
        dao.observeNamed().distinctUntilChanged().mapLatest { rows -> rows.map { it.toPerson(null) } }.flowOn(io)

    /** Personnes visibles reconnues sur une photo, avec leur visage sur celle-ci. */
    fun observePeopleIn(mediaId: Long): Flow<List<Person>> =
        dao.observePeopleIn(mediaId).distinctUntilChanged()
            .mapLatest { rows -> withCovers(rows.filter { !it.hidden && (it.name != null || it.photoCount >= MIN_PHOTOS) }) }
            .flowOn(io)

    /** Visages d'une personne, les meilleurs d'abord (choix de la vignette). */
    suspend fun faces(personId: Long, limit: Int = 120): List<FaceThumbnail> = withContext(io) {
        dao.facesOfPerson(personId, limit).map { it.toThumbnail() }
    }

    suspend fun rename(id: Long, name: String?) = edit { dao.rename(id, name?.trim()?.takeIf { it.isNotEmpty() }) }

    suspend fun setHidden(id: Long, hidden: Boolean) = edit { dao.setHidden(id, hidden) }

    suspend fun setCover(id: Long, faceId: Long) = edit { dao.setCover(id, faceId) }

    /** Vignette prise sur une photo de la personne (son meilleur visage sur celle-ci). */
    suspend fun setCoverFromMedia(id: Long, mediaId: Long) = edit {
        dao.bestFaceIn(id, mediaId)?.let { dao.setCover(id, it) }
    }

    suspend fun merge(sourceId: Long, targetId: Long) = edit { dao.merge(sourceId, targetId) }

    /** Retire des photos de la personne ; ces visages ne lui seront plus rattachés d'office. */
    suspend fun removeMedia(personId: Long, mediaIds: Collection<Long>): PersonRemoval? = edit {
        val person = dao.person(personId) ?: return@edit null
        val links = mediaIds.chunked(MediaRepository.MAX_SQL_ARGS).flatMap { dao.linksIn(personId, it) }
        mediaIds.chunked(MediaRepository.MAX_SQL_ARGS).forEach { dao.detach(personId, it) }
        PersonRemoval(mediaIds.size, person, links.map { it.id to it.manual })
    }

    /** Annule [removal] ; la personne est recréée si elle a disparu entre-temps. */
    suspend fun restore(removal: PersonRemoval) = edit {
        dao.restorePerson(removal.person)
        removal.links.groupBy({ it.second }, { it.first }).forEach { (manual, ids) ->
            ids.chunked(MediaRepository.MAX_SQL_ARGS).forEach { dao.relink(it, removal.person.id, manual) }
        }
    }

    // ── Revue des visages et photos ensemble ─────────────────────────────────────

    /** Visages de la personne, du moins au plus ressemblant ([FaceReview]) : les erreurs d'abord. */
    fun observeReview(personId: Long): Flow<List<ReviewFace>> =
        dao.observeReviewFaces(personId).mapLatest { rows ->
            FaceReview.leastSimilarFirst(rows.map { FaceEmbeddings.decode(it.embedding) })
                .map { (index, similarity) -> ReviewFace(rows[index].thumb.toThumbnail(), similarity) }
        }.distinctUntilChanged().flowOn(io)

    /** Autres personnes que [personId], masquées comprises, les nommées d'abord (listes de choix). */
    fun observeOthers(personId: Long): Flow<List<Person>> =
        observePeople(includeHidden = true).map { people -> people.filter { it.id != personId }.sortedBy { it.name == null } }

    /**
     * Personnes vues sur les photos de [personId] (masquées comprises), les nommées d'abord
     * puis les plus fréquentes, avec le nombre de photos où elles figurent ensemble.
     */
    fun observeTogether(personId: Long): Flow<List<PersonTogether>> =
        combine(observeOthers(personId), dao.observeTogetherCounts(personId)) { others, rows ->
            val counts = rows.associate { it.personId to it.photoCount }
            others.mapNotNull { person -> counts[person.id]?.let { PersonTogether(person, it) } }
                .sortedWith(compareBy({ it.person.name == null }, { -it.photoCount }))
        }.flowOn(io)

    /** Retire des visages de la personne ; le regroupement ne les lui rattachera plus d'office. */
    suspend fun removeFaces(personId: Long, faceIds: Collection<Long>): FaceTransfer? = edit {
        transfer(personId, faceIds, targetId = null)
    }

    /** Déplace des visages vers [targetId] : rattachement manuel, que le regroupement respecte. */
    suspend fun moveFaces(personId: Long, faceIds: Collection<Long>, targetId: Long): FaceTransfer? = edit {
        if (targetId == personId) null else transfer(personId, faceIds, targetId)
    }

    /** Déplace des visages vers une nouvelle personne, nommée [name] s'il est renseigné. */
    suspend fun moveFacesToNewPerson(personId: Long, faceIds: Collection<Long>, name: String?): FaceTransfer? = edit {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        val created = dao.insertPerson(PersonEntity(name = clean, createdAt = System.currentTimeMillis()))
        transfer(personId, faceIds, created, created).also { if (it == null) dao.deleteIfEmpty(created) }
    }

    /** Annule [transfer] ; la personne d'origine est recréée si elle a disparu entre-temps. */
    suspend fun restore(transfer: FaceTransfer) {
        restore(transfer.removal)
        transfer.created?.let { edit { dao.deleteIfEmpty(it) } }
    }

    /** Rattache à la main les visages [faceIds] de [personId] à [targetId] (null : retrait). */
    private suspend fun transfer(personId: Long, faceIds: Collection<Long>, targetId: Long?, created: Long? = null): FaceTransfer? {
        val person = dao.person(personId) ?: return null
        val chunks = faceIds.chunked(MediaRepository.MAX_SQL_ARGS)
        val links = chunks.flatMap { dao.linksOf(personId, it) }
        val moved = chunks.sumOf { dao.reassign(personId, it, targetId) }
        if (moved == 0) return null
        return FaceTransfer(PersonRemoval(moved, person, links.map { it.id to it.manual }), created)
    }

    // ── Liste triée, épinglage, refus de fusion ──────────────────────────────────────

    /** Personnes visibles dans l'ordre [sort], épinglées en tête ; masquées comprises sur demande. */
    fun observePeople(sort: PeopleSort, includeHidden: Boolean = false): Flow<List<Person>> =
        dao.observePeopleList(includeHidden, MIN_PHOTOS).distinctUntilChanged()
            .mapLatest { rows ->
                val covers = rows.mapNotNull { it.coverFaceId }.chunked(MediaRepository.MAX_SQL_ARGS)
                    .flatMap { dao.thumbs(it) }
                    .associateBy { it.id }
                sortPeople(rows, sort).map { row ->
                    Person(row.id, row.name, row.hidden, row.photoCount, row.coverFaceId?.let(covers::get)?.toThumbnail(), row.pinned)
                }
            }
            .flowOn(io)

    /** Épingle une personne en tête de la liste, ou l'en retire (sans effet sur le regroupement). */
    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(io) { dao.setPinned(id, pinned) }

    /** « Pas la même » : la fusion de [a] et [b] ne sera plus suggérée. */
    suspend fun refuseMerge(a: Long, b: Long) {
        if (a != b) withContext(io) { dao.refuse(minOf(a, b), maxOf(a, b)) }
    }

    private suspend fun <T> edit(block: suspend () -> T): T = withContext(io) {
        try {
            block()
        } finally {
            editions.bump()
        }
    }

    private suspend fun withCovers(rows: List<PersonRow>): List<Person> {
        val faces = rows.mapNotNull { it.coverFaceId }.chunked(MediaRepository.MAX_SQL_ARGS)
            .flatMap { dao.thumbs(it) }
            .associateBy { it.id }
        return rows.map { row -> row.toPerson(row.coverFaceId?.let(faces::get)?.toThumbnail()) }
    }

    private fun PersonRow.toPerson(cover: FaceThumbnail?) = Person(id, name, hidden, photoCount, cover)

    private fun FaceThumbRow.toThumbnail() = FaceThumbnail(
        faceId = id,
        mediaId = mediaId,
        uri = MediaStoreSource.contentUri(mediaId, false),
        dateModified = dateModified,
        box = FaceBox(boxLeft, boxTop, boxRight, boxBottom),
    )

    companion object {
        /** Nombre de photos à partir duquel une personne sans nom est proposée. */
        const val MIN_PHOTOS = 3
    }
}

/**
 * Ordre de la liste : épinglées d'abord, puis [sort] ; à égalité, les plus présentes. Par
 * nom : ni casse ni accents (langue de l'appareil), personnes sans nom à la fin.
 */
internal fun sortPeople(rows: List<PersonListRow>, sort: PeopleSort): List<PersonListRow> {
    val order: Comparator<PersonListRow> = when (sort) {
        PeopleSort.PHOTOS -> compareByDescending { it.photoCount }
        PeopleSort.NAME -> {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            compareBy(nullsLast(collator)) { it.name }
        }
        PeopleSort.RECENT -> compareByDescending { it.latestTaken }
    }
    return rows.sortedWith(
        compareByDescending<PersonListRow> { it.pinned }.then(order).thenByDescending { it.photoCount }.thenBy { it.id },
    )
}
