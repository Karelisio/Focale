package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Souvenir : les médias pris autour de la date du jour, [yearsAgo] ans plus tôt. */
@Immutable
data class Memory(
    val yearsAgo: Int,
    val query: MediaQuery,
    val count: Int,
    val cover: MediaItem,
)

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

/** Souvenirs des années passées, du plus récent au plus ancien. */
@Singleton
class MemoriesRepository @Inject constructor(
    private val dao: MediaDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun memories(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Memory> =
        withContext(io) {
            val oldest = dao.oldestDateTaken() ?: return@withContext emptyList()
            val oldestYear = Instant.ofEpochMilli(oldest).atZone(zone).year
            val hidden = settings.settings.first().hiddenFolders.sorted()
            MemoryWindows.of(today, oldestYear, zone).mapNotNull { window ->
                val query = MediaQuery(dateFrom = window.from, dateTo = window.to, excludedBuckets = hidden)
                val count = dao.count(MediaQueryBuilder.count(query))
                if (count == 0) return@mapNotNull null
                val cover = dao.list(MediaQueryBuilder.bestCover(query)).firstOrNull()?.toModel() ?: return@mapNotNull null
                Memory(window.yearsAgo, query, count, cover)
            }
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
    }
}
