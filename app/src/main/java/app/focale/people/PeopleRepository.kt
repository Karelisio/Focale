package app.focale.people

import androidx.compose.runtime.Immutable
import app.focale.core.database.FaceDao
import app.focale.core.database.FaceThumbRow
import app.focale.core.database.PersonEntity
import app.focale.core.database.PersonRow
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaStoreSource
import app.focale.data.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
