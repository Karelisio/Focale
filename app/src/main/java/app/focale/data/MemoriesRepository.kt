package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.MemoryDao
import app.focale.core.database.MemoryQueries
import app.focale.core.database.PersonPresence
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.settings.SettingsRepository
import app.focale.people.FaceThumbnail
import app.focale.people.PeopleRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/** Souvenir : des médias d'une année passée, présentés autour de leur date anniversaire. */
@Immutable
data class Memory(
    val kind: MemoryKind,
    val query: MediaQuery,
    val count: Int,
    val cover: MediaItem,
)

/** Nature d'un souvenir, qui en fait le titre ([MemoryTitles]). */
sealed interface MemoryKind {
    /** Années écoulées depuis la fenêtre du souvenir. */
    val yearsAgo: Int

    /** Les médias pris autour de la date du jour, [yearsAgo] ans plus tôt. */
    data class YearsAgo(override val yearsAgo: Int) : MemoryKind

    /** Ceux de la même fenêtre où figure une personne ; [name] nul pour une personne sans nom. */
    data class WithPerson(
        override val yearsAgo: Int,
        val personId: Long,
        val name: String?,
        /** Sa vignette dans Personnes, en badge sur la carte. */
        val face: FaceThumbnail?,
    ) : MemoryKind

    /** Séjour loin de chez soi commencé dans la fenêtre, titré par sa ville ([place]) ou son pays. */
    data class Trip(
        override val yearsAgo: Int,
        val place: String?,
        /** Code ISO 3166-1 alpha-2. */
        val country: String?,
        val firstDay: LocalDate,
        val lastDay: LocalDate,
    ) : MemoryKind
}

/** Fenêtre de dates d'un souvenir, en millisecondes (début inclus, fin exclue). */
data class MemoryWindow(val yearsAgo: Int, val from: Long, val to: Long)

/** Calcul des fenêtres « il y a X ans » : même jour à ± [MemoriesRepository.WINDOW_DAYS] jours. */
object MemoryWindows {

    fun of(today: LocalDate, oldestYear: Int, zone: ZoneId, maxYears: Int = MemoriesRepository.MAX_YEARS): List<MemoryWindow> {
        val years = (today.year - oldestYear).coerceIn(0, maxYears)
        return (1..years).map { yearsAgo ->
            // 29 février : minusYears ramène au 28, la fenêtre reste centrée.
            val day = today.minusYears(yearsAgo.toLong())
            MemoryWindow(
                yearsAgo = yearsAgo,
                from = day.minusDays(MemoriesRepository.WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli(),
                to = day.plusDays(MemoriesRepository.WINDOW_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
    }
}

/**
 * Personne d'un souvenir « avec » : présente sur au moins [MIN_PHOTOS] médias de la fenêtre
 * (une petite série, pas une photo de groupe isolée), mais sur moins de
 * [MemoriesRepository.DUPLICATE_SHARE] d'entre eux (au-delà, le souvenir général la montre
 * déjà presque partout : on préfère quelqu'un d'autre). Une personne nommée passe avant une
 * personne sans nom, qui doit figurer sur des photos d'au moins [MIN_UNNAMED_MONTHS] mois
 * différents : un proche, pas un inconnu photographié lors d'un seul événement. Puis la
 * plus présente.
 */
object MemoryPeople {
    const val MIN_PHOTOS = 5
    const val MIN_UNNAMED_MONTHS = 3

    fun choose(candidates: List<PersonPresence>, total: Int): PersonPresence? =
        candidates
            .filter { it.photos >= MIN_PHOTOS && it.photos < total * MemoriesRepository.DUPLICATE_SHARE }
            .filter { it.name != null || it.months >= MIN_UNNAMED_MONTHS }
            .minWithOrNull(compareBy<PersonPresence> { it.name == null }.thenByDescending { it.photos }.thenBy { it.personId })
}

/**
 * Souvenirs des années passées, du plus récent au plus ancien. Pour chaque année : le voyage
 * commencé dans la fenêtre s'il y en a un, le souvenir général (sauf si ce voyage le recouvre
 * presque entièrement), puis au plus un souvenir « avec » une personne.
 */
@Singleton
class MemoriesRepository @Inject constructor(
    private val dao: MediaDao,
    private val memoryDao: MemoryDao,
    private val people: PeopleRepository,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun memories(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Memory> =
        withContext(io) {
            val oldest = dao.oldestDateTaken() ?: return@withContext emptyList()
            val windows = MemoryWindows.of(today, Instant.ofEpochMilli(oldest).atZone(zone).year, zone)
            if (windows.isEmpty()) return@withContext emptyList()
            val hidden = settings.settings.first().hiddenFolders.sorted()
            val points = memoryDao.datedPoints(MemoryQueries.datedPoints(MediaQuery(excludedBuckets = hidden)))
            val trips = TripDetector.detect(points, zone)
            // Couvertures déjà prises : deux cartes voisines ne montrent pas la même photo.
            val covers = mutableSetOf<Long>()
            windows.flatMap { window -> memoriesOf(window, trips, hidden, covers) }
        }

    private suspend fun memoriesOf(window: MemoryWindow, trips: List<Trip>, hidden: List<Long>, covers: MutableSet<Long>): List<Memory> {
        val query = MediaQuery(dateFrom = window.from, dateTo = window.to, excludedBuckets = hidden)
        val count = dao.count(MediaQueryBuilder.count(query))
        if (count == 0) return emptyList()
        val memories = mutableListOf<Memory>()
        var general = true
        val trip = trips.filter { it.start >= window.from && it.start < window.to }.maxByOrNull { it.photos }
        val tripMemory = trip?.let { tripMemory(it, window.yearsAgo, hidden, covers) }
        if (trip != null && tripMemory != null) {
            memories += tripMemory
            // Fenêtre presque entièrement passée en voyage : le souvenir général ferait doublon.
            val during = query.copy(dateFrom = trip.start, dateTo = minOf(window.to, trip.end + 1))
            general = dao.count(MediaQueryBuilder.count(during)) < count * DUPLICATE_SHARE
        }
        if (general) memory(MemoryKind.YearsAgo(window.yearsAgo), query, count, covers)?.let(memories::add)
        personMemory(window.yearsAgo, query, count, covers)?.let(memories::add)
        return memories
    }

    /** Du premier au dernier média pris loin de chez soi : ceux de la maison, au départ et au retour, n'en font pas partie. */
    private suspend fun tripMemory(trip: Trip, yearsAgo: Int, hidden: List<Long>, covers: MutableSet<Long>): Memory? {
        val query = MediaQuery(dateFrom = trip.start, dateTo = trip.end + 1, excludedBuckets = hidden)
        val location = TripDetector.locate(memoryDao.tripPlaces(MemoryQueries.tripPlaces(query)), trip.home)
        val kind = MemoryKind.Trip(yearsAgo, location.place, location.country, trip.firstDay, trip.lastDay)
        return memory(kind, query, dao.count(MediaQueryBuilder.count(query)), covers)
    }

    private suspend fun personMemory(yearsAgo: Int, window: MediaQuery, count: Int, covers: MutableSet<Long>): Memory? {
        val presence = MemoryPeople.choose(memoryDao.people(MemoryQueries.people(window)), count) ?: return null
        val face = people.observePerson(presence.personId).first()?.cover
        val kind = MemoryKind.WithPerson(yearsAgo, presence.personId, presence.name, face)
        return memory(kind, window.copy(personId = presence.personId), presence.photos, covers)
    }

    /** Souvenir couvert par la meilleure photo de [query] qu'aucune carte précédente n'utilise. */
    private suspend fun memory(kind: MemoryKind, query: MediaQuery, count: Int, covers: MutableSet<Long>): Memory? {
        if (count == 0) return null
        val candidates = dao.list(MediaQueryBuilder.bestCover(query, limit = COVER_CHOICES))
        val cover = (candidates.firstOrNull { it.id !in covers } ?: candidates.firstOrNull())?.toModel() ?: return null
        covers += cover.id
        return Memory(kind, query, count, cover)
    }

    /**
     * Changements des lieux et des personnes (voir [MemoryDao.observeSignature]), au plus un
     * par [REFRESH_MS] : l'analyse écrit par lots, les souvenirs suivent sans recalcul incessant.
     */
    val changes: Flow<String> = memoryDao.observeSignature().distinctUntilChanged().conflate()
        .transform { signature ->
            emit(signature)
            delay(REFRESH_MS)
        }

    /** Date du jour, réémise à chaque minuit (les souvenirs changent avec elle). */
    val today: Flow<LocalDate> = flow {
        while (true) {
            val day = LocalDate.now()
            emit(day)
            val midnight = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            delay((midnight - System.currentTimeMillis()).coerceAtLeast(1_000L))
        }
    }

    companion object {
        const val WINDOW_DAYS = 3L
        const val MAX_YEARS = 40

        /**
         * Part commune au-delà de laquelle deux cartes feraient doublon : on garde alors la plus
         * large (le souvenir général plutôt qu'une personne présente partout, le voyage plutôt
         * qu'une fenêtre passée tout entière en voyage).
         */
        const val DUPLICATE_SHARE = 0.8

        /** Une fenêtre compte au plus trois cartes : autant de couvertures possibles. */
        private const val COVER_CHOICES = 3

        /** Intervalle minimal entre deux recalculs dus aux lieux et aux personnes. */
        private const val REFRESH_MS = 30_000L
    }
}
