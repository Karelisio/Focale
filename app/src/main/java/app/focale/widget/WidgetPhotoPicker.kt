package app.focale.widget

import android.content.Context
import app.focale.R
import app.focale.core.locale.AppLocales
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.settings.SettingsRepository
import app.focale.core.util.Formatters
import app.focale.data.MediaRepository
import app.focale.data.MemoriesRepository
import app.focale.data.MemoryTitles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Photo affichée par le widget, avec sa légende. */
data class WidgetPhoto(val item: MediaItem, val title: String, val subtitle: String)

/**
 * Choisit la photo du widget : un souvenir du jour en priorité (les mêmes que le carrousel :
 * années passées, personnes, voyages, avec leur titre), à défaut un favori, à défaut une
 * photo récente (jamais une capture d'écran). Le choix est déterministe sur une période de
 * [SLOT_HOURS] heures : toutes les instances du widget montrent la même photo, qui change à
 * chaque période. Légendes (titre, lieu, date) dans la langue de Focale.
 */
@Singleton
class WidgetPhotoPicker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memories: MemoriesRepository,
    private val media: MediaRepository,
    private val settings: SettingsRepository,
    private val locales: AppLocales,
) {

    suspend fun pick(now: Long = System.currentTimeMillis()): WidgetPhoto? {
        val slot = now / TimeUnit.HOURS.toMillis(SLOT_HOURS)
        val text = locales.localized(context)
        val todaysMemories = memories.memories()
        if (todaysMemories.isNotEmpty()) {
            val memory = todaysMemories[spread(slot, todaysMemories.size)]
            candidates(memory.query)?.let { list ->
                val item = list.choose(slot)
                return WidgetPhoto(item, MemoryTitles.title(text.resources, memory.kind), date(text, item))
            }
        }
        val hidden = settings.settings.first().hiddenFolders.sorted()
        candidates(MediaQuery(favoritesOnly = true, excludedBuckets = hidden))?.let { list ->
            val item = list.choose(slot)
            return WidgetPhoto(item, text.getString(R.string.widget_favorite), date(text, item))
        }
        return candidates(MediaQuery(excludedBuckets = hidden))?.let { list ->
            val item = list.choose(slot)
            WidgetPhoto(item, date(text, item), item.bucketName)
        }
    }

    private suspend fun candidates(query: MediaQuery): List<MediaItem>? =
        media.list(query.copy(filter = MediaFilter.PHOTOS), limit = CANDIDATES)
            .filterNot { it.isScreenshot }
            .takeIf { it.isNotEmpty() }

    private fun List<MediaItem>.choose(slot: Long): MediaItem = this[spread(slot, size)]

    private fun date(text: Context, item: MediaItem): String = Formatters.dayMonthYear(text, item.dateTaken)

    companion object {
        const val SLOT_HOURS = 4L
        private const val CANDIDATES = 60

        /** Index pseudo-aléatoire mais stable pour une période donnée. */
        fun spread(slot: Long, size: Int): Int = (((slot * 2_654_435_761L) ushr 7) % size).toInt()
    }
}
