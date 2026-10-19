package app.focale.widget

import android.content.Context
import app.focale.R
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.settings.SettingsRepository
import app.focale.core.util.Formatters
import app.focale.data.MediaRepository
import app.focale.data.MemoriesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Photo affichée par le widget, avec sa légende. */
data class WidgetPhoto(val item: MediaItem, val title: String, val subtitle: String)

/**
 * Choisit la photo du widget : un souvenir des années passées en priorité, à défaut un
 * favori, à défaut une photo récente (jamais une capture d'écran). Le choix est
 * déterministe sur une période de [SLOT_HOURS] heures : toutes les instances du widget
 * montrent la même photo, qui change à chaque période.
 */
@Singleton
class WidgetPhotoPicker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memories: MemoriesRepository,
    private val media: MediaRepository,
    private val settings: SettingsRepository,
) {

    suspend fun pick(now: Long = System.currentTimeMillis()): WidgetPhoto? {
        val slot = now / TimeUnit.HOURS.toMillis(SLOT_HOURS)
        val todaysMemories = memories.memories()
        if (todaysMemories.isNotEmpty()) {
            val memory = todaysMemories[spread(slot, todaysMemories.size)]
            candidates(memory.query)?.let { list ->
                val item = list.choose(slot)
                val title = context.resources.getQuantityString(R.plurals.memories_years_ago, memory.yearsAgo, memory.yearsAgo)
                return WidgetPhoto(item, title, date(item))
            }
        }
        val hidden = settings.settings.first().hiddenFolders.sorted()
        candidates(MediaQuery(favoritesOnly = true, excludedBuckets = hidden))?.let { list ->
            val item = list.choose(slot)
            return WidgetPhoto(item, context.getString(R.string.widget_favorite), date(item))
        }
        return candidates(MediaQuery(excludedBuckets = hidden))?.let { list ->
            val item = list.choose(slot)
            WidgetPhoto(item, date(item), item.bucketName)
        }
    }

    private suspend fun candidates(query: MediaQuery): List<MediaItem>? =
        media.list(query.copy(filter = MediaFilter.PHOTOS), limit = CANDIDATES)
            .filterNot { it.isScreenshot }
            .takeIf { it.isNotEmpty() }

    private fun List<MediaItem>.choose(slot: Long): MediaItem = this[spread(slot, size)]

    private fun date(item: MediaItem): String = Formatters.dayMonthYear(context, item.dateTaken)

    companion object {
        const val SLOT_HOURS = 4L
        private const val CANDIDATES = 60

        /** Index pseudo-aléatoire mais stable pour une période donnée. */
        fun spread(slot: Long, size: Int): Int = (((slot * 2_654_435_761L) ushr 7) % size).toInt()
    }
}
