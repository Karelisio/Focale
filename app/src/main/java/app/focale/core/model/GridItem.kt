package app.focale.core.model

import androidx.compose.runtime.Immutable

/** Granularité des en-têtes de date dans une grille. */
enum class GroupMode { AUTO, DAY, MONTH, YEAR, NONE }

/** Niveau effectif d'un en-tête (après résolution de [GroupMode.AUTO]). */
enum class GroupLevel { DAY, MONTH, YEAR }

/** Élément d'une grille paginée : un en-tête de période ou un média. */
@Immutable
sealed interface GridItem {
    val key: String

    /**
     * En-tête de période.
     * @param periodStart début de la période (ms, fuseau local), utilisé pour le libellé.
     */
    @Immutable
    data class Header(
        val level: GroupLevel,
        val periodStart: Long,
        val periodKey: Long,
    ) : GridItem {
        override val key: String = "header-${level.name}-$periodKey"
    }

    @Immutable
    data class Media(val item: MediaItem) : GridItem {
        override val key: String = "media-${item.id}"
    }
}

/** Groupe de la barre de défilement rapide : une période et son nombre de médias. */
@Immutable
data class DateBucket(
    val periodStart: Long,
    val count: Int,
)
