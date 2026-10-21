package app.focale.data

import android.content.res.Resources
import app.focale.R
import app.focale.ml.CountryResolver
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Titres des souvenirs (carrousel, grille du souvenir, widget), dans la langue de l'application. */
object MemoryTitles {

    /** « Il y a 2 ans », « Il y a 1 an avec Alice », « Lisbonne · juin 2023 »… */
    fun title(resources: Resources, kind: MemoryKind): String = when (kind) {
        is MemoryKind.YearsAgo -> resources.getQuantityString(R.plurals.memories_years_ago, kind.yearsAgo, kind.yearsAgo)
        is MemoryKind.WithPerson -> if (kind.name != null) {
            resources.getQuantityString(R.plurals.memories_years_ago_with, kind.yearsAgo, kind.yearsAgo, kind.name)
        } else {
            resources.getQuantityString(R.plurals.memories_years_ago_together, kind.yearsAgo, kind.yearsAgo)
        }
        is MemoryKind.Trip -> resources.getString(R.string.memories_trip, place(resources, kind), months(resources, kind))
    }

    /** Titre sur une carte du carrousel : celui d'un voyage y tient sur deux lignes, lieu puis mois. */
    fun cardTitle(resources: Resources, kind: MemoryKind): String =
        if (kind is MemoryKind.Trip) place(resources, kind) + "\n" + months(resources, kind) else title(resources, kind)

    /** Ville du voyage (dans la langue de l'application), sinon son pays, sinon « Voyage ». */
    private fun place(resources: Resources, kind: MemoryKind.Trip): String =
        kind.place?.display(resources.locale)
            ?: kind.country?.let { code -> runCatching { CountryResolver.displayName(code, resources.locale) }.getOrNull() }
            ?: resources.getString(R.string.memories_trip_somewhere)

    private fun months(resources: Resources, kind: MemoryKind.Trip): String = months(kind.firstDay, kind.lastDay, resources.locale)

    /** Mois d'un séjour : « juin 2023 », « juin – juillet 2023 », « décembre 2022 – janvier 2023 ». */
    fun months(first: LocalDate, last: LocalDate, locale: Locale): String {
        val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        return when {
            first.year != last.year -> monthYear.format(first) + RANGE + monthYear.format(last)
            first.month != last.month -> DateTimeFormatter.ofPattern("MMMM", locale).format(first) + RANGE + monthYear.format(last)
            else -> monthYear.format(first)
        }
    }

    private const val RANGE = " – "

    private val Resources.locale: Locale get() = configuration.locales[0]
}
